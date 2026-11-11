#include "usb_common.h"

#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <memory>
#include <thread>

#define LOG_TAG "marionette_usbd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr unsigned kRate = 48000;
constexpr unsigned kPeriodFrames = 1024;
// f_uac2 on this kernel advertises "Period count: min=4 max=16"; the old value
// of 2 made every pcm_open fail with "cannot set hw params: Invalid argument"
// (tinypcminfo reports the range, tinycap opens fine once count >= 4).
constexpr unsigned kPeriodCount = 4;
constexpr unsigned kAppChannels = 2;  // the app always gets S16LE stereo

// tinyalsa flags and format (include/tinyalsa/pcm.h). libtinyalsa is not part
// of the NDK, so the ABI is mirrored here and resolved with dlopen/dlsym.
constexpr unsigned kPCMOut = 0x00000000;
constexpr unsigned kPCMIn = 0x10000000;
constexpr unsigned kFormatS16LE = 0;

struct pcm_config {
    unsigned int channels;
    unsigned int rate;
    unsigned int period_size;
    unsigned int period_count;
    unsigned int format;
    unsigned int start_threshold;
    unsigned int stop_threshold;
    unsigned int silence_threshold;
    // Layout must match <tinyalsa/asoundlib.h> exactly: pcm_open() does
    // `pcm->config = *config`, i.e. it copies sizeof(struct pcm_config) bytes
    // out of this object. silence_size sits between silence_threshold and
    // avail_min; leaving it out shifted avail_min by 4 bytes and made pcm_open
    // read past the end of the struct.
    unsigned int silence_size;
    int avail_min;
};

struct pcm;

struct TinyAlsa {
    // Signatures are taken verbatim from <tinyalsa/asoundlib.h>: pcm_open()
    // writes the negotiated params back into *config, and pcm_is_ready() /
    // pcm_get_error() take a non-const handle.
    using OpenFn = pcm *(*)(unsigned int, unsigned int, unsigned int, pcm_config *);
    using CloseFn = int (*)(pcm *);
    using IsReadyFn = int (*)(pcm *);
    using ErrorFn = const char *(*)(pcm *);
    using WriteFn = int (*)(pcm *, const void *, unsigned int);
    using ReadFn = int (*)(pcm *, void *, unsigned int);
    using PrepareFn = int (*)(pcm *);

    void *lib = nullptr;
    OpenFn open_fn = nullptr;
    CloseFn close_fn = nullptr;
    IsReadyFn is_ready_fn = nullptr;
    ErrorFn error_fn = nullptr;
    WriteFn writei_fn = nullptr;
    ReadFn readi_fn = nullptr;
    PrepareFn prepare_fn = nullptr;  // optional, used for xrun recovery only
    bool byte_io = false;            // pcm_read/pcm_write take bytes, not frames
};

std::shared_ptr<TinyAlsa> load_tinyalsa() {
    auto t = std::make_shared<TinyAlsa>();
    t->lib = dlopen("libtinyalsa.so", RTLD_NOW | RTLD_LOCAL);
    if (!t->lib) {
        const char *err = dlerror();
        LOGW("dlopen(libtinyalsa.so) failed: %s", err ? err : "unknown");
        return nullptr;
    }
    t->open_fn = reinterpret_cast<TinyAlsa::OpenFn>(dlsym(t->lib, "pcm_open"));
    t->close_fn = reinterpret_cast<TinyAlsa::CloseFn>(dlsym(t->lib, "pcm_close"));
    t->is_ready_fn = reinterpret_cast<TinyAlsa::IsReadyFn>(dlsym(t->lib, "pcm_is_ready"));
    t->error_fn = reinterpret_cast<TinyAlsa::ErrorFn>(dlsym(t->lib, "pcm_get_error"));
    t->writei_fn = reinterpret_cast<TinyAlsa::WriteFn>(dlsym(t->lib, "pcm_writei"));
    t->readi_fn = reinterpret_cast<TinyAlsa::ReadFn>(dlsym(t->lib, "pcm_readi"));
    // This build of libtinyalsa (Android 13) exports the byte-count variants
    // pcm_write/pcm_read instead of the frame-count pcm_writei/pcm_readi.
    if (!t->writei_fn) {
        t->writei_fn = reinterpret_cast<TinyAlsa::WriteFn>(dlsym(t->lib, "pcm_write"));
        t->byte_io = true;
    }
    if (!t->readi_fn) {
        t->readi_fn = reinterpret_cast<TinyAlsa::ReadFn>(dlsym(t->lib, "pcm_read"));
        t->byte_io = true;
    }
    if (!t->open_fn || !t->close_fn || !t->is_ready_fn || !t->error_fn ||
        !t->writei_fn || !t->readi_fn) {
        LOGW("libtinyalsa.so is missing required pcm symbols");
        dlclose(t->lib);
        t->lib = nullptr;
        return nullptr;
    }
    // Not required by the task, but xrun recovery needs it when present.
    t->prepare_fn = reinterpret_cast<TinyAlsa::PrepareFn>(dlsym(t->lib, "pcm_prepare"));
    dlerror();  // clear the "symbol not found" error, if any
    return t;
}

void sleep_for_stop(std::atomic<bool> *stop, int total_ms) {
    for (int slept = 0; slept < total_ms && !stop->load(); slept += 50)
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
}

// /proc/asound/cards entry, e.g.
//  0 [UAC2Gadget     ]: UAC2_Gadget - UAC2_Gadget
int find_uac2_card() {
    FILE *f = fopen("/proc/asound/cards", "r");
    if (!f) return -1;
    char line[256];
    int card = -1;
    while (fgets(line, sizeof(line), f)) {
        if (!strstr(line, "UAC2Gadget")) continue;
        int idx = -1;
        if (sscanf(line, " %d [", &idx) == 1 && idx >= 0) {
            card = idx;
            break;
        }
    }
    fclose(f);
    return card;
}

struct DeviceList {
    int index[8] = {};
    int count = 0;
};

// Enumerate cardN/pcm0p, pcm0c, pcm1p, ... (playback and capture device ids).
//
// This vendor kernel does not create per-card pcm entries at all: with a bound
// f_uac2, /proc/asound/card1 contains only `id`, while /proc/asound/pcm lists
// `01-00: UAC2 PCM : UAC2 PCM : playback 1 : capture 1` and /dev/snd carries
// pcmC1D0c/pcmC1D0p. Scanning only the card directory therefore reports "no PCM
// nodes" forever, which is what kept the audio bridge closed.
int enumerate_pcms_global(int card, DeviceList *out) {
    FILE *f = fopen("/proc/asound/pcm", "r");
    if (!f) return out->count;
    char line[256];
    while (fgets(line, sizeof(line), f) && out->count < 8) {
        int c = -1, d = -1;
        if (sscanf(line, " %d-%d:", &c, &d) != 2) continue;
        if (c != card || d < 0) continue;
        bool seen = false;
        for (int i = 0; i < out->count; i++)
            if (out->index[i] == d) seen = true;
        if (!seen) out->index[out->count++] = d;
    }
    fclose(f);
    return out->count;
}

// Last resort: the device nodes exist even when /proc exposes no entry.
int enumerate_pcms_from_dev(int card, DeviceList *out) {
    char path[96];
    for (int d = 0; d < 8 && out->count < 8; d++) {
        bool present = false;
        for (const char *dir : {"c", "p"}) {
            snprintf(path, sizeof(path), "/dev/snd/pcmC%dD%d%s", card, d, dir);
            if (access(path, F_OK) == 0) present = true;
        }
        if (!present) continue;
        bool seen = false;
        for (int i = 0; i < out->count; i++)
            if (out->index[i] == d) seen = true;
        if (!seen) out->index[out->count++] = d;
    }
    return out->count;
}

void sort_devices(DeviceList *out) {
    for (int i = 1; i < out->count; i++) {
        int v = out->index[i];
        int j = i - 1;
        while (j >= 0 && out->index[j] > v) {
            out->index[j + 1] = out->index[j];
            j--;
        }
        out->index[j + 1] = v;
    }
}

void enumerate_pcms(int card, DeviceList *out) {
    char dir[64];
    snprintf(dir, sizeof(dir), "/proc/asound/card%d", card);
    DIR *d = opendir(dir);
    if (!d) return;
    dirent *e;
    while ((e = readdir(d)) != nullptr && out->count < 8) {
        const char *name = e->d_name;
        size_t len = strlen(name);
        if (len < 4 || strncmp(name, "pcm", 3) != 0) continue;
        char dir_char = name[len - 1];
        if (dir_char != 'p' && dir_char != 'c') continue;
        int dev = atoi(name + 3);
        bool seen = false;
        for (int i = 0; i < out->count; i++)
            if (out->index[i] == dev) seen = true;
        if (!seen) out->index[out->count++] = dev;
    }
    closedir(d);
    if (out->count == 0) enumerate_pcms_global(card, out);
    if (out->count == 0) enumerate_pcms_from_dev(card, out);
    sort_devices(out);
}

struct Direction {
    pcm *handle = nullptr;
    unsigned channels = 0;
    int card = -1;
    int device = -1;
};

const char *direction_name(unsigned flags) { return (flags & kPCMIn) ? "PCM_IN" : "PCM_OUT"; }

// pcm_open the exact configuration the gadget was told to expose; channels
// may be 1 or 2 depending on p_chmask/c_chmask, so callers probe both.
pcm *open_ready(TinyAlsa &alsa, int card, int device, unsigned flags, unsigned channels) {
    pcm_config cfg{};
    cfg.channels = channels;
    cfg.rate = kRate;
    cfg.period_size = kPeriodFrames;
    cfg.period_count = kPeriodCount;
    cfg.format = kFormatS16LE;
    cfg.start_threshold = 0;
    cfg.stop_threshold = kPeriodFrames * kPeriodCount;
    cfg.silence_threshold = 0;
    // Measured with a probe against the vendor libtinyalsa: avail_min must be 0.
    // Any non-zero value makes SNDRV_PCM_IOCTL_SW_PARAMS fail with EINVAL on
    // this gadget PCM, while 0 lets the driver pick its own default.
    cfg.avail_min = 0;

    pcm *h = alsa.open_fn(static_cast<unsigned>(card), static_cast<unsigned>(device), flags, &cfg);
    if (!h) return nullptr;  // wrong direction or device busy: expected while probing
    if (!alsa.is_ready_fn(h)) {
        const char *err = alsa.error_fn(h);
        LOGW("pcm_open card=%d dev=%d %s ch=%u not ready: %s", card, device,
             direction_name(flags), channels, err ? err : "unknown");
        alsa.close_fn(h);
        return nullptr;
    }
    // pcm_open() writes the negotiated hw params back into the config struct.
    LOGI("pcm_open ok card=%d dev=%d %s ch=%u rate=%u period=%u frames x%u", card, device,
         direction_name(flags), channels, cfg.rate, cfg.period_size, cfg.period_count);
    return h;
}

bool probe_direction(TinyAlsa &alsa, int card, const DeviceList &devs, unsigned flags,
                     Direction *out) {
    static const unsigned kChannelCandidates[] = {2, 1};
    for (int i = 0; i < devs.count; i++) {
        for (unsigned ch : kChannelCandidates) {
            pcm *h = open_ready(alsa, card, devs.index[i], flags, ch);
            if (!h) continue;
            out->handle = h;
            out->channels = ch;
            out->card = card;
            out->device = devs.index[i];
            return true;
        }
    }
    return false;
}

bool pcm_recover(TinyAlsa &alsa, pcm *h, int err) {
    if (!alsa.prepare_fn) return false;
    LOGW("pcm error %d, attempting pcm_prepare", err);
    return alsa.prepare_fn(h) == 0;
}

// Transfer `frames` frames and report how many frames moved (negative = error).
//
// This libtinyalsa only exports the byte-count variants pcm_write()/pcm_read()
// (<tinyalsa/asoundlib.h>): `count` is a byte count and the call returns 0 on
// success, negative on failure — it never returns a frame count, and it blocks
// until the whole request is transferred. Treating 0 as "no data" is what made
// the speaker path drop every period. pcm_writei()/pcm_readi() (frame counts,
// return frames transferred) are used when the library exports them.
int pcm_transfer(TinyAlsa &alsa, bool write, pcm *h, void *buf, unsigned frames,
                 unsigned channels) {
    const unsigned frame_bytes = channels * sizeof(int16_t);
    if (alsa.byte_io) {
        unsigned bytes = frames * frame_bytes;
        int r = write ? alsa.writei_fn(h, buf, bytes) : alsa.readi_fn(h, buf, bytes);
        if (r == 0) return static_cast<int>(frames);            // full transfer
        if (r > 0) return r / static_cast<int>(frame_bytes);    // bytes, defensively
        return r;
    }
    return write ? alsa.writei_fn(h, buf, frames) : alsa.readi_fn(h, buf, frames);
}

bool read_uint_file(const char *path, unsigned *out) {
    FILE *f = fopen(path, "r");
    if (!f) return false;
    unsigned v = 0;
    int ok = fscanf(f, "%u", &v);
    fclose(f);
    if (ok != 1) return false;
    *out = v;
    return true;
}

// Diagnostic only: when the PCM nodes exist but no direction opens, the usual
// cause is that the uac2 function instance is configured for another rate or
// channel mask (configfs writes in start.sh can fail silently; the vendor
// default for c_srate is 64000). Print what the live instances actually hold
// so the mismatch is obvious in logcat.
void log_gadget_rate_hint(int card) {
    DIR *g = opendir("/config/usb_gadget");
    if (!g) return;
    dirent *e;
    while ((e = readdir(g)) != nullptr) {
        if (e->d_name[0] == '.') continue;
        char functions[256];
        snprintf(functions, sizeof(functions), "/config/usb_gadget/%s/functions",
                 e->d_name);
        DIR *f = opendir(functions);
        if (!f) continue;
        dirent *fe;
        while ((fe = readdir(f)) != nullptr) {
            if (strncmp(fe->d_name, "uac", 3) != 0) continue;
            char path[384];
            unsigned p_mask = 0, p_rate = 0, c_mask = 0, c_rate = 0;
            snprintf(path, sizeof(path), "%s/%s/p_chmask", functions, fe->d_name);
            read_uint_file(path, &p_mask);
            snprintf(path, sizeof(path), "%s/%s/p_srate", functions, fe->d_name);
            read_uint_file(path, &p_rate);
            snprintf(path, sizeof(path), "%s/%s/c_chmask", functions, fe->d_name);
            read_uint_file(path, &c_mask);
            snprintf(path, sizeof(path), "%s/%s/c_srate", functions, fe->d_name);
            read_uint_file(path, &c_rate);
            LOGW("card %d: %s/functions/%s p_chmask=0x%x p_srate=%u c_chmask=0x%x "
                 "c_srate=%u (daemon needs %u Hz on both)",
                 card, e->d_name, fe->d_name, p_mask, p_rate, c_mask, c_rate, kRate);
        }
        closedir(f);
    }
    closedir(g);
}

bool send_all(int fd, const void *data, size_t len, std::atomic<bool> *stop) {
    const char *p = static_cast<const char *>(data);
    while (len > 0) {
        if (stop->load()) return false;
        ssize_t n = send(fd, p, len, MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n > 0) {
            p += n;
            len -= static_cast<size_t>(n);
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
            pollfd pfd{fd, POLLOUT, 0};
            int pr = poll(&pfd, 1, 250);
            if (pr < 0 && errno != EINTR) return false;
            continue;
        }
        return false;
    }
    return true;
}

struct WorkerCtx {
    std::shared_ptr<TinyAlsa> alsa;
    Direction host_playback;  // ALSA capture (PCM_IN); host playback is read here
    Direction host_record;    // ALSA playback (PCM_OUT); host recording is written here
    int mic_listen = -1;
    int spk_listen = -1;
    const char *token = nullptr;
    std::atomic<bool> *stop = nullptr;
    std::atomic<bool> failed{false};
    std::atomic<int> finished{0};
    int expected = 2;  // workers actually started (1 when only one direction exists)
};

// 47833: app sends S16LE mono 48 kHz phone-mic PCM. Host recording is fed by
// ALSA playback (PCM_OUT + pcm_write). Upmix to stereo when the gadget asks
// for two channels.
void mic_worker(std::shared_ptr<WorkerCtx> ctx) {
    TinyAlsa &alsa = *ctx->alsa;
    pcm *h = ctx->host_record.handle;
    const unsigned channels = ctx->host_record.channels;
    int16_t mono[kPeriodFrames];
    int16_t stereo[kPeriodFrames * kAppChannels];
    size_t filled = 0;
    int client = -1;

    while (!ctx->stop->load() && !ctx->failed.load()) {
        if (client < 0) {
            if (!accept_with_token(ctx->mic_listen, ctx->token, &client)) continue;
            LOGI("mic client connected (port %d)", 47833);
            filled = 0;
        }
        pollfd pfd{client, POLLIN, 0};
        int pr = poll(&pfd, 1, 250);
        if (pr < 0) {
            if (errno == EINTR) continue;
            close(client);
            client = -1;
            continue;
        }
        if (pr == 0) continue;
        if (!(pfd.revents & POLLIN)) {
            close(client);
            client = -1;
            continue;
        }

        ssize_t n = recv(client, reinterpret_cast<char *>(mono) + filled, sizeof(mono) - filled,
                         MSG_DONTWAIT);
        if (n > 0) {
            filled += static_cast<size_t>(n);
            if (filled < sizeof(mono)) continue;
            filled = 0;

            const void *data = mono;
            if (channels == 2) {
                for (unsigned i = 0; i < kPeriodFrames; i++) {
                    stereo[i * 2] = mono[i];
                    stereo[i * 2 + 1] = mono[i];
                }
                data = stereo;
            }
            int w = pcm_transfer(alsa, /*write=*/true, h, const_cast<void *>(data),
                                 kPeriodFrames, channels);
            if (w < 0) {
                const char *err = alsa.error_fn(h);
                LOGW("pcm_write: %s (%d)", err ? err : "error", w);
                if (!pcm_recover(alsa, h, w)) {
                    ctx->failed.store(true);
                    break;
                }
            } else if (static_cast<unsigned>(w) < kPeriodFrames) {
                LOGW("pcm_write short write: %d/%u frames", w, kPeriodFrames);
            }
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) continue;
        close(client);
        client = -1;
        LOGI("mic client disconnected");
    }

    if (client >= 0) close(client);
    alsa.close_fn(h);
    ctx->finished.fetch_add(1);
}

// 47834: host playback arrives on ALSA capture (PCM_IN + pcm_read); forward it
// to the app as S16LE stereo 48 kHz, upmixing if the gadget is mono.
void spk_worker(std::shared_ptr<WorkerCtx> ctx) {
    TinyAlsa &alsa = *ctx->alsa;
    pcm *h = ctx->host_playback.handle;
    const unsigned channels = ctx->host_playback.channels;
    int16_t in[kPeriodFrames * kAppChannels];
    int16_t out[kPeriodFrames * kAppChannels];
    int client = -1;

    while (!ctx->stop->load() && !ctx->failed.load()) {
        if (client < 0) {
            if (!accept_with_token(ctx->spk_listen, ctx->token, &client)) continue;
            LOGI("spk client connected (port %d)", 47834);
        }

        int n = pcm_transfer(alsa, /*write=*/false, h, in, kPeriodFrames, channels);
        if (n < 0) {
            const char *err = alsa.error_fn(h);
            LOGW("pcm_read: %s (%d)", err ? err : "error", n);
            if (!pcm_recover(alsa, h, n)) {
                ctx->failed.store(true);
                break;
            }
            continue;
        }
        if (n == 0) {  // a blocking read cannot return empty; never spin on it
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        const int16_t *frames = in;
        if (channels == 1) {
            for (int i = 0; i < n; i++) {
                out[i * 2] = in[i];
                out[i * 2 + 1] = in[i];
            }
            frames = out;
        }
        size_t bytes = static_cast<size_t>(n) * kAppChannels * sizeof(int16_t);
        if (!send_all(client, frames, bytes, ctx->stop)) {
            close(client);
            client = -1;
            LOGI("spk client disconnected");
        }
    }

    if (client >= 0) close(client);
    alsa.close_fn(h);
    ctx->finished.fetch_add(1);
}

}  // namespace

int audio_run(int mic_port, int spk_port, const char *token, std::atomic<bool> *stop) {
    if (!token || !stop) return -2;

    auto alsa = load_tinyalsa();
    if (!alsa) return -1;

    int mic_listen = listen_loopback(mic_port);
    int spk_listen = listen_loopback(spk_port);
    if (mic_listen < 0 || spk_listen < 0) {
        if (mic_listen >= 0) close(mic_listen);
        if (spk_listen >= 0) close(spk_listen);
        return -1;
    }
    LOGI("audio listening mic=127.0.0.1:%d spk=127.0.0.1:%d", mic_port, spk_port);

    bool waiting_logged = false;
    bool abandoned = false;
    int hint_card = -1;

    while (!stop->load()) {
        int card = find_uac2_card();
        if (card < 0) {
            if (!waiting_logged) {
                LOGI("waiting for UAC2Gadget in /proc/asound/cards");
                waiting_logged = true;
            }
            sleep_for_stop(stop, 500);
            continue;
        }
        waiting_logged = false;

        DeviceList devs;
        enumerate_pcms(card, &devs);
        if (devs.count == 0) {
            LOGW("UAC2Gadget card %d: no PCM in card dir, /proc/asound/pcm or /dev/snd (retrying)", card);
            sleep_for_stop(stop, 500);
            continue;
        }
        LOGI("UAC2Gadget card %d: PCM devices %d,%d,...", card,
             devs.count > 0 ? devs.index[0] : -1, devs.count > 1 ? devs.index[1] : -1);

        // f_uac2 inverts the ALSA view: what the host plays is ALSA capture
        // (PCM_IN, read by us); what the host records is ALSA playback
        // (PCM_OUT, written by us). Probe instead of guessing device numbers.
        // Either direction may legitimately be absent (p_chmask or c_chmask 0),
        // so a missing half is a warning, not a reason to keep re-probing.
        Direction host_playback;
        Direction host_record;
        bool have_playback = probe_direction(*alsa, card, devs, kPCMIn, &host_playback);
        bool have_record = probe_direction(*alsa, card, devs, kPCMOut, &host_record);
        if (!have_playback && !have_record) {
            if (hint_card != card) {
                hint_card = card;
                log_gadget_rate_hint(card);
            }
            LOGW("UAC2Gadget card %d: PCM nodes exist but no direction opened (retrying)", card);
            sleep_for_stop(stop, 500);
            continue;
        }
        hint_card = -1;
        if (!have_playback)
            LOGW("UAC2Gadget card %d: no ALSA capture PCM (host playback disabled?)", card);
        if (!have_record)
            LOGW("UAC2Gadget card %d: no ALSA playback PCM (host recording disabled?)", card);
        LOGI("audio mapping: host playback -> %s card=%d dev=%d ch=%u (pcm_read); "
             "host recording -> %s card=%d dev=%d ch=%u (pcm_write)",
             have_playback ? "PCM_IN" : "n/a", host_playback.card, host_playback.device,
             host_playback.channels, have_record ? "PCM_OUT" : "n/a",
             host_record.card, host_record.device, host_record.channels);

        auto ctx = std::make_shared<WorkerCtx>();
        ctx->alsa = alsa;
        ctx->host_playback = host_playback;
        ctx->host_record = host_record;
        ctx->mic_listen = mic_listen;
        ctx->spk_listen = spk_listen;
        ctx->token = token;
        ctx->stop = stop;
        ctx->expected = (have_playback ? 1 : 0) + (have_record ? 1 : 0);

        std::thread spk;
        std::thread mic;
        if (have_playback) spk = std::thread(spk_worker, ctx);
        if (have_record) mic = std::thread(mic_worker, ctx);

        unsigned ticks = 0;
        while (!stop->load() && !ctx->failed.load()) {
            sleep_for_stop(stop, 250);
            // Notice a USB replug even while no client is connected.
            if (++ticks % 4 == 0 && find_uac2_card() != card) {
                LOGI("UAC2Gadget card %d disappeared", card);
                ctx->failed.store(true);
            }
        }

        if (!stop->load() && ctx->failed.load()) {
            LOGW("audio PCM error on card %d; re-probing", card);
        }

        // A blocking pcm_read/pcm_write can outlive *stop when the host has
        // no active stream. Wait a bounded time, then abandon the handles
        // (the process is on its way out) instead of wedging the daemon.
        int waited = 0;
        while (ctx->finished.load() < ctx->expected && waited < 2000) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            waited += 50;
        }
        if (ctx->finished.load() < ctx->expected) {
            LOGW("audio workers stuck in PCM I/O; abandoning handles");
            if (spk.joinable()) spk.detach();
            if (mic.joinable()) mic.detach();
            abandoned = true;
            break;
        }
        if (spk.joinable()) spk.join();
        if (mic.joinable()) mic.join();
    }

    if (!abandoned) {
        close(mic_listen);
        close(spk_listen);
    }
    LOGI("audio stopped");
    return 0;
}
