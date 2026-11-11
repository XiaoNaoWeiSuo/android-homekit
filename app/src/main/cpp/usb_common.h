#pragma once

#include <atomic>
#include <stddef.h>
#include <stdint.h>

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
