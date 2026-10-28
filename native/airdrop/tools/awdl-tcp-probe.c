/* Fixed-pattern bidirectional TCP diagnostic. No files or AirDrop protocol.
 * IFACE LOCAL_IPV6 PEER_IPV6 PORT listen|connect [SECONDS]
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <net/if.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <stdint.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
static uint64_t milliseconds(void) {
    struct timespec t; if(clock_gettime(CLOCK_MONOTONIC,&t))exit(2);
    return (uint64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
static unsigned number(const char *s,unsigned lo,unsigned hi) {
    char *end; errno=0; unsigned long v=strtoul(s,&end,10);
    if(errno || !*s || *end || v<lo || v>hi)exit(2);return (unsigned)v;
}
static int wait_fd(int fd,short events,uint64_t deadline) {
    for(;;) {
        uint64_t now=milliseconds();if(now>=deadline){errno=ETIMEDOUT;return -1;}
        struct pollfd p={fd,events,0};int r=poll(&p,1,(int)(deadline-now));
        if(r<0 && errno==EINTR)continue;
        if(r<=0){if(!r)errno=ETIMEDOUT;return -1;}
        if(p.revents&events)return 0;
        errno=ECONNRESET;return -1;
    }
}
static int transfer(int fd,unsigned char *buf,size_t n,int writing,uint64_t deadline) {
    while(n) {
        if(wait_fd(fd,writing?POLLOUT:POLLIN,deadline))return -1;
        ssize_t k=writing?send(fd,buf,n,0):recv(fd,buf,n,0);
        if(k<0 && (errno==EAGAIN || errno==EINTR))continue;
        if(k<=0){if(!k)errno=ECONNRESET;return -1;}
        buf+=k;n-=(size_t)k;
    }return 0;
}
static int configure(int fd,unsigned index,const char *iface) {
    if(fcntl(fd,F_SETFL,O_NONBLOCK))return -1;
#ifdef __APPLE__
    int one=1;
    if(setsockopt(fd,SOL_SOCKET,0x1104,&one,sizeof(one)) ||
       setsockopt(fd,IPPROTO_IPV6,IPV6_BOUND_IF,&index,sizeof(index)) ||
       setsockopt(fd,SOL_SOCKET,SO_NOSIGPIPE,&one,sizeof(one)))return -1;
#else
    (void)index;
    if(setsockopt(fd,SOL_SOCKET,SO_BINDTODEVICE,iface,strlen(iface)+1))return -1;
#endif
    (void)iface;return 0;
}
int main(int argc,char **argv) {
    signal(SIGPIPE,SIG_IGN);
    if(argc<6 || argc>7){fprintf(stderr,"IFACE LOCAL_V6 PEER_V6 PORT listen|connect [SECONDS]\n");return 2;}
    int server=!strcmp(argv[5],"listen");if(!server && strcmp(argv[5],"connect"))return 2;
    unsigned port=number(argv[4],1024,65535),seconds=argc==7?number(argv[6],1,60):20;
    unsigned index=if_nametoindex(argv[1]);if(!index){perror("ifindex");return 2;}
    struct sockaddr_in6 local={.sin6_family=AF_INET6,.sin6_scope_id=index};
    struct sockaddr_in6 peer=local;peer.sin6_port=htons(port);local.sin6_port=server?htons(port):0;
    if(inet_pton(AF_INET6,argv[2],&local.sin6_addr)!=1 || inet_pton(AF_INET6,argv[3],&peer.sin6_addr)!=1)return 2;
    int fd=socket(AF_INET6,SOCK_STREAM,0),listener=-1;unsigned completed=0;int result=1;
    uint64_t start=milliseconds(),deadline=start+seconds*1000ULL;
    if(fd<0 || configure(fd,index,argv[1]) || bind(fd,(struct sockaddr *)&local,sizeof(local))){perror("socket/bind");goto done;}
    if(server) {
        if(listen(fd,1)){perror("listen");goto done;}
        printf("ready listener bound port=%u\n",port);fflush(stdout);
        listener=fd;
        if(wait_fd(listener,POLLIN,deadline)){perror("accept wait");fd=-1;goto done;}
        struct sockaddr_in6 from; socklen_t len=sizeof(from);
        fd=accept(listener,(struct sockaddr *)&from,&len);
        if(fd<0 || memcmp(&from.sin6_addr,&peer.sin6_addr,16) || configure(fd,index,argv[1])){perror("accept/peer");goto done;}
    } else {
        if(connect(fd,(struct sockaddr *)&peer,sizeof(peer))<0 && errno!=EINPROGRESS){perror("connect");goto done;}
        if(wait_fd(fd,POLLOUT,deadline)){perror("connect wait");goto done;}
        int err=0;socklen_t len=sizeof(err);
        if(getsockopt(fd,SOL_SOCKET,SO_ERROR,&err,&len) || err){errno=err;perror("connect result");goto done;}
    }
    printf("connected elapsed_ms=%llu\n",(unsigned long long)(milliseconds()-start));fflush(stdout);
    int one=1;setsockopt(fd,IPPROTO_TCP,TCP_NODELAY,&one,sizeof(one));
    for(unsigned seq=0;seq<8;seq++) {
        unsigned char pattern[1024],received[1024];
        for(unsigned i=0;i<sizeof(pattern);i++)pattern[i]=(unsigned char)(i*17u+seq*31u);
        if(server) {
            if(transfer(fd,received,sizeof(received),0,deadline)){perror("receive");goto done;}
            if(memcmp(pattern,received,sizeof(pattern))){fprintf(stderr,"payload mismatch\n");goto done;}
            for(unsigned i=0;i<sizeof(pattern);i++)pattern[i]^=0xa5;
            if(transfer(fd,pattern,sizeof(pattern),1,deadline)){perror("reply");goto done;}
        } else {
            if(transfer(fd,pattern,sizeof(pattern),1,deadline) || transfer(fd,received,sizeof(received),0,deadline)){perror("exchange");goto done;}
            for(unsigned i=0;i<sizeof(pattern);i++)pattern[i]^=0xa5;
            if(memcmp(pattern,received,sizeof(pattern))){fprintf(stderr,"payload mismatch\n");goto done;}
        }
        completed++;printf("verified block=%u\n",completed);fflush(stdout);
    }
    result=0;
done:
    printf("result verified_blocks=%u bytes_each_direction=%u elapsed_ms=%llu success=%d\n",completed,completed*1024,(unsigned long long)(milliseconds()-start),result==0);
    if(fd>=0)close(fd);if(listener>=0 && listener!=fd)close(listener);return result;
}
