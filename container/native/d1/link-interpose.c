#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>
#include "compat-report.h"

static int copy_excl(const char *src, const char *dst) {
    int in = open(src, O_RDONLY | O_CLOEXEC);
    if (in < 0) return -1;
    struct stat st;
    if (fstat(in, &st) < 0) { int e = errno; close(in); errno = e; return -1; }
    if (!S_ISREG(st.st_mode)) { close(in); errno = EPERM; return -1; }
    int out = open(dst, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, st.st_mode & 07777);
    if (out < 0) { int e = errno; close(in); errno = e; return -1; }
    char buf[1 << 16];
    for (;;) {
        ssize_t n = read(in, buf, sizeof buf);
        if (n < 0) {
            if (errno == EINTR) continue;
            int e = errno; close(in); close(out); unlink(dst); errno = e; return -1;
        }
        if (n == 0) break;
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(out, buf + off, (size_t)(n - off));
            if (w < 0) {
                if (errno == EINTR) continue;
                int e = errno; close(in); close(out); unlink(dst); errno = e; return -1;
            }
            off += w;
        }
    }
    int rc = close(out);
    close(in);
    if (rc != 0) { int e = errno; unlink(dst); errno = e; return -1; }
    return 0;
}

static const char *at_path(int dirfd, const char *path, char *buf, size_t n) {
    if (path[0] == '/' || dirfd == AT_FDCWD) return path;
    snprintf(buf, n, "/proc/self/fd/%d/%s", dirfd, path);
    return buf;
}

int link(const char *oldpath, const char *newpath) {
    int rc = copy_excl(oldpath, newpath);
    lobos_compat_report("d1.link-interpose", "link->copy", newpath);
    return rc;
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    (void)flags;
    char a[PATH_MAX], b[PATH_MAX];
    int rc = copy_excl(at_path(olddirfd, oldpath, a, sizeof a), at_path(newdirfd, newpath, b, sizeof b));
    lobos_compat_report("d1.link-interpose", "linkat->copy", newpath);
    return rc;
}

#include <stdlib.h>
#include <unistd.h>

__attribute__((constructor)) static void lobos_own_session(void) {
    const char *v = getenv("LOBOS_OWN_SESSION");
    if (v != 0 && v[0] == 0x31) {
        setsid();
    }
}
