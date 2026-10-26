/* SPDX-License-Identifier: GPL-3.0-only
 * Bounded nl80211 AWDL management-path diagnostic. Never changes interface
 * type, con_mode, association, power saving or the Wi-Fi switch.
 * A cookie/ACK is not proof of over-the-air transmission or AirDrop support.
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/genetlink.h>
#include <linux/netlink.h>
#include <linux/nl80211.h>
#include <net/if.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

static uint32_t sequence;
static unsigned rx_awdl, tx_status;
static unsigned roc_ready, roc_expired;
static uint64_t tx_cookie;
static bool cookie_seen;
static bool requesting_tx;
static bool capture_on_roc, capture_done, capture_failed, roc_cookie_seen, roc_pending;
static uint64_t roc_cookie, pending_roc_cookie;
static uint32_t capture_frequency, pending_roc_frequency;
static unsigned capture_slots;
static uint32_t mlme_group, probe_ifindex;
struct pending_status { uint64_t cookie; bool ack; };
static struct pending_status pending_statuses[8];
static size_t pending_status_count;
static int64_t now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

struct message { struct nlmsghdr n; struct genlmsghdr g; uint8_t data[2048]; };
static void init(struct message *m, uint16_t family, uint8_t command) {
    memset(m, 0, sizeof(*m));
    m->n.nlmsg_len = NLMSG_LENGTH(GENL_HDRLEN);
    m->n.nlmsg_type = family;
    m->n.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
    m->n.nlmsg_seq = ++sequence;
    m->g.cmd = command;
    m->g.version = 1;
}
static void attr(struct message *m, uint16_t type, const void *data, size_t len) {
    size_t start = NLMSG_ALIGN(m->n.nlmsg_len);
    size_t size = NLA_HDRLEN + len;
    if (start + NLA_ALIGN(size) > sizeof(*m)) abort();
    struct nlattr *a = (void *)((uint8_t *)m + start);
    a->nla_type = type;
    a->nla_len = size;
    if (len) memcpy((uint8_t *)a + NLA_HDRLEN, data, len);
    m->n.nlmsg_len = start + NLA_ALIGN(size);
}
static void u32attr(struct message *m, uint16_t type, uint32_t value) {
    attr(m, type, &value, sizeof(value));
}
static const void *find_attribute(const uint8_t *p, size_t remaining, uint16_t type, size_t *length) {
    while (remaining >= NLA_HDRLEN) {
        const struct nlattr *a = (const void *)p;
        if (a->nla_len < NLA_HDRLEN || a->nla_len > remaining) return NULL;
        if ((a->nla_type & NLA_TYPE_MASK) == type) {
            *length = a->nla_len - NLA_HDRLEN;
            return p + NLA_HDRLEN;
        }
        size_t step = NLA_ALIGN(a->nla_len);
        if (step > remaining) return NULL;
        remaining -= step;
        p += step;
    }
    return NULL;
}
static const void *find_attr(const struct nlmsghdr *n, uint16_t type, size_t *length) {
    if (n->nlmsg_len < NLMSG_LENGTH(GENL_HDRLEN)) return NULL;
    return find_attribute((const uint8_t *)NLMSG_DATA(n) + GENL_HDRLEN,
                          n->nlmsg_len - NLMSG_LENGTH(GENL_HDRLEN), type, length);
}
static uint32_t multicast_group(const struct nlmsghdr *n, const char *name) {
    size_t remaining = 0;
    const uint8_t *p = find_attr(n, CTRL_ATTR_MCAST_GROUPS, &remaining);
    while (p && remaining >= NLA_HDRLEN) {
        const struct nlattr *group = (const void *)p;
        if (group->nla_len < NLA_HDRLEN || group->nla_len > remaining) return 0;
        size_t nested = group->nla_len - NLA_HDRLEN, name_len = 0, id_len = 0;
        const char *group_name = find_attribute(p + NLA_HDRLEN, nested, CTRL_ATTR_MCAST_GRP_NAME, &name_len);
        const void *id = find_attribute(p + NLA_HDRLEN, nested, CTRL_ATTR_MCAST_GRP_ID, &id_len);
        if (group_name && name_len == strlen(name) + 1 && !memcmp(group_name, name, name_len) &&
            id && id_len == sizeof(uint32_t)) {
            uint32_t value;
            memcpy(&value, id, sizeof(value));
            return value;
        }
        size_t step = NLA_ALIGN(group->nla_len);
        if (step > remaining) return 0;
        remaining -= step;
        p += step;
    }
    return 0;
}
static void report_tx_status(uint64_t cookie, bool ack) {
    if (!cookie_seen || cookie != tx_cookie) return;
    tx_status++;
    printf("TX_STATUS cookie=%llu ack=%s (multicast has no peer ACK)\n",
           (unsigned long long)cookie, ack ? "true" : "false");
}
static void accept_tx_cookie(uint64_t cookie) {
    tx_cookie = cookie;
    cookie_seen = true;
    printf("REQUEST_COOKIE=%llu (request accepted; OTA unverified)\n", (unsigned long long)tx_cookie);
    for (size_t i = 0; i < pending_status_count; i++)
        report_tx_status(pending_statuses[i].cookie, pending_statuses[i].ack);
    pending_status_count = 0;
}
static bool capture_ready_matches(uint64_t cookie, uint32_t frequency) {
    return capture_on_roc && !capture_done && roc_cookie_seen &&
           cookie == roc_cookie && frequency == capture_frequency;
}
static void capture_ready(uint64_t cookie, uint32_t frequency) {
    if (!capture_ready_matches(cookie, frequency)) return;
    capture_done = true;
    int fd = open("/sys/module/kiwi_v2/parameters/marionette_awdl_capture_ms", O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        capture_failed = true;
        printf("ROC_CAPTURE_SLOT frequency=%u open_errno=%d\n", frequency, errno);
        return;
    }
    ssize_t count = write(fd, "120\n", 4);
    int write_error = count < 0 ? errno : count == 4 ? 0 : EIO;
    close(fd);
    if (write_error) capture_failed = true;
    else capture_slots++;
    printf("ROC_CAPTURE_SLOT frequency=%u duration_ms=120 result=%d normal_rx_restore_requested=true\n",
           frequency, -write_error);
}
static const uint8_t awdl_match[] = {0x7f, 0x00, 0x17, 0xf2, 0x08};
static void event(const struct nlmsghdr *n) {
    if (n->nlmsg_type < NLMSG_MIN_TYPE || n->nlmsg_len < NLMSG_LENGTH(GENL_HDRLEN)) return;
    const struct genlmsghdr *g = NLMSG_DATA(n);
    size_t length = 0;
    if (g->cmd == NL80211_CMD_REMAIN_ON_CHANNEL ||
        g->cmd == NL80211_CMD_CANCEL_REMAIN_ON_CHANNEL) {
        uint32_t index = 0, frequency = 0;
        uint64_t cookie = 0;
        const void *p = find_attr(n, NL80211_ATTR_IFINDEX, &length);
        if (!p || length != sizeof(index)) return;
        memcpy(&index, p, sizeof(index));
        if (index != probe_ifindex) return;
        p = find_attr(n, NL80211_ATTR_WIPHY_FREQ, &length);
        if (!p || length != sizeof(frequency)) return;
        memcpy(&frequency, p, sizeof(frequency));
        p = find_attr(n, NL80211_ATTR_COOKIE, &length);
        if (p && length == sizeof(cookie)) memcpy(&cookie, p, sizeof(cookie));
        if (g->cmd == NL80211_CMD_REMAIN_ON_CHANNEL) roc_ready++;
        else roc_expired++;
        printf("ROC_%s frequency=%u (driver event; RF dwell unverified)\n",
               g->cmd == NL80211_CMD_REMAIN_ON_CHANNEL ? "READY" : "EXPIRED", frequency);
        if (g->cmd == NL80211_CMD_REMAIN_ON_CHANNEL && capture_on_roc) {
            if (roc_cookie_seen) capture_ready(cookie, frequency);
            else { pending_roc_cookie = cookie; pending_roc_frequency = frequency; roc_pending = true; }
        }
        return;
    }
    const uint8_t *frame = find_attr(n, NL80211_ATTR_FRAME, &length);
    if (!frame || length < 29 || memcmp(frame + 24, awdl_match, sizeof(awdl_match))) return;
    if (g->cmd == NL80211_CMD_FRAME) {
        rx_awdl++;
        printf("AWDL_RX bytes=%zu source=%02x:%02x:%02x:%02x:%02x:%02x\n",
               length, frame[10], frame[11], frame[12], frame[13], frame[14], frame[15]);
    } else if (g->cmd == NL80211_CMD_FRAME_TX_STATUS) {
        size_t cookie_len = 0, ack_len = 0, index_len = 0;
        uint64_t cookie = 0;
        uint32_t index = 0;
        const void *index_data = find_attr(n, NL80211_ATTR_IFINDEX, &index_len);
        if (!index_data || index_len != sizeof(index)) return;
        memcpy(&index, index_data, sizeof(index));
        if (index != probe_ifindex) return;
        const void *p = find_attr(n, NL80211_ATTR_COOKIE, &cookie_len);
        if (!p || cookie_len != sizeof(cookie)) return;
        memcpy(&cookie, p, sizeof(cookie));
        bool ack = find_attr(n, NL80211_ATTR_ACK, &ack_len) != NULL;
        if (cookie_seen) report_tx_status(cookie, ack);
        else if (requesting_tx && pending_status_count < sizeof(pending_statuses) / sizeof(pending_statuses[0]))
            pending_statuses[pending_status_count++] = (struct pending_status){cookie, ack};
    }
}
/* Consume whole datagrams, including async events interleaved with ACKs.
 * Kernel sender and sequence are checked; all operations have deadlines. */
static int receive(int fd, uint32_t seq, int timeout_ms, uint16_t *family) {
    int64_t end = now_ms() + timeout_ms;
    bool response = false;
    while (now_ms() < end) {
        struct pollfd p = {.fd = fd, .events = POLLIN};
        int remaining_ms = (int)(end - now_ms());
        if (remaining_ms <= 0) break;
        int r = poll(&p, 1, remaining_ms);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return r == 0 ? -ETIMEDOUT : -errno;
        if (p.revents & (POLLERR | POLLHUP | POLLNVAL)) return -EIO;
        uint8_t bytes[65536];
        struct sockaddr_nl sender = {0};
        struct iovec iov = {.iov_base = bytes, .iov_len = sizeof(bytes)};
        struct msghdr msg = {.msg_name = &sender, .msg_namelen = sizeof(sender), .msg_iov = &iov, .msg_iovlen = 1};
        ssize_t count = recvmsg(fd, &msg, 0);
        if (count < 0) return -errno;
        if (msg.msg_flags & MSG_TRUNC) return -EMSGSIZE;
        if (sender.nl_pid != 0) continue;
        unsigned remaining = (unsigned)count;
        for (struct nlmsghdr *n = (void *)bytes; NLMSG_OK(n, remaining); n = NLMSG_NEXT(n, remaining)) {
            if (n->nlmsg_seq != seq || seq == 0) { event(n); continue; }
            if (n->nlmsg_type == NLMSG_ERROR) {
                if (n->nlmsg_len < NLMSG_LENGTH(sizeof(struct nlmsgerr))) return -EPROTO;
                int error = ((struct nlmsgerr *)NLMSG_DATA(n))->error;
                if (error) return error;
                if (family && !response) continue;
                return 0;
            }
            size_t length = 0;
            const void *data = find_attr(n, family ? CTRL_ATTR_FAMILY_ID : NL80211_ATTR_COOKIE, &length);
            if (family && data && length == sizeof(*family)) {
                memcpy(family, data, length); response = true;
                mlme_group = multicast_group(n, "mlme");
            }
            if (!family && data && length == sizeof(tx_cookie)) {
                uint64_t cookie;
                memcpy(&cookie, data, sizeof(cookie));
                if (requesting_tx) accept_tx_cookie(cookie);
                else {
                    printf("ROC_REQUEST_COOKIE=%llu (request accepted; RF dwell unverified)\n", (unsigned long long)cookie);
                    roc_cookie = cookie;
                    roc_cookie_seen = true;
                    if (roc_pending) capture_ready(pending_roc_cookie, pending_roc_frequency);
                }
            }
        }
    }
    return -ETIMEDOUT;
}
static int request(int fd, struct message *m, uint16_t *family) {
    struct sockaddr_nl kernel = {.nl_family = AF_NETLINK};
    requesting_tx = m->g.cmd == NL80211_CMD_FRAME && !family;
    if (m->g.cmd == NL80211_CMD_REMAIN_ON_CHANNEL && !family) {
        roc_cookie_seen = false;
        roc_pending = false;
        capture_done = false;
    }
    if (sendto(fd, m, m->n.nlmsg_len, 0, (void *)&kernel, sizeof(kernel)) != (ssize_t)m->n.nlmsg_len) return -errno;
    return receive(fd, m->n.nlmsg_seq, 3000, family);
}
static void result(const char *operation, int error) {
    printf("%s result=%d reason=%s\n", operation, error, error ? strerror(-error) : "accepted");
}
int main(int argc, char **argv) {
    setbuf(stdout, NULL);
    if (argc < 2 || argc > 4 || (argc >= 3 && strcmp(argv[2], "--transmit") && strcmp(argv[2], "--offchannel") && strcmp(argv[2], "--sweep") && strcmp(argv[2], "--capture-sweep") && strcmp(argv[2], "--direct-capture-sweep"))) {
        fprintf(stderr, "Usage: %s INTERFACE [--transmit|--offchannel [FREQUENCY_MHZ]|--sweep|--capture-sweep|--direct-capture-sweep]\nDefault: register AWDL RX for 5 seconds. --transmit sends one diagnostic MIF; --offchannel requests one 1000 ms listening dwell; --sweep listens on 6/44/149 three times; --capture-sweep requests 200ms dwells with one 120ms driver capture slot after the matching ready event; --direct-capture-sweep requests nine bounded 120ms monitor channel slots, restoring home after each.\n", argv[0]); return 2;
    }
    bool transmit = argc >= 3 && !strcmp(argv[2], "--transmit");
    bool offchannel = argc >= 3 && !strcmp(argv[2], "--offchannel");
    capture_on_roc = argc >= 3 && !strcmp(argv[2], "--capture-sweep");
    bool direct_capture = argc >= 3 && !strcmp(argv[2], "--direct-capture-sweep");
    bool sweep = (argc >= 3 && !strcmp(argv[2], "--sweep")) || capture_on_roc;
    if (sweep && argc != 3) return 2;
    if (direct_capture && argc != 3) return 2;
    unsigned index = if_nametoindex(argv[1]);
    if (!index) { perror("if_nametoindex"); return 1; }
    probe_ifindex = index;
    uint32_t frequency = 5220;
    if (argc == 4) {
        char *end; unsigned long parsed = strtoul(argv[3], &end, 10);
        /* Only non-DFS AWDL social channels; never bypass regulatory policy. */
        if (*end || (parsed != 2437 && parsed != 5220 && parsed != 5745)) return 2;
        frequency = parsed;
    }
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_GENERIC);
    if (fd < 0) { perror("socket"); return 1; }
    struct sockaddr_nl local = {.nl_family = AF_NETLINK};
    if (bind(fd, (void *)&local, sizeof(local))) { perror("bind"); close(fd); return 1; }
    struct message m;
    uint16_t family = 0;
    init(&m, GENL_ID_CTRL, CTRL_CMD_GETFAMILY);
    attr(&m, CTRL_ATTR_FAMILY_NAME, "nl80211", sizeof("nl80211"));
    int r = request(fd, &m, &family);
    if (r) { result("RESOLVE", r); close(fd); return 1; }
    printf("NL80211_FAMILY id=%u\n", family);
    /* TX status is multicast to the MLME group, unlike unicast RX frame
     * registration. Resolve its current ID after every cfg80211 reload. */
    if (!mlme_group || setsockopt(fd, SOL_NETLINK, NETLINK_ADD_MEMBERSHIP, &mlme_group, sizeof(mlme_group))) {
        fprintf(stderr, "MLME group subscription failed (group=%u): %s\n", mlme_group,
                mlme_group ? strerror(errno) : "missing group");
        close(fd); return 1;
    }
    printf("SUBSCRIBE_MLME group=%u result=accepted\n", mlme_group);
    init(&m, family, NL80211_CMD_REGISTER_FRAME);
    u32attr(&m, NL80211_ATTR_IFINDEX, index);
    uint16_t frame_type = 0xd0;
    attr(&m, NL80211_ATTR_FRAME_TYPE, &frame_type, sizeof(frame_type));
    attr(&m, NL80211_ATTR_FRAME_MATCH, awdl_match, sizeof(awdl_match));
    r = request(fd, &m, NULL);
    result("REGISTER_AWDL_RX", r);
    bool failed = r != 0;
    if (direct_capture && !failed) {
        const uint32_t frequencies[] = {2437, 5220, 5745};
        for (unsigned i = 0; i < 9; i++) {
            char command[32];
            int length = snprintf(command, sizeof(command), "%u:120\n", frequencies[i % 3]);
            int control = open("/sys/module/kiwi_v2/parameters/marionette_awdl_capture_ms", O_WRONLY | O_CLOEXEC);
            if (control < 0) { perror("direct capture open"); failed = true; break; }
            int64_t begin = now_ms();
            ssize_t count = write(control, command, (size_t)length);
            int write_error = count < 0 ? errno : count == length ? 0 : EIO;
            close(control);
            printf("DIRECT_CAPTURE_SLOT frequency=%u duration_ms=120 result=%d elapsed_ms=%lld\n",
                   frequencies[i % 3], -write_error, (long long)(now_ms() - begin));
            if (write_error) { failed = true; break; }
            capture_slots++;
            receive(fd, 0, 1000, NULL);
        }
    }
    if (sweep && !failed) {
        const uint32_t frequencies[] = {2437, 5220, 5745};
        for (unsigned i = 0; i < 9; i++) {
            capture_frequency = frequencies[i % 3];
            init(&m, family, NL80211_CMD_REMAIN_ON_CHANNEL);
            u32attr(&m, NL80211_ATTR_IFINDEX, index);
            u32attr(&m, NL80211_ATTR_WIPHY_FREQ, frequencies[i % 3]);
            u32attr(&m, NL80211_ATTR_DURATION, capture_on_roc ? 200 : 1000);
            r = request(fd, &m, NULL);
            printf("SWEEP slot=%u frequency=%u ", i, frequencies[i % 3]);
            result(capture_on_roc ? "LISTEN_OFFCHANNEL_200MS" : "LISTEN_OFFCHANNEL_1000MS", r);
            failed = failed || r != 0;
            receive(fd, 0, 1300, NULL);
        }
    }
    if (offchannel) {
        init(&m, family, NL80211_CMD_REMAIN_ON_CHANNEL);
        u32attr(&m, NL80211_ATTR_IFINDEX, index);
        u32attr(&m, NL80211_ATTR_WIPHY_FREQ, frequency);
        u32attr(&m, NL80211_ATTR_DURATION, 1000);
        r = request(fd, &m, NULL);
        result("LISTEN_OFFCHANNEL_1000MS", r);
        failed = failed || r != 0;
    }
    if (transmit) {
        char path[128], mac_text[32]; uint8_t mac[6]; unsigned octets[6];
        snprintf(path, sizeof(path), "/sys/class/net/%s/address", argv[1]);
        FILE *f = fopen(path, "r");
        if (!f || !fgets(mac_text, sizeof(mac_text), f) || sscanf(mac_text, "%x:%x:%x:%x:%x:%x", &octets[0], &octets[1], &octets[2], &octets[3], &octets[4], &octets[5]) != 6) {
            if (f) fclose(f);
            close(fd); return 1;
        }
        fclose(f);
        for (int i = 0; i < 6; i++) mac[i] = octets[i];
        /* Minimal parseable MIF with a version TLV. Does not advertise a
         * complete AWDL service and cannot make this a working receiver. */
        uint8_t frame[45] = {0xd0, 0};
        memset(frame + 4, 0xff, 6);
        memcpy(frame + 10, mac, 6);
        const uint8_t bssid[] = {0x00, 0x25, 0x00, 0xff, 0x94, 0x73};
        memcpy(frame + 16, bssid, 6);
        const uint8_t body[] = {0x7f,0x00,0x17,0xf2,0x08,0x10,0x03,0x00,0,0,0,0,0,0,0,0,0x15,0x02,0x00,0x34,0x01};
        memcpy(frame + 24, body, sizeof(body));
        init(&m, family, NL80211_CMD_FRAME);
        u32attr(&m, NL80211_ATTR_IFINDEX, index);
        u32attr(&m, NL80211_ATTR_WIPHY_FREQ, frequency);
        u32attr(&m, NL80211_ATTR_DURATION, 100);
        attr(&m, NL80211_ATTR_OFFCHANNEL_TX_OK, NULL, 0);
        attr(&m, NL80211_ATTR_FRAME, frame, sizeof(frame));
        r = request(fd, &m, NULL);
        result("AWDL_MGMT_TX", r);
        failed = failed || r != 0;
    }
    if (!sweep && !direct_capture) receive(fd, 0, 5000, NULL);
    printf("SUMMARY mode=%s rx_awdl=%u tx_status=%u cookie_seen=%s roc_ready=%u roc_expired=%u capture_slots=%u capture_failed=%s data_tx=untested ota=unverified\n",
           transmit ? "transmit" : direct_capture ? "direct_capture_sweep" : capture_on_roc ? "capture_sweep" : sweep ? "sweep" : offchannel ? "listen_offchannel" : "listen", rx_awdl, tx_status,
           cookie_seen ? "true" : "false", roc_ready, roc_expired, capture_slots, capture_failed ? "true" : "false");
    close(fd); /* unregisters this socket's frame subscription */
    return failed || capture_failed || ((capture_on_roc || direct_capture) && capture_slots != 9) ? 3 : 0;
}
