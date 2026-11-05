//! Companion Link (macOS 26/27 "Rapport") discovery.
//!
//! macOS 26+ moved AirDrop off the legacy `_airdrop._tcp` service: the only
//! thing a modern Mac advertises is `_companion-link._tcp`, served by
//! `rapportd` on a TCP port (61156 on a stock install). The service lives on
//! the ordinary Wi-Fi interface, **not** on AWDL.
//!
//! ## Why this is a unicast sweep, not an mDNS browse
//!
//! On the reference network the AP drops client-to-client **multicast**
//! queries: `mdns-sd` sent QM queries for `_companion-link._tcp` every few
//! seconds (visible in `tcpdump` on the phone) and the Mac never answered,
//! while the Mac's own multicast traffic reached the phone. A *unicast* query
//! with the QU bit set to `10.12.1.5:5353` was answered immediately with the
//! full PTR/SRV/TXT/A set.
//!
//! So discovery sweeps the local IPv4 subnet and sends that unicast query to
//! every host. It is read-only, sends no file data, and needs no prior
//! knowledge of the Mac's address (DHCP moves it: it was `10.12.1.64` and is
//! now `10.12.1.5`). The service port is taken from the SRV record, so a
//! non-default `rapportd` port works too.
//!
//! What we take from the record:
//!
//! * the **instance name** is the human device name on Companion Link
//!   (`焱林的MacBook`), unlike legacy AirDrop where it is an opaque token —
//!   this is why the phone's device list can finally show real names;
//! * SRV target + port (`MacBook.local.:61156`) for the Rapport connection;
//! * TXT keys `rpAD`/`rpHI`/`rpHA`/`rpBA`/`rpVr`/`rpFl`/`rpMd`/`rpMac`,
//!   which identify the device and are the input to pairing/verification.
//!
//! Discovery never sends frames or files; the Rapport transport lives in
//! [`crate::rapport`].

use crate::dns;
use crate::netutil;
use anyhow::{anyhow, Result};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};
use tracing::{debug, info, warn};

/// The Companion Link mDNS service type announced by `rapportd`.
pub const COMPANION_SERVICE_TYPE: &str = "_companion-link._tcp.local.";

/// mDNS port. Queries go out from an ephemeral port; responders reply unicast.
pub const MDNS_PORT: u16 = 5353;

/// Fallback rapportd port when a response has a PTR but no SRV.
pub const DEFAULT_COMPANION_PORT: u16 = 61156;

/// How long one sweep waits for responses after blasting the subnet.
///
/// The reference Mac runs Wi-Fi power save aggressively (ICMP RTT measured at
/// 0.2–5.7 s), and its mDNS responder answers only a fraction of unicast
/// queries. A 2.5 s window plus repeated rounds is what makes it show up
/// reliably; a single 900 ms round used to miss it every time.
const SWEEP_RESPONSE_WINDOW: Duration = Duration::from_millis(2500);

/// How many times each host is asked per sweep. mDNS has no retransmit, and a
/// power-saving peer can miss the first query entirely.
const SWEEP_ROUNDS: usize = 3;

/// Gap between sweep rounds (kept short so a whole sweep stays ~3 s).
const SWEEP_ROUND_GAP: Duration = Duration::from_millis(300);

/// Gap between refreshes. A refresh queries only the hosts we already know
/// plus the kernel's ARP neighbours, so it is a handful of packets.
const SWEEP_INTERVAL: Duration = Duration::from_secs(15);

/// A full subnet sweep is ~254 queries. Do it at startup and then only once a
/// minute; the 15 s refreshes in between cover known hosts and ARP neighbours.
const FULL_SWEEP_INTERVAL: Duration = Duration::from_secs(60);

/// Drop a peer that has not answered for this long (three missed sweeps).
const STALE_AFTER: Duration = Duration::from_secs(60);

/// Do not sweep absurdly large subnets: a /20 is 4094 hosts. Home and small
/// office LANs are /24 or wider-masked but reachable in practice; anything
/// larger is almost certainly a shared network where sweeping would be rude.
const MAX_SWEEP_HOSTS: u32 = 1024;

/// Parsed Companion Link TXT record.
///
/// Keys are the short `rp*` names Apple uses; each is optional because the set
/// varies between macOS/iOS versions and between Apple and third-party
/// implementations.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CompanionTxt {
    /// `rpAD` — account identifier (stable per Apple ID).
    pub account_id: Option<String>,
    /// `rpHI` — home identifier.
    pub home_id: Option<String>,
    /// `rpHA` — home-attribute hash.
    pub home_attribute: Option<String>,
    /// `rpBA` — Bluetooth/companion address (`XX:XX:XX:XX:XX:XX`).
    pub bt_address: Option<String>,
    /// `rpVr` — Rapport protocol version (`715.2`).
    pub version: Option<String>,
    /// `rpFl` — capability flags.
    pub flags: Option<String>,
    /// `rpMd` — model identifier (`MacBookPro18,3`, `iPhone15,2`, ...).
    pub model: Option<String>,
    /// `rpMac=1` — the peer is a Mac.
    pub is_mac: bool,
    /// Every key/value exactly as advertised, for logs and future keys.
    pub raw: HashMap<String, String>,
}

impl CompanionTxt {
    pub fn from_map(raw: HashMap<String, String>) -> Self {
        let get = |key: &str| raw.get(key).cloned();
        Self {
            account_id: get("rpAD"),
            home_id: get("rpHI"),
            home_attribute: get("rpHA"),
            bt_address: get("rpBA").map(|v| v.to_ascii_uppercase()),
            version: get("rpVr"),
            flags: get("rpFl"),
            model: get("rpMd"),
            is_mac: raw.get("rpMac").is_some_and(|v| v == "1"),
            raw,
        }
    }

    /// A short suffix that disambiguates devices with the same visible name.
    /// Falls back to the model when even the companion address is missing.
    pub fn identity_suffix(&self) -> String {
        if let Some(ba) = &self.bt_address {
            let compact: String = ba.chars().filter(|c| c.is_ascii_hexdigit()).collect();
            if compact.len() >= 4 {
                return compact[compact.len() - 4..].to_string();
            }
        }
        self.model.clone().unwrap_or_default()
    }
}

/// A Companion Link peer discovered over unicast mDNS.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompanionPeer {
    /// Human-readable instance name, e.g. `焱林的MacBook`.
    pub name: String,
    /// Fully-qualified instance (`焱林的MacBook._companion-link._tcp.local.`).
    pub fullname: String,
    /// SRV target (`MacBook.local.`).
    pub hostname: String,
    pub addresses: Vec<IpAddr>,
    /// Rapport TCP port from the SRV record (61156 on a stock macOS).
    pub port: u16,
    pub txt: CompanionTxt,
}

/// A Companion Link discovery event.
///
/// `Found` carries the whole parsed record (a few `String`s plus the TXT map),
/// which is larger than `Lost`'s name. Events are emitted a handful of times
/// per minute, so the stack/heap difference is irrelevant next to the
/// ergonomics of matching the peer directly.
#[allow(clippy::large_enum_variant)]
#[derive(Debug, Clone)]
pub enum CompanionEvent {
    Found(CompanionPeer),
    Lost(String),
}

/// Ask one host for its `_companion-link._tcp` records and parse the answer.
///
/// Returns every peer in the response (normally one). A host that is not a
/// Companion Link device simply does not answer, or answers NODATA; both are
/// `Ok(vec![])`.
pub fn query_host(host: IpAddr, timeout: Duration) -> Vec<CompanionPeer> {
    let Ok(socket) = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))) else {
        return Vec::new();
    };
    let _ = socket.set_read_timeout(Some(timeout));
    let Some(packet) = dns::encode_ptr_query(COMPANION_SERVICE_TYPE, 0) else {
        return Vec::new();
    };
    if socket
        .send_to(&packet, SocketAddr::new(host, MDNS_PORT))
        .is_err()
    {
        return Vec::new();
    }
    let deadline = Instant::now() + timeout;
    let mut peers = HashMap::new();
    let mut buf = [0u8; 2048];
    while Instant::now() < deadline {
        if let Ok((n, from)) = socket.recv_from(&mut buf) {
            if let Some(response) = dns::parse_response(&buf[..n]) {
                for peer in peers_from_response(&response, from.ip()) {
                    peers.insert(peer.fullname.clone(), peer);
                }
            }
        }
    }
    peers.into_values().collect()
}

/// The legacy AirDrop service type. macOS 27 still registers it on the Wi-Fi
/// interface **while the Finder AirDrop pane is open** (verified: `sharingd`
/// binds an IPv6 listener on 8770 at the same moment), so on this OS the new
/// discovery protocol is paired with the old transfer protocol.
pub const AIRDROP_SERVICE_TYPE: &str = "_airdrop._tcp.local.";

/// Port the legacy AirDrop receiver uses when the SRV record is missing.
pub const AIRDROP_DEFAULT_PORT: u16 = 8770;

/// A legacy AirDrop endpoint resolved over unicast DNS.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AirdropEndpoint {
    /// Full service instance (`f228b843c107._airdrop._tcp.local.`).
    pub instance: String,
    pub hostname: String,
    pub port: u16,
    pub addresses: Vec<IpAddr>,
    pub txt: HashMap<String, String>,
}

/// Send one unicast PTR question for `service` to `host` and return the first
/// response that actually carries answers.
///
/// The source is an ephemeral port, so the responder sends a unicast answer
/// back to us; that is what makes this work on networks where client-to-client
/// multicast is filtered (the case that motivated this module).
pub fn query_service(host: IpAddr, service: &str, timeout: Duration) -> Option<dns::Response> {
    let socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).ok()?;
    socket.set_read_timeout(Some(timeout)).ok()?;
    let packet = dns::encode_ptr_query(service, 0)?;
    socket
        .send_to(&packet, SocketAddr::new(host, MDNS_PORT))
        .ok()?;
    let deadline = Instant::now() + timeout;
    let mut buf = [0u8; 2048];
    while Instant::now() < deadline {
        if let Ok((n, _)) = socket.recv_from(&mut buf) {
            if let Some(response) = dns::parse_response(&buf[..n]) {
                if !response.ptr_targets().is_empty() {
                    return Some(response);
                }
            }
        }
    }
    None
}

/// Resolve the legacy AirDrop endpoint on `host`.
///
/// macOS 27 only answers while its AirDrop pane is open; when the pane is
/// closed this returns `None` even though the device is still discoverable via
/// Companion Link. That distinction is what the send path reports to the user.
pub fn resolve_airdrop_endpoint(host: IpAddr, timeout: Duration) -> Option<AirdropEndpoint> {
    let host =
        // `query_service` needs an IPv4 source socket; link-local-only peers are
        // not usable for the legacy transfer from a phone anyway.
        match host {
            IpAddr::V4(_) => host,
            IpAddr::V6(_) => return None,
        };
    let response = query_service(host, AIRDROP_SERVICE_TYPE, timeout)?;
    endpoint_from_response(&response, host)
}

/// Pure part of [`resolve_airdrop_endpoint`], split out so the record handling
/// can be tested without a network.
pub fn endpoint_from_response(response: &dns::Response, host: IpAddr) -> Option<AirdropEndpoint> {
    let suffix = AIRDROP_SERVICE_TYPE.trim_end_matches('.');
    for target in response.ptr_targets() {
        let target = target.trim_end_matches('.');
        if !target.ends_with(suffix) {
            continue;
        }
        let (port, hostname) = response
            .srv_for(target)
            .map(|(port, name)| (port, name.trim_end_matches('.').to_string()))
            .unwrap_or_else(|| (AIRDROP_DEFAULT_PORT, String::new()));
        let mut addresses = if hostname.is_empty() {
            Vec::new()
        } else {
            let named = response.addresses_for(&hostname);
            if named.is_empty() {
                response.addresses_for(&format!("{hostname}.local"))
            } else {
                named
            }
        };
        if addresses.is_empty() {
            addresses = response.addresses_for(target);
        }
        if addresses.is_empty() {
            addresses = vec![host];
        }
        return Some(AirdropEndpoint {
            instance: format!("{target}."),
            hostname,
            port,
            addresses,
            txt: response.txt_for(target).unwrap_or_default(),
        });
    }
    None
}

/// Sweep the interface's IPv4 subnet with unicast mDNS queries.
///
/// The sweep is one UDP socket sending a ~44-byte question per host, then a
/// short listen window. No TCP connection is opened and no file data is sent.
pub fn sweep(interface: &str) -> Result<Vec<CompanionPeer>> {
    let (local, netmask) = netutil::get_ipv4_for_interface(interface).ok_or_else(|| {
        anyhow!("interface {interface} has no IPv4 address (Companion Link needs the Wi-Fi link)")
    })?;
    let hosts = subnet_hosts(local, netmask)?;
    sweep_targets(interface, local, &hosts)
}

/// Hosts covered by a full sweep of the interface's IPv4 subnet, excluding the
/// interface's own address and capped at [`MAX_SWEEP_HOSTS`].
pub fn subnet_hosts(local: Ipv4Addr, netmask: Ipv4Addr) -> Result<Vec<Ipv4Addr>> {
    let network = u32::from(local) & u32::from(netmask);
    let broadcast = network | !u32::from(netmask);
    let total = broadcast.saturating_sub(network).saturating_sub(1);
    if total == 0 {
        return Err(anyhow!("a /31 or /32 netmask has no sweepable hosts"));
    }
    Ok((1..=total.min(MAX_SWEEP_HOSTS))
        .map(|offset| Ipv4Addr::from(network + offset))
        .filter(|host| *host != local)
        .collect())
}

/// Query a specific set of hosts. Used both for a full sweep (all subnet
/// hosts) and for the cheap 15 s refresh (known peers + ARP neighbours).
fn sweep_targets(
    interface: &str,
    local: Ipv4Addr,
    targets: &[Ipv4Addr],
) -> Result<Vec<CompanionPeer>> {
    let socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)))?;
    socket.set_read_timeout(Some(Duration::from_millis(100)))?;
    let packet = dns::encode_ptr_query(COMPANION_SERVICE_TYPE, 0)
        .ok_or_else(|| anyhow!("failed to encode the Companion Link PTR question"))?;

    let mut sent = 0usize;
    for round in 0..SWEEP_ROUNDS {
        for host in targets {
            if *host == local {
                continue;
            }
            if socket
                .send_to(&packet, SocketAddr::new(IpAddr::V4(*host), MDNS_PORT))
                .is_ok()
            {
                sent += 1;
            }
        }
        if round + 1 < SWEEP_ROUNDS {
            thread::sleep(SWEEP_ROUND_GAP);
        }
    }

    // A handful of known hosts does not need the full listen window; a full
    // subnet may have many slow (power-saving) responders, so keep it.
    let window = if targets.len() <= 32 {
        Duration::from_millis(900)
    } else {
        SWEEP_RESPONSE_WINDOW
    };
    let deadline = Instant::now() + window;
    let mut peers: HashMap<String, CompanionPeer> = HashMap::new();
    let mut buf = [0u8; 2048];
    while Instant::now() < deadline {
        if let Ok((n, from)) = socket.recv_from(&mut buf) {
            if let Some(response) = dns::parse_response(&buf[..n]) {
                for peer in peers_from_response(&response, from.ip()) {
                    peers.insert(peer.fullname.clone(), peer);
                }
            }
        }
    }
    debug!(
        interface,
        sent,
        targeted = targets.len(),
        found = peers.len(),
        "Companion Link sweep finished"
    );
    Ok(peers.into_values().collect())
}

/// Complete ARP neighbours from `/proc/net/arp` (Android/Linux); empty on
/// other platforms. These are cheap refresh candidates: a device that talked
/// to us recently is likely still around even before a full sweep.
pub fn arp_neighbors() -> Vec<Ipv4Addr> {
    let Ok(text) = std::fs::read_to_string("/proc/net/arp") else {
        return Vec::new();
    };
    text.lines()
        .skip(1)
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            let ip = fields.next()?.parse::<Ipv4Addr>().ok()?;
            let _hw_type = fields.next()?;
            let flags = u32::from_str_radix(fields.next()?, 16).ok()?;
            // ATF_COM (0x2): complete entry, i.e. the MAC is known.
            (flags & 0x2 != 0).then_some(ip)
        })
        .collect()
}

/// The host set for one cheap refresh: known peers + ARP neighbours, without
/// the local address, deduplicated and sorted for deterministic packet order.
pub fn refresh_targets(known: &[Ipv4Addr], arp: &[Ipv4Addr], local: Ipv4Addr) -> Vec<Ipv4Addr> {
    let mut targets: Vec<Ipv4Addr> = known
        .iter()
        .chain(arp.iter())
        .copied()
        .filter(|host| *host != local && !host.is_broadcast() && !host.is_unspecified())
        .collect();
    targets.sort_unstable();
    targets.dedup();
    targets
}

/// Turn a parsed DNS response into peers. Only answers for
/// `_companion-link._tcp.local` are considered; anything else (a phone
/// answering `_airdrop._tcp`, a printer, ...) is ignored.
pub fn peers_from_response(response: &dns::Response, responder: IpAddr) -> Vec<CompanionPeer> {
    let suffix = COMPANION_SERVICE_TYPE.trim_end_matches('.');
    response
        .ptr_targets()
        .into_iter()
        .filter_map(|instance| {
            let instance = instance.trim_end_matches('.');
            if !instance.ends_with(suffix) {
                return None;
            }
            let name = instance
                .strip_suffix(suffix)
                .unwrap_or(instance)
                .trim_end_matches('.')
                .to_string();
            if name.is_empty() {
                return None;
            }
            let fullname = format!("{instance}.");
            let (port, hostname) = response
                .srv_for(instance)
                .map(|(port, target)| (port, target.trim_end_matches('.').to_string()))
                .unwrap_or_else(|| (DEFAULT_COMPANION_PORT, String::new()));

            let mut addresses = if hostname.is_empty() {
                Vec::new()
            } else {
                let named = response.addresses_for(&hostname);
                if named.is_empty() {
                    response.addresses_for(&format!("{hostname}.local"))
                } else {
                    named
                }
            };
            if addresses.is_empty() {
                addresses = response.addresses_for(instance);
            }
            if addresses.is_empty() {
                addresses = vec![responder];
            }

            Some(CompanionPeer {
                name,
                fullname,
                hostname,
                addresses,
                port,
                txt: CompanionTxt::from_map(response.txt_for(instance).unwrap_or_default()),
            })
        })
        .collect()
}

/// A blocking browse. Runs sweeps until `duration` elapses (or forever when
/// `None`), invoking `on_event` as peers appear and disappear.
pub fn browse(
    interface: &str,
    duration: Option<Duration>,
    mut on_event: impl FnMut(CompanionEvent),
) -> Result<()> {
    let (tx, rx) = mpsc::channel();
    let handle = spawn_browse(interface, move |event| {
        let _ = tx.send(event);
    })?;
    let deadline = duration.map(|d| Instant::now() + d);
    loop {
        let wait = match deadline {
            Some(deadline) => {
                let left = deadline.saturating_duration_since(Instant::now());
                if left.is_zero() {
                    break;
                }
                left.min(Duration::from_millis(500))
            }
            None => Duration::from_millis(500),
        };
        match rx.recv_timeout(wait) {
            Ok(event) => on_event(event),
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }
    handle.stop();
    Ok(())
}

/// A background sweep loop.
pub struct CompanionBrowseHandle {
    stop: Arc<AtomicBool>,
    handle: Mutex<Option<JoinHandle<()>>>,
}

impl CompanionBrowseHandle {
    pub fn is_finished(&self) -> bool {
        self.handle
            .lock()
            .map(|h| h.as_ref().is_none_or(JoinHandle::is_finished))
            .unwrap_or(true)
    }

    pub fn stop(&self) {
        self.stop.store(true, Ordering::Release);
        if let Ok(mut slot) = self.handle.lock() {
            if let Some(handle) = slot.take() {
                let _ = handle.join();
            }
        }
    }
}

impl Drop for CompanionBrowseHandle {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Start the resident Companion Link discovery loop.
///
/// The loop retries when the interface has no IPv4 yet (Wi-Fi still coming up)
/// and only reports `Lost` after [`STALE_AFTER`], so one dropped sweep does not
/// flicker the UI.
pub fn spawn_browse(
    interface: &str,
    on_event: impl FnMut(CompanionEvent) + Send + 'static,
) -> Result<CompanionBrowseHandle> {
    let stop = Arc::new(AtomicBool::new(false));
    let thread_stop = stop.clone();
    let name = format!("companion-sweep({interface})");
    let interface = interface.to_string();
    let handle = thread::Builder::new().name(name).spawn(move || {
        let mut on_event = on_event;
        let mut known: HashMap<String, (CompanionPeer, Instant)> = HashMap::new();
        let mut warned_no_ipv4 = false;
        // Start with a full sweep; afterwards refresh the known hosts every
        // SWEEP_INTERVAL and only re-scan the whole subnet once a minute.
        let mut full_sweep_at = Instant::now();

        while !thread_stop.load(Ordering::Acquire) {
            let now = Instant::now();
            let result = if now >= full_sweep_at {
                full_sweep_at = now + FULL_SWEEP_INTERVAL;
                sweep(&interface)
            } else {
                match netutil::get_ipv4_for_interface(&interface) {
                    Some((local, _mask)) => {
                        let known_hosts: Vec<Ipv4Addr> = known
                            .values()
                            .flat_map(|(peer, _)| {
                                peer.addresses.iter().filter_map(|address| match address {
                                    IpAddr::V4(v4) => Some(*v4),
                                    IpAddr::V6(_) => None,
                                })
                            })
                            .collect();
                        let targets = refresh_targets(&known_hosts, &arp_neighbors(), local);
                        if targets.is_empty() {
                            full_sweep_at = now + FULL_SWEEP_INTERVAL;
                            sweep(&interface)
                        } else {
                            sweep_targets(&interface, local, &targets)
                        }
                    }
                    None => Err(anyhow!(
                        "interface {interface} has no IPv4 address (Companion Link needs the Wi-Fi link)"
                    )),
                }
            };

            match result {
                Ok(peers) => {
                    warned_no_ipv4 = false;
                    let now = Instant::now();
                    for peer in peers {
                        let changed = known
                            .get(&peer.fullname)
                            .map(|(previous, _)| previous != &peer)
                            .unwrap_or(true);
                        known.insert(peer.fullname.clone(), (peer.clone(), now));
                        if changed {
                            info!(
                                name = %peer.name,
                                host = %peer.hostname,
                                port = peer.port,
                                version = peer.txt.version.as_deref().unwrap_or(""),
                                model = peer.txt.model.as_deref().unwrap_or(""),
                                mac = peer.txt.is_mac,
                                addrs = ?peer.addresses,
                                "discovered Companion Link peer"
                            );
                            on_event(CompanionEvent::Found(peer));
                        }
                    }

                    let stale: Vec<String> = known
                        .iter()
                        .filter(|(_, (_, seen))| now.duration_since(*seen) > STALE_AFTER)
                        .map(|(name, _)| name.clone())
                        .collect();
                    for name in stale {
                        known.remove(&name);
                        on_event(CompanionEvent::Lost(name));
                    }
                }
                Err(error) => {
                    if !warned_no_ipv4 {
                        warn!(interface = %interface, %error, "Companion Link sweep unavailable — retrying");
                        warned_no_ipv4 = true;
                    }
                }
            }

            // Interruptible sleep so shutdown is immediate.
            let tick = Duration::from_millis(200);
            let mut slept = Duration::ZERO;
            while slept < SWEEP_INTERVAL && !thread_stop.load(Ordering::Acquire) {
                thread::sleep(tick);
                slept += tick;
            }
        }
        debug!(interface = %interface, "Companion Link sweep loop stopped");
    })?;

    Ok(CompanionBrowseHandle {
        stop,
        handle: Mutex::new(Some(handle)),
    })
}

/// Pick the address a Rapport TCP connection should use: prefer IPv4 on the
/// Wi-Fi link, then a routable IPv6, then link-local (which needs a scope id
/// and is usually the least reliable choice for a connection the *phone*
/// initiates).
pub fn preferred_address(addresses: &[IpAddr]) -> Option<IpAddr> {
    addresses
        .iter()
        .copied()
        .find(|a| a.is_ipv4())
        .or_else(|| {
            addresses
                .iter()
                .copied()
                .find(|a| matches!(a, IpAddr::V6(v6) if !v6.is_unicast_link_local()))
        })
        .or_else(|| addresses.first().copied())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mac_txt() -> HashMap<String, String> {
        [
            ("rpMac", "1"),
            ("rpHN", "8d42f7a605c4"),
            ("rpFl", "0x20000"),
            ("rpHA", "83b04c565445"),
            ("rpVr", "715.2"),
            ("rpAD", "ddb895229ac0"),
            ("rpHI", "b745f2587f0a"),
            ("rpBA", "3C:B1:D4:EE:EF:CF"),
        ]
        .into_iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect()
    }

    #[test]
    fn parses_the_mac_txt_record() {
        let txt = CompanionTxt::from_map(mac_txt());
        assert!(txt.is_mac);
        assert_eq!(txt.version.as_deref(), Some("715.2"));
        assert_eq!(txt.account_id.as_deref(), Some("ddb895229ac0"));
        assert_eq!(txt.home_id.as_deref(), Some("b745f2587f0a"));
        assert_eq!(txt.bt_address.as_deref(), Some("3C:B1:D4:EE:EF:CF"));
        assert_eq!(txt.flags.as_deref(), Some("0x20000"));
        assert_eq!(txt.identity_suffix(), "EFCF");
    }

    #[test]
    fn missing_keys_stay_none() {
        let txt = CompanionTxt::from_map(HashMap::new());
        assert!(txt.bt_address.is_none());
        assert!(!txt.is_mac);
        assert_eq!(txt.identity_suffix(), "");
    }

    #[test]
    fn prefers_ipv4_then_routable_v6() {
        let ll: IpAddr = "fe80::1".parse().unwrap();
        let gv6: IpAddr = "fd65::1".parse().unwrap();
        let v4: IpAddr = "10.12.1.5".parse().unwrap();
        assert_eq!(preferred_address(&[ll, gv6, v4]), Some(v4));
        assert_eq!(preferred_address(&[ll, gv6]), Some(gv6));
        assert_eq!(preferred_address(&[ll]), Some(ll));
        assert_eq!(preferred_address(&[]), None);
    }

    #[test]
    fn ignores_non_companion_answers() {
        let response = dns::Response {
            records: vec![dns::Record {
                name: "_airdrop._tcp.local".into(),
                ttl: 10,
                data: dns::RData::Ptr("someone._airdrop._tcp.local".into()),
            }],
        };
        assert!(peers_from_response(&response, "10.12.1.9".parse().unwrap()).is_empty());
    }

    #[test]
    fn resolves_the_legacy_airdrop_endpoint() {
        let instance = "f228b843c107._airdrop._tcp.local";
        let response = dns::Response {
            records: vec![
                dns::Record {
                    name: AIRDROP_SERVICE_TYPE.trim_end_matches('.').into(),
                    ttl: 120,
                    data: dns::RData::Ptr(instance.into()),
                },
                dns::Record {
                    name: instance.into(),
                    ttl: 120,
                    data: dns::RData::Srv {
                        priority: 0,
                        weight: 0,
                        port: 8770,
                        target: "MacBook.local".into(),
                    },
                },
                dns::Record {
                    name: instance.into(),
                    ttl: 120,
                    data: dns::RData::Txt(b"\x0aflags=0x88".to_vec()),
                },
                dns::Record {
                    name: "MacBook.local".into(),
                    ttl: 120,
                    data: dns::RData::A("10.12.1.5".parse().unwrap()),
                },
            ],
        };
        let host: IpAddr = "10.12.1.5".parse().unwrap();
        let endpoint = endpoint_from_response(&response, host).expect("endpoint");
        assert_eq!(endpoint.instance, "f228b843c107._airdrop._tcp.local.");
        assert_eq!(endpoint.hostname, "MacBook.local");
        assert_eq!(endpoint.port, 8770);
        assert_eq!(
            endpoint.addresses,
            vec!["10.12.1.5".parse::<IpAddr>().unwrap()]
        );
        assert_eq!(endpoint.txt.get("flags").map(String::as_str), Some("0x88"));
    }

    #[test]
    fn airdrop_endpoint_defaults_to_8770_without_srv() {
        let response = dns::Response {
            records: vec![dns::Record {
                name: AIRDROP_SERVICE_TYPE.trim_end_matches('.').into(),
                ttl: 120,
                data: dns::RData::Ptr("abc._airdrop._tcp.local".into()),
            }],
        };
        let host: IpAddr = "10.12.1.7".parse().unwrap();
        let endpoint = endpoint_from_response(&response, host).expect("endpoint");
        assert_eq!(endpoint.port, AIRDROP_DEFAULT_PORT);
        assert_eq!(endpoint.addresses, vec![host]);
    }

    #[test]
    fn subnet_hosts_excludes_self_and_covers_a_24() {
        let local: Ipv4Addr = "10.12.1.61".parse().unwrap();
        let mask: Ipv4Addr = "255.255.255.0".parse().unwrap();
        let hosts = subnet_hosts(local, mask).expect("hosts");
        assert_eq!(hosts.len(), 253);
        assert!(!hosts.contains(&local));
        assert!(hosts.contains(&"10.12.1.5".parse().unwrap()));
    }

    #[test]
    fn subnet_hosts_caps_wide_masks() {
        let local: Ipv4Addr = "10.0.0.1".parse().unwrap();
        let mask: Ipv4Addr = "255.255.0.0".parse().unwrap();
        let hosts = subnet_hosts(local, mask).expect("hosts");
        assert!(hosts.len() <= MAX_SWEEP_HOSTS as usize);
        assert!(!hosts.contains(&local));
    }

    #[test]
    fn refresh_targets_dedupes_and_skips_self() {
        let local: Ipv4Addr = "10.12.1.61".parse().unwrap();
        let known = vec!["10.12.1.5".parse::<Ipv4Addr>().unwrap()];
        let arp = vec![
            "10.12.1.5".parse::<Ipv4Addr>().unwrap(),
            "10.12.1.40".parse::<Ipv4Addr>().unwrap(),
            local,
        ];
        let targets = refresh_targets(&known, &arp, local);
        assert_eq!(targets.len(), 2);
        assert!(!targets.contains(&local));
        // Ipv4Addr sorts numerically, so .5 comes before .40.
        assert_eq!(targets[0], "10.12.1.5".parse::<Ipv4Addr>().unwrap());
        assert_eq!(targets[1], "10.12.1.40".parse::<Ipv4Addr>().unwrap());
    }

    #[test]
    fn builds_a_peer_from_a_companion_answer() {
        let instance = "焱林的MacBook._companion-link._tcp.local";
        let response = dns::Response {
            records: vec![
                dns::Record {
                    name: COMPANION_SERVICE_TYPE.trim_end_matches('.').into(),
                    ttl: 10,
                    data: dns::RData::Ptr(instance.into()),
                },
                dns::Record {
                    name: instance.into(),
                    ttl: 10,
                    data: dns::RData::Srv {
                        priority: 0,
                        weight: 0,
                        port: 61156,
                        target: "MacBook.local".into(),
                    },
                },
                dns::Record {
                    name: instance.into(),
                    ttl: 10,
                    data: dns::RData::Txt(
                        b"\x07rpMac=1\x0arpVr=715.2\x16rpBA=3C:B1:D4:EE:EF:CF".to_vec(),
                    ),
                },
                dns::Record {
                    name: "MacBook.local".into(),
                    ttl: 10,
                    data: dns::RData::A("10.12.1.5".parse().unwrap()),
                },
            ],
        };
        let peers = peers_from_response(&response, "10.12.1.5".parse().unwrap());
        assert_eq!(peers.len(), 1);
        let peer = &peers[0];
        assert_eq!(peer.name, "焱林的MacBook");
        assert_eq!(peer.fullname, "焱林的MacBook._companion-link._tcp.local.");
        assert_eq!(peer.hostname, "MacBook.local");
        assert_eq!(peer.port, 61156);
        assert_eq!(peer.addresses, vec!["10.12.1.5".parse::<IpAddr>().unwrap()]);
        assert!(peer.txt.is_mac);
        assert_eq!(peer.txt.version.as_deref(), Some("715.2"));
    }

    #[test]
    fn falls_back_to_the_responder_address() {
        let instance = "Someone._companion-link._tcp.local";
        let response = dns::Response {
            records: vec![dns::Record {
                name: COMPANION_SERVICE_TYPE.trim_end_matches('.').into(),
                ttl: 10,
                data: dns::RData::Ptr(instance.into()),
            }],
        };
        let peers = peers_from_response(&response, "10.12.1.9".parse().unwrap());
        assert_eq!(peers.len(), 1);
        assert_eq!(peers[0].port, DEFAULT_COMPANION_PORT);
        assert_eq!(
            peers[0].addresses,
            vec!["10.12.1.9".parse::<IpAddr>().unwrap()]
        );
    }
}
