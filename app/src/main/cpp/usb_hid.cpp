#include "usb_common.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <chrono>
#include <thread>

#define LOG_TAG "marionette_usbd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

int listen_loopback(int port) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        LOGW("socket: %s", strerror(errno));
        return -1;
    }
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons(port);
    if (bind(fd, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) < 0) {
        LOGW("bind 127.0.0.1:%d: %s", port, strerror(errno));
        close(fd);
        return -1;
    }
    if (listen(fd, 4) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static bool read_line(int fd, char *buf, size_t cap, int timeout_ms) {
    size_t used = 0;
    while (used + 1 < cap) {
        pollfd p{fd, POLLIN, 0};
        int pr = poll(&p, 1, timeout_ms);
        if (pr <= 0) return false;
        char c;
        ssize_t n = read(fd, &c, 1);
        if (n != 1) return false;
        if (c == '\n') {
            buf[used] = 0;
            return true;
        }
        buf[used++] = c;
    }
    return false;
}

bool accept_with_token(int listen_fd, const char *token, int *out_fd) {
    pollfd p{listen_fd, POLLIN, 0};
    int pr = poll(&p, 1, 250);
    if (pr <= 0) return false;
    int fd = accept4(listen_fd, nullptr, nullptr, SOCK_CLOEXEC);
    if (fd < 0) return false;
    char line[160];
    if (!read_line(fd, line, sizeof(line), 2000)) {
        close(fd);
        return false;
    }
    char expect[160];
    snprintf(expect, sizeof(expect), "HELLO %s", token);
    if (strcmp(line, expect) != 0) {
        LOGW("rejected client (bad token)");
        close(fd);
        return false;
    }
    *out_fd = fd;
    return true;
}

bool write_all(int fd, const void *data, size_t len) {
    const char *p = static_cast<const char *>(data);
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

// f_hid accepts a report only while the host has an interrupt-IN request
// pending. A dropped *release* report is far worse than a dropped keystroke:
// the host keeps the key held and auto-repeats it (this once flooded the
// connected Mac with 'a'). Retry EAGAIN instead of dropping, and give release
// reports a much longer budget.
static bool write_report(int fd, const uint8_t *report, size_t len, int budget_ms) {
    for (int i = 0; i < budget_ms; i++) {
        ssize_t n = write(fd, report, len);
        if (n == static_cast<ssize_t>(len)) return true;
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) {
            usleep(1000);
            continue;
        }
        return false;
    }
    return false;
}

static bool is_release_report(const uint8_t *r, size_t len) {
    if (len >= 9 && r[0] == 1) {
        for (size_t i = 1; i < 9; i++)
            if (r[i] != 0) return false;
        return true;
    }
    if (len >= 5 && r[0] == 2) {
        for (size_t i = 1; i < 5; i++)
            if (r[i] != 0) return false;
        return true;
    }
    if (len >= 3 && r[0] == 3) {
        return r[1] == 0 && r[2] == 0;
    }
    return false;
}

static void send_release_all(int fd) {
    if (fd < 0) return;
    static const uint8_t keyboard[9] = {1, 0, 0, 0, 0, 0, 0, 0, 0};
    static const uint8_t mouse[5] = {2, 0, 0, 0, 0};
    write_report(fd, keyboard, sizeof(keyboard), 500);
    write_report(fd, mouse, sizeof(mouse), 500);
}

static bool read_exact(int fd, void *data, size_t len) {
    char *p = static_cast<char *>(data);
    while (len > 0) {
        ssize_t n = read(fd, p, len);
        if (n == 0) return false;
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

// /dev/hidg0 is a blocking character device once the host has not queued an
// interrupt-IN request. O_NONBLOCK turns that into EAGAIN so a disconnected
// host can never wedge the HID thread (and never wedge teardown).
int usb_hid_run(const char *node, int port, const char *token, std::atomic<bool> *stop) {
    int listen_fd = listen_loopback(port);
    if (listen_fd < 0) return -1;
    LOGI("HID listening on 127.0.0.1:%d", port);

    int hid_fd = -1;
    int client_fd = -1;
    uint8_t report[64];

    while (!stop->load()) {
        if (client_fd < 0) {
            if (!accept_with_token(listen_fd, token, &client_fd)) {
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
                continue;
            }
            LOGI("HID client connected");
        }
        if (hid_fd < 0) {
            hid_fd = open(node, O_RDWR | O_NONBLOCK | O_CLOEXEC);
            if (hid_fd < 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(300));
                continue;
            }
            LOGI("HID node open: %s", node);
        }

        // 1-byte length + report. Reports are at most 8 bytes today, but keep
        // room for a richer descriptor later.
        uint8_t len = 0;
        if (!read_exact(client_fd, &len, 1) || len == 0 || len > sizeof(report)) {
            // The app went away while a key may still be held.
            send_release_all(hid_fd);
            close(client_fd);
            client_fd = -1;
            continue;
        }
        if (!read_exact(client_fd, report, len)) {
            send_release_all(hid_fd);
            close(client_fd);
            client_fd = -1;
            continue;
        }
        int budget = is_release_report(report, len) ? 2000 : 150;
        if (!write_report(hid_fd, report, len, budget)) {
            LOGW("hidg write failed: %s", strerror(errno));
            if (errno == ENODEV || errno == ESHUTDOWN || errno == EINVAL) {
                close(hid_fd);
                hid_fd = -1;
            }
        }
    }

    send_release_all(hid_fd);
    if (client_fd >= 0) close(client_fd);
    if (hid_fd >= 0) close(hid_fd);
    close(listen_fd);
    return 0;
}
