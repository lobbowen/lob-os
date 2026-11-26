#ifndef LOBOS_TMP_REDIRECT_H
#define LOBOS_TMP_REDIRECT_H

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include "vfs-root.h"

/*
 * Back-compat shim. Historically this redirected only /tmp -> $TMPDIR.
 * Now all standard absolute paths are mapped by the unified virtual-root
 * translator; /tmp maps to $LOBOS_VROOT/.tmp (RuntimeEnvironment also keeps
 * TMPDIR pointing there for programs that consult it directly).
 */
static inline const char *dsh_tmp_redirect(const char *path, char *buf, size_t bufsz) {
    return lobos_vfs_translate(path, buf, bufsz);
}
#endif
