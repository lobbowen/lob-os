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

/* Node 通用预加载 shim（platform 伪装为 linux + getuid 等 POSIX 垫片）。
   由 TerminalProfile.ensureNodePosixShim 生成，每次终端启动刷新。 */
#define LOBOS_NODE_SHIM_PATH "/data/user/0/lobos.os/files/.node-posix-shim.js"

static int (*real_execve)(const char *, char *const[], char *const[]);
static int (*real_access)(const char *, int);

/* raw execve syscall，绕过 PLT/dlsym（guest loader 无 PT_INTERP，
   必须直接执行，不能被 ET_DYN 分支再套 linker64）。 */
#include <sys/syscall.h>
static int raw_execve(const char *p, char *const a[], char *const e[]) {
    return (int)syscall(__NR_execve, p, a, e);
}

static void bind_real(void) {
  if (!real_execve) real_execve = (int (*)(const char *, char *const[], char *const[]))dlsym(RTLD_NEXT, "execve");
  if (!real_access) real_access = (int (*)(const char *, int))dlsym(RTLD_NEXT, "access");
}

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
        size_t li = 0;
        int done = 0;
        for (;;) {
            char c;
            ssize_t r = read(fd, &c, 1);
            if (r <= 0) { done = 1; break; }
            if (c == '\n') { done = 1; break; }
            if (li + 1 < sizeof line) line[li++] = c;
        }
        line[li] = 0;
        if (li > 0 && line[0] != 35) {
            if (n + 1 >= cap) {
                char **bigger = (char **)realloc(out, (size_t)cap * 2 * sizeof(char *));
                if (!bigger) break;
                out = bigger;
                cap *= 2;
            }
            out[n++] = strdup(line);
        }
        if (done && li == 0) break;
    }
    if (n == 0) {
        out[n] = 0;
        lobos_sysenv_cache = (char *)out;
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

static char **env_put(char *const envp[], const char *kv) {
  size_t n = 0;
  while (envp && envp[n]) n++;
  char **out = (char **)malloc((n + 2) * sizeof(char *));
  if (!out) { errno = ENOMEM; return 0; }
  for (size_t i = 0; i < n; i++) out[i] = envp[i];
  out[n] = (char *)kv;
  out[n + 1] = 0;
  return out;
}

static char **env_merge_sys(char *const envp[]) {
    int extra = 0;
    const char *const *SK = lobos_sysenv();
    int kk = 0;
    while (SK[kk]) kk++;
    for (int s = 0; SK[s]; s++)
        if (!env_has(envp, SK[s])) {
            const char *v = getenv(SK[s]);
            if (v && *v) extra++;
        }
    if (!extra) return (char **)envp;
      {
          char msg[256];
          int m = snprintf(msg, sizeof msg, "env_merge envp=%s nkeys=%d extra=%d ",
              envp ? "nonnull" : "NULL", kk, extra);
          for (int s = 0; s < kk; s++) {
              const char *v = getenv(SK[s]);
              m += snprintf(msg + m, sizeof msg - (size_t)m, "%s%s=%s",
                  s ? "," : "", SK[s], (v && *v) ? v : "(none)");
              if (m < 0 || (size_t)m >= sizeof msg) break;
          }
          lobos_compat_report("d1.env-merge", "merged", msg);
      }
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

/* 读 ELF 的 PT_INTERP 字符串（程序头 p_type==3），判 glibc/bionic 的唯一依据。
   bionic 程序写 /system/bin/linker64；glibc 程序写 /lib/ld-linux-*.so.1。 */
static int read_interp(const char *path, char *out, size_t cap) {
    if (!path || cap < 2) return 0;
    out[0] = 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    unsigned char h[64];
    ssize_t hn = read(fd, h, sizeof h);
    if (hn < (ssize_t)sizeof h || h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') {
        close(fd); return 0;
    }
    unsigned long long phoff = 0, shoff = 0;
    unsigned int phentsize = 0, phnum = 0;
    for (int i = 0; i < 8; i++) phoff |= ((unsigned long long)h[0x20 + i]) << (8 * i);
    phentsize = (unsigned int)h[0x36] | ((unsigned int)h[0x37] << 8);
    phnum = (unsigned int)h[0x38] | ((unsigned int)h[0x39] << 8);
    (void)shoff;
    if (phoff == 0 || phentsize < 56 || phnum == 0 || phnum > 4096) { close(fd); return 0; }
    if (lseek(fd, (off_t)phoff, SEEK_SET) < 0) { close(fd); return 0; }
    for (unsigned int i = 0; i < phnum; i++) {
        unsigned char p[56];
        if (read(fd, p, sizeof p) != (ssize_t)sizeof p) { close(fd); return 0; }
        unsigned int type = (unsigned int)p[0] | ((unsigned int)p[1] << 8) |
                            ((unsigned int)p[2] << 16) | ((unsigned int)p[3] << 24);
        if (type != 3) continue; /* PT_INTERP */
        unsigned long long off = 0, fsz = 0;
        for (int k = 0; k < 8; k++) {
            off |= ((unsigned long long)p[8 + k]) << (8 * k);
            fsz |= ((unsigned long long)p[32 + k]) << (8 * k);
        }
        if (fsz == 0 || fsz > 4096 || fsz >= cap) { close(fd); return 0; }
        if (lseek(fd, (off_t)off, SEEK_SET) < 0) { close(fd); return 0; }
        ssize_t n = read(fd, out, (size_t)fsz);
        close(fd);
        if (n <= 0) { out[0] = 0; return 0; }
        out[n < (ssize_t)cap ? n : (ssize_t)(cap - 1)] = 0;
        return 1;
    }
    close(fd);
    return 0;
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
        unsigned long long strsz = 0;
        unsigned long long parsed = 0, escaped = 0, dropped = 0;

        for (o = 0; o + 16 <= dynsz; o += 16) {
            unsigned long long tag = (unsigned long long)dyn[o] | ((unsigned long long)dyn[o+1] << 8)
                | ((unsigned long long)dyn[o+2] << 16) | ((unsigned long long)dyn[o+3] << 24)
                | ((unsigned long long)dyn[o+4] << 32) | ((unsigned long long)dyn[o+5] << 40)
                | ((unsigned long long)dyn[o+6] << 48) | ((unsigned long long)dyn[o+7] << 56);
            unsigned long long val = (unsigned long long)dyn[o+8] | ((unsigned long long)dyn[o+9] << 8)
                | ((unsigned long long)dyn[o+10] << 16) | ((unsigned long long)dyn[o+11] << 24)
                | ((unsigned long long)dyn[o+12] << 32) | ((unsigned long long)dyn[o+13] << 40)
                | ((unsigned long long)dyn[o+14] << 48) | ((unsigned long long)dyn[o+15] << 56);
            if (tag == 0) break;
            if (tag == 5) strtab_off = val;
            else if (tag == 10) strsz = val;
        }
        if (strtab_off == 0) {
            close(fd);
            snprintf(out, cap, "no-DT_STRTAB parsed=0 escaped=0 dropped=0");
            return -1;
        }

        if (strsz == 0) {
            close(fd);
            snprintf(out, cap, "no-DT_STRSZ parsed=0 escaped=0 dropped=0");
            return -1;
        }
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
            if (tag != 1) continue;

            if (val >= strsz) { dropped++; continue; }
            s = dynstr_at(path, strtab_off + val);
            if (s == 0) { dropped++; continue; }
            parsed++;
            if (n > 0) n += snprintf(out + n, cap - (size_t)n, " %s", s);
            else n = snprintf(out, cap, "%s", s);
            if (n < 0 || (size_t)n >= cap) break;
        }
        {
            int m = snprintf(out + (n > 0 ? n : 0), cap - (size_t)(n > 0 ? n : 0),
                " [parsed=%llu escaped=%llu dropped=%llu]",
                parsed, escaped, dropped);
            if (m > 0) n += m;
        }
        if (parsed == 0) {
            snprintf(out, cap, "parsed=0 escaped=%llu dropped=%llu reason=读不出任何依赖名",
                escaped, dropped);
            return -1;
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
  size_t extra = (size_t)nShebang, argc = 1 + extra + 1 + 2 /*expose+require*/, i, k = 0;
  char **nargv;
  if (argv) for (i = 1; argv[i]; i++) argc++;
  nargv = (char **)malloc((argc + 1) * sizeof(char *));
  if (!nargv) { errno = ENOMEM; return -1; }
  nargv[k++] = (char *)disp0;
  for (i = 0; i < extra; i++) nargv[k++] = shebangArgs[i];
  /* shebang #!/usr/bin/env node → 给 node 注入 --expose-internals，
     让依赖 Node 内部模块的 CLI（dsh/cordis 等）无需私有 native addon。
     NODE_OPTIONS 放这个 flag 会被 node 拒绝，必须命令行层注入。幂等。 */
  int add_ei = 0;
  if (disp0 && strcmp(disp0, "node") == 0) {
    add_ei = 1;
    if (argv) for (int z = 1; argv[z]; z++)
      if (strcmp(argv[z], "--expose-internals") == 0) { add_ei = 0; break; }
  }
  if (add_ei) {
    nargv[k++] = (char *)"--expose-internals";
    nargv[k++] = (char *)"--require";
    nargv[k++] = (char *)LOBOS_NODE_SHIM_PATH;
  }
  nargv[k++] = (char *)script;
  if (argv) for (i = 1; argv[i]; i++) nargv[k++] = argv[i];
  nargv[k] = NULL;
  real_execve(exe, nargv, envp);
  { int e = errno; free(nargv); errno = e; return -1; }
}

int execve(const char *path, char *const argv[], char *const envp[]) {

  if (envp) {
    char **merged = env_merge_sys(envp);
    if (!merged) return -1;
    envp = merged;
  }
  /* node 生态通用适配：让所有 node 启动（含 shebang `#!/usr/bin/env node`、
     npm 内部调用）都带 --expose-internals，使依赖 Node 内部模块的 CLI
     （dsh/cordis 等）无需私有 native addon。NODE_OPTIONS 放这个 flag 会被
     node 拒绝（RC=9），必须在命令行参数层注入。幂等：已带则不重复。
     只针对 basename=="node"，且路径含 /node（避免误伤同名普通程序）。 */
  if (path && argv) {
    const char *bn = base_name(path);
    if (bn && strcmp(bn, "node") == 0 && strstr(path, "/node") != 0) {
      int already = 0;
      for (int i = 1; argv[i]; i++)
        if (strcmp(argv[i], "--expose-internals") == 0) { already = 1; break; }
      if (!already) {
        int ac0 = 0; while (argv[ac0]) ac0++;
        char **na = (char **)malloc((size_t)(ac0 + 4) * sizeof(char *));
        if (na) {
          int k = 0;
          na[k++] = (char *)path;
          na[k++] = (char *)"--expose-internals";
          na[k++] = (char *)"--require";
          na[k++] = (char *)LOBOS_NODE_SHIM_PATH;
          for (int i = 1; i < ac0; i++) na[k++] = argv[i];
          na[k] = 0;
          argv = na;  /* 后续所有分支用带 expose+shim 的 argv */
        }
      }
    }
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

      while (cmd < ntok && strchr(tok[cmd], '=')) {
        char **merged = env_put(envp, tok[cmd]);
        if (!merged) return -1;
        envp = merged;
        cmd++;
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

      /* glibc guest：判据只落在 PT_INTERP（bionic 与 glibc 的 e_type 同为 ET_DYN，
         不能用它判）。PT_INTERP 含 ld-linux 且环境递来了 guest loader 时，
         改写成 loader --library-path <libdir> <程序> <原参数...>。
         loader 自己无 PT_INTERP，不会递归进这里。 */
      char interp[256];
      if (read_interp(path, interp, sizeof interp) && strstr(interp, "ld-linux") != 0) {
          int envc = 0;
          while (envp && envp[envc]) envc++;
            const char *loader_env = getenv("LOBOS_GLIBC_LOADER");
            const char *libdir = getenv("LOBOS_GLIBC_LIBDIR");
            const char *loader = loader_env;

          if (loader && libdir && loader[0] == '/' && libdir[0] == '/') {
              /* glibc guest 的 SIGSYS handler（freestanding so）经 loader 的
                 --preload 注入；Android seccomp 拦 rseq 等 syscall 时靠它兜住。 */
              const char *sigsys = getenv("LOBOS_GUEST_SIGSYS");
              int has_sigsys = (sigsys && sigsys[0] == '/') ? 1 : 0;
              char **ga = (char **)malloc((ac + 4) * sizeof(char *));
              if (!ga) { errno = ENOMEM; return -1; }
              int gi = 0;
              ga[gi++] = (char *)loader;
              ga[gi++] = "--library-path";
              ga[gi++] = (char *)libdir;
              ga[gi++] = (char *)path;
              for (int k = 1; k < ac; k++) ga[gi++] = argv[k];
              ga[gi] = 0;
              lobos_compat_report("d1.exec-path", "glibc->guest-loader", path);
              /* guest 进程由 glibc loader 加载，不能带 bionic 的 LD_PRELOAD hook
                 （那是 bionic ELF，glibc loader 加载不了）；env 里去掉它。 */
              {
                  char **ge = (char **)malloc((envc + 1) * sizeof(char *));
                  if (ge) {
                      int gj = 0;
                      for (int ei = 0; ei < envc; ei++) {
                          if (strncmp(envp[ei], "LD_PRELOAD=", 11) == 0) continue;
                          if (strncmp(envp[ei], "GLIBC_TUNABLES=", 15) == 0) continue;
                          ge[gj++] = envp[ei];
                      }
                        if (has_sigsys) {
                            static char sigpre[1024];
                            snprintf(sigpre, sizeof sigpre, "LD_PRELOAD=%s", sigsys);
                            ge[gj++] = sigpre;
                        }
                      ge[gj++] = "GLIBC_TUNABLES=glibc.pthread.rseq=0"; /* seccomp: skip rseq */
                      ge[gj] = 0;
                      raw_execve(loader, ga, ge);
                      free(ge);
                  } else {
                      raw_execve(loader, ga, envp);
                  }
              }
              { int e = errno; free(ga); errno = e; }
              return -1;
          }
      }

      if (read_etype(path, &et) && et == 3 &&
          !(path && strcmp(path, LOBOS_SYSTEM_LINKER) == 0)) {
          char **na = (char **)malloc((ac + 3) * sizeof(char *));
          if (!na) { errno = ENOMEM; return -1; }
          na[0] = (char *)LOBOS_SYSTEM_LINKER;
          na[1] = (char *)path;
          for (int k2 = 1; k2 < ac; k2++) na[1 + k2] = argv[k2];
          na[1 + ac] = 0;
          lobos_compat_report("d1.exec-path", "ET_DYN->linker64", path);
          {
            /* 链式传递 hook：改写后的程序(linux linker64 加载)必须继续带
               LD_PRELOAD=liblobosposix.so，否则它的 execve 不再被拦截
               （交互 shell 就是这样丢掉 hook 的）。 */
            const char *hso = getenv("LOBOS_HOOK_SO");
            if (hso && hso[0] == '/') {
              int nc = 0; while (envp && envp[nc]) nc++;
              char **ne = (char **)malloc((nc + 2) * sizeof(char *));
              if (ne) {
                int j = 0, put = 0;
                static char lpbuf[1024];
                snprintf(lpbuf, sizeof lpbuf, "LD_PRELOAD=%s", hso);
                for (int q = 0; q < nc; q++) {
                  if (strncmp(envp[q], "LD_PRELOAD=", 11) == 0) {
                    if (!put) { ne[j++] = lpbuf; put = 1; }
                    continue;
                  }
                  ne[j++] = envp[q];
                }
                if (!put) ne[j++] = lpbuf;
                ne[j] = 0;
                real_execve(LOBOS_SYSTEM_LINKER, na, ne);
                free(ne);
              } else {
                real_execve(LOBOS_SYSTEM_LINKER, na, envp);
              }
            } else {
              real_execve(LOBOS_SYSTEM_LINKER, na, envp);
            }
          }
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
