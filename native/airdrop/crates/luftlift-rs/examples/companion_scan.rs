//! Device-side smoke test for Companion Link discovery.
//!
//! Usage:
//!   `companion_scan [interface] [seconds]` — sweep the subnet and print peers
//!   `companion_scan --host <ip>`           — unicast-query one host
//!   `companion_scan --parse <file>`        — parse a captured DNS response
//!
//! Read-only: it opens no listener and never connects to the peer (except the
//! UDP query to port 5353).

use luftlift_rs::companion;
use std::time::Duration;

fn main() {
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_ansi(false)
        .init();

    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("--parse") => {
            let path = args.get(1).expect("usage: companion_scan --parse <file>");
            let bytes = std::fs::read(path).expect("read capture");
            match luftlift_rs::dns::parse_response(&bytes) {
                Some(response) => {
                    for record in &response.records {
                        println!("{} ttl={} {:?}", record.name, record.ttl, record.data);
                    }
                    let peers =
                        companion::peers_from_response(&response, "0.0.0.0".parse().unwrap());
                    for peer in peers {
                        println!(
                            "PEER name={:?} host={} port={} addrs={:?} mac={}",
                            peer.name, peer.hostname, peer.port, peer.addresses, peer.txt.is_mac
                        );
                    }
                }
                None => {
                    eprintln!("failed to parse {}", path);
                    std::process::exit(1);
                }
            }
        }
        Some("--host") => {
            let host: std::net::IpAddr = args
                .get(1)
                .expect("usage: companion_scan --host <ip>")
                .parse()
                .expect("ip");
            let peers = companion::query_host(host, Duration::from_secs(2));
            println!("{} peer(s) from {host}", peers.len());
            for peer in peers {
                println!(
                    "PEER name={:?} host={} port={} addrs={:?} txt={:?}",
                    peer.name, peer.hostname, peer.port, peer.addresses, peer.txt.raw
                );
            }
        }
        _ => {
            let interface = args.first().cloned().unwrap_or_else(|| "wlan0".to_string());
            let seconds: u64 = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(15);
            println!("scanning {interface} for {seconds} s");
            let result = companion::browse(
                &interface,
                Some(Duration::from_secs(seconds)),
                |event| match event {
                    companion::CompanionEvent::Found(peer) => {
                        let address = companion::preferred_address(&peer.addresses)
                            .map(|a| a.to_string())
                            .unwrap_or_default();
                        println!(
                            "FOUND name={:?} address={} port={} kind=companion model={:?} version={:?} is_mac={} ba={:?}",
                            peer.name,
                            address,
                            peer.port,
                            peer.txt.model,
                            peer.txt.version,
                            peer.txt.is_mac,
                            peer.txt.bt_address,
                        );
                    }
                    companion::CompanionEvent::Lost(name) => println!("LOST {name}"),
                },
            );
            if let Err(error) = result {
                eprintln!("browse failed: {error:#}");
                std::process::exit(1);
            }
        }
    }
}
