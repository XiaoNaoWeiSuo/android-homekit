/* SPDX-License-Identifier: GPL-3.0-only
 * awdl-frame.h - the AWDL monitor data frame shape shared by
 * native/airdrop/tools/awdl-raw-tx.c and awdl-raw-tx-test.c.
 *
 * The shape is a contract between two implementations:
 *
 *   driver side   qcacld-3.0 hdd_mon_awdl_start_xmit() (0003/0034), which
 *                 rejects everything that deviates from it;
 *   filin side    crates/filin-rs radiotap::build_tx_header() and
 *                 encapsulate_data_frame(), which produce it on the wire.
 *
 * Changing a constant here without changing both sides silently turns every
 * submission into a rejection, so awdl-raw-tx-test.c re-checks every gate
 * condition the driver applies.
 */
#ifndef AWDL_FRAME_H
#define AWDL_FRAME_H

#include <stdint.h>
#include <string.h>

/* qcacld-3.0 hdd_mon_awdl_start_xmit(): awdl_bssid */
static const uint8_t awdl_frame_bssid[6] = {0x00, 0x25, 0x00, 0xff, 0x94, 0x73};
/* qcacld-3.0 hdd_mon_awdl_start_xmit(): awdl_snap (LLC/SNAP + AWDL_DATA_HEAD) */
static const uint8_t awdl_frame_snap[10] = {0xaa, 0xaa, 0x03, 0x00, 0x17, 0xf2,
                                            0x08, 0x00, 0x03, 0x04};
/* qcacld-3.0 hdd_mon_awdl_start_xmit(): radiotap_prefix */
static const uint8_t awdl_frame_radiotap_prefix[10] = {
    0x00, 0x00, 0x0d, 0x00, 0x04, 0x80, 0x02, 0x00, 0x18, 0x00};

#define AWDL_FRAME_RT_LEN 13
#define AWDL_FRAME_80211_LEN 24
#define AWDL_FRAME_BODY_HEADER 16 /* LLC/SNAP + head + seq + pad + ethertype */
#define AWDL_FRAME_IPV6_UDP 44
/* Exact size of the packet awdl_frame_build() assembles. */
#define AWDL_PACKET_MAX (AWDL_FRAME_RT_LEN + AWDL_FRAME_80211_LEN + \
                         AWDL_FRAME_BODY_HEADER + AWDL_FRAME_IPV6_UDP)
#define AWDL_FRAME_MIN_LEN (AWDL_FRAME_RT_LEN + AWDL_FRAME_80211_LEN + \
                            AWDL_FRAME_BODY_HEADER + 40)
#define AWDL_FRAME_MAX_LEN (AWDL_FRAME_RT_LEN + 2304)

static void awdl_frame_put16(uint8_t *p, uint16_t value) {
    p[0] = (uint8_t)(value & 0xff);
    p[1] = (uint8_t)(value >> 8);
}

/* filin radiotap::build_tx_header(false): NOACK, rate 12 Mbps, retries 0. */
static void awdl_frame_build_radiotap(uint8_t rt[AWDL_FRAME_RT_LEN]) {
    static const uint8_t template_[AWDL_FRAME_RT_LEN] = {
        0x00, 0x00, 0x0d, 0x00,             /* version, pad, length = 13 */
        0x04, 0x80, 0x02, 0x00,             /* rate | TX flags | retries */
        0x18,                               /* 12 Mbps in 500 kbps units */
        0x00, 0x08, 0x00, 0x00              /* pad, TX flags = NOACK, retries */
    };
    memcpy(rt, template_, AWDL_FRAME_RT_LEN);
}

/* 802.11 Sequence Control = fragment number (bits 0-3) | sequence number
 * (bits 4-15).  The driver gate checks (frame[22] & 0x0f) == 0, i.e. the
 * fragment number, so a data frame must carry seq<<4 here.  Writing the raw
 * sequence number instead puts its low nibble in the fragment field and the
 * frame is rejected by the host gate for every seq that is not a multiple of
 * 16 -- which is exactly how the 0037-0039 runs lost 23 of 24 frames: the
 * harness used SEQ=200 and only seq=208 (200..223) passed. */
static void awdl_frame_put_seq_ctrl(uint8_t *p, uint16_t seq) {
    awdl_frame_put16(p, (uint16_t)(seq << 4));
}

/* filin build_data_frame(): three-address data frame, FC = 0x0008. */
static size_t awdl_frame_build_80211(uint8_t *frame, const uint8_t dst[6],
                                    const uint8_t src[6], uint16_t seq,
                                    const uint8_t *body, size_t body_len) {
    awdl_frame_put16(frame + 0, 0x0008);
    awdl_frame_put16(frame + 2, 0x0000);
    memcpy(frame + 4, dst, 6);
    memcpy(frame + 10, src, 6);
    memcpy(frame + 16, awdl_frame_bssid, 6);
    awdl_frame_put_seq_ctrl(frame + 22, seq);
    memcpy(frame + 24, body, body_len);
    return AWDL_FRAME_80211_LEN + body_len;
}

/* filin encapsulate_data_frame(): LLC/SNAP + AWDL_DATA_HEAD + seq + pad +
 * ethertype. head, seq and pad are little-endian u16; the ethertype is a
 * big-endian EtherType, matching filin's extend_from_slice(&ethertype). */
static size_t awdl_frame_build_body(uint8_t *body, uint16_t seq,
                                    uint16_t ethertype) {
    memcpy(body, awdl_frame_snap, 10);
    awdl_frame_put16(body + 10, seq);
    awdl_frame_put16(body + 12, 0);
    body[14] = (uint8_t)(ethertype >> 8);
    body[15] = (uint8_t)(ethertype & 0xff);
    return AWDL_FRAME_BODY_HEADER;
}

/* Minimal IPv6 + UDP header. Structurally valid so a peer or sniffer can parse
 * it; it is not a well-formed mDNS query. The destination is the all-nodes
 * multicast address and does not depend on addr1. All multi-byte fields are in
 * network byte order, as an on-the-wire IPv6 packet requires. */
static void awdl_frame_put_be16(uint8_t *p, uint16_t value) {
    p[0] = (uint8_t)(value >> 8);
    p[1] = (uint8_t)(value & 0xff);
}

static size_t awdl_frame_build_ipv6_udp(uint8_t *p) {
    static const uint8_t src[16] = {0xfe, 0x80, 0, 0, 0, 0, 0, 0,
                                    0x02, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0, 1};
    static const uint8_t dst[16] = {0xff, 0x02, 0, 0, 0, 0, 0, 0,
                                    0, 0, 0, 0, 0, 0, 0, 1};
    p[0] = 0x60;                        /* version 6, traffic class 0 */
    p[1] = 0x00;                        /* traffic class/flabel continuation */
    p[2] = 0x00;
    p[3] = 0x00;                        /* flow label */
    awdl_frame_put_be16(p + 4, 8);      /* payload length */
    p[6] = 17;                          /* next header: UDP */
    p[7] = 64;                          /* hop limit */
    memcpy(p + 8, src, 16);
    memcpy(p + 24, dst, 16);
    awdl_frame_put_be16(p + 36, 5353); /* source port */
    awdl_frame_put_be16(p + 38, 5353); /* destination port */
    awdl_frame_put_be16(p + 40, 8);    /* length */
    awdl_frame_put_be16(p + 42, 0);    /* checksum (not computed) */
    return AWDL_FRAME_IPV6_UDP;
}

/* Assemble the whole packet. Returns its length. */
static size_t awdl_frame_build(uint8_t *packet, size_t packet_size,
                               const uint8_t dst[6], const uint8_t src[6],
                               uint16_t seq, uint16_t ethertype) {
    uint8_t body[AWDL_FRAME_BODY_HEADER];
    uint8_t ipv6[AWDL_FRAME_IPV6_UDP];
    size_t body_len = awdl_frame_build_body(body, seq, ethertype);
    size_t ipv6_len = awdl_frame_build_ipv6_udp(ipv6);

    /* The driver's gate reads these bounds before anything else. */
    size_t needed = AWDL_FRAME_RT_LEN + AWDL_FRAME_80211_LEN + body_len +
                    ipv6_len;
    if (needed > packet_size)
        return 0;

    awdl_frame_build_radiotap(packet);
    awdl_frame_build_80211(packet + AWDL_FRAME_RT_LEN, dst, src, seq, body,
                           body_len);
    memcpy(packet + AWDL_FRAME_RT_LEN + AWDL_FRAME_80211_LEN + body_len, ipv6,
           ipv6_len);
    return needed;
}

#endif /* AWDL_FRAME_H */
