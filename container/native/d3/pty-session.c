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
  int master;    
  pid_t pid;     
  int open;      
  char slave[128];
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

static int alloc_slot(void) {
  for (int i = 0; i < MAX_SESSIONS; i++) {
    if (!g_sessions[i].open) return i;
  }
  return -1;
}

static void child_fail(int out_fd, int line, const char *what) {
    char buf[160];
    int e = errno;
    int n = snprintf(buf, sizeof(buf), "line %d %s：%s(%d)", line, what, strerror(e), e);
    if (n > 0) send_frame(out_fd, FRAME_ERROR, 0, buf, (uint32_t)n);
    _exit(127);
}

static int session_open(int out_fd, uint8_t sid, char *const argv[]) {
  if (!argv || !argv[0]) return -1;
  int master = posix_openpt(O_RDWR | O_NOCTTY);
  if (master < 0) return -1;
  if (grantpt(master) != 0 || unlockpt(master) != 0) {
    close(master);
    return -1;
  }
    /* ptsname 需要 TIOCGPTN，而 untrusted_app 域没有那个 ioctl 的 allow。
       /dev/pts 的编号是内核单调分配的，从 0 开始，
       所以这里自己数：从 0 起试 open("/dev/pts/<n>")，第一个能打开的就是它。 */
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

  pid_t pid = fork();
  if (pid < 0) {
    close(master);
    return -1;
  }
    if (pid == 0) {
        if (setsid() < 0) child_fail(out_fd, 141, "setsid 失败");
        int ctl = fcntl(out_fd, F_DUPFD_CLOEXEC, 10);
        if (ctl < 0) ctl = out_fd;
        int slave = open(sn, O_RDWR);
        if (slave < 0) child_fail(ctl, 145, "open 从设备失败");
        if (ioctl(slave, TIOCSCTTY, 0) < 0) child_fail(ctl, 146, "TIOCSCTTY 失败");
        if (dup2(slave, 0) < 0 || dup2(slave, 1) < 0 || dup2(slave, 2) < 0) child_fail(ctl, 147, "dup2 到从设备失败");
        if (slave > 2) close(slave);
        close(master);
        signal(SIGINT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        execv(argv[0], argv);
        child_fail(ctl, 155, "execv 失败");
        close(out_fd);
    }

  g_sessions[sid].master = master;
  g_sessions[sid].pid = pid;
  g_sessions[sid].open = 1;
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

    if (fds[0].revents & (POLLIN | POLLHUP)) {
      frame_hdr h;
      int got = read_full(in_fd, &h, sizeof(h));
      if (got == 0) break;                  
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
          if (session_open(out_fd, (uint8_t)slot, argv) != 0) {
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

    for (int i = 1; i < nfds; i++) {
      if (!(fds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
      int sid = i - 1;
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