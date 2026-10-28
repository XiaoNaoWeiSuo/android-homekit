/* Bounded IPv6 UDP echo probe for the AWDL link, without AirDrop/TLS/mDNS.
 * Usage: awdl-ip-probe IFACE LOCAL_V6 PEER_V6 PORT SECONDS ORIGIN(1|2) [PEER_PORT]
 * Run both ends with opposite origin values. Only the specified peer and
 * deterministic 64-byte probe packets can elicit a reply. No file content.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
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

static uint64_t now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts)) { perror("clock"); exit(2); }
    return (uint64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
static unsigned number(const char *s, unsigned min, unsigned max) {
    char *end = NULL;
    errno = 0;
    unsigned long value = strtoul(s, &end, 10);
    if (errno || !*s || *end || value < min || value > max) {
        fprintf(stderr, "invalid number: %s\n", s); exit(2);
    }
    return (unsigned)value;
}
static void packet(unsigned char out[64], unsigned origin, unsigned seq) {
    memcpy(out, "AWDLIP1!", 8);
    out[8] = (unsigned char)origin; out[9] = 1;
    out[10] = (unsigned char)(seq >> 8); out[11] = (unsigned char)seq;
    for (unsigned i = 12; i < 64; i++) out[i] = (unsigned char)(i ^ seq ^ origin);
}
int main(int argc, char **argv) {
    if (argc != 7 && argc != 8) {
        fprintf(stderr,"Usage: %s IFACE LOCAL_V6 PEER_V6 PORT SECONDS ORIGIN(1|2) [PEER_PORT]\n",argv[0]);
        return 2;
    }
    unsigned port = number(argv[4],1024,65535), seconds = number(argv[5],1,120);
    unsigned origin = number(argv[6],1,2), index = if_nametoindex(argv[1]);
    if (!index) { perror("interface index"); return 2; }
    struct sockaddr_in6 local = {.sin6_family=AF_INET6, .sin6_port=htons(port), .sin6_scope_id=index};
    struct sockaddr_in6 peer = local;
    if (argc == 8) peer.sin6_port = htons(number(argv[7],1024,65535));
    if (inet_pton(AF_INET6,argv[2],&local.sin6_addr)!=1 ||
        inet_pton(AF_INET6,argv[3],&peer.sin6_addr)!=1) return 2;
    int fd = socket(AF_INET6,SOCK_DGRAM,0);
    if (fd < 0) { perror("socket"); return 2; }
#ifdef __APPLE__
    /* Darwin AWDL ingress restriction: same option used by OpenDrop. */
    int anyif = 1;
    if (setsockopt(fd,SOL_SOCKET,0x1104,&anyif,sizeof(anyif))) { perror("SO_RECV_ANYIF"); return 2; }
    if (setsockopt(fd,IPPROTO_IPV6,IPV6_BOUND_IF,&index,sizeof(index))) { perror("IPV6_BOUND_IF"); return 2; }
#else
    if (setsockopt(fd,SOL_SOCKET,SO_BINDTODEVICE,argv[1],strlen(argv[1])+1)) { perror("SO_BINDTODEVICE"); return 2; }
#endif
    if (bind(fd,(struct sockaddr *)&local,sizeof(local))) { perror("bind"); close(fd);return 2; }
    printf("ready iface=%s local=%s peer=%s port=%u origin=%u\n",argv[1],argv[2],argv[3],port,origin);fflush(stdout);
    /* Bind BOTH endpoints before releasing their start gates. Otherwise the
     * first received packet elicits ICMP port-unreachable while the other
     * probe is still starting (observed on the real 2026-10-27 capture).
     * Each gate must be a fresh, caller-owned path. Duration starts after it.
     */
    const char *gate = getenv("AWDL_IP_PROBE_START_FILE");
    if (gate && *gate) {
        uint64_t deadline = now_ms() + 10000;
        while (access(gate, F_OK)) {
            if (errno != ENOENT) { perror("start gate"); close(fd); return 2; }
            if (now_ms() >= deadline) {
                fprintf(stderr,"start gate timed out after 10 seconds\n");close(fd);return 2;
            }
            struct timespec pause = {.tv_sec=0, .tv_nsec=20000000};
            while (nanosleep(&pause,&pause) && errno == EINTR) {}
        }
    }
    uint64_t sent_at[256]={0}, start=now_ms(), next=start, last=start, sum=0, maximum=0;
    bool seen[256]={false};
    unsigned seq=0, sent=0, send_errors=0, requests=0, echo_errors=0, replies=0, invalid=0;
    printf("started origin=%u\n",origin);fflush(stdout);
    while (now_ms()-start < seconds*1000ULL) {
        uint64_t now=now_ms();
        if (now >= next && seq < 256) {
            unsigned char out[64];packet(out,origin,seq);
            if (sendto(fd,out,sizeof(out),0,(struct sockaddr *)&peer,sizeof(peer)) == sizeof(out)) {
                sent_at[seq]=now;sent++;
            } else { send_errors++; if (send_errors<5) perror("send"); }
            seq++;next=now+500;
        }
        struct pollfd pfd={fd,POLLIN,0};
        int rc=poll(&pfd,1,50);
        if (rc<0 && errno==EINTR) continue;
        if (rc<0) { perror("poll"); break; }
        if (rc>0 && (pfd.revents & POLLIN)) {
            unsigned char in[128],expected[64];struct sockaddr_in6 from; socklen_t len=sizeof(from);
            ssize_t n=recvfrom(fd,in,sizeof(in),0,(struct sockaddr *)&from,&len);
            if (n!=64 || from.sin6_family!=AF_INET6 || from.sin6_port!=peer.sin6_port ||
                memcmp(&from.sin6_addr,&peer.sin6_addr,16) || memcmp(in,"AWDLIP1!",8)) {invalid++;continue;}
            unsigned id=((unsigned)in[10]<<8)|in[11];
            packet(expected,in[8],id);expected[9]=in[9];
            if (id>=256 || memcmp(in,expected,64)) {invalid++;continue;}
            if (in[9]==1 && in[8] == 3-origin) {
                requests++;in[9]=2;
                if (sendto(fd,in,64,0,(struct sockaddr *)&peer,sizeof(peer))!=64) echo_errors++;
            } else if (in[9]==2 && in[8]==origin && sent_at[id] && !seen[id]) {
                seen[id]=true;replies++;uint64_t rtt=now_ms()-sent_at[id];sum+=rtt;if(rtt>maximum)maximum=rtt;
            } else invalid++;
        }
        if (now-last>=10000) {
            printf("progress sent=%u replies=%u peer_requests=%u send_errors=%u\n",sent,replies,requests,send_errors);fflush(stdout);last=now;
        }
    }
    printf("result sent=%u replies=%u peer_requests=%u send_errors=%u echo_errors=%u invalid=%u mean_rtt_ms=%llu max_rtt_ms=%llu\n",
        sent,replies,requests,send_errors,echo_errors,invalid,(unsigned long long)(replies?sum/replies:0),(unsigned long long)maximum);
    close(fd);return replies && requests && !send_errors && !echo_errors ? 0 : 1;
}
