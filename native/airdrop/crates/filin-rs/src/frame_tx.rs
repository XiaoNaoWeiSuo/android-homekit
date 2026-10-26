//! Portable encoding of NL80211_CMD_FRAME requests.
//! Constants match the pinned kernel UAPI and tools/awdl-mgmt-tx.c.
//! Request acknowledgement (NLMSG_ERROR) and wireless TX status are distinct.

const CMD_FRAME: u8 = 59;
const ATTR_IFINDEX: u16 = 3;
const ATTR_FREQ: u16 = 38;
const ATTR_FRAME: u16 = 51;
const ATTR_DONT_WAIT_FOR_ACK: u16 = 142;

fn attr(out: &mut Vec<u8>, kind: u16, value: &[u8]) {
    let length = 4 + value.len();
    out.extend_from_slice(&(length as u16).to_ne_bytes());
    out.extend_from_slice(&kind.to_ne_bytes());
    out.extend_from_slice(value);
    out.resize(out.len() + ((4 - length % 4) % 4), 0);
}

/// Encode a bare three-address MAC frame. No radiotap or radio side effects.
/// Group-addressed frames cannot be MAC-ACKed. For unicast leave the flag
/// absent so the driver can report OTA completion instead of only download.
/// A successful Netlink request still does not prove OTA receipt.
pub fn build_request(ifindex: u32, frequency_mhz: u32, frame: &[u8]) -> Option<Vec<u8>> {
    if ifindex == 0 || frequency_mhz == 0 || frame.len() < 24 || frame.len() > 2304 {
        return None;
    }
    let mut payload = vec![CMD_FRAME, 1, 0, 0];
    attr(&mut payload, ATTR_IFINDEX, &ifindex.to_ne_bytes());
    attr(&mut payload, ATTR_FREQ, &frequency_mhz.to_ne_bytes());
    attr(&mut payload, ATTR_FRAME, frame);
    if frame[4] & 1 != 0 {
        attr(&mut payload, ATTR_DONT_WAIT_FOR_ACK, &[]);
    }
    Some(payload)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn attrs(payload: &[u8]) -> Vec<(u16, &[u8])> {
        let mut rest = &payload[4..];
        let mut result = Vec::new();
        while !rest.is_empty() {
            let len = u16::from_ne_bytes(rest[..2].try_into().unwrap()) as usize;
            let kind = u16::from_ne_bytes(rest[2..4].try_into().unwrap());
            assert!(len >= 4 && len <= rest.len());
            result.push((kind, &rest[4..len]));
            rest = &rest[(len + 3) & !3..];
        }
        result
    }

    #[test]
    fn unicast_keeps_ack_enabled_and_frame_unmodified() {
        // Data frame to a unicast MAC, with an odd length to test NLA padding.
        let mut frame = vec![0; 41];
        frame[0] = 0x08;
        frame[4..10].copy_from_slice(&[0xc2, 0x6b, 0xbe, 0x4a, 0x48, 0xf0]);
        frame[40] = 0xa5;
        let request = build_request(35, 5260, &frame).unwrap();
        assert_eq!(&request[..4], &[59, 1, 0, 0]);
        let fields = attrs(&request);
        assert_eq!(fields, vec![
            (3, &35u32.to_ne_bytes()[..]), (38, &5260u32.to_ne_bytes()[..]), (51, &frame[..]),
        ]);
        assert!(!fields.iter().any(|(id, _)| *id == 142));
    }

    #[test]
    fn broadcast_and_multicast_opt_out_of_wireless_ack() {
        for addr in [[0xff; 6], [0x33, 0x33, 0, 0, 0, 0xfb]] {
            let mut frame = [0; 24];
            frame[4..10].copy_from_slice(&addr);
            let request = build_request(35, 5260, &frame).unwrap();
            assert_eq!(attrs(&request).last(), Some(&(142, &[][..])));
        }
    }

    #[test]
    fn malformed_request_is_not_encoded() {
        for length in [0, 4, 23, 2305, 65536] {
            assert!(build_request(35, 5260, &vec![0; length]).is_none());
        }
        assert!(build_request(0, 5260, &[0; 24]).is_none());
        assert!(build_request(35, 0, &[0; 24]).is_none());
    }
}
