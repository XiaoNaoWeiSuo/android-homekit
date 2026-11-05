//! One bounded, cookie/frequency/interface-matched channel reservation.
//! A request ACK never grants permission to transmit: READY is required too.
use crate::tx_status::Event;

/// Four-slot availability pattern in ROC mode: slot 0 on the STA *home*
/// channel, slots 1 and 2 on the ROC social channel, slot 3 null (back to STA).
///
/// Infrastructure-anchored Apple peers commonly put their own channel (e.g. 52)
/// in slot 0 and a social channel (e.g. 149) in slots 2 and 10; slot 0 and 10
/// are the discovery EAWs. Keeping slot 0 on the home channel makes the phone
/// co-channel with such a peer for the slot-0 discovery EAW instead of dwelling
/// on 149 where the peer cannot hear it. Slots 1/2 (and 2/10 shared with the
/// peer) still cover data on the ROC channel. When the home channel is absent
/// or equal to the ROC channel, fall back to the pure 3-of-4 ROC duty so we
/// never advertise a channel we cannot actually reach.
pub fn sequence(roc_channel: u8, home_channel: u8) -> [[u8; 2]; 16] {
    let roc = [roc_channel, opclass(roc_channel)];
    let home = [home_channel, opclass(home_channel)];
    let fallback = home_channel == 0 || home_channel == roc_channel;
    std::array::from_fn(|i| match i % 4 {
        0 if !fallback => home,
        0..=2 => roc,
        _ => [0, 0],
    })
}

/// Choose the home visit from the freshest valid peer, rather than assuming
/// that every peer is infrastructure-anchored on our STA channel. An Apple
/// device with a fully social 149 sequence cannot hear a slot-0 announce on 52;
/// forcing 52 there used to strand both discovery and its unicast replies.
pub fn sequence_for_peers<'a>(
    roc_channel: u8,
    home_channel: u8,
    peers: impl Iterator<Item = &'a crate::peers::Peer>,
) -> [[u8; 2]; 16] {
    let observed = peers
        .filter(|peer| peer.is_valid)
        .filter(|peer| matches!(peer.sequence[0][0], 52 | 149))
        .max_by_key(|peer| peer.last_update_us)
        .map(|peer| peer.sequence[0][0]);
    sequence(roc_channel, observed.unwrap_or(home_channel))
}

/// Resolve an advertised slot to a channel this radio can actually serve.
/// A null slot in the all-social pattern continues the ROC visit; in the
/// mixed pattern it is an unavailable return-to-STA gap. This is only the
/// requested channel: the runtime must still require a live ROC reservation.
pub fn capture_channel(
    sequence: &[[u8; 2]; 16],
    slot: usize,
    roc_channel: u8,
    sta_home_channel: u8,
) -> u8 {
    let advertised = sequence[slot % 16][0];
    let requested = if advertised == 0 && sequence[0][0] == roc_channel {
        roc_channel
    } else {
        advertised
    };
    if requested == roc_channel || requested == sta_home_channel {
        requested
    } else {
        0
    }
}

/// TX view of our own ROC availability. Only the exact all-social ROC
/// pattern has null slots that continue the same reservation. Do not infer
/// this from arbitrary peer sequences, and never use it as proof of READY:
/// `current_channel` must already come from the runtime reservation check.
pub fn tx_sequence(
    advertised: &[[u8; 2]; 16],
    roc_channel: u8,
    current_channel: u8,
) -> [[u8; 2]; 16] {
    if roc_channel != 0
        && current_channel == roc_channel
        && *advertised == sequence(roc_channel, roc_channel)
    {
        [[roc_channel, opclass(roc_channel)]; 16]
    } else {
        *advertised
    }
}

fn opclass(channel: u8) -> u8 {
    match channel {
        6 => 0x51,
        // Captured channel-52 station peers advertise operating class 118.
        52 => 0x76,
        _ => 0x80,
    }
}

/// Time (µs) until the run of consecutive slots currently on `channel` ends, or
/// 0 when the current EAW slot is not on `channel`. Generalises the old fixed
/// `[roc, roc, roc, null]` assumption to any sequence, so the ROC dwell covers
/// exactly the ROC-channel slots (and the slot-0 home visit is not requested).
pub fn remaining_slot_us(
    sequence: &[[u8; 2]; 16],
    sync: &crate::sync::SyncState,
    channel: u8,
    now: u64,
) -> u64 {
    if channel == 0 {
        return 0;
    }
    let slot = usize::from(sync.current_eaw(now) % 16);
    if capture_channel(sequence, slot, channel, 0) != channel {
        return 0;
    }
    let eaw = u64::from(sync.presence_mode) * u64::from(sync.aw_period_tu) * 1024;
    let mut run = 0u64;
    // Null slots return the firmware to the ROC/home channel. If that is
    // social too, they extend the dwell rather than cutting it to zero.
    for _ in 0..16 {
        let next = (slot + run as usize + 1) % 16;
        if sequence[next][0] != channel && sequence[next][0] != 0 {
            break;
        }
        if run > 0 && sequence[next][0] == channel {
            break;
        }
        run += 1;
    }
    sync.next_aw_us(now) + run * eaw
}

#[derive(Default, Debug)]
pub struct Reservation {
    request: Option<Request>,
}

#[derive(Debug)]
struct Request {
    seq: u32,
    ifindex: u32,
    frequency: u32,
    duration_us: u64,
    requested_us: u64,
    accepted: bool,
    cookie: Option<u64>,
    early: Vec<(u64, bool, u64)>,
    ready_until_us: Option<u64>,
    ended: bool,
}

impl Reservation {
    pub fn start(
        &mut self,
        seq: u32,
        ifindex: u32,
        frequency: u32,
        duration_ms: u32,
        now: u64,
    ) -> bool {
        if self.request.is_some()
            || ifindex == 0
            || !matches!(frequency, 2437 | 5220 | 5745)
            || duration_ms == 0
            || duration_ms > 2000
        {
            return false;
        }
        self.request = Some(Request {
            seq,
            ifindex,
            frequency,
            duration_us: u64::from(duration_ms) * 1000,
            requested_us: now,
            accepted: false,
            cookie: None,
            early: Vec::new(),
            ready_until_us: None,
            ended: false,
        });
        true
    }

    pub fn event(&mut self, event: &Event, now: u64) {
        let Some(r) = self.request.as_mut() else {
            return;
        };
        if matches!(event, Event::RocCookie { .. } | Event::Roc { .. }) {
            tracing::debug!(?event, "ROC driver event");
        }
        match *event {
            Event::Ack { seq, error } if seq == r.seq => {
                r.accepted = error == 0;
                if error != 0 {
                    r.ended = true;
                }
            }
            Event::RocCookie { seq, cookie } if seq == r.seq => {
                r.cookie = Some(cookie);
                for (c, ready, at) in std::mem::take(&mut r.early) {
                    if c == cookie {
                        r.apply(ready, at);
                    }
                }
            }
            Event::Roc {
                ifindex,
                cookie,
                frequency,
                ready,
            } if ifindex == r.ifindex && frequency == r.frequency => {
                if r.cookie == Some(cookie) {
                    r.apply(ready, now);
                } else if r.cookie.is_none() && r.early.len() < 8 {
                    r.early.push((cookie, ready, now));
                }
            }
            _ => {}
        }
    }

    pub fn active_frequency(&self, now: u64) -> Option<u32> {
        let r = self.request.as_ref()?;
        (r.accepted && !r.ended && r.ready_until_us.is_some_and(|end| now + 10_000 < end))
            .then_some(r.frequency)
    }

    /// Bound both missing READY and missing EXPIRED; caller cancels before reuse.
    pub fn finished(&self, now: u64) -> bool {
        self.request.as_ref().is_some_and(|r| {
            r.ended
                || now
                    >= r.ready_until_us
                        .unwrap_or(r.requested_us.saturating_add(500_000))
        })
    }
    pub fn cookie(&self) -> Option<u64> {
        self.request.as_ref()?.cookie
    }
    pub fn pending(&self) -> bool {
        self.request.is_some()
    }
    pub fn clear(&mut self) {
        self.request = None;
    }
}

impl Request {
    fn apply(&mut self, ready: bool, now: u64) {
        if !ready {
            self.ended = true;
            self.ready_until_us = None;
        } else if !self.ended && self.ready_until_us.is_none() {
            self.ready_until_us = Some(now.saturating_add(self.duration_us));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn tx_uses_destination_clock_when_two_clusters_have_different_counters() {
        use crate::{peers::PeerTable, schedule, sync::SyncState};
        let mac = [2, 0, 0, 0, 0, 2];
        let own = SyncState::new(0);
        let mut theirs = SyncState::new(0);
        theirs.aw_counter = 4;
        let mut peers = PeerTable::new();
        peers.touch(mac, 0);
        let peer = peers.get_mut(&mac).unwrap();
        peer.sequence[2] = [149, 128];
        peer.sync = Some(theirs);
        // Ours slot 1 is the destination's slot 2: send is possible now.
        let now = 65_536 + 32_768;
        assert_eq!(
            schedule::data_send_decision(&mac, 149, &sequence(149, 52), &peers, &own, now),
            schedule::DataSendDecision::Send
        );
        // Ours slot 2 is the destination's null slot 3: must hold.
        assert_eq!(
            schedule::data_send_decision(&mac, 149, &sequence(149, 52), &peers, &own, now + 65_536),
            schedule::DataSendDecision::Hold
        );
    }

    #[test]
    fn infrastructure_mac_social_slots_have_a_transmit_window() {
        use crate::{peers::PeerTable, schedule, sync::SyncState};
        let mac = [0x7e, 0x4c, 0xde, 0xa9, 0x3e, 0x54];
        let mut peers = PeerTable::new();
        peers.touch(mac, 0);
        peers.get_mut(&mac).unwrap().sequence =
            [52, 0, 149, 0, 0, 0, 0, 0, 6, 0, 149, 0, 0, 0, 0, 0].map(|ch| [ch, 0]);
        let sync = SyncState::new(0);
        let own = sequence(149, 52);
        for slot in [2, 10] {
            let now = slot * 65_536 + 32_768;
            assert!(remaining_slot_us(&own, &sync, 149, now) > 0);
            assert_eq!(
                schedule::data_send_decision(&mac, 149, &own, &peers, &sync, now),
                schedule::DataSendDecision::Send
            );
        }
    }
    #[test]
    fn slot_zero_home_channel_lets_us_meet_an_infra_peer_on_its_slot_zero() {
        use crate::{peers::PeerTable, schedule, sync::SyncState};
        // The peer's real capture: 52 in slot 0 (discovery EAW), 149 in 2/10.
        let mac = [0xfa, 0x2f, 0xd3, 0xae, 0xc4, 0xe3];
        let mut peers = PeerTable::new();
        peers.touch(mac, 0);
        peers.get_mut(&mac).unwrap().sequence =
            [52, 0, 149, 0, 0, 0, 0, 0, 6, 0, 149, 0, 0, 0, 0, 0].map(|ch| [ch, 0]);
        let sync = SyncState::new(0);
        let own = sequence(149, 52);
        // Slot 0 is now on the home channel 52, matching the peer there.
        assert_eq!(own[0][0], 52);
        let now = 0 * 65_536 + 32_768;
        assert_eq!(
            schedule::data_send_decision(&mac, 52, &own, &peers, &sync, now),
            schedule::DataSendDecision::Send
        );
        // Slot 1 is not on a channel the peer uses; must not look sendable.
        let now1 = 1 * 65_536 + 32_768;
        assert_ne!(
            schedule::data_send_decision(&mac, 149, &own, &peers, &sync, now1),
            schedule::DataSendDecision::Send
        );
    }
    #[test]
    fn social_null_slots_extend_the_roc_reservation() {
        use crate::sync::SyncState;
        let sync = SyncState {
            aw_period_tu: 64,
            presence_mode: 1,
            aw_counter: 0,
            last_update_us: 0,
        };
        let seq = sequence(149, 149);
        assert_eq!(remaining_slot_us(&seq, &sync, 149, 0), 2 * 65_536);
    }

    #[test]
    fn capture_follows_updated_advertisement_not_startup_pattern() {
        let mut advertised = sequence(149, 52);
        assert_eq!(capture_channel(&advertised, 0, 149, 52), 52);
        assert_eq!(capture_channel(&advertised, 3, 149, 52), 0);
        advertised = sequence(149, 149);
        assert_eq!(capture_channel(&advertised, 0, 149, 52), 149);
        assert_eq!(capture_channel(&advertised, 3, 149, 52), 149);
        advertised = sequence(149, 52);
        assert_eq!(capture_channel(&advertised, 0, 149, 52), 52);
        assert_eq!(capture_channel(&advertised, 3, 149, 52), 0);
        advertised[0] = [44, 0x80];
        assert_eq!(capture_channel(&advertised, 0, 149, 52), 0);
    }

    #[test]
    fn all_social_null_slot_has_a_bounded_reservation_window() {
        let sync = crate::sync::SyncState {
            aw_period_tu: 64,
            presence_mode: 1,
            aw_counter: 0,
            last_update_us: 0,
        };
        let advertised = sequence(149, 149);
        let now = 3 * 65_536 + 32_768;
        assert_eq!(capture_channel(&advertised, 3, 149, 52), 149);
        assert!(remaining_slot_us(&advertised, &sync, 149, now) > 0);
        assert!(remaining_slot_us(&advertised, &sync, 149, now) <= 196_608);
        assert_eq!(remaining_slot_us(&sequence(149, 52), &sync, 149, now), 0);
    }

    #[test]
    fn tx_null_continuation_requires_ready_and_preserves_peer_constraints() {
        use crate::{peers::PeerTable, schedule, sync::SyncState};
        let own = sequence(149, 149);
        let mixed = sequence(149, 52);
        assert_eq!(tx_sequence(&own, 149, 0), own);
        assert_eq!(tx_sequence(&mixed, 149, 149), mixed);
        let mut arbitrary = own;
        arbitrary[4] = [52, 0x76];
        assert_eq!(tx_sequence(&arbitrary, 149, 149), arbitrary);
        let sync = SyncState::new(0);
        let now = 3 * 65_536 + 32_768; // center of our continuing null slot
        let mac = [2, 0, 0, 0, 0, 2];
        let mut peers = PeerTable::new();
        peers.touch(mac, 0);
        peers.get_mut(&mac).unwrap().sequence = [[149, 0x80]; 16];
        let effective = tx_sequence(&own, 149, 149);
        assert_eq!(
            schedule::data_send_decision(&mac, 149, &own, &peers, &sync, now),
            schedule::DataSendDecision::Hold
        );
        assert_eq!(
            schedule::data_send_decision(&mac, 149, &effective, &peers, &sync, now),
            schedule::DataSendDecision::Send
        );
        assert_eq!(
            schedule::data_send_decision(&mac, 0, &effective, &peers, &sync, now),
            schedule::DataSendDecision::Hold
        );
        // Destination null is still unavailable, even though our ROC is ready.
        peers.get_mut(&mac).unwrap().sequence[3] = [0, 0];
        assert_eq!(
            schedule::data_send_decision(&mac, 149, &effective, &peers, &sync, now),
            schedule::DataSendDecision::Hold
        );
        peers.get_mut(&mac).unwrap().sequence[3] = [149, 0x80];
        let guard = 3 * 65_536 + 1;
        assert_eq!(
            schedule::data_send_decision(&mac, 149, &effective, &peers, &sync, guard),
            schedule::DataSendDecision::Hold
        );
    }

    #[test]
    fn freshest_social_peer_restores_the_149_discovery_window() {
        use crate::peers::PeerTable;
        let infra = [2, 0, 0, 0, 0, 1];
        let social = [2, 0, 0, 0, 0, 2];
        let mut peers = PeerTable::new();
        for (mac, channel, seen) in [(infra, 52, 100), (social, 149, 200)] {
            peers.touch(mac, seen);
            let peer = peers.get_mut(&mac).unwrap();
            peer.sequence[0] = [channel, 0];
            peer.is_valid = true;
        }
        assert_eq!(sequence_for_peers(149, 52, peers.iter())[0][0], 149);
        peers.touch(social, 50);
        assert_eq!(sequence_for_peers(149, 52, peers.iter())[0][0], 52);
    }

    #[test]
    fn advertised_null_slots_match_home_gaps_across_counter_wrap() {
        let mut sync = crate::sync::SyncState::new(0);
        sync.aw_counter = 65520;
        let seq = sequence(149, 52);
        for now in (0..2_100_000).step_by(1000) {
            let slot = usize::from(sync.current_eaw(now) % 16);
            assert_eq!(
                remaining_slot_us(&seq, &sync, 149, now) > 0,
                seq[slot][0] == 149
            );
            assert!(remaining_slot_us(&seq, &sync, 149, now) <= 196_608);
        }
    }
    fn event(ready: bool) -> Event {
        Event::Roc {
            ifindex: 7,
            cookie: 256,
            frequency: 5745,
            ready,
        }
    }
    fn ack() -> Event {
        Event::Ack { seq: 10, error: 0 }
    }
    fn cookie() -> Event {
        Event::RocCookie {
            seq: 10,
            cookie: 256,
        }
    }
    fn reservation() -> Reservation {
        let mut r = Reservation::default();
        assert!(r.start(10, 7, 5745, 200, 0));
        r
    }
    #[test]
    fn ack_and_cookie_alone_never_enable_tx() {
        let mut r = reservation();
        r.event(&ack(), 1);
        r.event(&cookie(), 2);
        assert_eq!(r.active_frequency(3), None);
        assert!(r.finished(500_000));
    }
    #[test]
    fn ready_before_cookie_waits_for_both_cookie_and_ack() {
        let mut r = reservation();
        r.event(&event(true), 100);
        assert_eq!(r.active_frequency(100), None);
        r.event(&cookie(), 200);
        assert_eq!(r.active_frequency(200), None);
        r.event(&ack(), 300);
        assert_eq!(r.active_frequency(300), Some(5745));
        assert_eq!(r.active_frequency(190_100), None);
        assert!(r.finished(200_100));
    }
    #[test]
    fn wrong_cookie_frequency_interface_and_sequence_are_ignored() {
        let mut r = reservation();
        r.event(&ack(), 1);
        r.event(&cookie(), 2);
        for e in [
            Event::Roc {
                ifindex: 8,
                cookie: 256,
                frequency: 5745,
                ready: true,
            },
            Event::Roc {
                ifindex: 7,
                cookie: 257,
                frequency: 5745,
                ready: true,
            },
            Event::Roc {
                ifindex: 7,
                cookie: 256,
                frequency: 2437,
                ready: true,
            },
            Event::RocCookie {
                seq: 11,
                cookie: 257,
            },
        ] {
            r.event(&e, 10);
        }
        assert_eq!(r.active_frequency(11), None);
    }
    #[test]
    fn expired_or_rejected_cannot_be_revived_by_late_ready() {
        for failure in [
            event(false),
            Event::Ack {
                seq: 10,
                error: -22,
            },
        ] {
            let mut r = reservation();
            r.event(&ack(), 1);
            r.event(&cookie(), 2);
            r.event(&failure, 3);
            r.event(&event(true), 4);
            assert!(r.finished(5));
            assert_eq!(r.active_frequency(5), None);
        }
    }
    #[test]
    fn duplicate_ready_cannot_extend_and_only_one_bounded_social_roc_allowed() {
        let mut r = Reservation::default();
        for (freq, ms) in [(5260, 200), (5745, 0), (5745, 2001)] {
            assert!(!r.start(10, 7, freq, ms, 0));
        }
        assert!(r.start(10, 7, 5745, 200, 0));
        assert!(!r.start(11, 7, 2437, 200, 0));
        r.event(&ack(), 1);
        r.event(&cookie(), 2);
        r.event(&event(true), 3);
        r.event(&event(true), 150_000);
        assert!(r.finished(200_003));
    }
}
