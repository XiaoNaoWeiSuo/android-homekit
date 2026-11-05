//! Device-side probe: browse mDNS **on awdl0**.
//!
//! macOS 27's AirDrop receiver is only reachable over AWDL (verified: TCP
//! 8770 answers on the Mac's awdl0 link-local address but not on its Wi-Fi
//! IPv4 address). To send to it we first need the Mac's current AWDL address,
//! which changes whenever its AWDL session restarts. This tool checks whether
//! the Mac advertises Companion Link / AirDrop on the AWDL link, and prints
//! every resolved address + port.
//!
//! Usage: `awdl_scan [interface] [seconds]` (defaults: awdl0, 15)

use mdns_sd::{IfKind, ServiceDaemon, ServiceEvent};
use std::time::Duration;

fn main() {
    let mut args = std::env::args().skip(1);
    let interface = args.next().unwrap_or_else(|| "awdl0".to_string());
    let seconds: u64 = args.next().and_then(|s| s.parse().ok()).unwrap_or(15);

    let daemon = ServiceDaemon::new().expect("daemon");
    daemon.disable_interface(IfKind::All).expect("disable all");
    daemon
        .enable_interface(IfKind::Name(interface.clone()))
        .expect("enable interface");

    println!("browsing awdl services on {interface} for {seconds}s");
    for service in ["_companion-link._tcp.local.", "_airdrop._tcp.local."] {
        let receiver = daemon.browse(service).expect("browse");
        std::thread::spawn(move || {
            while let Ok(event) = receiver.recv() {
                if let ServiceEvent::ServiceFound(ty, name) = &event {
                    println!("FOUND {ty} {name}");
                }
                if let ServiceEvent::ServiceResolved(info) = event {
                    let addrs: Vec<String> =
                        info.get_addresses().iter().map(|a| a.to_string()).collect();
                    println!(
                        "RESOLVED {} host={} port={} addrs=[{}] txt={:?}",
                        info.get_fullname(),
                        info.get_hostname(),
                        info.get_port(),
                        addrs.join(","),
                        info.get_properties()
                            .iter()
                            .map(|p| format!("{}={}", p.key(), p.val_str()))
                            .collect::<Vec<_>>()
                    );
                }
            }
        });
    }

    std::thread::sleep(Duration::from_secs(seconds));
    let _ = daemon.shutdown();
}
