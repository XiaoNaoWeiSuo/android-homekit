#!/usr/bin/env python3
"""Exercise the actual 0058 forwarding block with fragmented skb/failure shims."""
import pathlib, subprocess, sys, tempfile
source = pathlib.Path(sys.argv[1]).read_text()
start = source.index('\tif (marionette_sta_monitor_enabled()) {', source.index('/* Restitch keeps'))
end = source.index('\n\tdp_rx_mon_process_dest_pktlog', start)
block = source[start:end]
shim = r'''
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
typedef uint32_t u32; typedef uint8_t u8;
static int enabled=1, fail_alloc, fail_copy, calls;
static u32 captured;
struct skb { u8 bytes[9000]; u32 len, head; };
struct mon { struct { struct { uint64_t tsft; } rx_status; } ppdu_info; };
static int marionette_sta_monitor_enabled(void) { return enabled; }
static u32 qdf_nbuf_len(struct skb *s) { return s->len; }
static u32 qdf_nbuf_headlen(struct skb *s) { return s->head; }
static u8 *qdf_nbuf_data(struct skb *s) { return s->bytes; }
static void *qdf_mem_malloc_atomic(u32 n) { return fail_alloc ? NULL : malloc(n); }
static void qdf_mem_free(void *p) { free(p); }
static int qdf_nbuf_copy_bits(struct skb *s,u32 off,u32 n,void *p) {
 if(fail_copy) return -1; memcpy(p,s->bytes+off,n); return 0;
}
static void marionette_monitor_post_frame(const u8 *p,u32 n,uint64_t tsft) {
 assert(tsft==42); assert(n<=9000); for(u32 i=0;i<n;i++) assert(p[i]==(u8)i);
 calls++;captured=n;
}
static void forward(struct skb *mon_mpdu, struct mon *mon_pdev) {
'''
main = r'''
}
int main(void) {
 struct skb s={.len=8192,.head=1800}; struct mon m={0}; m.ppdu_info.rx_status.tsft=42;
 for(u32 i=0;i<sizeof(s.bytes);i++)s.bytes[i]=(u8)i;
 forward(&s,&m);assert(calls==1&&captured==8192);assert(s.len==8192&&s.head==1800);
 s.len=s.head;forward(&s,&m);assert(calls==2&&captured==1800);
 s.len=8192;fail_alloc=1;forward(&s,&m);assert(calls==2);fail_alloc=0;
 fail_copy=1;forward(&s,&m);assert(calls==2);fail_copy=0;
 s.len=65536;forward(&s,&m);assert(calls==2);
 enabled=0;s.len=8192;forward(&s,&m);assert(calls==2);
 return 0;
}
'''
with tempfile.TemporaryDirectory() as d:
    p=pathlib.Path(d);(p/'test.c').write_text(shim+block+main)
    subprocess.run(['clang','-Wall','-Wextra','-Werror','-fsanitize=address,undefined',str(p/'test.c'),'-o',str(p/'test')],check=True)
    subprocess.run([str(p/'test')],check=True)
print('Full skb, linear fast path, size bound, allocation/copy failures and disabled path: passed')
