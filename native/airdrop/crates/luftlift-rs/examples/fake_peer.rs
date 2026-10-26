//! Synthetic AirDrop peer, for testing "nearby devices" discovery.
//!
//! Announces a `_airdrop._tcp` service on the given interface under a *different*
//! instance name than the receiver's, then re-announces every 10 s until it is
//! killed. It serves nothing and accepts nothing — its only purpose is to give a
//! receiver's browse something to resolve when no Apple device is at hand.
//!
//! This is the only way to test the discovery path deterministically: Apple
//! devices announce only while their share pane is open, so "no peers found" is
//! ambiguous in the field (is discovery broken, or is nobody announcing?).
//!
//! Usage: `fake_peer [interface] [name] [seconds]`
//!
//! It coexists with a live receiver because mdns-sd sets SO_REUSEADDR /
//! SO_REUSEPORT on its UDP 5353 socket (verified on the phone: `find -d 3` runs
//! beside a resident receiver and sees its announcement).

use luftlift_rs::mdns::{self, MdnsConfig, DEFAULT_FLAGS};
use luftlift_rs::netutil;
use std::sync::atomic::Ordering;
use std::time::Duration;

fn main() -> anyhow::Result<()> {
    let mut args = std::env::args().skip(1);
    let interface = args.next().unwrap_or_else(|| "awdl0".to_string());
    let name = args.next().unwrap_or_else(|| "fakemac".to_string());
    let secs: u64 = args.next().and_then(|s| s.parse().ok()).unwrap_or(120);

    // Announce the interface's real address: mdns-sd publishes this in the AAAA
    // record, and an address that is not actually assigned to the interface
    // would produce a peer that no sender could ever reach.
    let addr = netutil::get_ipv6_for_interface(&interface)
        .ok_or_else(|| anyhow::anyhow!("interface {interface} has no IPv6 address"))?;

    let cfg = MdnsConfig { computer_name: name, port: 8771, flags: DEFAULT_FLAGS };
    let (daemon, info) = mdns::announce(&interface, &cfg, addr.into())?;
    let daemon = std::sync::Arc::new(daemon);
    let (stop, handle) = mdns::spawn_reannouncer(daemon.clone(), info.clone());

    println!(
        "fake peer {} -> {} on {} for {}s",
        info.get_fullname(),
        addr,
        interface,
        secs
    );

    std::thread::sleep(Duration::from_secs(secs));
    stop.store(true, Ordering::Release);
    let _ = handle.join();
    let _ = mdns::goodbye(&daemon, info.get_fullname());
    println!("fake peer gone");
    Ok(())
}
