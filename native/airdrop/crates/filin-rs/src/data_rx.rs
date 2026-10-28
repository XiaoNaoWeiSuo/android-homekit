//! Admission of monitor RX frames before exposing them to the host TAP.
use crate::{awdl, ieee80211, radiotap, rx};

const BSSID: [u8; 6] = [0, 0x25, 0, 0xff, 0x94, 0x73];

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    Radiotap(radiotap::RadiotapError),
    Fcs(rx::ParseError),
    Header(ieee80211::ParseError),
    NotAwdlData,
    UnsupportedLayout,
    NotForHost,
    Payload(awdl::ParseError),
}

pub struct Delivery {
    pub source: [u8; 6],
    pub frames: Vec<awdl::EthernetFrame>,
    /// Only actual unicast delivery pins the radio for a transfer.
    pub unicast: bool,
}

fn for_host(destination: &[u8], local: [u8; 6]) -> bool {
    destination == local || destination[0] & 1 != 0
}

/// Reject unrelated BSS traffic, local reflections and overheard unicast.
/// A-MSDU subframes have their own destination addresses and must also pass.
/// Fragmentation, protected frames and four-address/HT-control layouts need
/// explicit support; never treat their header bytes as an AWDL payload.
pub fn decode(frame: &[u8], local: [u8; 6]) -> Result<Delivery, Error> {
    let radiotap = radiotap::parse_header(frame).map_err(Error::Radiotap)?;
    let payload = rx::ieee80211_payload_without_fcs(&radiotap).map_err(Error::Fcs)?;
    let mac = ieee80211::parse_mac_header(payload).map_err(Error::Header)?;
    // Only DATA and QOS_DATA; null-data/control/management have no AWDL body.
    if !matches!(mac.frame_control & 0x00ff, 0x08 | 0x88) || mac.bssid != BSSID {
        return Err(Error::NotAwdlData);
    }
    if mac.frame_control & (0x0300 | 0x0400 | 0x4000 | 0x8000) != 0
        || mac.sequence_control & 0xf != 0
    {
        return Err(Error::UnsupportedLayout);
    }
    if mac.source == local || !for_host(&mac.destination, local) {
        return Err(Error::NotForHost);
    }
    let mut frames = if mac
        .qos_control
        .is_some_and(|q| q & ieee80211::QOS_A_MSDU_PRESENT != 0)
    {
        awdl::decapsulate_amsdu_data_frames(mac.body).map_err(Error::Payload)?
    } else {
        vec![
            awdl::decapsulate_data_frame(mac.body, mac.source, mac.destination)
                .map_err(Error::Payload)?,
        ]
    };
    frames.retain(|frame| {
        frame.bytes.len() >= 14 && frame.bytes[6..12] != local && for_host(&frame.bytes[..6], local)
    });
    let unicast = frames.iter().any(|frame| frame.bytes[..6] == local);
    Ok(Delivery {
        source: mac.source,
        frames,
        unicast,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    const LOCAL: [u8; 6] = [2, 0, 0, 0, 0, 1];
    const PEER: [u8; 6] = [2, 0, 0, 0, 0, 2];
    const OTHER: [u8; 6] = [2, 0, 0, 0, 0, 3];
    const MULTICAST: [u8; 6] = [0x33, 0x33, 0, 0, 0, 0xfb];

    fn ethernet(dst: [u8; 6]) -> Vec<u8> {
        let mut b = dst.to_vec();
        b.extend(PEER);
        b.extend([0x86, 0xdd, 0x60, 0, 0, 0]);
        b
    }
    fn radio(dst: [u8; 6]) -> Vec<u8> {
        let body = awdl::encapsulate_ethernet_payload(&ethernet(dst), 1).unwrap();
        let mut b = vec![0, 0, 8, 0, 0, 0, 0, 0];
        b.extend(ieee80211::build_data_frame(dst, PEER, BSSID, 0x10, &body));
        b
    }
    #[test]
    fn own_unicast_delivered_but_multicast_does_not_pin_transfer() {
        let unicast = decode(&radio(LOCAL), LOCAL).unwrap();
        assert_eq!(unicast.source, PEER);
        assert!(unicast.unicast);
        assert_eq!(unicast.frames[0].bytes, ethernet(LOCAL));
        let multicast = decode(&radio(MULTICAST), LOCAL).unwrap();
        assert!(!multicast.unicast);
        assert_eq!(multicast.frames[0].bytes, ethernet(MULTICAST));
    }
    #[test]
    fn overheard_unicast_and_self_reflection_do_not_reach_tap() {
        assert!(matches!(
            decode(&radio(OTHER), LOCAL),
            Err(Error::NotForHost)
        ));
        let mut reflection = radio(MULTICAST);
        reflection[18..24].copy_from_slice(&LOCAL);
        assert!(matches!(decode(&reflection, LOCAL), Err(Error::NotForHost)));
    }
    #[test]
    fn wrong_bssid_type_oui_and_unsupported_headers_are_rejected() {
        let original = radio(LOCAL);
        for (offset, value) in [
            (24, 0x42),
            (8, 0xd0),
            (9, 1),
            (9, 4),
            (9, 0x40),
            (9, 0x80),
            (30, 0x11),
            (35, 0x42),
        ] {
            let mut bad = original.clone();
            bad[offset] = value;
            assert!(decode(&bad, LOCAL).is_err(), "mutation at {offset}");
        }
        for end in 0..48 {
            assert!(
                decode(&original[..end], LOCAL).is_err(),
                "truncation at {end}"
            );
        }
    }
    #[test]
    fn qos_data_fcs_and_retry_keep_original_host_payload() {
        let mut qos = radio(LOCAL);
        qos[8] = 0x88;
        qos[9] = 0x08; // Retry is supported; no new header fields.
        qos.splice(32..32, [0x05, 0x00]); // QoS TID 5, no A-MSDU
        assert_eq!(
            decode(&qos, LOCAL).unwrap().frames[0].bytes,
            ethernet(LOCAL)
        );

        let mut fcs = vec![0, 0, 9, 0, 2, 0, 0, 0, 0x10];
        fcs.extend(&qos[8..]);
        fcs.extend([0xde, 0xad, 0xbe, 0xef]);
        assert_eq!(
            decode(&fcs, LOCAL).unwrap().frames[0].bytes,
            ethernet(LOCAL)
        );
        fcs[8] |= 0x40;
        assert!(matches!(
            decode(&fcs, LOCAL),
            Err(Error::Fcs(rx::ParseError::BadFcs))
        ));
    }

    #[test]
    fn amsdu_filters_each_subframe_before_delivery_and_pinning() {
        let mut aggregate = Vec::new();
        for (i, dst) in [OTHER, MULTICAST, LOCAL].into_iter().enumerate() {
            let body = awdl::encapsulate_ethernet_payload(&ethernet(dst), i as u16).unwrap();
            aggregate.extend(dst);
            aggregate.extend(PEER);
            aggregate.extend((body.len() as u16).to_be_bytes());
            aggregate.extend(&body);
            if i < 2 {
                aggregate.resize((aggregate.len() + 3) & !3, 0);
            }
        }
        let mut frame = vec![0, 0, 8, 0, 0, 0, 0, 0];
        let mut mac = ieee80211::build_data_frame(LOCAL, PEER, BSSID, 0x20, &[]);
        mac[0] = 0x88;
        mac.extend(ieee80211::QOS_A_MSDU_PRESENT.to_le_bytes());
        mac.extend(aggregate);
        frame.extend(mac);
        let decoded = decode(&frame, LOCAL).unwrap();
        assert_eq!(decoded.frames.len(), 2);
        assert_eq!(decoded.frames[0].bytes, ethernet(MULTICAST));
        assert_eq!(decoded.frames[1].bytes, ethernet(LOCAL));
        assert!(decoded.unicast);
    }

    #[test]
    fn truncated_amsdu_never_delivers_a_partial_ip_packet() {
        let body = awdl::encapsulate_ethernet_payload(&ethernet(LOCAL), 3).unwrap();
        let mut frame = vec![0, 0, 8, 0, 0, 0, 0, 0];
        let mut mac = ieee80211::build_data_frame(LOCAL, PEER, BSSID, 0x30, &[]);
        mac[0] = 0x88;
        mac.extend(ieee80211::QOS_A_MSDU_PRESENT.to_le_bytes());
        mac.extend(LOCAL);
        mac.extend(PEER);
        mac.extend(((body.len() + 1400) as u16).to_be_bytes());
        mac.extend(body);
        frame.extend(mac);
        // The cut subframe is dropped rather than delivered half-formed. It is
        // no longer an error, because the same truncation on a *multi*-subframe
        // aggregate must still hand over the complete ones (next test).
        let decoded = decode(&frame, LOCAL).unwrap();
        assert!(decoded.frames.is_empty(), "cut subframe must not be delivered");
        assert!(!decoded.unicast);
    }

    /// A real Mac aggregates several MSDUs into one MPDU and the monitor path
    /// truncates the tail. The complete subframes must still reach the host,
    /// otherwise TCP never ACKs and the sender stalls forever.
    #[test]
    fn truncated_amsdu_still_delivers_the_complete_prefix() {
        let mut aggregate = Vec::new();
        for (i, dst) in [MULTICAST, LOCAL].into_iter().enumerate() {
            let body = awdl::encapsulate_ethernet_payload(&ethernet(dst), i as u16).unwrap();
            aggregate.extend(dst);
            aggregate.extend(PEER);
            aggregate.extend((body.len() as u16).to_be_bytes());
            aggregate.extend(&body);
            aggregate.resize((aggregate.len() + 3) & !3, 0);
        }
        // Append a third subframe that is cut in the middle, the way the
        // single-buffer cap truncates a live aggregate.
        aggregate.extend(LOCAL);
        aggregate.extend(PEER);
        aggregate.extend(((1448) as u16).to_be_bytes());
        aggregate.extend([0u8; 40]);

        let mut frame = vec![0, 0, 8, 0, 0, 0, 0, 0];
        let mut mac = ieee80211::build_data_frame(LOCAL, PEER, BSSID, 0x40, &[]);
        mac[0] = 0x88;
        mac.extend(ieee80211::QOS_A_MSDU_PRESENT.to_le_bytes());
        mac.extend(aggregate);
        frame.extend(mac);

        let decoded = decode(&frame, LOCAL).unwrap();
        assert_eq!(decoded.frames.len(), 2, "complete prefix must survive");
        assert_eq!(decoded.frames[1].bytes, ethernet(LOCAL));
        assert!(decoded.unicast);
    }
}
