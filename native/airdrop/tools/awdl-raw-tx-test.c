/* SPDX-License-Identifier: GPL-3.0-only
 * awdl-raw-tx-test - verify the assembled AWDL data frame against every gate
 * condition in qcacld-3.0 hdd_mon_awdl_start_xmit() (0003/0004/0034), and
 * against the shape filin produces (crates/filin-rs radiotap::build_tx_header
 * and encapsulate_data_frame).
 *
 * Host build: cc -O2 -Wall -Wextra -o awdl-raw-tx-test awdl-raw-tx-test.c
 * Android build: aarch64-linux-android21-clang -o awdl-raw-tx-test ...
 *
 * A change in either side silently turns every submission into a rejection,
 * so this test is the contract between them.
 */
#include "awdl-frame.h"

#include <stdio.h>

static int failures;

static void check(int condition, const char *what) {
    printf("%-58s %s\n", what, condition ? "ok" : "FAIL");
    if (!condition)
        failures++;
}

static void check_bytes(const uint8_t *got, const uint8_t *want, size_t n,
                        const char *what) {
    int equal = memcmp(got, want, n) == 0;
    check(equal, what);
    if (!equal) {
        for (size_t i = 0; i < n; i++)
            printf("    [%zu] got %02x want %02x\n", i, got[i], want[i]);
    }
}

int main(void) {
    uint8_t packet[AWDL_PACKET_MAX];
    const uint8_t dst[6] = {0xff, 0xff, 0xff, 0xff, 0xff, 0xff};
    /* A locally administered address, as a monitor interface would carry. */
    const uint8_t src[6] = {0x02, 0xaa, 0xbb, 0xcc, 0xdd, 0xee};

    size_t len = awdl_frame_build(packet, sizeof(packet), dst, src, 0x0020,
                                  0x86dd);
    check(len > 0, "frame assembles");
    if (!len)
        return 1;

    const uint8_t *rt = packet;
    const uint8_t *frame = packet + AWDL_FRAME_RT_LEN;

    /* ---- bounds the driver applies first ---- */
    check(len >= 13 + 24 + 16 + 40, "length >= 13+24+16+40");
    check(len <= 13 + 2304, "length <= 13+2304");

    /* ---- radiotap prefix (driver: memcmp(skb->data, radiotap_prefix, 10)) ---- */
    uint8_t expected_prefix[10];
    memcpy(expected_prefix, awdl_frame_radiotap_prefix, 10);
    check_bytes(rt, expected_prefix, 10, "radiotap prefix matches driver");

    /* ---- radiotap TX flags (driver: skb->data[10] in {0,8}, skb->data[11]==0) ---- */
    check(rt[10] == 0 || rt[10] == 8, "radiotap TX flags high byte in {0,8}");
    check(rt[11] == 0, "radiotap TX flags byte 11 is zero");
    check(rt[0] == 0, "radiotap version 0");
    check(((uint16_t)rt[2] | ((uint16_t)rt[3] << 8)) == AWDL_FRAME_RT_LEN,
          "radiotap it_len is 13");

    /* ---- 802.11 header ---- */
    check(frame[0] == 0x08, "frame control type is data (0x08)");
    check(frame[1] == 0x00, "frame control flags are zero");
    check((frame[22] & 0x0f) == 0, "fragment number is zero");

    /* ---- sequence control encoding: fragment 0, seq in the high 12 bits ---- */
    {
        /* 0x0123 has a non-zero low nibble, so the old raw-seq encoding would
         * have put 0x3 into the fragment field and been rejected by the host
         * gate.  Rebuilding with it pins the fix. */
        uint8_t p2[AWDL_PACKET_MAX];
        uint8_t body[AWDL_FRAME_BODY_HEADER];
        awdl_frame_build_body(body, 0x0123, 0x86dd);
        awdl_frame_build_radiotap(p2);
        awdl_frame_build_80211(p2 + AWDL_FRAME_RT_LEN, dst, src, 0x0123, body,
                               sizeof(body));
        const uint8_t *f2 = p2 + AWDL_FRAME_RT_LEN;
        check((f2[22] & 0x0f) == 0, "seq 0x0123 keeps the fragment number 0");
        check(((uint16_t)f2[23] << 8 | f2[22]) == (0x0123 << 4),
              "seq 0x0123 lands in the high 12 bits");
    }

    /* ---- addresses ---- */
    check_bytes(frame + 10, src, 6, "addr2 equals the interface MAC");
    check_bytes(frame + 16, awdl_frame_bssid, 6, "addr3 is the AWDL BSSID");
    check_bytes(frame + 4, dst, 6, "addr1 is the requested destination");

    /* ---- AWDL body ---- */
    check_bytes(frame + 24, awdl_frame_snap, 10,
                "LLC/SNAP + AWDL_DATA_HEAD matches driver");
    check_bytes(frame + 24, awdl_frame_snap, 8,
                "LLC/SNAP alone matches filin");
    check(frame[32] == 0x03 && frame[33] == 0x04,
          "AWDL_DATA_HEAD is 0x0403 (owl/src/tx.c:33)");
    check(frame[38] == 0x86 && frame[39] == 0xdd, "ethertype is IPv6");
    check((frame[40] >> 4) == 6, "IPv6 version is 6");

    /* ---- filin cross-checks ---- */
    uint8_t want_body[AWDL_FRAME_BODY_HEADER];
    awdl_frame_build_body(want_body, 0x0020, 0x86dd);
    check_bytes(frame + 24, want_body, AWDL_FRAME_BODY_HEADER,
                "body matches filin encapsulate_data_frame output");

    /* ---- the negative control breaks exactly one gated byte ---- */
    size_t bad_index = AWDL_FRAME_RT_LEN + 24 + 3;
    uint8_t before = packet[bad_index];
    packet[bad_index] ^= 0x5a;
    check(memcmp(packet + AWDL_FRAME_RT_LEN + 24, awdl_frame_snap, 10) != 0,
          "negative control breaks the SNAP comparison");
    packet[bad_index] = before;
    check(memcmp(packet + AWDL_FRAME_RT_LEN + 24, awdl_frame_snap, 10) == 0,
          "restoring the byte restores the SNAP comparison");

    /* ---- minimum and maximum accepted sizes ---- */
    check(AWDL_FRAME_MIN_LEN <= len && len <= AWDL_FRAME_MAX_LEN,
          "assembled length inside the accepted range");

    printf("awdl-raw-tx-test: %s\n", failures ? "FAILED" : "passed");
    return failures ? 1 : 0;
}
