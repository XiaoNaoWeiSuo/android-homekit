#pragma once

#include <android/log.h>
#include <dirent.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include <atomic>

// Shared by the root daemon modules. All modules run in one process while the
// Android app talks to them over loopback TCP.
//
// Every connection starts with the ASCII line "HELLO <token>\n" so a random
// local process cannot inject HID reports or audio.

struct DaemonConfig {
    const char *hid_node;
    const char *video_node;
    int hid_port;
    int video_port;
    int mic_port;
    int spk_port;
    const char *token;
};

int usb_hid_run(const char *node, int port, const char *token, std::atomic<bool> *stop);
int uvc_run(const char *node, int port, const char *token, std::atomic<bool> *stop);
int audio_run(int mic_port, int spk_port, const char *token, std::atomic<bool> *stop);

// Helpers shared by the modules.
int listen_loopback(int port);
bool accept_with_token(int listen_fd, const char *token, int *out_fd);
bool write_all(int fd, const void *data, size_t len);

/**
 * Closing a device node goes through __fput() -> module_put(f_op->owner). This
 * vendor kernel has panicked there (NULL dereference inside module_put) while
 * the daemon tore down a node, so log the fd's real path before closing it:
 * after the fact there is no way to tell which node was involved.
 */
static inline void close_traced(int fd, const char *what) {
    if (fd < 0) return;
    char link[64];
    char path[256];
    snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
    const ssize_t n = readlink(link, path, sizeof path - 1);
    if (n > 0) {
        path[n] = '\0';
    } else {
        snprintf(path, sizeof path, "?");
    }
    __android_log_print(ANDROID_LOG_INFO, "marionette_usbd", "close fd=%d path=%s what=%s",
                        fd, path, what);
    /*
     * logcat dies with the kernel when this close panics, so keep a copy on
     * disk: the last line before the reboot names the fd that killed it.
     */
    FILE *trace = fopen("/data/local/tmp/marionette_fd.log", "a");
    if (trace) {
        fprintf(trace, "close fd=%d path=%s what=%s\n", fd, path, what);
        fflush(trace);
        fclose(trace);
    }
    close(fd);
}

/**
 * Same durability trick for cable/node lifecycle transitions: when the phone
 * reboots, logcat is gone but this file still says what the daemon was doing.
 */
static inline void trace_lifecycle(const char *fmt, ...) {
    char line[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "marionette_usbd", "lifecycle: %s", line);
    FILE *trace = fopen("/data/local/tmp/marionette_fd.log", "a");
    if (trace) {
        fprintf(trace, "lifecycle: %s\n", line);
        fflush(trace);
        fclose(trace);
    }
}

/**
 * True when a host is on the other end of the UDC (powered/addressed/configured).
 *
 * The gadget nodes stay enumerable while the cable is out, so opening them
 * again "succeeds" and only the first write fails. That produced an endless
 * open/close storm across a flap - and every close walks __fput(), the path
 * that has panicked this kernel. Wait for the host instead.
 * Unreadable sysfs answers "attached" so a permission problem never parks a
 * node forever.
 */
static inline bool udc_host_attached() {
    DIR *dir = opendir("/sys/class/udc");
    if (!dir) return true;
    bool attached = false;
    struct dirent *de;
    while ((de = readdir(dir)) != nullptr) {
        if (de->d_name[0] == '.') continue;
        char path[256];
        const int n = snprintf(path, sizeof path, "/sys/class/udc/%s/state", de->d_name);
        if (n <= 0 || (size_t)n >= sizeof path) continue;
        FILE *f = fopen(path, "r");
        if (!f) continue;
        char buf[64] = {0};
        if (fgets(buf, sizeof buf, f)) {
            if (!strncmp(buf, "configured", 10) || !strncmp(buf, "addressed", 9) ||
                !strncmp(buf, "powered", 7)) {
                attached = true;
            }
        }
        fclose(f);
        if (attached) break;
    }
    closedir(dir);
    return attached;
}
