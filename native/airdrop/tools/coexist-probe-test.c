/* SPDX-License-Identifier: GPL-3.0-only */
#define main coexist_probe_main
#include "coexist-probe.c"
#undef main
#include <assert.h>

int main(void) {
    struct message m;
    size_t length = 999;
    init(&m, 30, NL80211_CMD_FRAME);
    assert(!find_attr(&m.n, NL80211_ATTR_COOKIE, &length));
    uint64_t cookie = 0x123456789abcdef0ULL, decoded;
    attr(&m, NL80211_ATTR_COOKIE, &cookie, sizeof(cookie));
    const void *data = find_attr(&m.n, NL80211_ATTR_COOKIE, &length);
    assert(data && length == sizeof(cookie));
    memcpy(&decoded, data, length);
    assert(decoded == cookie);
    struct message original = m;
    // Every truncated prefix must be rejected, including partial attr headers.
    for (unsigned n = 0; n < original.n.nlmsg_len; n++) {
        m = original;
        m.n.nlmsg_len = n;
        assert(!find_attr(&m.n, NL80211_ATTR_COOKIE, &length));
    }
    m = original;
    struct nlattr *a = (void *)m.data;
    a->nla_len = 0;
    assert(!find_attr(&m.n, NL80211_ATTR_COOKIE, &length));
    a->nla_len = UINT16_MAX;
    assert(!find_attr(&m.n, NL80211_ATTR_COOKIE, &length));
    // Skip an odd-sized attribute using its aligned size, mask flag bits.
    init(&m, 30, NL80211_CMD_FRAME);
    attr(&m, 900, "x", 1);
    attr(&m, NL80211_ATTR_COOKIE | NLA_F_NET_BYTEORDER, &cookie, sizeof(cookie));
    data = find_attr(&m.n, NL80211_ATTR_COOKIE, &length);
    assert(data && length == sizeof(cookie));
    // A zero-length ACK flag remains present; it is not absent.
    attr(&m, NL80211_ATTR_ACK, NULL, 0);
    data = find_attr(&m.n, NL80211_ATTR_ACK, &length);
    assert(data && length == 0);

    // Resolve MLME by its returned nested name, never a hardcoded group ID.
    struct message members, groups;
    init(&members, 30, 0);
    attr(&members, CTRL_ATTR_MCAST_GRP_NAME, "mlme", sizeof("mlme"));
    uint32_t group_id = 83;
    u32attr(&members, CTRL_ATTR_MCAST_GRP_ID, group_id);
    init(&groups, 30, 0);
    attr(&groups, 1 | NLA_F_NESTED, members.data,
         members.n.nlmsg_len - NLMSG_LENGTH(GENL_HDRLEN));
    init(&m, GENL_ID_CTRL, CTRL_CMD_NEWFAMILY);
    attr(&m, CTRL_ATTR_MCAST_GROUPS | NLA_F_NESTED, groups.data,
         groups.n.nlmsg_len - NLMSG_LENGTH(GENL_HDRLEN));
    assert(multicast_group(&m.n, "mlme") == group_id);
    assert(multicast_group(&m.n, "scan") == 0);
    original = m;
    for (unsigned n = 0; n < original.n.nlmsg_len; n++) {
        m = original;
        m.n.nlmsg_len = n;
        assert(multicast_group(&m.n, "mlme") == 0);
    }
    m = original;
    struct nlattr *outer = (void *)m.data;
    struct nlattr *nested = (void *)(m.data + NLA_HDRLEN);
    nested->nla_len = outer->nla_len; // Extends beyond the containing attr.
    assert(multicast_group(&m.n, "mlme") == 0);

    // A completion may arrive before the request cookie. Keep it, then
    // correlate exactly; another interface/cookie must not count as ours.
    uint8_t frame[45] = {0xd0, 0};
    memcpy(frame + 24, awdl_match, sizeof(awdl_match));
    init(&m, 30, NL80211_CMD_FRAME_TX_STATUS);
    u32attr(&m, NL80211_ATTR_IFINDEX, 7);
    attr(&m, NL80211_ATTR_COOKIE, &cookie, sizeof(cookie));
    attr(&m, NL80211_ATTR_FRAME, frame, sizeof(frame));
    attr(&m, NL80211_ATTR_ACK, NULL, 0);
    probe_ifindex = 8;
    event(&m.n);
    assert(!pending_status_count && !tx_status);
    probe_ifindex = 7;
    requesting_tx = true;
    event(&m.n);
    assert(pending_status_count == 1 && !tx_status);
    accept_tx_cookie(cookie);
    assert(tx_status == 1 && !pending_status_count);
    tx_cookie = cookie + 1;
    event(&m.n);
    assert(tx_status == 1);
    init(&m, 30, NL80211_CMD_REMAIN_ON_CHANNEL);
    u32attr(&m, NL80211_ATTR_IFINDEX, 7);
    u32attr(&m, NL80211_ATTR_WIPHY_FREQ, 2437);
    event(&m.n);
    assert(roc_ready == 1 && roc_expired == 0);
    m.g.cmd = NL80211_CMD_CANCEL_REMAIN_ON_CHANNEL;
    event(&m.n);
    assert(roc_ready == 1 && roc_expired == 1);
    probe_ifindex = 8;
    event(&m.n);
    assert(roc_expired == 1);
    capture_on_roc = true;
    roc_cookie_seen = false;
    capture_frequency = 5745;
    roc_cookie = 256;
    capture_done = false;
    assert(!capture_ready_matches(256, 5745));
    roc_cookie_seen = true;
    assert(capture_ready_matches(256, 5745));
    assert(!capture_ready_matches(257, 5745));
    assert(!capture_ready_matches(256, 2437));
    capture_done = true;
    assert(!capture_ready_matches(256, 5745));
    capture_on_roc = false;
    puts("coexist-probe parser tests passed");
    return 0;
}
