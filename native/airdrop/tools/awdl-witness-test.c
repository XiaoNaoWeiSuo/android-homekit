/* SPDX-License-Identifier: GPL-3.0-only */
#define main witness_main
#include "awdl-witness.c"
#undef main
#include <assert.h>

int main(void) {
    uint8_t tlv_frame[48] = {0};
    tlv_frame[40] = 4;
    tlv_frame[41] = 2;
    tlv_frame[45] = 6; // Second, zero-length TLV.
    unsigned tlvs;
    assert(awdl_tlv_tail(tlv_frame, 48, &tlvs) == 0 && tlvs == 2);
    for (size_t n = 40; n < 45; n++)
        assert(awdl_tlv_tail(tlv_frame, n, &tlvs) == n - 40 && tlvs == 0);
    assert(awdl_tlv_tail(tlv_frame, 46, &tlvs) == 1 && tlvs == 1);
    tlv_frame[42] = 0xff; // Oversized value must not be traversed.
    assert(awdl_tlv_tail(tlv_frame, 48, &tlvs) == 8 && tlvs == 0);
    uint8_t packet[48] = {0, 0, 8, 0};
    uint8_t *frame = packet + 8;
    frame[0] = 0xd0;
    const uint8_t bssid[] = {0, 0x25, 0, 0xff, 0x94, 0x73};
    const uint8_t prefix[] = {0x7f, 0, 0x17, 0xf2, 8, 0x10};
    memcpy(frame + 16, bssid, sizeof(bssid));
    memcpy(frame + 24, prefix, sizeof(prefix));
    assert(awdl_action(packet, sizeof(packet)) == frame);
    for (size_t n = 0; n < sizeof(packet); n++) assert(!awdl_action(packet, n));
    frame[30] = 3;
    assert(awdl_action(packet, sizeof(packet)) == frame);
    frame[30] = 2;
    assert(!awdl_action(packet, sizeof(packet)));
    uint8_t channel_header[28] = {0, 0, 12, 0, 8, 0, 0, 0, 0x71, 0x16, 0, 0};
    assert(radiotap_frequency(channel_header, sizeof(channel_header)) == 5745);
    for (size_t n = 0; n < 12; n++) assert(!radiotap_frequency(channel_header, n));
    channel_header[4] = 0x0f; // TSFT + flags + rate + channel, with alignment.
    channel_header[2] = 22;
    channel_header[18] = 0x3c;
    channel_header[19] = 0x14;
    assert(radiotap_frequency(channel_header, sizeof(channel_header)) == 5180);
    memset(channel_header, 0, sizeof(channel_header));
    channel_header[2] = 16;
    channel_header[4] = 8;
    channel_header[7] = 0x80; // One extended bitmap before channel fields.
    channel_header[12] = 0x85;
    channel_header[13] = 9;
    assert(radiotap_frequency(channel_header, sizeof(channel_header)) == 2437);
    channel_header[11] = 0x80; // Truncated/unbounded extended bitmap sequence.
    channel_header[15] = 0x80;
    assert(!radiotap_frequency(channel_header, 16));
    frame[30] = 0;
    frame[16] = 1;
    assert(!awdl_action(packet, sizeof(packet)));
    frame[16] = 0;
    packet[2] = 0xff;
    packet[3] = 0xff;
    assert(!awdl_action(packet, sizeof(packet)));
    packet[2] = 0;
    packet[3] = 0;
    assert(!awdl_action(packet, sizeof(packet)));
    puts("awdl-witness parser tests passed");
    return 0;
}
