#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
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

static const char *const SYS_KEYS[] = {
    "HOME", "TMPDIR", "PATH", "LANG", "SHELL",
    "LD_LIBRARY_PATH", "SSL_CERT_DIR", "SSL_CERT_FILE",
    "CURL_CA_BUNDLE", "GIT_SSL_CAINFO", "NODE_PATH",
    "LOBOS_COMPAT_LOG",
    0
};

static int env_has(char *const envp[], const char *k) {
    size_t n = strlen(k);
    for (int i = 0; envp && envp[i]; i++)
        if (!strncmp(envp[i], k, n) && envp[i][n] == '=') return 1;
    return 0;
}

static char **env_merge_sys(char *const envp[]) {
    int extra = 0;
    for (int s = 0; SYS_KEYS[s]; s++)
        if (!env_has(envp, SYS_KEYS[s])) {
            const char *v = getenv(SYS_KEYS[s]);
            if (v && *v) extra++;
        }
    if (!extra) return (char **)envp;
    int n = 0;
    while (envp && envp[n]) n++;
    char **out = (char **)malloc((n + extra + 1) * sizeof(char *));
    if (!out) { errno = ENOMEM; return 0; }
    int k = 0;
    for (int i = 0; i < n; i++) out[k++] = envp[i];
    for (int s = 0; SYS_KEYS[s]; s++) {
        if (env_has(envp, SYS_KEYS[s])) continue;
        const char *v = getenv(SYS_KEYS[s]);
        if (!v || !*v) continue;
        size_t kl = strlen(SYS_KEYS[s]), vl = strlen(v);
        char *e = (char *)malloc(kl + vl + 2);
        if (!e) break;
        memcpy(e, SYS_KEYS[s], kl); e[kl] = '=';
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
