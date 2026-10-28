#!/usr/bin/env python3
"""Compile real candidate monitor callbacks against a deterministic host shim.

Checks command/ownership order and reentrancy, not kernel ABI or firmware.
Usage: check-monitor-stop.py PATH_TO_PATCHED_WLAN_TREE
"""
import argparse
from pathlib import Path
import subprocess
import tempfile
import shutil


def extract(text, name):
    # Function names are unique in these production files.
    import re
    match = re.search(r'(?:static\s+)?(?:QDF_STATUS|bool|void|struct wma_txrx_node \*)\s*\n?' + re.escape(name) + r'\(', text)
    if not match:
        raise ValueError('function not found: ' + name)
    start = match.start()
    body = text.index('{', match.end())
    depth, end = 1, body + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


SHIM = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
typedef int QDF_STATUS;
typedef void *mac_handle_t;
#define QDF_STATUS_SUCCESS 0
#define QDF_STATUS_E_INVAL 1
#define QDF_STATUS_E_FAILURE 2
#define QDF_STATUS_E_TIMEOUT 3
#define QDF_STATUS_E_CANCELED 4
#define QDF_IS_STATUS_ERROR(s) ((s) != 0)
#define QDF_IS_STATUS_SUCCESS(s) ((s) == 0)
#define READ_ONCE(x) __atomic_load_n(&(x), __ATOMIC_ACQUIRE)
#define WRITE_ONCE(x,v) __atomic_store_n(&(x),(v),__ATOMIC_RELEASE)
#define cmpxchg(p,o,n) __sync_val_compare_and_swap((p),(o),(n))
#define SERIALIZE_VDEV_RESP 1
#define wma_info(...) ((void)0)
#define wma_err(...) ((void)0)
#define QDF_MONITOR_MODE 1
#define QDF_MODULE_ID_WMA 1
#define QDF_MODULE_ID_SME 2
#define WLAN_LEGACY_WMA_ID 3
#define QDF_VDEV_STOP_RESPONSE_TIMED_OUT 4
#define QDF_REASON_UNSPECIFIED 5
#define WMI_VDEV_RESTART_RESP_EVENT 1
enum wlan_vdev_sm_evt;
enum { WLAN_VDEV_S_INIT, WLAN_VDEV_S_UP, WLAN_VDEV_S_START, WLAN_VDEV_S_STOP };
enum wlan_vdev_sm_evt { WLAN_VDEV_SM_EV_START_RESP, WLAN_VDEV_SM_EV_START_REQ_FAIL,
       WLAN_VDEV_SM_EV_RESTART_RESP, WLAN_VDEV_SM_EV_RESTART_REQ_FAIL, WLAN_VDEV_SM_EV_DOWN, WLAN_VDEV_SM_EV_MLME_DOWN_REQ,
       WLAN_VDEV_SM_EV_STOP_FAIL, WLAN_VDEV_SM_EV_DOWN_COMPLETE,
       WLAN_VDEV_SM_EV_DOWN_FAIL };
enum { WMA_MON_DELETE_IDLE, WMA_MON_DELETE_WAIT, WMA_MON_DELETE_READY,
       WMA_MON_DELETE_RESUMED };
struct wlan_objmgr_vdev { uint8_t id; int mode, state, refs; bool deleted; };
struct vdev_mlme_obj { struct wlan_objmgr_vdev *vdev; };
struct vdev_start_response { uint8_t vdev_id; unsigned status, resp_type; };
struct vdev_stop_response { uint8_t vdev_id; QDF_STATUS status; };
struct wma_txrx_node { struct wlan_objmgr_vdev *vdev; unsigned monitor_delete_state;
                      QDF_STATUS monitor_stop_status; };
struct wma { unsigned max_bssid; struct wma_txrx_node interfaces[2]; };
typedef struct wma *tp_wma_handle;
static struct wma context;
static struct wlan_objmgr_vdev vdev;
static struct vdev_mlme_obj mlme;
static bool enabled, recovering, fw_pending, sm_locked, fast_response;
static int stop_result, down_result;
static unsigned stops, downs, deletes, recoveries;
static void *cds_get_context(int type) { return type == QDF_MODULE_ID_WMA ? &context : (void *)1; }
static uint8_t wlan_vdev_get_id(struct wlan_objmgr_vdev *v) { assert(v->refs > 0); return v->id; }
static int wlan_vdev_mlme_get_opmode(struct wlan_objmgr_vdev *v) { assert(v->refs > 0); return v->mode; }
static int wlan_vdev_mlme_get_state(struct wlan_objmgr_vdev *v) { assert(v->refs > 0); return v->state; }
static void mlme_set_chan_switch_in_progress(struct wlan_objmgr_vdev *v,bool flag) {(void)v;(void)flag;}
static bool marionette_sta_monitor_enabled(void) { return enabled; }
static bool qdf_is_recovering(void) { return recovering; }
static void cds_trigger_recovery(int reason) { (void)reason; ++recoveries; }
static void wlan_objmgr_vdev_get_ref(struct wlan_objmgr_vdev *v, int id) { (void)id; assert(v->refs > 0); ++v->refs; }
static void wlan_objmgr_vdev_release_ref(struct wlan_objmgr_vdev *v, int id) { (void)id; assert(v->refs > 0); --v->refs; }
static QDF_STATUS wma_send_vdev_stop_to_fw(tp_wma_handle w, uint8_t id) {
    (void)w; (void)id; assert(!fw_pending); ++stops;
    if (!stop_result) fw_pending = true;
    return stop_result;
}
static QDF_STATUS wma_send_vdev_down_to_fw(tp_wma_handle w, uint8_t id) {
    (void)w; (void)id; assert(!fw_pending); ++downs; return down_result;
}
bool wma_mon_stop_before_delete(struct wlan_objmgr_vdev *);
void wma_mon_resume_delete(struct wlan_objmgr_vdev *);
bool wma_mon_delete_start_response(struct vdev_mlme_obj *,struct vdev_start_response *);
QDF_STATUS wma_mon_down_complete(struct vdev_mlme_obj *, uint16_t, void *);
QDF_STATUS wma_mon_stop_response(struct vdev_mlme_obj *, struct vdev_stop_response *);
QDF_STATUS wma_mon_mlme_vdev_stop_send(struct vdev_mlme_obj *, uint16_t, void *);
QDF_STATUS wma_mon_mlme_vdev_down_send(struct vdev_mlme_obj *, uint16_t, void *);
static QDF_STATUS wlan_vdev_mlme_sm_deliver_evt(struct wlan_objmgr_vdev *, int, uint16_t, void *);
static QDF_STATUS wlan_vdev_mlme_sm_deliver_evt_sync(struct wlan_objmgr_vdev *, int, uint16_t, void *);
static QDF_STATUS sme_vdev_delete(mac_handle_t mac, struct wlan_objmgr_vdev *v) {
    (void)mac; assert(!sm_locked);
    if (wma_mon_stop_before_delete(v)) return 0;
    assert(!fw_pending); assert(!v->deleted); assert(v->refs >= 2);
    ++deletes; v->deleted = true; v->refs -= 2; return 0;
}
'''
MAIN = r'''
static void response(int status) {
    struct vdev_stop_response rsp = {vdev.id, status};
    // The real target-if dispatcher first disarms STOP and holds a vdev ref.
    assert(fw_pending); fw_pending = false;
    wlan_objmgr_vdev_get_ref(&vdev, 0);
    wma_mon_stop_response(&mlme, &rsp);
    wlan_objmgr_vdev_release_ref(&vdev, 0);
}
static QDF_STATUS wlan_vdev_mlme_sm_deliver_evt_sync(struct wlan_objmgr_vdev *v,
        int event, uint16_t len, void *data) {
    (void)len; (void)data; assert(sm_locked);
    switch (event) {
    case WLAN_VDEV_SM_EV_START_RESP:
    case WLAN_VDEV_SM_EV_RESTART_RESP:
        assert(v->state==WLAN_VDEV_S_START); v->state=WLAN_VDEV_S_STOP;
        wma_mon_mlme_vdev_stop_send(&mlme,0,0); return 0;
    case WLAN_VDEV_SM_EV_RESTART_REQ_FAIL:
    case WLAN_VDEV_SM_EV_START_REQ_FAIL:
        v->state=WLAN_VDEV_S_INIT; return wma_mon_down_complete(&mlme,0,0);
    case WLAN_VDEV_SM_EV_DOWN:
        if (v->state == WLAN_VDEV_S_START) return 0; // wait for START response
        if (v->state == WLAN_VDEV_S_INIT) return wma_mon_down_complete(&mlme,0,0);
        v->state = WLAN_VDEV_S_STOP;
        // Production SM consumes the callback's status; event itself was handled.
        wma_mon_mlme_vdev_stop_send(&mlme,0,0); return 0;
    case WLAN_VDEV_SM_EV_MLME_DOWN_REQ:
    case WLAN_VDEV_SM_EV_STOP_FAIL:
        assert(v->state == WLAN_VDEV_S_STOP);
        wma_mon_mlme_vdev_down_send(&mlme,0,0); return 0;
    case WLAN_VDEV_SM_EV_DOWN_COMPLETE:
    case WLAN_VDEV_SM_EV_DOWN_FAIL:
        v->state = WLAN_VDEV_S_INIT; return wma_mon_down_complete(&mlme,0,0);
    }
    return QDF_STATUS_E_INVAL;
}
static QDF_STATUS wlan_vdev_mlme_sm_deliver_evt(struct wlan_objmgr_vdev *v,
        int event, uint16_t len, void *data) {
    assert(!sm_locked); sm_locked = true;
    int result = wlan_vdev_mlme_sm_deliver_evt_sync(v,event,len,data);
    sm_locked = false;
    if (fast_response && fw_pending) { fast_response = false; response(0); }
    return result;
}
static void reset(void) {
    memset(&context,0,sizeof(context));
    vdev = (struct wlan_objmgr_vdev){0,QDF_MONITOR_MODE,WLAN_VDEV_S_UP,2,false};
    mlme.vdev = &vdev; context.max_bssid = 2; context.interfaces[0].vdev = &vdev;
    enabled = true; recovering = fw_pending = sm_locked = fast_response = false;
    stop_result = down_result = 0; stops = downs = deletes = recoveries = 0;
}
int main(void) {
    reset(); assert(wma_mon_stop_before_delete(&vdev));
    assert(stops==1 && fw_pending && downs==0 && deletes==0 && vdev.refs==2 && !vdev.deleted);
    assert(wma_mon_stop_before_delete(&vdev)); assert(stops==1);
    response(0); assert(downs==1 && deletes==1 && vdev.refs==0 && recoveries==0);

    reset(); fast_response = true; assert(wma_mon_stop_before_delete(&vdev));
    assert(deletes==1 && vdev.refs==0 && stops==1 && downs==1);

    reset(); stop_result = QDF_STATUS_E_FAILURE;
    assert(wma_mon_stop_before_delete(&vdev));
    assert(stops==1 && downs==0 && deletes==1 && recoveries==1 && vdev.refs==0);

    reset(); down_result = QDF_STATUS_E_FAILURE;
    assert(wma_mon_stop_before_delete(&vdev)); response(0);
    assert(downs==1 && deletes==1 && recoveries==1 && vdev.refs==0);

    reset(); assert(wma_mon_stop_before_delete(&vdev)); response(QDF_STATUS_E_TIMEOUT);
    assert(downs==0 && deletes==1 && recoveries==1 && vdev.refs==0);
    assert(context.interfaces[0].monitor_stop_status==QDF_STATUS_E_TIMEOUT);

    reset(); assert(wma_mon_stop_before_delete(&vdev)); recovering=true;
    response(QDF_STATUS_E_TIMEOUT);
    assert(downs==0 && deletes==1 && recoveries==0 && vdev.refs==0);

    reset(); vdev.state=WLAN_VDEV_S_START;
    assert(wma_mon_stop_before_delete(&vdev)); assert(stops==0 && deletes==0);
    struct vdev_start_response start_rsp={0,0,0};
    assert(wma_mon_delete_start_response(&mlme,&start_rsp));
    assert(deletes==0); response(0);
    assert(stops==1 && downs==1 && deletes==1 && vdev.refs==0);

    reset(); vdev.state=WLAN_VDEV_S_START;
    assert(wma_mon_stop_before_delete(&vdev));
    start_rsp.status=QDF_STATUS_E_TIMEOUT;
    // Real target-if START dispatcher owns a reference too.
    wlan_objmgr_vdev_get_ref(&vdev,0);
    assert(wma_mon_delete_start_response(&mlme,&start_rsp));
    wlan_objmgr_vdev_release_ref(&vdev,0);
    assert(stops==0 && downs==0 && deletes==1 && recoveries==1 && vdev.refs==0);

    reset(); vdev.state=WLAN_VDEV_S_INIT;
    assert(!wma_mon_stop_before_delete(&vdev)); assert(stops==0 && deletes==0);
    reset(); enabled=false; assert(!wma_mon_stop_before_delete(&vdev));
    reset(); recovering=true; assert(!wma_mon_stop_before_delete(&vdev));
    reset(); vdev.mode=0; assert(!wma_mon_stop_before_delete(&vdev));

    reset(); assert(wma_mon_stop_before_delete(&vdev));
    struct vdev_stop_response wrong={1,0};
    assert(wma_mon_stop_response(&mlme,&wrong)==QDF_STATUS_E_INVAL);
    assert(fw_pending && deletes==0);
    context.interfaces[0].vdev=NULL; wrong.vdev_id=0;
    assert(wma_mon_stop_response(&mlme,&wrong)==QDF_STATUS_E_INVAL);
    context.interfaces[0].vdev=&vdev;
    // Hold a test owner ref to exercise duplicate completion without a stale ptr.
    wlan_objmgr_vdev_get_ref(&vdev,0); response(0);
    assert(wma_mon_stop_response(&mlme,&wrong)==QDF_STATUS_E_INVAL);
    wma_mon_resume_delete(&vdev); assert(deletes==1);
    wlan_objmgr_vdev_release_ref(&vdev,0); assert(vdev.refs==0);
    puts("PASS: actual monitor callbacks; STOP/DOWN/delete order, duplicate, fast response, START pending/failure, STOP/DOWN failure, timeout, SSR, identity and balanced refs");
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    args = parser.parse_args()
    source = (args.source / 'qcacld-3.0/core/wma/src/wma_utils.c').read_text()
    names = ['wma_mon_delete_iface', 'wma_mon_resume_delete', 'wma_mon_stop_before_delete',
             'wma_mon_down_complete', 'wma_mon_delete_start_response', 'wma_mon_stop_response',
             'wma_mon_mlme_vdev_stop_send', 'wma_mon_mlme_vdev_down_send']
    code = SHIM + '\n'.join(extract(source, name) for name in names) + MAIN
    with tempfile.TemporaryDirectory(prefix='marionette-mon-stop-') as directory:
        root = Path(directory)
        (root/'check.c').write_text(code)
        subprocess.run([shutil.which('clang') or 'clang','-std=c11','-g','-O1',
                        '-fsanitize=address,undefined','-fno-omit-frame-pointer',
                        str(root/'check.c'),'-o',str(root/'check')],check=True)
        subprocess.run([str(root/'check')],check=True,timeout=10)

if __name__ == '__main__':
    main()
