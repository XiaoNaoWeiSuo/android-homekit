//! Device-side probe: speak Rapport to `rapportd` on port 61156.
//!
//! Exercises `luftlift_rs::rapport` (frame codec + OPACK) against a real
//! macOS 27 server. It connects, sends an unauthenticated `_systemInfo`,
//! prints every frame that comes back, then tries a `_sessionStart` for the
//! AirDrop app service.
//!
//! Usage: `rapport_probe [ip] [port]` — defaults to the Mac discovered by the
//! companion sweep (none by default), so pass the address explicitly.

use luftlift_rs::netutil;
use luftlift_rs::rapport::{self, frame, Map, Value};
use std::io::Write;
use std::net::{IpAddr, TcpStream};
use std::time::Duration;

fn main() {
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_ansi(false)
        .init();

    let mut args = std::env::args().skip(1);
    let host: IpAddr = args
        .next()
        .unwrap_or_else(|| "127.0.0.1".to_string())
        .parse()
        .expect("ip address");
    let port: u16 = args.next().and_then(|s| s.parse().ok()).unwrap_or(61156);
    let interface = args.next();
    let scope = interface.as_deref().and_then(netutil::if_index_for);
    let addr = netutil::connect_addr_with_scope(host, port, scope);

    println!("connecting to {addr} (scope {scope:?})");
    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(4)).expect("connect");
    stream
        .set_read_timeout(Some(Duration::from_secs(3)))
        .expect("timeout");

    // Drain the NoOp keepalive the server sends immediately.
    if let Ok(Some(f)) = rapport::read_frame(&mut stream) {
        println!("<- type={} len={} (preamble)", f.kind, f.payload.len());
    }

    let mut xid = 1u32;
    let info = {
        let mut map = Map::new();
        map.insert("_bf", 0i64)
            .insert("_cf", 512i64)
            .insert("_clFl", 128i64)
            .insert("_i", "aa:bb:cc:dd:ee:ff")
            .insert("_idsID", "aa:bb:cc:dd:ee:ff")
            .insert("_pubID", "aa:bb:cc:dd:ee:ff")
            .insert("_sf", 256i64)
            .insert("_sv", "170.18")
            .insert("model", "Android")
            .insert("name", "luftlift-probe");
        map.to_value()
    };

    for kind in [frame::U_OPACK, frame::E_OPACK] {
        let mut request = Map::new();
        request
            .insert("_i", "_systemInfo")
            .insert("_t", 2i64)
            .insert("_x", xid)
            .insert("_c", info.clone());
        let payload = rapport::encode(&request.to_value());
        println!("-> type={kind} _systemInfo ({} bytes)", payload.len());
        rapport::write_frame(&mut stream, &rapport::Frame::new(kind, payload)).expect("write");
        stream.flush().ok();
        match rapport::read_frame(&mut stream) {
            Ok(Some(response)) => println!(
                "<- type={} len={} body={:?}",
                response.kind,
                response.payload.len(),
                rapport::decode_exact(&response.payload)
                    .map(|v| format!("{v:?}"))
                    .unwrap_or_else(|e| format!("<decode: {e}>"))
            ),
            Ok(None) => println!("<- EOF"),
            Err(e) => println!("<- read error: {e}"),
        }
        xid += 1;
    }

    let mut start = Map::new();
    let mut session = Map::new();
    session
        .insert("_srvT", "com.apple.sharingd.AirDrop")
        .insert("_sid", 12345i64);
    start
        .insert("_i", "_sessionStart")
        .insert("_t", 2i64)
        .insert("_x", xid)
        .insert("_c", session.to_value());
    let payload = rapport::encode(&start.to_value());
    println!(
        "-> type={} _sessionStart AirDrop ({} bytes)",
        frame::E_OPACK,
        payload.len()
    );
    rapport::write_frame(&mut stream, &rapport::Frame::new(frame::E_OPACK, payload)).ok();
    stream.flush().ok();
    for _ in 0..3 {
        match rapport::read_frame(&mut stream) {
            Ok(Some(response)) => println!(
                "<- type={} len={} body={:?}",
                response.kind,
                response.payload.len(),
                rapport::decode_exact(&response.payload)
                    .map(|v| format!("{v:?}"))
                    .unwrap_or_else(|e| format!("<decode: {e}>"))
            ),
            Ok(None) => {
                println!("<- EOF");
                break;
            }
            Err(e) => {
                println!("<- read error: {e}");
                break;
            }
        }
    }

    // Pair-verify start with a REAL X25519 keypair, so the server's
    // PV_Next payload can be decrypted. The earlier random-bytes probe already
    // proved rapportd answers frame 5; this one reads M2.
    let secret = rapport::pair_verify::generate_secret();
    let ephemeral = rapport::pair_verify::public_bytes(&secret);
    let tlv = rapport::tlv8::encode(&[
        (rapport::tlv8::SEQ_NO, &[1u8]),
        (rapport::tlv8::PUBLIC_KEY, &ephemeral),
    ]);
    let mut pv = Map::new();
    pv.insert("_pd", tlv).insert("_auTy", 4i64);
    let payload = rapport::encode(&pv.to_value());
    println!(
        "-> type={} PV_Start ({} bytes, pubkey {:02x?}...)",
        frame::PV_START,
        payload.len(),
        &ephemeral[..4]
    );
    rapport::write_frame(&mut stream, &rapport::Frame::new(frame::PV_START, payload)).ok();
    stream.flush().ok();
    for _ in 0..3 {
        match rapport::read_frame(&mut stream) {
            Ok(Some(response)) => {
                println!(
                    "<- type={} len={}",
                    response.kind,
                    response.payload.len()
                );
                if response.kind == frame::PV_NEXT {
                    if let Ok(Value::Dict(map)) = rapport::decode_exact(&response.payload) {
                        if let Some(Value::Bytes(pd)) = map.get("_pd") {
                            let tlv = rapport::tlv8::decode(pd);
                            let types: Vec<u8> = tlv.iter().map(|(t, _)| *t).collect();
                            println!("   PV_Next tlv types={types:?}");
                            let server_pub = rapport::tlv8::get(&tlv, rapport::tlv8::PUBLIC_KEY)
                                .and_then(|b| <[u8; 32]>::try_from(b).ok());
                            let encrypted = rapport::tlv8::get(&tlv, rapport::tlv8::ENCRYPTED_DATA);
                            if let (Some(server_pub), Some(encrypted)) = (server_pub, encrypted) {
                                let shared = rapport::pair_verify::shared_secret(&secret, server_pub);
                                let key = rapport::pair_verify::encrypt_key(&shared);
                                match rapport::pair_verify::decrypt(&key, "PV-Msg02", encrypted) {
                                    Some(plain) => {
                                        let inner = rapport::tlv8::decode(&plain);
                                        let inner_types: Vec<u8> =
                                            inner.iter().map(|(t, _)| *t).collect();
                                        println!(
                                            "   DECRYPTED M2 ({} bytes) tlv types={inner_types:?}",
                                            plain.len()
                                        );
                                        if let Some(id) =
                                            rapport::tlv8::get(&inner, rapport::tlv8::IDENTIFIER)
                                        {
                                            println!(
                                                "   server identifier len={} hex={}",
                                                id.len(),
                                                id.iter().map(|b| format!("{b:02x}")).collect::<String>()
                                            );
                                        }
                                        if let Some(sig) =
                                            rapport::tlv8::get(&inner, rapport::tlv8::SIGNATURE)
                                        {
                                            println!("   server signature len={}", sig.len());
                                        }
                                    }
                                    None => println!("   decrypt failed (key/nonce mismatch)"),
                                }
                            }
                        }
                    }
                }
            }
            Ok(None) => {
                println!("<- EOF");
                break;
            }
            Err(e) => {
                println!("<- read error: {e}");
                break;
            }
        }
    }

    let _ = Value::Null;
}
