// SPDX-License-Identifier: GPL-2.0
/*
 * Marionette USB peripheral daemon: UVC gadget video source.
 *
 * The phone is presented to the host as a standard UVC camera. Frames come
 * from the Android app over loopback TCP (MRV1 framing, see
 * docs/USB_PERIPHERAL.md) and are converted to YUYV inside buffers of the
 * UVC function's V4L2 output device.
 *
 * The V4L2 setup sequence, the UVC event state machine and the class request
 * handling are a reduced port of the Linux kernel sample
 * tools/usb/uvc-gadget.c (GPL-2.0):
 *
 *   Copyright (C) 2010 Ideas on board SPRL <laurent.pinchart@ideasonboard.com>
 *
 * SAFETY (vendor f_uvc as shipped in android13-5.15, see
 * docs/USB_PERIPHERAL.md section 9):
 *
 *   - uvc_v4l2_streamon() ends with uvc_function_setup_continue(), which
 *     completes the host's pending SET_INTERFACE(alt=1) status stage. It must
 *     only be issued from UVC_EVENT_STREAMON; nothing else calls it.
 *   - uvc_v4l2_streamoff() also calls uvc_function_setup_continue(), for the
 *     pending SET_INTERFACE(alt=0). It must only be issued from
 *     UVC_EVENT_STREAMOFF. Calling it on our own initiative is just as
 *     dangerous as the STREAMON case.
 *
 * Both paths end in usb_composite_setup_continue(), which does
 * WARN(cdev, "Unexpected call") when no delayed status is pending; this ROM
 * panics on WARN (POWERUP_REASON=kpanic). Teardown therefore only closes the
 * fd: uvc_v4l2_release() stops the queue through uvcg_video_disable(), which
 * has no setup handshake, so it is safe at any time.
 */

#include "usb_common.h"

#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <linux/usb/ch9.h>
#include <linux/videodev2.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <thread>
#include <vector>

#define LOG_TAG "marionette_usbd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/* ---------------------------------------------------------------------------
 * UVC gadget ABI (include/uapi/linux/usb/g_uvc.h)
 *
 * The NDK header set does not match the vendor kernel, so the handful of
 * constants and structures we need are declared locally instead of including
 * <linux/usb/g_uvc.h>.
 * ------------------------------------------------------------------------- */

struct uvc_request_data {
    int32_t length;
    uint8_t data[60];
};

struct uvc_event {
    union {
        enum usb_device_speed speed;
        struct usb_ctrlrequest req;
        struct uvc_request_data data;
    } u;
};

static_assert(sizeof(uvc_request_data) == 64, "uvc_request_data ABI mismatch");
static_assert(sizeof(uvc_event) == 64, "uvc_event ABI mismatch");

/*
 * linux/usb/g_uvc.h has used _IOW('U', 1, ...) since at least 4.19, including
 * AOSP android13-5.15. With the wrong number video_ioctl2() routes the call to
 * uvc_v4l2_ioctl_default()'s default branch and returns -ENOTTY, so no class
 * request is ever answered: the host hangs on GET_CUR/SET_CUR(PROBE/COMMIT),
 * never issues SET_INTERFACE(alt=1) and UVC_EVENT_STREAMON never happens.
 */
#define UVCIOC_SEND_RESPONSE _IOW('U', 1, struct uvc_request_data)
static_assert(UVCIOC_SEND_RESPONSE == 0x40405501u, "UVCIOC_SEND_RESPONSE ABI mismatch");

enum : uint32_t {
    UVC_EVENT_FIRST = V4L2_EVENT_PRIVATE_START,
    UVC_EVENT_CONNECT = V4L2_EVENT_PRIVATE_START + 0,
    UVC_EVENT_DISCONNECT = V4L2_EVENT_PRIVATE_START + 1,
    UVC_EVENT_STREAMON = V4L2_EVENT_PRIVATE_START + 2,
    UVC_EVENT_STREAMOFF = V4L2_EVENT_PRIVATE_START + 3,
    UVC_EVENT_SETUP = V4L2_EVENT_PRIVATE_START + 4,
    UVC_EVENT_DATA = V4L2_EVENT_PRIVATE_START + 5,
    UVC_EVENT_CONTROL = V4L2_EVENT_PRIVATE_START + 6,
};

/* UVC request codes (linux/usb/video.h). */
enum : uint8_t {
    UVC_SET_CUR = 0x01,
    UVC_SET_MIN = 0x02,
    UVC_SET_MAX = 0x03,
    UVC_SET_RES = 0x04,
    UVC_GET_CUR = 0x81,
    UVC_GET_MIN = 0x82,
    UVC_GET_MAX = 0x83,
    UVC_GET_RES = 0x84,
    UVC_GET_LEN = 0x85,
    UVC_GET_INFO = 0x86,
    UVC_GET_DEF = 0x87,
};

/* Interface / selector ids. */
enum : uint8_t {
    UVC_INTF_CONTROL = 0,
    UVC_INTF_STREAMING = 1,
    UVC_VS_PROBE_CONTROL = 0x01,
    UVC_VS_COMMIT_CONTROL = 0x02,
    UVC_VC_REQUEST_ERROR_CODE_CONTROL = 0x02,
    UVC_PU_BRIGHTNESS_CONTROL = 0x02,
};

/*
 * struct uvc_streaming_control, exactly as in linux/usb/video.h: 34 bytes.
 * Kept local because only <linux/videodev2.h> and <linux/usb/ch9.h> are used.
 */
struct UvcStreamingControl {
    uint16_t bmHint;
    uint8_t bFormatIndex;
    uint8_t bFrameIndex;
    uint32_t dwFrameInterval;
    uint16_t wKeyFrameRate;
    uint16_t wPFrameRate;
    uint16_t wCompQuality;
    uint16_t wCompWindowSize;
    uint16_t wDelay;
    uint32_t dwMaxVideoFrameSize;
    uint32_t dwMaxPayloadTransferSize;
    uint32_t dwClockFrequency;
    uint8_t bmFramingInfo;
    uint8_t bPreferedVersion;
    uint8_t bMinVersion;
    uint8_t bMaxVersion;
} __attribute__((packed));

static_assert(sizeof(UvcStreamingControl) == 34, "uvc_streaming_control ABI mismatch");

/* ---------------------------------------------------------------------------
 * Small helpers
 * ------------------------------------------------------------------------- */

static const int kWidth = 640;
static const int kHeight = 480;
static const size_t kHeaderSize = 22; /* MRV1 header */
static const uint32_t kMaxPayload = 64u * 1024u * 1024u;
static const uint32_t kFrameInterval = 333333; /* 100 ns units, 30 fps */
static const uint32_t kMaxPayloadTransferSize = 3072; /* high-speed isoc */
static const unsigned kBufferCount = 4;
static const uint8_t kMagic[4] = {'M', 'R', 'V', '1'};

static int xioctl(int fd, unsigned long request, void *arg) {
    int ret;
    do {
        ret = ioctl(fd, request, arg);
    } while (ret < 0 && errno == EINTR);
    return ret;
}

static bool wait_ms(std::atomic<bool> *stop, int total_ms) {
    for (int waited = 0; waited < total_ms; waited += 25) {
        if (stop->load()) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(25));
    }
    return !stop->load();
}

static uint16_t le16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }

static uint32_t le32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint8_t clamp_u8(int v) { return (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v)); }

enum Pending : uint8_t {
    PENDING_NONE = 0,
    PENDING_PROBE,
    PENDING_COMMIT,
    PENDING_CONTROL,
};

struct VideoNode {
    int fd = -1;
    char path[128] = {0};
    unsigned nbufs = 0;
    struct {
        void *start;
        size_t len;
    } buffers[kBufferCount] = {};
    bool queued[kBufferCount] = {};
    unsigned width = 0;
    unsigned height = 0;
    size_t frame_bytes = 0;
    bool streaming = false;
    bool dead = false;
};

struct UvcControlState {
    UvcStreamingControl probe = {};
    UvcStreamingControl commit = {};
    uint16_t brightness = 128;
    uint8_t last_error = 0;
    int streaming_iface = -1; /* learned when interface numbering is not 0/1 */
    Pending pending = PENDING_NONE;
    uint8_t pending_entity = 0;
    uint8_t pending_cs = 0;
    unsigned setups = 0; /* diagnostics: first few class requests, then quiet */
};

struct Drops {
    uint64_t not_streaming = 0;
    uint64_t no_buffer = 0;
    uint64_t bad = 0;
};

struct Stats {
    uint64_t frames_in = 0;
    uint64_t frames_sent = 0;
    Drops drops;
};

struct FrameMap {
    int sw = -1;
    int sh = -1;
    int dw = -1;
    int dh = -1;
    std::vector<int> xs;
    std::vector<int> xc;
    std::vector<int> ys;

    void ensure(int src_w, int src_h, int dst_w, int dst_h) {
        if (src_w == sw && src_h == sh && dst_w == dw && dst_h == dh) return;
        sw = src_w;
        sh = src_h;
        dw = dst_w;
        dh = dst_h;
        xs.resize((size_t)dst_w);
        xc.resize((size_t)dst_w);
        ys.resize((size_t)dst_h);
        for (int x = 0; x < dst_w; ++x) {
            int sx = (int)(((int64_t)x * src_w) / dst_w);
            if (sx >= src_w) sx = src_w - 1;
            xs[(size_t)x] = sx;
            int cx = sx & ~1;
            if (cx > src_w - 2) cx = src_w - 2;
            if (cx < 0) cx = 0;
            xc[(size_t)x] = cx;
        }
        for (int y = 0; y < dst_h; ++y) {
            int sy = (int)(((int64_t)y * src_h) / dst_h);
            if (sy >= src_h) sy = src_h - 1;
            ys[(size_t)y] = sy;
        }
    }
};

/* ---------------------------------------------------------------------------
 * V4L2 output device
 * ------------------------------------------------------------------------- */

static void fill_black(void *dst, size_t bytes) {
    uint8_t *p = static_cast<uint8_t *>(dst);
    if (!p) return;
    size_t i = 0;
    for (; i + 3 < bytes; i += 4) {
        p[i + 0] = 16;  /* Y */
        p[i + 1] = 128; /* U */
        p[i + 2] = 16;  /* Y */
        p[i + 3] = 128; /* V */
    }
    for (; i < bytes; ++i) p[i] = 16;
}

static void fill_default_control(UvcStreamingControl &ctrl, const VideoNode &video) {
    memset(&ctrl, 0, sizeof ctrl);
    ctrl.bmHint = 1;
    ctrl.bFormatIndex = 1;
    ctrl.bFrameIndex = 1;
    ctrl.dwFrameInterval = kFrameInterval;
    ctrl.dwMaxVideoFrameSize = (uint32_t)video.frame_bytes;
    ctrl.dwMaxPayloadTransferSize = kMaxPayloadTransferSize;
    ctrl.bmFramingInfo = 3;
    ctrl.bPreferedVersion = 1;
    ctrl.bMaxVersion = 1;
}

static void queue_blank(VideoNode &v, unsigned index) {
    if (v.fd < 0 || index >= v.nbufs || index >= kBufferCount) return;
    if (!v.buffers[index].start || v.buffers[index].len == 0) return;

    const size_t bytes = std::min(v.buffers[index].len, v.frame_bytes);
    fill_black(v.buffers[index].start, bytes);

    struct v4l2_buffer buf;
    memset(&buf, 0, sizeof buf);
    buf.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    buf.memory = V4L2_MEMORY_MMAP;
    buf.index = index;
    buf.field = V4L2_FIELD_NONE;
    buf.bytesused = (uint32_t)bytes;

    if (xioctl(v.fd, VIDIOC_QBUF, &buf) < 0) {
        if (errno == ENODEV) {
            v.dead = true;
            return;
        }
        if (errno == EBUSY) {
            v.queued[index] = true; /* already owned by the driver */
        } else {
            LOGW("UVC: QBUF[%u]: %s", index, strerror(errno));
        }
        return;
    }
    v.queued[index] = true;
}

static void release_node(VideoNode &v) {
    if (v.fd >= 0) {
        /*
         * Deliberately no VIDIOC_STREAMOFF here. Closing the fd runs
         * uvc_v4l2_release() -> uvc_v4l2_disable() -> uvcg_video_disable(),
         * which cancels the queue without a setup handshake. The ioctl would
         * instead call uvc_function_setup_continue() and WARN (kpanic on this
         * ROM) unless the host happened to have a SET_INTERFACE in flight.
         */
        for (unsigned i = 0; i < kBufferCount; ++i) {
            if (v.buffers[i].start != nullptr) {
                munmap(v.buffers[i].start, v.buffers[i].len);
                v.buffers[i].start = nullptr;
                v.buffers[i].len = 0;
            }
        }
        close(v.fd);
        v.fd = -1;
    }
    v.nbufs = 0;
    v.width = 0;
    v.height = 0;
    v.frame_bytes = 0;
    v.streaming = false;
    v.dead = false;
    memset(v.queued, 0, sizeof v.queued);
    v.path[0] = '\0';
}

static bool resolve_node(const char *node, char *out, size_t cap) {
    if (strcmp(node, "auto") != 0) {
        const int n = snprintf(out, cap, "%s", node);
        return n > 0 && (size_t)n < cap;
    }

    DIR *dir = opendir("/sys/class/video4linux");
    if (!dir) return false;

    char best[64] = {0};
    long best_index = -1;
    struct dirent *de;
    while ((de = readdir(dir)) != nullptr) {
        if (strncmp(de->d_name, "video", 5) != 0) continue;
        char *end = nullptr;
        const long index = strtol(de->d_name + 5, &end, 10);
        if (!end || *end != '\0' || index < 0) continue;

        char sysfs[256];
        const int n = snprintf(sysfs, sizeof sysfs, "/sys/class/video4linux/%s/name", de->d_name);
        if (n <= 0 || (size_t)n >= sizeof sysfs) continue;

        const int fd = open(sysfs, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        char name[128];
        const ssize_t got = read(fd, name, sizeof name - 1);
        close(fd);
        if (got <= 0) continue;
        name[got] = '\0';
        if (strstr(name, "dwc3-gadget") == nullptr) continue;

        if (best_index < 0 || index < best_index) {
            best_index = index;
            snprintf(best, sizeof best, "%s", de->d_name);
        }
    }
    closedir(dir);

    if (best_index < 0) return false;
    const int n = snprintf(out, cap, "/dev/%s", best);
    return n > 0 && (size_t)n < cap;
}

static bool subscribe_events(VideoNode &v) {
    /*
     * UVC_EVENT_LAST is V4L2_EVENT_PRIVATE_START + 5 on 5.15, so there is no
     * UVC_EVENT_CONTROL to subscribe to (that request is newer than this
     * kernel and would only add noise). Subscribing UVC_EVENT_SETUP is also
     * what activates the function: the vendor f_uvc calls
     * uvc_function_connect() from uvc_v4l2_subscribe_event(), and fails with
     * -EBUSY if another handle is already subscribed.
     */
    static const uint32_t kEvents[] = {
        UVC_EVENT_CONNECT, /* == UVC_EVENT_FIRST */
        UVC_EVENT_SETUP,
        UVC_EVENT_DATA,
        UVC_EVENT_STREAMON,
        UVC_EVENT_STREAMOFF,
        UVC_EVENT_DISCONNECT,
    };

    bool ok = true;
    for (uint32_t type : kEvents) {
        struct v4l2_event_subscription sub;
        memset(&sub, 0, sizeof sub);
        sub.type = type;
        if (xioctl(v.fd, VIDIOC_SUBSCRIBE_EVENT, &sub) < 0) {
            if (type == UVC_EVENT_SETUP || type == UVC_EVENT_DATA || type == UVC_EVENT_STREAMON ||
                type == UVC_EVENT_STREAMOFF) {
                LOGW("UVC: subscribe 0x%08x failed: %s", type, strerror(errno));
                ok = false;
            }
        }
    }
    return ok;
}

static bool open_uvc_node(const char *node, VideoNode &v, UvcControlState &st, bool verbose) {
    release_node(v);
    st = UvcControlState();

    if (!resolve_node(node, v.path, sizeof v.path)) {
        if (verbose) LOGW("UVC: waiting for node (%s)", node);
        return false;
    }

    const int fd = open(v.path, O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        if (verbose) LOGW("UVC: open %s: %s", v.path, strerror(errno));
        v.path[0] = '\0';
        return false;
    }
    v.fd = fd;

    struct v4l2_capability cap;
    memset(&cap, 0, sizeof cap);
    if (xioctl(fd, VIDIOC_QUERYCAP, &cap) < 0) {
        if (verbose) LOGW("UVC: %s QUERYCAP: %s", v.path, strerror(errno));
        release_node(v);
        return false;
    }
    if (!(cap.capabilities & V4L2_CAP_VIDEO_OUTPUT)) {
        if (verbose) LOGW("UVC: %s is not a video output device", v.path);
        release_node(v);
        return false;
    }

    struct v4l2_format fmt;
    memset(&fmt, 0, sizeof fmt);
    fmt.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    fmt.fmt.pix.width = kWidth;
    fmt.fmt.pix.height = kHeight;
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_YUYV;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;
    fmt.fmt.pix.bytesperline = kWidth * 2;
    fmt.fmt.pix.sizeimage = kWidth * kHeight * 2;
    if (xioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
        if (verbose) LOGW("UVC: %s S_FMT: %s", v.path, strerror(errno));
        release_node(v);
        return false;
    }
    if (fmt.fmt.pix.pixelformat != V4L2_PIX_FMT_YUYV || fmt.fmt.pix.width < 2 ||
        fmt.fmt.pix.height < 1 || (fmt.fmt.pix.width & 1)) {
        LOGW("UVC: %s rejected YUYV %dx%d (got fmt 0x%08x %ux%u)", v.path, kWidth, kHeight,
             fmt.fmt.pix.pixelformat, fmt.fmt.pix.width, fmt.fmt.pix.height);
        release_node(v);
        return false;
    }
    v.width = fmt.fmt.pix.width;
    v.height = fmt.fmt.pix.height;
    v.frame_bytes = (size_t)v.width * (size_t)v.height * 2;

    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof req);
    req.count = kBufferCount;
    req.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    req.memory = V4L2_MEMORY_MMAP;
    if (xioctl(fd, VIDIOC_REQBUFS, &req) < 0) {
        if (verbose) LOGW("UVC: %s REQBUFS: %s", v.path, strerror(errno));
        release_node(v);
        return false;
    }
    if (req.count < 1) {
        LOGW("UVC: %s allocated no buffers", v.path);
        release_node(v);
        return false;
    }
    v.nbufs = std::min<unsigned>(req.count, kBufferCount);

    for (unsigned i = 0; i < v.nbufs; ++i) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof buf);
        buf.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        if (xioctl(fd, VIDIOC_QUERYBUF, &buf) < 0) {
            if (verbose) LOGW("UVC: %s QUERYBUF[%u]: %s", v.path, i, strerror(errno));
            release_node(v);
            return false;
        }
        void *p = mmap(nullptr, buf.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, buf.m.offset);
        if (p == MAP_FAILED) {
            if (verbose) LOGW("UVC: %s mmap[%u]: %s", v.path, i, strerror(errno));
            release_node(v);
            return false;
        }
        v.buffers[i].start = p;
        v.buffers[i].len = buf.length;
    }

    /*
     * Do not queue anything yet. The vendor uvc_v4l2_qbuf() only kicks the
     * video pump when uvc->state == UVC_STATE_STREAMING (i.e. after this
     * STREAMON), and its completion handler re-arms the pump only out of
     * req_ready. A buffer queued before STREAMON therefore sits in the vb2
     * queue and the pump never runs: the stream is "on" but no frame is
     * ever encoded or completed, so DQBUF keeps returning EAGAIN.
     *
     * The initial requests are queued by uvcg_video_enable() as zero-length
     * isoc requests; the QBUF loop in handle_streamon() is what starts the
     * actual data flow.
     */
    if (v.dead || !subscribe_events(v)) {
        release_node(v);
        return false;
    }

    fill_default_control(st.probe, v);
    fill_default_control(st.commit, v);
    st.streaming_iface = -1;

    LOGI("UVC: %s ready %ux%u YUYV, %u buffers", v.path, v.width, v.height, v.nbufs);
    return true;
}

static bool handle_streamon(VideoNode &v) {
    if (v.fd < 0) return false;
    if (v.streaming) {
        LOGI("UVC: STREAMON while already streaming, ignored");
        return true;
    }

    /* Only reached from UVC_EVENT_STREAMON: a host SETUP is pending. */
    int type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    if (xioctl(v.fd, VIDIOC_STREAMON, &type) < 0) {
        if (errno == ENODEV) {
            v.dead = true;
            return false;
        }
        LOGW("UVC: STREAMON: %s", strerror(errno));
        return true;
    }
    v.streaming = true;
    LOGI("UVC: host selected alt 1, streaming");

    /*
     * Queue after STREAMON, never before it: uvc_v4l2_qbuf() only schedules
     * the video pump while uvc->state is UVC_STATE_STREAMING, which the
     * STREAMON above sets. Queuing first leaves the pump idle and every
     * DQBUF returns EAGAIN, however many frames the app pushes. Every
     * buffer was returned to userspace by the previous STREAMOFF (or by the
     * initial REQBUFS), so this fills the whole queue again.
     */
    for (unsigned i = 0; i < v.nbufs; ++i) {
        if (!v.queued[i]) queue_blank(v, i);
    }
    return !v.dead;
}

static void handle_streamoff(VideoNode &v) {
    /*
     * The only place VIDIOC_STREAMOFF is allowed. The vendor f_uvc implements
     * it with uvc_function_setup_continue(), completing the status stage of
     * the host's SET_INTERFACE(alt=0); issuing it without that pending setup
     * trips the WARN in usb_composite_setup_continue() and panics.
     */
    if (!v.streaming) {
        LOGI("UVC: STREAMOFF event without a stream, ignored");
        return;
    }
    int type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    if (xioctl(v.fd, VIDIOC_STREAMOFF, &type) < 0) {
        if (errno == ENODEV) {
            v.dead = true;
            return;
        }
        LOGW("UVC: STREAMOFF: %s", strerror(errno));
    }
    v.streaming = false;
    memset(v.queued, 0, sizeof v.queued); /* STREAMOFF returns every buffer */
    LOGI("UVC: host selected alt 0, stream stopped");
}

/* ---------------------------------------------------------------------------
 * UVC control requests (ported from uvc_events_process_* in uvc-gadget.c)
 * ------------------------------------------------------------------------- */

static void process_streaming(UvcControlState &st, const VideoNode &v, uint8_t request, uint8_t cs,
                              uvc_request_data &resp) {
    if (cs != UVC_VS_PROBE_CONTROL && cs != UVC_VS_COMMIT_CONTROL) {
        st.last_error = 0x06;
        return; /* keep the stall default */
    }

    UvcStreamingControl *target = (cs == UVC_VS_PROBE_CONTROL) ? &st.probe : &st.commit;
    switch (request) {
    case UVC_SET_CUR:
        st.pending = (cs == UVC_VS_PROBE_CONTROL) ? PENDING_PROBE : PENDING_COMMIT;
        resp.length = (int32_t)sizeof(UvcStreamingControl);
        st.last_error = 0;
        break;
    case UVC_GET_CUR:
        memcpy(resp.data, target, sizeof *target);
        resp.length = (int32_t)sizeof *target;
        st.last_error = 0;
        break;
    case UVC_GET_MIN:
    case UVC_GET_MAX:
    case UVC_GET_DEF: {
        UvcStreamingControl ctrl;
        fill_default_control(ctrl, v);
        memcpy(resp.data, &ctrl, sizeof ctrl);
        resp.length = (int32_t)sizeof ctrl;
        st.last_error = 0;
        break;
    }
    case UVC_GET_RES:
        memset(resp.data, 0, sizeof(UvcStreamingControl));
        resp.length = (int32_t)sizeof(UvcStreamingControl);
        st.last_error = 0;
        break;
    case UVC_GET_LEN:
        resp.data[0] = 0x00;
        resp.data[1] = 0x22;
        resp.length = 2;
        st.last_error = 0;
        break;
    case UVC_GET_INFO:
        resp.data[0] = 0x03;
        resp.length = 1;
        st.last_error = 0;
        break;
    default:
        st.last_error = 0x07;
        break; /* unsupported: stall */
    }
}

static void process_control(UvcControlState &st, uint8_t request, uint8_t cs, uint8_t entity,
                            uint16_t wLength, uvc_request_data &resp) {
    if (entity == 0) {
        if (cs == UVC_VC_REQUEST_ERROR_CODE_CONTROL && request == UVC_GET_CUR) {
            resp.data[0] = st.last_error;
            resp.length = 1;
            return;
        }
        st.last_error = 0x06;
        return; /* keep the stall default */
    }
    if (entity > 2) {
        st.last_error = 0x06;
        return; /* no extension units in this descriptor */
    }

    if (request == UVC_GET_INFO) {
        resp.data[0] = 0x03;
        resp.length = 1;
        st.last_error = 0;
        return;
    }
    if (request == UVC_GET_LEN) {
        resp.data[0] = 0x02;
        resp.data[1] = 0x00;
        resp.length = 2;
        st.last_error = 0;
        return;
    }

    const size_t n = std::min<size_t>(wLength, sizeof resp.data);
    switch (request) {
    case UVC_GET_CUR:
    case UVC_GET_MIN:
    case UVC_GET_MAX:
    case UVC_GET_RES:
    case UVC_GET_DEF: {
        memset(resp.data, 0, n);
        resp.length = (int32_t)n;
        st.last_error = 0;
        /* Camera controls report zeros; the PU echoes its brightness. */
        if (entity == 2 && cs == UVC_PU_BRIGHTNESS_CONTROL && n >= 2) {
            uint16_t value = 0;
            switch (request) {
            case UVC_GET_CUR: value = st.brightness; break;
            case UVC_GET_MAX: value = 255; break;
            case UVC_GET_RES: value = 1; break;
            case UVC_GET_DEF: value = 127; break;
            default: value = 0; break; /* GET_MIN */
            }
            resp.data[0] = (uint8_t)(value & 0xff);
            resp.data[1] = (uint8_t)(value >> 8);
        }
        break;
    }
    case UVC_SET_CUR:
    case UVC_SET_MIN:
    case UVC_SET_MAX:
    case UVC_SET_RES:
        st.pending = PENDING_CONTROL;
        st.pending_entity = entity;
        st.pending_cs = cs;
        resp.length = (int32_t)n;
        st.last_error = 0;
        break;
    default:
        st.last_error = 0x07;
        break; /* unsupported: stall */
    }
}

static bool classify_interface(UvcControlState &st, uint8_t iface, uint8_t entity, uint8_t cs,
                               uint8_t request, uint16_t wLength) {
    /*
     * Two numbering schemes must both work.
     *
     * The vendor f_uvc rewrites wIndex in uvc_function_setup() so the
     * streaming interface is reported as 1 and the control interface as 0,
     * whatever real interface numbers the composite core handed out. A
     * kernel without that rewrite passes the raw numbers (3/4 on this
     * phone), which the 34-byte probe/commit request identifies.
     *
     * VS requests always use bEntityID 0. VC requests carry an entity id,
     * except the request error code control (cs 2, one byte) - and cs 2 is
     * also VS commit control, so the length disambiguates the two.
     */
    if (entity == 0 && iface == UVC_INTF_STREAMING) return true;
    if ((int)iface == st.streaming_iface) return true;
    if (entity != 0) return false;
    if (cs != UVC_VS_PROBE_CONTROL && cs != UVC_VS_COMMIT_CONTROL) return false;

    if (cs == UVC_VC_REQUEST_ERROR_CODE_CONTROL && request == UVC_GET_CUR && wLength <= 1)
        return false; /* VC request error code, not VS commit */

    if (wLength >= sizeof(UvcStreamingControl) || request == UVC_GET_LEN)
        st.streaming_iface = iface;
    return true;
}

static void process_setup(UvcControlState &st, const VideoNode &v, const struct usb_ctrlrequest &req,
                          uvc_request_data &resp) {
    st.pending = PENDING_NONE;

    if ((req.bRequestType & USB_TYPE_MASK) == USB_TYPE_CLASS &&
        (req.bRequestType & USB_RECIP_MASK) == USB_RECIP_INTERFACE) {
        const uint8_t iface = (uint8_t)(req.wIndex & 0xff);
        const uint8_t entity = (uint8_t)(req.wIndex >> 8);
        const uint8_t cs = (uint8_t)(req.wValue >> 8);

        if (classify_interface(st, iface, entity, cs, req.bRequest, req.wLength)) {
            process_streaming(st, v, req.bRequest, cs, resp);
        } else {
            process_control(st, req.bRequest, cs, entity, req.wLength, resp);
        }
        return;
    }

    /*
     * Standard requests (GET_DESCRIPTOR, SET_INTERFACE, ...) are answered by
     * the function driver. If one still reaches userspace the reference stalls
     * it; keep resp.length == -EL2HLT.
     */
}

static void process_event_data(UvcControlState &st, const VideoNode &v, const uvc_request_data &data) {
    if (st.pending == PENDING_PROBE || st.pending == PENDING_COMMIT) {
        if (data.length >= (int32_t)sizeof(UvcStreamingControl)) {
            UvcStreamingControl ctrl;
            memcpy(&ctrl, data.data, sizeof ctrl);
            /* This descriptor exposes exactly one YUYV frame. */
            ctrl.bFormatIndex = 1;
            ctrl.bFrameIndex = 1;
            ctrl.dwMaxVideoFrameSize = (uint32_t)v.frame_bytes;
            if (ctrl.dwFrameInterval == 0 || ctrl.dwFrameInterval > 10000000u)
                ctrl.dwFrameInterval = kFrameInterval;
            if (ctrl.dwMaxPayloadTransferSize == 0)
                ctrl.dwMaxPayloadTransferSize = kMaxPayloadTransferSize;
            if (st.pending == PENDING_PROBE)
                st.probe = ctrl;
            else
                st.commit = ctrl;
        }
    } else if (st.pending == PENDING_CONTROL) {
        /* SET_CUR(brightness) is the only value we keep; other controls are
         * accepted (the host data stage must complete) and ignored. */
        if (st.pending_entity == 2 && st.pending_cs == UVC_PU_BRIGHTNESS_CONTROL &&
            data.length >= 2) {
            st.brightness = le16(data.data);
        }
    }
    st.pending = PENDING_NONE;
}

/* Returns false when the node is gone and must be closed/re-polled. */
static bool drain_events(VideoNode &v, UvcControlState &st) {
    for (;;) {
        struct v4l2_event ev;
        memset(&ev, 0, sizeof ev);
        if (xioctl(v.fd, VIDIOC_DQEVENT, &ev) < 0) {
            // ENOENT is the normal "queue is empty" answer for DQEVENT; the loop
            // polls unconditionally, so treating it as an error floods logcat.
            if (errno == EAGAIN || errno == EINTR || errno == ENOENT) return true;
            if (errno == ENODEV) {
                LOGW("UVC: node disappeared (DQEVENT)");
                v.dead = true;
                return false;
            }
            LOGW("UVC: DQEVENT: %s", strerror(errno));
            return true;
        }

        if (ev.type == UVC_EVENT_CONNECT) {
            LOGI("UVC: host connected");
            continue;
        }
        if (ev.type == UVC_EVENT_DISCONNECT) {
            /*
             * uvc_function_disable() disabled the endpoints, and the isoc
             * requests came back with -ESHUTDOWN, which leaves the vb2 queue
             * flagged UVC_QUEUE_DISCONNECTED. From then on every QBUF fails
             * with -ENODEV, so the camera could never come back without a
             * full teardown. Closing the fd does that teardown safely:
             * uvc_v4l2_release() -> uvc_v4l2_disable() -> uvcg_video_disable()
             * clears the flag and the loop reopens the node once the gadget
             * is bound again.
             */
            LOGI("UVC: host disconnected, recycling node");
            v.dead = true;
            return false;
        }
        if (ev.type == UVC_EVENT_STREAMON) {
            if (!handle_streamon(v)) return false;
            continue;
        }
        if (ev.type == UVC_EVENT_STREAMOFF) {
            handle_streamoff(v);
            if (v.dead) return false;
            continue;
        }
        if (ev.type == UVC_EVENT_CONTROL) {
            LOGI("UVC: control event");
            continue;
        }
        if (ev.type == UVC_EVENT_SETUP) {
            uvc_event uev;
            memcpy(&uev, ev.u.data, sizeof uev);

            uvc_request_data resp;
            memset(&resp, 0, sizeof resp);
            resp.length = -EL2HLT; /* stall unless a handler fills the data */
            process_setup(st, v, uev.u.req, resp);

            if (st.setups < 16) {
                st.setups++;
                LOGI("UVC: setup %02x/%02x v=%04x i=%04x l=%u -> %d", uev.u.req.bRequestType,
                     uev.u.req.bRequest, (unsigned)uev.u.req.wValue, (unsigned)uev.u.req.wIndex,
                     (unsigned)uev.u.req.wLength, resp.length);
            }

            if (xioctl(v.fd, UVCIOC_SEND_RESPONSE, &resp) < 0) {
                if (errno == ENODEV) {
                    v.dead = true;
                    return false;
                }
                LOGW("UVC: SEND_RESPONSE: %s (host control transfer left unanswered)",
                     strerror(errno));
            }
            continue;
        }
        if (ev.type == UVC_EVENT_DATA) {
            uvc_event uev;
            memcpy(&uev, ev.u.data, sizeof uev);
            process_event_data(st, v, uev.u.data);
            continue;
        }

        LOGW("UVC: unhandled event 0x%08x", ev.type);
    }
}

/* ---------------------------------------------------------------------------
 * NV21 / RGBA8888 -> YUYV
 * ------------------------------------------------------------------------- */

static void rgb_to_yuv(const uint8_t *p, int &y, int &u, int &v) {
    const int r = p[0];
    const int g = p[1];
    const int b = p[2];
    y = (77 * r + 150 * g + 29 * b) >> 8;
    u = ((-43 * r - 85 * g + 128 * b) >> 8) + 128;
    v = ((128 * r - 107 * g - 21 * b) >> 8) + 128;
}

static bool convert_frame(FrameMap &map, const uint8_t *src, size_t src_len, int sw, int sh,
                          uint8_t format, uint8_t *dst, int dw, int dh) {
    if (!src || !dst) return false;
    if (sw < 2 || sh < 1 || dw < 2 || dh < 1) return false;
    if (sw > 8192 || sh > 8192 || dw > 8192 || dh > 8192) return false;
    if ((dw & 1) != 0) return false;

    const size_t y_size = (size_t)sw * (size_t)sh;
    if (format == 0) { /* NV21: Y plane followed by interleaved VU */
        if ((sw & 1) != 0 || (sh & 1) != 0) return false;
        if (src_len < y_size + y_size / 2) return false;
    } else if (format == 1) { /* RGBA8888 */
        if (src_len / 4 < y_size) return false;
    } else {
        return false;
    }

    map.ensure(sw, sh, dw, dh);

    if (format == 0) {
        const uint8_t *chroma = src + y_size;
        for (int y = 0; y < dh; ++y) {
            const int sy = map.ys[(size_t)y];
            const uint8_t *yrow = src + (size_t)sy * (size_t)sw;
            const uint8_t *crow = chroma + (size_t)(sy >> 1) * (size_t)sw;
            uint8_t *out = dst + (size_t)y * (size_t)dw * 2;
            for (int x = 0; x < dw; x += 2) {
                const int cx = map.xc[(size_t)x];
                out[0] = yrow[map.xs[(size_t)x]];
                out[1] = crow[cx + 1]; /* U */
                out[2] = yrow[map.xs[(size_t)(x + 1)]];
                out[3] = crow[cx];     /* V */
                out += 4;
            }
        }
        return true;
    }

    for (int y = 0; y < dh; ++y) {
        const int sy = map.ys[(size_t)y];
        const uint8_t *srow = src + (size_t)sy * (size_t)sw * 4;
        uint8_t *out = dst + (size_t)y * (size_t)dw * 2;
        for (int x = 0; x < dw; x += 2) {
            const uint8_t *p0 = srow + (size_t)map.xs[(size_t)x] * 4;
            const uint8_t *p1 = srow + (size_t)map.xs[(size_t)(x + 1)] * 4;
            int y0, u0, v0, y1, u1, v1;
            rgb_to_yuv(p0, y0, u0, v0);
            rgb_to_yuv(p1, y1, u1, v1);
            out[0] = clamp_u8(y0);
            out[1] = clamp_u8((u0 + u1) / 2);
            out[2] = clamp_u8(y1);
            out[3] = clamp_u8((v0 + v1) / 2);
            out += 4;
        }
    }
    return true;
}

/* ---------------------------------------------------------------------------
 * Socket frames
 * ------------------------------------------------------------------------- */

static void handle_frame(VideoNode &v, FrameMap &map, const uint8_t *payload, size_t length,
                         uint8_t format, int sw, int sh, Stats &stats) {
    if (!v.streaming) {
        stats.drops.not_streaming++;
        return;
    }
    if (sw < 1 || sh < 1 || format > 1) {
        stats.drops.bad++;
        return;
    }

    struct v4l2_buffer buf;
    memset(&buf, 0, sizeof buf);
    buf.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    buf.memory = V4L2_MEMORY_MMAP;
    if (xioctl(v.fd, VIDIOC_DQBUF, &buf) < 0) {
        if (errno == ENODEV) {
            v.dead = true;
        } else {
            stats.drops.no_buffer++; /* host has nothing pending; drop */
        }
        return;
    }

    if (buf.index >= v.nbufs || buf.index >= kBufferCount || !v.buffers[buf.index].start ||
        v.buffers[buf.index].len < v.frame_bytes) {
        stats.drops.bad++;
        queue_blank(v, buf.index);
        return;
    }
    v.queued[buf.index] = false;

    if (buf.flags & V4L2_BUF_FLAG_ERROR) {
        /* The gadget cancelled the queue (host stop/disconnect): refill it. */
        stats.drops.bad++;
        queue_blank(v, buf.index);
        return;
    }

    if (!convert_frame(map, payload, length, sw, sh, format,
                       static_cast<uint8_t *>(v.buffers[buf.index].start), (int)v.width,
                       (int)v.height)) {
        stats.drops.bad++;
        queue_blank(v, buf.index);
        return;
    }

    buf.bytesused = (uint32_t)v.frame_bytes;
    buf.field = V4L2_FIELD_NONE;
    buf.flags = 0;
    // Diagnostic: prove what is actually in the mmap buffer at QBUF time.
    // A blank frame here (y0=16) means the conversion never landed; y0=235
    // means this kernel simply is not transmitting the buffer.
    static unsigned frame_log = 0;
    if (frame_log++ % 30 == 0) {
        const uint8_t *p = static_cast<const uint8_t *>(v.buffers[buf.index].start);
        LOGI("UVC: qbuf idx=%u len=%zu used=%u y0=%u y1=%u y2=%u u=%u v=%u",
             buf.index, v.buffers[buf.index].len, buf.bytesused, p[0], p[2], p[4], p[1], p[3]);
    }
    if (xioctl(v.fd, VIDIOC_QBUF, &buf) < 0) {
        if (errno == ENODEV) {
            v.dead = true;
        } else {
            LOGW("UVC: QBUF(frame): %s", strerror(errno));
            stats.drops.no_buffer++;
        }
        return;
    }
    v.queued[buf.index] = true;
    stats.frames_sent++;
}

static void consume_socket_data(VideoNode &v, std::vector<uint8_t> &rx, FrameMap &map,
                                Stats &stats) {
    size_t offset = 0;

    while (!v.dead && rx.size() - offset >= sizeof kMagic) {
        const uint8_t *p = rx.data() + offset;

        if (memcmp(p, kMagic, sizeof kMagic) != 0) {
            /* Resync, keeping a possible partial magic at the tail. */
            const uint8_t *begin = rx.data() + offset + 1;
            const uint8_t *end = rx.data() + rx.size();
            const uint8_t *hit = std::search(begin, end, kMagic, kMagic + sizeof kMagic);
            size_t skip;
            if (hit == end) {
                const size_t remaining = rx.size() - offset;
                skip = remaining > 3 ? remaining - 3 : 0;
            } else {
                skip = (size_t)(hit - (rx.data() + offset));
            }
            stats.drops.bad++;
            if (skip == 0) break;
            offset += skip;
            continue;
        }

        if (rx.size() - offset < kHeaderSize) break;

        const uint32_t length = le32(p + 10);
        if (length > kMaxPayload) {
            stats.drops.bad++;
            offset += sizeof kMagic; /* corrupt header: look for the next magic */
            continue;
        }
        if (rx.size() - offset - kHeaderSize < (size_t)length) break; /* wait for it */

        const uint16_t sw = le16(p + 4);
        const uint16_t sh = le16(p + 6);
        const uint8_t format = p[8];
        /* p[9] flags and p[14..21] pts are unused: the gadget driver stamps
         * the USB payload headers itself, the app PTS only orders captures. */

        stats.frames_in++;
        handle_frame(v, map, p + kHeaderSize, length, format, (int)sw, (int)sh, stats);
        offset += kHeaderSize + (size_t)length;
    }

    if (offset > 0) rx.erase(rx.begin(), rx.begin() + (std::ptrdiff_t)offset);
}

/* ---------------------------------------------------------------------------
 * Entry point
 * ------------------------------------------------------------------------- */

int uvc_run(const char *node, int port, const char *token, std::atomic<bool> *stop) {
    if (!node || !token || !stop) return -1;

    const int listen_fd = listen_loopback(port);
    if (listen_fd < 0) return -1;
    LOGI("UVC listening on 127.0.0.1:%d (node %s)", port, node);

    VideoNode video;
    UvcControlState ctl;
    FrameMap map;
    Stats stats;
    std::vector<uint8_t> rx;
    rx.reserve(1 << 16);

    int client_fd = -1;
    int open_attempt = 0;
    auto last_stats = std::chrono::steady_clock::now();

    while (!stop->load()) {
        if (video.fd < 0) {
            const bool verbose = (open_attempt++ % 25) == 0;
            if (!open_uvc_node(node, video, ctl, verbose)) {
                if (!wait_ms(stop, 200)) break;
                continue;
            }
            open_attempt = 0;
            rx.clear();
            last_stats = std::chrono::steady_clock::now();
        }

        struct pollfd pfds[3];
        pfds[0].fd = video.fd;
        // V4L2 signals queued events through POLLPRI; vb2_poll() only sets it
        // for a v4l2_fh device (this one) when v4l2_event_pending() is true.
        // It is requested as an optimisation only - the event queue is
        // drained unconditionally below, because an unread UVC_EVENT_SETUP
        // leaves the host's control transfer hanging.
        pfds[0].events = POLLIN | POLLPRI;
        pfds[0].revents = 0;
        pfds[1].fd = listen_fd;
        pfds[1].events = POLLIN;
        pfds[1].revents = 0;
        pfds[2].fd = client_fd;
        pfds[2].events = client_fd >= 0 ? POLLIN : 0;
        pfds[2].revents = 0;
        const nfds_t nfds = client_fd >= 0 ? 3 : 2;

        const int pr = poll(pfds, nfds, 50);
        if (pr < 0) {
            if (errno == EINTR) continue;
            LOGW("UVC: poll: %s", strerror(errno));
            break;
        }

        /*
         * Control responses are latency critical, so events come first - and
         * they are drained on every iteration instead of only when poll()
         * reports POLLPRI. For a video *output* queue vb2_core_poll() returns
         * 0 unless EPOLLOUT was requested, so the event bits are not a
         * reliable wakeup source; with the old POLLIN-only wait the daemon
         * never dequeued a single UVC_EVENT_SETUP, the host's PROBE/COMMIT
         * transfers timed out, and the camera never reached alt 1.
         */
        if (!drain_events(video, ctl)) {
            LOGW("UVC: node lost, re-polling");
            release_node(video);
            rx.clear();
            if (!wait_ms(stop, 200)) break;
            continue;
        }
        if (video.dead) {
            release_node(video);
            rx.clear();
            if (!wait_ms(stop, 200)) break;
            continue;
        }

        if (pfds[1].revents & POLLIN) {
            int new_fd = -1;
            if (accept_with_token(listen_fd, token, &new_fd)) {
                if (client_fd >= 0) {
                    LOGI("UVC: replacing previous client");
                    close(client_fd);
                }
                client_fd = new_fd;
                int flags = fcntl(client_fd, F_GETFL, 0);
                if (flags >= 0) fcntl(client_fd, F_SETFL, flags | O_NONBLOCK);
                fcntl(client_fd, F_SETFD, FD_CLOEXEC);
                rx.clear();
                LOGI("UVC: client connected");
            }
        }

        if (client_fd >= 0 && (pfds[2].revents & (POLLIN | POLLERR | POLLHUP))) {
            uint8_t buf[64 * 1024];
            const ssize_t n = read(client_fd, buf, sizeof buf);
            if (n > 0) {
                rx.insert(rx.end(), buf, buf + n);
                consume_socket_data(video, rx, map, stats);
                if (video.dead) {
                    release_node(video);
                    rx.clear();
                    if (!wait_ms(stop, 200)) break;
                    continue;
                }
            } else if (n == 0) {
                LOGI("UVC: client disconnected");
                close(client_fd);
                client_fd = -1;
                rx.clear();
            } else if (errno != EAGAIN && errno != EINTR) {
                LOGW("UVC: client read: %s", strerror(errno));
                close(client_fd);
                client_fd = -1;
                rx.clear();
            }
        }

        const auto now = std::chrono::steady_clock::now();
        if (now - last_stats >= std::chrono::seconds(10)) {
            last_stats = now;
            if (stats.frames_in > 0 || stats.frames_sent > 0 || stats.drops.bad > 0) {
                LOGI("UVC: in=%llu out=%llu drop(notstream=%llu,nobuf=%llu,bad=%llu) streaming=%d",
                     (unsigned long long)stats.frames_in, (unsigned long long)stats.frames_sent,
                     (unsigned long long)stats.drops.not_streaming,
                     (unsigned long long)stats.drops.no_buffer,
                     (unsigned long long)stats.drops.bad, video.streaming ? 1 : 0);
            }
        }
    }

    release_node(video);
    if (client_fd >= 0) close(client_fd);
    close(listen_fd);
    LOGI("UVC stopped");
    return 0;
}
