//! luftlift — Rust port of opendrop (the AirDrop application layer).
//!
//! Runs over the `awdl0` interface that `filin-rs` brings up. See PORT.md for
//! the full spec and the reverse-engineered iOS-18 protocol details (dvzip,
//! chunked Ask/Discover, etc.).
//!
//! Reference: ../../opendrop-patch/ (patched Python opendrop + dvzip.py decoder).

use anyhow::Result;
use clap::{Parser, Subcommand};
use luftlift_rs::{
    client::{FileToSend, RustlsTransport, SenderConfig, send_files_with},
    introspect::{
        DEFAULT_HTTP_ADDR, IntrospectState, TraceCollector,
        spawn_http_server,
    },
    mdns, netutil, server, tls,
    mdns::DEFAULT_FLAGS,
    plist_impl::ReceiverConfig,
    progress::Sink,
};
use std::path::PathBuf;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::time::Duration;

type GoodbyeCallback = Arc<Mutex<Option<Box<dyn Fn() + Send>>>>;

#[derive(Parser)]
#[command(name = "luftlift", about = "AirDrop receiver (port of opendrop)")]
struct Cli {
    /// Network interface to bind (e.g. awdl0).
    #[arg(short, long, default_value = "awdl0")]
    interface: String,

    /// Emit machine-readable JSON progress events on stdout — one object per
    /// line, see native/airdrop/PROGRESS.md. Logs continue to go to stderr, so
    /// stdout stays parseable. Also enabled by MARIONETTE_PROGRESS=1, which is
    /// what the Android service sets so it does not have to rewrite its
    /// command line.
    #[arg(long, global = true)]
    progress: bool,

    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Receive files via AirDrop (announce + HTTPS server).
    Receive {
        /// Directory to save received files (default: current dir).
        #[arg(short, long, default_value = ".")]
        output: PathBuf,
        /// AirDrop `flags` TXT value (default 0x88 = opendrop's verified value
        /// that makes us appear in iOS 18's AirDrop picker). Override for
        /// experimentation; e.g. `--flags 0x01`.
        #[arg(short = 'F', long, default_value_t = DEFAULT_FLAGS, value_parser = parse_flags)]
        flags: u32,
        /// Receiver display name shown in the Apple AirDrop picker
        /// (ReceiverComputerName in /Discover + /Ask responses). Defaults to
        /// `<base>-<pid>` so a fresh run is distinguishable from stale ghosts;
        /// the base comes from LUFTLIFT_NAME or "luftlift". An explicit
        /// `--name foo` is used verbatim (no PID suffix).
        #[arg(short = 'n', long)]
        name: Option<String>,
        /// Bind address for the loopback JSON introspection API
        /// (`/status`, `/trace`). Defaults to `127.0.0.1:9931`. Use
        /// `--http-addr off` to disable it entirely. SEPARATE from the
        /// AirDrop HTTPS listener on 8771; never reachable off-box.
        #[arg(long, default_value = DEFAULT_HTTP_ADDR)]
        http_addr: String,
        /// Also browse for nearby AirDrop devices while receiving, emitting
        /// `browsing` / `peer` / `lost` on the same event stream. This is what
        /// the phone's "nearby devices" list consumes.
        ///
        /// Doing it here rather than in a separate `find --watch` process means
        /// one mDNS socket, one interface scope, one event stream, no
        /// cross-process merge, and it lets the browse reuse the exact
        /// interface the announcement went out on. It also lets the receiver
        /// filter out its *own* announcement (a separate process cannot know
        /// which instance is ours, so it lists the phone as a nearby device).
        #[arg(long)]
        browse: bool,
    },
    /// Find AirDrop senders on the local network.
    Find {
        /// Browse duration in seconds.
        #[arg(short, long, default_value_t = 5)]
        duration: u64,
        #[arg(long)]
        json: bool,
        /// Stream peers as they appear/disappear and never stop — the mode the
        /// phone's "scan nearby devices" screen uses. Emits `peer` and `lost`
        /// events (requires --progress). Ignores --duration.
        #[arg(long)]
        watch: bool,
    },
    /// Send a file to a specific AirDrop receiver.
    Send {
        /// Recipient identifier (mDNS instance name). Matched as a substring of
        /// the announced instance name; ignored (but still required, to keep
        /// the positional order stable) when --address is given.
        recipient: String,
        /// File to send.
        file: PathBuf,
        /// Browse this long before giving up on finding the recipient.
        #[arg(short, long, default_value_t = 5)]
        duration: u64,
        /// Skip mDNS and connect straight to this address (`[v6]:port` or
        /// `v4:port`). Useful for debugging over loopback, and for retrying a
        /// transfer to an address the UI already knows.
        #[arg(long, value_name = "ADDR:PORT")]
        address: Option<String>,
    },
}

/// Parse a flags integer in decimal or hex (e.g. `6`, `0x06`).
fn parse_flags(s: &str) -> Result<u32, String> {
    let s = s.trim();
    let lower = s.to_ascii_lowercase();
    let radix_base = if lower.starts_with("0x") { 16 } else { 10 };
    let stripped = lower.strip_prefix("0x").unwrap_or(s);
    u32::from_str_radix(stripped, radix_base)
        .map_err(|e| format!("invalid flags value '{s}': {e}"))
}

fn main() -> Result<()> {
    // Build the introspection state up-front so the TraceCollector layer can
    // be installed alongside the fmt layer from the start. The HTTP server
    // for the introspect API is only spawned by `receive`; `find` and `send`
    // never expose it (the layer is a no-op until set_trace_level is called,
    // which only POST /trace can do, and only `receive` serves that).
    let introspect = Arc::new(IntrospectState::new());

    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::util::SubscriberInitExt;
    let env_filter = tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| {
        // Default: our crate at debug, info elsewhere, but silence the
        // mdns-sd 0.11 NSEC parse spam ("Invalid incoming DNS message:
        // NSEC block length must be in the range 1-32: 0") triggered by
        // other devices' mDNS records on the LAN — not our bug, but it
        // drowns `luftlift find` output. RUST_LOG still overrides this.
        "luftlift_rs=debug,info,mdns_sd=off".into()
    });
    let registry = tracing_subscriber::registry()
        .with(env_filter)
        .with(tracing_subscriber::fmt::layer().with_writer(std::io::stderr).with_ansi(false))
        .with(TraceCollector::new(introspect.clone()));
    registry.init();

    let cli = Cli::parse();
    // Interactive CLI usage is unchanged; only managed Android children opt in.
    let _parent_monitor = if env_flag("MARIONETTE_CONTROL_STDIN") {
        Some(luftlift_rs::lifecycle::watch_parent_stdin()?)
    } else {
        None
    };

    // The progress channel is opt-in via `--progress` or MARIONETTE_PROGRESS.
    // A disabled sink is a no-op, so every code path can take one
    // unconditionally instead of threading `Option`s through the transfer code.
    let sink = Sink::from_flag(cli.progress || env_flag("MARIONETTE_PROGRESS"));

    match cli.command {
        Command::Receive { output, flags, name, http_addr, browse } => {
            receive(&cli.interface, output, flags, name, http_addr, browse, introspect, sink)
        }
        Command::Find { duration, json, watch } => {
            find(&cli.interface, duration, json, watch, sink)
        }
        Command::Send { recipient, file, duration, address } => {
            send(&cli.interface, &recipient, file, duration, address, sink)
        }
    }
}

/// Truthy environment flag: set to anything but `0`/`false`/empty.
fn env_flag(name: &str) -> bool {
    match std::env::var(name) {
        Ok(v) => {
            let v = v.trim().to_ascii_lowercase();
            !(v.is_empty() || v == "0" || v == "false" || v == "no" || v == "off")
        }
        Err(_) => false,
    }
}

/// Print a human-readable line, keeping stdout clean for the JSON-lines
/// progress channel. When that channel is on, prose goes to stderr instead —
/// otherwise a reader parsing stdout as one-JSON-object-per-line would choke on
/// "No AirDrop senders found."
fn say(sink: &Sink, line: &str) {
    if sink.is_enabled() {
        eprintln!("{line}");
    } else {
        println!("{line}");
    }
}

#[allow(clippy::too_many_arguments)]
fn receive(
    interface: &str,
    output: PathBuf,
    flags: u32,
    name: Option<String>,
    http_addr: String,
    browse: bool,
    introspect: Arc<IntrospectState>,
    sink: Arc<Sink>,
) -> Result<()> {
    tracing::info!(interface, flags = format!("0x{flags:02x}"), "starting AirDrop receiver");

    let cert = tls::generate_self_signed_cert()?;
    let tls_config = tls::build_server_config(&cert)?;
    tracing::info!("TLS cert generated");

    // The AirDrop picker displays ReceiverComputerName (from the /Discover and
    // /Ask response plists), NOT the mDNS instance name. Default to
    // `<base>-<pid>` so a fresh run is distinguishable from stale ghosts; an
    // explicit --name is used verbatim. The mDNS instance name continues to
    // embed the PID separately (in build_airdrop_service_info) for the same
    // reason at the mDNS layer.
    let base_name = default_base_name();
    let receiver_name = resolve_receiver_name(&name, &base_name);
    tracing::info!(receiver_name = %receiver_name, "receiver display name (shown in AirDrop picker)");

    let cfg = ReceiverConfig {
        computer_name: receiver_name.clone(),
        computer_model: "MacBookPro".into(),
        record_data: None,
    };

    // Spawn the loopback introspect HTTP server unless explicitly disabled.
    // Bound to 127.0.0.1 only — never reachable over the AWDL link. The
    // server runs in its own thread so it does not block the receive path.
    if !http_addr.eq_ignore_ascii_case("off") {
        match spawn_http_server(&http_addr, introspect.clone()) {
            Ok(server) => tracing::info!(
                addr = %server.local_addr,
                "introspect API serving /status and /trace (loopback)"
            ),
            Err(e) => tracing::warn!(
                error = %e,
                addr = %http_addr,
                "introspect HTTP server failed to bind — continuing without it",
            ),
        }
    } else {
        tracing::info!("introspect HTTP server disabled (--http-addr off)");
    }

    // RESILIENCE: the awdl0 tap is owned by filin and is destroyed/recreated
    // (with a new MAC → new IPv6) whenever filin restarts or the USB Wi-Fi card
    // is bounced. The HTTPS listener below is bound to [::]:8771 (dual-stack)
    // and survives that, but the mDNS announce is tied to awdl0's specific
    // address. A supervisor thread (re)announces whenever awdl0's address
    // appears or changes, checking once per second — so the receiver
    // automatically reappears in the AirDrop picker after a card bounce
    // instead of advertising a dead/old address.
    let goodbye: GoodbyeCallback = Arc::new(Mutex::new(None));
    {
        let interface = interface.to_string();
        let base_name = default_base_name();
        let receiver_name = receiver_name.clone();
        let introspect = introspect.clone();
        let goodbye = goodbye.clone();
        let sink = sink.clone();
        std::thread::spawn(move || {
            mdns_announce_supervisor(
                &interface,
                flags,
                &base_name,
                &receiver_name,
                browse,
                introspect,
                goodbye,
                sink,
            );
        });
    }

    // SIGINT/SIGTERM: send the current mDNS TTL=0 goodbye (the supervisor keeps
    // it up to date for whatever address is currently announced) so Apple
    // devices drop us from their picker immediately, then exit.
    {
        let goodbye = goodbye.clone();
        ctrlc::set_handler(move || {
            tracing::info!("signal received — sending mDNS goodbye");
            if let Ok(g) = goodbye.lock() {
                if let Some(f) = g.as_ref() {
                    f();
                }
            }
            std::process::exit(0);
        })?;
    }

    let listener = server::bind_listener(8771)?;
    tracing::info!(output = %output.display(), "saving received files to output dir");
    // We can accept a transfer from this moment on, even if the mDNS announce
    // has not landed yet (and might never land, if awdl0 never gets an
    // address). Telling the UI now lets it distinguish "starting" from
    // "up but invisible to Apple devices".
    sink.listening(&receiver_name, 8771);

    server::serve_forever(listener, Arc::new(tls_config), cfg, Some(output), introspect, sink);
    Ok(())
}

/// Keep an mDNS `_airdrop._tcp` announcement alive on the CURRENT awdl0
/// address, re-announcing whenever that address appears or changes. Runs
/// forever, polling once per second. Tearing down the previous announcement
/// (stop its re-announcer, TTL=0 goodbye, shut the daemon down) before making
/// a new one avoids advertising a stale/old address after a card bounce.
///
/// When `browse` is set, the same daemon also browses `_airdrop._tcp`, so the
/// receiver can list nearby devices without a second process fighting it for
/// UDP 5353. The browse loop is torn down and re-created together with the
/// announcement, because both are bound to the same interface address.
#[allow(clippy::too_many_arguments)]
fn mdns_announce_supervisor(
    interface: &str,
    flags: u32,
    base_name: &str,
    receiver_name: &str,
    browse: bool,
    introspect: Arc<IntrospectState>,
    goodbye: GoodbyeCallback,
    sink: Arc<Sink>,
) {
    let mdns_cfg = mdns::MdnsConfig {
        computer_name: base_name.to_string(),
        port: 8771,
        flags,
    };
    // Our own announcement is visible to our own browse (it is multicast, and
    // the daemon caches what it registers). The AirDrop picker must not list
    // the phone itself, so peers whose name carries our instance prefix are
    // filtered out of the event stream. build_airdrop_service_info() names the
    // instance `<base>-<pid>`, hence the same shape here.
    let self_prefix = format!("{}-{}.", base_name, std::process::id());
    // Teardown for the currently-active announce, and the address it advertises.
    let mut teardown: Option<Box<dyn Fn()>> = None;
    let mut last_addr: Option<std::net::Ipv6Addr> = None;
    let mut active_browse: Option<Arc<mdns::BrowseHandle>> = None;

    loop {
        if active_browse.as_ref().is_some_and(|handle| handle.is_finished()) {
            if let Some(td) = teardown.take() { td(); }
            active_browse = None;
            last_addr = None;
            tracing::warn!("browse stream ended; recreating discovery session");
        }
        match netutil::get_ipv6_for_interface(interface) {
            // New or changed awdl0 address → (re)announce.
            Some(addr) if Some(addr) != last_addr => {
                if let Some(td) = teardown.take() {
                    td();
                }
                match mdns::announce(interface, &mdns_cfg, addr.into()) {
                    Ok((daemon, info)) => {
                        let daemon = Arc::new(daemon);
                        let fullname = info.get_fullname().to_string();
                        let (stop, _handle) = mdns::spawn_reannouncer(daemon.clone(), info);
                        introspect.record_announce(receiver_name.to_string(), 8771);
                        tracing::info!(%addr, interface, service = %fullname, "mDNS (re)announced");
                        // The UI's "starting" spinner is cleared here rather
                        // than at bind() time: until the announce lands we are
                        // listening but *invisible*, and claiming readiness
                        // would make "waiting for a device" look like a
                        // transfer bug. Fires again after an address change,
                        // which is exactly the recovery signal the UI wants.
                        sink.ready(receiver_name, 8771);

                        // Nearby-device discovery, on the daemon we just
                        // announced with. A failure here is not fatal: the
                        // receiver still works, it just cannot list peers.
                        let browsing = if browse {
                            let started_sink = sink.clone();
                            match mdns::spawn_browse_with_started(
                                &daemon, move || started_sink.browsing(0), {
                                let sink = sink.clone();
                                let self_prefix = self_prefix.clone();
                                move |event| match event {
                                    mdns::PeerEvent::Found(peer) => {
                                        if peer.name.starts_with(&self_prefix) {
                                            return;
                                        }
                                        let address = preferred_address(&peer.addresses)
                                            .map(|a| a.to_string())
                                            .unwrap_or_default();
                                        sink.peer(&peer.name, &address, peer.port);
                                    }
                                    mdns::PeerEvent::Lost(name) => {
                                        if name.starts_with(&self_prefix) {
                                            return;
                                        }
                                        sink.lost(&name);
                                    }
                                }
                            }) {
                                Ok(handle) => {
                                    tracing::info!(interface, "browsing for nearby AirDrop devices");
                                    Some(Arc::new(handle))
                                }
                                Err(e) => {
                                    tracing::warn!(error = %e, "peer browse failed to start — receiver continues without a nearby list");
                                    None
                                }
                            }
                        } else {
                            None
                        };

                        active_browse = browsing.clone();
                        // Update the ctrl-c goodbye to point at this daemon.
                        if let Ok(mut g) = goodbye.lock() {
                            let d = daemon.clone();
                            let fname = fullname.clone();
                            *g = Some(Box::new(move || {
                                let _ = mdns::goodbye(&d, &fname);
                            }));
                        }
                        // Teardown: stop re-announcer + browse, goodbye, shut
                        // the daemon down.
                        let d = daemon.clone();
                        let fname = fullname.clone();
                        teardown = Some(Box::new(move || {
                            stop.store(true, Ordering::Release);
                            if let Some(browse) = browsing.as_ref() {
                                browse.stop();
                            }
                            let _ = mdns::goodbye(&d, &fname);
                            let _ = d.shutdown();
                        }));
                        last_addr = Some(addr);
                    }
                    Err(e) => {
                        tracing::warn!(error = %e, %addr, "mDNS announce failed; retrying in 1s");
                    }
                }
            }
            // awdl0 lost its address → tear down so we re-announce on return.
            None if last_addr.is_some() => {
                if let Some(td) = teardown.take() {
                    td();
                }
                active_browse = None;
                last_addr = None;
                tracing::warn!(interface, "awdl0 address gone; will re-announce when it returns");
            }
            _ => {}
        }
        std::thread::sleep(Duration::from_secs(1));
    }
}

fn find(interface: &str, duration: u64, json: bool, watch: bool, sink: Arc<Sink>) -> Result<()> {
    // --watch is the live "nearby devices" mode: stream every peer the moment
    // its record resolves, and keep going until killed. Without it we keep the
    // original one-shot behaviour (collect for `duration`, print, exit).
    if watch {
        tracing::info!(interface, "watching for _airdrop._tcp (streaming)");
        sink.browsing(0);
        return mdns::browse_stream(interface, None, |event| match event {
            mdns::PeerEvent::Found(p) => {
                sink.peer(
                    &p.name,
                    &preferred_address(&p.addresses).map(|a| a.to_string()).unwrap_or_default(),
                    p.port,
                );
            }
            mdns::PeerEvent::Lost(name) => sink.lost(&name),
        });
    }

    tracing::info!(interface, duration, "browsing for _airdrop._tcp");
    sink.browsing(duration);
    let peers = mdns::browse(interface, Duration::from_secs(duration))?;
    for p in &peers {
        sink.peer(&p.name, &p.addresses.first().map(|a| a.to_string()).unwrap_or_default(), p.port);
    }
    if json {
        // With --progress the `peer` events above already carry this; printing
        // the array as well would put a bare JSON *array* on a stream the
        // reader parses as one object per line.
        if !sink.is_enabled() {
            println!("{}", serde_json::Value::Array(peers.iter().map(|p| serde_json::json!({"name":p.name,"port":p.port})).collect()));
        }
        return Ok(());
    }
    if peers.is_empty() {
        say(&sink, "No AirDrop senders found.");
        say(&sink, "Tip: Apple devices announce only while their sharing pane is open.");
    } else {
        say(&sink, "AirDrop senders found:");
        for peer in &peers {
            let addrs = peer
                .addresses
                .iter()
                .map(|a| a.to_string())
                .collect::<Vec<_>>()
                .join(", ");
            say(&sink, &format!("  {} ({}:{})", peer.name, addrs, peer.port));
        }
    }
    Ok(())
}

/// Pick the address to advertise to the UI (and to hand to `send --address`).
///
/// AirDrop lives on the AWDL link, whose address is IPv6 link-local; a peer
/// that also has a routable IPv6 or an IPv4 address would otherwise be listed
/// with an address we cannot reach on awdl0. Prefer link-local v6, fall back to
/// whatever came first.
fn preferred_address(addresses: &[std::net::IpAddr]) -> Option<std::net::IpAddr> {
    addresses
        .iter()
        .copied()
        .find(|a| matches!(a, std::net::IpAddr::V6(v6) if v6.is_unicast_link_local()))
        .or_else(|| addresses.first().copied())
}

/// Base name for mDNS instance/hostname and the receiver name. Respects the
/// `LUFTLIFT_NAME` environment variable; defaults to "luftlift".
fn default_base_name() -> String {    std::env::var("LUFTLIFT_NAME").unwrap_or_else(|_| "luftlift".to_string())
}

/// Resolve the ReceiverComputerName (what the Apple AirDrop picker displays).
///
/// - `--name foo` → `"foo"` (user's explicit choice, no PID suffix).
/// - no `--name` → `"<base>-<pid>"` (default; PID lets the human tell a fresh
///   run from a stale ghost in the picker, since the picker shows
///   ReceiverComputerName, not the mDNS instance name).
fn resolve_receiver_name(cli_name: &Option<String>, base: &str) -> String {
    match cli_name {
        Some(n) => n.clone(),
        None => format!("{}-{}", base, std::process::id()),
    }
}

/// Best-effort name for the sender identity.
fn hostname() -> String {
    default_base_name()
}

/// Send a file to an AirDrop receiver. `recipient` is matched against mDNS
/// instance names (a prefix match is enough; the full names are long).
fn send(
    interface: &str,
    recipient: &str,
    file: PathBuf,
    duration: u64,
    address: Option<String>,
    sink: Arc<Sink>,
) -> Result<()> {
    let result = send_inner(interface, recipient, file, duration, address, &sink);
    if let Err(e) = &result {
        // One error event, carrying the same message the log gets, so the UI
        // can show the reason without scraping stderr.
        sink.error(&format!("{e:#}"));
    }
    result
}

fn send_inner(
    interface: &str,
    recipient: &str,
    file: PathBuf,
    duration: u64,
    address: Option<String>,
    sink: &Sink,
) -> Result<()> {
    let data = std::fs::read(&file)?;
    tracing::info!(file = %file.display(), bytes = data.len(), "loaded file to send");

    // Two ways to find the peer: an explicit address (debug / retry, no mDNS),
    // or a browse + name match. The explicit form exists so a transfer can be
    // driven end-to-end over loopback, where multicast routing does not work.
    let (peer_name, addr, port) = match address {
        Some(text) => {
            let (addr, port) = parse_socket_addr(&text)?;
            tracing::info!(%addr, port, "using explicit peer address (mDNS skipped)");
            (text.clone(), addr, port)
        }
        None => {
            tracing::info!(recipient, "browsing for receiver");
            sink.browsing(duration);
            let peers = mdns::browse(interface, Duration::from_secs(duration))?;
            // Match the recipient by name ONLY. Do NOT fall back to "the first peer
            // discovered" — that silently targets whatever turned up first (typically
            // our own co-located `luftlift receive` service), sending the file to the
            // wrong host. If nothing matches, fail loudly and list what WAS found.
            let peer = peers
                .iter()
                .find(|p| p.name.contains(recipient))
                .ok_or_else(|| {
                    let found = if peers.is_empty() {
                        "<none>".to_string()
                    } else {
                        peers
                            .iter()
                            .map(|p| p.name.as_str())
                            .collect::<Vec<_>>()
                            .join(", ")
                    };
                    anyhow::anyhow!(
                        "no AirDrop receiver matching '{recipient}' found; discovered: [{found}] \
                         (Apple devices announce only while their sharing pane is open)"
                    )
                })?;
            let addr = peer
                .addresses
                .first()
                .copied()
                .ok_or_else(|| anyhow::anyhow!("receiver {recipient} has no resolved address"))?;
            (peer.name.clone(), addr, peer.port)
        }
    };
    tracing::info!(peer = %peer_name, %addr, port, "receiver found");
    sink.peer(&peer_name, &addr.to_string(), port);

    // The peer's address is IPv6 link-local on awdl0 (fe80::/10). Connecting
    // to a link-local address REQUIRES a scope_id (the interface zone), else
    // TcpStream::connect fails with EINVAL (os error 22). mDNS discovery
    // returns the address but not the zone, so we attach the scope from the
    // operator's `-i <interface>`. See LUFTLIFT_SEND_BUG.md.
    let scope_id = netutil::if_index_for(interface);
    let needs_scope = matches!(addr, std::net::IpAddr::V6(v6) if v6.is_unicast_link_local());
    if needs_scope && scope_id.is_none() {
        tracing::warn!(
            interface,
            "link-local peer but interface index could not be resolved; \
             connect will likely fail with EINVAL (os error 22)"
        );
    } else if needs_scope {
        tracing::info!(interface, scope_id = scope_id, "attaching IPv6 scope_id for link-local peer");
    }
    let peer_addr = netutil::connect_addr_with_scope(addr, port, scope_id);

    let tls_config = Arc::new(tls::build_client_config()?);
    let mut transport = RustlsTransport::new(tls_config, peer_addr);

    let sender = SenderConfig::new(hostname(), "MacBookPro", "com.luftlift.app");
    let file_name = file
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("file")
        .to_string();
    let files = vec![FileToSend {
        name: file_name,
        uti_type: uti_for(&file).to_string(),
        data,
    }];
    let transfer_id = format!("{:08x}-{:04x}-{:04x}-{:04x}-{:012x}",
        rand_u32(), rand_u16(), rand_u16(), rand_u16(), rand_u64() & 0xFFFFFFFFFFFF);
    send_files_with(&mut transport, &sender, &files, &transfer_id, sink)?;
    say(sink, "Send complete.");
    Ok(())
}

/// Parse `--address` in either `1.2.3.4:8771` or `[fe80::1]:8771` form.
fn parse_socket_addr(text: &str) -> Result<(std::net::IpAddr, u16)> {
    let text = text.trim();
    let (host, port) = text
        .rsplit_once(':')
        .ok_or_else(|| anyhow::anyhow!("--address needs <ip>:<port>, got '{text}'"))?;
    let host = host.trim_start_matches('[').trim_end_matches(']');
    let addr: std::net::IpAddr = host
        .parse()
        .map_err(|e| anyhow::anyhow!("invalid IP address '{host}': {e}"))?;
    let port: u16 = port
        .parse()
        .map_err(|e| anyhow::anyhow!("invalid port '{port}': {e}"))?;
    Ok((addr, port))
}

/// Best-effort UTI type from a file extension.
fn uti_for(path: &std::path::Path) -> &'static str {
    match path.extension().and_then(|e| e.to_str()).map(|e| e.to_ascii_lowercase()).as_deref() {
        Some("jpg") | Some("jpeg") => "public.jpeg",
        Some("png") => "public.png",
        Some("heic") => "public.heic",
        Some("gif") => "com.compuserve.gif",
        Some("mov") => "com.apple.quicktime-movie",
        Some("mp4") => "public.mpeg-4",
        Some("pdf") => "com.adobe.pdf",
        Some("webloc") => "com.apple.web-internet-location",
        _ => "public.data",
    }
}

fn rand_u32() -> u32 {
    use std::time::SystemTime;
    let s = SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap_or_default();
    (s.subsec_nanos()) ^ (s.as_secs() as u32)
}
fn rand_u16() -> u16 {
    (rand_u32() & 0xFFFF) as u16
}
fn rand_u64() -> u64 {
    use std::time::SystemTime;
    let s = SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap_or_default();
    s.as_nanos() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolve_receiver_name_default_includes_pid() {
        // No --name: default receiver name is "<base>-<pid>" so the Apple
        // picker (which shows ReceiverComputerName, not the mDNS instance
        // name) distinguishes fresh runs from stale ghosts.
        let name = resolve_receiver_name(&None, "luftlift");
        assert_eq!(name, format!("luftlift-{}", std::process::id()));
    }

    #[test]
    fn resolve_receiver_name_explicit_override_uses_verbatim() {
        // --name foo: the explicit name is used as-is (no PID suffix).
        let name = resolve_receiver_name(&Some("my-mac".into()), "luftlift");
        assert_eq!(name, "my-mac");
    }

    #[test]
    fn resolve_receiver_name_empty_string_override_is_verbatim() {
        // An explicit empty --name "" is still an override (edge case).
        let name = resolve_receiver_name(&Some(String::new()), "luftlift");
        assert_eq!(name, "");
    }

    #[test]
    fn parse_socket_addr_accepts_v4_and_bracketed_v6() {
        let (addr, port) = parse_socket_addr("127.0.0.1:8771").unwrap();
        assert_eq!(addr.to_string(), "127.0.0.1");
        assert_eq!(port, 8771);
        // Link-local IPv6 needs the bracket form, otherwise rsplit_once(':')
        // would cut inside the address.
        let (addr, port) = parse_socket_addr("[fe80::1c2d:3e4f:5a6b:7c8d]:8771").unwrap();
        assert_eq!(addr.to_string(), "fe80::1c2d:3e4f:5a6b:7c8d");
        assert_eq!(port, 8771);
    }

    #[test]
    fn parse_socket_addr_rejects_garbage() {
        // A bare host without a port, a bad IP and a bad port must all fail
        // loudly rather than silently connecting somewhere unintended.
        assert!(parse_socket_addr("127.0.0.1").is_err());
        assert!(parse_socket_addr("not-an-ip:8771").is_err());
        assert!(parse_socket_addr("127.0.0.1:not-a-port").is_err());
    }

    #[test]
    fn preferred_address_picks_link_local_v6() {
        use std::net::IpAddr;
        // The AirDrop peer list feeds `send --address`, and the only address
        // reachable over awdl0 is the link-local v6. A peer advertising a
        // routable v6 as well must not be listed with it.
        let routable: IpAddr = "2001:db8::1".parse().unwrap();
        let link_local: IpAddr = "fe80::1c2d:3e4f:5a6b:7c8d".parse().unwrap();
        let v4: IpAddr = "192.0.2.5".parse().unwrap();
        assert_eq!(preferred_address(&[routable, link_local, v4]), Some(link_local));
        // No link-local → first entry (never None for a non-empty list).
        assert_eq!(preferred_address(&[v4, routable]), Some(v4));
        assert_eq!(preferred_address(&[]), None);
    }
}
