#!/usr/bin/env python3
"""Exercise actual DP timer start/stop/deinit and callback rearm code offline.

Deterministically interleaves start, callback and a second stop while synchronous
cancel is in progress. No radio; this does not simulate Linux timer internals.
"""
import argparse
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile

SHIM = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#define MON_VDEV_TIMER_INIT 1
#define MON_VDEV_TIMER_RUNNING 2
#define QDF_TIMER_TYPE_WAKE_APPS 0
#define DP_INTR_POLL_TIMER_MS 10
#define DP_TIMER_WORK_EXHAUST 1
#define DP_TIMER_TIME_EXHAUST 2
#define READ_ONCE(x) __atomic_load_n(&(x),__ATOMIC_ACQUIRE)
#define WRITE_ONCE(x,v) __atomic_store_n(&(x),(v),__ATOMIC_RELEASE)
struct lock { bool valid, held; };
struct timer { bool initialized,pending; };
struct dp_mon_soc { struct timer mon_vdev_timer; uint8_t mon_vdev_timer_state;
    struct lock mon_vdev_timer_lock; uint32_t mon_vdev_timer_stoppers; };
struct dp_soc { void *osdev; struct dp_mon_soc *monitor_soc; };
static struct dp_mon_soc mon;
static struct dp_soc soc={0,&mon};
static unsigned mods,cancels,frees;
static void (*cancel_hook)(void);
static void qdf_spinlock_create(struct lock *l) { assert(!l->valid); l->valid=true; }
static void qdf_spinlock_destroy(struct lock *l) { assert(l->valid && !l->held);l->valid=false; }
static void qdf_spin_lock_bh(struct lock *l) { assert(l->valid && !l->held);l->held=true; }
static void qdf_spin_unlock_bh(struct lock *l) { assert(l->held);l->held=false; }
static void dp_mon_vdev_timer(void *arg) { (void)arg; }
static void qdf_timer_init(void *d,struct timer *t,void(*cb)(void*),void *a,int flags) {
    (void)d;(void)cb;(void)a;(void)flags;t->initialized=true;
}
static void qdf_timer_mod(struct timer *t,int delay) {
    (void)delay;assert(t->initialized);assert(mon.mon_vdev_timer_lock.held);
    assert(mon.mon_vdev_timer_stoppers==0);t->pending=true;++mods;
}
static bool qdf_timer_sync_cancel(struct timer *t) {
    assert(t->initialized && !mon.mon_vdev_timer_lock.held);++cancels;
    if (cancel_hook) {void(*fn)(void)=cancel_hook;cancel_hook=0;fn();}
    bool pending=t->pending;t->pending=false;return pending;
}
static void qdf_timer_free(struct timer *t) {
    assert(t->initialized && !t->pending && !mon.mon_vdev_timer_lock.held);
    t->initialized=false;++frees;
}
'''
MAIN = r'''
static void during_cancel(void) {
    unsigned before=mods;
    assert(!(mon.mon_vdev_timer_state & MON_VDEV_TIMER_RUNNING));
    assert(mon.mon_vdev_timer_stoppers==1);
    dp_mon_vdev_timer_start(&soc);
    callback_rearm(&mon,DP_TIMER_WORK_EXHAUST);
    assert(!dp_mon_vdev_timer_stop(&soc));
    assert(mon.mon_vdev_timer_stoppers==1);
    assert(mods==before);
}
static void during_deinit(void) {
    unsigned before=mods;
    dp_mon_vdev_timer_start(&soc);
    callback_rearm(&mon,0);
    assert(mods==before);
}
int main(void) {
    // Never initialized and duplicate deinit must not touch a nonexistent lock.
    assert(!dp_mon_vdev_timer_stop(&soc));
    dp_mon_vdev_timer_start(&soc);dp_mon_vdev_timer_deinit(&soc);
    dp_mon_vdev_timer_init(&soc);dp_mon_vdev_timer_start(&soc);
    assert(mods==1 && mon.mon_vdev_timer.pending);
    callback_rearm(&mon,0);assert(mods==2);
    cancel_hook=during_cancel;
    assert(dp_mon_vdev_timer_stop(&soc));
    assert(!mon.mon_vdev_timer.pending && mon.mon_vdev_timer_stoppers==0);
    assert(mods==2 && cancels==2);
    callback_rearm(&mon,0);assert(mods==2);
    dp_mon_vdev_timer_start(&soc);assert(mods==3);
    cancel_hook=during_deinit;dp_mon_vdev_timer_deinit(&soc);
    assert(frees==1 && !mon.mon_vdev_timer.pending && mods==3);
    dp_mon_vdev_timer_deinit(&soc);dp_mon_vdev_timer_start(&soc);
    assert(!dp_mon_vdev_timer_stop(&soc));assert(frees==1);
    puts("PASS: actual DP timer code; rearm/start blocked during cancel, concurrent stop joins, restart after stop, deinit closes admission, duplicate/uninitialized cleanup");
}
'''

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path)
    args=parser.parse_args()
    source=(args.source/'qca-wifi-host-cmn/dp/wifi3.0/monitor/1.0/dp_mon_1.0.c').read_text()
    extract=runpy.run_path(str(Path(__file__).with_name('check-monitor-stop.py')))['extract']
    a=source.index('budget_done:');b=source.index('\n}',a)
    rearm='static void callback_rearm(struct dp_mon_soc *mon_soc,int yield) {\n'+source[a+len('budget_done:'):b]+'\n}\n'
    code=SHIM+rearm+'\n'.join(extract(source,n) for n in ['dp_mon_vdev_timer_init','dp_mon_vdev_timer_start','dp_mon_vdev_timer_stop','dp_mon_vdev_timer_deinit'])+MAIN
    with tempfile.TemporaryDirectory(prefix='marionette-mon-timer-') as directory:
        p=Path(directory);(p/'check.c').write_text(code)
        subprocess.run([shutil.which('clang') or 'clang','-std=c11','-g','-O1','-fsanitize=address,undefined',str(p/'check.c'),'-o',str(p/'check')],check=True)
        subprocess.run([str(p/'check')],check=True,timeout=10)
if __name__=='__main__':main()
