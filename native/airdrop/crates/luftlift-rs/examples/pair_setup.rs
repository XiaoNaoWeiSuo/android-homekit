//! HAP pair-setup against Companion Link (rapportd).
//!
//! Flow (all frames are OPACK dicts carrying a TLV8 `_pd`):
//!
//! ```text
//! PS_Start (frame 3)  { _pd: TLV{ Method=0, SeqNo=1 }, _pwTy: 1 }
//!   -> server salt (16 B) + SRP public key B (384 B)
//! PS_Next  (frame 4)  { _pd: TLV{ SeqNo=3, PublicKey=A, Proof=M1 } }
//!   -> server proof M2 (+ encrypted data)
//! PS_Next  (frame 4)  { _pd: TLV{ SeqNo=5, EncryptedData=PS-Msg05 } }
//!   -> TLV{ SeqNo=6, EncryptedData=PS-Msg06 }
//! ```
//!
//! Credentials written to `/data/local/tmp/companion-credentials.txt`:
//! `client_ltpk`, `client_ltsk`, `atv_id`, `client_id` (all hex, one per line).
//! They are what `pair-verify` needs for a real session.
//!
//! Usage: `pair_setup <ip> <port> <pin> [interface]`
//! The PIN is the code the Mac shows when pair-setup starts.

use ed25519_dalek::{Signer, SigningKey};
use luftlift_rs::netutil;
use luftlift_rs::rapport::{self, frame, pair_verify, srp, tlv8, Map, Value};
use std::io::Write;
use std::net::{IpAddr, TcpStream};
use std::time::Duration;

const CREDENTIALS_ANDROID: &str = "/data/local/tmp/companion-credentials.txt";
const CREDENTIALS_HOST: &str = "/tmp/companion-credentials.txt";

/// Where to save the pairing result. On the Mac the tool runs from a terminal,
/// so /tmp exists; on Android it runs under the app and uses the app tmp dir.
fn credentials_path() -> &'static str {
    if cfg!(target_os = "android") {
        CREDENTIALS_ANDROID
    } else {
        CREDENTIALS_HOST
    }
}

/// Send PS_Start, retrying through HAP backoff.
///
/// macOS throttles pair-setup after repeated attempts (`PairSetup server
/// throttling for N second(s)` in its log) and replies with TLV ERROR 3. A
/// 90 s pause clears the short backoffs; the connection is recreated for each
/// attempt because the server closes it on refusal.
fn start_pairing(addr: &std::net::SocketAddr) -> (TcpStream, Vec<u8>, Vec<u8>) {
    for attempt in 1..=6 {
        println!("connecting to {addr} (attempt {attempt})");
        let mut stream = match TcpStream::connect_timeout(addr, Duration::from_secs(5)) {
            Ok(stream) => stream,
            Err(e) => {
                eprintln!("connect failed: {e}");
                std::thread::sleep(Duration::from_secs(5));
                continue;
            }
        };
        stream.set_read_timeout(Some(Duration::from_secs(8))).ok();
        let _ = rapport::read_frame(&mut stream); // NoOp preamble

        let mut body = Map::new();
        body.insert(
            "_pd",
            tlv8::encode(&[(tlv8::METHOD, &[0u8]), (tlv8::SEQ_NO, &[1u8])]),
        )
        .insert("_pwTy", 1i64);
        send(&mut stream, frame::PS_START, &body);

        let Some(reply) = read_pd(&mut stream) else {
            eprintln!("no PS_Start reply");
            continue;
        };
        let tlv = tlv8::decode(&reply);
        if let (Some(salt), Some(b_pub)) =
            (tlv8::get(&tlv, tlv8::SALT), tlv8::get(&tlv, tlv8::PUBLIC_KEY))
        {
            return (stream, salt.to_vec(), b_pub.to_vec());
        }
        let types: Vec<u8> = tlv.iter().map(|(t, _)| *t).collect();
        let error = tlv8::get(&tlv, tlv8::ERROR).and_then(|b| b.first().copied());
        if error == Some(3) {
            // Retrying every 90 s extends the backoff. Read the remaining time
            // from rapportd's log and wait exactly that long instead.
            let wait = backoff_seconds().map(|s| s + 15).unwrap_or(120);
            eprintln!("Mac is throttling pairing for ~{wait}s; waiting before retry");
            std::thread::sleep(Duration::from_secs(wait));
            continue;
        }
        eprintln!("PS_Start refused: tlv={types:?} error={error:?}");
        std::process::exit(2);
    }
    eprintln!("pairing is still throttled after retries; wait a few minutes");
    std::process::exit(2);
}

/// Remaining HAP backoff (seconds) as stated in rapportd's log, if present.
fn backoff_seconds() -> Option<u64> {
    let output = std::process::Command::new("log")
        .args([
            "show",
            "--last",
            "30s",
            "--predicate",
            "process == \"rapportd\"",
        ])
        .output()
        .ok()?;
    let text = String::from_utf8_lossy(&output.stdout);
    text.lines().rev().find_map(|line| {
        let idx = line.find("throttling for ")?;
        let rest = &line[idx + "throttling for ".len()..];
        rest.chars()
            .take_while(|c| c.is_ascii_digit())
            .collect::<String>()
            .parse()
            .ok()
    })
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let host: IpAddr = args
        .first()
        .expect("usage: pair_setup <ip> <port> <pin> [interface]")
        .parse()
        .expect("ip");
    let port: u16 = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(61156);
    // The Mac shows the 4-digit code only AFTER PS_Start, so the PIN can also
    // be supplied through a file while the connection stays open.
    let pin_arg = args.get(2).cloned();
    let scope = args.get(3).and_then(|s| netutil::if_index_for(s));
    let addr = netutil::connect_addr_with_scope(host, port, scope);

    let secret = srp::SrpClient::generate();
    let (mut stream, salt, b_pub) = start_pairing(&addr);
    println!("PS_Start ok: salt={} B={}", salt.len(), b_pub.len());

    let pin = match pin_arg {
        Some(pin) => pin,
        None => {
            use std::io::IsTerminal;
            if std::io::stdin().is_terminal() {
                prompt_for_pin()
            } else {
                wait_for_pin()
            }
        }
    };
    println!("using PIN of {} digits", pin.len());

    // ---- PS_Next seq 3: A + M1 -----------------------------------------
    let proofs = secret
        .process_challenge(b"Pair-Setup", pin.as_bytes(), &salt, &b_pub)
        .expect("SRP challenge");
    let mut body = Map::new();
    body.insert(
        "_pd",
        tlv8::encode(&[
            (tlv8::SEQ_NO, &[3u8]),
            (tlv8::PUBLIC_KEY, &proofs.a_pub),
            (tlv8::PROOF, &proofs.m1),
        ]),
    )
    .insert("_pwTy", 1i64);
    send(&mut stream, frame::PS_NEXT, &body);
    let reply = read_pd(&mut stream).expect("seq 4 reply");
    let tlv = tlv8::decode(&reply);
    let server_proof = tlv8::get(&tlv, tlv8::PROOF).expect("server proof").to_vec();
    if server_proof != proofs.m2 {
        eprintln!("server proof mismatch (wrong PIN?)");
        std::process::exit(2);
    }
    println!("server proof verified (PIN correct)");

    // ---- PS_Next seq 5: our identity, encrypted -------------------------
    let ios_x = pair_verify::hkdf_expand(
        b"Pair-Setup-Controller-Sign-Salt",
        b"Pair-Setup-Controller-Sign-Info",
        &proofs.key,
    );
    let session_key = pair_verify::hkdf_expand(
        b"Pair-Setup-Encrypt-Salt",
        b"Pair-Setup-Encrypt-Info",
        &proofs.key,
    );

    let mut seed = [0u8; 32];
    {
        use std::io::Read;
        std::fs::File::open("/dev/urandom")
            .and_then(|mut f| f.read_exact(&mut seed))
            .expect("urandom");
    }
    let signing = SigningKey::from_bytes(&seed);
    let public = signing.verifying_key().to_bytes();
    let client_id = format!(
        "{:08X}-{:04X}-{:04X}-{:04X}-{:012X}",
        u32::from_le_bytes(seed[0..4].try_into().unwrap()),
        u16::from_le_bytes(seed[4..6].try_into().unwrap()),
        u16::from_le_bytes(seed[6..8].try_into().unwrap()),
        u16::from_le_bytes(seed[8..10].try_into().unwrap()),
        u64::from_le_bytes(seed[10..18].try_into().unwrap()) & 0xffff_ffff_ffff
    );

    let mut device_info = Vec::new();
    device_info.extend_from_slice(&ios_x);
    device_info.extend_from_slice(client_id.as_bytes());
    device_info.extend_from_slice(&public);
    let signature = signing.sign(&device_info);

    let tlv = tlv8::encode(&[
        (tlv8::IDENTIFIER, client_id.as_bytes()),
        (tlv8::PUBLIC_KEY, &public),
        (tlv8::SIGNATURE, &signature.to_bytes()),
    ]);
    let sealed = pair_verify::encrypt(&session_key, "PS-Msg05", &tlv).expect("seal");
    let mut body = Map::new();
    body.insert(
        "_pd",
        tlv8::encode(&[(tlv8::SEQ_NO, &[5u8]), (tlv8::ENCRYPTED_DATA, &sealed)]),
    );
    send(&mut stream, frame::PS_NEXT, &body);

    let reply = read_pd(&mut stream).expect("seq 6 reply");
    let tlv = tlv8::decode(&reply);
    let Some(encrypted) = tlv8::get(&tlv, tlv8::ENCRYPTED_DATA) else {
        eprintln!("seq 6 has no EncryptedData");
        std::process::exit(3);
    };
    let Some(plain) = pair_verify::decrypt(&session_key, "PS-Msg06", encrypted) else {
        eprintln!("PS-Msg06 decrypt failed");
        std::process::exit(3);
    };
    let inner = tlv8::decode(&plain);
    let atv_id = tlv8::get(&inner, tlv8::IDENTIFIER).expect("atv id");
    let atv_ltpk = tlv8::get(&inner, tlv8::PUBLIC_KEY).expect("atv ltpk");

    let hex = |bytes: &[u8]| bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
    let contents = format!(
        "{}\n{}\n{}\n{}\n",
        hex(&public),
        hex(&seed),
        hex(atv_id),
        client_id
    );
    let path = credentials_path();
    std::fs::write(path, contents).expect("write credentials");
    println!(
        "PAIRED: atv_id={} atv_ltpk_len={} credentials={path}",
        hex(atv_id),
        atv_ltpk.len()
    );
}

/// Prompt on stdin. Used when the tool runs in a Terminal on the Mac itself:
/// the code appears right after PS_Start, so typing it here beats the pairing
/// timeout without a chat round-trip.
fn prompt_for_pin() -> String {
    eprintln!("Enter the 4-digit code shown on this Mac: ");
    let mut line = String::new();
    std::io::stdin()
        .read_line(&mut line)
        .expect("stdin");
    line.trim().to_string()
}

/// Wait for the operator to write the code shown on the Mac. The pairing
/// session lives on this connection, so polling a file is the simplest way to
/// supply the PIN without dropping it.
fn wait_for_pin() -> String {
    // macOS host runs use /tmp, Android runs use the app's tmp dir.
    const PATHS: [&str; 2] = ["/tmp/pin.txt", "/data/local/tmp/pin.txt"];
    println!("waiting for PIN in {PATHS:?} (the Mac shows a 4-digit code)");
    for _ in 0..240 {
        for path in PATHS {
            if let Ok(text) = std::fs::read_to_string(path) {
                let pin = text.trim().to_string();
                if !pin.is_empty() {
                    let _ = std::fs::remove_file(path);
                    return pin;
                }
            }
        }
        std::thread::sleep(Duration::from_secs(1));
    }
    eprintln!("timed out waiting for {PATHS:?}");
    std::process::exit(4);
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
