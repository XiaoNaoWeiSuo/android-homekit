//! Bounded, cookie-correlated nl80211 TX diagnostics. No radio side effects.
use serde::Serialize;
use std::collections::VecDeque;

#[derive(Debug, PartialEq)]
pub enum Event {
    Ack {
        seq: u32,
        error: i32,
    },
    Cookie {
        seq: u32,
        cookie: u64,
    },
    Status {
        ifindex: u32,
        cookie: u64,
        acked: bool,
    },
    RocCookie { seq: u32, cookie: u64 },
    Roc { ifindex: u32, cookie: u64, frequency: u32, ready: bool },
}

fn attrs(mut bytes: &[u8]) -> Option<Vec<(u16, &[u8])>> {
    let mut out = Vec::new();
    while !bytes.is_empty() {
        let header = bytes.get(..4)?;
        let len = u16::from_ne_bytes(header[..2].try_into().ok()?) as usize;
        if len < 4 {
            return None;
        }
        let value = bytes.get(4..len)?;
        let kind = u16::from_ne_bytes(header[2..4].try_into().ok()?) & 0x3fff;
        out.push((kind, value));
        if len == bytes.len() {
            break;
        }
        bytes = bytes.get((len + 3) & !3..)?;
    }
    Some(out)
}
fn field<'a>(fields: &[(u16, &'a [u8])], kind: u16) -> Option<&'a [u8]> {
    let mut matching = fields.iter().filter(|(id, _)| *id == kind);
    let value = matching.next()?.1;
    if matching.next().is_some() {
        return None;
    }
    Some(value)
}

/// Resolve the nested generic-netlink mlme group by name, never hardcode ID.
pub fn mlme_group(payload: &[u8]) -> Option<u32> {
    let outer = attrs(payload.get(4..)?)?;
    let groups = attrs(field(&outer, 7)?)?; // CTRL_ATTR_MCAST_GROUPS
    for (_, group) in groups {
        let fields = attrs(group)?;
        if field(&fields, 1)? == b"mlme\0" {
            return Some(u32::from_ne_bytes(field(&fields, 2)?.try_into().ok()?));
        }
    }
    None
}

/// Parse the entire datagram before exposing events. A malformed tail cannot
/// turn a partial prefix into a reported success. Sequence is for ACK/cookie;
/// TX_STATUS is asynchronous and is correlated by cookie and interface.
pub fn parse(mut bytes: &[u8], family: u16) -> Option<Vec<Event>> {
    let mut out = Vec::new();
    while !bytes.is_empty() {
        let header = bytes.get(..16)?;
        let len = u32::from_ne_bytes(header[..4].try_into().ok()?) as usize;
        if len < 16 {
            return None;
        }
        let body = bytes.get(16..len)?;
        let kind = u16::from_ne_bytes(header[4..6].try_into().ok()?);
        let seq = u32::from_ne_bytes(header[8..12].try_into().ok()?);
        if kind == 2 {
            let error = i32::from_ne_bytes(body.get(..4)?.try_into().ok()?);
            if error > 0 {
                return None;
            }
            out.push(Event::Ack { seq, error });
        } else if kind == family {
            let cmd = *body.first()?;
            if cmd == 55 || cmd == 56 {
                let fields = attrs(body.get(4..)?)?;
                let cookie = u64::from_ne_bytes(field(&fields, 88)?.try_into().ok()?);
                if let Some(index) = field(&fields, 3) {
                    out.push(Event::Roc {
                        ifindex: u32::from_ne_bytes(index.try_into().ok()?),
                        cookie,
                        frequency: u32::from_ne_bytes(field(&fields, 38)?.try_into().ok()?),
                        ready: cmd == 55,
                    });
                } else if cmd == 55 {
                    out.push(Event::RocCookie { seq, cookie });
                }
            } else if cmd == 59 || cmd == 60 {
                let fields = attrs(body.get(4..)?)?;
                let cookie = u64::from_ne_bytes(field(&fields, 88)?.try_into().ok()?);
                if cmd == 59 {
                    out.push(Event::Cookie { seq, cookie });
                } else {
                    let ifindex = u32::from_ne_bytes(field(&fields, 3)?.try_into().ok()?);
                    if fields.iter().filter(|(id, _)| *id == 92).count() > 1 {
                        return None;
                    }
                    let acked = match field(&fields, 92) {
                        Some([]) => true,
                        Some(_) => return None,
                        None => false,
                    };
                    out.push(Event::Status {
                        ifindex,
                        cookie,
                        acked,
                    });
                }
            }
        }
        if len == bytes.len() {
            break;
        }
        bytes = bytes.get((len + 3) & !3..)?;
    }
    Some(out)
}

#[derive(Clone, Debug, Default, Serialize)]
pub struct Stats {
    pub subscribed: bool,
    pub requests_accepted: u64,
    pub requests_failed: u64,
    pub unicast_acked: u64,
    pub unicast_no_ack: u64,
    pub status_missing: u64,
    pub cookie_missing: u64,
    pub pending: usize,
}
struct Pending {
    cookie: u64,
    at: u64,
}
const CAP: usize = 128;
const TIMEOUT_MS: u64 = 5000;
pub struct Tracker {
    ifindex: u32,
    request: Option<(u32, Option<u64>)>,
    pending: VecDeque<Pending>,
    early: VecDeque<(u64, bool)>,
    stats: Stats,
}
impl Tracker {
    pub fn new(ifindex: u32, subscribed: bool) -> Self {
        Self {
            ifindex,
            request: None,
            pending: VecDeque::new(),
            early: VecDeque::new(),
            stats: Stats {
                subscribed,
                ..Stats::default()
            },
        }
    }
    pub fn begin(&mut self, seq: u32) {
        self.request = Some((seq, None));
        self.early.clear();
    }
    pub fn finish(&mut self, accepted: bool, now_ms: u64) {
        if let Some((_, cookie)) = self.request.take() {
            if accepted {
                if let Some(cookie) = cookie {
                    if let Some(i) = self.early.iter().position(|(c, _)| *c == cookie) {
                        let (_, acked) = self.early.remove(i).unwrap();
                        self.record(acked);
                    } else {
                        if self.pending.len() == CAP {
                            self.pending.pop_front();
                            self.stats.status_missing += 1;
                        }
                        self.pending.push_back(Pending { cookie, at: now_ms });
                    }
                } else {
                    self.stats.cookie_missing += 1;
                }
            }
        }
        self.early.clear();
    }
    pub fn event(&mut self, event: &Event, now_ms: u64) {
        self.expire(now_ms);
        match *event {
            Event::Cookie { seq, cookie } if self.request.is_some_and(|(s, _)| s == seq) => {
                self.request = Some((seq, Some(cookie)));
            }
            Event::Status {
                ifindex,
                cookie,
                acked,
            } if ifindex == self.ifindex => {
                if let Some(i) = self.pending.iter().position(|p| p.cookie == cookie) {
                    self.pending.remove(i);
                    self.record(acked);
                } else if self.request.is_some() {
                    if self.early.len() == CAP {
                        self.early.pop_front();
                    }
                    self.early.push_back((cookie, acked));
                }
            }
            _ => {}
        }
    }
    fn record(&mut self, acked: bool) {
        if acked {
            self.stats.unicast_acked += 1;
        } else {
            self.stats.unicast_no_ack += 1;
        }
    }
    pub fn expire(&mut self, now: u64) {
        while self
            .pending
            .front()
            .is_some_and(|p| now.saturating_sub(p.at) >= TIMEOUT_MS)
        {
            self.pending.pop_front();
            self.stats.status_missing += 1;
        }
    }
    pub fn snapshot(&mut self, now: u64) -> Stats {
        self.expire(now);
        let mut stats = self.stats.clone();
        stats.pending = self.pending.len();
        stats
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn attr(kind: u16, value: &[u8]) -> Vec<u8> {
        let mut b = Vec::new();
        let len = value.len() + 4;
        b.extend_from_slice(&(len as u16).to_ne_bytes());
        b.extend_from_slice(&kind.to_ne_bytes());
        b.extend_from_slice(value);
        b.resize((len + 3) & !3, 0);
        b
    }
    fn message(kind: u16, seq: u32, body: &[u8]) -> Vec<u8> {
        let mut b = Vec::new();
        let len = body.len() + 16;
        b.extend_from_slice(&(len as u32).to_ne_bytes());
        b.extend_from_slice(&kind.to_ne_bytes());
        b.extend_from_slice(&0u16.to_ne_bytes());
        b.extend_from_slice(&seq.to_ne_bytes());
        b.extend_from_slice(&0u32.to_ne_bytes());
        b.extend_from_slice(body);
        b.resize((len + 3) & !3, 0);
        b
    }
    fn status_payload(ifindex: u32, cookie: u64, ack: bool) -> Vec<u8> {
        let mut b = vec![60, 1, 0, 0];
        b.extend(attr(3, &ifindex.to_ne_bytes()));
        b.extend(attr(88, &cookie.to_ne_bytes()));
        if ack {
            b.extend(attr(92, &[]));
        }
        b
    }
    fn status(cookie: u64, acked: bool) -> Event {
        Event::Status {
            ifindex: 35,
            cookie,
            acked,
        }
    }
    #[test]
    fn combined_cookie_ack_and_status_are_all_parsed() {
        let mut reply = vec![59, 1, 0, 0];
        reply.extend(attr(88, &99u64.to_ne_bytes()));
        let mut b = message(44, 17, &reply);
        b.extend(message(2, 17, &0i32.to_ne_bytes()));
        b.extend(message(44, 0, &status_payload(35, 99, true)));
        assert_eq!(
            parse(&b, 44),
            Some(vec![
                Event::Cookie {
                    seq: 17,
                    cookie: 99
                },
                Event::Ack { seq: 17, error: 0 },
                status(99, true),
            ])
        );
    }
    #[test]
    fn group_id_resolves_from_nested_attributes_with_flags() {
        let mut group = attr(1, b"mlme\0");
        group.extend(attr(2, &29u32.to_ne_bytes()));
        let mut reply = vec![1, 2, 0, 0];
        reply.extend(attr(0x8007, &attr(0x8001, &group)));
        assert_eq!(mlme_group(&reply), Some(29));
        reply.pop();
        assert!(mlme_group(&reply).is_none());
    }
    #[test]
    fn malformed_tail_and_attribute_lengths_are_rejected() {
        let good = message(44, 0, &status_payload(35, 9, true));
        for end in 1..good.len() {
            assert!(parse(&good[..end], 44).is_none(), "truncation {end}");
        }
        let mut bad = message(2, 1, &0i32.to_ne_bytes());
        bad.extend_from_slice(&[0, 0]);
        assert!(parse(&bad, 44).is_none());
        let mut duplicate = status_payload(35, 9, true);
        duplicate.extend(attr(92, &[]));
        assert!(parse(&message(44, 0, &duplicate), 44).is_none());
        let mut invalid_ack = status_payload(35, 9, false);
        invalid_ack.extend(attr(92, &[1]));
        assert!(parse(&message(44, 0, &invalid_ack), 44).is_none());
    }
    #[test]
    fn roc_reply_ready_expiry_are_distinct_from_tx_status() {
        let mut reply = vec![55, 1, 0, 0];
        reply.extend(attr(88, &256u64.to_ne_bytes()));
        let mut ready = reply.clone();
        ready.extend(attr(3, &7u32.to_ne_bytes()));
        ready.extend(attr(38, &5745u32.to_ne_bytes()));
        let mut expired = ready.clone(); expired[0] = 56;
        let mut bytes = message(44, 10, &reply);
        bytes.extend(message(2, 10, &0i32.to_ne_bytes()));
        bytes.extend(message(44, 0, &ready));
        bytes.extend(message(44, 0, &expired));
        assert_eq!(parse(&bytes, 44), Some(vec![
            Event::RocCookie { seq: 10, cookie: 256 },
            Event::Ack { seq: 10, error: 0 },
            Event::Roc { ifindex: 7, cookie: 256, frequency: 5745, ready: true },
            Event::Roc { ifindex: 7, cookie: 256, frequency: 5745, ready: false },
        ]));
        ready.extend(attr(38, &2437u32.to_ne_bytes()));
        assert!(parse(&message(44, 0, &ready), 44).is_none());
    }

    #[test]
    fn status_before_cookie_counts_once_only_after_acceptance() {
        let mut t = Tracker::new(35, true);
        t.begin(7);
        t.event(&status(99, true), 0);
        t.event(&Event::Cookie { seq: 7, cookie: 99 }, 1);
        assert_eq!(t.snapshot(1).unicast_acked, 0);
        t.finish(true, 2);
        t.event(&status(99, true), 3);
        assert_eq!(t.snapshot(3).unicast_acked, 1);
        assert_eq!(t.snapshot(3).pending, 0);
    }
    #[test]
    fn rejects_other_interfaces_sequences_and_cookies() {
        let mut t = Tracker::new(35, true);
        t.begin(7);
        t.event(
            &Event::Status {
                ifindex: 36,
                cookie: 99,
                acked: true,
            },
            0,
        );
        t.event(&Event::Cookie { seq: 8, cookie: 98 }, 0);
        t.event(&Event::Cookie { seq: 7, cookie: 99 }, 0);
        t.finish(true, 0);
        t.event(&status(98, true), 1);
        assert_eq!(t.snapshot(1).pending, 1);
        assert_eq!(t.snapshot(1).unicast_acked, 0);
        t.event(&status(99, false), 2);
        assert_eq!(t.snapshot(2).unicast_no_ack, 1);
    }
    #[test]
    fn missing_status_is_unknown_and_memory_is_bounded() {
        let mut t = Tracker::new(35, false);
        for seq in 1..=130 {
            t.begin(seq);
            t.event(
                &Event::Cookie {
                    seq,
                    cookie: u64::from(seq),
                },
                0,
            );
            t.finish(true, 0);
        }
        assert_eq!(t.snapshot(0).pending, CAP);
        assert_eq!(t.snapshot(0).status_missing, 2);
        let stats = t.snapshot(TIMEOUT_MS);
        assert!(!stats.subscribed);
        assert_eq!(stats.status_missing, 130);
        assert_eq!(stats.unicast_no_ack, 0);
        assert_eq!(stats.unicast_acked, 0);
        t.begin(131);
        t.finish(true, TIMEOUT_MS);
        assert_eq!(t.snapshot(TIMEOUT_MS).cookie_missing, 1);
    }
    #[test]
    fn unrelated_family_and_sequence_are_not_request_success() {
        let mut b = message(45, 7, &status_payload(35, 99, true));
        b.extend(message(2, 8, &0i32.to_ne_bytes()));
        b.extend(message(2, 7, &(-22i32).to_ne_bytes()));
        assert_eq!(
            parse(&b, 44),
            Some(vec![
                Event::Ack { seq: 8, error: 0 },
                Event::Ack { seq: 7, error: -22 },
            ])
        );
    }

    #[test]
    fn failed_request_cannot_contribute_a_success() {
        let mut t = Tracker::new(35, true);
        t.begin(7);
        t.event(&Event::Cookie { seq: 7, cookie: 99 }, 0);
        t.event(&status(99, true), 1);
        t.finish(false, 2);
        t.event(&status(99, true), 3);
        assert_eq!(t.snapshot(3).unicast_acked, 0);
        assert_eq!(t.snapshot(3).pending, 0);
    }
}
