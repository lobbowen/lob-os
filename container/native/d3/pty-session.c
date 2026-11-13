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
  //  待发的那一帧 WINSIZE：`pending_winsize` 记着有没有、`pending_ws` 存尺寸。
  //    判据是帧发的时候 native 的槽开没开；槽开了就用它。
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

/*  PIPE_BUF 是 POSIX 保证原子的写上限（Linux 是 4096）。
    写小于等于它就不会被别的写插进去。 */
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

/*  缺陷名（C-102）：大帧一次写完不原子，子进程的帧会插进来。
    旧版 `write_full(fd, buf, total)` 一次写完；write() 大于 PIPE_BUF 时不原子。
    子进程也往同一个 stdout 写帧（note_raw），那六条「child: ...」就是帧通道上的六帧。
    后果：帧被插进大帧中间，流错位；下一帧的 sid 取自帧体，可能大于 MAX_SESSIONS，
    FRAME_WINSIZE 判「WINSIZE 指向不存在的会话」；第一趟偶发，后面几趟 OK。
    判据：帧写出去的时候写是不是原子的。
    处置：帧分块写，每块小于等于 PIPE_BUF，上限 FRAME_MAX 不变（读侧按帧读，看不出分块）。 */
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
  //  缺陷名（C-102）：帧拆成两次写，头与体能交错。
  //    旧版 `write_full(&h)` + `write_full(payload)`；读侧按字节读，长度能从错位的字节拼出来。
  //    后果：读侧 `ByteArray(len)` 没有上限，分配量 117031556 字节，OOM 第 2 趟就来。
  //    判据：那一帧是不是一个整的。
  //    处置：一帧一次写（头与体连着），读侧按长度一次取，另有上限 `MAX_FRAME_BYTES`（PtySession.kt）。
  //    上限 64 KiB（`FRAME_MAX`），帧通道自己不发更大的。
  if (len > FRAME_MAX) return -1;
  size_t total = sizeof(h) + (len > 0 ? (size_t)len : 0);
  unsigned char *buf = (unsigned char *)malloc(total);
  if (!buf) return -1;
  memcpy(buf, &h, sizeof(h));
  if (len > 0) memcpy(buf + sizeof(h), payload, len);
  //  分块写保证每次写原子（PIPE_BUF 那一族）。
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

/*  缺陷名（C-102）：子进程也往帧通道写帧，流会错位。
    旧版 `child_note(...)` 走 diag_to，diag_to 走 `send_frame(g_diag_fd, ...)` 写帧通道。
    子进程与 native 各写同一个 stdout，没有锁；大帧一次写完，F_DATA 能到 FRAME_MAX = 64 KiB。
    write() 大于 PIPE_BUF 时不原子，帧被插进大帧中间，流错位，sid 可能大于 MAX_SESSIONS。
    判据：帧通道是不是只有一个写者。
    处置：子进程诊断只往 stderr 写，不碰帧通道；信息不丢（宿主 diagReader 读 stderr）。 */
  /*  缺陷名（C-102）：子进程往 stderr 写，而 stderr 还是帧通道。
      旧版 `fprintf(stderr, "%s\n", text); fflush(stderr);`。
      真因：子进程的 `dup2(slave, 1)` 与 `dup2(slave, 2)` 在那六条之后，前几条的 stderr 还是继承的，
      继承的就是帧通道。
      判据：子进程诊断写出去的时候，那个通道是不是帧通道。
      处置：子进程诊断走专用管道，读侧是 native 宿主；帧通道只有宿主一个写者，信息不丢。 */
static int g_child_diag_fd = -1;
/* 读端（native 宿主那一边）。 */
static int g_diag_read_fd = -1;

static void child_note(const char *text) {
  /* 专用管道没开时退回 stderr（只是临时的）。 */
  int fd = g_child_diag_fd;
  if (fd < 0) fd = 2;
  char buf[256];
  int n = snprintf(buf, sizeof buf, "%s\n", text);
  if (n > 0) { ssize_t w = write(fd, buf, (size_t)n); (void)w; }
}

//  缺陷名（C-102）：子进程自己分槽，跟宿主侧分的号会错开，已删掉。
//   槽号由帧带来的号决定、由宿主侧分，号必须在 0..MAX_SESSIONS 一族里。

static void child_fail(int out_fd, int line, const char *what) {
    char buf[160];
    int e = errno;
    int n = snprintf(buf, sizeof(buf), "line %d %s：%s(%d)", line, what, strerror(e), e);
    /* 子进程不往帧通道写（帧通道只有 native 一个写者）。
       旧版是 `send_frame(out_fd, FRAME_ERROR, 0, buf, (uint32_t)n);` */
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
    /* ptsname 需要 TIOCGPTN，而 untrusted_app 域没有那个 ioctl 的 allow。
       /dev/pts 的编号由内核从 0 起单调分配，所以这里从 0 起试 open("/dev/pts/<n>")。 */
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

  //  缺陷名（C-102）：native 自己 fork + exec，在那个域里跑不起来。
  //   事实（ExecEntry.kt:115-122）：四份入口在设备上是 u:object_r:app_data_file:s0，
  //     而 app 域是 u:r:untrusted_app，对 app_data_file 没有执行许可（exec 在加载解释器前就 EACCES，error=13）；
  //     /system/bin/linker64 是 u:object_r:system_linker_exec:s0，那一域有执行许可。
  //   子进程从 native 宿主 fork 出来并继承该域，app_data_file 仍没有执行许可。
  //   后果：子进程在 execv 那一步就死了，fork/setsid/open/TIOCSCTTY/dup2 那几条留痕一条都没到；
  //     阳性对照：那九件都跑起来了，ProcessSupervisor 那一族能跑。
  //   处置：执行那一格不归 native（native 只做 PTY 层：建从设备、报给宿主侧），
  //     归产品侧 ProcessSupervisor，从设备由宿主侧转交；执行由宿主侧再发一帧回来。
  //   argv 仍是 ExecEntry.argv 给的那一个，形态不变。
  //   子进程诊断走专用管道，不碰帧通道：管道在 fork 之前开，子进程拿写端、宿主拿读端。
  int diag_pipe[2] = { -1, -1 };
  if (pipe(diag_pipe) == 0) {
    /* 子进程继承写端，读端要关掉。 */
    g_diag_read_fd = diag_pipe[0];
  }

  pid_t pid = fork();
  if (pid < 0) {
    close(master);
    return -1;
  }
    if (pid == 0) {
        // 逐步留痕：崩溃在哪一步之后一目了然。
        // 走 child_note（只往 stderr 写）；帧通道只有 native 宿主一个写者，插进去就错位了。
        child_note("child: fork 已做");
        if (setsid() < 0) child_fail(out_fd, 141, "setsid 失败");
        child_note("child: setsid 已做");
        /* 子进程诊断走专用管道，不碰帧通道。
           真因：子进程的 stderr 在 `dup2` 之前还是继承的，继承的就是帧通道。 */
        if (diag_pipe[0] >= 0) close(diag_pipe[0]);
        g_child_diag_fd = diag_pipe[1];
        /* 子进程的 stderr 立刻改成专用管道，前几条留痕也就安全了。 */
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
        //  执行那一格不在这一族里（是那个域缺陷的处置）。
        //    宿主侧从那 READY 帧（回的是从设备名与窗口大小）已拿到从设备名，由 Kotlin 那一族自己去执行 argv
        //    （它在 app 那个域，已经能跑那九件）。
        //    这个子进程只把三路接在从设备上，等从设备关为止。
        //
        //  缺陷名（C-102）：子进程读帧通道，把那些帧吃掉了。
        //    旧版 `while (read(ctl, buf, sizeof buf) > 0) { ... }`，ctl 是 out_fd 的副本（STDOUT = 帧通道），
        //    子进程读它就把帧抢走，父侧收不到。
        //    后果：那几条留痕一条都没到，父侧只收到自己发的「会话 0 就绪」。
        //    处置：子进程不读帧通道，只把从设备开着等它关；帧通道只归父侧。
        //    控制终端还在这子进程身上（setsid + TIOCSCTTY 做的），产品侧起的进程继承不了，另说。
        {
          //    只握住从设备、不读任何东西：读 master 就把数据抢走了（父侧收不到输出）。
          //    信号是父侧关 master；用 pause 等，不占 CPU、不抢数据。
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
        // ET_DYN 要经 linker64 驱动，唯一真相源是宿主侧 ExecEntry.argv（它读文件头定形态）。
        // 原来这里也做一次替换：判据问 argv[0] 有没有 "/files/opt/"，而那时 argv[0] 已是 linker64 自己
        // （宿主先替过），under 恒 0：同一件事两套逻辑，其中一套在目标形态上失效。按 C-87 删掉。
        //
        //  缺陷名（C-102）：native 自己去 exec，在那个域里跑不起来。
        //   事实（ExecEntry.kt:115-122）：四份入口在设备上是 u:object_r:app_data_file:s0，
        //     app 域是 u:r:untrusted_app，对 app_data_file 没有执行许可（exec 在加载解释器前就 EACCES，error=13）；
        //     /system/bin/linker64 是 u:object_r:system_linker_exec:s0，那一域有执行许可。
        //   子进程从 native 宿主 fork 出来并继承该域，app_data_file 仍没有执行许可。
        //   后果：子进程在 execv 那一步就死了，那几条留痕一条都没到；
        //     阳性对照：那九件都跑起来了，ProcessSupervisor 那一族能跑。
        //   处置：执行那一格不归 native（native 只做 PTY 层），归产品侧 ProcessSupervisor，
        //     从设备由宿主侧转交；宿主侧拿到从设备之后自己去 exec。
        //   argv 仍是 ExecEntry.argv 给的那一个，形态不变。
        execv(argv[0], argv);
        child_fail(ctl, 190, "execv 失败");
        close(out_fd);
    }

  g_sessions[sid].master = master;
  g_sessions[sid].pid = pid;
  g_sessions[sid].open = 1;
  //  缺陷名（C-102）：宿主侧早发的 WINSIZE 白发了，那一档尺寸被丢掉。
  //    旧版没有这一格：早发的帧被 `!g_sessions[s].open` 判成「WINSIZE 指向不存在的会话」，
  //    断掉，尺寸没落上去。
  //    后果：diag.jsonl 里 stage="pty" 两条 FAIL「PTY 的C 侧报错 | WINSIZE 指向不存在的会话」
  //    （run_id 1793604597308 与 1793604876414）。
  //    正解让 native 自己答：open 是它自己的本地状态，宿主侧看不到，F_READY 也不是它的回执。
  //    处置：早到的帧先记下来，槽开了再用（open = 1 在这里，补在后面）。
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
    //  缺陷名（C-102）：按数组下标反推会话号，会话关掉之后会错位。
    //    旧版 `fds[nfds].fd = g_sessions[i].master;`（nfds 只数 open 的），读侧 `int sid = i - 1;`。
    //    数组下标不等于会话槽号：关掉的槽会让下面前移。
    //    后果：exec 那一趟用槽 0 跑完退了，终端那一趟用槽 1，下标前移后 sid 算错，
    //    终端输出发到槽 0，stdout 没回来（设备侧「stdout 空、退出码 0、命令没有进程」）。
    //    处置：槽号跟着 fd 一起记（fds_sid），sid 不再是数组下标减一。
    int fds_sid[1 + MAX_SESSIONS];
    int nfds = 0;
    fds[nfds].fd = in_fd;
    fds[nfds].events = POLLIN;
    fds_sid[nfds] = -1;
    nfds++;
    //  子进程诊断走专用管道进 poll：读端是 native 宿主，子进程不碰帧通道，信息不丢。
    int diag_slot = -1;
    if (g_diag_read_fd >= 0) {
      fds[nfds].fd = g_diag_read_fd;
      fds[nfds].events = POLLIN;
      fds_sid[nfds] = -2;               /* **诊断**那**一格****标**出来 */
      diag_slot = nfds;
      nfds++;
    }
    for (int i = 0; i < MAX_SESSIONS; i++) {
      if (g_sessions[i].open) {
        fds[nfds].fd = g_sessions[i].master;
        fds[nfds].events = POLLIN;
        fds[nfds].revents = 0;
        fds_sid[nfds] = i;          /* 那一格是那**真的**会话槽号 */
        nfds++;
      }
    }

    int r = poll(fds, (nfds_t)nfds, 200);
    if (r < 0) {
      if (errno == EINTR) continue;
      diag_raw("poll 控制通道失败");
      break;
    }

    //  子进程诊断从专用管道读（读端就是这宿主）：信息不丢，帧通道不被子进程碰，流不会错位。
    if (diag_slot >= 0 && (fds[diag_slot].revents & (POLLIN | POLLHUP | POLLERR))) {
      char db[512];
      ssize_t dn = read(g_diag_read_fd, db, sizeof(db));
      if (dn > 0) {
        // 子进程留痕照旧进诊断那一族（它只往 stderr 写）。
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
        //  缺陷名（C-102）：超上限的帧被丢掉，体没被吃掉，流就错位了。
        //    旧版 `if (h.length > FRAME_MAX) { send_error(out_fd, "frame payload 超上限"); continue; }`：
        //    体还在管道里，continue 直接读下一帧，读到的是上一帧剩的体。
        //    后果：下一帧的 sid 取自体里的某个字节，可能大于 MAX_SESSIONS，
        //    FRAME_WINSIZE 判「WINSIZE 指向不存在的会话」，时刻对不上、症状偶发。
        //    判据：帧通道是不是还同步着。
        //    处置：把体吃掉，流不错位；吃不掉就断掉（流已经没救了）。上限还是 64 KiB，宿主侧要守同一个上限。
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
          //  缺陷名（C-102）：native 自己分槽，跟宿主侧分的号会错开。
          //    旧版 `int slot = alloc_slot();` 把帧带来的 sid 丢掉了；FRAME_READY 回的是真的槽号，
          //    宿主侧 onReady 按真槽号建 Session，而 latch 挂在宿主侧自己分的号上，可能不一致，latch 永远等不到。
          //    处置：槽用帧带来的那个号（宿主侧 allocSid() 从 0 往上找，最多 8 个）；
          //    号已经在用时报缺（按 C-05 不猜填）。
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
          //  缺陷名（C-102）：早到的帧被当成错丢掉。
          //    旧版 `if (s >= MAX_SESSIONS || !g_sessions[s].open) { send_error(...); break; }`，
          //    宿主侧早发的那一帧被判成错、报一条 FAIL。
          //    正解让 native 自己答：open 是它自己的本地状态，宿主侧看不到。
          //    判据：帧发的时候 native 的槽开没开。处置：槽没开先记下，开了再用；已开的立刻用。
          if (!g_sessions[s].open) {
            if (h.length >= sizeof(struct winsize)) {
              memcpy(&g_sessions[s].pending_ws, payload, sizeof(struct winsize));
              g_sessions[s].pending_winsize = 1;
              /* 早到不是错：记下来，等槽开了再用。 */
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
          //  缺陷名（C-102）：宿主侧立刻还号，而 native 的槽还没空。
          //    旧版 `case FRAME_CLOSE: session_close(h.sid); break;`，native 不回任何帧，
          //    宿主侧发完 F_CLOSE 就 releaseSid(sid)。下一趟拿到同一个号、槽还开着（open = 1），
          //    session_open 报「会话号已被占用」；槽还开着时 F_WINSIZE 落到旧会话上、报「WINSIZE 指向不存在的会话」。
          //    session_close 等子进程死最多 500 毫秒，这个等待在 native 里面、宿主侧不知道。
          //    判据：号还回去的时候 native 的槽空没空。
          //    处置：native 关完回一帧，宿主侧等到那一帧才还号；槽真空了，下一趟拿到那号才能开。
          session_close(h.sid);
          {
            // 回帧带那号、体空。
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
      int sid = fds_sid[i];        /* 那一格是那**真的**会话槽号（ 不是那数组下标减一） */
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
      } else if (errno == EIO) { /* 对端已退出：PTY master 读不到数据，不是错误 */
        uint8_t zi[8]; memset(zi, 0, 8); send_frame(out_fd, FRAME_EXITED, (uint8_t)sid, zi, 8);
        /* 对端已退出 —— 不能走 session_close（它会 kill 一个可能已被回收复用的 pid） */
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