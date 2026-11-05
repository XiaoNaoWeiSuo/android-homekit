//! Advertise a fake Companion Link peer over mDNS.
//!
//! Purpose: find out whether macOS 27's `rapportd` reacts to a third-party
//! `_companion-link._tcp` record (does it log the device, does it connect?).
//! The record points at an address that is *not* this host on purpose, so the
//! Mac sees a distinct device; run a listener on that address to capture what
//! the Mac sends.
//!
//! Usage: `companion_announce [interface] [ip] [port] [name] [seconds]`
//! Defaults: en0 10.12.1.61 61156 "Xiaomi 13" 60

use mdns_sd::{IfKind, ServiceDaemon, ServiceInfo};
use std::collections::HashMap;
use std::time::Duration;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let interface = args.first().cloned().unwrap_or_else(|| "en0".to_string());
    let ip: std::net::IpAddr = args
        .get(1)
        .cloned()
        .unwrap_or_else(|| "10.12.1.61".to_string())
        .parse()
        .expect("ip");
    let port: u16 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(61156);
    let name = args
        .get(3)
        .cloned()
        .unwrap_or_else(|| "Xiaomi 13".to_string());
    let seconds: u64 = args.get(4).and_then(|s| s.parse().ok()).unwrap_or(60);
    let rp_fl = args.get(5).cloned().unwrap_or_else(|| "0x4000".to_string());

    let daemon = ServiceDaemon::new().expect("mdns daemon");
    daemon.disable_interface(IfKind::All).ok();
    daemon
        .enable_interface(IfKind::Name(interface.clone()))
        .ok();

    // Mirrors the Mac's own record shape (rpVr/rpFl/rpAD/rpHI/rpHA/rpBA...),
    // with our values. Values are experimental: the point is discovery.
    let mut props = HashMap::new();
    for (key, value) in [
        ("rpMac", "0"),
        ("rpVr", "715.2"),
        ("rpFl", rp_fl.as_str()),
        ("rpAD", "1e5c687be580"),
        ("rpHI", "1e5c687be580"),
        ("rpHA", "83b04c565445"),
        ("rpBA", "1E:5C:68:7B:E5:80"),
        ("rpHN", "8d42f7a605c4"),
    ] {
        props.insert(key.to_string(), value.to_string());
    }

    let host = format!("{}.local.", name.replace(' ', "-"));
    let info = ServiceInfo::new(
        "_companion-link._tcp.local.",
        &name,
        &host,
        ip,
        port,
        Some(props),
    )
    .expect("service info");

    daemon.register(info.clone()).expect("register");
    println!(
        "announced {} -> {}:{} on {} for {}s",
        info.get_fullname(),
        ip,
        port,
        interface,
        seconds
    );
    std::thread::sleep(Duration::from_secs(seconds));
    let _ = daemon.unregister(info.get_fullname());
    println!("goodbye");
}
