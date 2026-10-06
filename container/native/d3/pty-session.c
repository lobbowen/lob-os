/*
 * librivospty —— 伪终端（PTY）会话宿主。
 *
 * 为什么需要它：ProcessBuilder 起子进程时不给 PTY，于是 isatty() 为假、
 * 程序不进交互模式、不能读窗口大小 —— 「程序要终端就报错」就是这么来的。
 * 而 Android 的 ProcessBuilder 不暴露 setsid/TIOCSCTTY（无 JNI 就做不到），
 * 所以要一个原生件来做这件事：它分配 PTY，setsid + TIOCSCTTY，然后 execve。
 *
 * 为什么是「常驻可执行件 + 三条流」而不是 JNI：
 *   仓内已有两种原生范式（LD_PRELOAD 注入 / 可执行件探针），本件走后者。
 *   不写 JNI 就避开 System.loadLibrary 的装载路径问题，也不必把 .so 放进
 *   nativeLibraryDir 被误当共享库加载。
 *
 * ── 三条流（与 Kotlin 侧 ProcessBuilder 的三个流一一对应）──
 *   stdin   控制帧：控制命令（建会话 / 输入 / 设窗口大小 / 关会话 / 退出）
 *   stdout  数据帧：PTY 主端读到的字节（带会话 id）
 *   stderr  诊断：启动与错误信息（人类可读，不参与协议）
 *
 * ── 帧格式（定长小端，头 8 字节；之后是 payload）──
 *   uint8  kind     帧类型
 *   uint8  sid      会话 id（0 = 无会话 / 全局）
 *   uint16 flags    保留
 *   uint32 length   payload 字节数
 *   bytes  payload
 * 帧类型：
 *   1 OPEN      payload = 要执行的 argv（NUL 分隔）   → 回 2 READY
 *   2 READY     payload = slave 路径（NUL 结尾）+ 8 字节 winsize
 *   3 DATA      payload = PTY 读到的字节（可能是任意二进制）
 *   4 INPUT     payload = 写进 PTY 的字节
 *   5 WINSIZE   payload = 8 字节 {u16 rows, u16 cols, u16 xpix, u16 ypix}
 *   6 CLOSE     payload 空 → 关会话
 *   7 EXITED    payload = 8 字节 {u32 status, u32 term_signal}
 *   8 EXIT      payload 空 → 本进程退出
 *   9 ERROR     payload = NUL 结尾的诊断文本
 *
 * 为什么定长小端而不是 JSON：PTY 输出是**任意二进制**（含 NUL 与 0xFF），
 * JSON 编码会把它破坏或变得昂贵。定长头 + 裸字节是终端协议的老做法。
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define FRAME_MAX (64 * 1024)
#define MAX_SESSIONS 8

enum {
  FRAME_OPEN = 1,
  FRAME_READY = 2,
  FRAME_DATA = 3,
  FRAME_INPUT = 4,
  FRAME_WINSIZE = 5,
  FRAME_CLOSE = 6,
  FRAME_EXITED = 7,
  FRAME_EXIT = 8,
  FRAME_ERROR = 9,
};

typedef struct {
  uint8_t kind;
  uint8_t sid;
  uint16_t flags;
  uint32_t length;
} frame_hdr;

typedef struct {
  int master;   /* PTY 主端 */
  pid_t pid;    /* 子进程 */
  int open;     /* 还在用 */
  char slave[128];
} session;

static session g_sessions[MAX_SESSIONS];
static volatile sig_atomic_t g_stop = 0;

static void on_signal(int sig) {
  (void)sig;
  g_stop = 1;
}

/* ── 帧读写 ─────────────────────────────────────────────────────────────── */

/* 全读：短读在管道上很常见（payload 可能被拆成多块），必须补齐。 */
static int read_full(int fd, void *buf, size_t n) {
  size_t got = 0;
  while (got < n) {
    ssize_t r = read(fd, (char *)buf + got, n - got);
    if (r == 0) return 0;               /* 对端关了 */
    if (r < 0) {
      if (errno == EINTR) continue;
      return -1;
    }
    got += (size_t)r;
  }
  return 1;
}

static int write_full(int fd, const void *buf, size_t n) {
  size_t sent = 0;
  while (sent < n) {
    ssize_t r = write(fd, (const char *)buf + sent, n - sent);
    if (r < 0) {
      if (errno == EINTR) continue;
      return -1;
    }
    sent += (size_t)r;
  }
  return 0;
}

static int send_frame(int fd, uint8_t kind, uint8_t sid, const void *payload, uint32_t len) {
  frame_hdr h;
  h.kind = kind;
  h.sid = sid;
  h.flags = 0;
  h.length = len;
  /* 头与 payload 分两次 write_full 发。
     早先试过 writev 一把发，但 writev 的部分写之后的续传处理很容易写错
     （头被拆开时后续调用的偏移完全不同），而这里的吞吐不敏感 —— PTY 输出
     本来就是按 poll 回来的小批。简单正确的代码比快而脆的代码好。 */
  if (write_full(fd, &h, sizeof(h)) != 0) return -1;
  if (len > 0 && write_full(fd, payload, len) != 0) return -1;
  return 0;
}

static void send_error(int fd, const char *msg) {
  send_frame(fd, FRAME_ERROR, 0, msg, (uint32_t)strlen(msg) + 1);
}

static void diag(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  vfprintf(stderr, fmt, ap);
  va_end(ap);
  fputc('\n', stderr);
  fflush(stderr);
}

/* ── 会话 ──────────────────────────────────────────────────────────────── */

static int alloc_slot(void) {
  for (int i = 0; i < MAX_SESSIONS; i++) {
    if (!g_sessions[i].open) return i;
  }
  return -1;
}

/*
 * 建会话：开 PTY → fork → 子进程 setsid + TIOCSCTTY + dup2 到 0/1/2 → execve
 *
 * TIOCSCTTY 必须在 setsid 之后做，且子进程不能是会话首进程的组组长
 *（否则 EPERM）—— 所以这里 setsid 之后不再调 setpgid。
 */
static int session_open(int out_fd, uint8_t sid, const char *slave_path) {
  int master = posix_openpt(O_RDWR | O_NOCTTY);
  if (master < 0) return -1;
  if (grantpt(master) != 0 || unlockpt(master) != 0) {
    close(master);
    return -1;
  }
  const char *sn = ptsname(master);
  if (!sn) {
    close(master);
    return -1;
  }

  pid_t pid = fork();
  if (pid < 0) {
    close(master);
    return -1;
  }
  if (pid == 0) {
    /* 子 */
    close(out_fd);
    if (setsid() < 0) _exit(126);
    int slave = open(sn, O_RDWR);
    if (slave < 0) _exit(126);
    if (ioctl(slave, TIOCSCTTY, 0) < 0) _exit(126);
    if (dup2(slave, 0) < 0 || dup2(slave, 1) < 0 || dup2(slave, 2) < 0) _exit(126);
    if (slave > 2) close(slave);
    close(master);
    /* 恢复默认信号处理：父进程设的 g_stop 处理不该继承到命令里 */
    signal(SIGINT, SIG_DFL);
    signal(SIGTERM, SIG_DFL);
    signal(SIGPIPE, SIG_DFL);
    signal(SIGHUP, SIG_DFL);
    execv(slave_path, (char *const[]){(char *)slave_path, NULL});
    _exit(127);
  }

  /* 父 */
  g_sessions[sid].master = master;
  g_sessions[sid].pid = pid;
  g_sessions[sid].open = 1;
  snprintf(g_sessions[sid].slave, sizeof(g_sessions[sid].slave), "%s", sn);
  return 0;
}

static void session_close(int sid) {
  if (sid >= MAX_SESSIONS || !g_sessions[sid].open) return;
  /* 先给子进程一次机会正常退出：终端关闭时 SIGHUP 是标准语义 */
  if (g_sessions[sid].pid > 0) kill(g_sessions[sid].pid, SIGHUP);
  close(g_sessions[sid].master);
  int st = 0;
  if (g_sessions[sid].pid > 0) {
    for (int i = 0; i < 50; i++) {           /* 最多等 500ms */
      pid_t r = waitpid(g_sessions[sid].pid, &st, WNOHANG);
      if (r == g_sessions[sid].pid || (r < 0 && errno != EINTR)) break;
      usleep(10 * 1000);
    }
    if (waitpid(g_sessions[sid].pid, &st, WNOHANG) == 0) {
      kill(g_sessions[sid].pid, SIGKILL);
      waitpid(g_sessions[sid].pid, &st, 0);
    }
  }
  memset(&g_sessions[sid], 0, sizeof(session));
}

/* ── 主循环 ────────────────────────────────────────────────────────────── */

static int parse_argv(const char *blob, uint32_t len, char ***out) {
  /* NUL 分隔 → 指针数组 */
  int argc = 1;
  for (uint32_t i = 0; i < len; i++) {
    if (blob[i] == '\0') argc++;
  }
  char **argv = (char **)calloc((size_t)argc + 1, sizeof(char *));
  if (!argv) return -1;
  int k = 0;
  uint32_t start = 0;
  for (uint32_t i = 0; i <= len; i++) {
    if (i == len || blob[i] == '\0') {
      if (i > start || k == 0) {
        argv[k++] = strndup(blob + start, i - start);
      }
      start = i + 1;
    }
  }
  argv[k] = NULL;
  *out = argv;
  return k;
}

int main(void) {
  signal(SIGPIPE, SIG_IGN);   /* 对端没了也不要死，要走正常的收尾路径 */
  signal(SIGINT, on_signal);
  signal(SIGTERM, on_signal);

  int in_fd = STDIN_FILENO;
  int out_fd = STDOUT_FILENO;

  diag("librivospty ready");

  while (!g_stop) {
    struct pollfd fds[1 + MAX_SESSIONS];
    int nfds = 0;
    fds[nfds].fd = in_fd;
    fds[nfds].events = POLLIN;
    nfds++;
    for (int i = 0; i < MAX_SESSIONS; i++) {
      if (g_sessions[i].open) {
        fds[nfds].fd = g_sessions[i].master;
        fds[nfds].events = POLLIN;
        fds[nfds].revents = 0;
        nfds++;
      }
    }

    int r = poll(fds, (nfds_t)nfds, 200);
    if (r < 0) {
      if (errno == EINTR) continue;
      break;
    }

    /* 控制帧 */
    if (fds[0].revents & (POLLIN | POLLHUP)) {
      frame_hdr h;
      int got = read_full(in_fd, &h, sizeof(h));
      if (got == 0) break;                 /* Kotlin 侧关闭 → 本进程该退出了 */
      if (got < 0) { if (errno == EINTR) continue; break; }
      if (h.length > FRAME_MAX) {
        send_error(out_fd, "frame payload 超上限");
        continue;
      }
      char *payload = (char *)malloc(h.length + 1);
      if (!payload) { send_error(out_fd, "内存不足"); break; }
      if (h.length > 0 && read_full(in_fd, payload, h.length) <= 0) {
        free(payload);
        break;
      }
      payload[h.length] = '\0';

      switch (h.kind) {
        case FRAME_OPEN: {
          int slot = alloc_slot();
          if (slot < 0) { send_error(out_fd, "会话数已满"); break; }
          char **argv = NULL;
          int argc = parse_argv(payload, h.length, &argv);
          if (argc <= 0 || !argv) { free(payload); send_error(out_fd, "OPEN 的 argv 为空"); break; }
          const char *path = argv[0];
          if (session_open(out_fd, (uint8_t)slot, path) != 0) {
            send_error(out_fd, strerror(errno));
            free(argv);
            break;
          }
          /* 回 READY：slave 路径 + 当前窗口大小（调用方要据此设初始大小） */
          struct winsize ws;
          memset(&ws, 0, sizeof(ws));
          ioctl(g_sessions[slot].master, TIOCGWINSZ, &ws);
          uint8_t reply[sizeof(g_sessions[slot].slave) + 8];
          uint32_t n = (uint32_t)strlen(g_sessions[slot].slave) + 1;
          memcpy(reply, g_sessions[slot].slave, n);
          memcpy(reply + n, &ws, sizeof(ws));
          send_frame(out_fd, FRAME_READY, (uint8_t)slot, reply, n + (uint32_t)sizeof(ws));
          for (int i = 0; i < argc; i++) free(argv[i]);
          free(argv);
          break;
        }
        case FRAME_INPUT: {
          int s = h.sid;
          if (s >= MAX_SESSIONS || !g_sessions[s].open) { send_error(out_fd, "INPUT 指向不存在的会话"); break; }
          if (h.length > 0) write_full(g_sessions[s].master, payload, h.length);
          break;
        }
        case FRAME_WINSIZE: {
          int s = h.sid;
          if (s >= MAX_SESSIONS || !g_sessions[s].open) { send_error(out_fd, "WINSIZE 指向不存在的会话"); break; }
          if (h.length >= sizeof(struct winsize)) {
            struct winsize ws;
            memcpy(&ws, payload, sizeof(ws));
            ioctl(g_sessions[s].master, TIOCSWINSZ, &ws);
          }
          break;
        }
        case FRAME_CLOSE: session_close(h.sid); break;
        case FRAME_EXIT:
          free(payload);
          goto done;
        default:
          send_error(out_fd, "未知帧类型");
          break;
      }
      free(payload);
    }

    /* PTY 输出 */
    for (int i = 1; i < nfds; i++) {
      if (!(fds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
      /* slot 就是 i-1：poll 数组按 slot 0..N-1 顺序填的，第一个是控制流 */
      int sid = i - 1;
      if (!g_sessions[sid].open) continue;
      static char buf[FRAME_MAX];
      ssize_t n = read(g_sessions[sid].master, buf, sizeof(buf));
      if (n > 0) {
        send_frame(out_fd, FRAME_DATA, (uint8_t)sid, buf, (uint32_t)n);
      } else if (n == 0) {
        /* 主端 EOF = 会话结束（EIO 在 PTY 关闭时也是 EOF 形态） */
        int st = 0;
        pid_t p = g_sessions[sid].pid;
        if (p > 0) waitpid(p, &st, 0);
        uint8_t info[8];
        uint32_t status = WIFEXITED(st) ? (uint32_t)WEXITSTATUS(st) : 0u;
        uint32_t sig = WIFSIGNALED(st) ? (uint32_t)WTERMSIG(st) : 0u;
        memcpy(info, &status, 4);
        memcpy(info + 4, &sig, 4);
        send_frame(out_fd, FRAME_EXITED, (uint8_t)sid, info, 8);
        close(g_sessions[sid].master);
        memset(&g_sessions[sid], 0, sizeof(session));
      } else if (errno != EINTR && errno != EAGAIN) {
        send_error(out_fd, strerror(errno));
        session_close(sid);
      }
    }
  }

done:
  for (int i = 0; i < MAX_SESSIONS; i++) session_close(i);
  diag("librivospty bye");
  return 0;
}