//! Machine-readable progress events for the Android front-end.
//!
//! ## Why stdout, and why JSON lines
//!
//! The whole point of this module is that a *phone UI* has to render what the
//! transfer is doing — a floating window with an accept prompt and a progress
//! bar, and a send sheet with its own bar. Neither is possible from the
//! human-readable logs, and scraping `tracing` output is brittle (it is
//! ANSI-free but free-form). So the binary gets a second, deliberately dumb
//! output channel:
//!
//! * **stderr** keeps the human logs (`tracing_subscriber::fmt` is installed
//!   with `.with_writer(std::io::stderr)` in `main.rs`),
//! * **stdout** carries one JSON object per line, and *nothing else*.
//!
//! That split means the Android side can read stdout with a plain
//! `BufferedReader`, parse every line as JSON, and never have to filter log
//! noise. It also means a human running `luftlift send` in a terminal still
//! sees the logs, while `| jq` gets clean events.
//!
//! ## The event set
//!
//! Every line has an `event` key. The set is closed and documented in
//! `native/airdrop/PROGRESS.md`, which is the contract the Kotlin side codes
//! against. Adding an event is fine; *renaming* one breaks the UI.
//!
//! ```text
//! listening  {name, port}                       HTTPS listener bound, not yet announced
//! ready      {name, port}                       receiver is listening + announcing
//! discover   {sender}                           an Apple device POSTed /Discover
//! request    {token, sender, total, files[]}    consent prompt (blocks the /Ask)
//! decision   {token, accepted}                  the prompt was answered
//! receiving  {total}                            /Upload body started arriving
//! progress   {received, total}                  throttled byte counter
//! received   {dir, total, files[]}              files are on disk
//! browsing   {duration}                         send: mDNS browse started
//! peer       {name, address, port}              send/find: a peer resolved
//! lost       {name}                             find --watch: a peer went away
//! stage      {stage}                            send: discover | ask | upload
//! sent       {bytes, peer}                      send: transfer finished
//! error      {message}                          anything fatal
//! ```
//!
//! ## Throttling
//!
//! A 5 MB upload produces ~80 socket writes of 64 KiB; a 5 GB one produces
//! 80 000. Emitting every one would drown the pipe (and the UI). [`Sink::progress`]
//! therefore drops an event unless *either* the whole-number percentage moved
//! *or* [`PROGRESS_MIN_INTERVAL`] elapsed — with the last update always
//! forced through so a bar never sticks at 99%.

use serde_json::{json, Value};
use std::io::Write;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Minimum wall-clock gap between two `progress` events for the same transfer.
/// 250 ms is comfortably below the ~60 Hz a progress bar can show and well
/// above the cost of a write+flush per 64 KiB chunk on a slow link.
pub const PROGRESS_MIN_INTERVAL: Duration = Duration::from_millis(250);

/// Byte-progress throttle state. Public only so [`should_emit`] can be tested
/// as a pure function; nothing outside this module should touch it.
#[derive(Debug, Default)]
pub struct Throttle {
    /// Last emitted whole-number percentage, or -1 when the total is unknown.
    pub last_percent: i64,
    /// When the last event went out.
    pub last_at: Option<Instant>,
    /// False until the first event of the current transfer.
    pub started: bool,
}

/// A JSON-lines event sink. Cheap to clone via `Arc`; disabled sinks are
/// allocation-free no-ops so the library stays usable without a UI.
pub struct Sink {
    enabled: bool,
    out: Mutex<Box<dyn Write + Send>>,
    throttle: Mutex<Throttle>,
    /// Set once an `error` event has been emitted, so a caller that reports
    /// the same failure from several layers only surfaces it once.
    errored: AtomicBool,
}

impl std::fmt::Debug for Sink {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Sink").field("enabled", &self.enabled).finish()
    }
}

impl Sink {
    /// A sink that swallows everything. Used by every code path that has no
    /// UI attached (library callers, most unit tests).
    pub fn disabled() -> Arc<Self> {
        Arc::new(Self {
            enabled: false,
            out: Mutex::new(Box::new(std::io::sink())),
            throttle: Mutex::new(Throttle::default()),
            errored: AtomicBool::new(false),
        })
    }

    /// A sink that writes JSON lines to stdout — the production channel.
    pub fn stdout() -> Arc<Self> {
        Arc::new(Self {
            enabled: true,
            out: Mutex::new(Box::new(std::io::stdout())),
            throttle: Mutex::new(Throttle::default()),
            errored: AtomicBool::new(false),
        })
    }

    /// A sink over an arbitrary writer. For tests, and for the odd case of a
    /// caller that wants the event stream in a file instead of a pipe.
    pub fn to_writer(w: impl Write + Send + 'static) -> Arc<Self> {
        Arc::new(Self {
            enabled: true,
            out: Mutex::new(Box::new(w)),
            throttle: Mutex::new(Throttle::default()),
            errored: AtomicBool::new(false),
        })
    }

    /// `enabled` selects stdout vs. a disabled sink — the shape `main.rs`
    /// needs for `--progress` / `MARIONETTE_PROGRESS`.
    pub fn from_flag(enabled: bool) -> Arc<Self> {
        if enabled {
            Self::stdout()
        } else {
            Self::disabled()
        }
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }

    /// Emit one event. Never panics and never propagates an IO error: a broken
    /// progress pipe (the UI died) must not take the transfer down with it.
    pub fn emit(&self, event: &str, mut fields: Value) {
        if !self.enabled {
            return;
        }
        if let Some(obj) = fields.as_object_mut() {
            obj.insert("event".to_string(), Value::String(event.to_string()));
        }
        let mut line = fields.to_string();
        line.push('\n');
        if let Ok(mut out) = self.out.lock() {
            // One write per line so a reader never sees a half event, then
            // flush: the UI is a *live* view, buffering it defeats the point.
            let _ = out.write_all(line.as_bytes());
            let _ = out.flush();
        }
    }

    // ---- receiver-side events ----

    /// The HTTPS listener is bound: we can accept a transfer, but we are not
    /// yet visible to Apple devices.
    ///
    /// This exists because "listening" and "announced" are genuinely different
    /// states and the gap between them can be permanent — if `awdl0` never gets
    /// an address, mDNS announce never fires while the listener sits happily on
    /// [::]:8771. Without this event the UI cannot tell "starting" from
    /// "up but invisible", which is exactly the failure a broken radio
    /// produces. Emitted before [`Sink::ready`].
    pub fn listening(&self, name: &str, port: u16) {
        self.emit("listening", json!({"name": name, "port": port}));
    }

    /// The receiver is listening and announcing; the phone can drop its
    /// "starting" spinner.
    pub fn ready(&self, name: &str, port: u16) {
        self.emit("ready", json!({"name": name, "port": port}));
    }

    /// An Apple device ran /Discover against us (it has opened its share pane
    /// and is looking at the picker). Purely informational, but it is the
    /// first sign of life and worth showing while debugging "why don't I see
    /// the Mac".
    pub fn discover(&self, sender: &str) {
        self.emit("discover", json!({"sender": sender}));
    }

    /// A consent prompt is now open. `files` is `[{"name","size"}]`.
    pub fn request(&self, token: &str, sender: &str, total: u64, files: &[(String, u64)]) {
        let files: Vec<Value> = files
            .iter()
            .map(|(n, s)| json!({"name": n, "size": s}))
            .collect();
        self.emit(
            "request",
            json!({"token": token, "sender": sender, "total": total, "files": files}),
        );
    }

    /// The user answered a prompt (or it timed out — `accepted: false`).
    pub fn decision(&self, token: &str, accepted: bool) {
        self.emit("decision", json!({"token": token, "accepted": accepted}));
    }

    /// The /Upload body started arriving. `total` is 0 when the sender used
    /// `Transfer-Encoding: chunked` (Apple always does) — the length is simply
    /// not on the wire, so the UI must render an indeterminate bar.
    pub fn receiving(&self, total: u64) {
        self.reset_throttle();
        self.emit("receiving", json!({"total": total}));
    }

    /// Throttled byte counter. `total == 0` means "unknown"; in that case the
    /// percentage rule is skipped and only the time rule applies.
    pub fn progress(&self, received: u64, total: u64) {
        if !self.enabled {
            return;
        }
        let now = Instant::now();
        let force = total > 0 && received >= total;
        let emit = {
            let mut t = match self.throttle.lock() {
                Ok(t) => t,
                Err(_) => return,
            };
            let decision = should_emit(&t, now, received, total);
            if decision || force {
                t.started = true;
                t.last_percent = percent(received, total);
                t.last_at = Some(now);
            }
            decision || force
        };
        if emit {
            self.emit("progress", json!({"received": received, "total": total}));
        }
    }

    /// Files are on disk. `dir` is where the phone should look.
    pub fn received(&self, dir: &str, total: u64, files: &[(String, u64, String)]) {
        let files: Vec<Value> = files
            .iter()
            .map(|(n, s, p)| json!({"name": n, "size": s, "path": p}))
            .collect();
        self.emit(
            "received",
            json!({"dir": dir, "total": total, "files": files}),
        );
    }

    // ---- sender-side events ----

    pub fn browsing(&self, duration_secs: u64) {
        self.emit("browsing", json!({"duration": duration_secs}));
    }

    /// A peer resolved. Shared by `send` (the chosen target) and
    /// `find --watch` (every peer as it appears).
    pub fn peer(&self, name: &str, address: &str, port: u16) {
        self.emit(
            "peer",
            json!({"name": name, "address": address, "port": port}),
        );
    }

    /// `find --watch`: a peer stopped responding (TTL ran out or it sent a
    /// goodbye). The UI removes the row.
    pub fn lost(&self, name: &str) {
        self.emit("lost", json!({"name": name}));
    }

    /// Which HTTP leg the sender is on: `discover`, `ask` or `upload`.
    pub fn stage(&self, stage: &str) {
        self.reset_throttle();
        self.emit("stage", json!({"stage": stage}));
    }

    pub fn sent(&self, bytes: u64, peer: &str) {
        self.emit("sent", json!({"bytes": bytes, "peer": peer}));
    }

    /// Fatal error. Emitted at most once per sink so a failure reported by
    /// both `send_files` and `main` does not show twice in the UI.
    pub fn error(&self, message: &str) {
        if self.errored.swap(true, Ordering::SeqCst) {
            return;
        }
        self.emit("error", json!({"message": message}));
    }

    fn reset_throttle(&self) {
        if let Ok(mut t) = self.throttle.lock() {
            *t = Throttle::default();
        }
    }
}

/// Whole-number percentage of `received`/`total`, or `-1` when `total` is
/// unknown. `-1` never equals a real percentage, so the "did it move" test
/// degrades to the time-based rule for chunked uploads.
fn percent(received: u64, total: u64) -> i64 {
    if total == 0 {
        return -1;
    }
    ((received.min(total) as u128 * 100) / total as u128) as i64
}

/// Pure throttle decision — split out so the rate-limiting policy is testable
/// without sleeping or writing to a pipe.
///
/// Emit when: the percentage moved, or the minimum interval elapsed, or this
/// is the first progress of the transfer.
pub fn should_emit(t: &Throttle, now: Instant, received: u64, total: u64) -> bool {
    if !t.started {
        return true;
    }
    let pct = percent(received, total);
    // Unknown total: percentages are meaningless, so fall back to bytes moved
    // *and* the time rule — a fast link still gets a steady stream of updates.
    if pct != t.last_percent && pct >= 0 {
        return true;
    }
    match t.last_at {
        Some(at) => now.saturating_duration_since(at) >= PROGRESS_MIN_INTERVAL,
        None => true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A `Write` that keeps everything in an `Arc<Mutex<Vec<u8>>>` so the test
    /// can read back what the sink emitted.
    #[derive(Clone, Default)]
    struct SharedBuf(Arc<Mutex<Vec<u8>>>);

    impl Write for SharedBuf {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.0.lock().unwrap().extend_from_slice(buf);
            Ok(buf.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    fn sink() -> (Arc<Sink>, SharedBuf) {
        let buf = SharedBuf::default();
        (Sink::to_writer(buf.clone()), buf)
    }

    fn lines(buf: &SharedBuf) -> Vec<Value> {
        let raw = buf.0.lock().unwrap().clone();
        String::from_utf8(raw)
            .unwrap()
            .lines()
            .map(|l| serde_json::from_str(l).expect("every line must be valid JSON"))
            .collect()
    }

    #[test]
    fn disabled_sink_writes_nothing() {
        // The library must stay usable with no UI attached: every helper is a
        // no-op, and nothing allocates or panics.
        let sink = Sink::disabled();
        assert!(!sink.is_enabled());
        sink.ready("x", 8771);
        sink.progress(1, 2);
        sink.error("boom");
        // Nothing to assert on the writer (it is io::sink) — the contract is
        // "does not panic / does not require a writer".
    }

    #[test]
    fn every_event_is_one_json_object_per_line() {
        let (sink, buf) = sink();
        sink.listening("Marionette 小米 13", 8771);
        sink.ready("Marionette 小米 13", 8771);
        sink.discover("Andrew's MacBook Pro");
        sink.request("1730-0", "iPhone", 2048, &[("a.jpg".into(), 2048)]);
        sink.decision("1730-0", true);
        sink.receiving(2048);
        sink.received("/sdcard/AirDrop", 2048, &[("a.jpg".into(), 2048, "/sdcard/AirDrop/a.jpg".into())]);
        sink.browsing(5);
        sink.peer("Mac", "fe80::1", 8771);
        sink.lost("Mac");
        sink.stage("upload");
        sink.sent(2048, "Mac");
        sink.error("nope");

        let raw = String::from_utf8(buf.0.lock().unwrap().clone()).unwrap();
        assert!(raw.ends_with('\n'), "the last event must be newline-terminated");
        assert_eq!(raw.matches('\n').count(), 13, "one line per event");

        let parsed = lines(&buf);
        let names: Vec<&str> = parsed
            .iter()
            .map(|v| v["event"].as_str().unwrap())
            .collect();
        assert_eq!(
            names,
            [
                "listening", "ready", "discover", "request", "decision", "receiving", "received",
                "browsing", "peer", "lost", "stage", "sent", "error"
            ]
        );
        // Non-ASCII must survive as real UTF-8, not escaped bytes, so the
        // Kotlin reader gets the same string the user sees in the picker.
        assert_eq!(parsed[0]["name"], "Marionette 小米 13");
        assert_eq!(parsed[2]["sender"], "Andrew's MacBook Pro");
    }

    #[test]
    fn request_event_carries_per_file_metadata() {
        // The floating window renders one row per file, so name+size must be
        // on the wire, not just the aggregate.
        let (sink, buf) = sink();
        sink.request(
            "T",
            "Mac",
            300,
            &[("a.jpg".into(), 100), ("b.mov".into(), 200)],
        );
        let v = &lines(&buf)[0];
        assert_eq!(v["total"], 300);
        assert_eq!(v["files"][0]["name"], "a.jpg");
        assert_eq!(v["files"][0]["size"], 100);
        assert_eq!(v["files"][1]["name"], "b.mov");
        assert_eq!(v["files"][1]["size"], 200);
    }

    #[test]
    fn error_is_emitted_only_once() {
        // A failure surfaces from both the transfer and main(); the UI must
        // show one error card, not two.
        let (sink, buf) = sink();
        sink.error("first");
        sink.error("second");
        let parsed = lines(&buf);
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0]["message"], "first");
    }

    #[test]
    fn progress_is_throttled_by_percentage() {
        // Same instant, same whole percent → dropped. A whole percent that
        // moves → emitted immediately even at the same instant.
        let (sink, buf) = sink();
        sink.receiving(1000);
        sink.progress(0, 1000); // first: always emitted
        sink.progress(1, 1000); // still 0% → dropped
        sink.progress(9, 1000); // still 0% → dropped
        sink.progress(10, 1000); // 1% → emitted
        let parsed = lines(&buf);
        let progress: Vec<&Value> = parsed
            .iter()
            .filter(|v| v["event"] == "progress")
            .collect();
        assert_eq!(progress.len(), 2, "0% then 1% only: {:?}", parsed);
        assert_eq!(progress[0]["received"], 0);
        assert_eq!(progress[1]["received"], 10);
    }

    #[test]
    fn progress_always_emits_the_final_byte() {
        // 99% → 100% must land, otherwise the bar sticks one tick short.
        let (sink, buf) = sink();
        sink.receiving(1000);
        sink.progress(990, 1000);
        sink.progress(1000, 1000);
        let parsed = lines(&buf);
        let last = parsed.last().unwrap();
        assert_eq!(last["event"], "progress");
        assert_eq!(last["received"], 1000);
    }

    #[test]
    fn unknown_total_falls_back_to_the_time_rule() {
        // Chunked uploads have no length on the wire; percentages are
        // meaningless, so only the interval gate applies. Verify a burst of
        // same-instant updates collapses to one event.
        let (sink, buf) = sink();
        sink.receiving(0);
        for n in 0..20 {
            sink.progress(n * 65536, 0);
        }
        let parsed = lines(&buf);
        let progress = parsed.iter().filter(|v| v["event"] == "progress").count();
        assert_eq!(progress, 1, "all 20 same-instant updates collapse: {:?}", parsed);
    }

    #[test]
    fn should_emit_honours_the_interval() {
        let now = Instant::now();
        let mut t = Throttle {
            started: true,
            last_percent: 0,
            last_at: Some(now),
        };
        // Same percentage, no time passed → no.
        assert!(!should_emit(&t, now, 1, 1000));
        // Same percentage, interval elapsed → yes (a stalled transfer still
        // has to prove it is alive).
        assert!(should_emit(&t, now + PROGRESS_MIN_INTERVAL, 1, 1000));
        // Percentage moved at the same instant → yes.
        assert!(should_emit(&t, now, 500, 1000));
        // Unknown total, same instant → no.
        t.last_percent = -1;
        assert!(!should_emit(&t, now, 500, 0));
    }

    #[test]
    fn percent_handles_unknown_and_overshoot() {
        assert_eq!(percent(0, 0), -1);
        assert_eq!(percent(5, 0), -1);
        assert_eq!(percent(0, 100), 0);
        assert_eq!(percent(100, 100), 100);
        // A sender that lies about its length must not produce 150%.
        assert_eq!(percent(150, 100), 100);
        // Large transfers must not overflow (u128 intermediate).
        assert_eq!(percent(u64::MAX, u64::MAX), 100);
    }

    #[test]
    fn stage_resets_the_throttle() {
        // Each HTTP leg starts its own 0→100% curve; without a reset the
        // upload's first event would be compared against the ask's counters.
        let (sink, buf) = sink();
        sink.receiving(1000);
        sink.progress(1000, 1000);
        sink.stage("upload");
        sink.progress(0, 1000);
        let parsed = lines(&buf);
        let progress: Vec<&Value> = parsed
            .iter()
            .filter(|v| v["event"] == "progress")
            .collect();
        assert_eq!(progress.len(), 2, "post-reset 0% must be emitted: {:?}", parsed);
        assert_eq!(progress[1]["received"], 0);
    }
}
