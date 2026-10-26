/* SPDX-License-Identifier: GPL-3.0-only
 * marionette-awdl-mgmt-tx.c - submit one AWDL vendor action frame through the
 * supported nl80211 management-transmit path.
 *
 * Every earlier attempt in this project tried to hand a complete 802.11 frame
 * to the driver's data path (hdd_mon_awdl_start_xmit -> cdp_tx_send_exc).  The
 * firmware answered all of them with the identical SFR assert
 * (cmnos_thread_patch.c:1170 ... line#2834), so that path is closed.
 *
 * This tool takes the other door: NL80211_CMD_FRAME.  cfg80211 builds nothing,
 * the driver's wlan_hdd_mgmt_tx() forwards the caller's bytes to WMI MGMT_TX,
 * and the firmware has a first-class command for exactly this.  It is how
 * wpa_supplicant sends P2P action frames, so it is a path the firmware is
 * expected to accept.
 *
 * Scope: management frames only.  AWDL discovery is a category-127 vendor
 * action frame, which is what this builds.  The AWDL data path (IPv6 over
 * LLC/SNAP 00:17:f2) is NOT covered and cannot be covered from here.
 *
 * The frame shape matches driver patch 0002 (cfg80211 marionette_awdl_mgmt_tx),
 * which relaxes the "a non-public action frame must address the associated AP"
 * rule for this exact signature:
 *   frame[24..29] == 7f 00 17 f2 08 10   (category, OUI, type, version)
 *   frame[30]     == 0 or 3
 *   addr3         == 00:25:00:ff:94:73
 */
#include <errno.h>
#include <net/if.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include <linux/genetlink.h>
#include <linux/netlink.h>
#include <linux/if_ether.h>

/* Values from include/uapi/linux/nl80211.h of the pinned 5.15.74 fuxi kernel,
 * read out with a throwaway program that prints the enum members rather than
 * copied from a host header.  The first version of this tool guessed them and
 * sent command 54 with attribute 52, which the kernel accepted as a different
 * (harmless) command and acknowledged with success -- a false positive that
 * looked exactly like a working transmit.  Never hand-copy these. */
#define NL80211_CMD_REGISTER_FRAME 58
#define NL80211_CMD_FRAME 59
#define NL80211_CMD_FRAME_TX_STATUS 60
#define NL80211_CMD_REMAIN_ON_CHANNEL 55

#define NL80211_ATTR_IFINDEX 3
#define NL80211_ATTR_WIPHY_FREQ 38
#define NL80211_ATTR_FRAME 51
#define NL80211_ATTR_DURATION 87
#define NL80211_ATTR_COOKIE 88
#define NL80211_ATTR_FRAME_MATCH 91
#define NL80211_ATTR_ACK 92
#define NL80211_ATTR_FRAME_TYPE 101
#define NL80211_ATTR_OFFCHANNEL_TX_OK 108
#define NL80211_ATTR_DONT_WAIT_FOR_ACK 142

#define NLA_ALIGNTO 4
#define NLA_ALIGN(len) (((len) + NLA_ALIGNTO - 1) & ~(NLA_ALIGNTO - 1))

static const uint8_t awdl_bssid[6] = {0x00, 0x25, 0x00, 0xff, 0x94, 0x73};

static int nla_put(uint8_t *buf, size_t cap, size_t *off, uint16_t type,
                   const void *data, uint16_t len)
{
	size_t need = NLA_ALIGN(sizeof(struct nlattr) + len);
	struct nlattr *nla;

	if (*off + need > cap)
		return -1;
	nla = (struct nlattr *)(buf + *off);
	nla->nla_type = type;
	nla->nla_len = (uint16_t)(sizeof(struct nlattr) + len);
	if (len)
		memcpy((uint8_t *)nla + sizeof(struct nlattr), data, len);
	memset((uint8_t *)nla + nla->nla_len, 0, need - nla->nla_len);
	*off += need;
	return 0;
}

static int nla_put_u32(uint8_t *buf, size_t cap, size_t *off, uint16_t type,
                       uint32_t value)
{
	return nla_put(buf, cap, off, type, &value, sizeof(value));
}

static int nla_put_flag(uint8_t *buf, size_t cap, size_t *off, uint16_t type)
{
	return nla_put(buf, cap, off, type, NULL, 0);
}

/* Walk the attributes of one message. Returns the attribute with the given
 * type, or NULL. */
static const struct nlattr *nla_find(const struct nlmsghdr *nh, uint16_t type)
{
	const struct genlmsghdr *gh = (const struct genlmsghdr *)NLMSG_DATA(nh);
	int rem = (int)nh->nlmsg_len - NLMSG_HDRLEN - GENL_HDRLEN;
	const struct nlattr *a = (const struct nlattr *)((const uint8_t *)gh +
							 GENL_HDRLEN);

	for (; rem >= (int)sizeof(*a) && a->nla_len >= sizeof(*a);
	     rem -= (int)NLA_ALIGN(a->nla_len),
	     a = (const struct nlattr *)((const uint8_t *)a +
					 NLA_ALIGN(a->nla_len))) {
		if ((a->nla_type & NLA_TYPE_MASK) == type)
			return a;
	}
	return NULL;
}

static const void *nla_data(const struct nlattr *a)
{
	return (const uint8_t *)a + sizeof(*a);
}

/* Join the nl80211 "mlme" multicast group. Without this the kernel never
 * delivers NL80211_CMD_FRAME_TX_STATUS, so a frame can be submitted and then
 * silently vanish with no ACK/no-ACK outcome recorded. */
static int join_mlme_group(int fd)
{
	uint8_t req[512] = {0};
	uint8_t resp[8192];
	struct nlmsghdr *nh = (struct nlmsghdr *)req;
	struct genlmsghdr *gh;
	struct sockaddr_nl sa = {.nl_family = AF_NETLINK};
	size_t off;
	static const char name[] = "nl80211";
	int group = -1;

	nh->nlmsg_type = GENL_ID_CTRL;
	nh->nlmsg_flags = NLM_F_REQUEST;
	nh->nlmsg_seq = 900;
	off = NLMSG_HDRLEN;
	gh = (struct genlmsghdr *)(req + off);
	gh->cmd = CTRL_CMD_GETFAMILY;
	gh->version = 1;
	off += GENL_HDRLEN;
	if (nla_put(req, sizeof(req), &off, CTRL_ATTR_FAMILY_NAME, name,
		    sizeof(name)))
		return -1;
	nh->nlmsg_len = (uint32_t)off;
	if (sendto(fd, req, nh->nlmsg_len, 0, (struct sockaddr *)&sa,
		   sizeof(sa)) < 0)
		return -1;

	for (;;) {
		ssize_t n = recv(fd, resp, sizeof(resp), 0);
		struct nlmsghdr *rn = (struct nlmsghdr *)resp;

		if (n <= 0)
			return -1;
		if (rn->nlmsg_type != GENL_ID_CTRL)
			continue;

		const struct nlattr *groups =
			nla_find(rn, CTRL_ATTR_MCAST_GROUPS);
		if (!groups)
			return -1;
		/* Nested: each group is CTRL_ATTR_MCAST_GRP_NAME + _ID. */
		int rem = (int)groups->nla_len - (int)sizeof(*groups);
		const struct nlattr *g =
			(const struct nlattr *)((const uint8_t *)groups +
						sizeof(*groups));

		for (; rem >= (int)sizeof(*g) && g->nla_len >= sizeof(*g);
		     rem -= (int)NLA_ALIGN(g->nla_len),
		     g = (const struct nlattr *)((const uint8_t *)g +
						 NLA_ALIGN(g->nla_len))) {
			int grem = (int)g->nla_len - (int)sizeof(*g);
			const struct nlattr *ga =
				(const struct nlattr *)((const uint8_t *)g +
							sizeof(*g));
			const char *gname = NULL;
			int gid = -1;

			for (; grem >= (int)sizeof(*ga) &&
			       ga->nla_len >= sizeof(*ga);
			     grem -= (int)NLA_ALIGN(ga->nla_len),
			     ga = (const struct nlattr *)((const uint8_t *)ga +
							  NLA_ALIGN(ga->nla_len))) {
				switch (ga->nla_type & NLA_TYPE_MASK) {
				case CTRL_ATTR_MCAST_GRP_NAME:
					gname = nla_data(ga);
					break;
				case CTRL_ATTR_MCAST_GRP_ID:
					gid = *(const uint32_t *)nla_data(ga);
					break;
				}
			}
			if (gname && gid >= 0 && !strcmp(gname, "mlme"))
				group = gid;
		}
		break;
	}

	if (group < 0)
		return -1;
	if (setsockopt(fd, SOL_NETLINK, NETLINK_ADD_MEMBERSHIP, &group,
		       sizeof(group)) < 0)
		return -1;
	return group;
}

/* Resolve the nl80211 generic-netlink family id. */
static int resolve_nl80211(int fd)
{
	uint8_t req[512] = {0};
	uint8_t resp[8192];
	struct nlmsghdr *nh = (struct nlmsghdr *)req;
	struct genlmsghdr *gh;
	struct sockaddr_nl sa = {.nl_family = AF_NETLINK};
	size_t off;
	static const char name[] = "nl80211";

	nh->nlmsg_type = GENL_ID_CTRL;
	nh->nlmsg_flags = NLM_F_REQUEST;
	nh->nlmsg_seq = 1;
	off = NLMSG_HDRLEN;
	gh = (struct genlmsghdr *)(req + off);
	gh->cmd = CTRL_CMD_GETFAMILY;
	gh->version = 1;
	off += GENL_HDRLEN;
	if (nla_put(req, sizeof(req), &off, CTRL_ATTR_FAMILY_NAME, name,
		    sizeof(name)))
		return -1;
	nh->nlmsg_len = (uint32_t)off;

	if (sendto(fd, req, nh->nlmsg_len, 0, (struct sockaddr *)&sa,
		   sizeof(sa)) < 0)
		return -1;

	for (;;) {
		ssize_t n = recv(fd, resp, sizeof(resp), 0);
		struct nlmsghdr *rn = (struct nlmsghdr *)resp;

		if (n < 0)
			return -1;
		if (!NLMSG_OK(rn, (unsigned int)n))
			continue;
		if (rn->nlmsg_type == NLMSG_ERROR) {
			struct nlmsgerr *e = (struct nlmsgerr *)NLMSG_DATA(rn);
			return e->error ? e->error : -1;
		}
		if (rn->nlmsg_type == GENL_ID_CTRL) {
			struct genlmsghdr *rg = (struct genlmsghdr *)NLMSG_DATA(rn);
			int rem = rn->nlmsg_len - NLMSG_HDRLEN - GENL_HDRLEN;
			struct nlattr *a = (struct nlattr *)((uint8_t *)rg +
							     GENL_HDRLEN);

			for (; rem >= (int)sizeof(*a) && a->nla_len >= sizeof(*a);
			     rem -= NLA_ALIGN(a->nla_len),
			     a = (struct nlattr *)((uint8_t *)a +
						   NLA_ALIGN(a->nla_len))) {
				if ((a->nla_type & NLA_TYPE_MASK) ==
				    CTRL_ATTR_FAMILY_ID)
					return *(uint16_t *)((uint8_t *)a +
							     sizeof(*a));
			}
			return -1;
		}
	}
}

static int iface_mac(const char *ifname, uint8_t mac[6])
{
	struct ifreq ifr;
	int fd = socket(AF_INET, SOCK_DGRAM, 0);

	if (fd < 0)
		return -1;
	memset(&ifr, 0, sizeof(ifr));
	strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);
	if (ioctl(fd, SIOCGIFHWADDR, &ifr) < 0) {
		close(fd);
		return -1;
	}
	memcpy(mac, ifr.ifr_hwaddr.sa_data, 6);
	close(fd);
	return 0;
}

static void put16(uint8_t *p, uint16_t v)
{
	p[0] = (uint8_t)(v & 0xff);
	p[1] = (uint8_t)(v >> 8);
}

/* AWDL vendor action frame: 24-byte header + category/OUI/type/version + TLVs.
 *
 * The TLV set is deliberately minimal and self-describing:
 *   0x00 awdl version        (len 1)
 *   0x01 awdl sync params    (len 2: channel sequence step, top master flag)
 *   0x0b awdl channel sequence
 * A real AirDrop peer will not act on these; the point of this tool is to find
 * out whether the firmware transmits the frame at all, which is observable
 * with the monitor path that already works.
 */
static size_t build_action_frame(uint8_t *frame, const uint8_t src[6],
				 const uint8_t dst[6], int awdl_signature,
				 uint16_t seq)
{
	uint8_t *p = frame;
	uint8_t *body;

	put16(p + 0, 0x00d0);            /* action */
	put16(p + 2, 0x0000);            /* duration */
	if (dst)
		memcpy(p + 4, dst, 6);   /* addr1: unicast to a real peer */
	else
		memset(p + 4, 0xff, 6);  /* addr1: broadcast */
	memcpy(p + 10, src, 6);          /* addr2: our address */
	if (dst)
		memcpy(p + 16, dst, 6);  /* addr3: BSSID of the addressed peer */
	else
		memcpy(p + 16, awdl_bssid, 6);
	put16(p + 22, (uint16_t)(seq << 4));

	body = p + 24;
	/* Public action when a unicast peer is addressed: that is the frame
	 * shape cfg80211 already allows on a station without the 0002 patch,
	 * so an ACK from it proves the radio transmitted, not that a gate
	 * opened.  The AWDL vendor signature is only used for the broadcast
	 * probe, where patch 0002 is what lets it through. */
	body[0] = dst ? 0x04 : (awdl_signature ? 0x7f : 0x00);
	body[1] = 0x00;                  /* Apple OUI */
	body[2] = 0x17;
	body[3] = 0xf2;
	body[4] = 0x08;                  /* AWDL */
	body[5] = 0x10;                  /* AWDL action version */
	body[6] = 0x00;                  /* driver patch 0002 gate: 0 or 3 */
	body[7] = 0x00;                  /* TLV 0x00: version */
	body[8] = 0x01;
	body[9] = 0x0d;
	body[10] = 0x01;                 /* TLV 0x01: sync params */
	body[11] = 0x02;
	body[12] = 0x10;                 /* channel sequence step */
	body[13] = 0x20;                 /* master indication */
	body[14] = 0x0b;                 /* TLV 0x0b: channel sequence */
	body[15] = 0x02;
	body[16] = 0x06;                 /* social channel 6 */
	body[17] = 0x00;
	return 24 + 18;
}

static int send_frame(int fd, int family, unsigned int ifindex, uint32_t freq,
		      const uint8_t *frame, size_t frame_len, int want_ack,
		      uint64_t *cookie_out)
{
	uint8_t req[1024] = {0};
	struct nlmsghdr *nh = (struct nlmsghdr *)req;
	struct genlmsghdr *gh;
	struct sockaddr_nl sa = {.nl_family = AF_NETLINK};
	uint8_t resp[4096];
	size_t off;
	ssize_t n;
	static uint32_t seq_counter = 2;
	uint32_t req_seq = ++seq_counter;

	nh->nlmsg_type = (uint16_t)family;
	nh->nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
	nh->nlmsg_seq = req_seq;
	off = NLMSG_HDRLEN;
	gh = (struct genlmsghdr *)(req + off);
	gh->cmd = NL80211_CMD_FRAME;
	gh->version = 0;
	off += GENL_HDRLEN;

	if (nla_put_u32(req, sizeof(req), &off, NL80211_ATTR_IFINDEX, ifindex) ||
	    nla_put_u32(req, sizeof(req), &off, NL80211_ATTR_WIPHY_FREQ, freq) ||
	    nla_put(req, sizeof(req), &off, NL80211_ATTR_FRAME, frame,
		    (uint16_t)frame_len))
		return -EMSGSIZE;
	/* Without DONT_WAIT_FOR_ACK the driver asks the firmware for an ACK and
	 * cfg80211 reports the outcome as an MLME multicast event, which is the
	 * only way to prove from inside this device that the frame was actually
	 * radiated rather than merely accepted. */
	if (!want_ack &&
	    nla_put_flag(req, sizeof(req), &off, NL80211_ATTR_DONT_WAIT_FOR_ACK))
		return -EMSGSIZE;
	nh->nlmsg_len = (uint32_t)off;

	if (sendto(fd, req, nh->nlmsg_len, 0, (struct sockaddr *)&sa,
		   sizeof(sa)) < 0)
		return -errno;

	/* The socket also carries MLME multicast events, and with --ack the TX
	 * status arrives on it while we are still waiting for the request ACK.
	 * Read until the message that belongs to this request shows up; a
	 * different nlmsg_seq is an event, not an answer.
	 *
	 * cfg80211 answers CMD_FRAME with two messages: a genl reply of type
	 * `family` carrying NL80211_ATTR_COOKIE, then the NLMSG_ERROR ack. The
	 * cookie is what ties the later NL80211_CMD_FRAME_TX_STATUS event back to
	 * this frame, so it has to be picked up here rather than ignored. */
	if (cookie_out)
		*cookie_out = 0;
	for (;;) {
		struct nlmsghdr *rn;
		struct nlmsgerr *e;

		n = recv(fd, resp, sizeof(resp), 0);
		if (n < 0) {
			if (errno == EAGAIN || errno == EWOULDBLOCK)
				return -ETIMEDOUT;
			return -errno;
		}
		rn = (struct nlmsghdr *)resp;
		if (getenv("MARIONETTE_DEBUG"))
			fprintf(stderr,
				"reply: n=%zd type=%u len=%u seq=%u (want seq=%u)\n",
				n, rn->nlmsg_type, rn->nlmsg_len,
				rn->nlmsg_seq, req_seq);
		if (rn->nlmsg_type == (uint16_t)family &&
		    rn->nlmsg_seq == req_seq && cookie_out) {
			const struct nlattr *c =
				nla_find(rn, NL80211_ATTR_COOKIE);

			if (c && c->nla_len >= sizeof(*c) + 8)
				memcpy(cookie_out, nla_data(c), 8);
		}
		if (rn->nlmsg_type != NLMSG_ERROR || rn->nlmsg_seq != req_seq)
			continue;
		e = (struct nlmsgerr *)NLMSG_DATA(rn);
		return e->error;
	}
}

/* Send a request and wait for its NLMSG_ERROR ack. */
static int genl_call(int fd, int family, uint8_t cmd, uint8_t *req,
		     size_t req_len, uint32_t req_seq){
	struct sockaddr_nl sa = {.nl_family = AF_NETLINK};
	uint8_t resp[4096];
	struct nlmsghdr *nh = (struct nlmsghdr *)req;

	nh->nlmsg_type = (uint16_t)family;
	nh->nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
	nh->nlmsg_seq = req_seq;
	nh->nlmsg_len = (uint32_t)req_len;
	((struct genlmsghdr *)(req + NLMSG_HDRLEN))->cmd = cmd;

	if (sendto(fd, req, req_len, 0, (struct sockaddr *)&sa, sizeof(sa)) < 0)
		return -errno;

	for (;;) {
		ssize_t n = recv(fd, resp, sizeof(resp), 0);
		struct nlmsghdr *rn = (struct nlmsghdr *)resp;

		if (n <= 0)
			return -ETIMEDOUT;
		if (rn->nlmsg_type != NLMSG_ERROR || rn->nlmsg_seq != req_seq)
			continue;
		return ((struct nlmsgerr *)NLMSG_DATA(rn))->error;
	}
}

/* Monotonic milliseconds, for ROC bookkeeping only. */
static long long now_ms(void)
{
	struct timespec t;

	clock_gettime(CLOCK_MONOTONIC, &t);
	return (long long)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

/* Ask cfg80211 to keep the radio on `freq` for `duration_ms`.
 *
 * NL80211_ATTR_WIPHY_FREQ on CMD_FRAME is only advisory: wlan_hdd_mgmt_tx
 * ignores it, so the frame leaves on the station's current channel. Measured
 * on the phone: 80 frames "submitted" on 2437, and a monitor parked on 2437
 * saw none of them and the Mac never answered. A remain-on-channel reservation
 * is what actually moves the radio.
 *
 * The wiphy caps the reservation at 2000 ms (2048 returns -EINVAL) when no
 * monitor vdev exists, and at 200 ms once one does, so a caller that runs
 * longer has to re-arm.  See roc_keep. */
static int roc_start(int fd, int family, unsigned int ifindex, uint32_t freq,
		     uint32_t duration_ms)
{
	uint8_t req[512] = {0};
	size_t off = NLMSG_HDRLEN;
	static uint32_t seq_counter = 1000;
	uint32_t req_seq = ++seq_counter;

	off += GENL_HDRLEN;
	if (nla_put_u32(req, sizeof(req), &off, NL80211_ATTR_IFINDEX, ifindex) ||
	    nla_put_u32(req, sizeof(req), &off, NL80211_ATTR_WIPHY_FREQ, freq) ||
	    nla_put_u32(req, sizeof(req), &off, NL80211_ATTR_DURATION,
			duration_ms))
		return -EMSGSIZE;
	return genl_call(fd, family, NL80211_CMD_REMAIN_ON_CHANNEL, req, off,
			 req_seq);
}

/* Keep a ROC reservation alive across a long plan.
 *
 * The duration is not free to choose.  Without a monitor vdev the wiphy accepts
 * 2000 ms (2048 returns -EINVAL), but as soon as the monitor exists the same
 * call is capped at 200 ms: measured, 200 returns 0 and 250 already returns
 * -EINVAL, on every social channel, while the station's own channel is refused
 * outright.  So a long plan has to be covered by a chain of 200 ms reservations
 * and the re-arm has to happen before the current one expires. */
#define ROC_SLICE_MS 200
/* Re-arm with this much of the current reservation left.  One netlink round
 * trip costs about 17 ms on this target, so 50 ms leaves room without throwing
 * away a useful part of the window. */
#define ROC_MARGIN_MS 50

static int roc_keep(int fd, int family, unsigned int ifindex, uint32_t freq,
		    long long *deadline_ms, int *rearms)
{
	long long now = now_ms();
	int ret;

	if (*deadline_ms && now + ROC_MARGIN_MS < *deadline_ms)
		return 0;
	ret = roc_start(fd, family, ifindex, freq, ROC_SLICE_MS);
	if (ret == -EBUSY)
		/* A reservation is still running: cfg80211 refuses to replace
		 * one in place.  Let it expire and try again on the next frame
		 * rather than tearing down a window we are already inside. */
		return 0;
	if (ret < 0)
		return -1;
	*deadline_ms = now + ROC_SLICE_MS;
	(*rearms)++;
	return 0;
}

/* Ask cfg80211 to deliver management frames of one type to this socket.
 *
 * This only registers interest on the host. Whether anything arrives depends
 * entirely on the target's RX filter, which is the whole point of the probe:
 * a zero count here is firmware evidence, not a host-side mistake. */
static int register_frame(int fd, int family, unsigned int ifindex,
			  uint16_t frame_type, const uint8_t *match,
			  size_t match_len)
{
	uint8_t req[512] = {0};
	size_t off = NLMSG_HDRLEN + GENL_HDRLEN;

	if (nla_put_u32(req, sizeof(req), &off, NL80211_ATTR_IFINDEX, ifindex) ||
	    nla_put(req, sizeof(req), &off, NL80211_ATTR_FRAME_TYPE,
		    &frame_type, sizeof(frame_type)) ||
	    (match_len &&
	     nla_put(req, sizeof(req), &off, NL80211_ATTR_FRAME_MATCH, match,
		     (uint16_t)match_len)))
		return -EMSGSIZE;
	return genl_call(fd, family, NL80211_CMD_REGISTER_FRAME, req, off, 700);
}

/* Listen for NL80211_CMD_FRAME events for `ms` and dump what arrives.
 *
 * Returns the number of frames seen. A frame here means the target handed a
 * management frame up to the host: for AWDL that is the capability the whole
 * resident design depends on, because without RX there is no sync, no mDNS and
 * no AirDrop discovery. */
static int rx_listen(int fd, int family, uint32_t ms, int verbose)
{
	struct timeval tv = {.tv_sec = ms / 1000, .tv_usec = (ms % 1000) * 1000};
	struct timeval old;
	socklen_t optlen = sizeof(old);
	uint8_t resp[8192];
	int frames = 0;

	if (getsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &old, &optlen) < 0)
		memset(&old, 0, sizeof(old));
	setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

	for (;;) {
		ssize_t n = recv(fd, resp, sizeof(resp), 0);
		struct nlmsghdr *rn = (struct nlmsghdr *)resp;

		if (n <= 0)
			break;
		if (rn->nlmsg_type != (uint16_t)family)
			continue;
		if (((const struct genlmsghdr *)NLMSG_DATA(rn))->cmd !=
		    NL80211_CMD_FRAME)
			continue;

		const struct nlattr *f = nla_find(rn, NL80211_ATTR_FRAME);

		frames++;
		if (!f)
			continue;
		int len = (int)f->nla_len - (int)sizeof(*f);
		const uint8_t *p = nla_data(f);
		int show = len < 40 ? len : 40;

		if (verbose) {
			printf("rx frame %d len=%d ", frames, len);
			for (int i = 0; i < show; i++)
				printf("%02x", p[i]);
			printf("%s\n", len > show ? "..." : "");
		}
	}

	setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &old, sizeof(old));
	return frames;
}

/* Discard any queued netlink messages.
 *
 * This driver hands every CMD_FRAME the SAME cookie (256), so a TX status can
 * only be tied to a frame by arrival order, not by its cookie value. A status
 * that arrives after its own frame's wait has given up would otherwise be
 * consumed by the NEXT frame and reported as that frame's ACK -- a false
 * positive. Draining immediately before each send makes "the first status after
 * the send belongs to the send" true. */
static void drain_events(int fd)
{
	uint8_t buf[4096];

	while (recv(fd, buf, sizeof(buf), MSG_DONTWAIT) > 0)
		;
}

/* Wait for NL80211_CMD_FRAME_TX_STATUS for one cookie.
 *
 * Returns 1 = acked, 0 = not acked, -1 = no status arrived. Only a unicast frame
 * ever produces a meaningful ACK: the receiver's hardware answers a frame
 * addressed to its MAC while it is awake on that channel, so an ACK here is
 * evidence that the frame was radiated and heard. Broadcast frames are never
 * ACKed and are sent with DONT_WAIT_FOR_ACK instead of being waited on. */
static int wait_tx_status(int fd, int family, uint64_t cookie, int timeout_ms)
{
	struct timeval tv = {.tv_sec = timeout_ms / 1000,
			     .tv_usec = (timeout_ms % 1000) * 1000};
	struct timeval old;
	socklen_t optlen = sizeof(old);
	uint8_t resp[4096];
	int acked = -1;

	if (getsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &old, &optlen) < 0)
		memset(&old, 0, sizeof(old));
	setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

	for (;;) {
		ssize_t n = recv(fd, resp, sizeof(resp), 0);
		struct nlmsghdr *rn = (struct nlmsghdr *)resp;

		if (n <= 0)
			break;
		if (rn->nlmsg_type != (uint16_t)family)
			continue;
		if (((const struct genlmsghdr *)NLMSG_DATA(rn))->cmd !=
		    NL80211_CMD_FRAME_TX_STATUS)
			continue;

		const struct nlattr *c = nla_find(rn, NL80211_ATTR_COOKIE);
		uint64_t got = 0;

		if (c && c->nla_len >= sizeof(*c) + 8)
			memcpy(&got, nla_data(c), 8);
		if (got != cookie)
			continue;
		acked = nla_find(rn, NL80211_ATTR_ACK) ? 1 : 0;
		break;
	}

	setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &old, sizeof(old));
	return acked;
}


/* AWDL data frame: three-address 802.11 data + LLC/SNAP 00:17:f2/08/00 and the
 * AWDL data head, then an IPv6/UDP payload.  No radiotap: this path hands the
 * bytes straight to WMI MGMT_TX, which takes a bare 802.11 frame.
 *
 * The point of this mode is the cfg80211 subtype gate.  nl80211 checks
 *     mgmt_stypes[iftype].tx & BIT(subtype >> 4)
 * and the fuxi wiphy advertises every management subtype for `managed`.  A
 * data frame has subtype 0, so BIT(0) is set and the frame passes the check
 * even though it is not a management frame.  If the driver and firmware then
 * accept it, arbitrary 802.11 frame injection exists through a supported path
 * and the exception-path work becomes unnecessary.
 */
static size_t build_data_frame(uint8_t *frame, const uint8_t src[6],
			       const uint8_t dst[6], uint16_t seq)
{
	uint8_t *p = frame;

	put16(p + 0, 0x0008);            /* data */
	put16(p + 2, 0x0000);
	memcpy(p + 4, dst, 6);           /* addr1 */
	memcpy(p + 10, src, 6);          /* addr2 */
	memcpy(p + 16, dst, 6);          /* addr3: BSSID of the addressed peer */
	put16(p + 22, (uint16_t)(seq << 4));

	p += 24;
	/* LLC/SNAP: Apple OUI, AWDL data */
	p[0] = 0xaa; p[1] = 0xaa; p[2] = 0x03;
	p[3] = 0x00; p[4] = 0x17; p[5] = 0xf2;
	p[6] = 0x08; p[7] = 0x00;
	put16(p + 8, seq);               /* AWDL data head */
	put16(p + 10, 0);                /* seq */
	put16(p + 12, 0);                /* pad */
	p[14] = 0x86; p[15] = 0xdd;      /* ethertype: IPv6 */
	p += 16;

	/* IPv6 + UDP, structurally valid, multicast destination. */
	p[0] = 0x60; p[1] = 0; p[2] = 0; p[3] = 0;
	put16(p + 4, 8);
	p[6] = 17; p[7] = 64;
	memset(p + 8, 0, 16);
	p[8] = 0xfe; p[9] = 0x80; p[24] = 0xff; p[25] = 0x02;
	p[39] = 1;
	put16(p + 40, 5353); put16(p + 42, 5353);
	put16(p + 44, 8); put16(p + 46, 0);
	return 24 + 16 + 48;
}

/* Replay a timed plan produced by `awdl-emit` (filin-rs).  One frame per line:
 *
 *     <offset_us> <KIND> <len> <hex>
 *
 * The plan carries ONE availability-window epoch for all of its frames, so the
 * offsets must be honoured relative to the first frame; regenerating each frame
 * on its own would advertise a phase that resets every time (see the awdl-emit
 * module comment).
 *
 * Why the bytes come from outside this tool: AWDL frames are owl's wire format,
 * not something to re-derive here.  Hand-building them in this file already
 * shipped two bugs -- addr3 set to the peer MAC instead of the AWDL BSSID, and
 * the AWDL data head written as the sequence number instead of 0x0403 -- both
 * of which make an Apple peer ignore the frame.
 *
 * DATA frames wait for the TX status; PSF/MIF are broadcast and are never
 * ACKed, so waiting for one would only ever report a timeout.
 *
 * The plan also holds a remain-on-channel reservation for the whole run. That
 * is not an optimisation: without it the frames are accepted by cfg80211 and
 * by the firmware and then radiated on whatever channel the station is tuned
 * to, which is indistinguishable from "the peer ignored us".
 */
static int run_plan(int fd, int family, unsigned int ifindex, uint32_t freq,
		    const char *path, int use_roc)
{
	FILE *fp = fopen(path, "r");
	char line[8192];
	uint8_t frame[2048];
	struct timespec t0;
	long long sent = 0, ok = 0, fail = 0;
	long long acked_n = 0, noack_n = 0, lost_n = 0;
	unsigned long long plan_span_us = 0;
	int last_err = 0;
	/* A remain-on-channel reservation is what actually moves the radio onto
	 * the social channel; ATTR_WIPHY_FREQ on CMD_FRAME is ignored by
	 * wlan_hdd_mgmt_tx. See the comment on roc_start. */
	long long roc_deadline_ms = 0;
	int roc_rearms = 0, roc_failed = 0;

	if (!fp) {
		fprintf(stderr, "open(%s): %s\n", path, strerror(errno));
		return -errno;
	}
	clock_gettime(CLOCK_MONOTONIC, &t0);
	/* Arm before the first frame, otherwise frame #1 is the one that goes out
	 * on the station channel and the whole run is misread as "the peer
	 * ignored us".  Off by default: on this target a reservation only time
	 * shares the radio with the station (measured: 0% ping loss, latency
	 * 14 -> 52 ms inside a 2 s reservation), so it cannot hold the social
	 * channel.  The capture slot is the mechanism that actually parks the
	 * radio there, and the two must not fight. */
	if (use_roc &&
	    roc_keep(fd, family, ifindex, freq, &roc_deadline_ms, &roc_rearms) < 0) {
		roc_failed++;
		fprintf(stderr,
			"warning: remain-on-channel on %u MHz failed; frames will "
			"leave on the station channel\n", freq);
	}
	while (fgets(line, sizeof(line), fp)) {
		char kind[16], hex[4096];
		unsigned long long off_us;
		uint64_t cookie = 0;
		int declared_len, want_ack, ret;
		size_t len, i;

		if (line[0] == '#')
			continue;
		if (sscanf(line, "%llu %15s %d %4095s", &off_us, kind,
			   &declared_len, hex) != 4)
			continue;
		if (off_us > plan_span_us)
			plan_span_us = off_us;
		len = strlen(hex) / 2;
		if (len == 0 || len > sizeof(frame)) {
			fprintf(stderr, "plan: bad frame length %zu\n", len);
			continue;
		}
		for (i = 0; i < len; i++) {
			unsigned int byte;

			if (sscanf(hex + 2 * i, "%2x", &byte) != 1)
				break;
			frame[i] = (uint8_t)byte;
		}
		if (i != len) {
			fprintf(stderr, "plan: bad hex\n");
			continue;
		}
		if ((size_t)declared_len != len)
			fprintf(stderr,
				"plan: %s declares %d bytes, line has %zu\n",
				kind, declared_len, len);

		{
			struct timespec now;
			long long elapsed_us;

			clock_gettime(CLOCK_MONOTONIC, &now);
			elapsed_us = (now.tv_sec - t0.tv_sec) * 1000000LL +
				     (now.tv_nsec - t0.tv_nsec) / 1000;
			if ((long long)off_us > elapsed_us)
				usleep((useconds_t)((long long)off_us -
						    elapsed_us));
		}

		want_ack = !strcmp(kind, "DATA");
		if (want_ack)
			drain_events(fd);
		ret = send_frame(fd, family, ifindex, freq, frame, len, want_ack,
				 &cookie);
		sent++;
		if (ret == 0) {
			ok++;
		} else {
			fail++;
			last_err = ret;
		}
		if (ret == 0 && want_ack) {
			int acked = wait_tx_status(fd, family, cookie, 300);

			if (acked == 1) {
				acked_n++;
				printf("%-4s off=%8llu len=%3zu cookie=%llu ACKED\n",
				       kind, off_us, len,
				       (unsigned long long)cookie);
			} else if (acked == 0) {
				noack_n++;
				printf("%-4s off=%8llu len=%3zu cookie=%llu no_ack\n",
				       kind, off_us, len,
				       (unsigned long long)cookie);
			} else {
				lost_n++;
				printf("%-4s off=%8llu len=%3zu cookie=%llu no_status\n",
				       kind, off_us, len,
				       (unsigned long long)cookie);
			}
		} else {
			printf("%-4s off=%8llu len=%3zu ack_wait=%d %s\n", kind,
			       off_us, len, want_ack,
			       ret == 0 ? "submitted" : "REJECTED");
			if (ret)
				printf("     err=%d (%s)\n", ret,
				       strerror(ret < 0 ? -ret : ret));
		}
		/* Top the reservation up in the gap *after* the frame, never before
		 * it. Two reasons: a re-arm costs one netlink round trip (~17 ms on
		 * this target), and paying it ahead of the send would push the frame
		 * off its plan offset; and genl_call() discards messages that belong
		 * to another sequence, so re-arming between the send and
		 * wait_tx_status() would swallow the very TX status it is waiting
		 * for. Doing it last keeps both intact. */
		if (use_roc &&
		    roc_keep(fd, family, ifindex, freq, &roc_deadline_ms,
			     &roc_rearms) < 0)
			roc_failed++;
	}
	fclose(fp);
	{
		struct timespec now;
		long long elapsed_us;

		clock_gettime(CLOCK_MONOTONIC, &now);
		elapsed_us = (now.tv_sec - t0.tv_sec) * 1000000LL +
			     (now.tv_nsec - t0.tv_nsec) / 1000;
		/* The plan is replayed against absolute offsets, so if the run takes
		 * longer than the plan spans, the sender fell behind and frames went
		 * out back-to-back instead of at the intended times. That silently
		 * destroys any timing measurement built on this run, so report it. */
		printf("elapsed_us=%lld plan_span_us=%lld%s\n", elapsed_us,
		       (long long)plan_span_us,
		       elapsed_us > (long long)plan_span_us + 50000 ? " FELL_BEHIND" : "");
	}
	printf("plan_frames=%lld submitted=%lld rejected=%lld "
	       "unicast_acked=%lld unicast_noack=%lld unicast_no_status=%lld "
	       "roc_rearms=%d roc_failed=%d last_errno=%d\n",
	       sent, ok, fail, acked_n, noack_n, lost_n, roc_rearms, roc_failed,
	       last_err);
	return fail ? 1 : 0;
}

int main(int argc, char **argv)
{
	const char *ifname;
	const char *plan_path = NULL;
	uint32_t freq;
	int rx_ms = 0;
	int roc_only_ms = 0;
	int rx_match_awdl = 1;
	uint16_t rx_type = 0x00d0;
	const char *rx_match_hex = NULL;
	int count = 1;
	uint8_t mac[6];
	uint8_t dst_mac[6];
	const uint8_t *dst = NULL;
	int awdl_signature = 1;
	int data_mode = 0;
	int want_ack = 0;
	int use_roc = 0;
	uint8_t frame[128];
	size_t frame_len;
	int fd, family, ifindex, i, ok = 0, fail = 0, last_err = 0;
	int ack_n = 0, noack_n = 0, lost_n = 0;

	if (argc < 3) {
		fprintf(stderr,
			"usage: %s <ifname> <freq_mhz> [count] [options]\n"
			"  --dst <mac>  address a real unicast peer instead of the\n"
			"               AWDL broadcast; the frame becomes a public\n"
			"               action frame so cfg80211 needs no patch\n"
			"  --ack        wait for the TX status instead of DONT_WAIT_FOR_ACK\n"
			"  --data       send a three-address data frame instead of an\n"
			"               action frame (tests the subtype gate)\n"
			"  --zero       negative control: category byte forced to 0x00,\n"
			"               which cfg80211 must refuse with ENOTCONN\n"
			"  --plan <f>   replay a timed frame plan from `awdl-emit`\n"
			"  --roc        hold a remain-on-channel reservation while the\n"
			"               plan runs; off by default because this target only\n"
			"               time shares it with the station, so frames still\n"
			"               mostly leave on the station channel\n"
			"  --rx-ms <n>  register for action frames, remain on <freq> for n\n"
			"               ms and dump what the target hands up\n"
			"  --roc-only <n> only reserve <freq> for n ms and report whether\n"
			"               cfg80211 accepted it; works on a monitor vdev,\n"
			"               where frame registration is refused\n"
			"  --match none with --rx-ms: match 0x7f (all vendor action frames)\n"
			"  --type <hex> frame type to register for (default 0x00d0 action)\n"
			"  --match-hex <hex> arbitrary match bytes (body after the MAC header)\n"
			"               instead of only the AWDL OUI prefix\n",
			argv[0]);
		return 2;
	}
	ifname = argv[1];
	freq = (uint32_t)strtoul(argv[2], NULL, 0);
	if (argc >= 4 && argv[3][0] != '-')
		count = atoi(argv[3]);
	if (count <= 0)
		count = 1;
	for (i = 3; i < argc; i++) {
		if (!strcmp(argv[i], "--zero")) {
			awdl_signature = 0;
		} else if (!strcmp(argv[i], "--data")) {
			data_mode = 1;
		} else if (!strcmp(argv[i], "--ack")) {
			want_ack = 1;
		} else if (!strcmp(argv[i], "--roc")) {
			use_roc = 1;
		} else if (!strcmp(argv[i], "--rx-ms") && i + 1 < argc) {
			rx_ms = atoi(argv[++i]);
		} else if (!strcmp(argv[i], "--roc-only") && i + 1 < argc) {
			roc_only_ms = atoi(argv[++i]);
		} else if (!strcmp(argv[i], "--match-hex") && i + 1 < argc) {
			rx_match_hex = argv[++i];
		} else if (!strcmp(argv[i], "--type") && i + 1 < argc) {
			rx_type = (uint16_t)strtoul(argv[++i], NULL, 0);
		} else if (!strcmp(argv[i], "--match") && i + 1 < argc) {
			rx_match_awdl = strcmp(argv[++i], "none") != 0;
		} else if (!strcmp(argv[i], "--plan") && i + 1 < argc) {
			plan_path = argv[++i];
		} else if (!strcmp(argv[i], "--dst") && i + 1 < argc) {
			unsigned int b[6];

			if (sscanf(argv[++i], "%x:%x:%x:%x:%x:%x", &b[0], &b[1],
				   &b[2], &b[3], &b[4], &b[5]) != 6) {
				fprintf(stderr, "bad --dst\n");
				return 2;
			}
			for (int k = 0; k < 6; k++)
				dst_mac[k] = (uint8_t)b[k];
			dst = dst_mac;
		}
	}

	if (iface_mac(ifname, mac)) {
		fprintf(stderr, "iface_mac(%s): %s\n", ifname, strerror(errno));
		return 3;
	}

	fd = socket(AF_NETLINK, SOCK_RAW, NETLINK_GENERIC);
	if (fd < 0) {
		perror("socket(NETLINK_GENERIC)");
		return 3;
	}
	{
		struct timeval tv = {.tv_sec = 2, .tv_usec = 0};

		setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
	}
	family = resolve_nl80211(fd);
	if (family < 0) {
		fprintf(stderr, "resolve nl80211 family failed: %d\n", family);
		return 3;
	}
	ifindex = (int)if_nametoindex(ifname);
	if (!ifindex) {
		fprintf(stderr, "if_nametoindex(%s) failed\n", ifname);
		return 3;
	}
	{
		int group = join_mlme_group(fd);

		if (group < 0)
			fprintf(stderr,
				"warning: could not join the mlme group; "
				"per-frame ACK reporting will be unavailable\n");
		else
			printf("mlme_group=%d (subscribed)\n", group);
	}

	printf("iface=%s ifindex=%d family=%d freq=%u src=%02x:%02x:%02x:%02x:%02x:%02x\n",
	       ifname, ifindex, family, freq, mac[0], mac[1], mac[2], mac[3],
	       mac[4], mac[5]);

	if (roc_only_ms > 0) {
		/* Reserve the channel and nothing else.
		 *
		 * Registering for management frames is not allowed on a monitor
		 * interface (cfg80211 answers -EOPNOTSUPP), so the ordinary RX
		 * probe cannot be used to ask whether a reservation is accepted
		 * on that vdev.  This mode answers exactly that question. */
		int ret = roc_start(fd, family, (unsigned int)ifindex, freq,
				    (uint32_t)roc_only_ms);

		printf("roc_start(%s, freq=%u, %d ms) -> %d\n", ifname, freq,
		       roc_only_ms, ret);
		if (ret == 0)
			usleep((useconds_t)roc_only_ms * 1000);
		close(fd);
		return ret == 0 ? 0 : 1;
	}

	if (rx_ms > 0) {		/* Differential probe: registering only the AWDL prefix and then all
		 * action frames separates "the target never delivers action frames"
		 * from "it delivers them but filters AWDL out". */
		static const uint8_t awdl_prefix[] = {0x7f, 0x00, 0x17,
						      0xf2, 0x08, 0x10};
		uint8_t match[sizeof(awdl_prefix)];
		size_t match_len = 0;
		int ret;

		if (rx_match_hex) {
			/* Arbitrary match bytes, for controls. cfg80211 compares this
			 * against the frame body after the 24-byte MAC header. */
			for (size_t k = 0; rx_match_hex[2 * k] &&
					   rx_match_hex[2 * k + 1] &&
					   k < sizeof(match); k++) {
				unsigned int b;

				if (sscanf(rx_match_hex + 2 * k, "%2x", &b) != 1)
					break;
				match[k] = (uint8_t)b;
				match_len = k + 1;
			}
		} else if (rx_match_awdl) {
			memcpy(match, awdl_prefix, sizeof(awdl_prefix));
			match_len = sizeof(awdl_prefix);
		} else {
			/* NL80211_ATTR_FRAME_MATCH is mandatory in this kernel:
			 * nl80211_register_mgmt() returns -EINVAL when it is absent,
			 * which looks exactly like a driver limitation. A one-byte
			 * match on 0x7f (vendor-specific action category) is the
			 * broadest registration that still carries a match. */
			match[0] = 0x7f;
			match_len = 1;
		}
		ret = register_frame(fd, family, (unsigned int)ifindex, rx_type,
				     match, match_len);
		printf("register_frame(type=0x%04x, match=%s len=%zu) -> %d\n",
		       rx_type, rx_match_hex ? rx_match_hex : (rx_match_awdl ? "awdl" : "0x7f"), match_len, ret);
		if (ret == 0) {
			ret = roc_start(fd, family, (unsigned int)ifindex, freq,
					(uint32_t)rx_ms);
			printf("roc_start(freq=%u, %d ms) -> %d\n", freq, rx_ms,
			       ret);
			if (ret == 0) {
				int frames = rx_listen(fd, family,
						       (uint32_t)rx_ms, 1);

				printf("rx_frames=%d\n", frames);
			}
		}
		close(fd);
		return 0;
	}

	if (plan_path) {
		int ret = run_plan(fd, family, (unsigned int)ifindex, freq,
				   plan_path, use_roc);

		close(fd);
		return ret ? 1 : 0;
	}

	for (i = 0; i < count; i++) {
		int ret;
		uint64_t cookie = 0;

		if (data_mode) {
			uint8_t peer[6];

			if (dst) {
				memcpy(peer, dst, 6);
			} else {
				memset(peer, 0xff, 6);
			}
			frame_len = build_data_frame(frame, mac, peer,
						     (uint16_t)(0x100 + i));
		} else {
			frame_len = build_action_frame(frame, mac, dst,
						       awdl_signature,
						       (uint16_t)(0x100 + i));
		}
		ret = send_frame(fd, family, (unsigned int)ifindex, freq, frame,
				 frame_len, want_ack,
				 want_ack ? &cookie : NULL);
		if (ret == 0) {
			ok++;
		} else {
			fail++;
			last_err = ret;
			printf("frame %d failed: %d (%s)\n", i, ret,
			       strerror(ret < 0 ? -ret : ret));
		}
		/* Only the plan path used to wait for the TX status, and only
		 * for DATA frames, so --ack on a single action frame reported
		 * nothing and the flag looked accepted when it was inert.
		 * Wait here too, otherwise "accepted by the host" keeps being
		 * mistaken for "heard over the air". */
		if (ret == 0 && want_ack) {
			int acked = wait_tx_status(fd, family, cookie, 300);

			if (acked == 1) {
				ack_n++;
				printf("frame %d len=%zu cookie=%llu ACKED\n", i,
				       frame_len, (unsigned long long)cookie);
			} else if (acked == 0) {
				noack_n++;
				printf("frame %d len=%zu cookie=%llu no_ack\n", i,
				       frame_len, (unsigned long long)cookie);
			} else {
				lost_n++;
				printf("frame %d len=%zu cookie=%llu no_status\n",
				       i, frame_len, (unsigned long long)cookie);
			}
		}
		usleep(50000);
	}
	printf("frame_bytes=%zu sent=%d ok=%d fail=%d "
	       "acked=%d no_ack=%d no_status=%d last_errno=%d\n",
	       frame_len, count, ok, fail, ack_n, noack_n, lost_n, last_err);
	printf("NOTE: an accepted NL80211_CMD_FRAME proves host and firmware "
	       "accepted the submission. Over-the-air delivery needs the monitor "
	       "path to observe it, or --ack plus the MLME TX status event.\n");
	close(fd);
	return fail ? 1 : 0;
}
