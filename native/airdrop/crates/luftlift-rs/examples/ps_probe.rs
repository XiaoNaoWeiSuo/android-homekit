//! Probe pair-setup across HAP methods.
//!
//! `rpFl=0x20000` suggests this Mac does not offer PIN pairing, and method 0
//! got no answer in earlier runs. This sweeps methods 0..=4 (pair setup, pair
//! setup with auth, pair verify, add pairing, remove pairing) on fresh
//! connections so the conclusion rests on measurements rather than the flag
//! heuristic.
//!
//! Usage: `ps_probe [ip] [port] [interface]` (defaults: 127.0.0.1 61156)

use luftlift_rs::netutil;
use luftlift_rs::rapport::{self, frame, tlv8, Map, Value};
use std::io::Write;
use std::net::{IpAddr, TcpStream};
use std::time::Duration;

fn main() {
    let mut args = std::env::args().skip(1);
    let host: IpAddr = args
        .next()
        .unwrap_or_else(|| "127.0.0.1".to_string())
        .parse()
        .expect("ip");
    let port: u16 = args.next().and_then(|s| s.parse().ok()).unwrap_or(61156);
    let scope = args.next().as_deref().and_then(netutil::if_index_for);
    let only: Option<u8> = args.next().and_then(|s| s.parse().ok());
    let addr = netutil::connect_addr_with_scope(host, port, scope);
    match only {
        Some(method) => println!("probing PS_Start method {method} on {addr}"),
        None => println!("probing PS_Start methods 0..=4 on {addr}"),
    }

    let methods: Vec<u8> = match only {
        Some(method) => vec![method],
        None => (0u8..=4).collect(),
    };
    for method in methods {
        let mut stream = match TcpStream::connect_timeout(&addr, Duration::from_secs(5)) {
            Ok(stream) => stream,
            Err(e) => {
                println!("method {method}: connect failed: {e}");
                continue;
            }
        };
        stream.set_read_timeout(Some(Duration::from_secs(4))).ok();
        let _ = rapport::read_frame(&mut stream); // NoOp preamble

        let tlv = tlv8::encode(&[(tlv8::METHOD, &[method]), (tlv8::SEQ_NO, &[1u8])]);
        let mut body = Map::new();
        body.insert("_pd", tlv).insert("_pwTy", 1i64);
        let payload = rapport::encode(&body.to_value());
        rapport::write_frame(&mut stream, &rapport::Frame::new(frame::PS_START, payload)).ok();
        stream.flush().ok();

        match rapport::read_frame(&mut stream) {
            Ok(Some(response)) => {
                let mut summary = format!(
                    "method {method}: type={} len={}",
                    response.kind,
                    response.payload.len()
                );
                if let Ok(Value::Dict(map)) = rapport::decode_exact(&response.payload) {
                    if let Some(Value::Bytes(pd)) = map.get("_pd") {
                        let tlv = tlv8::decode(pd);
                        summary.push_str(&format!(
                            " tlv={:?}",
                            tlv.iter().map(|(t, _)| *t).collect::<Vec<_>>()
                        ));
                        if let Some(error) = tlv8::get(&tlv, tlv8::ERROR) {
                            summary.push_str(&format!(" ERROR={error:?}"));
                        }
                        if let Some(salt) = tlv8::get(&tlv, tlv8::SALT) {
                            summary.push_str(&format!(" salt_len={}", salt.len()));
                        }
                        if let Some(key) = tlv8::get(&tlv, tlv8::PUBLIC_KEY) {
                            summary.push_str(&format!(" pubkey_len={}", key.len()));
                        }
                    }
                }
                println!("{summary}");
            }
            Ok(None) => println!("method {method}: EOF (server closed)"),
            Err(e) => println!("method {method}: no answer ({e})"),
        }
    }
}
