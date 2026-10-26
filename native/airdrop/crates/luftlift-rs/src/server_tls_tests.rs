//! Loopback TLS tests through the production connection/consent/storage path.
//! No AWDL, mDNS, radio, external peer, or Apple identity required.
use super::*;
use crate::client::{
    build_ask_request, build_discover_request, build_upload_request, FileToSend, SenderConfig,
};
use crate::http::{format_request, HttpResponse};
use std::io::Read;
use std::net::Shutdown;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

struct Server {
    address: SocketAddr,
    approvals: Approvals,
    stopping: Arc<AtomicBool>,
    sockets: Arc<Mutex<Vec<TcpStream>>>,
    worker: Option<thread::JoinHandle<()>>,
    root: PathBuf,
    old_env: Option<std::ffi::OsString>,
}

impl Server {
    fn start() -> Self {
        let root = std::env::temp_dir().join(format!(
            "marionette-tls-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(root.join("requests")).unwrap();
        std::fs::create_dir_all(root.join("inbox")).unwrap();
        let old_env = std::env::var_os("MARIONETTE_APPROVAL_DIR");
        std::env::set_var("MARIONETTE_APPROVAL_DIR", root.join("requests"));
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        listener.set_nonblocking(true).unwrap();
        let cert = crate::tls::generate_self_signed_cert().unwrap();
        let tls = Arc::new(crate::tls::build_server_config(&cert).unwrap());
        let cfg = Arc::new(ReceiverConfig {
            computer_name: "TLS receiver".into(),
            computer_model: "test".into(),
            record_data: None,
        });
        let approvals: Approvals = Arc::new(Mutex::new(Default::default()));
        let stopping = Arc::new(AtomicBool::new(false));
        let sockets = Arc::new(Mutex::new(Vec::new()));
        let worker = {
            let stop = stopping.clone();
            let sockets = sockets.clone();
            let approvals = approvals.clone();
            let output = Arc::new(Some(root.join("inbox")));
            thread::spawn(move || {
                let mut workers = Vec::new();
                while !stop.load(Ordering::Acquire) {
                    match listener.accept() {
                        Ok((socket, _)) => {
                            socket.set_nonblocking(false).unwrap();
                            sockets.lock().unwrap().push(socket.try_clone().unwrap());
                            let (tls, cfg, output, approvals) =
                                (tls.clone(), cfg.clone(), output.clone(), approvals.clone());
                            workers.push(thread::spawn(move || {
                                let result = handle_tls_connection(
                                    socket,
                                    tls,
                                    cfg,
                                    output,
                                    None,
                                    Arc::new(Mutex::new(String::new())),
                                    Arc::new(IntrospectState::new()),
                                    Sink::disabled(),
                                    approvals,
                                );
                                if let Err(error) = result {
                                    eprintln!("test server: {error}");
                                }
                            }));
                        }
                        Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                            thread::sleep(Duration::from_millis(5))
                        }
                        Err(e) => panic!("accept: {e}"),
                    }
                }
                for socket in sockets.lock().unwrap().iter() {
                    let _ = socket.shutdown(Shutdown::Both);
                }
                for worker in workers {
                    worker.join().unwrap();
                }
            })
        };
        Self {
            address,
            approvals,
            stopping,
            sockets,
            worker: Some(worker),
            root,
            old_env,
        }
    }
    fn connect(&self) -> BufReader<rustls::StreamOwned<rustls::ClientConnection, TcpStream>> {
        let socket = TcpStream::connect_timeout(&self.address, Duration::from_secs(2)).unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(4)))
            .unwrap();
        socket
            .set_write_timeout(Some(Duration::from_secs(4)))
            .unwrap();
        let config = Arc::new(crate::tls::build_client_config().unwrap());
        let conn = rustls::ClientConnection::new(config, "airdrop".try_into().unwrap()).unwrap();
        BufReader::new(rustls::StreamOwned::new(conn, socket))
    }
    fn answer(&self, accepted: bool) -> thread::JoinHandle<()> {
        let requests = self.root.join("requests");
        thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(3);
            while Instant::now() < deadline {
                for entry in std::fs::read_dir(&requests).unwrap().flatten() {
                    if entry.path().extension().is_some_and(|e| e == "json") {
                        std::fs::write(
                            entry.path().with_extension("decision"),
                            if accepted { "accept" } else { "reject" },
                        )
                        .unwrap();
                        return;
                    }
                }
                thread::sleep(Duration::from_millis(10));
            }
            panic!("consent prompt never appeared");
        })
    }
}
impl Drop for Server {
    fn drop(&mut self) {
        self.stopping.store(true, Ordering::Release);
        for socket in self.sockets.lock().unwrap().iter() {
            let _ = socket.shutdown(Shutdown::Both);
        }
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
        match self.old_env.take() {
            Some(value) => std::env::set_var("MARIONETTE_APPROVAL_DIR", value),
            None => std::env::remove_var("MARIONETTE_APPROVAL_DIR"),
        }
        let _ = std::fs::remove_dir_all(&self.root);
    }
}
fn sender() -> SenderConfig {
    SenderConfig::with_sender_id("TLS sender", "test", "test", vec![1, 2, 3])
}
fn files() -> Vec<FileToSend> {
    vec![FileToSend {
        name: "sample.bin".into(),
        uti_type: "public.data".into(),
        data: (0..8193).map(|i| (i % 251) as u8).collect(),
    }]
}
fn response(reader: &mut impl BufRead) -> HttpResponse {
    let mut raw = Vec::new();
    loop {
        let mut line = Vec::new();
        assert_ne!(
            reader.read_until(b'\n', &mut line).unwrap(),
            0,
            "EOF in HTTP response"
        );
        raw.extend(line);
        if raw.ends_with(b"\r\n\r\n") {
            break;
        }
        assert!(raw.len() < 8192);
    }
    let head = std::str::from_utf8(&raw).unwrap();
    let len = parse_header_value(head, "content-length")
        .map(|s| s.parse::<usize>().unwrap())
        .unwrap_or(0);
    assert!(len < 1024 * 1024);
    let start = raw.len();
    raw.resize(start + len, 0);
    reader.read_exact(&mut raw[start..]).unwrap();
    crate::http::parse_response(&raw).unwrap()
}
fn exchange(stream: &mut BufReader<impl Read + Write>, req: &HttpRequest) -> HttpResponse {
    stream.get_mut().write_all(&format_request(req)).unwrap();
    stream.get_mut().flush().unwrap();
    response(stream)
}

#[test]
fn tls_keepalive_consent_upload_is_consumed_across_connections() {
    let _env = crate::guard::tests::ENV_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let server = Server::start();
    let mut stream = server.connect();
    let discover = exchange(&mut stream, &build_discover_request(&sender()));
    assert_eq!(discover.status, 200);
    assert!(discover.body.starts_with(b"bplist00"));
    let answer = server.answer(true);
    assert_eq!(
        exchange(
            &mut stream,
            &build_ask_request(&sender(), "transfer-1", &files())
        )
        .status,
        200
    );
    answer.join().unwrap();

    // A real Expect client waits before sending chunked archive bytes.
    let upload = build_upload_request(&files());
    stream.get_mut().write_all(b"POST /Upload HTTP/1.1\r\nContent-Type: application/x-dvzip\r\nTransfer-Encoding: chunked\r\nExpect: 100-continue\r\n\r\n").unwrap();
    stream.get_mut().flush().unwrap();
    assert_eq!(response(&mut stream).status, 100);
    for chunk in upload.body.chunks(37) {
        write!(stream.get_mut(), "{:x}\r\n", chunk.len()).unwrap();
        stream.get_mut().write_all(chunk).unwrap();
        stream.get_mut().write_all(b"\r\n").unwrap();
    }
    stream.get_mut().write_all(b"0\r\n\r\n").unwrap();
    stream.get_mut().flush().unwrap();
    assert_eq!(response(&mut stream).status, 200);
    drop(stream);
    assert_eq!(
        std::fs::read(server.root.join("inbox/sample.bin")).unwrap(),
        files()[0].data
    );
    assert!(
        server.approvals.lock().unwrap().is_empty(),
        "same-connection upload must consume shared grant"
    );
    let mut retry = server.connect();
    retry
        .get_mut()
        .write_all(
            b"POST /Upload HTTP/1.1\r\nContent-Length: 900000\r\nExpect: 100-continue\r\n\r\n",
        )
        .unwrap();
    retry.get_mut().flush().unwrap();
    assert_eq!(
        response(&mut retry).status,
        403,
        "replay must be rejected before 100/body"
    );
    assert_eq!(
        std::fs::read_dir(server.root.join("inbox"))
            .unwrap()
            .count(),
        1
    );
}

#[test]
fn tls_rejected_ask_revokes_old_grant_and_other_methods_cannot_upload() {
    let _env = crate::guard::tests::ENV_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let server = Server::start();
    let peer = server.address.ip();
    grant_approval(&server.approvals, peer);
    let answer = server.answer(false);
    assert_eq!(
        exchange(
            &mut server.connect(),
            &build_ask_request(&sender(), "rejected", &files())
        )
        .status,
        403
    );
    answer.join().unwrap();
    assert!(!take_approval(&server.approvals, peer));
    for method in ["GET", "PUT", "HEAD"] {
        let mut stream = server.connect();
        write!(
            stream.get_mut(),
            "{method} /Upload HTTP/1.1\r\nContent-Length: 1000\r\nExpect: 100-continue\r\n\r\n"
        )
        .unwrap();
        stream.get_mut().flush().unwrap();
        assert_eq!(response(&mut stream).status, 405);
    }
    assert_eq!(
        std::fs::read_dir(server.root.join("inbox"))
            .unwrap()
            .count(),
        0
    );
}

#[test]
fn tls_sender_reconnects_and_saves_duplicate_names_without_overwrite() {
    let _env = crate::guard::tests::ENV_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let server = Server::start();
    let config = Arc::new(crate::tls::build_client_config().unwrap());
    let mut client = crate::client::RustlsTransport::new(config, server.address);
    for i in 0..2 {
        let answer = server.answer(true);
        let mut batch = files();
        batch[0].data.push(i);
        crate::client::send_files(&mut client, &sender(), &batch, &format!("reconnect-{i}"))
            .unwrap();
        answer.join().unwrap();
        let filename = if i == 0 { "sample.bin" } else { "1-sample.bin" };
        assert_eq!(
            std::fs::read(server.root.join("inbox").join(filename)).unwrap(),
            batch[0].data
        );
    }
    assert!(server.approvals.lock().unwrap().is_empty());
    let first = std::fs::read(server.root.join("inbox/sample.bin")).unwrap();
    assert_eq!(first.last(), Some(&0));
    assert_eq!(
        std::fs::read_dir(server.root.join("inbox"))
            .unwrap()
            .count(),
        2
    );
}

#[test]
fn tls_expired_consent_does_not_start_reading_upload() {
    let _env = crate::guard::tests::ENV_LOCK
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let server = Server::start();
    server.approvals.lock().unwrap().insert(
        server.address.ip(),
        Instant::now() - APPROVAL_TTL - Duration::from_secs(1),
    );
    let mut stream = server.connect();
    stream
        .get_mut()
        .write_all(
            b"POST /Upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\nExpect: 100-continue\r\n\r\n",
        )
        .unwrap();
    stream.get_mut().flush().unwrap();
    assert_eq!(response(&mut stream).status, 403);
    assert_eq!(
        std::fs::read_dir(server.root.join("inbox"))
            .unwrap()
            .count(),
        0
    );
}
