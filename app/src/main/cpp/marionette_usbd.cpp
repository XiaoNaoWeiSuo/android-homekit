#include "usb_common.h"

#include <android/log.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <atomic>
#include <thread>

#define LOG_TAG "marionette_usbd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::atomic<bool> g_stop{false};

static void on_signal(int) { g_stop.store(true); }

/**
 * The daemon is spawned from a root shell and inherits whatever that shell
 * inherited. The panic trace of 2026-11-12 showed __fput() -> module_put()
 * dying inside this process, and the gadget functions are all built in, so an
 * inherited module-owned fd is a plausible source. Record what we start with.
 */
static void log_inherited_fds() {
    for (int fd = 0; fd < 64; ++fd) {
        char link[64];
        char path[256];
        snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
        const ssize_t n = readlink(link, path, sizeof path - 1);
        if (n <= 0) continue;
        path[n] = '\0';
        LOGI("fd inherited %d -> %s", fd, path);
    }
}

// Weak fallbacks let the HID path build and run before the video/audio modules
// land. The real implementations override these at link time.
__attribute__((weak)) int uvc_run(const char *, int, const char *, std::atomic<bool> *) {
    LOGI("uvc module not built");
    return 0;
}
__attribute__((weak)) int audio_run(int, int, const char *, std::atomic<bool> *) {
    LOGI("audio module not built");
    return 0;
}

static const char *arg_value(int argc, char **argv, const char *name, const char *def) {
    for (int i = 1; i + 1 < argc; i++)
        if (!strcmp(argv[i], name)) return argv[i + 1];
    return def;
}

int main(int argc, char **argv) {
    const char *token = arg_value(argc, argv, "--token", nullptr);
    if (!token || !*token) {
        LOGI("missing --token");
        return 2;
    }
    const char *hid_node = arg_value(argc, argv, "--hid-node", "/dev/hidg0");
    const char *video_node = arg_value(argc, argv, "--video-node", "/dev/video2");
    int hid_port = atoi(arg_value(argc, argv, "--hid-port", "47831"));
    int video_port = atoi(arg_value(argc, argv, "--video-port", "47832"));
    int mic_port = atoi(arg_value(argc, argv, "--mic-port", "47833"));
    int spk_port = atoi(arg_value(argc, argv, "--spk-port", "47834"));

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGHUP, on_signal);

    LOGI("daemon start hid=%s video=%s", hid_node, video_node);
    log_inherited_fds();
    std::thread hid(usb_hid_run, hid_node, hid_port, token, &g_stop);
    std::thread video(uvc_run, video_node, video_port, token, &g_stop);
    std::thread audio(audio_run, mic_port, spk_port, token, &g_stop);

    while (!g_stop.load()) sleep(1);

    if (hid.joinable()) hid.join();
    if (video.joinable()) video.join();
    if (audio.joinable()) audio.join();
    LOGI("daemon stopped");
    return 0;
}
