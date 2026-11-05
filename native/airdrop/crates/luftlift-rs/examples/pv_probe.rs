//! Minimal pair-verify probe: connect, send PV_Start, decrypt M2.
//!
//! Isolated from `rapport_probe` on purpose: that tool first sends several
//! unauthenticated OPACK commands, and macOS 27's rapportd appears to throttle
//! or drop pair-verify attempts that follow them. This one does nothing else,
//! which makes the handshake result unambiguous.
//!
//! Usage: `pv_probe [ip] [port] [interface]` (defaults: 127.0.0.1 61156)

use luftlift_rs::netutil;
use ed25519_dalek::{Signer, SigningKey};
use luftlift_rs::rapport::{self, frame, pair_verify, tlv8, Map, Value};
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
    let addr = netutil::connect_addr_with_scope(host, port, scope);

    println!("connecting to {addr}");
    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(5)).expect("connect");
    stream.set_read_timeout(Some(Duration::from_secs(6))).ok();
    if let Ok(Some(preamble)) = rapport::read_frame(&mut stream) {
        println!(
            "<- preamble type={} len={}",
            preamble.kind,
            preamble.payload.len()
        );
    }

    let secret = pair_verify::generate_secret();
    let public = pair_verify::public_bytes(&secret);
    let tlv = tlv8::encode(&[(tlv8::SEQ_NO, &[1u8]), (tlv8::PUBLIC_KEY, &public)]);
    let mut body = Map::new();
    body.insert("_pd", tlv).insert("_auTy", 4i64);
    let payload = rapport::encode(&body.to_value());
    println!("-> PV_Start ({} bytes)", payload.len());
    rapport::write_frame(&mut stream, &rapport::Frame::new(frame::PV_START, payload)).ok();
    stream.flush().ok();

    match rapport::read_frame(&mut stream) {
        Ok(Some(response)) => {
            println!("<- type={} len={}", response.kind, response.payload.len());
            if response.kind != frame::PV_NEXT {
                println!("unexpected frame type");
                return;
            }
            let Value::Dict(map) = rapport::decode_exact(&response.payload).expect("opack") else {
                println!("not a dict");
                return;
            };
            let Some(Value::Bytes(pd)) = map.get("_pd") else {
                println!("no _pd");
                return;
            };
            let tlv = tlv8::decode(pd);
            println!(
                "   M2 tlv types={:?}",
                tlv.iter().map(|(t, _)| *t).collect::<Vec<_>>()
            );
            let Some(server_public) =
                tlv8::get(&tlv, tlv8::PUBLIC_KEY).and_then(|b| <[u8; 32]>::try_from(b).ok())
            else {
                println!("no server public key");
                return;
            };
            let Some(encrypted) = tlv8::get(&tlv, tlv8::ENCRYPTED_DATA) else {
                println!("no encrypted data");
                return;
            };
            let shared = pair_verify::shared_secret(&secret, server_public);
            let key = pair_verify::encrypt_key(&shared);
            match pair_verify::decrypt(&key, "PV-Msg02", encrypted) {
                Some(plain) => {
                    let inner = tlv8::decode(&plain);
                    println!(
                        "   DECRYPTED M2 {} bytes types={:?}",
                        plain.len(),
                        inner.iter().map(|(t, _)| *t).collect::<Vec<_>>()
                    );
                    if let Some(id) = tlv8::get(&inner, tlv8::IDENTIFIER) {
                        println!(
                            "   identifier len={} hex={}",
                            id.len(),
                            id.iter().map(|b| format!("{b:02x}")).collect::<String>()
                        );
                    }
                    if let Some(sig) = tlv8::get(&inner, tlv8::SIGNATURE) {
                        println!("   signature len={}", sig.len());
                    }

                    // M3: sign the transcript with a locally generated
                    // long-term key. The server does not know this key (no
                    // prior pairing), so this is the experiment that shows
                    // exactly how it rejects an unpaired client.
                    let mut seed = [0u8; 32];
                    {
                        use std::io::Read;
                        if let Ok(mut f) = std::fs::File::open("/dev/urandom") {
                            let _ = f.read_exact(&mut seed);
                        }
                    }
                    let signing = SigningKey::from_bytes(&seed);
                    let client_id = format!(
                        "{:08X}-{:04X}-{:04X}-{:04X}-{:012X}",
                        u32::from_le_bytes(seed[0..4].try_into().unwrap()),
                        u16::from_le_bytes(seed[4..6].try_into().unwrap()),
                        u16::from_le_bytes(seed[6..8].try_into().unwrap()),
                        u16::from_le_bytes(seed[8..10].try_into().unwrap()),
                        u64::from_le_bytes(seed[10..18].try_into().unwrap()) & 0xffff_ffff_ffff
                    );
                    let mut transcript = Vec::new();
                    transcript.extend_from_slice(&public);
                    transcript.extend_from_slice(client_id.as_bytes());
                    transcript.extend_from_slice(&server_public);
                    let signature = signing.sign(&transcript);
                    let tlv = tlv8::encode(&[
                        (tlv8::IDENTIFIER, client_id.as_bytes()),
                        (tlv8::SIGNATURE, &signature.to_bytes()),
                    ]);
                    if let Some(sealed) = pair_verify::encrypt(&key, "PV-Msg03", &tlv) {
                        let pd = tlv8::encode(&[
                            (tlv8::SEQ_NO, &[3u8]),
                            (tlv8::ENCRYPTED_DATA, &sealed),
                        ]);
                        let mut m3 = Map::new();
                        m3.insert("_pd", pd);
                        let payload = rapport::encode(&m3.to_value());
                        println!("-> PV_Next M3 ({} bytes)", payload.len());
                        rapport::write_frame(
                            &mut stream,
                            &rapport::Frame::new(frame::PV_NEXT, payload),
                        )
                        .ok();
                        stream.flush().ok();
                        match rapport::read_frame(&mut stream) {
                            Ok(Some(resp)) => {
                                println!("<- type={} len={}", resp.kind, resp.payload.len());
                                if let Ok(Value::Dict(map)) =
                                    rapport::decode_exact(&resp.payload)
                                {
                                    if let Some(Value::Bytes(pd)) = map.get("_pd") {
                                        let tlv = tlv8::decode(pd);
                                        println!(
                                            "   M4 tlv types={:?}",
                                            tlv.iter().map(|(t, _)| *t).collect::<Vec<_>>()
                                        );
                                        if let Some(err) =
                                            tlv8::get(&tlv, tlv8::ERROR)
                                        {
                                            println!("   ERROR code={err:?}");
                                        }
                                    }
                                }
                            }
                            Ok(None) => println!("<- EOF after M3 (server closed)"),
                            Err(e) => println!("<- read error after M3: {e}"),
                        }
                    }
                }
                None => println!("   decrypt FAILED"),
            }
        }
        Ok(None) => println!("<- EOF (server closed)"),
        Err(e) => println!("<- read error: {e}"),
    }
}
