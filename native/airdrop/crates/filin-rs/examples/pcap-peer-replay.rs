//! Offline replay of real radiotap pcap through peer, RX and pending-RX code.
//! Uses capture timestamps; does not simulate radio/OS scheduling or transmit.
//! Usage: cargo run -p filin-rs --example pcap-peer-replay -- CAPTURE LOCAL_MAC
use filin_rs::{data_rx, pending_rx::PendingRx, rx, state::AwdlState, sync::TsftBridge};
use std::{env, fs};
fn u32le(b: &[u8], p: usize) -> u32 {
    u32::from_le_bytes(b[p..p + 4].try_into().unwrap())
}
fn drain(q: &mut PendingRx, state: &AwdlState, now: u64) -> usize {
    let ready = q.ready(now, |s| state.peers.get(s).is_some_and(|p| p.is_valid));
    for p in &ready {
        println!(
            "release source={:02x?} delay_us={} frames={}",
            p.delivery.source,
            now.saturating_sub(p.received_us),
            p.delivery.frames.len()
        );
    }
    ready.len()
}
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = env::args().collect();
    if args.len() != 3 {
        return Err("expected CAPTURE LOCAL_MAC".into());
    }
    let local: [u8; 6] = args[2]
        .split(':')
        .map(|p| u8::from_str_radix(p, 16))
        .collect::<Result<Vec<_>, _>>()?
        .try_into()
        .map_err(|_| "six MAC octets required")?;
    let b = fs::read(&args[1])?;
    if b.len() < 24 || b[..4] != [0xd4, 0xc3, 0xb2, 0xa1] || u32le(&b, 20) != 127 {
        return Err("expected little-endian microsecond radiotap pcap".into());
    }
    let mut state = AwdlState::new(local, "replay".into(), 52, 0);
    state.constrain_radio_channel(52);
    let mut bridge = TsftBridge::default();
    let mut q = PendingRx::default();
    let (mut pos, mut queued, mut released, mut immediate) = (24, 0, 0, 0);
    let (mut capture_start, mut first_sync, mut sync_updates) = (None, None, 0);
    while pos < b.len() {
        if b.len() - pos < 16 {
            return Err("truncated record header".into());
        }
        let now = u64::from(u32le(&b, pos)) * 1_000_000 + u64::from(u32le(&b, pos + 4));
        capture_start.get_or_insert(now);
        let size = u32le(&b, pos + 8) as usize;
        pos += 16;
        if size > b.len() - pos {
            return Err("truncated packet".into());
        }
        let frame = &b[pos..pos + size];
        pos += size;
        state.clean_peers(now);
        released += drain(&mut q, &state, now);
        if let Ok(action) = rx::parse_action_frame(frame, now, &mut bridge) {
            if action.source != local {
                let before = state.sync.last_update_us;
                state.apply_action(
                    action.source,
                    action.action.subtype,
                    &action.tlvs,
                    action.rx_time_us,
                );
                if state.sync.last_update_us != before {
                    sync_updates += 1;
                    first_sync.get_or_insert(now - capture_start.unwrap());
                }
                released += drain(&mut q, &state, now);
            }
        } else if let Ok(delivery) = data_rx::decode(frame, local) {
            if state.peers.get(&delivery.source).is_some() {
                immediate += 1;
            } else if q.push(delivery, 52, now) {
                queued += 1;
            }
        }
    }
    println!("result queued={queued} released_after_action={released} immediately_deliverable={immediate}");
    println!("sync_updates={sync_updates} first_sync_delay_us={first_sync:?}");
    Ok(())
}
