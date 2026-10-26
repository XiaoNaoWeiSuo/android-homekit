/* SPDX-License-Identifier: GPL-3.0-only
 * awdl-capture.c - receive-only dump of AWDL action frames from a monitor
 * interface.
 *
 * awdl-witness prints headers and counts.  That is enough to prove the radio
 * receives AWDL, but not enough to build a transmitter: the AWDL action frame
 * body is a TLV list (sync params, channel sequence, master indication, ...)
 * and every field has to be exactly what a real Apple peer emits before it
 * will synchronise.  This tool prints the whole frame so the ground truth can
 * be read off a real Mac instead of guessed.
 *
 * Prints one line per frame:
 *   AWDL ts=<ms> freq=<mhz> len=<n> <hex of the 802.11 frame>
 * and a summary of everything else it saw.  `ts` is milliseconds since
 * CAPTURE_START, which is what makes peer timing analysis possible: AWDL peers
 * only listen inside their own availability windows, so the transmit instants
 * measured here are the schedule a resident service has to aim at.
 *
 * Receive only.  It never transmits and never changes the radio.
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

static const uint8_t awdl_bssid[6] = {0, 0x25, 0, 0xff, 0x94, 0x73};
static const uint8_t awdl_prefix[6] = {0x7f, 0, 0x17, 0xf2, 8, 0x10};

static int64_t now_ms(void)
{
	struct timespec t;

	clock_gettime(CLOCK_MONOTONIC, &t);
	return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

/* Radiotap: find the channel frequency.  Returns 0 when absent. */
static uint16_t rt_freq(const uint8_t *p, size_t n)
{
	size_t header, offset;
	uint32_t present, first;

	if (n < 8 || p[0] != 0)
		return 0;
	header = (size_t)p[2] | ((size_t)p[3] << 8);
	if (header < 8 || header > n)
		return 0;
	first = (uint32_t)p[4] | ((uint32_t)p[5] << 8) |
		((uint32_t)p[6] << 16) | ((uint32_t)p[7] << 24);
	if (!(first & 8))	/* IEEE80211_RADIOTAP_FREQ */
		return 0;
	offset = 4;
	do {
		if (offset + 4 > header)
			return 0;
		present = (uint32_t)p[offset] | ((uint32_t)p[offset + 1] << 8) |
			  ((uint32_t)p[offset + 2] << 16) |
			  ((uint32_t)p[offset + 3] << 24);
		offset += 4;
	} while (present & 0x80000000);
	/* fields before FREQ: TSFT(8) FLAGS(1) RATE(1) */
	if (present & 1)
		offset += 8;
	if (present & 2)
		offset += 1;
	if (present & 4)
		offset += 1;
	if (offset + 2 > header)
		return 0;
	return (uint16_t)(p[offset] | (p[offset + 1] << 8));
}

static void hex(const uint8_t *p, size_t n)
{
	size_t i;

	for (i = 0; i < n; i++)
		printf("%02x", p[i]);
}

int main(int argc, char **argv)
{
	const char *ifname;
	int duration_ms = 10000;
	struct sockaddr_ll sll;
	struct ifreq ifr;
	struct packet_mreq mreq;
	int fd, n;
	int64_t deadline;
	int64_t t0 = 0;
	unsigned int awdl = 0, apple = 0, action = 0, total = 0;
	uint8_t buf[4096];

	if (argc < 2) {
		fprintf(stderr, "usage: %s <iface> [duration_ms]\n", argv[0]);
		return 2;
	}
	ifname = argv[1];
	if (argc >= 3)
		duration_ms = atoi(argv[2]);

	fd = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ALL));
	if (fd < 0) {
		perror("socket(AF_PACKET)");
		return 3;
	}
	memset(&ifr, 0, sizeof(ifr));
	strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);
	if (ioctl(fd, SIOCGIFINDEX, &ifr) < 0) {
		perror("SIOCGIFINDEX");
		return 3;
	}
	memset(&sll, 0, sizeof(sll));
	sll.sll_family = AF_PACKET;
	sll.sll_protocol = htons(ETH_P_ALL);
	sll.sll_ifindex = ifr.ifr_ifindex;
	if (bind(fd, (struct sockaddr *)&sll, sizeof(sll)) < 0) {
		perror("bind");
		return 3;
	}
	memset(&mreq, 0, sizeof(mreq));
	mreq.mr_ifindex = ifr.ifr_ifindex;
	mreq.mr_type = PACKET_MR_PROMISC;
	setsockopt(fd, SOL_PACKET, PACKET_ADD_MEMBERSHIP, &mreq, sizeof(mreq));

	printf("CAPTURE_START iface=%s duration_ms=%d\n", ifname, duration_ms);
	fflush(stdout);
	deadline = now_ms() + duration_ms;
	t0 = now_ms();

	while (now_ms() < deadline) {
		struct pollfd pfd = {.fd = fd, .events = POLLIN};
		int left = (int)(deadline - now_ms());
		const uint8_t *frame;
		size_t header, flen;

		if (left <= 0)
			break;
		if (poll(&pfd, 1, left) <= 0)
			break;
		n = (int)recv(fd, buf, sizeof(buf), 0);
		if (n <= 0)
			continue;
		total++;
		if ((size_t)n < 8 || buf[0] != 0)
			continue;
		header = (size_t)buf[2] | ((size_t)buf[3] << 8);
		if (header < 8 || header > (size_t)n || (size_t)n - header < 24)
			continue;
		frame = buf + header;
		flen = (size_t)n - header;
		if ((frame[0] & 0x0c) == 0)
			action++;
		if (flen >= 30 && !memcmp(frame + 24, awdl_prefix, 6) &&
		    (frame[30] == 0 || frame[30] == 3)) {
			/* Apple OUI 00:17:f2, type 0x08 */
			if (!memcmp(frame + 25, "\x00\x17\xf2", 3))
				apple++;
			if (!memcmp(frame + 16, awdl_bssid, 6)) {
				awdl++;
				printf("AWDL ts=%lld freq=%u len=%zu seq=%u ",
				       (long long)(now_ms() - t0),
				       rt_freq(buf, (size_t)n), flen,
				       (unsigned)((frame[22] | (frame[23] << 8)) >> 4));
				hex(frame, flen);
				printf("\n");
				fflush(stdout);
			}
		}
	}
	printf("CAPTURE_SUMMARY total=%u mgmt=%u apple_oui=%u awdl=%u\n",
	       total, action, apple, awdl);
	close(fd);
	return awdl ? 0 : 1;
}
