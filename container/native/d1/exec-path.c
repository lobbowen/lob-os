#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <jni.h>
#include "compat-report.h"

extern char **environ;

static int (*real_execve)(const char *, char *const[], char *const[]);
static int (*real_access)(const char *, int);

static void bind_real(void) {
  if (!real_execve) real_execve = (int (*)(const char *, char *const[], char *const[]))dlsym(RTLD_NEXT, "execve");
  if (!real_access) real_access = (int (*)(const char *, int))dlsym(RTLD_NEXT, "access");
}

// ── 系统视图注入 ──────────────────────────────────────────────
// 宿主（OsHostService）把自己的环境设成系统视图。
// 每个 Lob OS 起的进程都从 execve 出去，所以在这里合并一次，
// 而不是让每个调用方（程序宿主 / shell.exec / 终端）各自拼一遍。
//
// 契约：envp 里缺、但 environ 里有且属于系统视图的键，补进 envp。
// envp 里显式给了的，不覆盖 —— 显式优先。

static char *lobos_sysenv_cache = 0;

static const char *lobos_sysenv_path(void) {
    const char *p = getenv("LOBOS_SYSENV");
    if (p != 0 && p[0] == 47) return p;
    return "/data/user/0/lobos.os/files/etc/lobos-sysenv";
}

static const char *const *lobos_sysenv(void) {
    if (lobos_sysenv_cache) return (const char *const *)lobos_sysenv_cache;
    int fd = open(lobos_sysenv_path(), O_RDONLY | O_CLOEXEC);
    int cap = 16, n = 0;
    char **out;
    if (fd < 0) {
        static const char *const none[] = { 0 };
        lobos_sysenv_cache = (char *)none;
        return none;
    }
    out = (char **)malloc((size_t)cap * sizeof(char *));
    if (!out) { close(fd); static const char *const none[] = { 0 }; return none; }
    for (;;) {
        char line[128];
        ssize_t r = read(fd, line, sizeof line - 1);
        char *nl;
        if (r <= 0) break;
        line[r] = 0;
        nl = strchr(line, 10);
        if (nl) *nl = 0;
        if (line[0] == 0 || line[0] == 35) continue;
        if (n + 1 >= cap) {
            char **bigger = (char **)realloc(out, (size_t)cap * 2 * sizeof(char *));
            if (!bigger) break;
            out = bigger;
            cap *= 2;
        }
        out[n++] = strdup(line);
    }
    close(fd);
    out[n] = 0;
    lobos_sysenv_cache = (char *)out;
    return (const char *const *)out;
}

static int env_has(char *const envp[], const char *k) {
    size_t n = strlen(k);
    for (int i = 0; envp && envp[i]; i++)
        if (!strncmp(envp[i], k, n) && envp[i][n] == '=') return 1;
    return 0;
}

static char **env_merge_sys(char *const envp[]) {
    int extra = 0;
    const char *const *SK = lobos_sysenv();
    for (int s = 0; SK[s]; s++)
        if (!env_has(envp, SK[s])) {
            const char *v = getenv(SK[s]);
            if (v && *v) extra++;
        }
    if (!extra) return (char **)envp;
    int n = 0;
    while (envp && envp[n]) n++;
    char **out = (char **)malloc((n + extra + 1) * sizeof(char *));
    if (!out) { errno = ENOMEM; return 0; }
    int k = 0;
    for (int i = 0; i < n; i++) out[k++] = envp[i];
    for (int s = 0; SK[s]; s++) {
        if (env_has(envp, SK[s])) continue;
        const char *v = getenv(SK[s]);
        if (!v || !*v) continue;
        size_t kl = strlen(SK[s]), vl = strlen(v);
        char *e = (char *)malloc(kl + vl + 2);
        if (!e) break;
        memcpy(e, SK[s], kl); e[kl] = '=';
        memcpy(e + kl + 1, v, vl + 1);
        out[k++] = e;
    }
    out[k] = 0;
    return out;
}

static int x_ok(const char *p) {
  if (real_access) return real_access(p, X_OK) == 0;
  return access(p, X_OK) == 0;
}

#define LOBOS_SYSTEM_LINKER "/system/bin/linker64"

static int read_etype(const char *path, int *type) {
    unsigned char b[18];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, b, sizeof b);
    close(fd);
    if (n < (ssize_t)sizeof b) return 0;
    if (b[0] != 0x7f || b[1] != 'E' || b[2] != 'L' || b[3] != 'F') return 0;
    *type = (int)(b[16] | ((int)b[17] << 8));
    return 1;
}

static const char *dynstr_at(const char *path, unsigned long long off) {
    static __thread char buf[256];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    if (lseek(fd, (off_t)off, SEEK_SET) < 0) { close(fd); return 0; }
    ssize_t n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = 0;
    {
        int k;
        for (k = 0; k < n; k++) {
            unsigned char c = (unsigned char)buf[k];
            if (c < 0x20 || c > 0x7e) { buf[k] = 0; break; }
        }
    }
    return buf;
}
static int lobos_needed(const char *path, char *out, size_t cap) {
    unsigned char hdr[64];
    unsigned char ph[56];
    char dyn[4096];
    unsigned long long phoff;
    unsigned short phnum, phentsize, i;
    int fd, found = 0, n = 0;
    off_t dynoff = 0;
    unsigned long long dynsz = 0;
    if (cap < 2) return -1;
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { snprintf(out, cap, "open-errno=%d", errno); return -1; }
    if (read(fd, hdr, 64) != 64) { close(fd); snprintf(out, cap, "hdr-short"); return -1; }
    if (hdr[0] != 0x7f || hdr[1] != 0x45 || hdr[2] != 0x4c || hdr[3] != 0x46 || hdr[4] != 2) {
        close(fd); snprintf(out, cap, "not-elf64"); return -1;
    }
    phoff = (unsigned long long)hdr[32] | ((unsigned long long)hdr[33] << 8)
          | ((unsigned long long)hdr[34] << 16) | ((unsigned long long)hdr[35] << 24)
          | ((unsigned long long)hdr[36] << 32) | ((unsigned long long)hdr[37] << 40)
          | ((unsigned long long)hdr[38] << 48) | ((unsigned long long)hdr[39] << 56);
    phnum = (unsigned short)(hdr[56] | (hdr[57] << 8));
    phentsize = (unsigned short)(hdr[54] | (hdr[55] << 8));
    if (phentsize != 56) { close(fd); snprintf(out, cap, "phentsize=%d", phentsize); return -1; }
    for (i = 0; i < phnum; i++) {
        if (lseek(fd, (off_t)(phoff + (unsigned long long)i * 56), SEEK_SET) < 0) break;
        if (read(fd, ph, 56) != 56) break;
        if (ph[0] != 2) continue;
        dynoff = (off_t)((unsigned long long)ph[8] | ((unsigned long long)ph[9] << 8)
            | ((unsigned long long)ph[10] << 16) | ((unsigned long long)ph[11] << 24)
            | ((unsigned long long)ph[12] << 32) | ((unsigned long long)ph[13] << 40)
            | ((unsigned long long)ph[14] << 48) | ((unsigned long long)ph[15] << 56));
        dynsz = (unsigned long long)ph[32] | ((unsigned long long)ph[33] << 8)
            | ((unsigned long long)ph[34] << 16) | ((unsigned long long)ph[35] << 24)
            | ((unsigned long long)ph[36] << 32) | ((unsigned long long)ph[37] << 40)
            | ((unsigned long long)ph[38] << 48) | ((unsigned long long)ph[39] << 56);
        found = 1;
        break;
    }
    if (!found || dynsz == 0) { close(fd); snprintf(out, cap, "no-PT_DYNAMIC"); return -1; }
    if (dynsz > sizeof dyn) dynsz = sizeof dyn;
    if (lseek(fd, dynoff, SEEK_SET) < 0 || (unsigned long long)read(fd, dyn, (size_t)dynsz) != dynsz) {
        close(fd); snprintf(out, cap, "dyn-unreadable"); return -1;
    }
    close(fd);
    {
        unsigned long long o;
        unsigned long long strtab_off = 0;
        for (o = 0; o + 16 <= dynsz; o += 16) {
            unsigned long long tag = (unsigned long long)dyn[o] | ((unsigned long long)dyn[o+1] << 8)
                | ((unsigned long long)dyn[o+2] << 16) | ((unsigned long long)dyn[o+3] << 24)
                | ((unsigned long long)dyn[o+4] << 32) | ((unsigned long long)dyn[o+5] << 40)
                | ((unsigned long long)dyn[o+6] << 48) | ((unsigned long long)dyn[o+7] << 56);
            unsigned long long val = (unsigned long long)dyn[o+8] | ((unsigned long long)dyn[o+9] << 8)
                | ((unsigned long long)dyn[o+10] << 16) | ((unsigned long long)dyn[o+11] << 24)
                | ((unsigned long long)dyn[o+12] << 32) | ((unsigned long long)dyn[o+13] << 40)
                | ((unsigned long long)dyn[o+14] << 48) | ((unsigned long long)dyn[o+15] << 56);
            const char *s;
            if (tag == 0) break;
            if (tag == 5) { strtab_off = val; continue; }
            if (tag != 1) continue;
            if (strtab_off == 0) continue;
            if (val >= 65536) continue;
            s = dynstr_at(path, strtab_off + val);
            if (s == 0) continue;
            if (n > 0) n += snprintf(out + n, cap - (size_t)n, " %s", s);
            else n = snprintf(out, cap, "%s", s);
            if (n < 0 || (size_t)n >= cap) break;
        }
    }
    return n > 0 ? 0 : -1;
}

static int is_std_abs(const char *p) {
  if (!p) return 0;
  return strncmp(p, "/usr/bin/", 9) == 0 || strncmp(p, "/bin/", 5) == 0;
}

static const char *base_name(const char *p) {
  const char *s = strrchr(p, '/');
  return s ? s + 1 : p;
}

static int which_in_path(const char *name, char *out, size_t n) {
  const char *path; const char *p;
  if (!name || !*name || strchr(name, '/')) return 0;
  path = getenv("PATH");
  if (!path || !*path) return 0;
  p = path;
  while (*p) {
    const char *e = strchr(p, ':');
    size_t len = e ? (size_t)(e - p) : strlen(p);
    size_t nl = strlen(name);
    if (len > 0 && len + 1 + nl + 1 <= n) {
      memcpy(out, p, len);
      out[len] = '/';
      memcpy(out + len + 1, name, nl + 1);
      if (x_ok(out)) return 1;
    }
    if (!e) break;
    p = e + 1;
  }
  return 0;
}

static int resolve_interp(const char *bn, char *out, size_t n) {
  if (which_in_path(bn, out, n)) return 1;
  if (strcmp(bn, "sh") == 0 && which_in_path("bash", out, n)) return 1;
  return 0;
}

static int parse_shebang(const char *file, char *buf, size_t n, char *tok[], int max, int *ntok) {
  int fd; ssize_t got; size_t i = 0; char *nl; char *s; int k = 0;
  fd = open(file, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return 0;
  got = read(fd, buf, n - 1);
  close(fd);
  if (got < 4) return 0;
  buf[got] = 0;
  if (buf[0] != '#' || buf[1] != '!') return 0;
  nl = strchr(buf, '\n');
  if (!nl) return 0;
  *nl = 0;
  s = buf + 2;
  while (*s && k < max) {
    while (*s == ' ' || *s == '\t') s++;
    if (!*s) break;
    tok[k++] = s;
    while (*s && *s != ' ' && *s != '\t') s++;
    if (*s) { *s = 0; s++; }
  }
  *ntok = k;
  return k >= 1;
}

static int exec_with(const char *exe, const char *script, char *const argv[], char *const envp[], const char *disp0, char *shebangArgs[], int nShebang) {
  size_t extra = (size_t)nShebang, argc = 1 + extra + 1, i, k = 0;
  char **nargv;
  if (argv) for (i = 1; argv[i]; i++) argc++;
  nargv = (char **)malloc((argc + 1) * sizeof(char *));
  if (!nargv) { errno = ENOMEM; return -1; }
  nargv[k++] = (char *)disp0;
  for (i = 0; i < extra; i++) nargv[k++] = shebangArgs[i];
  nargv[k++] = (char *)script;
  if (argv) for (i = 1; argv[i]; i++) nargv[k++] = argv[i];
  nargv[k] = NULL;
  real_execve(exe, nargv, envp);
  { int e = errno; free(nargv); errno = e; return -1; }
}

int execve(const char *path, char *const argv[], char *const envp[]) {
  // 系统视图合并：一次，所有走 execve 的进程都拿到。
  // envp 为 NULL（execv 用 environ）时直接用 environ —— 那已含宿主环境。
  if (envp) {
    char **merged = env_merge_sys(envp);
    if (!merged) return -1;
    envp = merged;
  }
  char buf[512]; char *tok[8]; int ntok = 0;
  char resolved[1024];
  bind_real();
  if (!real_execve) { errno = ENOSYS; return -1; }
  if (!path) { errno = ENOENT; return -1; }

  if (parse_shebang(path, buf, sizeof buf, tok, 8, &ntok)) {
    const char *interp = tok[0];
    const char *disp0 = base_name(interp);
    int cmd = 1;
    int envStyle = strcmp(disp0, "env") == 0;
    if (envStyle) {
      while (cmd < ntok) {
        const char *t = tok[cmd];
        if (strchr(t, '=') || strcmp(t, "-S") == 0 || strcmp(t, "-i") == 0 || strcmp(t, "-u") == 0) { cmd++; continue; }
        break;
      }
      if (cmd >= ntok) return real_execve(path, argv, envp);
      interp = tok[cmd];
      disp0 = base_name(interp);
      cmd++;
    }
    if (envStyle || is_std_abs(interp)) {
      if (resolve_interp(disp0, resolved, sizeof resolved)) {
        lobos_compat_report("d1.exec-path", "stdio-abs->PATH", resolved);
        return exec_with(resolved, path, argv, envp, disp0, &tok[cmd], ntok - cmd);
      }
    }
    return real_execve(path, argv, envp);
  }

  if (is_std_abs(path)) {
    const char *bn = base_name(path);
    if (resolve_interp(bn, resolved, sizeof resolved)) return real_execve(resolved, argv, envp);
  }
  {
      int et = 0;
      int ac = 0;
      while (argv && argv[ac]) ac++;
      if (read_etype(path, &et) && et == 3) {
          char **na = (char **)malloc((ac + 3) * sizeof(char *));
          if (!na) { errno = ENOMEM; return -1; }
          na[0] = (char *)LOBOS_SYSTEM_LINKER;
          na[1] = (char *)path;
          for (int k2 = 0; k2 < ac; k2++) na[2 + k2] = argv[k2];
          na[2 + ac] = 0;
          lobos_compat_report("d1.exec-path", "ET_DYN->linker64", path);
          real_execve(LOBOS_SYSTEM_LINKER, na, envp);
          { int e = errno; free(na); errno = e; }
          return -1;
      }
  }
  return real_execve(path, argv, envp);
}

int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }

int execvp(const char *file, char *const argv[]) {
  char resolved[1024];
  bind_real();
  if (!real_execve) { errno = ENOSYS; return -1; }
  if (!file) { errno = ENOENT; return -1; }
  if (strchr(file, '/')) return execve(file, argv, environ);
  if (which_in_path(file, resolved, sizeof resolved)) return execve(resolved, argv, environ);
  {
    int (*real_execvp)(const char *, char *const[]) = (int (*)(const char *, char *const[]))dlsym(RTLD_NEXT, "execvp");
    if (real_execvp) return real_execvp(file, argv);
  }
  return execve(file, argv, environ);
}

static int exec_varargs(const char *path, const char *arg0, va_list ap, int usePath) {
  const char *args[256]; int n = 0;
  args[n++] = arg0;
  while (n < 255) { const char *a = va_arg(ap, const char *); if (!a) break; args[n++] = a; }
  args[n] = NULL;
  return usePath ? execvp(path, (char *const *)args) : execve(path, (char *const *)args, environ);
}

int execl(const char *path, const char *arg0, ...) {
  va_list ap; int r;
  va_start(ap, arg0); r = exec_varargs(path, arg0, ap, 0); va_end(ap);
  return r;
}

int execlp(const char *file, const char *arg0, ...) {
  va_list ap; int r;
  va_start(ap, arg0); r = exec_varargs(file, arg0, ap, 1); va_end(ap);
  return r;
}

int execle(const char *path, const char *arg0, ...) {
  va_list ap; const char *args[256]; int n = 0; char *const *envp; int r;
  va_start(ap, arg0);
  args[n++] = arg0;
  while (n < 255) { const char *a = va_arg(ap, const char *); if (!a) break; args[n++] = a; }
  args[n] = NULL;
  envp = va_arg(ap, char *const *);
  r = execve(path, (char *const *)args, envp ? envp : environ);
  va_end(ap);
  return r;
}
JNIEXPORT jstring JNICALL
Java_lobos_kernel_compat_PieceInspect_nativeNeeded(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    const char *p = (*env)->GetStringUTFChars(env, path, 0);
    char buf[2048];
    int r = lobos_needed(p, buf, sizeof buf);
    jstring out;
    if (r < 0 && buf[0] == 0) snprintf(buf, sizeof buf, "读不出动态段");
    out = (*env)->NewStringUTF(env, buf);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return out;
}

