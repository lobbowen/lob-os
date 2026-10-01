#ifndef LOBOS_TMP_REDIRECT_H
#define LOBOS_TMP_REDIRECT_H

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <limits.h>
#include <stdlib.h>
#include <string.h>

static inline const char *dsh_tmp_redirect(const char *path, char *buf, size_t bufsz) {
    if (path == 0 || path[0] != '/') return path;
    if (strncmp(path, "/tmp", 4) != 0) return path;
    if (path[4] != '/' && path[4] != '\0') return path;  
    const char *tmp = getenv("TMPDIR");
    if (tmp == 0 || tmp[0] != '/') return path;
    size_t tl = strlen(tmp);
    while (tl > 1 && tmp[tl - 1] == '/') tl--;
    size_t rest = strlen(path + 4);
    if (tl + rest >= bufsz) return path;
    memcpy(buf, tmp, tl);
    memcpy(buf + tl, path + 4, rest + 1);
    return buf;
}
#endif
