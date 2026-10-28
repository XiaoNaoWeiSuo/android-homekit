//! mDNS announce for AirDrop: registers a `_airdrop._tcp` service so the
//! receiver appears in the sender's AirDrop picker. Uses the pure-Rust
//! `mdns-sd` crate (no system avahi/dbus). Apple devices announce only while
//! their sharing pane is open; we announce continuously while `receive` runs.
//!
//! Reference: `opendrop-patch/server.py.patched` (`_init_service`,
//! `get_properties`).

use anyhow::Result;
use mdns_sd::{IfKind, ServiceDaemon, ServiceEvent, ServiceInfo, UnregisterStatus};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};
use tracing::{debug, info, warn};

/// The AirDrop mDNS service type. Both the announce and the browse side use
/// this exact string; a typo here silently produces an empty peer list.
pub const AIRDROP_SERVICE_TYPE: &str = "_airdrop._tcp.local.";

/// How long the browse loop blocks before re-checking its stop flag. Only sets
/// the worst-case teardown latency, not the peer-discovery latency (that is
/// governed by when a peer's record arrives).
const BROWSE_TICK: Duration = Duration::from_millis(500);

/// Default AirDrop `flags` TXT value, matching the reference opendrop
/// (`config.py`: `SUPPORTS_MIXED_TYPES | SUPPORTS_DISCOVER_MAYBE`).
/// `0x88 = 0x08 | 0x80`: MIXED_TYPES marks the node a valid receiver, and
/// DISCOVER_MAYBE signals the sender it may run `/Discover` — without 0x80 an
/// iOS sender can decline to surface the receiver in the picker. (Was 0x06,
/// which omitted DISCOVER_MAYBE; see LUFTLIFT_MDNS_AWDL_FIX.md.)
pub const DEFAULT_FLAGS: u32 = 0x88;

/// Interval between proactive mDNS re-announcements while `receive` runs.
///
/// mdns-sd 0.11's record TTLs are: A/SRV = **120s**, PTR/TXT = 4500s (the
/// setters are `pub(crate)`, so we cannot raise them). mdns-sd answers
/// incoming queries, but over a quiet/flaky AWDL link the Apple device's
/// refresh queries (or our responses) don't always get through; once the
/// 120s A/SRV TTL lapses with no refresh the device can no longer resolve
/// the receiver and it drops from the AirDrop picker.
///
/// Re-announcing every **10s** (well under the 120s A/SRV TTL) gives ~12
/// chances to land in a peer's listen window before the records would
/// expire — important because filin channel-hops across the AWDL channel
/// sequence (6/44/149), so the receiver and the Apple device may not share
/// a channel at any given moment. A short interval maximizes the odds that
/// at least one announcement gets through during a coincident listen
/// window. This matches the symptom observed in live testing (receiver
/// disappeared from the Mac's picker ~2 min after starting `receive`).
pub const REANNOUNCE_SECS: u64 = 10;

/// Tick rate of the re-announce loop. Short enough that shutdown (via the
/// stop flag) is responsive, long enough that the loop is effectively idle
/// between announcements.
const REANNOUNCE_TICK: Duration = Duration::from_secs(1);

/// Configuration for the mDNS announcement.
pub struct MdnsConfig {
    pub computer_name: String,
    pub port: u16,
    /// AirDrop `flags` TXT record value (decimal integer advertised as a
    /// string). This advertises the receiver's discoverability/capability
    /// bits to the sender; if the wrong bits are set the sender never
    /// surfaces us in its AirDrop picker.
    ///
    /// The reference opendrop default is **`0x88`** = `SUPPORTS_MIXED_TYPES
    /// (0x08) | SUPPORTS_DISCOVER_MAYBE (0x80)`. MIXED_TYPES marks us a valid
    /// receiver; DISCOVER_MAYBE tells the sender it may run `/Discover`.
    /// Override via `--flags` for experimentation.
    pub flags: u32,
}

/// Build the `_airdrop._tcp` ServiceInfo for announcement.
///
/// The instance name is **stable across runs** (just the computer name). It
/// used to embed the process PID so a human could tell a fresh run from a
/// ghost left by a killed one — but the Apple picker shows only a few
/// characters and, in practice, three restarts put three indistinguishable
/// copies of the phone in Finder. A stable name makes a restart *replace* the
/// entry instead of adding another one; the TTL=0 [`goodbye`] on shutdown
/// still removes it when we exit cleanly.
pub fn build_airdrop_service_info(cfg: &MdnsConfig, addr: IpAddr) -> Result<ServiceInfo> {
    let instance = cfg.computer_name.clone();
    let hostname = format!("{}.local.", cfg.computer_name);
    let mut props = HashMap::new();
    props.insert("flags".to_string(), cfg.flags.to_string());

    let info = ServiceInfo::new(
        "_airdrop._tcp.local.",
        &instance,
        &hostname,
        addr,
        cfg.port,
        Some(props),
    )?;
    Ok(info)
}

/// Register the AirDrop service on the local network. Returns the daemon
/// (keep alive to maintain the announcement) and the registered info.
pub fn announce(
    interface: &str,
    cfg: &MdnsConfig,
    addr: IpAddr,
) -> Result<(ServiceDaemon, ServiceInfo)> {
    let daemon = scoped_daemon(interface)?;
    let info = build_airdrop_service_info(cfg, addr)?;
    daemon.register(info.clone())?;
    info!(name = %cfg.computer_name, addr = %addr, port = cfg.port, interface, "mDNS _airdrop._tcp announced");
    Ok((daemon, info))
}

/// Create a `ServiceDaemon` restricted to a single interface (e.g. `awdl0`).
/// AirDrop lives entirely on the AWDL link; announcing/browsing on every host
/// interface leaks the service onto the regular LAN (the receiver then shows up
/// twice — once over awdl0, once over Wi-Fi) and pollutes discovery with
/// non-AWDL noise. Disable all interfaces, then re-enable only `interface`.
fn scoped_daemon(interface: &str) -> Result<ServiceDaemon> {
    let daemon = ServiceDaemon::new()?;
    daemon.disable_interface(IfKind::All)?;
    daemon.enable_interface(IfKind::Name(interface.to_string()))?;
    Ok(daemon)
}

/// Re-announce an already-registered service: calling `register` again on the
/// same `ServiceDaemon` pushes out fresh A/SRV/PTR/TXT records immediately and
/// schedules mdns-sd's RFC 6762 §8.3 1s retransmit, without a TTL=0 goodbye
/// (no picker flicker). This is the canonical mdns-sd 0.11 refresh mechanism
/// — `register_service` always emits an unsolicited response, then replaces
/// the existing entry in `my_services`. Used to keep the receiver visible in
/// the AirDrop picker past the 120s A/SRV TTL.
pub fn reannounce(daemon: &ServiceDaemon, info: &ServiceInfo) -> Result<()> {
    daemon.register(info.clone())?;
    Ok(())
}

/// Pure decision helper: has `REANNOUNCE_SECS` elapsed since `last`?
/// Split out so the interval logic is unit-testable without sleeping.
/// Returns `true` when `now - last >= REANNOUNCE_SECS`.
pub fn should_reannounce(last: Instant, now: Instant) -> bool {
    now.saturating_duration_since(last) >= Duration::from_secs(REANNOUNCE_SECS)
}

/// Spawn a background thread that proactively re-announces the service every
/// `REANNOUNCE_SECS` until the returned stop flag is set. Keeps the A/SRV
/// records (120s TTL) fresh on peers so the receiver stays in the Apple
/// AirDrop picker indefinitely. The `ServiceDaemon` is shared (`Arc`) with
/// the main thread's goodbye handler; `ServiceInfo` is `Clone`.
///
/// Returns the stop flag (set `true` to ask the thread to exit on its next
/// tick, ≤ `REANNOUNCE_TICK` later) plus the thread handle. The thread is
/// also killed cleanly by `std::process::exit` in the SIGINT handler, so
/// setting the flag is best-effort hygiene rather than a hard requirement.
pub fn spawn_reannouncer(
    daemon: Arc<ServiceDaemon>,
    info: ServiceInfo,
) -> (Arc<AtomicBool>, JoinHandle<()>) {
    let stop = Arc::new(AtomicBool::new(false));
    let stop_clone = stop.clone();
    let handle = thread::spawn(move || {
        let mut last = Instant::now();
        debug!(interval_secs = REANNOUNCE_SECS, "mDNS re-announcer started");
        while !stop.load(Ordering::Acquire) {
            thread::sleep(REANNOUNCE_TICK);
            if stop.load(Ordering::Acquire) {
                break;
            }
            let now = Instant::now();
            if should_reannounce(last, now) {
                let fullname = info.get_fullname().to_string();
                match reannounce(&daemon, &info) {
                    Ok(_) => {
                        debug!(service = %fullname, "mDNS re-announced (refresh A/SRV/PTR/TXT)");
                        last = now;
                    }
                    Err(e) => warn!(service = %fullname, error = %e, "mDNS re-announce failed"),
                }
            }
        }
        debug!("mDNS re-announcer stopped");
    });
    (stop_clone, handle)
}

/// Gracefully unregister an mDNS service: multicasts the TTL=0 goodbye for
/// every record (PTR/TXT/SRV/A) so peers (incl. Apple devices) remove the
/// entry from their AirDrop picker promptly. Waits up to `timeout` for mdns-sd
/// to confirm the goodbye was sent, then shuts the daemon down.
///
/// This covers clean exits (Ctrl+C / SIGTERM). For ungraceful crashes, the
/// mdns-sd 0.11 default `host_ttl=120` already self-clears the A/SRV records
/// in ~2 min; the PTR/TXT records (`other_ttl=4500`) cannot be shortened
/// because mdns-sd exposes no public TTL setter (`_set_*_ttl` is `pub(crate)`
/// in every released version) — the PID-suffixed instance name is the
/// mitigation there, letting you tell a fresh run from a stale ghost.
pub fn goodbye(daemon: &ServiceDaemon, fullname: &str) -> Result<()> {
    match daemon.unregister(fullname) {
        Ok(recv) => match recv.recv_timeout(Duration::from_secs(2)) {
            Ok(UnregisterStatus::OK) => info!(service = %fullname, "mDNS goodbye (TTL=0) sent"),
            Ok(other) => warn!(service = %fullname, ?other, "mDNS unregister returned non-OK"),
            Err(e) => warn!(service = %fullname, error = %e, "mDNS goodbye timed out — continuing"),
        },
        Err(e) => warn!(service = %fullname, error = %e, "mDNS unregister failed — continuing"),
    }
    let _ = daemon.shutdown();
    Ok(())
}

/// A discovered AirDrop peer (from mDNS browse).
#[derive(Debug, Clone)]
pub struct DiscoveredPeer {
    pub name: String,
    pub addresses: Vec<IpAddr>,
    pub port: u16,
}

/// A live discovery event, as it happens.
#[derive(Debug, Clone)]
pub enum PeerEvent {
    /// A peer resolved (we know its address and port).
    Found(DiscoveredPeer),
    /// A peer went away — TTL expired or it sent a goodbye. The UI drops the
    /// row; the peer may come back, in which case `Found` fires again.
    Lost(String),
}

/// Browse for `_airdrop._tcp` services for `duration`. Returns discovered peers.
/// Apple devices appear only while their sharing pane is open.
pub fn browse(interface: &str, duration: Duration) -> Result<Vec<DiscoveredPeer>> {
    let mut peers = Vec::new();
    browse_stream(interface, Some(duration), |event| {
        if let PeerEvent::Found(p) = event {
            peers.push(p);
        }
    })?;
    Ok(peers)
}

/// Browse, calling `on_event` for every peer as it appears or disappears.
///
/// This is what the "scan nearby devices" screen needs. [`browse`] only
/// returns once the whole window has elapsed, so a UI built on it would sit
/// blank for five seconds and then show everything at once — the opposite of
/// the AirDrop picker it is imitating. Here the first peer shows up as soon as
/// its record resolves.
///
/// `duration: None` browses until the process is killed — that is the mode the
/// resident service uses, so the peer list stays live for as long as the
/// AirDrop sheet is open.
pub fn browse_stream(
    interface: &str,
    duration: Option<Duration>,
    mut on_event: impl FnMut(PeerEvent),
) -> Result<()> {
    let daemon = scoped_daemon(interface)?;
    let receiver = daemon.browse(AIRDROP_SERVICE_TYPE)?;
    let deadline = duration.map(|d| Instant::now() + d);

    loop {
        let wait = match deadline {
            Some(d) => match d.checked_duration_since(Instant::now()) {
                Some(left) => left,
                None => break,
            },
            // No deadline: wake up periodically so a caller that stops
            // consuming (e.g. the UI closed) can still unwind, and so we
            // notice a daemon shutdown.
            None => Duration::from_secs(1),
        };
        match receiver.recv_timeout(wait) {
            Ok(ServiceEvent::ServiceResolved(info)) => {
                info!(name = %info.get_fullname(), addresses = ?info.get_addresses(), "discovered AirDrop peer");
                on_event(PeerEvent::Found(DiscoveredPeer {
                    name: info.get_fullname().to_string(),
                    addresses: info.get_addresses().iter().copied().collect(),
                    port: info.get_port(),
                }));
            }
            Ok(ServiceEvent::ServiceRemoved(_, fullname)) => {
                debug!(name = %fullname, "AirDrop peer went away");
                on_event(PeerEvent::Lost(fullname));
            }
            Ok(_) => {}
            // recv_timeout expiry: either the browse window closed (Some
            // deadline) or this is just a keep-alive tick (None) — both loop.
            Err(_) => {
                if deadline.is_some() || receiver.is_disconnected() {
                    break;
                }
            }
        }
    }
    let _ = daemon.shutdown();
    Ok(())
}

/// A browse loop running inside an existing [`ServiceDaemon`].
///
/// Dropping it stops the loop. `stop()` takes `&self` so the handle can live
/// inside the `Fn` teardown closures in `main.rs` (which cannot consume their
/// captures).
pub struct BrowseHandle {
    stop: Arc<AtomicBool>,
    handle: Mutex<Option<JoinHandle<()>>>,
}

impl BrowseHandle {
    pub fn is_finished(&self) -> bool {
        self.handle.lock().map(|h| h.as_ref().is_none_or(JoinHandle::is_finished))
            .unwrap_or(true)
    }

    /// Ask the loop to exit and wait for it (bounded by [`BROWSE_TICK`], plus
    /// the time the caller's `on_event` needs). Idempotent.
    pub fn stop(&self) {
        self.stop.store(true, Ordering::Release);
        if let Ok(mut slot) = self.handle.lock() {
            if let Some(handle) = slot.take() {
                let _ = handle.join();
            }
        }
    }
}

impl Drop for BrowseHandle {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Browse `_airdrop._tcp` **on a daemon that is already announcing**.
///
/// Why not just run `find --watch` next to `receive`? That works at the socket
/// level (mdns-sd sets SO_REUSEADDR/SO_REUSEPORT, so both processes can hold
/// 5353 — verified on the phone: `find -d 3` runs happily beside a live
/// receiver), but it is the wrong shape for a resident service:
///
/// * two processes each receive every packet and each keep their own cache;
/// * the second process cannot know which announced instance is *ours*, so it
///   lists the phone itself as a nearby device — the in-process version
///   filters by instance prefix;
/// * the app would have to merge two stdout event streams and two lifetimes.
///
/// So the resident receiver browses in-process on the same `ServiceDaemon` it
/// announces with — which mdns-sd supports natively (register + browse
/// coexist).
///
/// Differences from [`browse_stream`]:
///
/// * no daemon is created and none is shut down at the end — the caller owns
///   the daemon and its lifetime (in practice: one per announced address);
/// * events are **de-duplicated by full name**. mdns-sd re-resolves a service
///   on every cache refresh (a peer re-announces every 10 s), so without this
///   the UI would receive a duplicate `peer` event every 10 s per peer;
/// * a `Lost` is only reported for a name we actually reported as `Found`, so
///   the UI never has to remove a row it never added.
///
/// `on_event` runs on the browse thread. It must not block for long (it writes
/// one JSON line), or peer events would queue up behind it.
pub fn spawn_browse(
    daemon: &ServiceDaemon,
    on_event: impl FnMut(PeerEvent) + Send + 'static,
) -> Result<BrowseHandle> {
    spawn_browse_with_started(daemon, || {}, on_event)
}

/// Emit the session-reset event on the consumer thread before any cached
/// resolution. Starting the thread and then emitting reset on the caller
/// races with an immediate peer event and clears that peer from the UI.
pub fn spawn_browse_with_started(
    daemon: &ServiceDaemon,
    on_started: impl FnOnce() + Send + 'static,
    on_event: impl FnMut(PeerEvent) + Send + 'static,
) -> Result<BrowseHandle> {
    let receiver = daemon.browse(AIRDROP_SERVICE_TYPE)?;
    let stop = Arc::new(AtomicBool::new(false));
    let stop_thread = stop.clone();
    let handle = thread::Builder::new().name("airdrop-browse".into()).spawn(move || {
        browse_loop(&stop_thread, on_started, || {
            match receiver.recv_timeout(BROWSE_TICK) {
                Ok(event) => Ok(Some(event)),
                Err(_) if receiver.is_disconnected() => Err(()),
                Err(_) => Ok(None),
            }
        }, on_event);
    })?;
    Ok(BrowseHandle { stop, handle: Mutex::new(Some(handle)) })
}

// The receive adapter distinguishes timeout from channel closure, making
// shutdown finite even when the daemon dies before its owner calls stop().
fn browse_loop(
    stop: &AtomicBool,
    on_started: impl FnOnce(),
    mut receive: impl FnMut() -> std::result::Result<Option<ServiceEvent>, ()>,
    mut on_event: impl FnMut(PeerEvent),
) {
    let mut known: HashMap<String, DiscoveredPeer> = HashMap::new();
    on_started();
    debug!("browse loop started on the announcing daemon");
    while !stop.load(Ordering::Acquire) {
        match receive() {
            Ok(Some(ServiceEvent::ServiceResolved(info))) => {
                let mut addresses: Vec<_> = info.get_addresses().iter().copied().collect();
                addresses.sort_unstable();
                let peer = DiscoveredPeer {
                    name: info.get_fullname().to_string(),
                    addresses,
                    port: info.get_port(),
                };
                if peer_is_new_or_changed(&known, &peer) {
                    debug!(name = %peer.name, addresses = ?peer.addresses,
                           port = peer.port, "AirDrop peer resolved");
                    known.insert(peer.name.clone(), peer.clone());
                    on_event(PeerEvent::Found(peer));
                }
            }
            Ok(Some(ServiceEvent::ServiceRemoved(_, fullname))) => {
                if known.remove(&fullname).is_some() {
                    on_event(PeerEvent::Lost(fullname));
                }
            }
            Ok(_) => {}
            Err(()) => break,
        }
    }
    // A disconnected discovery stream cannot continue to vouch for old rows.
    // Emit removals before the next session's reset/repopulation.
    for name in known.into_keys() {
        on_event(PeerEvent::Lost(name));
    }
    debug!("browse loop stopped");
}

/// Pure de-duplication decision for the browse loop: is this resolution worth
/// an event? True when the peer is unknown, or its address set / port changed
/// (which happens when a peer's AWDL interface is re-created).
///
/// Split out so the "don't spam the UI every 10 s" rule is testable without a
/// real mDNS network.
pub fn peer_is_new_or_changed(
    known: &HashMap<String, DiscoveredPeer>,
    peer: &DiscoveredPeer,
) -> bool {
    match known.get(&peer.name) {
        Some(old) => {
            old.port != peer.port
                || old.addresses.iter().collect::<std::collections::BTreeSet<_>>()
                    != peer.addresses.iter().collect::<std::collections::BTreeSet<_>>()
        },
        None => true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg() -> MdnsConfig {
        MdnsConfig { computer_name: "luftlift-pc".into(), port: 8771, flags: DEFAULT_FLAGS }
    }

    #[test]
    fn service_info_has_airdrop_type_and_flags() {
        let info =
            build_airdrop_service_info(&cfg(), "127.0.0.1".parse().unwrap()).expect("build info");
        assert_eq!(info.get_port(), 8771);
        assert!(info.get_fullname().contains("_airdrop._tcp"));
        // flags TXT property must be present and carry the opendrop default
        // 0x88, advertised as its decimal string "136".
        let props = info.get_properties();
        assert_eq!(props.get("flags").map(|v| v.val_str()), Some("136"));
    }

    #[test]
    fn default_flags_is_opendrop_value_0x88() {
        // Reference opendrop config.py: SUPPORTS_MIXED_TYPES | DISCOVER_MAYBE.
        // Guards against regressing to 0x06 (omits DISCOVER_MAYBE) or 1.
        assert_eq!(DEFAULT_FLAGS, 0x88);
    }

    #[test]
    fn service_info_instance_name_is_stable_across_runs() {
        let cfg = cfg();
        let info =
            build_airdrop_service_info(&cfg, "127.0.0.1".parse().unwrap()).expect("build info");
        // A stable instance name is what stops three restarts from putting
        // three identical phones in the Apple picker: the new run refreshes
        // the same record instead of adding another one.
        let pid = std::process::id().to_string();
        let fullname = info.get_fullname().to_string();
        assert!(
            fullname.starts_with(&format!("{}._airdrop._tcp.local.", cfg.computer_name)),
            "instance name {fullname} must be the stable computer name"
        );
        assert!(
            !fullname.contains(&format!("-{pid}")),
            "instance name {fullname} must not embed the PID {pid}"
        );
    }

    #[test]
    fn announce_registers_without_error() {
        let (daemon, info) = announce("lo", &cfg(), "127.0.0.1".parse().unwrap()).expect("announce");
        assert!(info.get_fullname().contains("_airdrop._tcp"));
        // Clean shutdown via graceful goodbye.
        goodbye(&daemon, info.get_fullname()).expect("goodbye");
    }

    #[test]
    fn goodbye_unregisters_a_registered_service() {
        let (daemon, info) = announce("lo", &cfg(), "127.0.0.1".parse().unwrap()).expect("announce");
        let fullname = info.get_fullname().to_string();
        // goodbye must return Ok and send the TTL=0 multicast promptly (the
        // unregister status receiver resolves well within the 2s timeout).
        goodbye(&daemon, &fullname).expect("goodbye");
        // A second unregister of the same fullname should now be a no-op /
        // non-fatal; the daemon is also shut down. Just confirm no panic.
        let _ = daemon.shutdown();
    }

    // ---- re-announce (the 120s TTL refresh fix) ----

    #[test]
    #[allow(clippy::assertions_on_constants)] // intentional invariant guard on a const
    fn reannounce_interval_is_under_asrv_ttl() {
        // The A/SRV record TTL is 120s. REANNOUNCE_SECS must be comfortably
        // under it so a refresh goes out before the records expire on a peer.
        assert!(REANNOUNCE_SECS < 120, "re-announce interval must be < 120s A/SRV TTL");
        assert_eq!(REANNOUNCE_SECS, 10, "10s gives ~12 chances to land in a peer listen window before TTL expiry");
    }

    #[test]
    fn should_reannounce_returns_false_before_interval_and_true_at_or_after() {
        // Pure decision-logic test (no real sleeping). Guards the interval
        // boundary the live receiver depends on.
        let last = Instant::now();
        // 1s before the threshold: not yet.
        let before =
            last + Duration::from_secs(REANNOUNCE_SECS) - Duration::from_secs(1);
        assert!(!should_reannounce(last, before), "1s before interval -> no re-announce");
        // Exactly at the threshold: yes.
        let at = last + Duration::from_secs(REANNOUNCE_SECS);
        assert!(should_reannounce(last, at), "at interval -> re-announce");
        // 30s past the threshold: still yes.
        let past = last + Duration::from_secs(REANNOUNCE_SECS + 30);
        assert!(should_reannounce(last, past), "past interval -> re-announce");
    }

    #[test]
    fn reannounce_calls_register_again_on_same_daemon_without_error() {
        // Re-registering an already-announced service on the same daemon is
        // the canonical mdns-sd refresh (emits fresh A/SRV/PTR/TXT + the
        // RFC 6762 §8.3 1s retransmit, no TTL=0 goodbye). Verify it accepts
        // the second register without error.
        let (daemon, info) = announce("lo", &cfg(), "127.0.0.1".parse().unwrap()).expect("announce");
        reannounce(&daemon, &info).expect("re-announce must succeed on same daemon");
        // A second re-announce should also be fine (idempotent refresh).
        reannounce(&daemon, &info).expect("second re-announce must succeed");
        goodbye(&daemon, info.get_fullname()).expect("goodbye");
    }

    #[test]
    fn spawn_reannouncer_stops_promptly_when_flag_is_set() {
        // The re-announcer thread must observe the stop flag within ~1 tick
        // (REANNOUNCE_TICK = 1s), so the SIGINT handler can wind it down
        // cleanly. Verifies the join handle resolves promptly after stop.
        let (daemon, info) = announce("lo", &cfg(), "127.0.0.1".parse().unwrap()).expect("announce");
        let fullname = info.get_fullname().to_string();
        let daemon = Arc::new(daemon);
        let (stop, handle) = spawn_reannouncer(daemon.clone(), info);
        // Let it tick once, then stop it.
        std::thread::sleep(Duration::from_millis(1100));
        stop.store(true, std::sync::atomic::Ordering::Release);
        handle.join().expect("re-announcer thread must exit cleanly after stop");
        goodbye(&daemon, &fullname).ok();
    }

    // ---- in-process browse on the announcing daemon (the resident path) ----

    fn peer(name: &str, addr: &str, port: u16) -> DiscoveredPeer {
        DiscoveredPeer {
            name: name.to_string(),
            addresses: vec![addr.parse().expect("test address")],
            port,
        }
    }

    #[test]
    fn peer_is_new_or_changed_dedupes_repeats() {
        // A peer re-announces every 10s and mdns-sd re-resolves on every cache
        // refresh. Without dedup the UI would get a duplicate row/event each
        // time; the browse loop must stay silent for an unchanged record.
        let mut known = HashMap::new();
        let first = peer("a._airdrop._tcp.local.", "fe80::1", 8771);
        assert!(peer_is_new_or_changed(&known, &first), "first sighting is always emitted");
        known.insert(first.name.clone(), first.clone());
        assert!(!peer_is_new_or_changed(&known, &first), "identical re-resolution is suppressed");

        // Port change (a restarted receiver) must be surfaced.
        let mut moved = first.clone();
        moved.port = 9000;
        assert!(peer_is_new_or_changed(&known, &moved), "port change is emitted");

        // Address change (peer's AWDL interface re-created) must be surfaced.
        let mut readdr = first.clone();
        readdr.addresses = vec!["fe80::2".parse().unwrap()];
        assert!(peer_is_new_or_changed(&known, &readdr), "address change is emitted");

        // A different peer with the same address is still its own row.
        let other = peer("b._airdrop._tcp.local.", "fe80::1", 8771);
        assert!(peer_is_new_or_changed(&known, &other), "different name is emitted");
    }

    #[test]
    fn cached_peer_is_emitted_after_reset_and_removed_on_channel_close() {
        use std::cell::RefCell;
        let info = build_airdrop_service_info(&cfg(), "fe80::2".parse().unwrap()).unwrap();
        let mut input = std::collections::VecDeque::from([
            Ok(Some(ServiceEvent::ServiceResolved(info))),
            Ok(None), // timeout is not disconnect
            Err(()),
        ]);
        let events = RefCell::new(Vec::new());
        let mut calls = 0;
        browse_loop(&AtomicBool::new(false), || events.borrow_mut().push("reset"),
            || {
                calls += 1;
                input.pop_front().expect("disconnected channel was polled again")
            },
            |event| events.borrow_mut().push(match event {
                PeerEvent::Found(_) => "peer",
                PeerEvent::Lost(_) => "lost",
            }));
        assert_eq!(*events.borrow(), ["reset", "peer", "lost"]);
        assert_eq!(calls, 3);
    }

    #[test]
    fn address_set_order_does_not_emit_duplicate_peer() {
        let mut known = HashMap::new();
        let mut first = peer("a._airdrop._tcp.local.", "fe80::1", 8771);
        first.addresses.push("fe80::2".parse().unwrap());
        known.insert(first.name.clone(), first.clone());
        first.addresses.reverse();
        assert!(!peer_is_new_or_changed(&known, &first));
        first.addresses.pop();
        assert!(peer_is_new_or_changed(&known, &first));
    }

    #[test]
    fn removal_and_channel_shutdown_do_not_duplicate_lost() {
        let info = build_airdrop_service_info(&cfg(), "fe80::2".parse().unwrap()).unwrap();
        let fullname = info.get_fullname().to_string();
        let mut input = std::collections::VecDeque::from([
            Ok(Some(ServiceEvent::ServiceResolved(info.clone()))),
            Ok(Some(ServiceEvent::ServiceResolved(info))),
            Ok(Some(ServiceEvent::ServiceRemoved(AIRDROP_SERVICE_TYPE.into(), fullname.clone()))),
            Ok(Some(ServiceEvent::ServiceRemoved(AIRDROP_SERVICE_TYPE.into(), fullname))),
            Err(()),
        ]);
        let mut events = Vec::new();
        browse_loop(&AtomicBool::new(false), || {}, || input.pop_front().unwrap(),
            |event| events.push(match event {
                PeerEvent::Found(_) => "peer",
                PeerEvent::Lost(_) => "lost",
            }));
        assert_eq!(events, ["peer", "lost"]);
    }

    #[test]
    fn spawn_browse_shares_the_announcing_daemon_and_stops_promptly() {
        // The whole point of `spawn_browse` over `browse_stream`: it must run
        // on the daemon that is already announcing (one UDP 5353 socket, one
        // responder), and it must stop within a browse tick so the announce
        // supervisor can tear down / re-create the pair on address change.
        let (daemon, info) = announce("lo", &cfg(), "127.0.0.1".parse().unwrap()).expect("announce");
        let handle = spawn_browse(&daemon, |_event| {}).expect("browse must start on a live daemon");
        std::thread::sleep(Duration::from_millis(200));
        let started = Instant::now();
        handle.stop();
        assert!(
            started.elapsed() < Duration::from_secs(3),
            "browse loop must exit promptly, took {:?}",
            started.elapsed()
        );
        // Stopping twice (teardown + Drop) must not panic or block.
        handle.stop();
        goodbye(&daemon, info.get_fullname()).expect("goodbye");
    }

    #[test]
    fn browse_handle_drop_stops_the_loop() {
        // Teardown closures drop the handle when they cannot call stop()
        // explicitly; Drop must be equivalent, otherwise every address change
        // would leak a browse thread.
        let (daemon, info) = announce("lo", &cfg(), "127.0.0.1".parse().unwrap()).expect("announce");
        {
            let _handle = spawn_browse(&daemon, |_event| {}).expect("browse must start");
        }
        goodbye(&daemon, info.get_fullname()).expect("goodbye");
    }
}

