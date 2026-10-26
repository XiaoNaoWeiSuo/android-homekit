//! Admission of locally generated mDNS responses into the replay cache.
//! Packet delivery is unchanged; this only decides what may be replayed.
#[derive(Debug, PartialEq, Eq)]
pub enum Decision {
    Keep,
    Replace { max_age_us: u64 },
    Clear,
}

fn u16_at(bytes: &[u8], offset: usize) -> Option<u16> {
    Some(u16::from_be_bytes(
        bytes.get(offset..offset + 2)?.try_into().ok()?,
    ))
}

fn name_end(bytes: &[u8], mut offset: usize) -> Option<usize> {
    let start = offset;
    loop {
        let n = *bytes.get(offset)?;
        offset += 1;
        if offset - start > 255 {
            return None;
        }
        match n {
            0 => return Some(offset),
            1..=63 => {
                offset = offset.checked_add(usize::from(n))?;
                bytes.get(..offset)?;
            }
            0xc0..=0xff => {
                let target = (usize::from(n & 0x3f) << 8) | usize::from(*bytes.get(offset)?);
                // Compression references prior names in the message, not headers.
                if target < 12 || target >= offset - 1 {
                    return None;
                }
                return Some(offset + 1);
            }
            _ => return None,
        }
    }
}

fn dns_decision(dns: &[u8]) -> Option<Decision> {
    let flags = u16_at(dns, 2)?;
    let questions = u16_at(dns, 4)?;
    let records =
        u32::from(u16_at(dns, 6)?) + u32::from(u16_at(dns, 8)?) + u32::from(u16_at(dns, 10)?);
    if flags & 0x8000 == 0 {
        return Some(Decision::Keep);
    }
    // Only a complete successful standard response is replayable.
    if flags & (0x7800 | 0x0200 | 0x000f) != 0 {
        return None;
    }
    let mut offset = 12;
    for _ in 0..questions {
        offset = name_end(dns, offset)?.checked_add(4)?;
        dns.get(..offset)?;
    }
    let mut min_ttl = u32::MAX;
    for _ in 0..records {
        offset = name_end(dns, offset)?;
        let header = dns.get(offset..offset + 10)?;
        let ttl = u32::from_be_bytes(header[4..8].try_into().ok()?);
        min_ttl = min_ttl.min(ttl);
        offset = offset.checked_add(10 + usize::from(u16_at(header, 8)?))?;
        dns.get(..offset)?;
    }
    if offset != dns.len() || records == 0 {
        return None;
    }
    if min_ttl == 0 {
        return Some(Decision::Clear);
    }
    Some(Decision::Replace {
        max_age_us: u64::from(min_ttl).min(45) * 1_000_000,
    })
}

/// Called only for local mDNS traffic. Unsupported/truncated layouts clear
/// stale replay data rather than guessing, without dropping the original TX.
pub fn classify(frame: &[u8]) -> Decision {
    let result = (|| {
        let (start, end) = match u16_at(frame, 12)? {
            0x86dd => {
                if frame.get(14)? >> 4 != 6 || *frame.get(20)? != 17 {
                    return None;
                }
                (54, 54 + usize::from(u16_at(frame, 18)?))
            }
            0x0800 => {
                if frame.get(14)? >> 4 != 4 || *frame.get(23)? != 17 {
                    return None;
                }
                let ihl = usize::from(frame[14] & 15) * 4;
                if ihl < 20 || u16_at(frame, 20)? & 0x3fff != 0 {
                    return None;
                }
                (14 + ihl, 14 + usize::from(u16_at(frame, 16)?))
            }
            _ => return None,
        };
        let udp = frame.get(start..end)?;
        if u16_at(udp, 0)? != 5353 || u16_at(udp, 2)? != 5353 {
            return None;
        }
        let len = usize::from(u16_at(udp, 4)?);
        if len != udp.len() {
            return None;
        }
        dns_decision(udp.get(8..len)?)
    })();
    result.unwrap_or(Decision::Clear)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn dns(ttls: &[u32]) -> Vec<u8> {
        let mut b = vec![0, 0, 0x84, 0, 0, 0, 0, ttls.len() as u8, 0, 0, 0, 0];
        for (i, ttl) in ttls.iter().enumerate() {
            if i == 0 {
                b.extend([1, b'x', 0]);
            } else {
                b.extend([0xc0, 12]);
            }
            b.extend([0, 16, 0x80, 1]); // TXT, cache-flush class
            b.extend(ttl.to_be_bytes());
            b.extend([0, 2, 1, b'a']);
        }
        b
    }
    fn ethernet(dns: &[u8]) -> Vec<u8> {
        let mut b = vec![0; 54];
        b[12..14].copy_from_slice(&0x86ddu16.to_be_bytes());
        b[14] = 0x60;
        b[20] = 17;
        let len = (8 + dns.len()) as u16;
        b[18..20].copy_from_slice(&len.to_be_bytes());
        b.extend(5353u16.to_be_bytes());
        b.extend(5353u16.to_be_bytes());
        b.extend(len.to_be_bytes());
        b.extend([0, 0]);
        b.extend(dns);
        b
    }
    #[test]
    fn response_uses_shortest_ttl_and_goodbye_clears() {
        assert_eq!(
            classify(&ethernet(&dns(&[120, 4500]))),
            Decision::Replace {
                max_age_us: 45_000_000
            }
        );
        assert_eq!(
            classify(&ethernet(&dns(&[120, 3]))),
            Decision::Replace {
                max_age_us: 3_000_000
            }
        );
        assert_eq!(classify(&ethernet(&dns(&[120, 0]))), Decision::Clear);
    }
    #[test]
    fn query_does_not_replace_cached_announcement() {
        let mut query = dns(&[120]);
        query[2] = 0;
        assert_eq!(classify(&ethernet(&query)), Decision::Keep);
    }
    #[test]
    fn cache_keeps_positive_response_across_queries_then_stops_on_goodbye() {
        let mut cache = crate::schedule::AnnounceCache::new(0, 45_000_000);
        cache.observe_mdns(&ethernet(&dns(&[3])), b"positive", 100);
        let mut query = dns(&[120]);
        query[2] = 0;
        cache.observe_mdns(&ethernet(&query), b"query", 200);
        assert_eq!(cache.rebroadcast(52, 300, &[52]), Some(&b"positive"[..]));
        assert!(cache.rebroadcast(52, 3_000_100, &[52]).is_none());
        cache.observe_mdns(&ethernet(&dns(&[120])), b"new", 4_000_000);
        cache.observe_mdns(&ethernet(&dns(&[0])), b"goodbye", 4_000_001);
        assert!(cache.rebroadcast(52, 4_000_002, &[52]).is_none());
    }

    #[test]
    fn ipv4_options_are_supported_but_fragments_are_not_replayed() {
        let payload = dns(&[120]);
        let mut b = vec![0; 14 + 24];
        b[12..14].copy_from_slice(&0x0800u16.to_be_bytes());
        b[14] = 0x46;
        b[23] = 17;
        b[16..18].copy_from_slice(&((24 + 8 + payload.len()) as u16).to_be_bytes());
        b.extend(5353u16.to_be_bytes());
        b.extend(5353u16.to_be_bytes());
        b.extend(((8 + payload.len()) as u16).to_be_bytes());
        b.extend([0, 0]);
        b.extend(payload);
        assert!(matches!(classify(&b), Decision::Replace { .. }));
        b[20] = 0x20;
        assert_eq!(classify(&b), Decision::Clear);
    }

    #[test]
    fn malformed_response_never_becomes_replay_data() {
        let original = ethernet(&dns(&[120, 30]));
        for len in 0..original.len() {
            assert_eq!(classify(&original[..len]), Decision::Clear);
        }
        let mut truncated = dns(&[120]);
        truncated[2] |= 2;
        assert_eq!(classify(&ethernet(&truncated)), Decision::Clear);
        let mut pointer = dns(&[120]);
        pointer[12..14].copy_from_slice(&[0xc0, 12]); // self pointer
        assert_eq!(classify(&ethernet(&pointer)), Decision::Clear);
        let mut oversize = dns(&[120]);
        oversize[23..25].copy_from_slice(&[0xff, 0xff]);
        assert_eq!(classify(&ethernet(&oversize)), Decision::Clear);
    }
}
