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

static int x_ok(const char *p) {
  if (real_access) return real_access(p, X_OK) == 0;
  return access(p, X_OK) == 0;
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
