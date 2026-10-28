//! Ground-truth AWDL announce frames.
//!
//! The AWDL wire format is not documented and cannot be guessed: an Apple peer
//! only synchronises when every TLV carries the exact fields it expects. So we
//! captured a real Mac's PSF and MIF off the air and treat them as templates.
//! Identity and live scheduling fields are rewritten. Live announcements omit
//! the captured pairing-service records: those protocols are not implemented.
//!
//! Provenance of `templates/*.bin`: raw 802.11 action frames lifted verbatim
//! from `build/airdrop/driver/coexist-rx/frames-2026-10-21.log`, source
//! `02:57:dc:2d:32:26`, PSF 352 bytes / MIF 984 bytes.
//!
//! # Frame layout (verified on air, 45 frames)
//!
//! ```text
//!   0..24    802.11 action header (FC 0x00d0, addr1 broadcast, addr3 = AWDL BSSID)
//!   24..30   7f 00 17 f2 08 10        vendor category / OUI / type / subtype
//!   30       AWDL subtype (0 = PSF, 3 = MIF)
//!   31       reserved, always 0
//!   32..36   phy_tx    (u32 LE)
//!   36..40   target_tx (u32 LE)
//!   40..     TLV chain: type u8, length u16 LE, value
//! ```
//!
//! TLV order on air:
//!
//! ```text
//!   PSF: 4, 5, 18, 19, 6, 24, 12, 7, 17, 21, 33
//!   MIF: 4, 5, 18, 24, 12, 7, 17, 33, 16, 21, 6, 2, 2, ...
//! ```
//!
//! # Why a template and not a builder
//!
//! Re-deriving TLV 4 (73 bytes of sync parameters), 5 (21), 12 (47), 17 (14)
//! and 33 (14) from first principles is a research project; the fields are not
//! documented anywhere. Cloning is exact by construction and stays exact as
//! long as we only touch fields whose meaning we have measured. The fields
//! below were identified by diffing 16 frames from one peer over time: those
//! that never change are identity, those that move are counters.
//!
//! # The service TLVs are identity too
//!
//! TLV 2 carries application-service pairing records naming the *template peer*:
//! its hostname, its display name, its session id and its service-instance
//! UUID. Leaving them alone advertises the Mac back at itself, so nothing can
//! ever be discovered as us. `rewrite_tlv2` replaces them; the format is
//! documented at [`rewrite_tlv2`] and in ROADMAP §17. This rewrite is retained
//! for fixture research; [`build_link_announcement`] omits TLV 2 on live TX.

use crate::awdl::ActionSubtype;

/// PSF template: a real Mac's presence frame.
pub const PSF_TEMPLATE: &[u8] = include_bytes!("../templates/psf.bin");
/// MIF template: a real Mac's master indication frame (carries the service TLVs).
pub const MIF_TEMPLATE: &[u8] = include_bytes!("../templates/mif.bin");

/// Fixed AWDL "BSSID". Every action frame is addressed to it; it is never a peer.
pub const AWDL_BSSID: [u8; 6] = [0x00, 0x25, 0x00, 0xFF, 0x94, 0x73];

const ACTION_HDR_LEN: usize = 24;
/// Action body is 16 bytes: 6 vendor + subtype + reserved + phy_tx + target_tx.
const TLV_START: usize = ACTION_HDR_LEN + 16;
const ADDR2: usize = 10;
const SEQ_CONTROL: usize = 22;
const PHY_TX: usize = 32;
const TARGET_TX: usize = 36;

/// DNS-SD service names that mark a TLV 2 record as AirDrop's.
///
/// The hostname label sits immediately before one of these, which is the only
/// reliable way to tell it apart from a service label: both are plain
/// `<len><bytes>` DNS labels.
const SERVICE_LABELS: [&[u8]; 2] = [b"_appsvcprepair", b"_applicationservicepairing"];

const TYPE_PTR: u8 = 0x0c;
const TYPE_SRV: u8 = 0x21;
const TYPE_TXT: u8 = 0x10;

/// The parts of a frame that identify *us* rather than the peer we cloned.
#[derive(Debug, Clone)]
pub struct Identity<'a> {
    /// Our AWDL address (the interface MAC). Goes into addr2 and the data-path
    /// TLV, which describe the transmitter.
    pub self_addr: [u8; 6],
    /// The elected master of the cluster we are in. Advertised in the sync and
    /// election TLVs. Equal to `self_addr` when we are the master ourselves —
    /// claiming someone else's election without being in their cluster makes
    /// Apple peers reject the frame.
    pub master_addr: [u8; 6],
    /// Service-instance UUID. Apple puts a 36-character UUID in TLV 16 and the
    /// identical string at the tail of every SRV rdata (8 of 8 captured peers),
    /// so both are driven from this one field.
    pub uuid: &'a str,
    /// mDNS hostname, lower case. Appears in the SRV and TXT record names,
    /// immediately before the service label.
    pub host: &'a str,
    /// Display name, as shown in the peer's AirDrop panel. Appears as the PTR
    /// rdata and as `dnm=` in the TXT rdata. Apple uses title case here and
    /// lower case for the mDNS hostname, which is why both are carried.
    pub display: &'a str,
    /// AirDrop session id, `<36-char UUID>` behind `sid=` in the TXT rdata.
    pub sid: &'a str,
    /// Capability string behind `at=` in the TXT rdata, 12 hex characters.
    pub at: &'a str,
}

/// Per-frame timing. Every value here is expected to advance between frames.
#[derive(Debug, Clone, Copy)]
pub struct Timing {
    /// 802.11 sequence control, already shifted into the high 12 bits.
    pub seq_control: u16,
    /// Low 32 bits of the TX timestamp.
    pub phy_tx: u32,
    /// Target TX timestamp.
    pub target_tx: u32,
    /// Availability-window counter; advances once per AW.
    pub aw_counter: u16,
    /// Countdown to the next EAW, in TU. This is NOT the AW sequence counter.
    pub time_to_next_aw_tu: u16,
    /// The master's election counter, mirrored from the election state so a
    /// peer can tell how fresh our view of the cluster is.
    pub master_counter: u32,
}

impl Timing {
    /// Derive a self-consistent set from a monotonic microsecond clock.
    pub fn from_now_us(now_us: u64, seq: u16) -> Self {
        // AW is 16 TU ≈ 16.384 ms; TU is 1024 µs.
        let aw_counter = ((now_us / (16 * 1024)) & 0xffff) as u16;
        Timing {
            seq_control: seq.wrapping_shl(4),
            phy_tx: now_us as u32,
            target_tx: now_us as u32,
            aw_counter,
            time_to_next_aw_tu: (64 - (now_us / 1024) % 64) as u16,
            master_counter: 0,
        }
    }
}

#[derive(Debug, Clone, Copy)]
struct TlvSpan {
    kind: u8,
    value_start: usize,
    len: usize,
}

/// Walk the TLV chain of a template. Returns `None` if the chain does not
/// parse, which means the template was edited into something that is no longer
/// a real Apple frame.
fn spans(frame: &[u8]) -> Option<Vec<TlvSpan>> {
    if frame.len() < TLV_START {
        return None;
    }
    let mut out = Vec::new();
    let mut off = TLV_START;
    while frame.len() - off >= 3 {
        let kind = frame[off];
        let len = usize::from(frame[off + 1]) | (usize::from(frame[off + 2]) << 8);
        if len > frame.len() - off - 3 {
            return None;
        }
        out.push(TlvSpan {
            kind,
            value_start: off + 3,
            len,
        });
        off += 3 + len;
    }
    Some(out)
}

// ---------------------------------------------------------------------------
// TLV 2: the AirDrop mDNS service records
// ---------------------------------------------------------------------------

/// Split a TLV 2 payload into record bodies plus its trailing bytes.
///
/// Measured on nine MIFs from eight different Apple peers: every TLV 2 payload
/// ends with two bytes that the last record's declared length does not count.
/// The last record's body therefore runs two bytes past its `u16` length, and
/// those two bytes are exactly this tail (they are the last two characters of
/// `dnm=`/`sn=`, or a dangling compression pointer on PTR/SRV records). It is
/// part of the wire format, so it is preserved rather than "fixed".
fn parse_records(v: &[u8]) -> (Vec<&[u8]>, &[u8]) {
    let mut recs = Vec::new();
    let mut off = 0usize;
    while off + 2 <= v.len() {
        let ln = usize::from(v[off]) | (usize::from(v[off + 1]) << 8);
        if off + 2 + ln > v.len() {
            break;
        }
        recs.push(&v[off + 2..off + 2 + ln]);
        off += 2 + ln;
    }
    (recs, &v[off..])
}

/// Leading DNS labels of a record body.
///
/// Returns `(offset_of_length_byte, label_len)` for each label and the offset
/// just past the terminating root label or compression pointer. The `Option`
/// is `None` if the name runs off the end of the body.
fn split_name(b: &[u8]) -> (Vec<(usize, usize)>, Option<usize>) {
    let mut out = Vec::new();
    let mut i = 0usize;
    while i < b.len() {
        let n = b[i];
        if n == 0 {
            return (out, Some(i + 1));
        }
        if (n & 0xc0) == 0xc0 {
            return (out, Some(i + 2));
        }
        let n = usize::from(n);
        if i + 1 + n > b.len() {
            return (out, None);
        }
        out.push((i, n));
        i += 1 + n;
    }
    (out, Some(b.len()))
}

fn label(b: &[u8], at: (usize, usize)) -> &[u8] {
    &b[at.0 + 1..at.0 + 1 + at.1]
}

/// A name record is a DNS name followed by a compression pointer and a type.
fn is_name_record(b: &[u8]) -> bool {
    if b.len() < 3 {
        return false;
    }
    match split_name(b).1 {
        Some(end) if end == b.len() - 1 => (b[end - 2] & 0xc0) == 0xc0,
        _ => false,
    }
}

fn is_service_label(l: &[u8]) -> bool {
    SERVICE_LABELS.contains(&l)
}

/// Replace the hostname label of a SRV/TXT record name.
///
/// The hostname is the label immediately before a service label. Matching on
/// position rather than on content matters: an earlier version looked for a
/// label whose first nine bytes were non-ASCII, which mis-identified
/// `_applicationservicepairing` itself as a hostname and left the rewrite
/// half-done.
fn rewrite_name_record(body: &[u8], host: &[u8]) -> Vec<u8> {
    if host.is_empty() || host.len() > 60 {
        return body.to_vec();
    }
    let (labels, _) = split_name(body);
    if labels.len() < 2 {
        return body.to_vec();
    }
    for i in (0..labels.len()).rev() {
        if !is_service_label(label(body, labels[i])) {
            continue;
        }
        if i == 0 {
            return body.to_vec();
        }
        let (off, n) = labels[i - 1];
        let mut out = Vec::with_capacity(body.len());
        out.extend_from_slice(&body[..off]);
        out.push(host.len() as u8);
        out.extend_from_slice(host);
        out.extend_from_slice(&body[off + 1 + n..]);
        return out;
    }
    body.to_vec()
}

/// `<prefix><value>`, for the TXT strings that keep a fixed key.
fn prefixed(prefix: &[u8], value: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(prefix.len() + value.len());
    out.extend_from_slice(prefix);
    out.extend_from_slice(value.as_bytes());
    out
}

/// A 36-character UUID in its textual form.
fn is_uuid_text(b: &[u8]) -> bool {
    b.len() == 36
        && b[8] == b'-'
        && b[13] == b'-'
        && b[18] == b'-'
        && b[23] == b'-'
        && b.iter().enumerate().all(|(i, c)| {
            matches!(i, 8 | 13 | 18 | 23) || c.is_ascii_hexdigit()
        })
}

/// Rewrite the identity inside one rdata record.
///
/// `service` says whether the paired name record carried one of the two
/// AirDrop service labels. A MIF's TLV 2 chain also carries other services
/// (a bare-pointer PTR whose rdata is a 12-byte device label, and TXT records
/// with `flags=`); those names are not ours to change.
fn rewrite_rdata(body: &[u8], kind: u8, id: &Identity, service: bool) -> Vec<u8> {
    if body.len() < 2 || body[0] != 0 || body[1] != 0 || !service {
        return body.to_vec();
    }
    let rd = &body[2..];
    match kind {
        TYPE_PTR => {
            // The whole rdata is a single DNS label: the display name.
            if id.display.is_empty() || id.display.len() > 60 {
                return body.to_vec();
            }
            let (labels, _) = split_name(rd);
            if labels.len() != 1 {
                return body.to_vec();
            }
            let (off, n) = labels[0];
            let mut out = Vec::with_capacity(body.len());
            out.extend_from_slice(&body[..2]);
            out.push(id.display.len() as u8);
            out.extend_from_slice(id.display.as_bytes());
            out.extend_from_slice(&rd[off + 1 + n..]);
            out
        }
        TYPE_TXT => {
            // A sequence of <len><string>; only sid=/at=/dnm= carry identity.
            let mut out = Vec::with_capacity(body.len());
            out.extend_from_slice(&body[..2]);
            let mut i = 0usize;
            while i < rd.len() {
                let n = usize::from(rd[i]);
                if i + 1 + n > rd.len() {
                    // The tail string runs into the payload's trailing bytes,
                    // which `rewrite_tlv2` has already folded back in. If it
                    // is still short, copy it verbatim rather than guess.
                    out.extend_from_slice(&rd[i..]);
                    break;
                }
                let s = &rd[i + 1..i + 1 + n];
                // The key stays; only the value behind it is ours.
                let swap: Option<Vec<u8>> = if s.starts_with(b"sid=") && !id.sid.is_empty() {
                    Some(prefixed(b"sid=", id.sid))
                } else if s.starts_with(b"at=") && !id.at.is_empty() {
                    Some(prefixed(b"at=", id.at))
                } else if s.starts_with(b"dnm=") && !id.display.is_empty() {
                    Some(prefixed(b"dnm=", id.display))
                } else {
                    None
                };
                match swap {
                    Some(r) if r.len() <= 255 => {
                        out.push(r.len() as u8);
                        out.extend_from_slice(&r);
                    }
                    _ => {
                        out.push(n as u8);
                        out.extend_from_slice(s);
                    }
                }
                i += 1 + n;
            }
            out
        }
        TYPE_SRV => {
            // ... <u8 36> <36-char UUID>, byte-identical to TLV 16. Find it by
            // shape rather than by offset so a layout change upstream cannot
            // silently leave the template peer's UUID in place.
            let new = id.uuid.as_bytes();
            if !is_uuid_text(new) {
                return body.to_vec();
            }
            for start in 0..=rd.len().saturating_sub(36) {
                if !is_uuid_text(&rd[start..start + 36]) {
                    continue;
                }
                let mut out = Vec::with_capacity(body.len());
                out.extend_from_slice(&body[..2 + start]);
                out.extend_from_slice(new);
                out.extend_from_slice(&rd[start + 36..]);
                return out;
            }
            body.to_vec()
        }
        _ => body.to_vec(),
    }
}

/// Rebuild one TLV 2 payload with our identity.
///
/// ```text
/// payload := <record>* <2 bytes>
/// record  := <u16 LE L> <body of L bytes>
///
/// name    := <labels...> <c0 xx> <type u8>     PTR 0x0c / SRV 0x21 / TXT 0x10
/// rdata   := <u16 0x0000> <rdata>
/// ```
///
/// Records come in pairs, a name record followed by its rdata. Returns `None`
/// only if the payload cannot be re-emitted consistently.
fn rewrite_tlv2(v: &[u8], id: &Identity) -> Option<Vec<u8>> {
    let (recs, tail) = parse_records(v);
    if recs.is_empty() {
        return Some(v.to_vec());
    }
    let has_tail = !tail.is_empty();

    let mut bodies: Vec<Vec<u8>> = recs.iter().map(|b| b.to_vec()).collect();
    if has_tail {
        let last = bodies.len() - 1;
        bodies[last].extend_from_slice(tail);
    }

    let mut rewritten: Vec<Vec<u8>> = Vec::with_capacity(bodies.len());
    let mut cur_type = 0u8;
    let mut service = false;
    for body in &bodies {
        if is_name_record(body) {
            cur_type = *body.last().expect("non-empty record");
            let (labels, _) = split_name(body);
            service = labels.iter().any(|&l| is_service_label(label(body, l)));
            rewritten.push(rewrite_name_record(body, id.host.as_bytes()));
        } else {
            rewritten.push(rewrite_rdata(body, cur_type, id, service));
        }
    }

    let mut out = Vec::with_capacity(v.len() + 8);
    for (i, body) in rewritten.iter().enumerate() {
        let last = i == rewritten.len() - 1;
        let declared = if last && has_tail {
            body.len().checked_sub(2)?
        } else {
            body.len()
        };
        if declared > u16::MAX as usize {
            return None;
        }
        out.extend_from_slice(&(declared as u16).to_le_bytes());
        out.extend_from_slice(body);
    }
    Some(out)
}

/// `03 <len> <name> c0 0c`: the TLV 16 ARPA name.
fn arpa_value(id: &Identity) -> Option<Vec<u8>> {
    let name = id.uuid.as_bytes();
    if name.len() > 255 {
        return None;
    }
    let mut value = Vec::with_capacity(4 + name.len());
    value.push(0x03);
    value.push(name.len() as u8);
    value.extend_from_slice(name);
    value.extend_from_slice(&[0xc0, 0x0c]);
    Some(value)
}

/// Replay a captured PSF or MIF with identity patched in, for fixture research.
/// Live senders must use [`build_link_announcement`]: the captured service
/// records advertise pairing protocols and ports this implementation lacks.
///
/// Returns `None` only if the embedded template is malformed or the identity
/// cannot be encoded, which is a build error rather than a runtime condition.
pub fn build(subtype: ActionSubtype, id: &Identity, timing: &Timing) -> Option<Vec<u8>> {
    let template = match subtype {
        ActionSubtype::Psf => PSF_TEMPLATE,
        ActionSubtype::Mif => MIF_TEMPLATE,
    };
    let parsed = spans(template)?;
    let mut frame = template.to_vec();

    // --- 802.11 header ---------------------------------------------------
    frame[ADDR2..ADDR2 + 6].copy_from_slice(&id.self_addr);
    frame[SEQ_CONTROL..SEQ_CONTROL + 2].copy_from_slice(&timing.seq_control.to_le_bytes());
    frame[PHY_TX..PHY_TX + 4].copy_from_slice(&timing.phy_tx.to_le_bytes());
    frame[TARGET_TX..TARGET_TX + 4].copy_from_slice(&timing.target_tx.to_le_bytes());

    // --- TLV chain: patch each value, then re-emit ------------------------
    // Rebuilding the whole chain rather than splicing in place is what keeps
    // this correct: changing the hostname moves six TLV 2 records, so every
    // span after the first edit would otherwise be stale.
    let mut out = Vec::with_capacity(frame.len() + 16);
    out.extend_from_slice(&frame[..TLV_START]);
    let mut chain_end = TLV_START;
    for span in &parsed {
        let mut value = frame[span.value_start..span.value_start + span.len].to_vec();
        let vlen = value.len();
        let v = value.as_mut_slice();
        match span.kind {
            // TLV 4: sync parameters. Byte 0 is the next AW channel and byte 3
            // the master channel — both are the peer's anchor and stay as-is.
            // 1..3 is the countdown in TU, 21..27 the master address, 29..31 the
            // AW sequence number.
            4 if vlen >= 31 => {
                v[1..3].copy_from_slice(&timing.time_to_next_aw_tu.to_le_bytes());
                v[21..27].copy_from_slice(&id.master_addr);
                v[29..31].copy_from_slice(&timing.aw_counter.to_le_bytes());
            }
            // TLV 5: election parameters. flags(1) id(2) distancetop(1)
            // unknown(1) master_addr(6) master_metric(2) reserved(2)
            // self_metric(2) reserved(4).
            5 if vlen >= 21 => {
                v[5..11].copy_from_slice(&id.master_addr);
            }
            // TLV 24: election parameters v2. master_addr(6) sync_addr(6)
            // master_counter(4) distance(4) master_metric(4) self_metric(4)
            // unknown(4) reserved(4) self_counter(4).
            24 if vlen >= 40 => {
                v[0..6].copy_from_slice(&id.master_addr);
                v[6..12].copy_from_slice(&id.master_addr);
                v[12..16].copy_from_slice(&timing.master_counter.to_le_bytes());
                v[36..40].copy_from_slice(&u32::from(timing.aw_counter).to_le_bytes());
            }
            // TLV 12: data path state. Carries our address at value offset 15.
            12 if vlen >= 21 => {
                v[15..21].copy_from_slice(&id.self_addr);
            }
            // TLV 16: the ARPA name, which is the service-instance UUID.
            16 => value = arpa_value(id)?,
            // TLV 2: the AirDrop mDNS service records.
            2 => value = rewrite_tlv2(&value, id)?,
            _ => {}
        }
        out.push(span.kind);
        if value.len() > u16::MAX as usize {
            return None;
        }
        out.extend_from_slice(&(value.len() as u16).to_le_bytes());
        out.extend_from_slice(&value);
        chain_end = span.value_start + span.len;
    }
    // Whatever trails the chain (two dangling bytes on the PSF template).
    out.extend_from_slice(&frame[chain_end..]);
    let frame = out;

    debug_assert_eq!(&frame[24..30], &[0x7f, 0x00, 0x17, 0xf2, 0x08, 0x10]);
    debug_assert_eq!(&frame[16..22], &AWDL_BSSID);
    debug_assert_eq!(&frame[ADDR2..ADDR2 + 6], &id.self_addr);
    Some(frame)
}

/// Build the live link announcement without the template Mac's service records.
/// TLV 2 contains _appsvcprepair/_applicationservicepairing on captured ports
/// 53870/53869. Renaming these records does not implement those protocols.
/// Our implemented AirDrop HTTPS service is announced separately over mDNS.
pub fn build_link_announcement(
    subtype: ActionSubtype,
    id: &Identity,
    timing: &Timing,
) -> Option<Vec<u8>> {
    let frame = build(subtype, id, timing)?;
    let parsed = spans(&frame)?;
    let mut out = frame[..TLV_START].to_vec();
    let mut chain_end = TLV_START;
    for span in parsed {
        let end = span.value_start + span.len;
        if span.kind != 2 {
            out.extend_from_slice(&frame[span.value_start - 3..end]);
        }
        chain_end = end;
    }
    out.extend_from_slice(&frame[chain_end..]);
    Some(out)
}

/// Replace the captured peer's scheduling/election values with our live state.
/// Layout: OWL frame.h / tx.c, awdl_init_sync_params_tlv and
/// awdl_init_election_params_v2_tlv. Does not add service records.
pub fn apply_runtime_state(frame: &mut [u8], state: &crate::state::AwdlState) -> Option<()> {
    let parsed = spans(frame)?;
    let mut sequence = [0u8; 38];
    // 16 entries, opclass encoding, four AWs per entry, repeat-current fill.
    sequence[..6].copy_from_slice(&[15, 3, 0, 3, 0xff, 0xff]);
    for (slot, channel) in state.channel_sequence.iter().enumerate() {
        sequence[6 + slot * 2..8 + slot * 2].copy_from_slice(channel);
    }
    for span in parsed {
        let v = &mut frame[span.value_start..span.value_start + span.len];
        match span.kind {
            4 if v.len() >= 73 => {
                v[0] = if state.current_channel == 0 { state.anchor_channel as u8 } else { state.current_channel };
                v[3] = if state.master_anchor == 0 { state.anchor_channel as u8 } else { state.master_anchor };
                v[5..7].copy_from_slice(&state.sync.aw_period_tu.to_le_bytes());
                v[11..13].copy_from_slice(&state.sync.aw_period_tu.to_le_bytes());
                v[13..15].copy_from_slice(&state.sync.aw_period_tu.to_le_bytes());
                let next = u16::from_le_bytes(v[1..3].try_into().ok()?);
                let elapsed = state.sync.presence_mode * state.sync.aw_period_tu - next;
                v[15..17].copy_from_slice(&state.sync.aw_period_tu.saturating_sub(elapsed).to_le_bytes());
                v[17..21].fill((state.sync.presence_mode - 1) as u8);
                v[27] = state.sync.presence_mode as u8;
                let aw_counter = [v[29], v[30]];
                v[31..33].copy_from_slice(&aw_counter);
                v[33..71].copy_from_slice(&sequence);
                v[71..73].fill(0);
            }
            18 if v.len() == 41 => {
                v[..38].copy_from_slice(&sequence);
                v[38..].fill(0);
            }
            5 if v.len() >= 21 => {
                v[3] = state.election.height as u8;
                v[11..15].copy_from_slice(&state.election.master_metric.to_le_bytes());
                v[15..19].copy_from_slice(&state.election.self_metric.to_le_bytes());
            }
            24 if v.len() >= 40 => {
                v[6..12].copy_from_slice(&state.election.sync_addr);
                v[16..20].copy_from_slice(&state.election.height.to_le_bytes());
                v[20..24].copy_from_slice(&state.election.master_metric.to_le_bytes());
                v[24..28].copy_from_slice(&state.election.self_metric.to_le_bytes());
                v[36..40].copy_from_slice(&state.election.self_counter.to_le_bytes());
            }
            _ => {}
        }
    }
    Some(())
}

/// The TLV kinds present in a template, in wire order. Used by tests and by
/// `awdl-frame-dump.py` cross-checks.
pub fn tlv_kinds(subtype: ActionSubtype) -> Vec<u8> {
    let template = match subtype {
        ActionSubtype::Psf => PSF_TEMPLATE,
        ActionSubtype::Mif => MIF_TEMPLATE,
    };
    spans(template)
        .map(|s| s.into_iter().map(|t| t.kind).collect())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The template peer's identity, taken off the wire. None of it may
    /// survive a rewrite.
    const TEMPLATE_HOST: &[u8] = b"\xe7\x84\xb1\xe6\x9e\x97\xe7\x9a\x84macbook";
    const TEMPLATE_DISPLAY: &[u8] = b"\xe7\x84\xb1\xe6\x9e\x97\xe7\x9a\x84MacBook";
    const TEMPLATE_SID: &[u8] = b"3D78B4B3-62F3-4EA6-AD57-452577C498B2";
    const TEMPLATE_AT: &[u8] = b"at=217213dfa91a";
    const TEMPLATE_UUID: &[u8] = b"b4fb8f9f-5a19-485b-86d4-1b497dc19d38";

    fn identity() -> Identity<'static> {
        Identity {
            self_addr: [0x02, 0x11, 0x22, 0x33, 0x44, 0x55],
            master_addr: [0x02, 0x11, 0x22, 0x33, 0x44, 0x55],
            uuid: "8dd0d393-aba1-4727-b338-75b9f049a66a",
            host: "marionette",
            display: "Marionette",
            sid: "2C9D473F-65EB-4AB7-82C7-757E4B8D06B9",
            at: "4656d6de6b8a",
        }
    }

    fn contains(haystack: &[u8], needle: &[u8]) -> bool {
        haystack.windows(needle.len()).any(|w| w == needle)
    }

    #[test]
    fn live_announcements_omit_unimplemented_pairing_services() {
        let timing = Timing {
            seq_control: 0x120,
            phy_tx: 42,
            target_tx: 42,
            aw_counter: 16,
            time_to_next_aw_tu: 32,
            master_counter: 1,
        };
        for subtype in [ActionSubtype::Psf, ActionSubtype::Mif] {
            let replay = build(subtype, &identity(), &timing).unwrap();
            let live = build_link_announcement(subtype, &identity(), &timing).unwrap();
            assert_eq!(&live[..TLV_START], &replay[..TLV_START]);
            let expected: Vec<_> = spans(&replay).unwrap().into_iter()
                .filter(|s| s.kind != 2)
                .map(|s| (s.kind, replay[s.value_start..s.value_start + s.len].to_vec()))
                .collect();
            let actual: Vec<_> = spans(&live).unwrap().into_iter()
                .map(|s| (s.kind, live[s.value_start..s.value_start + s.len].to_vec()))
                .collect();
            assert_eq!(actual, expected);
            assert!(!contains(&live, b"_appsvcprepair"));
            assert!(!contains(&live, b"_applicationservicepairing"));
        }
        // Captured SRV port 0xd26e is exactly the 53870 targeted by the
        // real Mac SYNs in the 2026-10-26 receive capture.
        assert!(contains(MIF_TEMPLATE, &[0xd2, 0x6e]));
    }

    /// Recover the identity a built frame advertises, the same way a parser
    /// would, so tests assert on meaning rather than on offsets.
    type IdentityParts = (Option<Vec<u8>>, Option<Vec<u8>>, Vec<Vec<u8>>);

    fn read_identity(frame: &[u8]) -> IdentityParts {
        let mut host = None;
        let mut uuid = None;
        let mut txt = Vec::new();
        for span in spans(frame).expect("chain parses") {
            let value = &frame[span.value_start..span.value_start + span.len];
            if span.kind == 16 {
                uuid = Some(value[2..2 + usize::from(value[1])].to_vec());
            }
            if span.kind != 2 {
                continue;
            }
            let (recs, tail) = parse_records(value);
            let mut bodies: Vec<Vec<u8>> = recs.iter().map(|b| b.to_vec()).collect();
            if let (true, Some(last)) = (!tail.is_empty(), bodies.len().checked_sub(1)) {
                bodies[last].extend_from_slice(tail);
            }
            let mut cur = 0u8;
            for body in &bodies {
                if is_name_record(body) {
                    cur = *body.last().unwrap();
                    let (labels, _) = split_name(body);
                    if labels.len() >= 2 && is_service_label(label(body, labels[1])) {
                        host = Some(label(body, labels[0]).to_vec());
                    }
                } else if cur == TYPE_TXT && body.len() >= 2 {
                    let rd = &body[2..];
                    let mut i = 0;
                    while i < rd.len() {
                        let n = usize::from(rd[i]);
                        if i + 1 + n > rd.len() {
                            break;
                        }
                        txt.push(rd[i + 1..i + 1 + n].to_vec());
                        i += 1 + n;
                    }
                }
            }
        }
        (host, uuid, txt)
    }

    #[test]
    fn a_synced_peer_advertises_its_master_not_itself() {
        let me = [0x02, 0x11, 0x22, 0x33, 0x44, 0x55];
        let master = [0x02, 0xaa, 0xbb, 0xcc, 0xdd, 0xee];
        let id = Identity {
            master_addr: master,
            host: "h",
            ..identity()
        };
        let mut t = Timing::from_now_us(0, 0);
        t.master_counter = 77;
        let frame = build(ActionSubtype::Psf, &id, &t).unwrap();

        // addr2 and the data-path TLV describe the transmitter...
        assert_eq!(&frame[10..16], &me);
        let parsed = spans(&frame).unwrap();
        let dp = parsed.iter().find(|s| s.kind == 12).unwrap();
        assert_eq!(
            &frame[dp.value_start + 15..dp.value_start + 21],
            &me,
            "data path TLV carries our address"
        );
        let sync = parsed.iter().find(|s| s.kind == 4).unwrap();
        assert_eq!(&frame[sync.value_start + 21..sync.value_start + 27], &master);
        let ev2 = parsed.iter().find(|s| s.kind == 24).unwrap();
        assert_eq!(&frame[ev2.value_start..ev2.value_start + 6], &master);
        assert_eq!(
            u32::from_le_bytes(
                frame[ev2.value_start + 12..ev2.value_start + 16]
                    .try_into()
                    .unwrap()
            ),
            77
        );
    }

    #[test]
    fn templates_are_real_apple_frames() {
        for t in [PSF_TEMPLATE, MIF_TEMPLATE] {
            assert_eq!(&t[0..2], &[0xd0, 0x00], "action frame");
            assert_eq!(&t[16..22], &AWDL_BSSID, "addr3 is the AWDL BSSID");
            assert_eq!(&t[24..30], &[0x7f, 0x00, 0x17, 0xf2, 0x08, 0x10]);
            assert!(spans(t).is_some(), "TLV chain parses to the last byte");
        }
    }

    /// Ground truth captured on air; a change here means the template changed,
    /// not that the code improved.
    #[test]
    fn tlv_order_matches_the_air() {
        assert_eq!(
            tlv_kinds(ActionSubtype::Psf),
            vec![4, 5, 18, 19, 6, 24, 12, 7, 17, 21, 33]
        );
        let mif = tlv_kinds(ActionSubtype::Mif);
        assert_eq!(&mif[..11], &[4, 5, 18, 24, 12, 7, 17, 33, 16, 21, 6]);
        assert!(
            mif[11..17].iter().all(|k| *k == 2),
            "AirDrop service TLVs trail the MIF"
        );
    }

    #[test]
    fn psf_carries_our_identity() {
        let id = identity();
        let t = Timing::from_now_us(1_234_567, 7);
        let frame = build(ActionSubtype::Psf, &id, &t).expect("template parses");

        assert_eq!(&frame[10..16], &id.self_addr, "addr2 is us");
        assert_eq!(&frame[16..22], &AWDL_BSSID, "addr3 stays the AWDL BSSID");
        assert_eq!(frame[30], 0, "PSF subtype");
        assert_eq!(
            u16::from_le_bytes([frame[22], frame[23]]) >> 4,
            7,
            "sequence number round-trips"
        );

        // Every place the template used to carry the Mac's address now
        // carries ours, and the Mac's address is gone entirely.
        let mac = [0x02, 0x57, 0xdc, 0x2d, 0x32, 0x26];
        assert!(
            !frame.windows(6).any(|w| w == mac),
            "no leftover peer identity"
        );
        let hits = frame.windows(6).filter(|w| *w == id.self_addr).count();
        assert_eq!(
            hits, 6,
            "addr2 + TLV 4/5/24 masters + TLV 24 sync addr + TLV 12 data path"
        );
    }

    /// The MIF is where discovery happens, so this is the test that matters:
    /// the service records have to name us, not the peer we cloned.
    #[test]
    fn mif_service_records_carry_our_identity() {
        let id = identity();
        let t = Timing::from_now_us(9_000_000, 1);
        let frame = build(ActionSubtype::Mif, &id, &t).expect("template parses");

        assert_eq!(frame[30], 3, "MIF subtype");
        let kinds = spans(&frame)
            .expect("rebuilt chain parses")
            .into_iter()
            .map(|s| s.kind)
            .collect::<Vec<_>>();
        assert_eq!(kinds, tlv_kinds(ActionSubtype::Mif), "order preserved");

        let (host, uuid, txt) = read_identity(&frame);
        assert_eq!(host.as_deref(), Some(id.host.as_bytes()), "mDNS hostname");
        assert_eq!(uuid.as_deref(), Some(id.uuid.as_bytes()), "TLV 16 UUID");
        assert!(
            txt.iter().any(|s| s == b"sid=2C9D473F-65EB-4AB7-82C7-757E4B8D06B9"),
            "sid= is ours, got {:?}",
            txt.iter().map(|s| String::from_utf8_lossy(s)).collect::<Vec<_>>()
        );
        assert!(txt.iter().any(|s| s == b"at=4656d6de6b8a"), "at= is ours");
        assert!(
            txt.iter().any(|s| s == b"dnm=Marionette"),
            "dnm= is ours"
        );

        // Nothing of the template peer may survive.
        for (what, needle) in [
            ("hostname", TEMPLATE_HOST),
            ("display name", TEMPLATE_DISPLAY),
            ("session id", TEMPLATE_SID),
            ("capability", TEMPLATE_AT),
            ("service uuid", TEMPLATE_UUID),
        ] {
            assert!(
                !contains(&frame, needle),
                "template {what} {:?} survived",
                String::from_utf8_lossy(needle)
            );
        }
    }

    /// Every TLV 2 payload keeps the two-byte tail real Apple frames carry.
    #[test]
    fn mif_service_tlv2_payloads_keep_the_two_byte_tail() {
        let id = identity();
        let t = Timing::from_now_us(0, 0);
        let frame = build(ActionSubtype::Mif, &id, &t).expect("template parses");
        let mut checked = 0;
        for span in spans(&frame).expect("chain parses") {
            if span.kind != 2 {
                continue;
            }
            let value = &frame[span.value_start..span.value_start + span.len];
            let (recs, tail) = parse_records(value);
            assert!(!recs.is_empty(), "TLV 2 has records");
            assert_eq!(tail.len(), 2, "two trailing bytes, got {tail:?}");
            // The tail is what the last record's length left out, so folding
            // it back in must make every record well-formed.
            let mut bodies: Vec<Vec<u8>> = recs.iter().map(|b| b.to_vec()).collect();
            let last = bodies.len() - 1;
            bodies[last].extend_from_slice(tail);
            checked += 1;
        }
        assert!(checked >= 6, "the MIF carries six service TLVs, saw {checked}");
    }

    /// Rewriting the template with the identity it already carries must be a
    /// no-op. This is the regression guard for the TLV 2 walker: it pins the
    /// record splitter, the name splitter and the re-emitter together.
    #[test]
    fn rewriting_the_template_with_its_own_identity_changes_nothing() {
        // The template's own timing, or the header/TLV-4 patches would show up
        // as differences that have nothing to do with the service records.
        let parsed = spans(MIF_TEMPLATE).expect("template parses");
        let tlv4 = parsed.iter().find(|s| s.kind == 4).expect("TLV 4");
        let tlv24 = parsed.iter().find(|s| s.kind == 24).expect("TLV 24");
        let t = Timing {
            seq_control: u16::from_le_bytes([MIF_TEMPLATE[22], MIF_TEMPLATE[23]]),
            phy_tx: u32::from_le_bytes(MIF_TEMPLATE[32..36].try_into().unwrap()),
            target_tx: u32::from_le_bytes(MIF_TEMPLATE[36..40].try_into().unwrap()),
            aw_counter: u16::from_le_bytes([
                MIF_TEMPLATE[tlv4.value_start + 29],
                MIF_TEMPLATE[tlv4.value_start + 30],
            ]),
            time_to_next_aw_tu: u16::from_le_bytes([
                MIF_TEMPLATE[tlv4.value_start + 1],
                MIF_TEMPLATE[tlv4.value_start + 2],
            ]),
            master_counter: u32::from_le_bytes(
                MIF_TEMPLATE[tlv24.value_start + 12..tlv24.value_start + 16]
                    .try_into()
                    .unwrap(),
            ),
        };

        let own = Identity {
            self_addr: MIF_TEMPLATE[10..16].try_into().unwrap(),
            master_addr: MIF_TEMPLATE[tlv4.value_start + 21..tlv4.value_start + 27]
                .try_into()
                .unwrap(),
            uuid: "b4fb8f9f-5a19-485b-86d4-1b497dc19d38",
            host: std::str::from_utf8(TEMPLATE_HOST).unwrap(),
            display: std::str::from_utf8(TEMPLATE_DISPLAY).unwrap(),
            sid: std::str::from_utf8(TEMPLATE_SID).unwrap(),
            at: "217213dfa91a",
        };
        let frame = build(ActionSubtype::Mif, &own, &t).expect("template parses");
        assert_eq!(
            frame.len(),
            MIF_TEMPLATE.len(),
            "a self-rewrite must not change the frame length"
        );
        // Compare the service TLVs byte for byte. The rest of the frame cannot
        // match: TLV 4 and TLV 24 carry counters that are patched on purpose,
        // so a full-frame comparison would be testing the clock, not the
        // rewrite.
        let built = spans(&frame).expect("chain parses");
        let orig = spans(MIF_TEMPLATE).expect("chain parses");
        let b2: Vec<_> = built.iter().filter(|s| s.kind == 2).collect();
        let o2: Vec<_> = orig.iter().filter(|s| s.kind == 2).collect();
        assert_eq!(b2.len(), o2.len(), "same number of service TLVs");
        for (i, (b, o)) in b2.iter().zip(o2.iter()).enumerate() {
            assert_eq!(b.len, o.len, "TLV 2 #{i} changed length");
            let bv = &frame[b.value_start..b.value_start + b.len];
            let ov = &MIF_TEMPLATE[o.value_start..o.value_start + o.len];
            assert_eq!(bv, ov, "TLV 2 #{i} is not byte-identical");
        }
        let b16 = built.iter().find(|s| s.kind == 16).expect("TLV 16");
        let o16 = orig.iter().find(|s| s.kind == 16).expect("TLV 16");
        assert_eq!(
            &frame[b16.value_start..b16.value_start + b16.len],
            &MIF_TEMPLATE[o16.value_start..o16.value_start + o16.len],
            "TLV 16 is not byte-identical"
        );
    }

    #[test]
    fn mif_leaves_non_airdrop_services_alone() {
        // A name record that is a bare compression pointer (no labels) plus a
        // PTR rdata holding a 12-byte device label, as mif_1184 carries. It is
        // not one of our services, so its rdata must not be touched.
        let rdata = b"\x00\x00\x0cd0550ba77089";
        let out = rewrite_rdata(rdata, TYPE_PTR, &identity(), false);
        assert_eq!(out, rdata);
        // ...and the same record with the service flag set *is* rewritten,
        // which is what proves the flag is doing the work.
        let out = rewrite_rdata(rdata, TYPE_PTR, &identity(), true);
        assert_ne!(out, rdata);
        assert_eq!(&out[..2], b"\x00\x00");
        assert_eq!(out[2] as usize, "Marionette".len());
    }

    #[test]
    fn rebuilt_frame_length_tracks_the_name() {
        let short = Identity {
            self_addr: [1, 2, 3, 4, 5, 6],
            master_addr: [1, 2, 3, 4, 5, 6],
            host: "a",
            display: "A",
            ..identity()
        };
        let long = Identity {
            self_addr: [1, 2, 3, 4, 5, 6],
            master_addr: [1, 2, 3, 4, 5, 6],
            host: &"x".repeat(36),
            display: &"X".repeat(36),
            ..identity()
        };
        let t = Timing::from_now_us(0, 0);
        let a = build(ActionSubtype::Mif, &short, &t).unwrap();
        let b = build(ActionSubtype::Mif, &long, &t).unwrap();
        assert!(b.len() > a.len(), "a longer hostname grows the frame");
        assert!(spans(&a).is_some() && spans(&b).is_some());
        // The name is carried in seven places: four SRV/TXT record names, two
        // PTR rdata labels, and one `dnm=` TXT string.
        assert_eq!(b.len() - a.len(), 7 * 35);
    }
}
