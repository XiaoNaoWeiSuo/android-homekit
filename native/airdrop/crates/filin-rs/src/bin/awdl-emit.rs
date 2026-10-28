//! `awdl-emit` — emit a timed plan of bare 802.11 AWDL frames.
//!
//! This is the bridge between filin's AWDL wire format and an out-of-tree
//! injector. It exists because the frame layout must have exactly one source of
//! truth: `filin_rs::runtime::build_announce_80211` (owl's PSF/MIF TLV set,
//! unit-tested in `lib.rs`). Hand-building AWDL frames in a separate C tool
//! already produced two real bugs — `addr3` set to the peer MAC instead of the
//! AWDL BSSID, and the AWDL data head written as the sequence number instead of
//! `0x0403` — so the injector now takes bytes from here and nothing else.
//!
//! Output is a replay plan on stdout, one frame per line:
//!
//! ```text
//! # offset_us kind len hex
//! 0 PSF 154 0000d0...
//! 32768 MIF 172 0000d0...
//! 112640 PSF 154 0000d0...
//! ```
//!
//! `offset_us` is relative to the first frame, so the whole plan shares ONE
//! availability-window epoch. That matters: `sync::SyncState` measures the AW
//! phase from `last_update_us`, so a plan assembled from separate process
//! invocations would advertise a phase that resets on every frame and no peer
//! could ever sync to it.
//!
//! # Cross-run epoch (`--epoch-us`)
//!
//! One plan is not enough either. Apple peer discovery is built on persistence:
//! a neighbour is only recorded after it has been seen across *many*
//! availability windows, and a resident service must therefore transmit burst
//! after burst. Every frame carries its own timestamp (`phy_tx`, `target_tx`)
//! and an availability-window counter (`aw_counter`), and a peer that sees
//! those jump backwards concludes the sender re-clustered and drops it.
//!
//! With the epoch pinned at 0 — the default, and what every earlier experiment
//! did — each burst restarts the counter from zero, so the advertised phase
//! resets roughly every second and no peer can hold sync. That is the blocker
//! recorded in ROADMAP §14.5.
//!
//! `--epoch-us` fixes it by stamping the plan from a caller-supplied absolute
//! clock (CLOCK_MONOTONIC microseconds, the same clock the receiver compares
//! against) instead of from zero. Two runs given the same epoch origin describe
//! one continuous schedule: burst *n* and burst *n+1* agree on where the
//! availability windows fall.
//!
//! ```text
//! # the shell reads the clock once, then every burst reuses that origin
//! origin=$(( $(date +%s%N) / 1000 ))
//! awdl-emit --epoch-us $origin ...        # frame contents stamped from origin
//! ```
//!
//! `--lead-us` is the matching knob on the *offset* side: how long after the
//! replayer starts the first frame should go out. The replayer walks `offset_us`
//! from its own start, so a plan whose contents say `epoch` while its offsets
//! say `0` would fire early by exactly the process-startup time. The harness
//! picks `--lead-us` to cover that, keeping `epoch + offset` true on the air.
//!
//! No radiotap. `NL80211_CMD_FRAME` takes a MAC frame; the driver and target
//! build the PHY header themselves.

use std::process::ExitCode;

use filin_rs::awdl::{self, ActionSubtype};
use filin_rs::ieee80211;
use filin_rs::runtime;
use filin_rs::state::AwdlState;

/// owl `PSF_INTERVAL_MASTER_TU` (core.c): 110 TU between announces.
const PSF_INTERVAL_TU: u64 = 110;
const TU_US: u64 = 1024;
/// `SyncState::new` defaults: presence_mode 4 × aw_period 16 TU.
const EAW_US: u64 = 4 * 16 * TU_US;

struct Args {
    mac: [u8; 6],
    name: String,
    channel: u16,
    dst: Option<[u8; 6]>,
    announces: u32,
    with_mif: bool,
    data_probes: u32,
    /// Offset of the first probe and the spacing between probes. Independent of
    /// the announce cadence on purpose — probe density is what decides whether
    /// a probe lands inside the peer's availability window.
    data_start_us: u64,
    data_interval_us: u64,
    /// Ethernet payload carried by every probe, built once so all probes are
    /// byte-identical apart from the AWDL sequence number.
    probe_ethernet: Vec<u8>,
    /// Absolute CLOCK_MONOTONIC microseconds at which the first frame will be
    /// transmitted. Frame contents are stamped from this, so consecutive runs
    /// that pass the same origin share one availability-window schedule.
    /// `None` keeps the historical behaviour (origin 0, phase resets per run).
    epoch_us: Option<u64>,
    /// Stamp the plan from the current CLOCK_MONOTONIC instead of a supplied
    /// origin. Equivalent to `--epoch-us (now + lead)`, but reads the clock
    /// in-process so it is precise and immune to shell/REALTIME skew.
    epoch_now: bool,
    /// Microseconds the replayer waits before its first frame. Emitted offsets
    /// are shifted by this so the plan can absorb process startup.
    lead_us: u64,
}

fn usage() -> ExitCode {
    eprintln!(
        "usage: awdl-emit --mac AA:BB:CC:DD:EE:FF [--name NAME] [--channel 6|44|149]\n\
         \x20                 [--dst PEER_MAC] [--announces N] [--mif] [--data N]\n\
         \x20                 [--epoch-us ABS | --epoch-now] [--lead-us US]\n\
         \n\
         Emits a replay plan of bare 802.11 AWDL frames on stdout.\n\
         \x20 --dst        also emit N unicast data probes addressed to PEER_MAC\n\
         \x20 --announces  number of PSF announces (default 8)\n\
         \x20 --mif        interleave MIF announces (carries the ARPA/device name)\n\
         \x20 --data-start-ms / --data-interval-ms  probe cadence (default 5 / 50)\n\
         \x20 --epoch-us   absolute CLOCK_MONOTONIC us of the first frame; makes\n\
         \x20              consecutive bursts share one availability-window phase\n\
         \x20              instead of restarting it. Required for peer discovery.\n\
         \x20 --epoch-now  stamp from the current CLOCK_MONOTONIC (reads the clock\n\
         \x20              in-process; use this from a resident loop)\n\
         \x20 --lead-us    delay before the first frame, added to every offset so\n\
         \x20              the plan can absorb process startup (default 0)\n"
    );
    ExitCode::from(2)
}

fn parse_mac(s: &str) -> Option<[u8; 6]> {
    let mut out = [0u8; 6];
    let mut parts = s.split(':');
    for slot in out.iter_mut() {
        *slot = u8::from_str_radix(parts.next()?, 16).ok()?;
    }
    if parts.next().is_some() {
        return None;
    }
    Some(out)
}

fn parse_args() -> Result<Args, ExitCode> {
    let mut mac = None;
    let mut name = String::from("marionette");
    let mut channel = 6u16;
    let mut dst = None;
    let mut announces = 8u32;
    let mut with_mif = false;
    let mut data_probes = 0u32;
    let mut data_start_us = 5_000u64;
    let mut data_interval_us = 50_000u64;
    let mut epoch_us = None;
    let mut epoch_now = false;
    let mut lead_us = 0u64;

    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "--mac" => {
                let v = it.next().ok_or_else(usage)?;
                mac = Some(parse_mac(&v).ok_or_else(usage)?);
            }
            "--name" => name = it.next().ok_or_else(usage)?,
            "--channel" => {
                let v = it.next().ok_or_else(usage)?;
                channel = v.parse().map_err(|_| usage())?;
                if !matches!(channel, 6 | 44 | 149) {
                    eprintln!("awdl-emit: channel must be one of 6, 44, 149");
                    return Err(ExitCode::from(2));
                }
            }
            "--dst" => {
                let v = it.next().ok_or_else(usage)?;
                dst = Some(parse_mac(&v).ok_or_else(usage)?);
            }
            "--announces" => {
                let v = it.next().ok_or_else(usage)?;
                announces = v.parse().map_err(|_| usage())?;
            }
            "--mif" => with_mif = true,
            "--data-start-ms" => {
                let v = it.next().ok_or_else(usage)?;
                let ms: u64 = v.parse().map_err(|_| usage())?;
                data_start_us = ms * 1000;
            }
            "--data-interval-ms" => {
                let v = it.next().ok_or_else(usage)?;
                let ms: u64 = v.parse().map_err(|_| usage())?;
                if ms == 0 {
                    eprintln!("awdl-emit: --data-interval-ms must be > 0");
                    return Err(ExitCode::from(2));
                }
                data_interval_us = ms * 1000;
            }
            "--data" => {
                let v = it.next().ok_or_else(usage)?;
                data_probes = v.parse().map_err(|_| usage())?;
            }
            "--epoch-us" => {
                let v = it.next().ok_or_else(usage)?;
                epoch_us = Some(v.parse().map_err(|_| usage())?);
            }
            "--epoch-now" => epoch_now = true,
            "--lead-us" => {
                let v = it.next().ok_or_else(usage)?;
                lead_us = v.parse().map_err(|_| usage())?;
            }
            "-h" | "--help" => return Err(usage()),
            other => {
                eprintln!("awdl-emit: unknown argument {other}");
                return Err(usage());
            }
        }
    }

    let Some(mac) = mac else {
        eprintln!("awdl-emit: --mac is required (the AWDL address to transmit as)");
        return Err(usage());
    };
    if data_probes > 0 && dst.is_none() {
        eprintln!("awdl-emit: --data requires --dst");
        return Err(ExitCode::from(2));
    }
    if epoch_now && epoch_us.is_some() {
        eprintln!("awdl-emit: --epoch-now and --epoch-us are mutually exclusive");
        return Err(ExitCode::from(2));
    }

    let probe_ethernet = match dst {
        Some(d) => data_probe_ethernet(d, mac),
        None => Vec::new(),
    };

    Ok(Args {
        mac,
        name,
        channel,
        dst,
        announces,
        with_mif,
        data_probes,
        data_start_us,
        data_interval_us,
        probe_ethernet,
        epoch_us,
        epoch_now,
        lead_us,
    })
}

fn hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

/// A minimal, structurally valid AWDL data payload (IPv6/UDP).
///
/// The probe only needs to be a well-formed unicast data frame: the peer's ACK
/// is generated by the MAC layer before the payload is looked at, so the point
/// of the frame is the addressing and the fact that it is unicast.
fn data_probe_ethernet(dst: [u8; 6], src: [u8; 6]) -> Vec<u8> {
    let mut eth = Vec::with_capacity(14 + 48);
    eth.extend_from_slice(&dst);
    eth.extend_from_slice(&src);
    eth.extend_from_slice(&[0x86, 0xdd]); // IPv6

    let mut ip = [0u8; 48];
    ip[0] = 0x60; // version 6
    ip[4..6].copy_from_slice(&8u16.to_be_bytes()); // payload length (UDP)
    ip[6] = 17; // next header: UDP
    ip[7] = 64; // hop limit
    ip[8] = 0xfe; // src: link-local
    ip[9] = 0x80;
    ip[24] = 0xff; // dst: ff02::1 (all-nodes)
    ip[25] = 0x02;
    ip[39] = 1;
    ip[40..42].copy_from_slice(&5353u16.to_be_bytes());
    ip[42..44].copy_from_slice(&5353u16.to_be_bytes());
    ip[44..46].copy_from_slice(&8u16.to_be_bytes());
    eth.extend_from_slice(&ip);
    eth
}

/// Absolute time the plan's frame contents are stamped from.
///
/// `--epoch-now` reads the clock so a resident loop gets one continuous
/// schedule across bursts; otherwise the caller supplies the origin, and the
/// default of 0 reproduces the historical per-run phase reset.
fn resolve_epoch(epoch_now: bool, epoch_us: Option<u64>, lead_us: u64, now_us: u64) -> u64 {
    if epoch_now {
        // The first frame is due `lead_us` from now, which is exactly where the
        // replayer's first offset lands if it starts immediately.
        now_us + lead_us
    } else {
        epoch_us.unwrap_or(0)
    }
}

/// Build the replay plan as `(offset_us, "<kind> <len> <hex>")`, unsorted.
///
/// `base_us` is the absolute time of the first frame; every offset is shifted
/// by `args.lead_us` so the replayer can absorb process startup while the
/// contents still describe `base_us + rel`.
fn build_plan(args: &Args, base_us: u64) -> Result<Vec<(u64, String)>, String> {
    let lead_us = args.lead_us;
    let mut state = AwdlState::new(args.mac, args.name.clone(), args.channel, base_us);
    // `AwdlState::new` starts a fresh local clock with AW zero.  That is right
    // for a live daemon which immediately owns the clock, but a replay plan is
    // explicitly stamped in an absolute epoch.  Seed the local AW counter
    // from that epoch so two separately generated bursts describe the same
    // schedule instead of both advertising AW zero.
    state.sync.aw_counter = ((base_us / (16 * TU_US)) & 0xffff) as u16;
    // `--name` is the name a peer shows in its AirDrop panel, so it drives the
    // display name and the mDNS hostname together — Apple keeps the hostname
    // lower case and the display name as typed. The service UUID, session id
    // and capability string stay at their stable defaults.
    state.service_display = args.name.clone();
    state.service_host = args.name.to_lowercase();

    // Announces and probes run on independent cadences: the announces keep the
    // AWDL state machine ticking (one PSF per 110 TU), while the probes are a
    // density knob. A probe is only ACKed if the peer's radio happens to be
    // awake on this channel, and a MAC-layer ACK needs no peer-table entry, so
    // hitting the peer is purely a question of how many probes land inside its
    // availability windows.
    let mut plan: Vec<(u64, String)> = Vec::new();
    let mut seq: u16 = 0;

    for i in 0..args.announces {
        // `rel` is time since the first frame — it is what the frame contents
        // are stamped with. The plan offset adds the startup lead, so the
        // replayer fires each frame at `epoch + rel` on the wall clock.
        let psf_rel = u64::from(i) * PSF_INTERVAL_TU * TU_US;
        let psf = runtime::build_announce_80211(
            ActionSubtype::Psf,
            &state,
            (seq & 0x0fff) << 4,
            base_us + psf_rel,
        );
        seq = seq.wrapping_add(1);
        plan.push((lead_us + psf_rel, format!("PSF {} {}", psf.len(), hex(&psf))));

        if args.with_mif {
            // owl `awdl_send_mif` fires in the middle of the EAW.
            let mif_rel = psf_rel + EAW_US / 2;
            let mif = runtime::build_announce_80211(
                ActionSubtype::Mif,
                &state,
                (seq & 0x0fff) << 4,
                base_us + mif_rel,
            );
            seq = seq.wrapping_add(1);
            plan.push((lead_us + mif_rel, format!("MIF {} {}", mif.len(), hex(&mif))));
        }
    }

    if let Some(dst) = args.dst {
        let body = awdl::encapsulate_ethernet_payload(&args.probe_ethernet, state.tx_seq)
            .map_err(|err| format!("failed to encapsulate data probe: {err:?}"))?;
        for i in 0..args.data_probes {
            let rel = args.data_start_us + u64::from(i) * args.data_interval_us;
            let frame = ieee80211::build_data_frame(
                dst,
                args.mac,
                runtime::AWDL_BSSID,
                (seq & 0x0fff) << 4,
                &body,
            );
            seq = seq.wrapping_add(1);
            plan.push((lead_us + rel, format!("DATA {} {}", frame.len(), hex(&frame))));
        }
    }

    Ok(plan)
}

fn main() -> ExitCode {
    let args = match parse_args() {
        Ok(a) => a,
        Err(code) => return code,
    };

    let base_us = resolve_epoch(
        args.epoch_now,
        args.epoch_us,
        args.lead_us,
        runtime::host_time_us(),
    );

    println!(
        "# awdl-emit mac={} name={} channel={} epoch_us={} lead_us={}",
        hex(&args.mac),
        args.name,
        args.channel,
        base_us,
        args.lead_us
    );
    println!("# offset_us kind len hex");

    let mut plan = match build_plan(&args, base_us) {
        Ok(plan) => plan,
        Err(err) => {
            eprintln!("awdl-emit: {err}");
            return ExitCode::FAILURE;
        }
    };

    // The replayer walks the plan front to back and sleeps to each offset, so
    // it must be chronological even though the two cadences are independent.
    plan.sort_by_key(|(at, _)| *at);
    let emitted = plan.len();
    for (at, line) in plan {
        println!("{at} {line}");
    }

    eprintln!("awdl-emit: {emitted} frame(s)");
    ExitCode::SUCCESS
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args_with(epoch_us: Option<u64>, lead_us: u64) -> Args {
        Args {
            mac: [0x02, 0x11, 0x22, 0x33, 0x44, 0x55],
            name: "marionette".into(),
            channel: 6,
            dst: None,
            announces: 3,
            with_mif: true,
            data_probes: 0,
            data_start_us: 5_000,
            data_interval_us: 50_000,
            probe_ethernet: Vec::new(),
            epoch_us,
            epoch_now: false,
            lead_us,
        }
    }

    /// Decode the hex payload of a plan line ("PSF 352 <hex>").
    fn frame_of(line: &str) -> Vec<u8> {
        let hex_part = line.split(' ').nth(2).expect("kind len hex");
        (0..hex_part.len() / 2)
            .map(|i| u8::from_str_radix(&hex_part[i * 2..i * 2 + 2], 16).expect("hex"))
            .collect()
    }

    /// phy_tx is the u32 at body offset 32 (action header is 24 + 16).
    fn phy_tx(frame: &[u8]) -> u32 {
        u32::from_le_bytes([frame[32], frame[33], frame[34], frame[35]])
    }

    /// The AW counter lives in TLV 4, which is first: kind at 40, len at
    /// 41..43, value at 43, and value offset 29..31 holds the counter.  The
    /// value at offset 1..3 is the countdown to the next EAW, not the AW
    /// sequence number (confusing those two made this regression test pass on
    /// a constant countdown while missing a broken schedule).
    fn aw_counter(frame: &[u8]) -> u16 {
        assert_eq!(frame[40], 4, "TLV 4 must lead the PSF chain");
        let value_start = 43;
        u16::from_le_bytes([frame[value_start + 29], frame[value_start + 30]])
    }

    fn first_psf(plan: &[(u64, String)]) -> &str {
        plan.iter()
            .find(|(_, line)| line.starts_with("PSF "))
            .map(|(_, line)| line.as_str())
            .expect("a PSF in every plan")
    }

    #[test]
    fn epoch_now_stamps_from_the_clock_plus_the_lead() {
        assert_eq!(resolve_epoch(true, None, 400_000, 1_700_000_000), 1_700_400_000);
    }

    #[test]
    fn epoch_defaults_to_zero_for_the_legacy_behaviour() {
        // No --epoch-us and no --epoch-now is exactly what every pre-fix run
        // did, and it is what reset the advertised phase every burst.
        assert_eq!(resolve_epoch(false, None, 0, 1_700_000_000), 0);
    }

    #[test]
    fn an_explicit_origin_is_used_verbatim() {
        assert_eq!(resolve_epoch(false, Some(42_000), 0, 1_700_000_000), 42_000);
    }

    #[test]
    fn plan_offsets_start_at_the_lead() {
        let args = args_with(Some(1_000_000), 400_000);
        let plan = build_plan(&args, 1_000_000).expect("plan builds");
        let first = plan.iter().map(|(at, _)| *at).min().expect("non-empty");
        assert_eq!(first, 400_000, "the replayer waits the lead before frame 0");
    }

    /// The regression this whole change exists for: two bursts must not
    /// advertise the same availability-window counter.
    #[test]
    fn consecutive_bursts_advance_the_aw_counter() {
        let args = args_with(None, 0);
        let first = build_plan(&args, 1_700_000_000).expect("plan builds");
        let second = build_plan(&args, 1_700_000_000 + 1_200_000).expect("plan builds");

        let a = frame_of(first_psf(&first));
        let b = frame_of(first_psf(&second));

        assert_ne!(
            phy_tx(&a),
            phy_tx(&b),
            "each burst must carry its own timestamp"
        );
        assert_ne!(
            aw_counter(&a),
            aw_counter(&b),
            "the advertised AW counter must advance across bursts, not restart"
        );
        // 1.2 s is ~73 availability windows of 16 TU.
        let delta = aw_counter(&b).wrapping_sub(aw_counter(&a));
        assert!(
            (70..=76).contains(&delta),
            "expected ~73 windows of advance, got {delta}"
        );
    }

    #[test]
    fn a_plan_still_carries_mif_and_psf_when_asked() {
        let args = args_with(Some(1_000_000), 0);
        let plan = build_plan(&args, 1_000_000).expect("plan builds");
        let kinds: Vec<&str> = plan
            .iter()
            .map(|(_, line)| line.split(' ').next().unwrap())
            .collect();
        assert!(kinds.contains(&"PSF"));
        assert!(kinds.contains(&"MIF"));
    }
}
