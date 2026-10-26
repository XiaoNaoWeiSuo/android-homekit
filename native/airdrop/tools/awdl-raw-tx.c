/* SPDX-License-Identifier: GPL-3.0-only
 * awdl-raw-tx - bounded AF_PACKET injector for the experimental AWDL monitor
 * data path (0003/0004/0034).
 *
 * Builds the frame described by awdl-frame.h and submits it on a radiotap
 * monitor interface. The source address is read from the interface, because
 * the driver gate requires addr2 == adapter->mac_addr.bytes. Nothing else on
 * the radio is configured.
 *
 * This tool does not verify over-the-air delivery. It only submits frames to
 * the driver's exception path and reports what the driver's own counters say.
 */
#include "awdl-frame.h"

#include <arpa/inet.h>
#include <errno.h>
#include <linux/if_ether.h>
#include <linux/if_packet.h>
#include <net/if.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

static int read_mac(const char *iface, uint8_t out[6]) {
    char path[256];
    snprintf(path, sizeof(path), "/sys/class/net/%s/address", iface);
    FILE *f = fopen(path, "r");
    if (!f)
        return -1;
    int values[6];
    if (fscanf(f, "%x:%x:%x:%x:%x:%x", &values[0], &values[1], &values[2],
               &values[3], &values[4], &values[5]) != 6) {
        fclose(f);
        return -1;
    }
    fclose(f);
    for (int i = 0; i < 6; i++)
        out[i] = (uint8_t)values[i];
    return 0;
}

static int ifindex_of(const char *iface) {
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    struct ifreq req;
    memset(&req, 0, sizeof(req));
    strncpy(req.ifr_name, iface, IFNAMSIZ - 1);
    int result = -1;
    if (ioctl(fd, SIOCGIFINDEX, &req) == 0)
        result = req.ifr_ifindex;
    close(fd);
    return result;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr,
                "usage: %s IFACE [COUNT] [DST_MAC] [SEQ] [BAD]\n"
                "  IFACE    radiotap monitor interface (e.g. awdlmon)\n"
                "  COUNT    frames to submit (default 1)\n"
                "  DST_MAC  addr1 of the data frame (default ff:ff:ff:ff:ff:ff)\n"
                "  SEQ      starting sequence number (default 1)\n"
                "  BAD      1 = corrupt one gated SNAP byte as a negative control\n"
                "Submits AWDL data frames only; never reads or changes the radio.\n",
                argv[0]);
        return 2;
    }
    const char *iface = argv[1];
    long count = argc > 2 ? strtol(argv[2], NULL, 0) : 1;
    uint16_t seq = argc > 4 ? (uint16_t)strtoul(argv[4], NULL, 0) : 1;
    long bad = argc > 5 ? strtol(argv[5], NULL, 0) : 0;
    uint8_t dst[6] = {0xff, 0xff, 0xff, 0xff, 0xff, 0xff};
    if (argc > 3) {
        int values[6];
        if (sscanf(argv[3], "%x:%x:%x:%x:%x:%x", &values[0], &values[1],
                   &values[2], &values[3], &values[4], &values[5]) != 6) {
            fprintf(stderr, "unparsable destination MAC\n");
            return 2;
        }
        for (int i = 0; i < 6; i++)
            dst[i] = (uint8_t)values[i];
    }
    if (count < 1 || count > 4096) {
        fprintf(stderr, "count out of range\n");
        return 2;
    }

    uint8_t src[6];
    if (read_mac(iface, src) != 0) {
        fprintf(stderr, "cannot read %s MAC: %s\n", iface, strerror(errno));
        return 3;
    }
    int ifindex = ifindex_of(iface);
    if (ifindex < 0) {
        fprintf(stderr, "cannot resolve %s: %s\n", iface, strerror(errno));
        return 3;
    }

    uint8_t packet[AWDL_PACKET_MAX];
    size_t packet_len = awdl_frame_build(packet, sizeof(packet), dst, src, seq,
                                         0x86dd);
    if (!packet_len || packet_len < AWDL_FRAME_MIN_LEN ||
        packet_len > AWDL_FRAME_MAX_LEN) {
        fprintf(stderr, "assembled frame outside the driver's accepted range\n");
        return 3;
    }
    const uint8_t *frame = packet + AWDL_FRAME_RT_LEN;

    /* Negative control: break exactly one byte the driver gate compares, so a
     * rejected submission is observable instead of indistinguishable from a
     * frame that never reached the driver. */
    if (bad)
        packet[AWDL_FRAME_RT_LEN + 24 + 3] ^= 0x5a;        /* SNAP OUI byte */

    int fd = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
    if (fd < 0) {
        fprintf(stderr, "AF_PACKET socket: %s\n", strerror(errno));
        return 3;
    }
    struct sockaddr_ll addr;
    memset(&addr, 0, sizeof(addr));
    addr.sll_family = AF_PACKET;
    addr.sll_ifindex = ifindex;
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        fprintf(stderr, "bind %s: %s\n", iface, strerror(errno));
        close(fd);
        return 3;
    }

    printf("iface=%s ifindex=%d src=%02x:%02x:%02x:%02x:%02x:%02x\n",
           iface, ifindex, src[0], src[1], src[2], src[3], src[4], src[5]);
    printf("packet_bytes=%zu mode=%s\n", packet_len,
           bad ? "negative-control" : "well-formed");
    printf("fc=%02x%02x addr1=%02x:%02x:%02x:%02x:%02x:%02x "
           "addr3=%02x:%02x:%02x:%02x:%02x:%02x "
           "snap=%02x%02x%02x%02x%02x%02x%02x%02x head=%02x%02x "
           "ethertype=%02x%02x ipver=%x\n",
           frame[0], frame[1], frame[4], frame[5], frame[6], frame[7],
           frame[8], frame[9], frame[16], frame[17], frame[18], frame[19],
           frame[20], frame[21], frame[24], frame[25], frame[26], frame[27],
           frame[28], frame[29], frame[30], frame[31], frame[32], frame[33],
           frame[38], frame[39], frame[40] >> 4);

    long submitted = 0, failed = 0;
    for (long i = 0; i < count; i++) {
        /* Sequence Control, not the raw seq: fragment number must stay 0.
         * The AWDL body head below carries its own copy of seq verbatim. */
        awdl_frame_put_seq_ctrl(packet + AWDL_FRAME_RT_LEN + 22, seq);
        awdl_frame_put16(packet + AWDL_FRAME_RT_LEN + 24 + 10, seq);
        ssize_t sent = send(fd, packet, packet_len, 0);
        if (sent == (ssize_t)packet_len) {
            submitted++;
        } else {
            failed++;
            fprintf(stderr, "send %ld failed: %s\n", i, strerror(errno));
        }
        seq++;
    }
    close(fd);
    printf("submitted=%ld failed=%ld\n", submitted, failed);
    printf("NOTE: host submission only. Firmware and OTA delivery are not "
           "established by this tool.\n");
    return failed ? 1 : 0;
}
