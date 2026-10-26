#!/usr/bin/env python3
"""Compile the actual WMA response callbacks against a host boundary shim.

This tests argument/index validation only, not kernel ABI, locking or firmware.
Pass the pinned wlan_hdd WMA source file (wma_dev_if.c), optionally --patch.
No device or network operations.
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

def extract(source, name):
    start = source.index("QDF_STATUS " + name + "(")
    body = source.index("{", start)
    depth = 1
    end = body + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]

SHIM = r"""
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
typedef int QDF_STATUS;
#define QDF_STATUS_SUCCESS 0
#define QDF_STATUS_E_FAILURE 1
#define QDF_STATUS_E_INVAL 2
#define QDF_MODULE_ID_WMA 1
#define QDF_BUG(x) ((void)(x))
#define wma_err(...) ((void)0)
struct wlan_objmgr_vdev { struct { uint8_t vdev_id; } vdev_objmgr; };
struct vdev_mlme_obj { struct wlan_objmgr_vdev *vdev; };
struct vdev_delete_response { uint8_t vdev_id; };
struct vdev_stop_response { uint8_t vdev_id; };
struct sir_roam_scan_stats { int dummy; };
struct wma_txrx_node {
    struct wlan_objmgr_vdev *vdev;
    void *del_staself_req;
    struct sir_roam_scan_stats *roam_scan_stats_req;
};
struct wma { uint8_t max_bssid; struct wma_txrx_node *interfaces; };
typedef struct wma *tp_wma_handle;
static struct wma *context;
static unsigned deletes, stops, deinit_calls, init_calls;
static void *cds_get_context(int unused) { (void)unused; return context; }
static unsigned wlan_vdev_get_id(struct wlan_objmgr_vdev *v) { return v->vdev_objmgr.vdev_id; }
static void qdf_mem_free(void *p) { free(p); }
static void qdf_mem_zero(void *p, size_t n) { memset(p, 0, n); }
static void wma_vdev_deinit(struct wma_txrx_node *p) { (void)p; ++deinit_calls; }
static void wma_vdev_init(struct wma_txrx_node *p) { (void)p; ++init_calls; }
static void mlme_vdev_del_resp(unsigned id) { (void)id; ++deletes; }
static QDF_STATUS wma_handle_vdev_stop_rsp(tp_wma_handle w, struct vdev_stop_response *r) {
    (void)w; (void)r; ++stops; return QDF_STATUS_SUCCESS;
}
"""
MAIN = r"""
int main(void) {
    struct wma_txrx_node *slots = calloc(2, sizeof(*slots));
    struct wma w = {2, slots};
    struct wlan_objmgr_vdev v0 = {{0}}, v1 = {{1}}, old = {{0}};
    struct vdev_mlme_obj mlme = {&v0};
    struct vdev_delete_response del = {2};
    struct vdev_stop_response stop = {0};
    context = &w;
    slots[0].vdev = &v0; slots[1].vdev = &v1;

    /* Critical exclusive boundary. ASan catches the original invalid write. */
    assert(wma_vdev_detach_callback(&del) != QDF_STATUS_SUCCESS);
    assert(deletes == 0 && deinit_calls == 0 && init_calls == 0);
    assert(wma_vdev_detach_callback(NULL) != QDF_STATUS_SUCCESS);
    assert(wma_vdev_stop_resp_handler(NULL, &stop) != QDF_STATUS_SUCCESS);
    assert(wma_vdev_stop_resp_handler(&mlme, NULL) != QDF_STATUS_SUCCESS);
    mlme.vdev = NULL;
    assert(wma_vdev_stop_resp_handler(&mlme, &stop) != QDF_STATUS_SUCCESS);
    mlme.vdev = &old; /* same ID, replaced object */
    assert(wma_vdev_stop_resp_handler(&mlme, &stop) != QDF_STATUS_SUCCESS);
    mlme.vdev = &v0; stop.vdev_id = 1;
    assert(wma_vdev_stop_resp_handler(&mlme, &stop) != QDF_STATUS_SUCCESS);
    stop.vdev_id = 2;
    assert(wma_vdev_stop_resp_handler(&mlme, &stop) != QDF_STATUS_SUCCESS);
    stop.vdev_id = 0; slots[0].vdev = NULL;
    assert(wma_vdev_stop_resp_handler(&mlme, &stop) != QDF_STATUS_SUCCESS);
    assert(stops == 0);

    slots[0].vdev = &v0;
    assert(wma_vdev_stop_resp_handler(&mlme, &stop) == QDF_STATUS_SUCCESS);
    assert(stops == 1);
    del.vdev_id = 1; /* last valid slot */
    assert(wma_vdev_detach_callback(&del) == QDF_STATUS_SUCCESS);
    assert(deletes == 1 && deinit_calls == 1 && init_calls == 1);
    assert(slots[0].vdev == &v0);
    context = NULL;
    assert(wma_vdev_detach_callback(&del) != QDF_STATUS_SUCCESS);
    assert(wma_vdev_stop_resp_handler(&mlme, &stop) != QDF_STATUS_SUCCESS);
    free(slots);
    return 0;
}
"""

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--patch", type=Path)
    parser.add_argument("--expect-failure", action="store_true")
    args = parser.parse_args()
    compiler = shutil.which("clang")
    if not compiler:
        raise SystemExit("clang required")
    with tempfile.TemporaryDirectory(prefix="marionette-vdev-boundary-") as directory:
        root = Path(directory)
        source = root / "qcacld-3.0/core/wma/src/wma_dev_if.c"
        source.parent.mkdir(parents=True)
        source.write_bytes(args.source.read_bytes())
        if args.patch:
            patch = str(args.patch.resolve())
            subprocess.run(["git", "apply", "--check", patch], cwd=root, check=True)
            subprocess.run(["git", "apply", patch], cwd=root, check=True)
        code = source.read_text()
        harness = SHIM + extract(code, "wma_vdev_detach_callback") + chr(10)
        harness += extract(code, "wma_vdev_stop_resp_handler") + MAIN
        (root / "check.c").write_text(harness)
        binary = root / "check"
        subprocess.run([compiler, "-std=c11", "-g", "-O1",
                        "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                        str(root / "check.c"), "-o", str(binary)], check=True)
        result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
        if args.expect_failure:
            if result.returncode == 0 or "AddressSanitizer" not in result.stderr:
                raise SystemExit("expected original boundary failure was not reproduced")
            print("Original source: reproduced AddressSanitizer boundary failure.")
        else:
            if result.returncode:
                print(result.stderr)
                raise SystemExit(result.returncode)
            print("Patched callbacks: null/bounds/stale-object and valid callback checks passed under ASan/UBSan.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
