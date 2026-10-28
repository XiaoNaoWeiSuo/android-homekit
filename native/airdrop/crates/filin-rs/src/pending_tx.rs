//! Bounded unicast backlog. A peer with no shared radio channel must not stop
//! TAP reads (including discovery and traffic for other peers).
use crate::schedule::DataSendDecision;
use std::collections::{HashSet, VecDeque};

pub const MAX_PACKETS: usize = 32;
pub const MAX_PER_PEER: usize = 8;
pub const MAX_BYTES: usize = 64 * 1024;
pub const MAX_AGE_US: u64 = 2 * crate::schedule::HOP_CYCLE_US;

#[derive(Debug)]
pub struct Frame {
    pub radiotap_frame: Vec<u8>,
    pub dst: [u8; 6],
    pub seq: u16,
}

#[derive(Default)]
pub struct PendingTx {
    frames: VecDeque<(u64, Frame)>,
    bytes: usize,
}

impl PendingTx {
    /// Reject newest on overflow; one unreachable peer cannot fill the queue.
    /// The caller keeps reading TAP even when this returns false.
    pub fn push(&mut self, frame: Frame, now_us: u64) -> bool {
        self.expire(now_us);
        let size = frame.radiotap_frame.len();
        if size == 0
            || size > MAX_BYTES
            || self.frames.len() >= MAX_PACKETS
            || self.bytes + size > MAX_BYTES
            || self
                .frames
                .iter()
                .filter(|(_, f)| f.dst == frame.dst)
                .count()
                >= MAX_PER_PEER
        {
            return false;
        }
        self.bytes += size;
        self.frames.push_back((now_us, frame));
        true
    }

    fn expire(&mut self, now_us: u64) {
        self.frames.retain(|(queued_us, frame)| {
            if now_us.saturating_sub(*queued_us) < MAX_AGE_US {
                true
            } else {
                self.bytes -= frame.radiotap_frame.len();
                tracing::trace!(dst = ?frame.dst, seq = frame.seq, "expired queued unicast");
                false
            }
        });
    }

    /// Visit every destination, retaining FIFO order within each destination.
    /// Hold also covers a transient send error, so later packets for that same
    /// peer must wait. Age is measured from enqueue, never refreshed by retry.
    pub fn drain(&mut self, now_us: u64, mut send: impl FnMut(&Frame) -> DataSendDecision) {
        self.expire(now_us);
        let mut blocked = HashSet::new();
        for _ in 0..self.frames.len() {
            let (queued_us, frame) = self.frames.pop_front().unwrap();
            if blocked.contains(&frame.dst) || send(&frame) == DataSendDecision::Hold {
                blocked.insert(frame.dst);
                self.frames.push_back((queued_us, frame));
            } else {
                self.bytes -= frame.radiotap_frame.len();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{peers::PeerTable, schedule, sync::SyncState};

    fn frame(peer: u8, seq: u16, size: usize) -> Frame {
        Frame {
            dst: [2, 0, 0, 0, 0, peer],
            seq,
            radiotap_frame: vec![0; size],
        }
    }

    #[test]
    fn captured_no_shared_channel_peer_does_not_block_reachable_peer() {
        let mut peers = PeerTable::new();
        for id in [1, 2] {
            peers.touch(frame(id, 0, 1).dst, 0);
        }
        // Mac 7e:4c:de:a9:3e:54, lease-20261027/radio.pcap: no channel 52.
        let captured = [149, 149, 149, 0, 0, 0, 0, 0, 6, 149, 149, 0, 0, 0, 0, 0];
        peers.get_mut(&frame(1, 0, 1).dst).unwrap().sequence = captured.map(|ch| [ch, 0]);
        peers.get_mut(&frame(2, 0, 1).dst).unwrap().sequence = [[52, 0]; 16];
        let mut queue = PendingTx::default();
        for (id, seq) in [(1, 1), (1, 2), (2, 3), (2, 4)] {
            assert!(queue.push(frame(id, seq, 128), 0));
        }
        let sync = SyncState::new(0);
        let mut sent = Vec::new();
        for slot in 0..16 {
            queue.drain(slot * 65_536 + 32_768, |f| {
                let result = schedule::data_send_decision(
                    &f.dst,
                    52,
                    &[[52, 0]; 16],
                    &peers,
                    &sync,
                    slot * 65_536 + 32_768,
                );
                if result == DataSendDecision::Send {
                    sent.push(f.seq);
                }
                result
            });
        }
        assert_eq!(sent, [3, 4]);
        assert_eq!(queue.frames.len(), 2);
        queue.drain(MAX_AGE_US, |_| panic!("expired frames must never be sent"));
        assert_eq!(queue.bytes, 0);
    }

    #[test]
    fn transient_failure_preserves_peer_order_but_allows_other_destinations() {
        let mut q = PendingTx::default();
        for (id, seq) in [(1, 1), (1, 2), (2, 3)] {
            q.push(frame(id, seq, 100), 0);
        }
        let mut seen = Vec::new();
        q.drain(1, |f| {
            seen.push(f.seq);
            if f.seq == 1 {
                DataSendDecision::Hold
            } else {
                DataSendDecision::Send
            }
        });
        assert_eq!(seen, [1, 3]);
        q.drain(2, |f| {
            seen.push(f.seq);
            DataSendDecision::Send
        });
        assert_eq!(seen, [1, 3, 1, 2]);
        assert_eq!(q.bytes, 0);
    }

    #[test]
    fn overflow_is_bounded_per_peer_by_packets_and_bytes() {
        let mut q = PendingTx::default();
        assert!(!q.push(frame(1, 0, 0), 0));
        assert!(!q.push(frame(1, 0, MAX_BYTES + 1), 0));
        for seq in 0..MAX_PER_PEER {
            assert!(q.push(frame(1, seq as u16, 128), 0));
        }
        assert!(!q.push(frame(1, 99, 128), 0));
        for id in 2..=4 {
            for seq in 0..MAX_PER_PEER {
                assert!(q.push(frame(id, seq as u16, 128), 0));
            }
        }
        assert_eq!(q.frames.len(), MAX_PACKETS);
        assert!(!q.push(frame(5, 0, 128), 0));
        // Enqueue also reclaims expiry without waiting for a drain.
        assert!(q.push(frame(5, 1, MAX_BYTES), MAX_AGE_US));
        assert!(!q.push(frame(6, 1, 1), MAX_AGE_US));
        q.drain(MAX_AGE_US + 1, |_| DataSendDecision::Drop);
        assert!(q.push(frame(6, 2, MAX_BYTES), MAX_AGE_US + 1));
    }

    #[test]
    fn retries_do_not_extend_deadline_or_resurrect_removed_peer() {
        let mut q = PendingTx::default();
        q.push(frame(1, 1, 128), 10);
        q.drain(MAX_AGE_US, |_| DataSendDecision::Hold);
        q.drain(MAX_AGE_US + 10, |_| panic!("retry must not renew age"));
        assert_eq!(q.bytes, 0);
        q.push(frame(1, 2, 128), MAX_AGE_US + 10);
        q.drain(MAX_AGE_US + 11, |_| DataSendDecision::Drop);
        q.drain(MAX_AGE_US + 12, |_| {
            panic!("dropped peer must not be retried")
        });
        assert_eq!(q.bytes, 0);
    }
}
