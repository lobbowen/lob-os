#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static int raw_openat(int dirfd, const char *path, int flags, mode_t mode) {
    return (int)syscall(__NR_openat, dirfd, path, flags, mode);
}

#include "tmp-redirect.h"
#include "compat-report.h"

static int substitute_dir_fd(const char *path) {
    if (path == 0 || path[0] != '/') return -1;
    const char *home = getenv("HOME");
    if (home == 0 || home[0] != '/') return -1;
    size_t n = strlen(path);
    if (strcmp(path, "/") != 0) {
        if (strncmp(home, path, n) != 0 || home[n] != '/') return -1;
    }
    struct stat st;
    if (syscall(__NR_newfstatat, AT_FDCWD, path, &st, 0) != 0) return -1;
    if (!S_ISDIR(st.st_mode)) return -1;
    int saved = errno;
    int fd = raw_openat(AT_FDCWD, home, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0);
    if (fd < 0) { errno = saved; return -1; }
    lobos_compat_report("d1.open-fallback", "EACCES->HOME", path);
    return fd;
}

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
    int fd = raw_openat(AT_FDCWD, p, flags, mode);
    if (fd >= 0 || errno != EACCES) return fd;
    return substitute_dir_fd(p);
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
    int fd = raw_openat(dirfd, p, flags, mode);
    if (fd >= 0 || errno != EACCES) return fd;
    if (dirfd == AT_FDCWD) return substitute_dir_fd(p);
    return fd;
}
