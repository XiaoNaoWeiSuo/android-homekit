//! Integration test for in-process peer discovery (`mdns::spawn_browse`).
//!
//! Proves the exact chain the phone's "nearby devices" list depends on:
//!
//! 1. a daemon that is *announcing* can also browse (one socket, one scope);
//! 2. a second `_airdrop._tcp` instance on the same link is reported as `Found`
//!    with its link-local address and port;
//! 3. its goodbye is reported as `Lost`;
//! 4. repeated re-announcements do **not** produce duplicate `Found` events
//!    (the de-duplication rule that keeps the UI from flickering every 10 s).
//!
//! It is gated on `LUFTLIFT_TEST_IFACE` because it puts real multicast on a real
//! interface — on a phone that is `awdl0`, on a dev machine `en0`:
//!
//! ```text
//! LUFTLIFT_TEST_IFACE=en0 cargo test --test browse_discovery -- --nocapture
//! ```
//!
//! Without the variable the test is skipped (and says so), so `cargo test`
//! stays hermetic by default.

use luftlift_rs::mdns::{self, MdnsConfig, PeerEvent, DEFAULT_FLAGS};
use luftlift_rs::netutil;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Poll `events` until `pred` matches, or panic after `timeout`.
fn wait_for(
    events: &Arc<Mutex<Vec<PeerEvent>>>,
    timeout: Duration,
    what: &str,
    pred: impl Fn(&PeerEvent) -> bool,
) {
    let deadline = Instant::now() + timeout;
    loop {
        {
            let guard = events.lock().expect("event list poisoned");
            if guard.iter().any(&pred) {
                return;
            }
        }
        assert!(
            Instant::now() < deadline,
            "timed out after {:?} waiting for {what}; saw {:#?}",
            timeout,
            events.lock().unwrap()
        );
        std::thread::sleep(Duration::from_millis(100));
    }
}

#[test]
fn browse_on_announcing_daemon_reports_peer_then_lost() {
    let Some(interface) = std::env::var("LUFTLIFT_TEST_IFACE").ok() else {
        eprintln!("skipping: set LUFTLIFT_TEST_IFACE (e.g. en0 / awdl0) to run this test");
        return;
    };
    let Some(addr) = netutil::get_ipv6_for_interface(&interface) else {
        eprintln!("skipping: interface {interface} has no link-local IPv6 address");
        return;
    };

    // 1. Announce "ourselves" — the daemon under test is announcing, which is
    //    the situation the resident receiver is always in.
    let self_cfg = MdnsConfig { computer_name: "luftlift-itest-self".into(), port: 8771, flags: DEFAULT_FLAGS };
    let (daemon, self_info) =
        mdns::announce(&interface, &self_cfg, addr.into()).expect("announce self");
    let self_name = self_info.get_fullname().to_string();

    // 2. Browse on that same daemon.
    let events: Arc<Mutex<Vec<PeerEvent>>> = Arc::new(Mutex::new(Vec::new()));
    let sink = events.clone();
    let browse = mdns::spawn_browse(&daemon, move |event| {
        sink.lock().expect("event list poisoned").push(event);
    })
    .expect("spawn_browse on a live daemon");

    // 3. A second instance on the same link, announced through the same daemon
    //    (a real peer would announce from its own host; the daemon does not
    //    care which process the record came from).
    let peer_cfg = MdnsConfig { computer_name: "luftlift-itest-peer".into(), port: 8771, flags: DEFAULT_FLAGS };
    let peer_info =
        mdns::build_airdrop_service_info(&peer_cfg, addr.into()).expect("build peer info");
    let peer_name = peer_info.get_fullname().to_string();
    daemon.register(peer_info.clone()).expect("register peer");

    // 4. The peer must be reported, with the address and port a sender needs.
    wait_for(&events, Duration::from_secs(15), "Found(peer)", |event| {
        matches!(event, PeerEvent::Found(p)
            if p.name == peer_name && p.port == 8771 && p.addresses.contains(&addr.into()))
    });

    // 5. Our own announcement is reported too — `spawn_browse` deliberately
    //    does not filter; the receiver filters by instance prefix because only
    //    it knows which instance is its own.
    wait_for(&events, Duration::from_secs(15), "Found(self)", |event| {
        matches!(event, PeerEvent::Found(p) if p.name == self_name)
    });

    // 6. Re-announcing the same records must not duplicate the Found event.
    mdns::reannounce(&daemon, &peer_info).expect("re-announce peer");
    std::thread::sleep(Duration::from_secs(2));
    let found_count = events
        .lock()
        .unwrap()
        .iter()
        .filter(|e| matches!(e, PeerEvent::Found(p) if p.name == peer_name))
        .count();
    assert_eq!(found_count, 1, "re-announce must not duplicate the peer event");

    // 7. A goodbye must surface as Lost, so the UI can drop the row.
    let status = daemon.unregister(&peer_name).expect("unregister peer");
    let _ = status.recv_timeout(Duration::from_secs(2));
    wait_for(&events, Duration::from_secs(15), "Lost(peer)", |event| {
        matches!(event, PeerEvent::Lost(name) if *name == peer_name)
    });

    browse.stop();
    mdns::goodbye(&daemon, &self_name).expect("goodbye self");
}
