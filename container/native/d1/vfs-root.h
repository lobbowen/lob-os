#ifndef LOBOS_VFS_ROOT_H
#define LOBOS_VFS_ROOT_H

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

/* raw existence check, no libc path call (avoids hook recursion). */
static inline int lobos_vfs_exists_raw(const char *p) {
#ifndef __NR_faccessat
    return 0;
#else
    return syscall(__NR_faccessat, AT_FDCWD, p, 0, 0) == 0;
#endif
}

/*
 * LobOS user-space virtual root.
 *
 * Programs see a standard Linux path tree (/usr /lib /etc /tmp ...). This
 * function maps those absolute paths into the sandbox root ($LOBOS_VROOT) that
 * Android handed us. It is the single translation entry for every libc path
 * hook; no hook should hard-code /tmp or $HOME prefixes any more.
 *
 * Pure string prefix rewrite + whitelist bypass. No I/O, no allocation,
 * reentrant. Returns either the original pointer (no rewrite / no buffer) or buf.
 */

/* Prefixes that belong to the real Android system and must NOT be rewritten. */
static inline int lobos_vfs_passthrough(const char *p) {
    static const char *const sys[] = {
        "/system", "/apex", "/dev", "/proc", "/sys", "/data", "/storage",
        "/vendor", "/odm", "/product", "/mnt", "/sdcard", "/acct", "/debug",
    };
    for (unsigned i = 0; i < sizeof(sys) / sizeof(sys[0]); i++) {
        size_t n = strlen(sys[i]);
        if (strncmp(p, sys[i], n) == 0 && (p[n] == '/' || p[n] == '\0')) return 1;
    }
    return 0;
}

/*
 * Translate a program-visible absolute path into the sandbox.
 * out: caller buffer, must be >= cap. Returns path unchanged when no rewrite
 * is needed (relative path, whitelist, or buffer too small).
 */
static inline const char *lobos_vfs_translate(const char *path, char *out, size_t cap) {
    if (path == 0 || path[0] != '/' || out == 0 || cap == 0) return path;

    const char *vroot = getenv("LOBOS_VROOT");
    if (vroot == 0 || vroot[0] != '/' || vroot[0] == '\0') return path;

    /* Never rewrite a path already inside the vroot (idempotence / no recursion). */
    size_t vr = strlen(vroot);
    while (vr > 1 && vroot[vr - 1] == '/') vr--;
    if (strncmp(path, vroot, vr) == 0 && (path[vr] == '/' || path[vr] == '\0')) return path;

    if (lobos_vfs_passthrough(path)) return path;

    /* /tmp must agree with $TMPDIR (set by RuntimeEnvironment to ctx.cacheDir).
       Hardcoding $VROOT/.tmp here split the two views. Honor TMPDIR when it is
       an absolute path; only fall back to $VROOT/.tmp if TMPDIR is absent. */
    if (strncmp(path, "/tmp", 4) == 0 && (path[4] == '/' || path[4] == '\0')) {
        const char *tmpdir = getenv("TMPDIR");
        if (tmpdir != 0 && tmpdir[0] == '/') {
            size_t tl = strlen(tmpdir);
            size_t rest = strlen(path + 4);
            if (tl + rest + 1 <= cap) {
                memcpy(out, tmpdir, tl);
                memcpy(out + tl, path + 4, rest + 1);
                return out;
            }
        }
        /* fallback: map to $VROOT/.tmp */
        static const char tmp_suffix[] = "/.tmp";
        size_t tn = sizeof(tmp_suffix) - 1;
        size_t rest = strlen(path + 4);
        if (vr + tn + rest + 1 <= cap) {
            memcpy(out, vroot, vr);
            memcpy(out + vr, tmp_suffix, tn);
            memcpy(out + vr + tn, path + 4, rest + 1);
            return out;
        }
        return path;
    }

    /* Map the standard tree. usr-merge for /lib /bin /sbin. */
    const char *sub = 0;      /* suffix after the matched standard prefix */
    char merged_buf[8];
    (void)merged_buf;
    struct rule {
        const char *from; size_t n; const char *to; size_t tn;
        unsigned char overlay; /* 1 = fall back to real path if sandbox has none */
    };
    static const struct rule rules[] = {
        { "/usr",  4, "/usr",    4, 1 },
        /* /etc is intentionally NOT mapped: Android's real /etc (passwd,
           resolv.conf, zoneinfo) must stay visible to programs, and LobOS's
           own /etc files are consumed via explicit absolute paths already.
           Mapping it broke cat /etc/passwd. */
        { "/var",  4, "/var",    4, 1 },
        { "/opt",  4, "/opt",    4, 1 },
        { "/lib64",6, "/usr/lib",8, 1 },
        { "/lib",  4, "/usr/lib",8, 1 },
        { "/bin",  4, "/usr/bin",8, 1 },
        { "/sbin", 5, "/usr/bin",8, 1 },
    };
    const struct rule *hit = 0;
    for (unsigned i = 0; i < sizeof(rules) / sizeof(rules[0]); i++) {
        const char *f = rules[i].from;
        size_t n = rules[i].n;
        if (strncmp(path, f, n) == 0 && (path[n] == '/' || path[n] == '\0')) {
            hit = &rules[i];
            sub = path + n;    /* starts at '/' or '\0' */
            break;
        }
    }
    if (hit == 0) return path; /* other absolute paths (e.g. /cmdline) untouched */

    size_t need = vr + hit->tn + strlen(sub) + 1;
    if (need > cap) return path;
    memcpy(out, vroot, vr);
    memcpy(out + vr, hit->to, hit->tn);
    memcpy(out + vr + hit->tn, sub, strlen(sub) + 1);
    /* Overlay semantics: /usr /etc /var /opt /lib /bin are layered over the
       real Android root. If the sandbox has no such node, fall through to the
       real path (e.g. /etc/passwd, /usr/share/zoneinfo on the device). /tmp is
       sandbox-only and never falls back. */
    if (hit->overlay && !lobos_vfs_exists_raw(out)) return path;
    return out;
}

#endif
