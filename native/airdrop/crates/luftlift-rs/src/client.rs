//! AirDrop **sender** (client) — builds the `/Discover`, `/Ask`, `/Upload`
//! requests as pure functions and orchestrates the POST sequence over a TLS
//! connection. The network glue is kept behind a small trait so the request
//! building + response handling is unit-testable without sockets.
//!
//! Reference: `docs/airdrop-protocol.md` (HTTP flow + iOS-18 quirks) and
//! `opendrop-patch/server.py.patched` (the receiver these requests target).

use crate::dvzip::cpio_to_dvzip;
use crate::cpio::build_odc_cpio;
use crate::http::{HttpRequest, HttpResponse};
use crate::plist_impl::ReceiverConfig;
use anyhow::Result;
use plist::{Dictionary, Value};
use std::collections::HashMap;
use tracing::info;

/// Frame a payload as an HTTP/1.1 chunked body: a single chunk of `body`
/// followed by the terminating `0\r\n\r\n`. Used to re-frame the /Upload body
/// as chunked to mimic a real Apple sender.
fn chunk_frame(body: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(body.len() + 16);
    if !body.is_empty() {
        out.extend_from_slice(format!("{:x}\r\n", body.len()).as_bytes());
        out.extend_from_slice(body);
        out.extend_from_slice(b"\r\n");
    }
    out.extend_from_slice(b"0\r\n\r\n");
    out
}

/// Sender identity/configuration used to build /Discover and /Ask requests.
#[derive(Debug, Clone)]
pub struct SenderConfig {
    pub computer_name: String,
    pub model_name: String,
    pub bundle_id: String,
    /// Opaque per-sender service identifier, stored as bytes internally and
    /// encoded as a lowercase hex string in /Ask (OpenDrop config.service_id).
    /// It is not an Apple ID hash, certificate, or contacts-only credential.
    pub sender_id: Vec<u8>,
}

impl SenderConfig {
    /// Build a `SenderConfig` with a freshly generated 6-byte `SenderID`.
    /// The value is random-but-stable-for-the-process so multiple transfers
    /// from one `luftlift send` invocation share a sender identity.
    pub fn new(computer_name: impl Into<String>, model_name: impl Into<String>, bundle_id: impl Into<String>) -> Self {
        Self {
            computer_name: computer_name.into(),
            model_name: model_name.into(),
            bundle_id: bundle_id.into(),
            sender_id: random_sender_id(),
        }
    }

    /// Convenience for tests: explicit sender_id bytes.
    #[cfg(test)]
    pub fn with_sender_id(
        computer_name: impl Into<String>,
        model_name: impl Into<String>,
        bundle_id: impl Into<String>,
        sender_id: Vec<u8>,
    ) -> Self {
        Self {
            computer_name: computer_name.into(),
            model_name: model_name.into(),
            bundle_id: bundle_id.into(),
            sender_id,
        }
    }
}

/// Generate a 6-byte pseudo-random sender ID (MAC-address-sized, matching the
/// shape real Apple senders use). Uses the same time-seeded PRNG as the
/// transfer-id generator in main.rs — not cryptographic, but adequate for an
/// AirDrop sender identifier that only needs to be unique per process.
fn random_sender_id() -> Vec<u8> {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::SystemTime;
    // Two SenderConfigs built in the same clock tick used to collide: the seed
    // was pure time, and SystemTime's resolution is not guaranteed to be finer
    // than the gap between two calls (this made the unit test flaky and would
    // have given a multi-peer send the same SenderID twice). A process-wide
    // counter guarantees distinctness within a process.
    static SEQ: AtomicU64 = AtomicU64::new(0);
    let s = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap_or_default();
    let seq = SEQ.fetch_add(1, Ordering::Relaxed);
    let seed = s.as_nanos() as u64
        ^ (std::process::id() as u64).rotate_left(17)
        ^ seq.wrapping_mul(0x9E3779B97F4A7C15);
    // xorshift64* → 6 bytes. Good enough dispersion for a non-crypto ID.
    let mut x = seed.wrapping_mul(0x2545F4914F6CDD1D).max(1);
    let mut out = [0u8; 6];
    for byte in &mut out {
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        *byte = (x >> 24) as u8;
    }
    out.to_vec()
}

/// One file to send.
#[derive(Debug, Clone)]
pub struct FileToSend {
    pub name: String,
    /// UTI type, e.g. "public.jpeg" for JPEGs, "com.apple.web-internet-location"
    /// for links. opendrop uses "public.data" as a generic fallback.
    pub uti_type: String,
    pub data: Vec<u8>,
}

/// Build the `/Discover` request body (sender record plist). The receiver
/// responds with its own record so we appear in its picker — actually for a
/// sender, /Discover makes the *receiver* appear in *our* picker. Body is a
/// minimal sender plist; Content-Type application/x-apple-plist.
pub fn build_discover_request(cfg: &SenderConfig) -> HttpRequest {
    let mut dict = Dictionary::new();
    dict.insert("SenderComputerName".into(), Value::String(cfg.computer_name.clone()));
    dict.insert("SenderModelName".into(), Value::String(cfg.model_name.clone()));
    let mut body = Vec::new();
    Value::Dictionary(dict).to_writer_binary(&mut body).expect("serialise discover request");

    let mut headers = HashMap::new();
    headers.insert("host".into(), "airdrop._tcp.local.".into());
    headers.insert("content-type".into(), "application/x-apple-plist".into());
    HttpRequest {
        method: "POST".into(),
        path: "/Discover".into(),
        headers,
        body,
    }
}

/// Build the `/Ask` request body (file metadata plist). `transfer_id` is a
/// caller-generated UUID; the receiver replies 200 after accepting the request.
pub fn build_ask_request(cfg: &SenderConfig, transfer_id: &str, files: &[FileToSend]) -> HttpRequest {
    let mut files_arr = Vec::new();
    for f in files {
        let mut entry = Dictionary::new();
        entry.insert("FileName".into(), Value::String(f.name.clone()));
        entry.insert("FileType".into(), Value::String(f.uti_type.clone()));
        entry.insert("FileSize".into(), Value::Integer((f.data.len() as i64).into()));
        entry.insert("FileIsDirectory".into(), Value::Boolean(false));
        entry.insert("FileBomPath".into(), Value::String(format!("./{}", f.name)));
        files_arr.push(Value::Dictionary(entry));
    }

    let mut root = Dictionary::new();
    root.insert("TransferID".into(), Value::String(transfer_id.into()));
    root.insert("TransferType".into(), Value::String("files".into()));
    // OpenDrop's /Ask sends config.service_id (12 hex characters), not
    // plist Data. Keep the type explicit; local handlers ignore this field
    // and therefore cannot establish Apple compatibility by round-tripping.
    let sender_id: String = cfg.sender_id.iter().map(|byte| format!("{byte:02x}")).collect();
    root.insert("SenderID".into(), Value::String(sender_id));
    root.insert("ConvertMediaFormats".into(), Value::Boolean(false));
    root.insert("SenderComputerName".into(), Value::String(cfg.computer_name.clone()));
    root.insert("SenderModelName".into(), Value::String(cfg.model_name.clone()));
    root.insert("BundleID".into(), Value::String(cfg.bundle_id.clone()));
    root.insert("Files".into(), Value::Array(files_arr));

    let mut body = Vec::new();
    Value::Dictionary(root).to_writer_binary(&mut body).expect("serialise ask request");

    let mut headers = HashMap::new();
    headers.insert("host".into(), "airdrop._tcp.local.".into());
    headers.insert("content-type".into(), "application/x-apple-plist".into());
    HttpRequest {
        method: "POST".into(),
        path: "/Ask".into(),
        headers,
        body,
    }
}

/// Build the `/Upload` request body: a CPIO archive of the files, framed as
/// dvzip (iOS-18 Content-Type `application/x-dvzip`).
pub fn build_upload_request(files: &[FileToSend]) -> HttpRequest {
    let entries: Vec<(&str, &[u8])> =
        files.iter().map(|f| (f.name.as_str(), f.data.as_slice())).collect();
    let cpio = build_odc_cpio(&entries);
    let dvzip = cpio_to_dvzip(&cpio);

    let mut headers = HashMap::new();
    headers.insert("host".into(), "airdrop._tcp.local.".into());
    headers.insert("content-type".into(), "application/x-dvzip".into());
    HttpRequest {
        method: "POST".into(),
        path: "/Upload".into(),
        headers,
        body: dvzip,
    }
}

/// Verify the receiver's /Discover response carries the expected receiver
/// record fields. Returns the parsed ReceiverComputerName on success.
pub fn parse_discover_response(resp: &HttpResponse) -> Result<ReceiverConfig> {
    if resp.status != 200 {
        anyhow::bail!("/Discover returned status {}", resp.status);
    }
    let val = Value::from_reader(std::io::Cursor::new(&resp.body))?;
    let dict = val.as_dictionary().ok_or_else(|| anyhow::anyhow!("/Discover response not a dict"))?;
    Ok(ReceiverConfig {
        computer_name: dict
            .get("ReceiverComputerName")
            .and_then(|v| v.as_string())
            .unwrap_or("unknown")
            .to_string(),
        computer_model: dict
            .get("ReceiverModelName")
            .and_then(|v| v.as_string())
            .unwrap_or("unknown")
            .to_string(),
        record_data: dict.get("ReceiverRecordData").and_then(|v| v.as_data()).map(|d| d.to_vec()),
    })
}

// ---- thin network trait ----

/// A minimal HTTP-over-TLS transport. Implementations talk to a real
/// `rustls::StreamOwned<TcpStream>`; the in-memory test impl round-trips
/// through the crate's own server handler.
pub trait AirDropTransport {
    /// Send a request and read back the response.
    fn send(&mut self, req: &HttpRequest) -> Result<HttpResponse>;

    /// Send a request, reporting `(written, total)` bytes as it goes out.
    ///
    /// The default implementation cannot see the wire and therefore reports a
    /// single `(total, total)` at the end — enough for a caller to know the
    /// request completed, useless for a progress bar. [`RustlsTransport`]
    /// overrides it and streams the body in pieces, which is what the send UI
    /// actually needs: a multi-hundred-megabyte upload can run for minutes.
    fn send_with_progress(
        &mut self,
        req: &HttpRequest,
        progress: &mut dyn FnMut(u64, u64),
    ) -> Result<HttpResponse> {
        let resp = self.send(req)?;
        let total = req.body.len() as u64;
        progress(total, total);
        Ok(resp)
    }
}

/// Run the full AirDrop send flow against a peer over the given transport.
/// Sequence: POST /Discover → POST /Ask → POST /Upload.
pub fn send_files(
    transport: &mut impl AirDropTransport,
    sender: &SenderConfig,
    files: &[FileToSend],
    transfer_id: &str,
) -> Result<()> {
    send_files_with(transport, sender, files, transfer_id, &crate::progress::Sink::disabled())
}

/// As [`send_files`], reporting each protocol leg and byte progress to `sink`.
///
/// The events map one-to-one onto what the send sheet shows: `stage` drives the
/// label ("正在查找…" → "等待对方接受…" → "正在发送…") and `progress` drives the
/// bar. `sent` is the terminal event so the UI can show "完成" without having
/// to infer it from a percentage hitting 100.
pub fn send_files_with(
    transport: &mut impl AirDropTransport,
    sender: &SenderConfig,
    files: &[FileToSend],
    transfer_id: &str,
    sink: &crate::progress::Sink,
) -> Result<()> {
    sink.stage("discover");
    info!("POST /Discover");
    let req = build_discover_request(sender);
    let resp = transport.send(&req)?;
    let receiver = parse_discover_response(&resp)?;
    info!(receiver = %receiver.computer_name, "receiver appeared");

    sink.stage("ask");
    info!(transfer_id, "POST /Ask");
    let req = build_ask_request(sender, transfer_id, files);
    let resp = transport.send(&req)?;
    if resp.status != 200 {
        anyhow::bail!("/Ask rejected (status {})", resp.status);
    }
    info!("receiver accepted transfer");

    sink.stage("upload");
    info!("POST /Upload ({} files)", files.len());
    let mut req = build_upload_request(files);
    // Re-frame the /Upload body as chunked + Expect:100-continue, mimicking
    // a real Apple sender. The server's Expect:100-continue + flush path is
    // exercised (commit fix for the /Upload stall), and read_full_response
    // on the client side skips the interim 100. The in-memory transport test
    // still works because format_request → parse_request transparently
    // decodes the chunked body before handle_post sees it.
    req.headers.insert("transfer-encoding".into(), "chunked".into());
    req.headers.insert("expect".into(), "100-continue".into());
    req.body = chunk_frame(&req.body);
    let resp = transport.send_with_progress(&req, &mut |written, total| {
        sink.progress(written, total);
    })?;
    if resp.status != 200 {
        anyhow::bail!("/Upload failed (status {})", resp.status);
    }
    let total: usize = files.iter().map(|f| f.data.len()).sum();
    info!(files = files.len(), bytes = total, "upload complete");
    sink.sent(total as u64, &receiver.computer_name);
    Ok(())
}

// ---- real TLS transport ----

use crate::http::{format_request, parse_response};
use rustls::ClientConfig;
use std::io::BufReader;
use std::net::{SocketAddr, TcpStream};
use std::sync::Arc;
use std::time::Duration;

/// A real HTTP-over-TLS transport targeting a single AirDrop receiver.
/// Opens one TLS TCP connection per request (matching the AirDrop flow where
/// each POST is a fresh connection from the sender).
pub struct RustlsTransport {
    config: Arc<ClientConfig>,
    peer: SocketAddr,
    connect_timeout: Duration,
    io_timeout: Duration,
}

impl RustlsTransport {
    pub fn new(config: Arc<ClientConfig>, peer: SocketAddr) -> Self {
        Self {
            config,
            peer,
            connect_timeout: Duration::from_secs(10),
            // Leave enough time for the peer's 45-second acceptance dialog.
            io_timeout: Duration::from_secs(70),
        }
    }
}

/// How much of the outgoing wire to hand to TLS between progress reports.
const SEND_CHUNK: usize = 64 * 1024;

impl AirDropTransport for RustlsTransport {
    fn send(&mut self, req: &HttpRequest) -> Result<HttpResponse> {
        self.send_with_progress(req, &mut |_, _| {})
    }

    fn send_with_progress(
        &mut self,
        req: &HttpRequest,
        progress: &mut dyn FnMut(u64, u64),
    ) -> Result<HttpResponse> {
        // Fresh connection per request (AirDrop sender opens new TLS each time).
        let tcp = TcpStream::connect_timeout(&self.peer, self.connect_timeout)?;
        tcp.set_read_timeout(Some(self.io_timeout))?;
        tcp.set_write_timeout(Some(self.io_timeout))?;
        let server_name = rustls::pki_types::ServerName::try_from("airdrop")?;
        let conn = rustls::ClientConnection::new(self.config.clone(), server_name)?;
        let mut tls = rustls::StreamOwned::new(conn, tcp);

        // Write the request in SEND_CHUNK pieces so the caller can render
        // progress. `write_all` on a TLS stream blocks on TCP backpressure, so
        // "bytes handed to TLS" tracks "bytes on the wire" closely enough for a
        // progress bar — and it is the only number available without peeking
        // into rustls internals.
        let wire = format_request(req);
        let total = wire.len() as u64;
        let mut written = 0u64;
        use std::io::Write as _;
        for piece in wire.chunks(SEND_CHUNK) {
            tls.write_all(piece)?;
            written += piece.len() as u64;
            progress(written, total);
        }
        tls.flush()?;

        // Read the full response.
        let mut reader = BufReader::new(&mut tls);
        let raw = read_full_response(&mut reader)?;
        let resp = parse_response(&raw)?;
        Ok(resp)
    }
}

/// Read a full HTTP response (status line + headers + Content-Length body)
/// from a BufRead. Mirrors `server::read_full_request` but for responses.
///
/// Handles interim `1xx` responses per RFC 7231 §6.2: a client MUST be able
/// to parse one or more 1xx responses received prior to a final response,
/// even if it did not expect a 100-Continue. We skip any 1xx block and
/// continue reading until the final (non-1xx) response arrives.
fn read_full_response(reader: &mut impl std::io::BufRead) -> Result<Vec<u8>> {
    loop {
        let mut buf = Vec::new();
        loop {
            let mut line = Vec::new();
            let n = reader.read_until(b'\n', &mut line)?;
            if n == 0 {
                break;
            }
            buf.extend_from_slice(&line);
            if buf.ends_with(b"\r\n\r\n") {
                break;
            }
        }
        if buf.is_empty() {
            anyhow::bail!("connection closed before response headers complete");
        }
        // If this is an interim 1xx response (100 Continue, 102 Processing,
        // …), discard it and read the next response block. RFC 7231 §6.2:
        // "a client MUST be able to parse one or more 1xx responses received
        // prior to a final response, even if the client does not expect a
        // 100-Continue status message."
        let head = std::str::from_utf8(&buf).unwrap_or("");
        let status_line = head.lines().next().unwrap_or("");
        let is_interim = status_line
            .split_whitespace()
            .nth(1)
            .and_then(|s| s.parse::<u16>().ok())
            .map(|status| (100..200).contains(&status))
            .unwrap_or(false);
        if is_interim {
            continue;
        }
        // Final response — read the Content-Length-delimited body.
        if let Some(cl) = parse_header_value(head, "content-length") {
            let len: usize = cl.parse().unwrap_or(0);
            let mut body = vec![0u8; len];
            reader.read_exact(&mut body)?;
            buf.extend_from_slice(&body);
        }
        return Ok(buf);
    }
}

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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::chunked::decode_chunked;
    use crate::dvzip::dvzip_to_cpio;
    use crate::cpio::read_odc_cpio;
    use crate::http::{format_request, parse_request};
    use crate::handlers::handle_post;
    use crate::plist_impl::ReceiverConfig;

    fn sender() -> SenderConfig {
        SenderConfig::with_sender_id(
            "luftlift-sender",
            "MacBookPro",
            "com.luftlift.app",
            vec![0xDE, 0xAD, 0xBE, 0xEF, 0xCA, 0xFE],
        )
    }

    #[test]
    fn tls_handshake_with_silent_peer_times_out() {
        use std::net::TcpListener;
        use std::time::Instant;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let peer = listener.local_addr().unwrap();
        let (done, stop) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            let (_socket, _) = listener.accept().unwrap();
            // Accept TCP but never answer TLS. A broken client must not leave
            // this test hanging indefinitely.
            let _ = stop.recv_timeout(Duration::from_secs(2));
        });
        let config = Arc::new(crate::tls::build_client_config().unwrap());
        let mut transport = RustlsTransport::new(config, peer);
        transport.io_timeout = Duration::from_millis(80);
        let start = Instant::now();
        let result = transport.send(&build_discover_request(&sender()));
        let elapsed = start.elapsed();
        let _ = done.send(());
        worker.join().unwrap();
        assert!(result.is_err());
        assert!(elapsed < Duration::from_secs(1), "silent TLS peer stalled for {elapsed:?}");
    }

    // ---- build_discover_request ----

    #[test]
    fn build_discover_request_is_binary_plist_post() {
        let req = build_discover_request(&sender());
        assert_eq!(req.method, "POST");
        assert_eq!(req.path, "/Discover");
        assert_eq!(req.header("content-type"), Some("application/x-apple-plist"));
        assert_eq!(&req.body[..8], b"bplist00");
        // The receiver handler should accept it and produce a response.
        let resp = handle_post(&req, &rcfg());
        assert_eq!(resp.response.status, 200);
    }

    // ---- build_ask_request ----

    #[test]
    fn build_ask_request_describes_files() {
        let files = vec![FileToSend {
            name: "photo.jpg".into(),
            uti_type: "public.jpeg".into(),
            data: vec![0xFF, 0xD8, 0xFF],
        }];
        let req = build_ask_request(&sender(), "TID-1", &files);
        assert_eq!(req.path, "/Ask");
        // Receiver should be able to parse our Ask and accept.
        let resp = handle_post(&req, &rcfg());
        assert_eq!(resp.response.status, 200);
    }

    #[test]
    fn build_ask_request_matches_opendrop_file_offer_types() {
        // Independent protocol reference: seemoo-lab/opendrop client.py
        // send_ask uses String service_id, Files, and no Items for files.
        let files = vec![FileToSend {
            name: "note.txt".into(),
            uti_type: "public.data".into(),
            data: b"hello".to_vec(),
        }];
        let req = build_ask_request(&sender(), "TID-SID", &files);
        let val = Value::from_reader(std::io::Cursor::new(&req.body)).unwrap();
        let dict = val.as_dictionary().unwrap();
        let sid = dict.get("SenderID").expect("SenderID must be present in Ask body");
        assert_eq!(sid.as_string(), Some("deadbeefcafe"));
        assert!(dict.get("Items").is_none());
        assert_eq!(dict.get("ConvertMediaFormats").unwrap().as_boolean(), Some(false));
        let offer = dict.get("Files").unwrap().as_array().unwrap()[0].as_dictionary().unwrap();
        assert_eq!(offer.get("FileIsDirectory").unwrap().as_boolean(), Some(false));
        assert_eq!(offer.get("FileBomPath").unwrap().as_string(), Some("./note.txt"));
    }

    #[test]
    fn sender_config_new_generates_non_empty_sender_id() {
        let cfg = SenderConfig::new("n", "m", "b");
        assert!(!cfg.sender_id.is_empty(), "default SenderID must not be empty");
        // Two constructions in the same process should be distinct (time-seeded).
        let other = SenderConfig::new("n", "m", "b");
        assert_ne!(cfg.sender_id, other.sender_id, "SenderIDs should differ");
    }

    // ---- build_upload_request ----

    #[test]
    fn build_upload_request_roundtrips_through_receiver() {
        let files = vec![
            FileToSend {
                name: "hello.txt".into(),
                uti_type: "public.data".into(),
                data: b"hi from luftlift".to_vec(),
            },
            FileToSend {
                name: "sweep.bin".into(),
                uti_type: "public.data".into(),
                data: (0u8..=255).collect(),
            },
        ];
        let req = build_upload_request(&files);
        assert_eq!(req.header("content-type"), Some("application/x-dvzip"));
        assert_eq!(req.path, "/Upload");

        // The receiver handler should decode it and extract the files.
        let resp = handle_post(&req, &rcfg());
        assert_eq!(resp.response.status, 200);
        assert_eq!(resp.received_files.len(), 2);
        assert_eq!(resp.received_files[0].name, "hello.txt");
        assert_eq!(resp.received_files[0].bytes, b"hi from luftlift");
        assert_eq!(resp.received_files[1].name, "sweep.bin");
        assert_eq!(resp.received_files[1].bytes, (0u8..=255).collect::<Vec<u8>>());
    }

    #[test]
    fn build_upload_request_body_is_valid_dvzip() {
        let files = vec![FileToSend {
            name: "x".into(),
            uti_type: "public.data".into(),
            data: b"abc".to_vec(),
        }];
        let req = build_upload_request(&files);
        // Directly decode the dvzip body → CPIO → entries.
        let cpio = dvzip_to_cpio(&req.body);
        assert_eq!(&cpio[..6], b"070707");
        let entries = read_odc_cpio(&cpio);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].name, "x");
        assert_eq!(entries[0].data, b"abc");
    }

    // ---- parse_discover_response ----

    #[test]
    fn parse_discover_response_extracts_receiver_name() {
        let resp = handle_post(&build_discover_request(&sender()), &rcfg()).response;
        let recv = parse_discover_response(&resp).unwrap();
        assert_eq!(recv.computer_name, "luftlift-pc");
        assert_eq!(recv.computer_model, "MacBookPro");
    }

    #[test]
    fn parse_discover_response_errors_on_non_200() {
        let resp = HttpResponse { status: 404, body: Vec::new(), headers: Vec::new() };
        assert!(parse_discover_response(&resp).is_err());
    }

    // ---- end-to-end send via in-memory transport ----

    /// An in-memory transport that routes requests through our own server
    /// handler — proving the client requests and server handler interoperate.
    struct InMemoryTransport {
        receiver: ReceiverConfig,
    }
    impl AirDropTransport for InMemoryTransport {
        fn send(&mut self, req: &HttpRequest) -> Result<HttpResponse> {
            // Serialise the request, then re-parse to exercise the wire format.
            let wire = format_request(req);
            let parsed = parse_request(&wire).unwrap();
            let result = handle_post(&parsed, &self.receiver);
            Ok(result.response)
        }
    }

    fn rcfg() -> ReceiverConfig {
        ReceiverConfig {
            computer_name: "luftlift-pc".into(),
            computer_model: "MacBookPro".into(),
            record_data: None,
        }
    }

    #[test]
    fn send_files_end_to_end_succeeds_via_in_memory_transport() {
        let mut transport = InMemoryTransport { receiver: rcfg() };
        let files = vec![FileToSend {
            name: "note.txt".into(),
            uti_type: "public.data".into(),
            data: b"hello world".to_vec(),
        }];
        send_files(&mut transport, &sender(), &files, "TID-E2E").unwrap();
    }

    // ---- send progress events ----

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

    fn cap_events(buf: &CapBuf) -> Vec<serde_json::Value> {
        String::from_utf8(buf.0.lock().unwrap().clone())
            .unwrap()
            .lines()
            .map(|l| serde_json::from_str(l).unwrap())
            .collect()
    }

    #[test]
    fn send_files_with_reports_the_three_stages_and_completion() {
        // The send sheet drives its label off `stage` and its bar off
        // `progress`, then flips to "done" on `sent`. Assert the exact order so
        // a refactor cannot silently leave the UI stuck on "正在查找…".
        let mut transport = InMemoryTransport { receiver: rcfg() };
        let buf = CapBuf::default();
        let sink = crate::progress::Sink::to_writer(buf.clone());
        let files = vec![FileToSend {
            name: "note.txt".into(),
            uti_type: "public.data".into(),
            data: b"hello world".to_vec(),
        }];
        send_files_with(&mut transport, &sender(), &files, "TID-P", &sink).unwrap();

        let events = cap_events(&buf);
        let stages: Vec<&str> = events
            .iter()
            .filter(|e| e["event"] == "stage")
            .map(|e| e["stage"].as_str().unwrap())
            .collect();
        assert_eq!(stages, ["discover", "ask", "upload"]);

        let sent = events.iter().find(|e| e["event"] == "sent").expect("sent event");
        assert_eq!(sent["bytes"], 11, "the payload size, not the dvzip size");
        assert_eq!(sent["peer"], "luftlift-pc", "the name the receiver reported in /Discover");

        // The default transport impl cannot see the wire, so it reports one
        // (total,total) at the end — the contract the real TLS transport refines.
        let progress: Vec<&serde_json::Value> =
            events.iter().filter(|e| e["event"] == "progress").collect();
        assert!(!progress.is_empty(), "an upload must report at least one progress event");
    }

    #[test]
    fn send_files_with_reports_a_rejection_as_an_error_and_no_sent() {
        // A refused /Ask is the common real failure (the peer's screen is
        // locked, or "Contacts Only"). The UI must get an error, not a bar
        // that stalls at 0 %.
        struct RefusingTransport;
        impl AirDropTransport for RefusingTransport {
            fn send(&mut self, req: &HttpRequest) -> Result<HttpResponse> {
                if req.path == "/Discover" {
                    return Ok(HttpResponse {
                        status: 200,
                        body: crate::plist_impl::build_discover_response(&rcfg()),
                        headers: Vec::new(),
                    });
                }
                Ok(HttpResponse { status: 403, body: Vec::new(), headers: Vec::new() })
            }
        }
        let buf = CapBuf::default();
        let sink = crate::progress::Sink::to_writer(buf.clone());
        let files = vec![FileToSend {
            name: "x".into(),
            uti_type: "public.data".into(),
            data: b"y".to_vec(),
        }];
        let err = send_files_with(&mut RefusingTransport, &sender(), &files, "T", &sink);
        assert!(err.is_err(), "a 403 from /Ask must surface as an error");
        let events = cap_events(&buf);
        assert!(
            !events.iter().any(|e| e["event"] == "sent"),
            "no completion event for a rejected transfer"
        );
        // No `upload` stage either: we must not claim to be uploading after a
        // rejection.
        assert!(!events.iter().any(|e| e["event"] == "stage" && e["stage"] == "upload"));
    }

    #[test]
    fn send_files_handles_chunked_request_bodies() {
        // Verify our requests use Content-Length by default (the body is a
        // binary plist, so inspect only the header section of the wire bytes).
        let req = build_ask_request(&sender(), "TID-C", &[]);
        let wire = format_request(&req);
        let header_end = wire.windows(4).position(|w| w == b"\r\n\r\n").unwrap();
        let headers = std::str::from_utf8(&wire[..header_end]).unwrap();
        assert!(headers.contains("Content-Length"));
        // And our chunked decoder handles a hand-framed body (iOS-18 path).
        let chunked = b"3\r\nabc\r\n0\r\n\r\n";
        assert_eq!(decode_chunked(chunked).unwrap(), b"abc");
    }

    // ---- read_full_response: interim 1xx handling ----

    #[test]
    fn read_full_response_skips_interim_100_continue() {
        // A receiver that sends 100 Continue before the final 200 (RFC 7231
        // §6.2: client MUST parse one or more 1xx responses before the final).
        let raw = b"HTTP/1.1 100 Continue\r\n\r\n\
                    HTTP/1.1 200 OK\r\n\
                    Content-Type: application/x-apple-plist\r\n\
                    Content-Length: 5\r\n\
                    \r\n\
                    hello";
        let mut reader = std::io::Cursor::new(&raw[..]);
        let bytes = read_full_response(&mut reader).unwrap();
        let resp = crate::http::parse_response(&bytes).unwrap();
        assert_eq!(resp.status, 200, "must skip the 100 Continue and return the final 200");
        assert_eq!(resp.body, b"hello");
    }

    #[test]
    fn read_full_response_skips_multiple_1xx_responses() {
        // RFC 7231 allows multiple 1xx responses before the final one.
        let raw = b"HTTP/1.1 100 Continue\r\n\r\n\
                    HTTP/1.1 102 Processing\r\n\r\n\
                    HTTP/1.1 200 OK\r\n\
                    Content-Length: 2\r\n\
                    \r\n\
                    ok";
        let mut reader = std::io::Cursor::new(&raw[..]);
        let bytes = read_full_response(&mut reader).unwrap();
        let resp = crate::http::parse_response(&bytes).unwrap();
        assert_eq!(resp.status, 200);
        assert_eq!(resp.body, b"ok");
    }

    #[test]
    fn read_full_response_no_interim_returns_directly() {
        // No 1xx: just the final response, as before.
        let raw = b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc";
        let mut reader = std::io::Cursor::new(&raw[..]);
        let bytes = read_full_response(&mut reader).unwrap();
        let resp = crate::http::parse_response(&bytes).unwrap();
        assert_eq!(resp.status, 200);
        assert_eq!(resp.body, b"abc");
    }

    // ---- chunk_frame + Apple-style Upload ----

    #[test]
    fn chunk_frame_single_chunk_roundtrips_through_decode_chunked() {
        let body = b"hello world";
        let framed = chunk_frame(body);
        // Must be: b\r\nhello world\r\n0\r\n\r\n
        assert_eq!(decode_chunked(&framed).unwrap(), body);
    }

    #[test]
    fn chunk_frame_empty_body_yields_just_terminator() {
        let framed = chunk_frame(b"");
        assert_eq!(framed, b"0\r\n\r\n");
        assert_eq!(decode_chunked(&framed).unwrap(), b"");
    }

    #[test]
    fn send_files_upload_uses_chunked_and_expect_100_continue() {
        // Verify the /Upload re-frames the body as chunked + Expect:100-continue,
        // mimicking a real Apple sender. Use the InMemoryTransport (which
        // serialises/deserialises through the wire format) and intercept the
        // upload request by capturing the wire bytes it produces.
        struct WireCapturingTransport;
        impl AirDropTransport for WireCapturingTransport {
            fn send(&mut self, req: &HttpRequest) -> Result<HttpResponse> {
                // For /Upload, verify the headers are set correctly.
                if req.path == "/Upload" {
                    assert_eq!(
                        req.header("transfer-encoding"),
                        Some("chunked"),
                        "Upload must use chunked to mimic Apple sender"
                    );
                    assert_eq!(
                        req.header("expect"),
                        Some("100-continue"),
                        "Upload must send Expect:100-continue to mimic Apple sender"
                    );
                    // The body must be chunk-framed (not raw dvzip) — decode it.
                    let decoded =
                        decode_chunked(&req.body).expect("Upload body must be chunked-framed");
                    let cpio = dvzip_to_cpio(&decoded);
                    let entries = read_odc_cpio(&cpio);
                    assert_eq!(entries.len(), 1);
                    assert_eq!(entries[0].name, "f.txt");
                }
                // Return synthetic responses so send_files progresses.
                if req.path == "/Discover" {
                    let cfg = rcfg();
                    Ok(HttpResponse {
                        status: 200,
                        body: crate::plist_impl::build_discover_response(&cfg),
                        headers: Vec::new(),
                    })
                } else {
                    Ok(HttpResponse { status: 200, body: Vec::new(), headers: Vec::new() })
                }
            }
        }

        let mut transport = WireCapturingTransport;
        let files = vec![FileToSend {
            name: "f.txt".into(),
            uti_type: "public.data".into(),
            data: b"data".to_vec(),
        }];
        send_files(&mut transport, &sender(), &files, "TID-X").unwrap();
    }
}
