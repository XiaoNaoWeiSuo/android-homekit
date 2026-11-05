//! Rapport (Companion Link) transport primitives: frames, OPACK, ChaCha20-Poly1305.
//!
//! Wire format (verified against a live `rapportd` on macOS 27 and against
//! pyatv's companion implementation):
//!
//! ```text
//! frame  = type:u8 | length:u24be | payload[length]
//! payload= OPACK value (a dict for every request/response we send)
//! ```
//!
//! Once a session is verified the payload is encrypted with
//! ChaCha20-Poly1305: the nonce is the 12-byte little-endian counter (one per
//! direction, starting at 0) and the associated data is the 4-byte frame
//! header, so the type/length cannot be tampered with.
//!
//! OPACK is Apple's compact binary serialization. Only the subset needed by
//! Companion Link is implemented (the full spec also has UUIDs, dates and
//! object references; dates are not used by the commands we send).

use std::collections::HashMap;
use std::io::{self, Read, Write};

/// Rapport frame types. Names follow pyatv so the wire captures read the same.
pub mod frame {
    pub const NO_OP: u8 = 1;
    pub const PS_START: u8 = 3;
    pub const PS_NEXT: u8 = 4;
    pub const PV_START: u8 = 5;
    pub const PV_NEXT: u8 = 6;
    pub const U_OPACK: u8 = 7;
    pub const E_OPACK: u8 = 8;
    pub const P_OPACK: u8 = 9;
    pub const SESSION_START_REQUEST: u8 = 16;
    pub const SESSION_START_RESPONSE: u8 = 17;
    pub const SESSION_DATA: u8 = 18;
}

/// A decoded frame.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub kind: u8,
    pub payload: Vec<u8>,
}

impl Frame {
    pub fn new(kind: u8, payload: Vec<u8>) -> Self {
        Self { kind, payload }
    }
}

/// Read one frame. Returns `Ok(None)` on a clean EOF before any header byte.
pub fn read_frame(reader: &mut impl Read) -> io::Result<Option<Frame>> {
    let mut header = [0u8; 4];
    match reader.read(&mut header[..1]) {
        Ok(0) => return Ok(None),
        Ok(_) => {}
        Err(e) if e.kind() == io::ErrorKind::Interrupted => return read_frame(reader),
        Err(e) => return Err(e),
    }
    reader.read_exact(&mut header[1..])?;
    let length = u32::from_be_bytes([0, header[1], header[2], header[3]]) as usize;
    if length > 16 * 1024 * 1024 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("frame too large: {length}"),
        ));
    }
    let mut payload = vec![0u8; length];
    reader.read_exact(&mut payload)?;
    Ok(Some(Frame {
        kind: header[0],
        payload,
    }))
}

/// Write one frame (plaintext).
pub fn write_frame(writer: &mut impl Write, frame: &Frame) -> io::Result<()> {
    let length = u32::try_from(frame.payload.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "payload too large"))?;
    let header = [
        frame.kind,
        (length >> 16) as u8,
        (length >> 8) as u8,
        length as u8,
    ];
    writer.write_all(&header)?;
    writer.write_all(&frame.payload)?;
    writer.flush()
}

/// An OPACK value.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(String),
    Bytes(Vec<u8>),
    List(Vec<Value>),
    Dict(Map),
}

/// Insertion-ordered map, which keeps encoding deterministic (tests and packet
/// captures compare byte-for-byte).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Map(pub Vec<(String, Value)>);

impl Map {
    pub fn new() -> Self {
        Self(Vec::new())
    }

    pub fn insert(&mut self, key: impl Into<String>, value: impl Into<Value>) -> &mut Self {
        let key = key.into();
        let value = value.into();
        if let Some(slot) = self.0.iter_mut().find(|(k, _)| k == &key) {
            slot.1 = value;
        } else {
            self.0.push((key, value));
        }
        self
    }

    pub fn get(&self, key: &str) -> Option<&Value> {
        self.0.iter().find(|(k, _)| k == key).map(|(_, v)| v)
    }

    pub fn get_str(&self, key: &str) -> Option<&str> {
        match self.get(key) {
            Some(Value::Str(s)) => Some(s),
            _ => None,
        }
    }

    pub fn get_int(&self, key: &str) -> Option<i64> {
        match self.get(key) {
            Some(Value::Int(n)) => Some(*n),
            _ => None,
        }
    }

    pub fn to_value(self) -> Value {
        Value::Dict(self)
    }
}

impl From<bool> for Value {
    fn from(v: bool) -> Self {
        Value::Bool(v)
    }
}
impl From<i64> for Value {
    fn from(v: i64) -> Self {
        Value::Int(v)
    }
}
impl From<u32> for Value {
    fn from(v: u32) -> Self {
        Value::Int(v as i64)
    }
}
impl From<i32> for Value {
    fn from(v: i32) -> Self {
        Value::Int(v as i64)
    }
}
impl From<f64> for Value {
    fn from(v: f64) -> Self {
        Value::Float(v)
    }
}
impl From<&str> for Value {
    fn from(v: &str) -> Self {
        Value::Str(v.to_string())
    }
}
impl From<String> for Value {
    fn from(v: String) -> Self {
        Value::Str(v)
    }
}
impl From<Vec<u8>> for Value {
    fn from(v: Vec<u8>) -> Self {
        Value::Bytes(v)
    }
}
impl From<Vec<Value>> for Value {
    fn from(v: Vec<Value>) -> Self {
        Value::List(v)
    }
}
impl From<Map> for Value {
    fn from(v: Map) -> Self {
        Value::Dict(v)
    }
}

/// HAP pair-setup SRP-6a (RFC 5054 group 3072, SHA-512).
///
/// The RustCrypto `srp` crate implements a different `M1` (`H(A|B|K)`), which
/// does not verify against HAP accessories. This module follows the HAP
/// construction used by pyatv's `srptools` configuration:
///
/// ```text
/// k  = H(N | PAD(g))
/// x  = H(s | H(I | ":" | P))
/// u  = H(PAD(A) | PAD(B))
/// S  = (B - k*g^x)^(a + u*x) mod N
/// K  = H(PAD(S))
/// M1 = H(H(N) XOR H(g) | H(I) | s | PAD(A) | PAD(B) | K)
/// M2 = H(PAD(A) | M1 | K)
/// ```
pub mod srp {
    use num_bigint::BigUint;
    use sha2::{Digest, Sha512};

    pub const N_HEX: &str = "ffffffffffffffffc90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b139b22514a08798e3404ddef9519b3cd3a431b302b0a6df25f14374fe1356d6d51c245e485b576625e7ec6f44c42e9a637ed6b0bff5cb6f406b7edee386bfb5a899fa5ae9f24117c4b1fe649286651ece45b3dc2007cb8a163bf0598da48361c55d39a69163fa8fd24cf5f83655d23dca3ad961c62f356208552bb9ed529077096966d670c354e4abc9804f1746c08ca18217c32905e462e36ce3be39e772c180e86039b2783a2ec07a28fb5c55df06f4c52c9de2bcbf6955817183995497cea956ae515d2261898fa051015728e5a8aaac42dad33170d04507a33a85521abdf1cba64ecfb850458dbef0a8aea71575d060c7db3970f85a6e1e4c7abf5ae8cdb0933d71e8c94e04a25619dcee3d2261ad2ee6bf12ffa06d98a0864d87602733ec86a64521f2b18177b200cbbe117577a615d6c770988c0bad946e208e24fa074e5ab3143db5bfce0fd108e4b82d120a93ad2caffffffffffffffff";

    fn modulus() -> BigUint {
        BigUint::parse_bytes(N_HEX.as_bytes(), 16).expect("valid prime")
    }

    fn generator() -> BigUint {
        BigUint::from(5u32)
    }

    /// Left-pad to the modulus width (384 bytes for the 3072-bit group).
    fn pad(value: &[u8]) -> Vec<u8> {
        let width = 384;
        if value.len() >= width {
            return value.to_vec();
        }
        let mut out = vec![0u8; width - value.len()];
        out.extend_from_slice(value);
        out
    }

    fn hash(parts: &[&[u8]]) -> [u8; 64] {
        let mut digest = Sha512::new();
        for part in parts {
            digest.update(part);
        }
        digest.finalize().into()
    }

    /// Client-side SRP state for one pairing attempt.
    pub struct SrpClient {
        a: BigUint,
        a_pub: BigUint,
        n: BigUint,
        g: BigUint,
    }

    /// Proofs and shared key produced after processing the server challenge.
    pub struct SrpProofs {
        pub a_pub: Vec<u8>,
        pub m1: Vec<u8>,
        pub m2: Vec<u8>,
        pub key: Vec<u8>,
    }

    impl SrpClient {
        /// Random `a` from `/dev/urandom` (fallback: time-derived).
        pub fn generate() -> Self {
            let mut bytes = [0u8; 32];
            match std::fs::File::open("/dev/urandom") {
                Ok(mut file) => {
                    use std::io::Read;
                    if file.read_exact(&mut bytes).is_err() {
                        bytes = fallback_bytes();
                    }
                }
                Err(_) => bytes = fallback_bytes(),
            }
            Self::from_secret(&bytes)
        }

        pub fn from_secret(secret: &[u8]) -> Self {
            let n = modulus();
            let g = generator();
            let a = BigUint::from_bytes_be(secret);
            let a_pub = g.modpow(&a, &n);
            Self { a, a_pub, n, g }
        }

        pub fn a_pub_bytes(&self) -> Vec<u8> {
            pad(&self.a_pub.to_bytes_be())
        }

        /// Process `PS_Next (seq 2)` from the server: derive `M1`, `M2`, `K`.
        pub fn process_challenge(
            &self,
            username: &[u8],
            password: &[u8],
            salt: &[u8],
            b_pub: &[u8],
        ) -> Option<SrpProofs> {
            let b = BigUint::from_bytes_be(b_pub);
            if &b % &self.n == BigUint::default() {
                return None;
            }
            let identity_hash = hash(&[username, b":", password]);
            let x = BigUint::from_bytes_be(&hash(&[salt, &identity_hash]));
            let k = BigUint::from_bytes_be(&hash(&[&self.n.to_bytes_be(), &pad(&[5u8])[..]]));
            let u = BigUint::from_bytes_be(&hash(&[&pad(&self.a_pub.to_bytes_be()), &pad(b_pub)]));

            // (B - k*g^x) mod N
            let base = (k * self.g.modpow(&x, &self.n)) % &self.n;
            let base = ((&self.n + &b) - base) % &self.n;
            let exponent = (&u * &x) + &self.a;
            let s = base.modpow(&exponent, &self.n);
            let s_bytes = pad(&s.to_bytes_be());
            let key = hash(&[&s_bytes]);

            let h_n = hash(&[&self.n.to_bytes_be()]);
            let h_g = hash(&[&[5u8][..]]);
            let xor: Vec<u8> = h_n.iter().zip(h_g.iter()).map(|(a, b)| a ^ b).collect();
            let m1 = hash(&[
                &xor,
                &identity_hash,
                salt,
                &pad(&self.a_pub.to_bytes_be()),
                &pad(b_pub),
                &key,
            ]);
            let m2 = hash(&[&pad(&self.a_pub.to_bytes_be()), &m1, &key]);
            Some(SrpProofs {
                a_pub: pad(&self.a_pub.to_bytes_be()),
                m1: m1.to_vec(),
                m2: m2.to_vec(),
                key: key.to_vec(),
            })
        }
    }

    fn fallback_bytes() -> [u8; 32] {
        let mut bytes = [0u8; 32];
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default();
        let mut state = (now.as_nanos() as u64) ^ ((std::process::id() as u64) << 32) | 1;
        for byte in bytes.iter_mut() {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            *byte = state as u8;
        }
        bytes
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        /// Simulate the server half with the same formulas and check that M1 and
        /// M2 match. This validates the arithmetic without a live accessory.
        #[test]
        fn srp_client_and_server_agree_on_proofs() {
            let client = SrpClient::from_secret(&[7u8; 32]);
            let salt = [9u8; 16];
            let password = b"1234";
            let username = b"Pair-Setup";

            // Server: v = g^x, B = k*v + g^b
            let n = modulus();
            let g = generator();
            let identity_hash = hash(&[username, b":", password]);
            let x = BigUint::from_bytes_be(&hash(&[&salt, &identity_hash]));
            let v = g.modpow(&x, &n);
            let k = BigUint::from_bytes_be(&hash(&[&n.to_bytes_be(), &pad(&[5u8])[..]]));
            let b = BigUint::from_bytes_be(&[3u8; 32]);
            let b_pub = ((k * &v) + g.modpow(&b, &n)) % &n;
            let b_bytes = pad(&b_pub.to_bytes_be());

            let proofs = client
                .process_challenge(username, password, &salt, &b_bytes)
                .expect("challenge");
            let a_bytes = proofs.a_pub.clone();

            // Server side: u, S, K, M1, M2
            let a = BigUint::from_bytes_be(&a_bytes);
            let u = BigUint::from_bytes_be(&hash(&[&a_bytes, &b_bytes]));
            let s = (a * v.modpow(&u, &n)).modpow(&b, &n);
            let s_bytes = pad(&s.to_bytes_be());
            let key = hash(&[&s_bytes]);
            let h_n = hash(&[&n.to_bytes_be()]);
            let h_g = hash(&[&[5u8][..]]);
            let xor: Vec<u8> = h_n.iter().zip(h_g.iter()).map(|(x, y)| x ^ y).collect();
            let m1 = hash(&[&xor, &identity_hash, &salt, &a_bytes, &b_bytes, &key]);
            let m2 = hash(&[&a_bytes, &m1, &key]);

            assert_eq!(proofs.m1, m1.to_vec(), "client M1 must match server M1");
            assert_eq!(proofs.m2, m2.to_vec(), "client M2 must match server M2");
            assert_eq!(proofs.key, key.to_vec(), "shared key must match");
        }

        #[test]
        fn rejects_zero_b_pub() {
            let client = SrpClient::from_secret(&[1u8; 32]);
            assert!(client
                .process_challenge(b"Pair-Setup", b"1234", &[0u8; 16], &[0u8; 384])
                .is_none());
        }
    }
}

/// HAP-style pair-verify, as used by Companion Link.
///
/// The server answers a `PV_Start` (frame 5) even for an unknown client: the
/// response (`PV_Next`, frame 6) carries its ephemeral X25519 public key plus
/// a ChaCha20-Poly1305 blob. The blob is encrypted with a key derived from the
/// X25519 shared secret via HKDF-SHA512, so a client that keeps its private
/// key can read the server's pairing identifier and signature. Completing the
/// exchange still requires a long-term Ed25519 key the server already trusts.
pub mod pair_verify {
    use chacha20poly1305::aead::{Aead, KeyInit};
    use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
    use hkdf::Hkdf;
    use sha2::Sha512;
    use x25519_dalek::{PublicKey, StaticSecret};

    pub const ENCRYPT_SALT: &[u8] = b"Pair-Verify-Encrypt-Salt";
    pub const ENCRYPT_INFO: &[u8] = b"Pair-Verify-Encrypt-Info";

    /// Load 32 random bytes as an X25519 secret. Falls back to a
    /// time-derived value only if `/dev/urandom` is unavailable (tests).
    pub fn generate_secret() -> StaticSecret {
        let mut bytes = [0u8; 32];
        match std::fs::File::open("/dev/urandom") {
            Ok(mut file) => {
                use std::io::Read;
                if file.read_exact(&mut bytes).is_err() {
                    bytes = fallback_bytes();
                }
            }
            Err(_) => bytes = fallback_bytes(),
        }
        StaticSecret::from(bytes)
    }

    fn fallback_bytes() -> [u8; 32] {
        let mut bytes = [0u8; 32];
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default();
        let seed = now.as_nanos() as u64 ^ (std::process::id() as u64) << 32;
        let mut state = seed | 1;
        for byte in bytes.iter_mut() {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            *byte = state as u8;
        }
        bytes
    }

    pub fn public_bytes(secret: &StaticSecret) -> [u8; 32] {
        PublicKey::from(secret).to_bytes()
    }

    pub fn shared_secret(secret: &StaticSecret, peer: [u8; 32]) -> [u8; 32] {
        secret.diffie_hellman(&PublicKey::from(peer)).to_bytes()
    }

    /// HKDF-SHA512 with explicit salt/info, used by both pair-verify and
    /// pair-setup (the label sets differ).
    pub fn hkdf_expand(salt: &[u8], info: &[u8], ikm: &[u8]) -> [u8; 32] {
        let hkdf = Hkdf::<Sha512>::new(Some(salt), ikm);
        let mut okm = [0u8; 32];
        hkdf.expand(info, &mut okm).expect("32 bytes is valid");
        okm
    }

    /// HKDF-SHA512 key used to protect the pair-verify payloads.
    pub fn encrypt_key(shared: &[u8; 32]) -> [u8; 32] {
        hkdf_expand(ENCRYPT_SALT, ENCRYPT_INFO, shared)
    }

    /// 12-byte nonce for a labelled message (`PV-Msg02`, `PS-Msg05`, ...).
    ///
    /// HAP's `_pad_nonce` **prepends** zeros: the label occupies the last 8
    /// bytes and the first 4 are zero. pyatv implements exactly this in
    /// `Chacha20Cipher._pad_nonce`, and getting the order wrong makes every
    /// pair-verify decryption fail authentication.
    pub fn nonce(label: &str) -> [u8; 12] {
        let mut nonce = [0u8; 12];
        let bytes = label.as_bytes();
        let len = bytes.len().min(8);
        nonce[4..4 + len].copy_from_slice(&bytes[..len]);
        nonce
    }

    pub fn decrypt(key: &[u8; 32], label: &str, data: &[u8]) -> Option<Vec<u8>> {
        let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
        cipher.decrypt(Nonce::from_slice(&nonce(label)), data).ok()
    }

    pub fn encrypt(key: &[u8; 32], label: &str, data: &[u8]) -> Option<Vec<u8>> {
        let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
        cipher.encrypt(Nonce::from_slice(&nonce(label)), data).ok()
    }
}

/// HAP-style TLV8, the container Apple uses inside the `_pd` field of
/// pair-setup / pair-verify frames.
///
/// Layout is `type:u8 | length:u8 | value[length]`; values longer than 255
/// bytes are split into consecutive chunks of the same type, and a decoder
/// must join those chunks back together. Only the types Companion Link uses
/// are named here; unknown types are preserved verbatim.
pub mod tlv8 {
    pub const METHOD: u8 = 0;
    pub const IDENTIFIER: u8 = 1;
    pub const SALT: u8 = 2;
    pub const PUBLIC_KEY: u8 = 3;
    pub const PROOF: u8 = 4;
    pub const ENCRYPTED_DATA: u8 = 5;
    pub const SEQ_NO: u8 = 6;
    pub const ERROR: u8 = 7;
    pub const SIGNATURE: u8 = 10;

    /// Encode `(type, value)` pairs, splitting long values into 255-byte
    /// chunks as the format requires.
    pub fn encode(pairs: &[(u8, &[u8])]) -> Vec<u8> {
        let mut out = Vec::new();
        for (ty, value) in pairs {
            if value.is_empty() {
                out.push(*ty);
                out.push(0);
                continue;
            }
            for chunk in value.chunks(255) {
                out.push(*ty);
                out.push(chunk.len() as u8);
                out.extend_from_slice(chunk);
            }
        }
        out
    }

    /// Decode into `(type, value)` pairs, joining consecutive chunks of the
    /// same type.
    pub fn decode(bytes: &[u8]) -> Vec<(u8, Vec<u8>)> {
        let mut out: Vec<(u8, Vec<u8>)> = Vec::new();
        let mut pos = 0;
        while pos + 2 <= bytes.len() {
            let ty = bytes[pos];
            let len = bytes[pos + 1] as usize;
            pos += 2;
            if pos + len > bytes.len() {
                break;
            }
            let value = &bytes[pos..pos + len];
            pos += len;
            match out.last_mut() {
                Some((last_ty, buf)) if *last_ty == ty => buf.extend_from_slice(value),
                _ => out.push((ty, value.to_vec())),
            }
        }
        out
    }

    /// First value of `ty`, if present.
    pub fn get(items: &[(u8, Vec<u8>)], ty: u8) -> Option<&[u8]> {
        items
            .iter()
            .find(|(item_ty, _)| *item_ty == ty)
            .map(|(_, value)| value.as_slice())
    }
}

/// Encode an OPACK value.
pub fn encode(value: &Value) -> Vec<u8> {
    let mut out = Vec::with_capacity(32);
    encode_into(value, &mut out);
    out
}

fn encode_into(value: &Value, out: &mut Vec<u8>) {
    match value {
        Value::Null => out.push(0x04),
        Value::Bool(true) => out.push(0x01),
        Value::Bool(false) => out.push(0x02),
        Value::Int(n) => {
            let n = *n;
            if (0..0x28).contains(&n) {
                out.push(n as u8 + 8);
            } else if (0..=0xff).contains(&n) {
                out.push(0x30);
                out.push(n as u8);
            } else if (0..=0xffff).contains(&n) {
                out.push(0x31);
                out.extend_from_slice(&(n as u16).to_le_bytes());
            } else if (0..=0xffff_ffff).contains(&n) {
                out.push(0x32);
                out.extend_from_slice(&(n as u32).to_le_bytes());
            } else {
                out.push(0x33);
                out.extend_from_slice(&n.to_le_bytes());
            }
        }
        Value::Float(f) => {
            out.push(0x36);
            out.extend_from_slice(&f.to_le_bytes());
        }
        Value::Str(s) => {
            let bytes = s.as_bytes();
            let len = bytes.len();
            if len <= 0x20 {
                out.push(0x40 + len as u8);
            } else if len <= 0xff {
                out.push(0x61);
                out.push(len as u8);
            } else if len <= 0xffff {
                out.push(0x62);
                out.extend_from_slice(&(len as u16).to_le_bytes());
            } else {
                out.push(0x63);
                out.extend_from_slice(&(len as u32).to_le_bytes()[..3]);
            }
            out.extend_from_slice(bytes);
        }
        Value::Bytes(bytes) => {
            let len = bytes.len();
            if len <= 0x20 {
                out.push(0x70 + len as u8);
            } else if len <= 0xff {
                out.push(0x91);
                out.push(len as u8);
            } else if len <= 0xffff {
                out.push(0x92);
                out.extend_from_slice(&(len as u16).to_le_bytes());
            } else {
                out.push(0x93);
                out.extend_from_slice(&(len as u32).to_le_bytes());
            }
            out.extend_from_slice(bytes);
        }
        Value::List(items) => {
            let count = items.len();
            out.push(0xd0 | count.min(0xf) as u8);
            for item in items {
                encode_into(item, out);
            }
            if count >= 0xf {
                out.push(0x03);
            }
        }
        Value::Dict(map) => {
            let count = map.0.len();
            out.push(0xe0 | count.min(0xf) as u8);
            for (key, value) in &map.0 {
                encode_into(&Value::Str(key.clone()), out);
                encode_into(value, out);
            }
            if count >= 0xf {
                out.push(0x03);
            }
        }
    }
}

/// Decode one OPACK value; returns the value and the unconsumed tail.
pub fn decode(bytes: &[u8]) -> io::Result<(Value, &[u8])> {
    decode_at(bytes, 0)
}

fn decode_at(bytes: &[u8], depth: usize) -> io::Result<(Value, &[u8])> {
    if depth > 64 {
        return Err(invalid("OPACK nesting too deep"));
    }
    let tag = *bytes.first().ok_or_else(|| invalid("truncated OPACK"))?;
    let rest = &bytes[1..];
    let (value, rest) = match tag {
        0x01 => (Value::Bool(true), rest),
        0x02 => (Value::Bool(false), rest),
        0x04 => (Value::Null, rest),
        0x08..=0x2f => (Value::Int((tag - 8) as i64), rest),
        0x30..=0x33 => {
            let width = 1usize << (tag & 0x0f);
            if rest.len() < width {
                return Err(invalid("truncated OPACK int"));
            }
            let mut raw = [0u8; 8];
            raw[..width].copy_from_slice(&rest[..width]);
            (Value::Int(i64::from_le_bytes(raw)), &rest[width..])
        }
        0x36 => {
            if rest.len() < 8 {
                return Err(invalid("truncated OPACK double"));
            }
            let mut raw = [0u8; 8];
            raw.copy_from_slice(&rest[..8]);
            (Value::Float(f64::from_le_bytes(raw)), &rest[8..])
        }
        0x40..=0x60 => {
            let len = (tag - 0x40) as usize;
            if rest.len() < len {
                return Err(invalid("truncated OPACK string"));
            }
            (
                Value::Str(String::from_utf8_lossy(&rest[..len]).into_owned()),
                &rest[len..],
            )
        }
        0x61..=0x64 => {
            let width = (tag & 0x0f) as usize;
            if rest.len() < width {
                return Err(invalid("truncated OPACK string length"));
            }
            let mut len_bytes = [0u8; 4];
            len_bytes[..width].copy_from_slice(&rest[..width]);
            let len = u32::from_le_bytes(len_bytes) as usize;
            let body = &rest[width..];
            if body.len() < len {
                return Err(invalid("truncated OPACK string body"));
            }
            (
                Value::Str(String::from_utf8_lossy(&body[..len]).into_owned()),
                &body[len..],
            )
        }
        0x70..=0x90 => {
            let len = (tag - 0x70) as usize;
            if rest.len() < len {
                return Err(invalid("truncated OPACK data"));
            }
            (Value::Bytes(rest[..len].to_vec()), &rest[len..])
        }
        0x91..=0x94 => {
            let width = 1usize << ((tag & 0x0f) - 1);
            if rest.len() < width {
                return Err(invalid("truncated OPACK data length"));
            }
            let mut len_bytes = [0u8; 8];
            len_bytes[..width].copy_from_slice(&rest[..width]);
            let len = u64::from_le_bytes(len_bytes) as usize;
            let body = &rest[width..];
            if body.len() < len {
                return Err(invalid("truncated OPACK data body"));
            }
            (Value::Bytes(body[..len].to_vec()), &body[len..])
        }
        0xd0..=0xdf => {
            let count = (tag & 0x0f) as usize;
            let mut items = Vec::new();
            let mut rest = rest;
            let mut index = 0;
            loop {
                if count == 0x0f {
                    if rest.first() == Some(&0x03) {
                        rest = &rest[1..];
                        break;
                    }
                } else if index >= count {
                    break;
                }
                let (item, tail) = decode_at(rest, depth + 1)?;
                items.push(item);
                rest = tail;
                index += 1;
            }
            (Value::List(items), rest)
        }
        0xe0..=0xef => {
            let count = (tag & 0x0f) as usize;
            let mut map = Map::new();
            let mut rest = rest;
            let mut index = 0;
            loop {
                if count == 0x0f {
                    if rest.first() == Some(&0x03) {
                        rest = &rest[1..];
                        break;
                    }
                } else if index >= count {
                    break;
                }
                let (key, tail) = decode_at(rest, depth + 1)?;
                let (value, tail) = decode_at(tail, depth + 1)?;
                let key = match key {
                    Value::Str(s) => s,
                    other => return Err(invalid(&format!("non-string OPACK key: {other:?}"))),
                };
                map.insert(key, value);
                rest = tail;
                index += 1;
            }
            (Value::Dict(map), rest)
        }
        other => return Err(invalid(&format!("unsupported OPACK tag 0x{other:02x}"))),
    };
    Ok((value, rest))
}

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message.to_string())
}

/// Convenience: decode a full payload, rejecting trailing bytes.
pub fn decode_exact(bytes: &[u8]) -> io::Result<Value> {
    let (value, rest) = decode(bytes)?;
    if !rest.is_empty() {
        return Err(invalid("trailing bytes after OPACK value"));
    }
    Ok(value)
}

/// A plain `HashMap<String, String>` view of a decoded dictionary, for callers
/// that only need string fields (e.g. TXT records).
pub fn string_map(value: &Value) -> HashMap<String, String> {
    let mut out = HashMap::new();
    if let Value::Dict(map) = value {
        for (key, value) in &map.0 {
            if let Value::Str(s) = value {
                out.insert(key.clone(), s.clone());
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn opack_roundtrips_scalars() {
        for value in [
            Value::Null,
            Value::Bool(true),
            Value::Bool(false),
            Value::Int(0),
            Value::Int(39),
            Value::Int(40),
            Value::Int(255),
            Value::Int(256),
            Value::Int(65536),
            Value::Int(4_294_967_296),
            Value::Float(1.5),
            Value::Str("hello".into()),
            Value::Str("x".repeat(300)),
            Value::Bytes(vec![1, 2, 3]),
            Value::Bytes(vec![7u8; 300]),
        ] {
            let encoded = encode(&value);
            let decoded = decode_exact(&encoded).expect("decode");
            assert_eq!(decoded, value, "roundtrip failed for {value:?}");
        }
    }

    #[test]
    fn opack_encodes_the_shortest_int_form() {
        assert_eq!(encode(&Value::Int(0)), vec![0x08]);
        assert_eq!(encode(&Value::Int(39)), vec![0x2f]);
        assert_eq!(encode(&Value::Int(40)), vec![0x30, 40]);
        assert_eq!(encode(&Value::Int(300)), vec![0x31, 0x2c, 0x01]);
    }

    #[test]
    fn opack_roundtrips_a_session_start() {
        let mut map = Map::new();
        map.insert("_srvT", "com.apple.sharingd.AirDrop");
        map.insert("_sid", 12345i64);
        let value = map.to_value();
        let decoded = decode_exact(&encode(&value)).expect("decode");
        assert_eq!(decoded, value);
    }

    #[test]
    fn opack_roundtrips_nested_structures() {
        let mut inner = Map::new();
        inner.insert("_c", 1i64);
        let value = Value::List(vec![
            Value::Str("a".into()),
            inner.to_value(),
            Value::Dict(Map::new()),
        ]);
        assert_eq!(decode_exact(&encode(&value)).expect("decode"), value);
    }

    #[test]
    fn pair_verify_derives_the_same_shared_secret_on_both_sides() {
        let a = pair_verify::generate_secret();
        let b = pair_verify::generate_secret();
        let shared_a = pair_verify::shared_secret(&a, pair_verify::public_bytes(&b));
        let shared_b = pair_verify::shared_secret(&b, pair_verify::public_bytes(&a));
        assert_eq!(shared_a, shared_b);
        assert_eq!(pair_verify::encrypt_key(&shared_a), pair_verify::encrypt_key(&shared_b));
    }

    #[test]
    fn pair_verify_roundtrips_a_payload() {
        let secret = pair_verify::generate_secret();
        let shared = pair_verify::shared_secret(&secret, pair_verify::public_bytes(&secret));
        let key = pair_verify::encrypt_key(&shared);
        let plaintext = b"hello pair verify";
        let sealed = pair_verify::encrypt(&key, "PV-Msg02", plaintext).expect("seal");
        assert_ne!(sealed, plaintext);
        assert_eq!(
            pair_verify::decrypt(&key, "PV-Msg02", &sealed).expect("open"),
            plaintext
        );
        // A different label is a different nonce and must not open.
        assert!(pair_verify::decrypt(&key, "PV-Msg03", &sealed).is_none());
    }

    #[test]
    fn pair_verify_nonce_is_ascii_padded_with_zeros() {
        // HAP prepends the four zero bytes; the ASCII label sits at the end.
        let nonce = pair_verify::nonce("PV-Msg02");
        assert_eq!(&nonce[..4], &[0, 0, 0, 0]);
        assert_eq!(&nonce[4..12], b"PV-Msg02");
    }

    #[test]
    fn tlv8_roundtrips_and_chunks_long_values() {
        let long = vec![0xABu8; 600];
        let encoded = tlv8::encode(&[(tlv8::SEQ_NO, &[1]), (tlv8::PUBLIC_KEY, &long)]);
        let decoded = tlv8::decode(&encoded);
        assert_eq!(decoded.len(), 2);
        assert_eq!(tlv8::get(&decoded, tlv8::SEQ_NO), Some(&[1u8][..]));
        assert_eq!(tlv8::get(&decoded, tlv8::PUBLIC_KEY), Some(long.as_slice()));
    }

    #[test]
    fn tlv8_decodes_the_pair_verify_start() {
        let mut key = [0u8; 32];
        for (i, b) in key.iter_mut().enumerate() {
            *b = i as u8;
        }
        let encoded = tlv8::encode(&[(tlv8::SEQ_NO, &[1]), (tlv8::PUBLIC_KEY, &key)]);
        let decoded = tlv8::decode(&encoded);
        assert_eq!(tlv8::get(&decoded, tlv8::SEQ_NO), Some(&[1u8][..]));
        assert_eq!(tlv8::get(&decoded, tlv8::PUBLIC_KEY), Some(&key[..]));
    }

    #[test]
    fn frame_roundtrips_and_reports_clean_eof() {
        let frame = Frame::new(frame::NO_OP, Vec::new());
        let mut out = Vec::new();
        write_frame(&mut out, &frame).expect("write");
        let mut cursor = std::io::Cursor::new(out.clone());
        assert_eq!(read_frame(&mut cursor).expect("read"), Some(frame));

        let mut empty = std::io::Cursor::new(Vec::new());
        assert_eq!(read_frame(&mut empty).expect("eof"), None);
    }

    #[test]
    fn frame_header_is_big_endian_length() {
        let mut out = Vec::new();
        write_frame(&mut out, &Frame::new(frame::E_OPACK, vec![0xaa; 258])).expect("write");
        assert_eq!(&out[..4], &[frame::E_OPACK, 0x00, 0x01, 0x02]);
    }

    #[test]
    fn rejects_truncated_payloads() {
        // 0x40 is a valid *empty* string; use 0x41 (one byte promised, none
        // present) for the truncation case.
        assert_eq!(
            decode_exact(&[0x40]).expect("empty string"),
            Value::Str(String::new())
        );
        assert!(decode_exact(&[0x41]).is_err());
        assert!(decode_exact(&[0xe1, 0x40]).is_err()); // dict missing value
        assert!(decode_exact(&[0x92, 0x10, 0x00]).is_err()); // data len 16, no body
    }
}
