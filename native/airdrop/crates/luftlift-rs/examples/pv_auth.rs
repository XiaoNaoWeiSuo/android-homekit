//! Authenticated pair-verify + encrypted session probe.
//!
//! Uses the credentials written by `pair_setup` to finish HAP pair-verify
//! (M3 signed with the long-term key the Mac stored during pair-setup) and
//! then switches the connection to the encrypted frame format pyatv uses:
//! ChaCha20-Poly1305, 12-byte little-endian counter nonce, AAD = frame header.
//!
//! Session keys: HKDF-SHA512(ikm = X25519 shared, salt = "", info =
//! "ClientEncrypt-main" for our output, "ServerEncrypt-main" for input).
//!
//! Usage: `pv_auth [ip] [port] [interface] [service]`
//! Defaults: 10.12.1.5 61156 wlan0 com.apple.sharingd.AirDrop
//!
//! Credentials file (same layout as `pair_setup` writes):
//! `client_ltpk / client_ltsk / atv_id / client_id` hex or ASCII, one per line,
//! plus an optional 5th line with the server long-term public key.

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use luftlift_rs::netutil;
use luftlift_rs::rapport::{self, frame, pair_verify, tlv8, Map, Value};
use std::io::Write;
use std::net::{IpAddr, TcpStream};
use std::time::Duration;

const CREDENTIALS_ANDROID: &str = "/data/local/tmp/companion-credentials.txt";
const CREDENTIALS_HOST: &str = "/tmp/companion-credentials.txt";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let host: IpAddr = args
        .first()
        .map(|s| s.parse().expect("ip"))
        .unwrap_or_else(|| IpAddr::from([10, 12, 1, 5]));
    let port: u16 = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(61156);
    let interface = args.get(2).map(String::as_str).unwrap_or("wlan0");
    let service = args
        .get(3)
        .cloned()
        .unwrap_or_else(|| "com.apple.sharingd.AirDrop".to_string());
    let scope = netutil::if_index_for(interface);
    let addr = netutil::connect_addr_with_scope(host, port, scope);

    let creds = load_credentials();
    println!(
        "credentials: client_id={} atv_id={} server_ltpk={}",
        creds.client_id,
        creds.atv_id,
        if creds.atv_ltpk.is_some() {
            "yes"
        } else {
            "no"
        }
    );

    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(5)).expect("connect");
    stream.set_read_timeout(Some(Duration::from_secs(8))).ok();
    let _ = rapport::read_frame(&mut stream); // NoOp preamble

    // ---- M1 --------------------------------------------------------------
    let secret = pair_verify::generate_secret();
    let client_pub = pair_verify::public_bytes(&secret);
    let tlv = tlv8::encode(&[(tlv8::SEQ_NO, &[1u8]), (tlv8::PUBLIC_KEY, &client_pub)]);
    let mut body = Map::new();
    body.insert("_pd", tlv).insert("_auTy", 4i64);
    send(&mut stream, frame::PV_START, &body);

    // ---- M2 --------------------------------------------------------------
    let pd = read_pd(&mut stream).expect("M2");
    let tlv = tlv8::decode(&pd);
    let server_pub: [u8; 32] = tlv8::get(&tlv, tlv8::PUBLIC_KEY)
        .and_then(|b| b.try_into().ok())
        .expect("server public key");
    let encrypted = tlv8::get(&tlv, tlv8::ENCRYPTED_DATA).expect("M2 encrypted data");

    let shared = pair_verify::shared_secret(&secret, server_pub);
    let key = pair_verify::encrypt_key(&shared);
    let plain = pair_verify::decrypt(&key, "PV-Msg02", encrypted).expect("M2 decrypt");
    let inner = tlv8::decode(&plain);
    let server_id = tlv8::get(&inner, tlv8::IDENTIFIER)
        .expect("server id")
        .to_vec();
    let server_sig = tlv8::get(&inner, tlv8::SIGNATURE)
        .expect("server signature")
        .to_vec();
    println!(
        "M2 ok: server_id={} sig_len={}",
        String::from_utf8_lossy(&server_id),
        server_sig.len()
    );

    if let Some(server_ltpk) = creds.atv_ltpk {
        if let Ok(verifying) = VerifyingKey::from_bytes(&server_ltpk) {
            let mut transcript = Vec::new();
            transcript.extend_from_slice(&server_pub);
            transcript.extend_from_slice(&server_id);
            transcript.extend_from_slice(&client_pub);
            let ok = <[u8; 64]>::try_from(server_sig.as_slice())
                .ok()
                .map(|bytes| {
                    verifying
                        .verify_strict(&transcript, &Signature::from_bytes(&bytes))
                        .is_ok()
                })
                .unwrap_or(false);
            println!("M2 signature {}", if ok { "verified" } else { "MISMATCH" });
        }
    } else {
        println!("M2 signature not checked (no server_ltpk in credentials)");
    }

    // ---- M3 --------------------------------------------------------------
    let signing = SigningKey::from_bytes(&creds.ltsk);
    let mut transcript = Vec::new();
    transcript.extend_from_slice(&client_pub);
    transcript.extend_from_slice(creds.client_id.as_bytes());
    transcript.extend_from_slice(&server_pub);
    let signature = signing.sign(&transcript);
    let tlv = tlv8::encode(&[
        (tlv8::IDENTIFIER, creds.client_id.as_bytes()),
        (tlv8::SIGNATURE, &signature.to_bytes()),
    ]);
    let sealed = pair_verify::encrypt(&key, "PV-Msg03", &tlv).expect("M3 seal");
    let mut body = Map::new();
    body.insert(
        "_pd",
        tlv8::encode(&[(tlv8::SEQ_NO, &[3u8]), (tlv8::ENCRYPTED_DATA, &sealed)]),
    );
    send(&mut stream, frame::PV_NEXT, &body);

    // ---- M4 --------------------------------------------------------------
    let pd = match read_pd(&mut stream) {
        Some(pd) => pd,
        None => {
            println!("M4: no reply (server closed?)");
            return;
        }
    };
    let tlv = tlv8::decode(&pd);
    let types: Vec<u8> = tlv.iter().map(|(t, _)| *t).collect();
    println!("M4 tlv types={types:?}");
    if let Some(err) = tlv8::get(&tlv, tlv8::ERROR) {
        println!("M4 ERROR={err:?} -- pair-verify refused");
        return;
    }
    println!("PAIR-VERIFY OK: connection is authenticated");

    // ---- encrypted session ----------------------------------------------
    let out_key = pair_verify::hkdf_expand(b"", b"ClientEncrypt-main", &shared);
    let in_key = pair_verify::hkdf_expand(b"", b"ServerEncrypt-main", &shared);
    let mut cipher = Cipher::new(out_key, in_key);
    println!("encryption enabled (ClientEncrypt-main / ServerEncrypt-main)");

    let mut xid = 1i64;
    let info = system_info();
    let mut request = Map::new();
    request
        .insert("_i", "_systemInfo")
        .insert("_t", 2i64)
        .insert("_x", xid)
        .insert("_c", info);
    send_encrypted(&mut stream, &mut cipher, frame::E_OPACK, &request);
    print_reply(&mut stream, &mut cipher, "_systemInfo");

    xid += 1;
    let mut session = Map::new();
    session
        .insert("_srvT", service.clone())
        .insert("_sid", 0i64);
    let mut start = Map::new();
    start
        .insert("_i", "_sessionStart")
        .insert("_t", 2i64)
        .insert("_x", xid)
        .insert("_c", session);
    send_encrypted(&mut stream, &mut cipher, frame::E_OPACK, &start);
    print_reply(&mut stream, &mut cipher, "_sessionStart");
}

struct Credentials {
    ltsk: [u8; 32],
    client_id: String,
    atv_id: String,
    atv_ltpk: Option<[u8; 32]>,
}

fn credentials_path() -> &'static str {
    if cfg!(target_os = "android") {
        CREDENTIALS_ANDROID
    } else {
        CREDENTIALS_HOST
    }
}

fn load_credentials() -> Credentials {
    let path = credentials_path();
    let text = std::fs::read_to_string(path)
        .unwrap_or_else(|e| panic!("read {path}: {e} (run pair_setup first)"));
    let lines: Vec<&str> = text
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .collect();
    assert!(
        lines.len() >= 4,
        "credentials need 4 lines, got {}",
        lines.len()
    );
    let ltsk = hex32(lines[1]).expect("client_ltsk");
    let atv_id = String::from_utf8(hex_bytes(lines[2]).expect("atv_id hex"))
        .unwrap_or_else(|_| lines[2].to_string());
    let client_id = lines[3].to_string();
    let atv_ltpk = lines.get(4).and_then(|l| hex32(l));
    Credentials {
        ltsk,
        client_id,
        atv_id,
        atv_ltpk,
    }
}

fn hex_bytes(text: &str) -> Option<Vec<u8>> {
    if text.len() % 2 != 0 {
        return None;
    }
    (0..text.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&text[i..i + 2], 16).ok())
        .collect()
}

fn hex32(text: &str) -> Option<[u8; 32]> {
    hex_bytes(text)?.try_into().ok()
}

fn system_info() -> Value {
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
        .insert("name", "luftlift");
    map.to_value()
}

fn send(stream: &mut TcpStream, kind: u8, body: &Map) {
    let payload = rapport::encode(&body.clone().to_value());
    rapport::write_frame(stream, &rapport::Frame::new(kind, payload)).ok();
    stream.flush().ok();
}

fn read_pd(stream: &mut TcpStream) -> Option<Vec<u8>> {
    let response = rapport::read_frame(stream).ok()??;
    let Value::Dict(map) = rapport::decode_exact(&response.payload).ok()? else {
        return None;
    };
    let Value::Bytes(pd) = map.get("_pd")? else {
        return None;
    };
    Some(pd.clone())
}

/// ChaCha20-Poly1305 frame encryption with the Companion nonce layout:
/// 12-byte little-endian counter, AAD = the plaintext 4-byte frame header.
struct Cipher {
    out: ChaCha20Poly1305,
    input: ChaCha20Poly1305,
    out_counter: u64,
    in_counter: u64,
}

impl Cipher {
    fn new(out_key: [u8; 32], in_key: [u8; 32]) -> Self {
        Self {
            out: ChaCha20Poly1305::new(Key::from_slice(&out_key)),
            input: ChaCha20Poly1305::new(Key::from_slice(&in_key)),
            out_counter: 0,
            in_counter: 0,
        }
    }

    fn nonce(counter: u64) -> [u8; 12] {
        let mut nonce = [0u8; 12];
        nonce[..8].copy_from_slice(&counter.to_le_bytes());
        nonce
    }

    fn header(kind: u8, length: usize) -> [u8; 4] {
        let len = (length as u32).to_be_bytes();
        [kind, len[1], len[2], len[3]]
    }

    fn encrypt(&mut self, kind: u8, plain: &[u8]) -> rapport::Frame {
        let header = Self::header(kind, plain.len() + 16);
        let nonce = Self::nonce(self.out_counter);
        self.out_counter += 1;
        let sealed = self
            .out
            .encrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: plain,
                    aad: &header,
                },
            )
            .expect("encrypt");
        rapport::Frame::new(kind, sealed)
    }

    fn decrypt(&mut self, frame: &rapport::Frame) -> Option<Vec<u8>> {
        let header = Self::header(frame.kind, frame.payload.len());
        let nonce = Self::nonce(self.in_counter);
        self.in_counter += 1;
        self.input
            .decrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: &frame.payload,
                    aad: &header,
                },
            )
            .ok()
    }
}

fn send_encrypted(stream: &mut TcpStream, cipher: &mut Cipher, kind: u8, body: &Map) {
    let payload = rapport::encode(&body.clone().to_value());
    println!("-> type={kind} encrypted ({} bytes)", payload.len());
    let frame = cipher.encrypt(kind, &payload);
    rapport::write_frame(stream, &frame).ok();
    stream.flush().ok();
}

fn print_reply(stream: &mut TcpStream, cipher: &mut Cipher, label: &str) {
    match rapport::read_frame(stream) {
        Ok(Some(response)) => match cipher.decrypt(&response) {
            Some(plain) => println!(
                "<- {label} type={} body={:?}",
                response.kind,
                rapport::decode_exact(&plain)
                    .map(|v| format!("{v:?}"))
                    .unwrap_or_else(|e| format!("<decode: {e}>"))
            ),
            None => println!("<- {label}: decrypt failed (type={})", response.kind),
        },
        Ok(None) => println!("<- {label}: EOF (server closed)"),
        Err(e) => println!("<- {label}: read error {e}"),
    }
}
