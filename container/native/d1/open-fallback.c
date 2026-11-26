#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static int raw_openat(int dirfd, const char *path, int flags, mode_t mode) {
    return (int)syscall(__NR_openat, dirfd, path, flags, mode);
}

#include "tmp-redirect.h"

/* Pure virtual-root path translation. We deliberately do NOT fall back to a
   HOME directory fd on EACCES: returning a different object than requested is
   a semantic error (callers would read the wrong content / mistake it for
   success). EACCES is returned as-is so the program can handle it. */
int open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    char rb[PATH_MAX];
    const char *p = dsh_tmp_redirect(path, rb, sizeof rb);
    return raw_openat(AT_FDCWD, p, flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    char rb[PATH_MAX];
    const char *p = dsh_tmp_redirect(path, rb, sizeof rb);
    return raw_openat(dirfd, p, flags, mode);
}
