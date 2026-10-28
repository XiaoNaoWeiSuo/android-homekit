//! AirDrop HTTPS server — the accept loop that ties TLS + HTTP parsing +
//! handlers together. The per-connection logic is extracted as a pure
//! function over `BufRead + Write` so it's unit-testable without sockets.
//!
//! Reference: `opendrop-patch/server.py.patched` (`start_server`, `do_POST`).

use crate::handlers::{HandleResult, handle_post};
use crate::http::{HttpRequest, format_response, parse_request};
use crate::introspect::IntrospectState;
use crate::plist_impl::ReceiverConfig;
use crate::progress::Sink;
use anyhow::Result;
use rustls::ServerConfig;
use std::io::{BufRead, BufReader, Write, Read};
use std::net::{IpAddr, Ipv6Addr, SocketAddr, TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Instant, SystemTime, UNIX_EPOCH};
use tracing::{error, info, warn};

/// How much of an upload body to pull off the socket between progress events.
/// Large enough that the syscall overhead is irrelevant on a 1 Gbps link,
/// small enough that the on-screen bar moves smoothly on a slow AWDL one.
const RECV_CHUNK: usize = 64 * 1024;

/// How long an approved `/Ask` authorizes a following `/Upload`.
///
/// The sender may take a moment between accepting and shipping the archive, but
/// it will not take two minutes. The window also bounds how long a stale
/// approval could be replayed.
const APPROVAL_TTL: std::time::Duration = std::time::Duration::from_secs(120);

/// Per-peer approval state, shared across connections.
///
/// Both keep-alive and reconnecting senders consume this same registry.
/// A local connection flag must never bypass consumption: doing so leaves
/// a second reusable grant after an upload on the original connection.
type Approvals = Arc<Mutex<std::collections::HashMap<IpAddr, Instant>>>;

/// Record that `peer` just had a transfer approved.
fn grant_approval(approvals: &Approvals, peer: IpAddr) {
    if let Ok(mut map) = approvals.lock() {
        // Opportunistic cleanup: this map holds one entry per peer that ever
        // sent something, which on a shared link is unbounded over time.
        map.retain(|_, at| at.elapsed() < APPROVAL_TTL);
        map.insert(peer, Instant::now());
    }
}

/// Consume an approval for `peer`, if one is still valid.
///
/// Consuming (rather than just checking) means one `/Ask` authorizes exactly one
/// `/Upload`; a replayed upload would otherwise be accepted forever within the
/// TTL.
fn take_approval(approvals: &Approvals, peer: IpAddr) -> bool {
    match approvals.lock() {
        Ok(mut map) => match map.remove(&peer) {
            Some(at) => at.elapsed() < APPROVAL_TTL,
            None => false,
        },
        Err(_) => false,
    }
}

/// Handle a single HTTP request from `reader`, write the response to `writer`.
/// Pure (no sockets) — works with in-memory buffers for testing.
///
/// If the request carries `Expect: 100-continue`, an interim
/// `HTTP/1.1 100 Continue` line is written to `writer` *before* the body is
/// read — mirroring `opendrop-patch/server.py.patched::handle_upload`. Without
/// this, an Apple sender that waits for the 100 before shipping the body
/// deadlocks against a server blocked reading the body.
pub fn handle_one_connection(
    reader: &mut impl BufRead,
    writer: &mut impl Write,
    cfg: &ReceiverConfig,
) -> Result<HandleResult> {
    handle_one_connection_with(reader, writer, cfg, None)
}

/// As [`handle_one_connection`], but reporting byte progress for the body to
/// `sink`. The UI needs this: `/Upload` can take minutes on a slow AWDL link
/// and a floating window with no bar is indistinguishable from a hang.
pub fn handle_one_connection_with(
    reader: &mut impl BufRead,
    writer: &mut impl Write,
    cfg: &ReceiverConfig,
    sink: Option<&Sink>,
) -> Result<HandleResult> {
    let raw = read_request_respecting_expect_with(reader, writer, sink)?;
    let req = parse_request(&raw)?;
    if let Some(sink) = sink {
        announce_discover(&req, sink);
    }
    let result = handle_post(&req, cfg);
    let response_bytes = format_response(&result.response);
    writer.write_all(&response_bytes)?;
    Ok(result)
}

/// Report a `/Discover` to the UI. Shared by both connection paths so the
/// event set does not depend on which one served the request.
///
/// `/Discover` is the earliest identity signal in the AirDrop flow — it arrives
/// while the user is still scrolling the picker, well before `/Ask` — so it is
/// what lets the phone say "MacBook Pro 正在查看" instead of showing nothing
/// until a transfer is offered.
pub fn announce_discover(req: &HttpRequest, sink: &Sink) {
    if req.path != "/Discover" {
        return;
    }
    let who = crate::plist_impl::parse_discover_request(&req.body)
        .ok()
        .map(|d| d.computer_name)
        .filter(|n| !n.is_empty())
        .unwrap_or_else(|| "unknown".to_string());
    sink.discover(&who);
}

/// Read a complete HTTP/1.1 request (headers + body) from a `BufRead`,
/// without handling `Expect: 100-continue`. Kept for callers/tests that don't
/// need the interim response.
pub fn read_full_request(reader: &mut impl BufRead) -> Result<Vec<u8>> {
    let header_bytes = read_headers(reader)?;
    let body = read_body(reader, &header_bytes, None)?;
    let mut full = header_bytes;
    full.extend_from_slice(&body);
    Ok(full)
}

/// Read a complete HTTP/1.1 request. If the headers carry
/// `Expect: 100-continue`, write `HTTP/1.1 100 Continue\r\n\r\n` to `writer`
/// **before** reading the body, so a peer waiting on the interim response
/// ships the body. This is the counterpart to the Python upload handler's
/// `send_response(100)` branch.
pub fn read_request_respecting_expect(
    reader: &mut impl BufRead,
    writer: &mut impl Write,
) -> Result<Vec<u8>> {
    read_request_respecting_expect_with(reader, writer, None)
}

/// As [`read_request_respecting_expect`], with progress reporting.
pub fn read_request_respecting_expect_with(
    reader: &mut impl BufRead,
    writer: &mut impl Write,
    sink: Option<&Sink>,
) -> Result<Vec<u8>> {
    let header_bytes = read_headers(reader)?;
    let head_str = std::str::from_utf8(&header_bytes).unwrap_or("");
    if let Some(expect) = parse_header_value(head_str, "expect") {
        if expect.eq_ignore_ascii_case("100-continue") {
            writer.write_all(b"HTTP/1.1 100 Continue\r\n\r\n")?;
            // CRITICAL: flush so the 100 Continue actually reaches the peer.
            // On the TLS path rustls buffers writes internally; without an
            // explicit flush the 100 Continue is never sent, the Apple sender
            // waits forever for it, and the connection deadlocks.
            writer.flush()?;
        }
    }
    let body = read_body(reader, &header_bytes, sink)?;
    let mut full = header_bytes;
    full.extend_from_slice(&body);
    Ok(full)
}

/// Read the HTTP/1.1 header section up to and including the `\r\n\r\n`
/// terminator. Returns the raw header bytes (including the terminator).
fn read_headers(reader: &mut impl BufRead) -> Result<Vec<u8>> {
    let mut buf = Vec::new();
    loop {
        let mut line = Vec::new();
        let n = reader.take(8193).read_until(b'\n', &mut line)?;
        anyhow::ensure!(buf.len() + line.len() <= 8192, "HTTP headers too large");
        if n == 0 {
            break;
        }
        buf.extend_from_slice(&line);
        if buf.ends_with(b"\r\n\r\n") {
            break;
        }
    }
    if buf.is_empty() {
        anyhow::bail!("connection closed before headers complete");
    }
    Ok(buf)
}

/// Read the body following already-parsed `header_bytes`. Dispatches on
/// `Content-Length` (exact read) or `Transfer-Encoding: chunked` (raw chunk
/// framing preserved for downstream `parse_request`).
///
/// When a `sink` is supplied **and this is an /Upload**, the body is pulled off
/// the socket in [`RECV_CHUNK`] pieces and a throttled `progress` event goes out
/// between them. This is the only place that can report receive progress: by the
/// time `handle_post` runs, the whole body is already in memory, so a UI hooked
/// there could only ever show 0 % then 100 %.
fn read_body(reader: &mut impl BufRead, header_bytes: &[u8], sink: Option<&Sink>) -> Result<Vec<u8>> {
    let head_str = std::str::from_utf8(header_bytes).unwrap_or("");
    // Progress is a property of the *transfer*, which is /Upload. /Discover and
    // /Ask also carry bodies (a few hundred bytes of plist), and reporting
    // those would pop a progress card on the phone every time a device merely
    // looks at us.
    let is_transfer = head_str.starts_with("POST /Upload ");
    let sink = if is_transfer { sink } else { None };
    if let Some(cl_str) = parse_header_value(head_str, "content-length") {
        let len: usize = cl_str.parse()?;
        anyhow::ensure!(len <= crate::guard::MAX_BODY, "body too large");
        // Content-Length is on the wire, so the bar can be determinate.
        if let Some(sink) = sink {
            sink.receiving(len as u64);
        }
        let mut body = vec![0u8; len];
        read_exact_with_progress(reader, &mut body, sink)?;
        Ok(body)
    } else if let Some(te_str) = parse_header_value(head_str, "transfer-encoding") {
        if te_str.to_ascii_lowercase().contains("chunked") {
            // Apple always sends chunked /Upload bodies, which carry no total
            // length. `receiving(0)` tells the UI to render an indeterminate
            // bar rather than a wrong percentage.
            if let Some(sink) = sink {
                sink.receiving(0);
            }
            Ok(read_chunked_stream(reader, sink)?)
        } else {
            Ok(Vec::new())
        }
    } else {
        Ok(Vec::new())
    }
}

/// `read_exact` in [`RECV_CHUNK`] steps, reporting cumulative bytes after each
/// one. Reports only what actually arrived, so a truncated body never shows a
/// full bar.
fn read_exact_with_progress(
    reader: &mut impl Read,
    buf: &mut [u8],
    sink: Option<&Sink>,
) -> Result<()> {
    let total = buf.len() as u64;
    let mut done = 0usize;
    while done < buf.len() {
        let want = (buf.len() - done).min(RECV_CHUNK);
        reader.read_exact(&mut buf[done..done + want])?;
        done += want;
        if let Some(sink) = sink {
            sink.progress(done as u64, total);
        }
    }
    Ok(())
}

/// Bump the introspection counters for an incoming AirDrop request. Pure
/// (no I/O) so it's unit-testable without sockets. Decodes the `/Ask` body
/// for the sender name; if decoding fails the counter is still bumped, just
/// without updating `last_peer`.
pub fn bump_introspect_for_request(state: &IntrospectState, req: &HttpRequest) {
    match req.path.as_str() {
        "/Discover" => state.bump_discover(),
        "/Ask" => {
            let sender = crate::plist_impl::parse_ask_request(&req.body)
                .ok()
                .map(|a| a.sender_computer_name);
            state.bump_ask(sender.as_deref());
        }
        "/Upload" => state.bump_upload(),
        _ => {}
    }
}

/// Serve forever on the given listener. Each connection is handled in a
/// thread. If `output_dir` is set, received files are saved there.
///
/// `introspect` is the shared receiver-state snapshot bumped on every
/// `/Discover`, `/Ask`, `/Upload` POST so the loopback introspect API
/// (`GET /status`) can report how far each Apple sender has progressed.
///
/// If the `LUFTLIFT_DUMP_UPLOAD` env var is set to a directory, each /Upload
/// raw body is also written there as `upload-<unix_ts>-<sender>.bin` for
/// offline decode diffing against the Python reference (`dvzip.py`). The
/// sender name is tracked across the per-connection AirDrop flow via a
/// shared `last_sender` cell updated when /Ask arrives.
pub fn serve_forever(
    listener: TcpListener,
    tls_config: Arc<ServerConfig>,
    cfg: ReceiverConfig,
    output_dir: Option<PathBuf>,
    introspect: Arc<IntrospectState>,
    sink: Arc<Sink>,
) {
    let cfg = Arc::new(cfg);
    let output_dir = Arc::new(output_dir);
    let dump_dir = dump_upload_dir();
    let last_sender: Arc<Mutex<String>> = Arc::new(Mutex::new(String::new()));
    let approvals: Approvals = Arc::new(Mutex::new(std::collections::HashMap::new()));
    let active = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    for stream in listener.incoming() {
        match stream {
            Ok(tcp_stream) => {
                if active.fetch_add(1, std::sync::atomic::Ordering::SeqCst) >= 8 {
                    active.fetch_sub(1, std::sync::atomic::Ordering::SeqCst); continue;
                }
                let active = active.clone();
                let tls_config = tls_config.clone();
                let cfg = cfg.clone();
                let output_dir = output_dir.clone();
                let dump_dir = dump_dir.clone();
                let last_sender = last_sender.clone();
                let introspect = introspect.clone();
                let sink = sink.clone();
                let approvals = approvals.clone();
                thread::spawn(move || {
                    if let Err(e) = handle_tls_connection(
                        tcp_stream,
                        tls_config,
                        cfg,
                        output_dir,
                        dump_dir,
                        last_sender,
                        introspect,
                        sink,
                        approvals,
                    ) {
                        warn!(error = %e, "connection error");
                    }
                    active.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
                });
            }
            Err(e) => error!(error = %e, "accept error"),
        }
    }
}

#[allow(clippy::too_many_arguments)] // Shared connection/consent state; kept explicit at the worker boundary.
fn handle_tls_connection(
    tcp_stream: TcpStream,
    tls_config: Arc<ServerConfig>,
    cfg: Arc<ReceiverConfig>,
    output_dir: Arc<Option<PathBuf>>,
    dump_dir: Option<PathBuf>,
    last_sender: Arc<Mutex<String>>,
    introspect: Arc<IntrospectState>,
    sink: Arc<Sink>,
    approvals: Approvals,
) -> Result<()> {
    let peer = tcp_stream.peer_addr()?.ip();
    anyhow::ensure!(peer.is_loopback() || matches!(peer, std::net::IpAddr::V6(v) if v.is_unicast_link_local() || v.to_ipv4_mapped().map(|a| a.is_loopback()).unwrap_or(false)), "non-local AirDrop peer");
    tcp_stream.set_read_timeout(Some(std::time::Duration::from_secs(60)))?;
    tcp_stream.set_write_timeout(Some(std::time::Duration::from_secs(30)))?;
    let conn = rustls::ServerConnection::new(tls_config)?;
    let mut tls = rustls::StreamOwned::new(conn, tcp_stream);
    // Read headers, then body. The split lets us emit an interim
    // `HTTP/1.1 100 Continue` between them when the client sent
    // `Expect: 100-continue` — without that an Apple sender can deadlock
    // waiting for the 100 before shipping the body. We write through
    // `reader.get_mut()` (the underlying `&mut tls`) because `BufReader`
    // only buffers reads.
    let mut reader = BufReader::new(&mut tls);
    let mut served = 0usize;
    loop {
        // Read the next request on this (keep-alive) connection. A clean close
        // after we've already served ≥1 request is normal end-of-connection,
        // not an error.
        let header_bytes = match read_headers(&mut reader) {
            Ok(h) => h,
            Err(_) if served > 0 => return Ok(()),
            Err(e) => return Err(e),
        };
        let head_str = std::str::from_utf8(&header_bytes).unwrap_or("");
        let (method, path) = crate::http::parse_request_line(
            head_str.split("\r\n").next().unwrap_or("")
        )?;
        if method != "POST" {
            reader.get_mut().write_all(b"HTTP/1.1 405 Method Not Allowed\r\nAllow: POST\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")?;
            reader.get_mut().flush()?;
            return Ok(());
        }
        if path == "/Ask" {
            // A new request supersedes an old approval even if the user
            // rejects it, the body is malformed, or the connection drops.
            let _ = take_approval(&approvals, peer);
        }
        if path == "/Upload" && !take_approval(&approvals, peer) {
            // No valid /Ask approval for this peer — refuse before reading a
            // possibly huge body. The approval may have arrived on a *different*
            // connection, which is why it lives in `approvals` and not in a
            // local flag.
            reader.get_mut().write_all(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")?;
            reader.get_mut().flush()?;
            return Ok(());
        }
        if let Some(expect) = parse_header_value(head_str, "expect") {
            if expect.eq_ignore_ascii_case("100-continue") {
                reader.get_mut().write_all(b"HTTP/1.1 100 Continue\r\n\r\n")?;
                // CRITICAL: flush the 100 Continue through the TLS encryption
                // layer so it reaches the Apple sender. Without this, rustls
                // buffers the plaintext and the sender never sees the 100,
                // deadlocking the /Upload (sender waits for 100, server waits
                // for body).
                reader.get_mut().flush()?;
            }
        }
        let body = read_body(&mut reader, &header_bytes, Some(&sink))?;
        let mut raw = header_bytes;
        raw.extend_from_slice(&body);

        let req = parse_request(&raw)?;

        // Track the sender name across the AirDrop flow (Discover/Ask/Upload
        // share one keep-alive connection, or reconnect) so the raw-upload
        // dump can be named after the device that sent the preceding /Ask.
        if req.path == "/Ask" {
            if let Ok(ask) = crate::plist_impl::parse_ask_request(&req.body) {
                if !ask.sender_computer_name.is_empty() {
                    if let Ok(mut cell) = last_sender.lock() {
                        *cell = ask.sender_computer_name.clone();
                    }
                }
            }
        }
        let sender_snapshot = last_sender
            .lock()
            .map(|cell| cell.clone())
            .unwrap_or_default();

        // /Discover is the first contact from an Apple device that has its
        // share pane open — the earliest useful "someone can see us" signal.
        announce_discover(&req, &sink);

        // Bump the introspection counters before dispatching so /status
        // reflects how far the Apple sender got (Discover -> Ask -> Upload)
        // even if the handler errors out.
        bump_introspect_for_request(&introspect, &req);

        if req.path == "/Ask" {
            if !crate::guard::approve(&req, &sink)? {
                reader.get_mut().write_all(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")?;
                reader.get_mut().flush()?;
                return Ok(());
            }
            // Publish the approval for the /Upload, which may arrive on its own
            // connection (our sender opens one per request).
            grant_approval(&approvals, peer);
        }
        let result = handle_post(&req, &cfg);
        let mut saved: Vec<(String, u64, String)> = Vec::new();
        for f in &result.received_files {
            if let Some(dir) = output_dir.as_ref() {
                let path = crate::guard::save(dir, &f.name, &f.bytes)?;
                saved.push((f.name.clone(), f.bytes.len() as u64, path.display().to_string()));
            }
        }
        // One event per completed transfer so the floating window can flip
        // from "receiving" to "saved" with the paths the phone should show.
        if !saved.is_empty() {
            let dir = output_dir
                .as_ref()
                .as_ref()
                .map(|d| d.display().to_string())
                .unwrap_or_default();
            let total: u64 = saved.iter().map(|(_, s, _)| *s).sum();
            sink.received(&dir, total, &saved);
        }

        // Env-gated raw /Upload capture for offline decode diffing against the
        // Python reference (opendrop-patch/dvzip.py). Off by default.
        if req.path == "/Upload" {
            if let Some(dir) = dump_dir.as_ref() {
                dump_raw_upload(dir, &sender_snapshot, &req.body);
            }
        }
        // Also dump the raw /Ask body under the same env var — some sends carry
        // the payload in /Ask, so this is the only way to see what was shared.
        if req.path == "/Ask" {
            if let Some(dir) = dump_dir.as_ref() {
                dump_raw_body(dir, "ask", &sender_snapshot, &req.body);
            }
        }

        // Keep-alive decision: close only if the handler ended the connection
        // (/Upload success, 406 — they set `Connection: close`) or the client
        // asked to. Otherwise loop to read the pipelined next request — this is
        // what lets a web-link Upload arrive on the same connection as its Ask.
        let resp_closes = result.response.headers.iter().any(|(k, v)| {
            k.eq_ignore_ascii_case("connection") && v.eq_ignore_ascii_case("close")
        });
        let req_closes = req
            .header("connection")
            .map(|v| v.eq_ignore_ascii_case("close"))
            .unwrap_or(false);

        let response_bytes = format_response(&result.response);
        reader.get_mut().write_all(&response_bytes)?;
        reader.get_mut().flush()?;
        served += 1;
        if resp_closes || req_closes {
            return Ok(());
        }
    }
}

/// Bind a TCP listener on `[::]:port` (IPv6, dual-stack).
pub fn bind_listener(port: u16) -> Result<TcpListener> {
    let addr = SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), port);
    let listener = TcpListener::bind(addr)?;
    #[cfg(target_os = "macos")]
    {
        use std::os::fd::AsRawFd;
        crate::netutil::allow_awdl_ingress(listener.as_raw_fd())?;
    }
    info!(port, "HTTPS listener bound");
    Ok(listener)
}

// ---- helpers ----

fn parse_header_value(headers: &str, name: &str) -> Option<String> {
    let lower = name.to_ascii_lowercase();
    for line in headers.split("\r\n") {
        if let Some((k, v)) = line.split_once(':') {
            if k.trim().to_ascii_lowercase() == lower {
                return Some(v.trim().to_string());
            }
        }
    }
    None
}

/// Read an HTTP/1.1 chunked body from a `BufRead` into a byte buffer
/// preserving the raw chunk framing (so the downstream `parse_request` /
/// `decode_chunked` can re-decode it). Stops at the genuine `0` last-chunk.
///
/// Robustness note: a malformed chunk-size line (partial read, CRLF desync,
/// stray byte) MUST NOT be silently treated as the `0` last-chunk. The
/// earlier `unwrap_or(0)` here did exactly that: a single bad size line
/// truncated the whole body after the first chunk, dropping the real file
/// payload (only the leading "." directory entry survived). We now error
/// loudly so truncation is visible instead of silently corrupting the
/// upload.
fn read_chunked_stream(reader: &mut impl BufRead, sink: Option<&Sink>) -> Result<Vec<u8>> {
    let mut raw = Vec::new();
    // Running total of *payload* bytes (chunk framing excluded) for the UI.
    // The wire has no declared length for a chunked body, so this only ever
    // feeds `progress(received, 0)`.
    let mut payload = 0u64;
    loop {
        let mut size_line = String::new();
        let n = reader.take(1025).read_line(&mut size_line)?;
        anyhow::ensure!(size_line.len() <= 1024, "chunk header too large");
        if n == 0 {
            anyhow::bail!("chunked body: EOF before last-chunk (truncated)");
        }
        raw.extend_from_slice(size_line.as_bytes());
        // Strip any chunk-extension (";name=value"); only the leading hex
        // token is the size.
        let size_token = size_line.trim().split(';').next().unwrap_or("").trim();
        let size = match usize::from_str_radix(size_token, 16) {
            Ok(s) => s,
            Err(_) => {
                // Distinguish the genuine last-chunk ("0") from a parse
                // FAILURE. A real "0" parses fine; anything else that fails
                // to parse as hex is a CRLF desync / partial read, NOT a
                // last-chunk. Bail loudly rather than silently truncating.
                anyhow::bail!(
                    "chunked body: malformed chunk-size line {:?} \
                     (not valid hex; would have silently truncated with unwrap_or(0))",
                    size_line.trim()
                );
            }
        };
        if size == 0 {
            // Genuine last-chunk: consume the trailing CRLF (and any
            // optional trailer-part up to the blank line).
            let mut trailer = String::new();
            reader.read_line(&mut trailer)?;
            raw.extend_from_slice(trailer.as_bytes());
            break;
        }
        anyhow::ensure!(size <= crate::guard::MAX_BODY.saturating_sub(raw.len()), "chunk body too large");
        let start = raw.len();
        raw.resize(start + size, 0);
        read_chunk_payload(reader, &mut raw[start..], payload, sink)?;
        payload += size as u64;
        let mut crlf = [0u8; 2];
        reader.read_exact(&mut crlf)?;
        raw.extend_from_slice(&crlf);
    }
    Ok(raw)
}

/// Read one chunk's payload in [`RECV_CHUNK`] slices, reporting cumulative
/// payload bytes with an *unknown* total (`0`).
///
/// Deliberately not [`read_exact_with_progress`]: that reports
/// `(done, chunk_len)`, which would tell the UI this chunk is a complete
/// transfer and snap the bar to 100 % after the first one. A chunked body has
/// no total anywhere on the wire, so the honest report is a running count with
/// no denominator.
fn read_chunk_payload(
    reader: &mut impl Read,
    buf: &mut [u8],
    already: u64,
    sink: Option<&Sink>,
) -> Result<()> {
    let mut done = 0usize;
    while done < buf.len() {
        let want = (buf.len() - done).min(RECV_CHUNK);
        reader.read_exact(&mut buf[done..done + want])?;
        done += want;
        if let Some(sink) = sink {
            sink.progress(already + done as u64, 0);
        }
    }
    Ok(())
}

// ---- raw /Upload body capture (LUFTLIFT_DUMP_UPLOAD) ----

/// Read the `LUFTLIFT_DUMP_UPLOAD` env var as a directory path. Returns None
/// if unset (the default — capture is off) or if the path doesn't exist (we
/// don't auto-create it to avoid surprising filesystem writes).
pub fn dump_upload_dir() -> Option<PathBuf> {
    let dir = std::env::var_os("LUFTLIFT_DUMP_UPLOAD")?;
    let path = PathBuf::from(dir);
    if path.is_dir() {
        Some(path)
    } else {
        warn!(
            dir = %path.display(),
            "LUFTLIFT_DUMP_UPLOAD is set but is not a directory; raw upload capture disabled"
        );
        None
    }
}

/// Sanitize a sender name for use in a filename: keep alphanumerics, dash,
/// underscore, dot; replace everything else (spaces, apostrophes in
/// "Andrew's MacBook Pro", etc.) with `_`. Pure (no I/O) so it's unit-testable.
pub fn sanitize_sender_for_filename(sender: &str) -> String {
    sender
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

/// Write a raw /Upload body to `<dir>/upload-<unix_ts>-<sanitized_sender>.bin`
/// for offline decode diffing against the Python reference (`dvzip.py`).
/// Best-effort: logs a warning on failure but never propagates the error —
/// the dump is a debugging aid, not part of the receive path.
pub fn dump_raw_upload(dir: &std::path::Path, sender: &str, body: &[u8]) {
    dump_raw_body(dir, "upload", sender, body);
}

/// Dump a raw request body to `dir` as `<kind>-<ts>-<sender>.bin`. Used for the
/// env-gated capture of /Upload and /Ask bodies (LUFTLIFT_DUMP_UPLOAD).
pub fn dump_raw_body(dir: &std::path::Path, kind: &str, sender: &str, body: &[u8]) {
    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let sender_part = if sender.is_empty() {
        "unknown".to_string()
    } else {
        sanitize_sender_for_filename(sender)
    };
    let filename = format!("{kind}-{ts}-{sender_part}.bin");
    let path = dir.join(&filename);
    match std::fs::write(&path, body) {
        Ok(_) => info!(
            path = %path.display(),
            bytes = body.len(),
            kind,
            "dumped raw request body (LUFTLIFT_DUMP_UPLOAD)"
        ),
        Err(e) => warn!(path = %path.display(), error = %e, kind, "failed to dump raw request body"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn cfg() -> ReceiverConfig {
        ReceiverConfig {
            computer_name: "luftlift-pc".into(),
            computer_model: "MacBookPro".into(),
            record_data: None,
        }
    }

    fn find_crlf_crlf(buf: &[u8]) -> usize {
        buf.windows(4).position(|w| w == b"\r\n\r\n").unwrap()
    }

    // ---- introspect counter wiring ----

    fn req_for(path: &str, body: Vec<u8>) -> HttpRequest {
        use std::collections::HashMap;
        HttpRequest {
            method: "POST".into(),
            path: path.into(),
            headers: HashMap::new(),
            body,
        }
    }

    fn ask_body(sender: &str) -> Vec<u8> {
        // Build a minimal binary-plist Ask body with just the sender name.
        use plist::{Dictionary, Value};
        let mut d = Dictionary::new();
        d.insert("TransferID".into(), Value::String("X".into()));
        d.insert("TransferType".into(), Value::String("links".into()));
        d.insert("SenderComputerName".into(), Value::String(sender.into()));
        d.insert("SenderModelName".into(), Value::String("iPhone".into()));
        d.insert("BundleID".into(), Value::String("com.apple.sharing".into()));
        let mut body = Vec::new();
        Value::Dictionary(d).to_writer_binary(&mut body).unwrap();
        body
    }

    #[test]
    fn bump_introspect_for_request_dispatches_by_path() {
        let st = std::sync::Arc::new(IntrospectState::new());
        bump_introspect_for_request(&st, &req_for("/Discover", Vec::new()));
        bump_introspect_for_request(&st, &req_for("/Discover", Vec::new()));
        bump_introspect_for_request(&st, &req_for("/Ask", ask_body("iPhone 15")));
        bump_introspect_for_request(&st, &req_for("/Upload", Vec::new()));
        let s = st.snapshot();
        assert_eq!(s.discover_count, 2);
        assert_eq!(s.ask_count, 1);
        assert_eq!(s.upload_count, 1);
        assert_eq!(s.last_peer, "iPhone 15", "Ask must populate last_peer");
    }

    #[test]
    fn bump_introspect_for_request_unknown_path_does_nothing() {
        let st = std::sync::Arc::new(IntrospectState::new());
        bump_introspect_for_request(&st, &req_for("/Unknown", Vec::new()));
        let s = st.snapshot();
        assert_eq!(s.discover_count, 0);
        assert_eq!(s.ask_count, 0);
        assert_eq!(s.upload_count, 0);
        assert_eq!(s.last_peer, "");
    }

    #[test]
    fn bump_introspect_for_request_ask_with_unparseable_body_still_bumps() {
        // A malformed /Ask body must still bump ask_count — the counter
        // answers "did an Apple sender even reach /Ask", which is more
        // useful for diagnosis than "did we parse the body". The last_peer
        // just isn't updated.
        let st = std::sync::Arc::new(IntrospectState::new());
        bump_introspect_for_request(&st, &req_for("/Ask", b"garbage".to_vec()));
        let s = st.snapshot();
        assert_eq!(s.ask_count, 1);
        assert_eq!(s.last_peer, "");
    }

    #[test]
    fn handle_connection_discover_writes_plist_response() {
        let request = b"POST /Discover HTTP/1.1\r\n\
                        Host: localhost\r\n\
                        Content-Length: 0\r\n\
                        \r\n";
        let mut reader = Cursor::new(&request[..]);
        let mut writer = Vec::new();
        let result = handle_one_connection(&mut reader, &mut writer, &cfg()).unwrap();
        assert_eq!(result.response.status, 200);

        // Find header/body boundary in raw bytes (body is binary plist).
        let sep = find_crlf_crlf(&writer);
        let headers = std::str::from_utf8(&writer[..sep]).unwrap();
        assert!(headers.starts_with("HTTP/1.1 200 OK\r\n"));
        let body = &writer[sep + 4..];
        assert_eq!(&body[..8], b"bplist00");
    }

    #[test]
    fn handle_connection_chunked_ask_body() {
        // iOS-18 style: chunked body, no Content-Length.
        let request = b"POST /Ask HTTP/1.1\r\n\
                        Host: localhost\r\n\
                        Transfer-Encoding: chunked\r\n\
                        \r\n\
                        0\r\n\
                        \r\n";
        let mut reader = Cursor::new(&request[..]);
        let mut writer = Vec::new();
        let result = handle_one_connection(&mut reader, &mut writer, &cfg()).unwrap();
        assert_eq!(result.response.status, 200);
        let sep = find_crlf_crlf(&writer);
        assert_eq!(&writer[sep + 4..sep + 12], b"bplist00");
    }

    #[test]
    fn handle_connection_unknown_path_returns_404() {
        let request = b"POST /Unknown HTTP/1.1\r\nContent-Length: 0\r\n\r\n";
        let mut reader = Cursor::new(&request[..]);
        let mut writer = Vec::new();
        let result = handle_one_connection(&mut reader, &mut writer, &cfg()).unwrap();
        assert_eq!(result.response.status, 404);
        let headers = std::str::from_utf8(&writer).unwrap();
        assert!(headers.starts_with("HTTP/1.1 404"));
    }

    #[test]
    fn handle_connection_upload_dvzip_returns_files() {
        // Build a dvzip payload via the public encoders (one file "test.txt").
        use crate::{cpio::build_odc_cpio, dvzip::cpio_to_dvzip};
        let dvzip = cpio_to_dvzip(&build_odc_cpio(&[("test.txt", b"hello")]));

        let request = format!(
            "POST /Upload HTTP/1.1\r\n\
             Host: localhost\r\n\
             Content-Type: application/x-dvzip\r\n\
             Content-Length: {}\r\n\
             \r\n",
            dvzip.len()
        );
        let mut raw = request.into_bytes();
        raw.extend_from_slice(&dvzip);

        let mut reader = Cursor::new(&raw[..]);
        let mut writer = Vec::new();
        let result = handle_one_connection(&mut reader, &mut writer, &cfg()).unwrap();
        assert_eq!(result.response.status, 200);
        assert_eq!(result.received_files.len(), 1);
        assert_eq!(result.received_files[0].name, "test.txt");
        assert_eq!(result.received_files[0].bytes, b"hello");
    }

    #[test]
    fn handle_connection_sends_100_continue_for_expect() {
        // A sender that sends Expect: 100-continue must receive an interim
        // HTTP/1.1 100 Continue before the final response — otherwise the
        // sender deadlocks waiting for the 100 before shipping the body.
        // Mirrors opendrop-patch/server.py.patched::handle_upload's
        // `send_response(100)` branch.
        use crate::{cpio::build_odc_cpio, dvzip::cpio_to_dvzip};
        let dvzip = cpio_to_dvzip(&build_odc_cpio(&[("note.txt", b"expect me")]));
        let request = format!(
            "POST /Upload HTTP/1.1\r\n\
             Host: localhost\r\n\
             Content-Type: application/x-dvzip\r\n\
             Content-Length: {}\r\n\
             Expect: 100-continue\r\n\
             \r\n",
            dvzip.len()
        );
        let mut raw = request.into_bytes();
        raw.extend_from_slice(&dvzip);

        let mut reader = Cursor::new(&raw[..]);
        let mut writer = Vec::new();
        let result = handle_one_connection(&mut reader, &mut writer, &cfg()).unwrap();
        assert_eq!(result.response.status, 200);
        assert_eq!(result.received_files[0].bytes, b"expect me");

        // The writer must contain the interim 100 Continue BEFORE the final
        // 200 response. The body was still extracted, proving the body read
        // happened after the 100 was emitted.
        let out = String::from_utf8_lossy(&writer);
        let cont_idx = out
            .find("HTTP/1.1 100 Continue")
            .expect("interim 100 Continue must be sent");
        let final_idx = out.find("HTTP/1.1 200").expect("final 200 response must follow");
        assert!(cont_idx < final_idx, "100 Continue must precede the final response");
    }

    #[test]
    fn handle_connection_no_100_continue_without_expect_header() {
        // Without Expect: 100-continue there must be no interim response.
        let request = b"POST /Discover HTTP/1.1\r\n\
                         Host: localhost\r\n\
                         Content-Length: 0\r\n\
                         \r\n";
        let mut reader = Cursor::new(&request[..]);
        let mut writer = Vec::new();
        let _ = handle_one_connection(&mut reader, &mut writer, &cfg()).unwrap();
        let out = String::from_utf8_lossy(&writer);
        assert!(!out.contains("100 Continue"), "no 100 Continue without Expect header");
        assert!(out.starts_with("HTTP/1.1 200"));
    }

    // ---- 100 Continue flush regression tests ----
    //
    // On the TLS path, rustls buffers writes internally. If the 100 Continue
    // is written but not flushed, it stays in the buffer and never reaches
    // the Apple sender — which waits forever for the 100 before sending the
    // body, deadlocking the /Upload. The in-memory Cursor+Vec tests above do
    // NOT catch this because Vec writes are immediate. The tests below verify
    // (a) flush() is called after 100 Continue, and (b) a real socket-pair
    // integration with BufWriter (simulating TLS buffering) completes.

    /// A writer wrapper that records whether flush() was called.
    struct FlushTracker<W: std::io::Write> {
        inner: W,
        flush_count: u32,
    }
    impl<W: std::io::Write> std::io::Write for FlushTracker<W> {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.inner.write(buf)
        }
        fn flush(&mut self) -> std::io::Result<()> {
            self.flush_count += 1;
            self.inner.flush()
        }
    }

    #[test]
    fn read_request_respecting_expect_flushes_100_continue() {
        // After writing 100 Continue, flush() MUST be called — without it,
        // the response stays in the TLS encryption buffer and the Apple
        // sender deadlocks waiting for the 100.
        let request = b"POST /Upload HTTP/1.1\r\n\
                         Expect: 100-continue\r\n\
                         Content-Length: 5\r\n\
                         \r\n\
                         hello";
        let mut reader = Cursor::new(&request[..]);
        let mut writer = FlushTracker { inner: Vec::new(), flush_count: 0 };

        let raw = read_request_respecting_expect(&mut reader, &mut writer).unwrap();

        // 100 Continue was written.
        assert!(
            writer.inner.starts_with(b"HTTP/1.1 100 Continue\r\n\r\n"),
            "100 Continue must be written to the writer"
        );
        // flush() was called at least once after the 100 Continue.
        assert!(
            writer.flush_count > 0,
            "flush() must be called after 100 Continue — without it, the \
             response stays buffered in TLS/BufWriter and the Apple sender \
             deadlocks waiting for it"
        );
        // Body was still read correctly.
        assert!(raw.windows(5).any(|w| w == b"hello"), "body must be present in raw");
    }

    #[test]
    fn handle_upload_chunked_expect_100_continue_full_flow_no_deadlock() {
        // Full Apple-style Upload: chunked + Expect:100-continue, multi-chunk
        // dvzip body, over a real TCP socket pair with a BufWriter on the
        // server side (to simulate TLS write buffering). Without the flush
        // after 100 Continue, the client's read for the 100 times out
        // (deadlock caught by set_read_timeout).
        use std::io::{Read, Write as IoWrite};
        use std::net::TcpListener;
        use std::time::Duration;

        // Build a real dvzip(CPIO) payload with a test file.
        let entries: &[(&str, &[u8])] = &[("expect_test.txt", b"chunked + expect body")];
        let cpio = crate::cpio::build_odc_cpio(entries);
        let dvzip = crate::dvzip::cpio_to_dvzip(&cpio);

        // Frame as chunked, split into 2 chunks to exercise multi-chunk decoding.
        let mid = dvzip.len() / 2;
        let rest = dvzip.len() - mid;
        let mut chunked_body = Vec::new();
        chunked_body.extend_from_slice(format!("{:x}\r\n", mid).as_bytes());
        chunked_body.extend_from_slice(&dvzip[..mid]);
        chunked_body.extend_from_slice(b"\r\n");
        chunked_body.extend_from_slice(format!("{:x}\r\n", rest).as_bytes());
        chunked_body.extend_from_slice(&dvzip[mid..]);
        chunked_body.extend_from_slice(b"\r\n0\r\n\r\n");

        // Bind a local listener for a real TCP socket pair.
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let addr = listener.local_addr().expect("local_addr");

        // Server in background thread. BufWriter simulates TLS's internal
        // write buffering — if 100 Continue isn't explicitly flushed, it
        // stays in the BufWriter and never reaches the client.
        let server_cfg = cfg();
        let server_thread = std::thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            let write_stream = stream.try_clone().expect("try_clone");
            let mut reader = std::io::BufReader::new(stream);
            let mut writer = std::io::BufWriter::new(write_stream);
            handle_one_connection(&mut reader, &mut writer, &server_cfg)
        });

        // Client (Apple sender simulation): send headers with chunked +
        // Expect:100-continue, WAIT for 100 Continue, then send the body.
        let mut stream =
            TcpStream::connect_timeout(&addr, Duration::from_secs(5)).expect("connect");
        stream
            .set_read_timeout(Some(Duration::from_secs(10)))
            .expect("set_read_timeout");

        let headers = "POST /Upload HTTP/1.1\r\n\
                       Host: localhost\r\n\
                       Content-Type: application/x-dvzip\r\n\
                       Transfer-Encoding: chunked\r\n\
                       Expect: 100-continue\r\n\
                       \r\n";
        stream.write_all(headers.as_bytes()).expect("write headers");
        stream.flush().expect("flush headers");

        // WAIT for 100 Continue. If the server didn't flush, this read times
        // out after 10s and .expect() panics — catching the regression.
        let mut buf = [0u8; 1024];
        let n = stream
            .read(&mut buf)
            .expect("read 100-continue (timed out = server didn't flush the 100 Continue)");
        let cont = String::from_utf8_lossy(&buf[..n]);
        assert!(cont.contains("100 Continue"), "expected 100 Continue, got: {cont}");

        // 100 Continue received — send the chunked body.
        stream.write_all(&chunked_body).expect("write chunked body");
        stream.flush().expect("flush body");

        // Read the final 200 response.
        let mut resp_buf = Vec::new();
        stream.read_to_end(&mut resp_buf).expect("read final response");
        let resp = String::from_utf8_lossy(&resp_buf);
        assert!(resp.contains("200"), "final response should be 200: {resp}");

        // Verify the server-side result.
        let result = server_thread.join().expect("server thread").expect("handle");
        assert_eq!(result.response.status, 200);
        assert_eq!(result.received_files.len(), 1);
        assert_eq!(result.received_files[0].name, "expect_test.txt");
        assert_eq!(result.received_files[0].bytes, b"chunked + expect body");
    }

    // ---- raw /Upload capture (LUFTLIFT_DUMP_UPLOAD) ----

    #[test]
    fn sanitize_sender_replaces_unsafe_chars() {
        // "Andrew's MacBook Pro" -> "Andrew_s_MacBook_Pro"
        assert_eq!(sanitize_sender_for_filename("Andrew's MacBook Pro"), "Andrew_s_MacBook_Pro");
        // Alphanumeric / -_./ are kept.
        assert_eq!(sanitize_sender_for_filename("iPhone42"), "iPhone42");
        assert_eq!(sanitize_sender_for_filename("luftlift-pc.local"), "luftlift-pc.local");
        assert_eq!(sanitize_sender_for_filename("a_b-c.d"), "a_b-c.d");
    }

    #[test]
    fn sanitize_sender_handles_empty_and_unicode() {
        assert_eq!(sanitize_sender_for_filename(""), "");
        // Non-ASCII chars are replaced with `_`; ASCII alphanumerics survive.
        // Use a string of only non-ASCII chars so output is all underscores.
        let s = sanitize_sender_for_filename("üïöé");
        assert!(s.chars().all(|c| c == '_'), "expected all underscores, got {s:?}");
        // Mixed: ASCII kept, non-ASCII replaced.
        assert_eq!(sanitize_sender_for_filename("café"), "caf_");
    }

    #[test]
    fn dump_raw_upload_writes_file_with_sender_and_timestamp_pattern() {
        // Best-effort dump: writes <dir>/upload-<ts>-<sender>.bin with the
        // raw body bytes. Verify in a tempdir.
        let dir = std::env::temp_dir();
        let body = b"raw dvzip body bytes";
        dump_raw_upload(&dir, "iPhone42", body);
        // Find the most recent matching file and verify its content + name.
        let pattern = "upload-";
        let mut found: Option<PathBuf> = None;
        if let Ok(rd) = std::fs::read_dir(&dir) {
            for entry in rd.flatten() {
                let name = entry.file_name();
                let name = name.to_string_lossy();
                if name.starts_with(pattern)
                    && name.ends_with("-iPhone42.bin")
                    && entry.metadata().map(|m| m.len() as usize == body.len()).unwrap_or(false)
                {
                    let candidate = entry.path();
                    if found.as_ref().is_none_or(|p| {
                        candidate.metadata().and_then(|m| m.modified()).ok()
                            > p.metadata().and_then(|m| m.modified()).ok()
                    }) {
                        found = Some(candidate);
                    }
                }
            }
        }
        let path = found.expect("dump file must exist after dump_raw_upload");
        let read_back = std::fs::read(&path).expect("read dumped file");
        assert_eq!(read_back, body, "dumped bytes must match the input body");
        let fname = path.file_name().unwrap().to_string_lossy().into_owned();
        assert!(fname.contains("-iPhone42.bin"), "filename must include sender: {fname}");
        assert!(fname.starts_with("upload-"), "filename must start with upload-: {fname}");
        // Cleanup.
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn dump_raw_upload_uses_unknown_for_empty_sender() {
        let dir = std::env::temp_dir();
        let body = b"x";
        dump_raw_upload(&dir, "", body);
        // Find a matching upload-*-unknown.bin from the last few seconds.
        if let Ok(rd) = std::fs::read_dir(&dir) {
            for entry in rd.flatten() {
                let name = entry.file_name().to_string_lossy().into_owned();
                if name.starts_with("upload-") && name.ends_with("-unknown.bin") {
                    let _ = std::fs::remove_file(entry.path());
                    return; // pass
                }
            }
        }
        panic!("expected an upload-*-unknown.bin file after dumping with empty sender");
    }

    // ---- receive progress events ----
    //
    // The floating window's bar is fed by these events and by nothing else, so
    // they are part of the protocol surface, not a debug aid. The two encodings
    // a sender may use behave differently on purpose: Content-Length gives a
    // determinate bar, chunked (what Apple always sends) cannot.

    #[derive(Clone, Default)]
    struct CapBuf(std::sync::Arc<std::sync::Mutex<Vec<u8>>>);

    impl std::io::Write for CapBuf {
        fn write(&mut self, b: &[u8]) -> std::io::Result<usize> {
            self.0.lock().unwrap().extend_from_slice(b);
            Ok(b.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    fn cap_sink() -> (std::sync::Arc<Sink>, CapBuf) {
        let buf = CapBuf::default();
        (Sink::to_writer(buf.clone()), buf)
    }

    fn cap_events(buf: &CapBuf) -> Vec<serde_json::Value> {
        String::from_utf8(buf.0.lock().unwrap().clone())
            .unwrap()
            .lines()
            .map(|l| serde_json::from_str(l).unwrap())
            .collect()
    }

    #[test]
    fn upload_with_content_length_reports_a_determinate_bar() {
        // Content-Length is on the wire, so the bar can show a real percentage
        // from the first byte. The final progress must equal the declared
        // length — a bar that stops at 98 % looks like a failed transfer.
        let dvzip = crate::dvzip::cpio_to_dvzip(&crate::cpio::build_odc_cpio(&[("a.txt", b"hello")]));
        let request = format!(
            "POST /Upload HTTP/1.1\r\nContent-Type: application/x-dvzip\r\nContent-Length: {}\r\n\r\n",
            dvzip.len()
        );
        let mut raw = request.into_bytes();
        raw.extend_from_slice(&dvzip);

        let (sink, buf) = cap_sink();
        let mut reader = Cursor::new(&raw[..]);
        let mut writer = Vec::new();
        let result = handle_one_connection_with(&mut reader, &mut writer, &cfg(), Some(&sink)).unwrap();
        assert_eq!(result.response.status, 200);

        let events = cap_events(&buf);
        let receiving = events.iter().find(|e| e["event"] == "receiving").expect("receiving event");
        assert_eq!(receiving["total"], dvzip.len() as u64, "declared length must be reported");
        let last = events.iter().rev().find(|e| e["event"] == "progress").expect("progress events");
        assert_eq!(last["received"], dvzip.len() as u64);
        assert_eq!(last["total"], dvzip.len() as u64);
    }

    #[test]
    fn chunked_upload_reports_bytes_without_a_total() {
        // Apple sends chunked /Upload bodies: there is no length on the wire,
        // so `total` is 0 and the UI must render an indeterminate bar. The
        // byte counter still has to advance, otherwise the user sees nothing
        // at all for a 2-minute transfer.
        let dvzip = crate::dvzip::cpio_to_dvzip(&crate::cpio::build_odc_cpio(&[("b.txt", b"chunk me")]));
        let mut raw = b"POST /Upload HTTP/1.1\r\nContent-Type: application/x-dvzip\r\nTransfer-Encoding: chunked\r\n\r\n".to_vec();
        raw.extend_from_slice(format!("{:x}\r\n", dvzip.len()).as_bytes());
        raw.extend_from_slice(&dvzip);
        raw.extend_from_slice(b"\r\n0\r\n\r\n");

        let (sink, buf) = cap_sink();
        let mut reader = Cursor::new(&raw[..]);
        let mut writer = Vec::new();
        let result = handle_one_connection_with(&mut reader, &mut writer, &cfg(), Some(&sink)).unwrap();
        assert_eq!(result.response.status, 200);
        assert_eq!(result.received_files.len(), 1);

        let events = cap_events(&buf);
        let receiving = events.iter().find(|e| e["event"] == "receiving").expect("receiving event");
        assert_eq!(receiving["total"], 0, "chunked bodies have no declared total");
        let progress: Vec<&serde_json::Value> =
            events.iter().filter(|e| e["event"] == "progress").collect();
        assert!(!progress.is_empty(), "chunked uploads must still report bytes");
        assert!(progress.last().unwrap()["received"].as_u64().unwrap() > 0);
        assert_eq!(progress.last().unwrap()["total"], 0);
    }

    #[test]
    fn discover_request_reports_the_sender_name() {
        // /Discover is the first sign of life from an Apple device; the UI
        // shows it while the user is still browsing the picker.
        use plist::{Dictionary, Value};
        let mut d = Dictionary::new();
        d.insert("SenderComputerName".into(), Value::String("Andrew's MacBook Pro".into()));
        d.insert("SenderModelName".into(), Value::String("MacBookPro18,3".into()));
        let mut body = Vec::new();
        Value::Dictionary(d).to_writer_binary(&mut body).unwrap();

        let request = format!(
            "POST /Discover HTTP/1.1\r\nContent-Length: {}\r\n\r\n",
            body.len()
        );
        let mut raw = request.into_bytes();
        raw.extend_from_slice(&body);

        let (sink, buf) = cap_sink();
        let mut reader = Cursor::new(&raw[..]);
        let mut writer = Vec::new();
        handle_one_connection_with(&mut reader, &mut writer, &cfg(), Some(&sink)).unwrap();

        let events = cap_events(&buf);
        let discover = events.iter().find(|e| e["event"] == "discover").expect("discover event");
        assert_eq!(discover["sender"], "Andrew's MacBook Pro");
    }

    #[test]
    fn discover_with_unparseable_body_reports_unknown_sender() {
        // A /Discover we cannot parse must not abort the connection: it carries
        // no payload, and dropping it would make that peer look unreachable.
        let request = "POST /Discover HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello";
        let (sink, buf) = cap_sink();
        let mut reader = Cursor::new(request.as_bytes());
        let mut writer = Vec::new();
        let result = handle_one_connection_with(&mut reader, &mut writer, &cfg(), Some(&sink)).unwrap();
        assert_eq!(result.response.status, 200);
        let events = cap_events(&buf);
        let discover = events.iter().find(|e| e["event"] == "discover").expect("discover event");
        assert_eq!(discover["sender"], "unknown");
    }

    #[test]
    fn discover_and_ask_do_not_report_transfer_progress() {
        // /Discover and /Ask also carry bodies, but they are not transfers.
        // Reporting them popped a progress card on the phone every time a
        // device merely opened its share pane and looked at us.
        let (sink, buf) = cap_sink();
        let mut writer = Vec::new();

        let ask_body = {
            use plist::{Dictionary, Value};
            let mut d = Dictionary::new();
            d.insert("SenderComputerName".into(), Value::String("Mac".into()));
            let mut b = Vec::new();
            Value::Dictionary(d).to_writer_binary(&mut b).unwrap();
            b
        };
        let ask = format!("POST /Ask HTTP/1.1\r\nContent-Length: {}\r\n\r\n", ask_body.len());
        let mut raw = ask.into_bytes();
        raw.extend_from_slice(&ask_body);
        handle_one_connection_with(&mut Cursor::new(&raw[..]), &mut writer, &cfg(), Some(&sink)).unwrap();

        let discover = b"POST /Discover HTTP/1.1\r\nContent-Length: 0\r\n\r\n";
        handle_one_connection_with(&mut Cursor::new(&discover[..]), &mut writer, &cfg(), Some(&sink)).unwrap();

        let events = cap_events(&buf);
        assert!(
            !events.iter().any(|e| e["event"] == "receiving" || e["event"] == "progress"),
            "only /Upload is a transfer: {:?}",
            events
        );
    }

    // ---- cross-connection approval ----

    #[test]
    fn approval_is_scoped_to_the_peer_and_consumed_once() {
        // The /Upload may arrive on a different connection than the /Ask, so
        // the approval cannot live in the connection handler. It must also be
        // consumed: one /Ask authorizes exactly one /Upload.
        let approvals: Approvals = Arc::new(Mutex::new(std::collections::HashMap::new()));
        let mac: IpAddr = "fe80::1".parse().unwrap();
        let phone: IpAddr = "fe80::2".parse().unwrap();

        assert!(!take_approval(&approvals, mac), "nothing approved yet");
        grant_approval(&approvals, mac);
        assert!(!take_approval(&approvals, phone), "another peer must not inherit it");
        assert!(take_approval(&approvals, mac), "the approved peer may upload");
        assert!(!take_approval(&approvals, mac), "and only once");
    }

    #[test]
    fn stale_approvals_are_dropped() {
        // A grant that was never used must not authorize an upload minutes
        // later. Simulate age by inserting a timestamp in the past.
        let approvals: Approvals = Arc::new(Mutex::new(std::collections::HashMap::new()));
        let mac: IpAddr = "fe80::3".parse().unwrap();
        {
            let mut map = approvals.lock().unwrap();
            map.insert(mac, Instant::now() - APPROVAL_TTL - std::time::Duration::from_secs(1));
        }
        assert!(!take_approval(&approvals, mac));
        // And a fresh grant still works, proving the map is not wedged.
        grant_approval(&approvals, mac);
        assert!(take_approval(&approvals, mac));
    }

    #[test]
    fn no_sink_means_no_events_and_no_panic() {
        // Library callers pass None; the receive path must be unchanged for
        // them (this is the pre-progress behaviour, byte for byte).
        let dvzip = crate::dvzip::cpio_to_dvzip(&crate::cpio::build_odc_cpio(&[("c.txt", b"x")]));
        let request = format!(
            "POST /Upload HTTP/1.1\r\nContent-Type: application/x-dvzip\r\nContent-Length: {}\r\n\r\n",
            dvzip.len()
        );
        let mut raw = request.into_bytes();
        raw.extend_from_slice(&dvzip);
        let mut reader = Cursor::new(&raw[..]);
        let mut writer = Vec::new();
        let result = handle_one_connection_with(&mut reader, &mut writer, &cfg(), None).unwrap();
        assert_eq!(result.received_files[0].bytes, b"x");
    }
}

#[cfg(test)]
#[path = "server_tls_tests.rs"]
mod tls_regressions;
