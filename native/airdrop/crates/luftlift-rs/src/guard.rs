//! Marionette receive consent and bounded, non-overwriting file storage.
use crate::progress::Sink;
use anyhow::{bail, Result};
use std::{fs::{self, OpenOptions}, io::Write, path::{Path, PathBuf}, sync::atomic::{AtomicU64, Ordering}, time::{Duration, Instant, SystemTime, UNIX_EPOCH}};
/// Upper bound on a single HTTP body (the dvzip-framed /Upload), and on a
/// single decoded file.
///
/// These are memory ceilings, not policy. The receive path holds the body, a
/// de-chunked copy, the inflated CPIO and the extracted file at once — roughly
/// 4× the payload — so 128 MiB here means ~512 MiB peak RSS on the phone.
/// Raising this without first streaming the archive to disk would trade "file
/// too large" errors for OOM kills, which are much harder to explain.
pub const MAX_BODY: usize = 128 * 1024 * 1024;
pub const MAX_EXPANDED: usize = 128 * 1024 * 1024;
static NEXT: AtomicU64 = AtomicU64::new(0);

/// Ask the UI whether to accept a transfer, blocking the /Ask response until
/// it answers (or the deadline passes).
///
/// Two channels carry the prompt to the phone, deliberately redundant:
///
/// * the `<token>.json` file in `MARIONETTE_APPROVAL_DIR`, which the Kotlin
///   service polls — the original mechanism, and the one that keeps working
///   when the progress pipe is not being read;
/// * `request` / `decision` events on the [`Sink`], which reach the floating
///   window instantly instead of on the next 500 ms poll.
///
/// The decision comes back as a `<token>.decision` file containing `accept` or
/// `reject`; a missing file at the deadline means "no answer" and is treated as
/// a rejection, so a wedged UI can never silently accept a transfer.
pub fn approve(req: &crate::http::HttpRequest, sink: &Sink) -> Result<bool> {
    let ask = crate::plist_impl::parse_ask_request(&req.body)?;
    if ask.files.len() > 100 || ask.files.iter().any(|f| f.file_size > MAX_EXPANDED as u64) { return Ok(false); }
    // The Android frontend always supplies a private directory and asks per transfer.
    let dir = match std::env::var_os("MARIONETTE_APPROVAL_DIR") { Some(d) => PathBuf::from(d), None => return Ok(false) };
    let token = format!("{}-{}", SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis(), NEXT.fetch_add(1, Ordering::Relaxed));
    let request = dir.join(format!("{token}.json"));
    let decision = dir.join(format!("{token}.decision"));
    let payload = serde_json::json!({"sender": ask.sender_computer_name, "files": ask.files.iter().map(|f| serde_json::json!({"name":f.file_name,"size":f.file_size})).collect::<Vec<_>>()});
    let tmp = dir.join(format!("{token}.tmp"));
    fs::write(&tmp, serde_json::to_vec(&payload)?)?;
    fs::rename(&tmp, &request)?;
    let total: u64 = ask.files.iter().map(|f| f.file_size).sum();
    let files: Vec<(String, u64)> = ask.files.iter().map(|f| (f.file_name.clone(), f.file_size)).collect();
    sink.request(&token, &ask.sender_computer_name, total, &files);
    let deadline = Instant::now() + Duration::from_secs(45);
    let mut accepted = false;
    while Instant::now() < deadline {
        // Trim: the decision may come from `echo accept > ...` (trailing
        // newline) as easily as from `writeText("accept")`. An exact match
        // would silently turn a shell-written "accept" into a rejection.
        if let Ok(value) = fs::read_to_string(&decision) { accepted = value.trim() == "accept"; break; }
        std::thread::sleep(Duration::from_millis(100));
    }
    let _ = fs::remove_file(request); let _ = fs::remove_file(decision);
    // Also report the timeout case: the UI must dismiss the card either way.
    sink.decision(&token, accepted);
    Ok(accepted)
}

/// Write one received file into `dir`, returning the path actually used.
///
/// Apple prefixes names with a transfer directory (`./photo.jpg`), so the leaf
/// is flattened and never followed. Existing files are never overwritten — a
/// duplicate gets a `1-` prefix — and the returned path is what the UI should
/// show and open, which is why this returns it rather than `()`.
pub fn save(dir: &Path, name: &str, bytes: &[u8]) -> Result<PathBuf> {
    if bytes.len() > MAX_EXPANDED { bail!("file too large"); }
    // Apple prefixes files with a transfer directory. Flatten it; never follow paths.
    let leaf = name.rsplit('/').next().unwrap_or("");
    if leaf.is_empty() || leaf == "." || leaf == ".." || leaf.len() > 180 || leaf.chars().any(|c| c.is_control() || c == '\\') { bail!("invalid received filename"); }
    for suffix in 0..1000 {
        let leaf = if suffix == 0 { leaf.to_string() } else { format!("{suffix}-{leaf}") };
        let path = dir.join(&leaf);
        match OpenOptions::new().write(true).create_new(true).open(&path) {
            Ok(mut file) => { file.write_all(bytes)?; file.sync_all()?; return Ok(path); },
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e.into()),
        }
    }
    bail!("too many duplicate names")
}

/// Require a complete ODC archive before acknowledging receipt.
pub fn complete_cpio(data: &[u8]) -> bool {
    let mut pos = 0usize;
    while let Some(end) = pos.checked_add(76).filter(|end| *end <= data.len()) {
        let h = &data[pos..end];
        if &h[..6] != b"070707" { return false; }
        let octal = |bytes: &[u8]| std::str::from_utf8(bytes).ok().and_then(|s| usize::from_str_radix(s, 8).ok());
        let (name_size, file_size) = match (octal(&h[59..65]), octal(&h[65..76])) {
            (Some(n), Some(f)) if n > 0 && n <= 4096 => (n, f), _ => return false,
        };
        pos = end;
        let name_end = match pos.checked_add(name_size) { Some(e) if e <= data.len() => e, _ => return false };
        let name = &data[pos..name_end];
        if name.last() != Some(&0) { return false; }
        if name == b"TRAILER!!!\0" { return file_size == 0; }
        pos = match name_end.checked_add(file_size) { Some(e) if e <= data.len() => e, _ => return false };
    }
    false
}

#[cfg(test)] pub(crate) mod tests {
    use super::*;

    /// `approve` reads `MARIONETTE_APPROVAL_DIR` from the *process* environment,
    /// so tests that set it must not run concurrently — otherwise one test's
    /// cleanup yanks the variable out from under another's poll loop.
    pub(crate) static ENV_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    #[test] fn refuses_truncated_archive() {
        let data = crate::cpio::build_odc_cpio(&[("photo.jpg", b"jpeg")]);
        assert!(complete_cpio(&data));
        assert!(!complete_cpio(&data[..data.len()-12]));
        assert!(!complete_cpio(b"070707"));
    }
    #[test] fn prevents_traversal_overwrite_and_links() {
        let dir = std::env::temp_dir().join(format!("marionette-storage-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        save(&dir, "../../photo.jpg", b"one").unwrap();
        save(&dir, "photo.jpg", b"two").unwrap();
        assert_eq!(fs::read(dir.join("photo.jpg")).unwrap(), b"one");
        assert_eq!(fs::read(dir.join("1-photo.jpg")).unwrap(), b"two");
        assert!(save(&dir, "..", b"bad").is_err());
        assert!(save(&dir, "a\\b", b"bad").is_err());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test] fn approve_accepts_a_shell_written_decision() {
        // Regression: `echo accept > <token>.decision` writes "accept\n". An
        // exact string match turned that into a rejection, which is exactly
        // what a scripted test harness does — and it silently refused a
        // transfer the user had approved.
        use std::sync::{Arc, Mutex};
        #[derive(Clone, Default)]
        struct Buf(Arc<Mutex<Vec<u8>>>);
        impl std::io::Write for Buf {
            fn write(&mut self, b: &[u8]) -> std::io::Result<usize> { self.0.lock().unwrap().extend_from_slice(b); Ok(b.len()) }
            fn flush(&mut self) -> std::io::Result<()> { Ok(()) }
        }
        let dir = std::env::temp_dir().join(format!("marionette-approve-nl-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let _env = ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        std::env::set_var("MARIONETTE_APPROVAL_DIR", &dir);
        let answer_dir = dir.clone();
        let decider = std::thread::spawn(move || {
            for _ in 0..200 {
                if let Ok(rd) = fs::read_dir(&answer_dir) {
                    for e in rd.flatten() {
                        let name = e.file_name().to_string_lossy().into_owned();
                        if name.ends_with(".json") {
                            fs::write(answer_dir.join(format!("{}.decision", name.trim_end_matches(".json"))), "accept\n").unwrap();
                            return;
                        }
                    }
                }
                std::thread::sleep(std::time::Duration::from_millis(10));
            }
            panic!("no consent prompt appeared");
        });
        let req = crate::http::HttpRequest {
            method: "POST".into(), path: "/Ask".into(),
            headers: std::collections::HashMap::new(),
            body: {
                let mut d = plist::Dictionary::new();
                d.insert("SenderComputerName".into(), plist::Value::String("Mac".into()));
                let mut buf = Vec::new();
                plist::Value::Dictionary(d).to_writer_binary(&mut buf).unwrap();
                buf
            },
        };
        let sink = crate::progress::Sink::to_writer(Buf::default());
        assert!(approve(&req, &sink).unwrap(), "'accept\\n' must be read as an accept");
        decider.join().unwrap();
        std::env::remove_var("MARIONETTE_APPROVAL_DIR");
        fs::remove_dir_all(dir).unwrap();
    }

    #[test] fn save_returns_the_path_it_actually_wrote() {
        // The floating window opens the file the user just accepted, so `save`
        // must report the real destination — including the `N-` prefix a
        // duplicate got, which the caller cannot predict.
        let dir = std::env::temp_dir().join(format!("marionette-path-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let first = save(&dir, "./a.txt", b"1").unwrap();
        assert_eq!(first, dir.join("a.txt"));
        let second = save(&dir, "./a.txt", b"2").unwrap();
        assert_eq!(second, dir.join("1-a.txt"), "duplicate must report the suffixed name");
        assert_eq!(fs::read(&second).unwrap(), b"2");
        fs::remove_dir_all(dir).unwrap();
    }

    #[test] fn approve_emits_request_and_decision_events() {
        // The consent prompt reaches the UI on two channels: the polled
        // `<token>.json` file and the event stream. Verify the stream carries
        // the sender, the per-file sizes and the final decision.
        use std::sync::{Arc, Mutex};
        #[derive(Clone, Default)]
        struct Buf(Arc<Mutex<Vec<u8>>>);
        impl std::io::Write for Buf {
            fn write(&mut self, b: &[u8]) -> std::io::Result<usize> { self.0.lock().unwrap().extend_from_slice(b); Ok(b.len()) }
            fn flush(&mut self) -> std::io::Result<()> { Ok(()) }
        }
        let buf = Buf::default();
        let sink = crate::progress::Sink::to_writer(buf.clone());

        let _env = ENV_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = std::env::temp_dir().join(format!("marionette-approve-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        std::env::set_var("MARIONETTE_APPROVAL_DIR", &dir);

        // Answer the prompt from another thread, the way the Kotlin service
        // does: wait for the .json to appear, then write .decision.
        let answer_dir = dir.clone();
        let decider = std::thread::spawn(move || {
            for _ in 0..200 {
                if let Ok(rd) = fs::read_dir(&answer_dir) {
                    for e in rd.flatten() {
                        let name = e.file_name().to_string_lossy().into_owned();
                        if name.ends_with(".json") {
                            let token = name.trim_end_matches(".json");
                            // Trailing newline on purpose: `echo accept > f` is
                            // the obvious way to answer from a shell, and it must
                            // still count as an accept.
                            fs::write(answer_dir.join(format!("{token}.decision")), "accept\n").unwrap();
                            return;
                        }
                    }
                }
                std::thread::sleep(std::time::Duration::from_millis(10));
            }
            panic!("no consent prompt appeared");
        });

        let req = crate::http::HttpRequest {
            method: "POST".into(),
            path: "/Ask".into(),
            headers: std::collections::HashMap::new(),
            body: {
                let mut d = plist::Dictionary::new();
                d.insert("SenderComputerName".into(), plist::Value::String("Mac".into()));
                d.insert("SenderModelName".into(), plist::Value::String("MacBookPro".into()));
                let mut f = plist::Dictionary::new();
                f.insert("FileName".into(), plist::Value::String("a.jpg".into()));
                f.insert("FileSize".into(), plist::Value::Integer(1234.into()));
                d.insert("Files".into(), plist::Value::Array(vec![plist::Value::Dictionary(f)]));
                let mut buf = Vec::new();
                plist::Value::Dictionary(d).to_writer_binary(&mut buf).unwrap();
                buf
            },
        };

        assert!(approve(&req, &sink).unwrap(), "an accept answer must be honoured");
        decider.join().unwrap();

        let raw = String::from_utf8(buf.0.lock().unwrap().clone()).unwrap();
        let events: Vec<serde_json::Value> = raw.lines().map(|l| serde_json::from_str(l).unwrap()).collect();
        assert_eq!(events[0]["event"], "request");
        assert_eq!(events[0]["sender"], "Mac");
        assert_eq!(events[0]["total"], 1234);
        assert_eq!(events[0]["files"][0]["name"], "a.jpg");
        assert_eq!(events[1]["event"], "decision");
        assert_eq!(events[1]["accepted"], true);
        assert_eq!(events[1]["token"], events[0]["token"], "decision must echo the prompt's token");

        std::env::remove_var("MARIONETTE_APPROVAL_DIR");
        fs::remove_dir_all(dir).unwrap();
    }
}
