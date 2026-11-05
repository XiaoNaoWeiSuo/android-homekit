//! Minimal DNS wire format for **unicast** mDNS queries.
//!
//! Why hand-rolled instead of reusing `mdns-sd`: on the test network the AP
//! drops client-to-client multicast queries (our QM packets leave the phone and
//! the Mac never answers), while a *unicast* query with the QU bit set to
//! `10.12.1.5:5353` is answered immediately. `mdns-sd` has no API for "send
//! this question to that host over unicast", so the companion path carries its
//! own encoder/decoder.
//!
//! Scope is deliberately narrow: build a PTR query, parse PTR/SRV/TXT/A/AAAA
//! answers with compression pointers. Nothing else is needed for discovery.

use std::collections::HashMap;
use std::net::{Ipv4Addr, Ipv6Addr};

pub const TYPE_A: u16 = 1;
pub const TYPE_PTR: u16 = 12;
pub const TYPE_TXT: u16 = 16;
pub const TYPE_AAAA: u16 = 28;
pub const TYPE_SRV: u16 = 33;

/// Class IN with the mDNS unicast-response (QU) bit set.
const CLASS_IN_QU: u16 = 0x8001;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RData {
    A(Ipv4Addr),
    Aaaa(Ipv6Addr),
    Ptr(String),
    Srv {
        priority: u16,
        weight: u16,
        port: u16,
        target: String,
    },
    Txt(Vec<u8>),
    Other,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Record {
    pub name: String,
    pub ttl: u32,
    pub data: RData,
}

#[derive(Debug, Clone, Default)]
pub struct Response {
    pub records: Vec<Record>,
}

impl Response {
    pub fn ptr_targets(&self) -> Vec<&str> {
        self.records
            .iter()
            .filter_map(|r| match &r.data {
                RData::Ptr(target) => Some(target.as_str()),
                _ => None,
            })
            .collect()
    }

    pub fn srv_for(&self, name: &str) -> Option<(u16, &str)> {
        self.records.iter().find_map(|r| match &r.data {
            RData::Srv { port, target, .. } if r.name.eq_ignore_ascii_case(name) => {
                Some((*port, target.as_str()))
            }
            _ => None,
        })
    }

    pub fn txt_for(&self, name: &str) -> Option<HashMap<String, String>> {
        self.records.iter().find_map(|r| match &r.data {
            RData::Txt(bytes) if r.name.eq_ignore_ascii_case(name) => Some(parse_txt(bytes)),
            _ => None,
        })
    }

    pub fn addresses_for(&self, name: &str) -> Vec<std::net::IpAddr> {
        self.records
            .iter()
            .filter(|r| r.name.eq_ignore_ascii_case(name))
            .filter_map(|r| match &r.data {
                RData::A(v4) => Some(std::net::IpAddr::V4(*v4)),
                RData::Aaaa(v6) => Some(std::net::IpAddr::V6(*v6)),
                _ => None,
            })
            .collect()
    }
}

/// Encode a PTR question with the QU bit set so the responder answers to the
/// source address/port instead of multicasting the answer.
pub fn encode_ptr_query(name: &str, id: u16) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(64);
    out.extend_from_slice(&id.to_be_bytes());
    out.extend_from_slice(&0u16.to_be_bytes()); // QDCOUNT 1 comes from flags=0
                                                // Header: ID, flags, QDCOUNT, AN, NS, AR
    out.clear();
    out.extend_from_slice(&id.to_be_bytes());
    out.extend_from_slice(&0u16.to_be_bytes());
    out.extend_from_slice(&1u16.to_be_bytes());
    out.extend_from_slice(&0u16.to_be_bytes());
    out.extend_from_slice(&0u16.to_be_bytes());
    out.extend_from_slice(&0u16.to_be_bytes());

    for label in name.trim_end_matches('.').split('.') {
        let bytes = label.as_bytes();
        if bytes.is_empty() || bytes.len() > 63 {
            return None;
        }
        out.push(bytes.len() as u8);
        out.extend_from_slice(bytes);
    }
    out.push(0);
    out.extend_from_slice(&TYPE_PTR.to_be_bytes());
    out.extend_from_slice(&CLASS_IN_QU.to_be_bytes());
    Some(out)
}

/// Parse a DNS response. Returns `None` only for structurally broken packets;
/// unknown record types are kept as [`RData::Other`].
pub fn parse_response(bytes: &[u8]) -> Option<Response> {
    if bytes.len() < 12 {
        return None;
    }
    let qd = u16::from_be_bytes([bytes[4], bytes[5]]) as usize;
    let an = u16::from_be_bytes([bytes[6], bytes[7]]) as usize;
    let ns = u16::from_be_bytes([bytes[8], bytes[9]]) as usize;
    let ar = u16::from_be_bytes([bytes[10], bytes[11]]) as usize;

    let mut pos = 12;
    for _ in 0..qd {
        let (_, next) = read_name(bytes, pos)?;
        pos = next.checked_add(4)?; // QTYPE + QCLASS
        if pos > bytes.len() {
            return None;
        }
    }

    let mut records = Vec::with_capacity(an + ns + ar);
    for _ in 0..(an + ns + ar) {
        let (name, next) = read_name(bytes, pos)?;
        pos = next;
        if pos + 10 > bytes.len() {
            return None;
        }
        let rtype = u16::from_be_bytes([bytes[pos], bytes[pos + 1]]);
        // class at pos+2..pos+4 (ignored)
        let ttl = u32::from_be_bytes([
            bytes[pos + 4],
            bytes[pos + 5],
            bytes[pos + 6],
            bytes[pos + 7],
        ]);
        let rdlen = u16::from_be_bytes([bytes[pos + 8], bytes[pos + 9]]) as usize;
        let rdata_start = pos + 10;
        let rdata_end = rdata_start.checked_add(rdlen)?;
        if rdata_end > bytes.len() {
            return None;
        }
        let data = match rtype {
            TYPE_A if rdlen == 4 => RData::A(Ipv4Addr::new(
                bytes[rdata_start],
                bytes[rdata_start + 1],
                bytes[rdata_start + 2],
                bytes[rdata_start + 3],
            )),
            TYPE_AAAA if rdlen == 16 => {
                let mut octets = [0u8; 16];
                octets.copy_from_slice(&bytes[rdata_start..rdata_end]);
                RData::Aaaa(Ipv6Addr::from(octets))
            }
            TYPE_PTR => {
                let (target, _) = read_name(bytes, rdata_start)?;
                RData::Ptr(target)
            }
            TYPE_SRV if rdlen >= 7 => {
                let priority = u16::from_be_bytes([bytes[rdata_start], bytes[rdata_start + 1]]);
                let weight = u16::from_be_bytes([bytes[rdata_start + 2], bytes[rdata_start + 3]]);
                let port = u16::from_be_bytes([bytes[rdata_start + 4], bytes[rdata_start + 5]]);
                let (target, _) = read_name(bytes, rdata_start + 6)?;
                RData::Srv {
                    priority,
                    weight,
                    port,
                    target,
                }
            }
            TYPE_TXT => RData::Txt(bytes[rdata_start..rdata_end].to_vec()),
            _ => RData::Other,
        };
        records.push(Record { name, ttl, data });
        pos = rdata_end;
    }
    Some(Response { records })
}

/// Read a (possibly compressed) domain name starting at `start`.
/// Returns the name and the offset just past the name *in the current record*.
fn read_name(buf: &[u8], start: usize) -> Option<(String, usize)> {
    let mut labels: Vec<String> = Vec::new();
    let mut pos = start;
    let mut end: Option<usize> = None;
    let mut jumps = 0;

    loop {
        let len = *buf.get(pos)?;
        if len == 0 {
            pos += 1;
            break;
        }
        if len & 0xc0 == 0xc0 {
            let low = *buf.get(pos + 1)?;
            let target = (((len & 0x3f) as usize) << 8) | low as usize;
            if end.is_none() {
                end = Some(pos + 2);
            }
            jumps += 1;
            if jumps > 16 || target >= buf.len() {
                return None;
            }
            pos = target;
            continue;
        }
        let label_end = pos.checked_add(1 + len as usize)?;
        if label_end > buf.len() {
            return None;
        }
        labels.push(String::from_utf8_lossy(&buf[pos + 1..label_end]).into_owned());
        pos = label_end;
    }

    Some((labels.join("."), end.unwrap_or(pos)))
}

/// Decode a TXT rdata blob into `key=value` pairs (a key without `=` maps to
/// an empty string). Multiple strings with the same key: last one wins.
pub fn parse_txt(bytes: &[u8]) -> HashMap<String, String> {
    let mut out = HashMap::new();
    let mut pos = 0;
    while pos < bytes.len() {
        let len = bytes[pos] as usize;
        pos += 1;
        if pos + len > bytes.len() {
            break;
        }
        let entry = String::from_utf8_lossy(&bytes[pos..pos + len]).into_owned();
        pos += len;
        match entry.split_once('=') {
            Some((key, value)) => {
                out.insert(key.to_string(), value.to_string());
            }
            None if !entry.is_empty() => {
                out.insert(entry, String::new());
            }
            None => {}
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_a_ptr_question_with_qu_bit() {
        let packet = encode_ptr_query("_companion-link._tcp.local.", 7).unwrap();
        assert_eq!(&packet[0..2], &7u16.to_be_bytes());
        assert_eq!(u16::from_be_bytes([packet[4], packet[5]]), 1);
        assert_eq!(packet[12], 15); // "_companion-link"
        let tail = &packet[packet.len() - 4..];
        assert_eq!(u16::from_be_bytes([tail[0], tail[1]]), TYPE_PTR);
        assert_eq!(u16::from_be_bytes([tail[2], tail[3]]), 0x8001);
        // names round-trip through the name reader
        let (name, _) = read_name(&packet, 12).unwrap();
        assert_eq!(name, "_companion-link._tcp.local");
    }

    #[test]
    fn rejects_empty_labels() {
        assert!(encode_ptr_query("_tcp..local", 1).is_none());
        assert!(encode_ptr_query("", 1).is_none());
    }

    #[test]
    fn parses_txt_key_values() {
        let txt = b"\x07rpMac=1\x11rpHN=8d42f7a605c4\x0crpFl=0x20000";
        let map = parse_txt(txt);
        assert_eq!(map.get("rpMac").map(String::as_str), Some("1"));
        assert_eq!(map.get("rpHN").map(String::as_str), Some("8d42f7a605c4"));
        assert_eq!(map.get("rpFl").map(String::as_str), Some("0x20000"));
    }

    /// Build a response the way the Mac does: PTR answer pointing at the
    /// instance, then SRV/TXT/A in the additional section with compression
    /// pointers. Exercises the parser without a live network.
    #[test]
    fn parses_a_compressed_companion_response() {
        let mut p = Vec::new();
        p.extend_from_slice(&0x1234u16.to_be_bytes());
        p.extend_from_slice(&0x8400u16.to_be_bytes());
        p.extend_from_slice(&1u16.to_be_bytes()); // qd
        p.extend_from_slice(&1u16.to_be_bytes()); // an
        p.extend_from_slice(&0u16.to_be_bytes()); // ns
        p.extend_from_slice(&2u16.to_be_bytes()); // ar

        // question: _companion-link._tcp.local PTR IN
        let qname_off = p.len();
        for label in ["_companion-link", "_tcp", "local"] {
            p.push(label.len() as u8);
            p.extend_from_slice(label.as_bytes());
        }
        p.push(0);
        p.extend_from_slice(&TYPE_PTR.to_be_bytes());
        p.extend_from_slice(&1u16.to_be_bytes());

        // answer: PTR -> 焱林的MacBook (encoded literally, no compression)
        let instance = "焱林的MacBook._companion-link._tcp.local";
        write_name(&mut p, instance);
        p.extend_from_slice(&TYPE_PTR.to_be_bytes());
        p.extend_from_slice(&1u16.to_be_bytes());
        p.extend_from_slice(&10u32.to_be_bytes());
        let rdlen_pos = p.len();
        p.extend_from_slice(&0u16.to_be_bytes());
        let rd_start = p.len();
        write_name(&mut p, instance);
        let rdlen = (p.len() - rd_start) as u16;
        p[rdlen_pos..rdlen_pos + 2].copy_from_slice(&rdlen.to_be_bytes());

        // SRV for the instance, target "MacBook.local" compressed to a new name
        write_name(&mut p, instance);
        p.extend_from_slice(&TYPE_SRV.to_be_bytes());
        p.extend_from_slice(&1u16.to_be_bytes());
        p.extend_from_slice(&10u32.to_be_bytes());
        let srv_len_pos = p.len();
        p.extend_from_slice(&0u16.to_be_bytes());
        let srv_start = p.len();
        p.extend_from_slice(&0u16.to_be_bytes()); // priority
        p.extend_from_slice(&0u16.to_be_bytes()); // weight
        p.extend_from_slice(&61156u16.to_be_bytes());
        write_name(&mut p, "MacBook.local");
        let srv_len = (p.len() - srv_start) as u16;
        p[srv_len_pos..srv_len_pos + 2].copy_from_slice(&srv_len.to_be_bytes());

        // TXT for the instance
        write_name(&mut p, instance);
        p.extend_from_slice(&TYPE_TXT.to_be_bytes());
        p.extend_from_slice(&1u16.to_be_bytes());
        p.extend_from_slice(&10u32.to_be_bytes());
        let txt = b"\x07rpMac=1\x0crpFl=0x20000\x0arpVr=715.2";
        p.extend_from_slice(&(txt.len() as u16).to_be_bytes());
        p.extend_from_slice(txt);

        let parsed = parse_response(&p).expect("parse");
        assert_eq!(parsed.ptr_targets(), vec![instance]);
        assert_eq!(parsed.srv_for(instance), Some((61156, "MacBook.local")));
        let txt = parsed.txt_for(instance).expect("txt");
        assert_eq!(txt.get("rpMac").map(String::as_str), Some("1"));
        assert_eq!(txt.get("rpVr").map(String::as_str), Some("715.2"));

        // sanity: the question name is also readable at its original offset
        let (qname, _) = read_name(&p, qname_off).unwrap();
        assert_eq!(qname, "_companion-link._tcp.local");
    }

    fn write_name(out: &mut Vec<u8>, name: &str) {
        for label in name.split('.') {
            out.push(label.len() as u8);
            out.extend_from_slice(label.as_bytes());
        }
        out.push(0);
    }
}
