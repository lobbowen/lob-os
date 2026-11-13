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
  FRAME_NOTE = 10,
};

typedef struct {
  uint8_t kind;
  uint8_t sid;
  uint16_t flags;
  uint32_t length;
} frame_hdr;

typedef struct {
  int master;
  pid_t pid;
  int open;
  char slave[128];

  struct winsize pending_ws;
  int pending_winsize;
} session;

static session g_sessions[MAX_SESSIONS];
static volatile sig_atomic_t g_stop = 0;

static void on_signal(int sig) {
  (void)sig;
  g_stop = 1;
}

static int read_full(int fd, void *buf, size_t n) {
  size_t got = 0;
  while (got < n) {
    ssize_t r = read(fd, (char *)buf + got, n - got);
    if (r == 0) return 0;
    if (r < 0) {
      if (errno == EINTR) continue;
      return -1;
    }
    got += (size_t)r;
  }
  return 1;
}

#ifndef PIPE_BUF
#define PIPE_BUF 4096
#endif

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

static int write_frame_atomic(int fd, const void *buf, size_t n) {
  size_t sent = 0;
  while (sent < n) {
    size_t chunk = n - sent;
    if (chunk > (size_t)PIPE_BUF) chunk = (size_t)PIPE_BUF;
    ssize_t r = write(fd, (const char *)buf + sent, chunk);
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

  if (len > FRAME_MAX) return -1;
  size_t total = sizeof(h) + (len > 0 ? (size_t)len : 0);
  unsigned char *buf = (unsigned char *)malloc(total);
  if (!buf) return -1;
  memcpy(buf, &h, sizeof(h));
  if (len > 0) memcpy(buf + sizeof(h), payload, len);

  int rc = write_frame_atomic(fd, buf, total);
  free(buf);
  return rc == 0 ? 0 : -1;
}

static void send_error(int fd, const char *msg) {
  send_frame(fd, FRAME_ERROR, 0, msg, (uint32_t)strlen(msg) + 1);
}

static int g_diag_fd = -1;

static void diag_to(int kind, const char *text) {
  char buf[512];
  int n = snprintf(buf, sizeof buf, "%s", text);
  if (g_diag_fd < 0) {
    fprintf(stderr, "[diag] 无通道 %s\n", buf);
    fflush(stderr);
    return;
  }
  if (n > 0 && send_frame(g_diag_fd, (uint8_t)kind, 0, buf, (uint32_t)n + 1) != 0)
    fprintf(stderr, "[diag] 通道断了 %s\n", buf);
  fprintf(stderr, "%s\n", buf);
  fflush(stderr);
}

static void diag_raw(const char *text) { diag_to(FRAME_ERROR, text); }

static void note_raw(const char *text) { diag_to(FRAME_NOTE, text); }

static int g_child_diag_fd = -1;

static int g_diag_read_fd = -1;

static void child_note(const char *text) {

  int fd = g_child_diag_fd;
  if (fd < 0) fd = 2;
  char buf[256];
  int n = snprintf(buf, sizeof buf, "%s\n", text);
  if (n > 0) { ssize_t w = write(fd, buf, (size_t)n); (void)w; }
}

static void child_fail(int out_fd, int line, const char *what) {
    char buf[160];
    int e = errno;
    int n = snprintf(buf, sizeof(buf), "line %d %s：%s(%d)", line, what, strerror(e), e);

    (void)out_fd;
    if (n > 0) { fprintf(stderr, "%s\n", buf); fflush(stderr); }
    _exit(127);
}

static int session_open(int out_fd, uint8_t sid, char *const argv[], int argc) {
  if (!argv || !argv[0]) { send_error(out_fd, "OPEN 没有可执行文件路径"); return -1; }
  int master = posix_openpt(O_RDWR | O_NOCTTY);
  if (master < 0) { int e0 = errno; send_error(out_fd, "posix_openpt 失败"); errno = e0; return -1; }
  if (grantpt(master) != 0 || unlockpt(master) != 0) {
    int e1 = errno;
    send_error(out_fd, "grantpt/unlockpt 失败");
    close(master);
    errno = e1;
    return -1;
  }

    static char g_pts_path[64];
    static int g_pts_next = 0;
    const char *sn = ptsname(master);
    if (!sn) {
        sn = 0;
        for (int tries = 0; tries < 4096; tries++) {
            int n = g_pts_next++;
            char cand[64];
            snprintf(cand, sizeof cand, "/dev/pts/%d", n);
            int probe = open(cand, O_RDWR | O_NOCTTY);
            if (probe >= 0) { close(probe); sn = cand; break; }
            if (errno != ENOENT && errno != EACCES) break;
        }
    }
    if (!sn) {
        close(master);
        child_fail(out_fd, 129, "取不到 PTY 从设备名（ptsname 与自数都失败）");
    }
    snprintf(g_pts_path, sizeof g_pts_path, "%s", sn);
    sn = g_pts_path;

  int diag_pipe[2] = { -1, -1 };
  if (pipe(diag_pipe) == 0) {

    g_diag_read_fd = diag_pipe[0];
  }

  pid_t pid = fork();
  if (pid < 0) {
    close(master);
    return -1;
  }
    if (pid == 0) {

        child_note("child: fork 已做");
        if (setsid() < 0) child_fail(out_fd, 141, "setsid 失败");
        child_note("child: setsid 已做");

        if (diag_pipe[0] >= 0) close(diag_pipe[0]);
        g_child_diag_fd = diag_pipe[1];

        if (g_child_diag_fd >= 0) dup2(g_child_diag_fd, 2);

        int ctl = fcntl(out_fd, F_DUPFD_CLOEXEC, 10);
        if (ctl < 0) ctl = out_fd;
        child_note("child: fcntl 控制口已留");
        int slave = open(sn, O_RDWR);
        if (slave < 0) child_fail(ctl, 145, "open 从设备失败");
        child_note("child: open 从设备已做");
        if (ioctl(slave, TIOCSCTTY, 0) < 0) child_fail(ctl, 146, "TIOCSCTTY 失败");
        child_note("child: TIOCSCTTY 已做");
        if (dup2(slave, 0) < 0 || dup2(slave, 1) < 0 || dup2(slave, 2) < 0) child_fail(ctl, 147, "dup2 到从设备失败");
        child_note("child: dup2 到从设备已做");
        if (slave > 2) close(slave);
        close(master);

        {

          for (;;) pause();
        }
        _exit(0);
        {
            char tb[512];
            int tn = snprintf(tb, sizeof tb,
                "child: exec 即将做 argv[0]=%s argv[1]=%s slave=%s argc=%d",
                argc > 0 ? argv[0] : "(无)", argc > 1 ? argv[1] : "(无)",
                sn, argc);
            if (tn > 0) child_note(tb);
        }
        signal(SIGINT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        child_note("child: signal 已做");

        execv(argv[0], argv);
        child_fail(ctl, 190, "execv 失败");
        close(out_fd);
    }

  g_sessions[sid].master = master;
  g_sessions[sid].pid = pid;
  g_sessions[sid].open = 1;

  if (g_sessions[sid].pending_winsize) {
    struct winsize pws;
    memcpy(&pws, &g_sessions[sid].pending_ws, sizeof(pws));
    g_sessions[sid].pending_winsize = 0;
    ioctl(g_sessions[sid].master, TIOCSWINSZ, &pws);
  }
  snprintf(g_sessions[sid].slave, sizeof(g_sessions[sid].slave), "%s", sn);
  return 0;
}

static void session_close(int sid) {
  if (sid >= MAX_SESSIONS || !g_sessions[sid].open) return;
  if (g_sessions[sid].pid > 0) kill(g_sessions[sid].pid, SIGHUP);
  close(g_sessions[sid].master);
  int st = 0;
  if (g_sessions[sid].pid > 0) {
    for (int i = 0; i < 50; i++) {
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

static int parse_argv(const char *blob, uint32_t len, char ***out) {
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
  signal(SIGPIPE, SIG_IGN);
  signal(SIGINT, on_signal);
  signal(SIGTERM, on_signal);

  int in_fd = STDIN_FILENO;
  int out_fd = STDOUT_FILENO;

  g_diag_fd = out_fd;

  note_raw("librivospty ready");

  while (!g_stop) {
    struct pollfd fds[1 + MAX_SESSIONS];

    int fds_sid[1 + MAX_SESSIONS];
    int nfds = 0;
    fds[nfds].fd = in_fd;
    fds[nfds].events = POLLIN;
    fds_sid[nfds] = -1;
    nfds++;

    int diag_slot = -1;
    if (g_diag_read_fd >= 0) {
      fds[nfds].fd = g_diag_read_fd;
      fds[nfds].events = POLLIN;
      fds_sid[nfds] = -2;
      diag_slot = nfds;
      nfds++;
    }
    for (int i = 0; i < MAX_SESSIONS; i++) {
      if (g_sessions[i].open) {
        fds[nfds].fd = g_sessions[i].master;
        fds[nfds].events = POLLIN;
        fds[nfds].revents = 0;
        fds_sid[nfds] = i;
        nfds++;
      }
    }

    int r = poll(fds, (nfds_t)nfds, 200);
    if (r < 0) {
      if (errno == EINTR) continue;
      diag_raw("poll 控制通道失败");
      break;
    }

    if (diag_slot >= 0 && (fds[diag_slot].revents & (POLLIN | POLLHUP | POLLERR))) {
      char db[512];
      ssize_t dn = read(g_diag_read_fd, db, sizeof(db));
      if (dn > 0) {

        fprintf(stderr, "%.*s", (int)dn, db);
        fflush(stderr);
      }
    }

    if (fds[0].revents & (POLLIN | POLLHUP)) {
      frame_hdr h;
      int got = read_full(in_fd, &h, sizeof(h));
      if (got == 0) { diag_raw("控制通道已关闭（父侧退出）"); break; }
      if (got < 0) {
        if (errno == EINTR) continue;
        diag_raw("读控制通道失败");
        break;
      }
      if (h.length > FRAME_MAX) {

        uint64_t left = (uint64_t)h.length;
        unsigned char sink[4096];
        while (left > 0) {
          size_t want = left < sizeof(sink) ? (size_t)left : sizeof(sink);
          ssize_t got = read(in_fd, sink, want);
          if (got < 0) { if (errno == EINTR) continue; break; }
          if (got == 0) break;
          left -= (uint64_t)got;
        }
        if (left > 0) {
          diag_raw("读帧体中断（payload 超上限）");
          break;
        }
        send_error(out_fd, "frame payload 超上限");
        continue;
      }
      char *payload = (char *)malloc(h.length + 1);
      if (!payload) { send_error(out_fd, "内存不足"); break; }
      if (h.length > 0 && read_full(in_fd, payload, h.length) <= 0) {
        free(payload);
        diag_raw("读帧体中断");
        break;
      }
      payload[h.length] = '\0';

      switch (h.kind) {
        case FRAME_OPEN: {

          int slot = (int) h.sid;
          if (slot < 0 || slot >= MAX_SESSIONS) {
            send_error(out_fd, "会话号越界（宿主侧只分 0..7 那一族）");
            break;
          }
          if (g_sessions[slot].open) {
            send_error(out_fd, "会话号已被占用");
            break;
          }

          char **argv = NULL;
          int argc = parse_argv(payload, h.length, &argv);
          if (argc <= 0 || !argv) { free(payload); send_error(out_fd, "OPEN 的 argv 为空"); break; }
          if (session_open(out_fd, (uint8_t)slot, argv, argc) != 0) {
            send_error(out_fd, strerror(errno));
            for (int i = 0; i < argc; i++) free(argv[i]);
            free(argv);
            break;
          }
          struct winsize ws;
          memset(&ws, 0, sizeof(ws));
          ioctl(g_sessions[slot].master, TIOCGWINSZ, &ws);
          uint8_t reply[sizeof(g_sessions[slot].slave) + 8];
          uint32_t n = (uint32_t)strlen(g_sessions[slot].slave) + 1;
          memcpy(reply, g_sessions[slot].slave, n);
          memcpy(reply + n, &ws, sizeof(ws));
          send_frame(out_fd, FRAME_READY, (uint8_t)slot, reply, n + (uint32_t)sizeof(ws));
          { char m[96];
            snprintf(m, sizeof m, "会话 %u 就绪 从设备=%s",
                     (unsigned)slot, g_sessions[slot].slave);
            note_raw(m); }
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
          if (s >= MAX_SESSIONS) { send_error(out_fd, "WINSIZE 指向不存在的会话"); break; }

          if (!g_sessions[s].open) {
            if (h.length >= sizeof(struct winsize)) {
              memcpy(&g_sessions[s].pending_ws, payload, sizeof(struct winsize));
              g_sessions[s].pending_winsize = 1;

              diag_raw("WINSIZE 早到，槽还没开 -> 记下来等它开");
            }
            break;
          }
          if (h.length >= sizeof(struct winsize)) {
            struct winsize ws;
            memcpy(&ws, payload, sizeof(ws));
            ioctl(g_sessions[s].master, TIOCSWINSZ, &ws);
          }
          break;
        }
        case FRAME_CLOSE:

          session_close(h.sid);
          {

            uint8_t zi[8]; memset(zi, 0, 8);
            send_frame(out_fd, FRAME_EXITED, (uint8_t)h.sid, zi, 8);
          }
          break;
        case FRAME_EXIT:
          free(payload);
          goto done;
        default:
          send_error(out_fd, "未知帧类型");
          break;
      }
      free(payload);
    }

    for (int i = 1; i < nfds; i++) {
      if (!(fds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
      int sid = fds_sid[i];
      if (sid < 0 || sid >= MAX_SESSIONS) continue;
      if (!g_sessions[sid].open) continue;
      static char buf[FRAME_MAX];
      ssize_t n = read(g_sessions[sid].master, buf, sizeof(buf));
      if (n > 0) {
        send_frame(out_fd, FRAME_DATA, (uint8_t)sid, buf, (uint32_t)n);
      } else if (n == 0) {
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
      } else if (errno == EIO) {
        uint8_t zi[8]; memset(zi, 0, 8); send_frame(out_fd, FRAME_EXITED, (uint8_t)sid, zi, 8);

        pid_t dead = g_sessions[sid].pid;
        int st2 = 0; if (dead > 0) waitpid(dead, &st2, WNOHANG);
        close(g_sessions[sid].master);
        memset(&g_sessions[sid], 0, sizeof(session));
      }
    }
  }

done:
  for (int i = 0; i < MAX_SESSIONS; i++) session_close(i);
  note_raw("librivospty bye");
  return 0;
}