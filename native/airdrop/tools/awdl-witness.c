/* SPDX-License-Identifier: GPL-3.0-only
 * Receive-only AF_PACKET witness. Does not configure or transmit on the radio.
 * Prints AWDL action headers only; other frames are counted without payload.
 */
#include <arpa/inet.h>
#include <errno.h>
#include <linux/if_ether.h>
#include <linux/if_packet.h>
#include <net/if.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

static int64_t now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static const uint8_t *awdl_action(const uint8_t *p, size_t n) {
    static const uint8_t bssid[] = {0, 0x25, 0, 0xff, 0x94, 0x73};
    static const uint8_t prefix[] = {0x7f, 0, 0x17, 0xf2, 8, 0x10};
    if (n < 8 || p[0] || p[1]) return NULL;
    size_t header = (size_t)p[2] | ((size_t)p[3] << 8);
    if (header < 8 || header > n || n - header < 40) return NULL;
    const uint8_t *frame = p + header;
    if (frame[0] != 0xd0 || (frame[1] & 0x40) ||
        memcmp(frame + 16, bssid, sizeof(bssid)) ||
        memcmp(frame + 24, prefix, sizeof(prefix)) ||
        (frame[30] != 0 && frame[30] != 3)) return NULL;
    return frame;
}
static uint16_t radiotap_frequency(const uint8_t *p, size_t n) {
    if (n < 8 || p[0] || p[1]) return 0;
    size_t header = (size_t)p[2] | ((size_t)p[3] << 8);
    if (header < 8 || header > n) return 0;
    uint32_t first = (uint32_t)p[4] | ((uint32_t)p[5] << 8) |
                     ((uint32_t)p[6] << 16) | ((uint32_t)p[7] << 24);
    if (!(first & 8)) return 0;
    size_t offset = 4;
    uint32_t present;
    do {
        if (offset > header || header - offset < 4) return 0;
        present = (uint32_t)p[offset] | ((uint32_t)p[offset+1] << 8) |
                  ((uint32_t)p[offset+2] << 16) | ((uint32_t)p[offset+3] << 24);
        offset += 4;
    } while (present & 0x80000000U);
    if (first & 1) { offset = (offset + 7) & ~(size_t)7; offset += 8; }
    if (first & 2) offset++;
    if (first & 4) offset++;
    offset = (offset + 1) & ~(size_t)1;
    if (offset > header || header - offset < 4) return 0;
    return (uint16_t)p[offset] | ((uint16_t)p[offset+1] << 8);
}
/* Check the entire TLV chain, rather than accepting just its vendor prefix.
 * Report a truncated tail separately; never read or print TLV payloads. */
static size_t awdl_tlv_tail(const uint8_t *frame, size_t n, unsigned *count) {
    *count = 0;
    if (n < 40) return n;
    size_t offset = 40;
    while (n - offset >= 3) {
        size_t length = (size_t)frame[offset + 1] |
                        ((size_t)frame[offset + 2] << 8);
        if (length > n - offset - 3) break;
        offset += 3 + length;
        (*count)++;
    }
    return n - offset;
}
int main(int argc, char **argv) {
    if (argc < 2 || argc > 3) { fprintf(stderr, "Usage: %s MONITOR_INTERFACE [SECONDS:1..15]\n", argv[0]); return 2; }
    long seconds = 15;
    if (argc == 3) {
        char *end;
        seconds = strtol(argv[2], &end, 10);
        if (*end || seconds < 1 || seconds > 15) return 2;
    }
    setbuf(stdout, NULL);
    unsigned index = if_nametoindex(argv[1]);
    if (!index) { perror("if_nametoindex"); return 1; }
    int fd = socket(AF_PACKET, SOCK_RAW | SOCK_CLOEXEC, htons(ETH_P_ALL));
    if (fd < 0) { perror("socket"); return 1; }
    struct sockaddr_ll addr = {.sll_family = AF_PACKET, .sll_protocol = htons(ETH_P_ALL), .sll_ifindex = (int)index};
    if (bind(fd, (void *)&addr, sizeof(addr))) { perror("bind"); close(fd); return 1; }
    unsigned packets = 0, awdl = 0, radiotap = 0, actions = 0, packet_types[8] = {0};
    unsigned complete_tlv_frames = 0, incomplete_tlv_frames = 0;
    struct { uint16_t frequency; unsigned count; } channels[16] = {{0}};
    unsigned channel_count = 0;
    int64_t deadline = now_ms() + seconds * 1000;
    printf("WITNESS_READY ifindex=%u duration_ms=%ld receive_only=true\n", index, seconds * 1000);
    while (now_ms() < deadline) {
        struct pollfd pollfd = {.fd = fd, .events = POLLIN};
        int remaining = (int)(deadline - now_ms());
        if (remaining <= 0) break;
        int status = poll(&pollfd, 1, remaining);
        if (status < 0 && errno == EINTR) continue;
        if (status <= 0) break;
        if (!(pollfd.revents & POLLIN)) { close(fd); return 1; }
        uint8_t buffer[4096];
        struct sockaddr_ll source;
        socklen_t length = sizeof(source);
        ssize_t n = recvfrom(fd, buffer, sizeof(buffer), 0, (void *)&source, &length);
        if (n < 0) { if (errno == EINTR) continue; perror("recvfrom"); close(fd); return 1; }
        if (length < sizeof(source) || source.sll_ifindex != (int)index) continue;
        packets++;
        if (source.sll_pkttype < 8) packet_types[source.sll_pkttype]++;
        uint16_t frequency = radiotap_frequency(buffer, (size_t)n);
        unsigned channel;
        for (channel = 0; channel < channel_count; channel++)
            if (channels[channel].frequency == frequency) break;
        if (channel == channel_count && channel_count < 16)
            channels[channel_count++].frequency = frequency;
        if (channel < channel_count) channels[channel].count++;
        if (n >= 8 && !buffer[0] && !buffer[1]) {
            size_t header = (size_t)buffer[2] | ((size_t)buffer[3] << 8);
            if (header >= 8 && header <= (size_t)n) {
                radiotap++;
                if ((size_t)n - header >= 24 && buffer[header] == 0xd0) actions++;
            }
        }
        const uint8_t *frame = awdl_action(buffer, (size_t)n);
        if (!frame) continue;
        awdl++;
        unsigned tlvs;
        size_t tail = awdl_tlv_tail(frame, (size_t)n - (size_t)(frame - buffer), &tlvs);
        if (!tail && tlvs) complete_tlv_frames++; else incomplete_tlv_frames++;
        if (awdl <= 12)
            printf("AWDL_ACTION pkttype=%u frequency=%u bytes=%zd subtype=%u source=%02x:%02x:%02x:%02x:%02x:%02x tlvs=%u trailing_bytes=%zu\n",
                   source.sll_pkttype, frequency, n, frame[30], frame[10], frame[11], frame[12], frame[13], frame[14], frame[15], tlvs, tail);
    }
    printf("WITNESS_SUMMARY packets=%u radiotap=%u action_frames=%u awdl_actions=%u packet_types=",
           packets, radiotap, actions, awdl);
    for (unsigned i = 0; i < 8; i++) printf("%s%u:%u", i ? "," : "", i, packet_types[i]);
    printf(" radiotap_frequencies=");
    for (unsigned i = 0; i < channel_count; i++) printf("%s%u:%u", i ? "," : "", channels[i].frequency, channels[i].count);
    printf(" complete_tlv_frames=%u incomplete_tlv_frames=%u\n", complete_tlv_frames, incomplete_tlv_frames);
    close(fd);
    return 0;
}
