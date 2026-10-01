#ifndef LOBOS_COMPAT_REPORT_H
#define LOBOS_COMPAT_REPORT_H

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <fcntl.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <unistd.h>

static inline void lobos_compat_report(const char *driver, const char *mode, const char *detail) {
    char buf[320];
    size_t i = 0;
    const char *segs[6];
    int s;
    segs[0] = "LOBOS_DEGRADE ";
    segs[1] = driver ? driver : "?";
    segs[2] = " ";
    segs[3] = mode ? mode : "?";
    segs[4] = " ";
    segs[5] = 0;
    for (s = 0; segs[s] != 0 && i < sizeof(buf) - 2; s++) {
        const char *p = segs[s];
        while (*p != 0 && i < sizeof(buf) - 2) buf[i++] = *p++;
    }
    if (detail != 0) {
        const char *p = detail;
        while (*p != 0 && i < sizeof(buf) - 2) {
            char c = *p++;
            buf[i++] = (c == '\n' || c == '\r') ? ' ' : c;
        }
    }
    buf[i++] = '\n';
    {
        const char *path = getenv("LOBOS_COMPAT_LOG");
        int fd = -1;
        if (path != 0 && path[0] == '/') {
            fd = (int)syscall(__NR_openat, AT_FDCWD, path, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0600);
        }
        if (fd >= 0) {
            ssize_t rc = write(fd, buf, i);
            (void)rc;
            close(fd);
        } else {
            ssize_t rc = write(2, buf, i);
            (void)rc;
        }
    }
}

#endif
