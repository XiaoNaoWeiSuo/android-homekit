//! Hold already-validated local unicast briefly when DATA precedes peer Action.
//! This never creates a peer, changes election state, or admits unknown traffic.
use crate::data_rx::Delivery;
use std::collections::VecDeque;

const MAX_AGE_US: u64 = 250_000;
const MAX_PACKETS: usize = 16;
const MAX_BYTES: usize = 64 * 1024;

pub struct Pending {
    pub delivery: Delivery,
    pub channel: u8,
    pub received_us: u64,
    bytes: usize,
}

#[derive(Default)]
pub struct PendingRx {
    queue: VecDeque<Pending>,
    bytes: usize,
}

impl PendingRx {
    pub fn push(&mut self, delivery: Delivery, channel: u8, now_us: u64) -> bool {
        let bytes = delivery.frames.iter().map(|f| f.bytes.len()).sum::<usize>();
        if !delivery.unicast || channel == 0 || bytes == 0 || bytes > MAX_BYTES {
            return false;
        }
        while self.queue.len() >= MAX_PACKETS || self.bytes + bytes > MAX_BYTES {
            if let Some(old) = self.queue.pop_front() {
                self.bytes -= old.bytes;
            }
        }
        self.bytes += bytes;
        self.queue.push_back(Pending {
            delivery,
            channel,
            received_us: now_us,
            bytes,
        });
        true
    }

    /// Only release for a peer established by the Action receive path. Expiry
    /// is checked first, so a late Action cannot resurrect an old datagram.
    pub fn ready(&mut self, now_us: u64, established: impl Fn(&[u8; 6]) -> bool) -> Vec<Pending> {
        let mut ready = Vec::new();
        let mut waiting = VecDeque::new();
        while let Some(item) = self.queue.pop_front() {
            if now_us.saturating_sub(item.received_us) >= MAX_AGE_US {
                self.bytes -= item.bytes;
            } else if established(&item.delivery.source) {
                self.bytes -= item.bytes;
                ready.push(item);
            } else {
                waiting.push_back(item);
            }
        }
        self.queue = waiting;
        ready
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn packet(peer: u8, length: usize, unicast: bool) -> Delivery {
        Delivery {
            source: [2, 0, 0, 0, 0, peer],
            frames: vec![crate::awdl::EthernetFrame {
                bytes: vec![peer; length],
            }],
            unicast,
        }
    }
    #[test]
    fn data_before_action_released_once_for_exact_peer() {
        let mut q = PendingRx::default();
        assert!(q.push(packet(1, 128, true), 52, 1000));
        assert!(q.push(packet(2, 128, true), 52, 1001));
        assert!(q.ready(2000, |_| false).is_empty());
        let r = q.ready(24000, |p| p[5] == 1);
        assert_eq!(r.len(), 1);
        assert_eq!(r[0].delivery.frames[0].bytes, vec![1; 128]);
        assert_eq!((r[0].channel, r[0].received_us), (52, 1000));
        assert!(q.ready(25000, |p| p[5] == 1).is_empty());
        assert_eq!(q.ready(26000, |_| true).len(), 1);
        assert_eq!(q.bytes, 0);
    }
    #[test]
    fn late_action_cannot_revive_expired_data() {
        let mut q = PendingRx::default();
        q.push(packet(1, 128, true), 52, 1000);
        assert!(q.ready(251000, |_| true).is_empty());
        assert_eq!(q.bytes, 0);
    }
    #[test]
    fn multicast_empty_and_oversize_never_buffered() {
        let mut q = PendingRx::default();
        assert!(!q.push(packet(1, 128, false), 52, 0));
        assert!(!q.push(packet(1, 0, true), 52, 0));
        assert!(!q.push(packet(1, MAX_BYTES + 1, true), 52, 0));
        assert!(!q.push(packet(1, 128, true), 0, 0));
        assert_eq!(q.bytes, 0);
    }
    #[test]
    fn packet_and_memory_bounds_evict_oldest() {
        let mut q = PendingRx::default();
        for i in 0..17 {
            q.push(packet(i, 100, true), 52, i as u64);
        }
        let r = q.ready(100, |_| true);
        assert_eq!(r.len(), MAX_PACKETS);
        assert_eq!(r[0].delivery.source[5], 1);
        q.push(packet(1, 40000, true), 52, 100);
        q.push(packet(2, 40000, true), 52, 101);
        assert_eq!(q.bytes, 40000);
        assert_eq!(q.ready(102, |_| true)[0].delivery.source[5], 2);
    }
}
