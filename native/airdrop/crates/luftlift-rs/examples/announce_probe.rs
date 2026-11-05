//! Device-side experiment: announce `_airdrop._tcp` on the Wi-Fi interface.
//!
//! macOS 27 no longer keeps an AirDrop receiver listening on 8770; the listener
//! appears only while the Finder AirDrop pane is open. One open question is
//! whether the Mac opens it in response to a *sender* advertisement on the LAN
//! (the legacy behaviour) or only from its own UI. This tool answers that by
//! advertising the phone as a legacy AirDrop peer on wlan0 and doing nothing
//! else — it opens no listener and never sends a file.
//!
//! Usage: `announce_probe [interface] [seconds]`

use luftlift_rs::mdns::{self, MdnsConfig, DEFAULT_FLAGS};
use luftlift_rs::netutil;
use std::time::Duration;

fn main() {
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_ansi(false)
        .init();

    let mut args = std::env::args().skip(1);
    let interface = args.next().unwrap_or_else(|| "wlan0".to_string());
    let seconds: u64 = args.next().and_then(|s| s.parse().ok()).unwrap_or(40);

    let Some((addr, _mask)) = netutil::get_ipv4_for_interface(&interface) else {
        eprintln!("{interface} has no IPv4 address");
        std::process::exit(1);
    };

    let config = MdnsConfig {
        computer_name: "luftlift-probe".to_string(),
        // Deliberately a port we do not actually serve: this experiment only
        // tests whether the Mac *reacts* to the advertisement.
        port: 8771,
        flags: DEFAULT_FLAGS,
        pin_info: None,
    };

    let (daemon, info) = mdns::announce(&interface, &config, addr.into()).expect("announce");
    println!(
        "announced {} at {}:{} on {interface} for {} s",
        info.get_fullname(),
        addr,
        config.port,
        seconds
    );
    std::thread::sleep(Duration::from_secs(seconds));
    let _ = mdns::goodbye(&daemon, info.get_fullname());
}
