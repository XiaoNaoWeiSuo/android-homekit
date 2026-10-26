/* SPDX-License-Identifier: GPL-3.0-only
 * Read-only kernel log follower. Each record goes straight to write(2) so
 * stdio buffering on the phone cannot hide the last messages before a reboot.
 */
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <unistd.h>

int main(void) {
    int fd = open("/dev/kmsg", O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) { perror("open /dev/kmsg"); return 1; }
    if (lseek(fd, 0, SEEK_END) < 0) { perror("seek /dev/kmsg"); close(fd); return 1; }
    const char ready[] = "MARIONETTE_KMSG_READY live_records_only=true\n";
    if (write(STDOUT_FILENO, ready, sizeof(ready) - 1) != sizeof(ready) - 1) { close(fd); return 1; }
    for (;;) {
        struct pollfd pfd = {.fd = fd, .events = POLLIN};
        int status = poll(&pfd, 1, 1000);
        if (status < 0 && errno == EINTR) continue;
        if (status < 0 || pfd.revents & (POLLHUP | POLLNVAL)) { close(fd); return 1; }
        if (!status) continue;
        char record[65536];
        ssize_t length = read(fd, record, sizeof(record));
        if (length < 0 && (errno == EAGAIN || errno == EINTR || errno == EPIPE)) continue;
        if (length <= 0) { perror("read /dev/kmsg"); close(fd); return 1; }
        ssize_t offset = 0;
        while (offset < length) {
            ssize_t count = write(STDOUT_FILENO, record + offset, (size_t)(length - offset));
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) { close(fd); return 1; }
            offset += count;
        }
    }
}
