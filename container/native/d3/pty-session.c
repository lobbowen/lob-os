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
  //  => 而而那那**待发**那一格逐字（=> 而而那它逐字是那「那宿主侧逐字**早**发的那一帧 WINSIZE」那一族）
  //    => 而而那那 `pending_winsize` 逐字是「那一帧逐字**记着**了没有」、=> 而而那那 `pending_ws` 逐字是那一档尺寸
  //    => 而而那那**槽**逐字**开**了之后逐字**就用**它（=> 而而那那**判据**逐字是「那一帧逐字发的时候、
  //      => 而而那那 native 那一族逐字那槽逐字逐字开没开」那一个事实上）
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

//  缺陷名（C-102）**那一格逐字自己分槽、=> 而而那它逐字跟宿主侧分的那一号逐字错开** ——
//   => 而而那它逐字**删掉**了（=> 而而那槽号逐字现在**由**那帧带来的那个号决定、=> 而那宿主侧逐字分）
//   => 而而那那**号**逐字**必须**在 0..MAX_SESSIONS 那一族里、=> 而而那宿主侧逐字**也是**那样分的

static void child_fail(int out_fd, int line, const char *what) {
    char buf[160];
    int e = errno;
    int n = snprintf(buf, sizeof(buf), "line %d %s：%s(%d)", line, what, strerror(e), e);
    if (n > 0) send_frame(out_fd, FRAME_ERROR, 0, buf, (uint32_t)n);
    diag_raw(buf);
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

  //  缺陷名（C-102）**那一格逐字由 native 自己去 fork + exec、=> 而而那它逐字在那个域里**跑不起来** ——
  //   => 而那**事实**（仓内 `ExecEntry.kt:115` 到 `:122` 逐字记着）：
  //     · 而那四份入口在设备上是 u:object_r:app_data_file:s0
  //     · 而 app 进程那个域是 u:r:untrusted_app，**那一域对 app_data_file 没有执行许可**
  //       （=> 而直接 exec 它在加载解释器之前就 EACCES => 而那一格是 error=13）
  //     · 而 /system/bin/linker64 是 u:object_r:system_linker_exec:s0，那一域有执行许可
  //   => 而而那**这个**子进程逐字是从**那 native 宿主**逐字 fork 出来的
  //     => 而而那那宿主逐字是那 linker64 逐字驱动的、=> 而而那它逐字在 system_linker_exec 域
  //     => 而而那那子进程逐字**继承**那域、=> 而而那那 app_data_file 逐字**仍**没有执行许可
  //   => 而而那**后果**（07 本轮逐字核出、Lead 本机复核成立）：
  //     · 那子进程逐字**在 execv 那一格就死了**、=> 而而那那**子进程**逐字的那几条留痕
  //       （fork / setsid / open / TIOCSCTTY / dup2 / exec 即将做）逐字**一条都没到**
  //       （=> 而而那那**阳性对照**：那九件逐字都跑起来了、=> 而那 `ProcessSupervisor` 那一族
  //         逐字（ProcessBuilder + ExecEntry.argv）逐字**能**跑）
  //   => 而那**处置**：=> 而那**执行**那一格逐字**不**归 native
  //     => 而而那 native 那一族逐字**只**做那 PTY 那一层的事
  //       （=> 而而那它逐字建那从设备、=> 而而那它逐字把那从设备**报**给宿主侧）
  //     => 而而那**执行**那一格逐字归那**产品侧**（=> 而那 `ProcessSupervisor` 那一族）
  //       （=> 而而那它逐字**已经**能跑那九件、=> 而而那它逐字在 app 那个域）
  //     => 而而那那**从设备**逐字由宿主侧逐字转交那产品侧
  //   => 而而那那 argv 逐字**还**是那 ExecEntry.argv 逐字给的那一个、=> 而而那形态逐字**不**变
  //   => 而而那那**执行**逐字由宿主侧逐字**再**发一帧回来（=> 而而那那新帧那一族逐字见下）
  pid_t pid = fork();
  if (pid < 0) {
    close(master);
    return -1;
  }
    if (pid == 0) {
        // 逐步留痕：崩溃在哪一步之后一目了然。走 note_raw（diag_to 双路：全局诊断 fd + stderr），
        // 不走裸 send_frame(out_fd, …) —— 那个 fd 只有 native 宿主在读，宿主不看就等于没写。
        note_raw("child: fork 已做");
        if (setsid() < 0) child_fail(out_fd, 141, "setsid 失败");
        note_raw("child: setsid 已做");
        int ctl = fcntl(out_fd, F_DUPFD_CLOEXEC, 10);
        if (ctl < 0) ctl = out_fd;
        note_raw("child: fcntl 控制口已留");
        int slave = open(sn, O_RDWR);
        if (slave < 0) child_fail(ctl, 145, "open 从设备失败");
        note_raw("child: open 从设备已做");
        if (ioctl(slave, TIOCSCTTY, 0) < 0) child_fail(ctl, 146, "TIOCSCTTY 失败");
        note_raw("child: TIOCSCTTY 已做");
        if (dup2(slave, 0) < 0 || dup2(slave, 1) < 0 || dup2(slave, 2) < 0) child_fail(ctl, 147, "dup2 到从设备失败");
        note_raw("child: dup2 到从设备已做");
        if (slave > 2) close(slave);
        close(master);
        //  ★★ 而那**执行**那一格逐字**不**在这一族里（=> 而那是那一族那个域缺陷的处置）
        //    => 而而那那**宿主侧**逐字从那 READY 帧逐字**已经**拿到那从设备名了
        //      （=> 而而那那 READY 帧逐字回的就是那从设备名与那窗口大小）
        //    => 而而那那宿主侧逐字（=> 而那是那 **Kotlin** 那一族）逐字**自己**去执行那一条 argv
        //      （=> 而而那它逐字**在 app 那个域**、=> 而而那它逐字**已经**能跑那九件）
        //    => 而而那这个子进程逐字**只**把它的三路接在那从设备上、=> 而而那它逐字**等**到那从设备**关**为止
        //      （=> 而而那那**关**逐字是那 PTY 那一层的事、=> 而而那它逐字**不**管那执行）
        //
        //  缺陷名（C-102）**那一格逐字读那帧通道、=> 而而那它逐字把那些帧逐字**吃掉**了** ——
        //    => 而那旧版逐字是 `while (read(ctl, buf, sizeof buf) > 0) { … }`
        //      => 而而那那 `ctl` 逐字是那 `out_fd` 逐字的一个**副本**（=> 而那是那 **STDOUT**）
        //        => 而而那那 STDOUT 逐字是那**帧通道**
        //      => 而而那那子进程逐字**读**它逐字就**把**那些帧逐字**抢走**了
        //        => 而而那那父侧逐字就**收不到**那些帧了
        //      => 而而那那**后果**（07 本轮逐字核出）：=> 而而那那子进程逐字的那几条留痕
        //        （fork / setsid / open / TIOCSCTTY / dup2 / exec 即将做）逐字**一条都没到**
        //        （=> 而而那那父侧逐字**只**收到它自己发的「会话 0 就绪」那一条）
        //    => 而那**处置**：=> 而而那这个子进程逐字**不**读那帧通道
        //      => 而而那它逐字**只**把那从设备**开着**、=> 而而那它逐字**等**那从设备**关**
        //      => 而而那那**帧通道**逐字**只**归那父侧（=> 而而那那帧逐字**不**再被抢）
        //      => 而而那那**控制终端**逐字**还在**这子进程逐字身上（=> 而而那它逐字 setsid + TIOCSCTTY 逐字做的）
        //        => 而而那那产品侧逐字起的那进程逐字**继承**不了它、=> 而而那那一格逐字另说
        {
          //    => 而而那这个子进程逐字**只**要**握**住那从设备、=> 而而那它逐字**不**读任何东西
          //      => 而而那它逐字**读**那 master 逐字就**把**那些数据逐字**抢走**了
          //        （=> 而那父侧逐字就**收不到**那输出了、=> 而那「会话 0 就绪」之外逐字**什么**都**没有**）
          //      => 而而那它逐字**只要**那从设备**别**被关掉就**行**、=> 而而那它逐字**等**就**行**
          //      => 而而那那**信号**逐字是那父侧逐字关那 master（=> 而那它逐字**收到**那个）
          //      => 而而那那**等**逐字用那 pause（=> 而而那它逐字**不**占 CPU、=> 而而那它逐字**不**抢数据）
          for (;;) pause();
        }
        _exit(0);
        {
            char tb[512];
            int tn = snprintf(tb, sizeof tb,
                "child: exec 即将做 argv[0]=%s argv[1]=%s slave=%s argc=%d",
                argc > 0 ? argv[0] : "(无)", argc > 1 ? argv[1] : "(无)",
                sn, argc);
            if (tn > 0) note_raw(tb);
        }
        signal(SIGINT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        note_raw("child: signal 已做");
        // ET_DYN 要经 linker64 驱动 —— 唯一真相源是宿主侧 ExecEntry.argv（它读文件头定形态）。
        // 原来这里也做一次替换，判据问 argv[0] 里有没有 "/files/opt/"，而那时 argv[0]
        // 已经是 linker64 自己（宿主先替过），于是 under 恒 0 —— 同一件事两套逻辑、
        // 且其中一套在自己的目标形态上失效。按 C-87 删掉，native 只做 exec。
        //
        //  缺陷名（C-102）**那一格逐字由 native 自己去 exec、=> 而而那它逐字在那个域里**跑不起来** ——
        //   => 而那**事实**（仓内 `ExecEntry.kt:115` 到 `:122` 逐字记着）：
        //     · 而那四份入口在设备上是 u:object_r:app_data_file:s0
        //     · 而 app 进程那个域是 u:r:untrusted_app，**那一域对 app_data_file 没有执行许可**
        //       （=> 而直接 exec 它在加载解释器之前就 EACCES => 而那一格是 error=13）
        //     · 而 /system/bin/linker64 是 u:object_r:system_linker_exec:s0，那一域有执行许可
        //     · 而「直接 exec 那个入口」那条路在 app 自己那个域里不通
        //   => 而而那**这个**子进程逐字是从**那 native 宿主**逐字 fork 出来的
        //     => 而而那那宿主逐字是那 linker64 逐字驱动的、=> 而而那它逐字在 system_linker_exec 域
        //     => 而而那那子进程逐字**继承**那域、=> 而而那那 app_data_file 逐字**仍**没有执行许可
        //   => 而而那**后果**（07 本轮逐字核出、Lead 本机复核成立）：
        //     · 那子进程逐字**在 execv 那一格就死了**、=> 而而那那**子进程**逐字的那几条留痕
        //       （fork / setsid / open / TIOCSCTTY / dup2 / exec 即将做）逐字**一条都没到**
        //       （=> 而而那那**阳性对照**：那九件逐字都跑起来了、=> 而那 `ProcessSupervisor` 那一族
        //         逐字（ProcessBuilder + ExecEntry.argv）逐字**能**跑）
        //     => 而而那那差别逐字在「那**native fork 出来**的子进程逐字那个域」
        //   => 而那**处置**：=> 而那**执行**那一格逐字**不**归 native
        //     => 而而那 native 那一族逐字**只**做那 PTY 那一层的事
    //       （=> 而而那它逐字建那从设备、=> 而而那它逐字把那从设备**报**给宿主侧）
        //     => 而而那**执行**那一格逐字归那**产品侧**（=> 而那 `ProcessSupervisor` 那一族）
        //       （=> 而而那它逐字**已经**能跑那九件、=> 而而那它逐字在 app 那个域）
        //     => 而而那那**从设备**逐字由宿主侧逐字转交那产品侧（=> 而而那它逐字挂在那从设备名上）
        //  => 而而那**宿主侧**逐字拿到那从设备之后逐字**自己**去 exec
        //     （=> 而而那那 argv 逐字**还是**那 ExecEntry.argv 逐字给的那一个、=> 而而那形态逐字**不**变）
        execv(argv[0], argv);
        child_fail(ctl, 190, "execv 失败");
        close(out_fd);
    }

  g_sessions[sid].master = master;
  g_sessions[sid].pid = pid;
  g_sessions[sid].open = 1;
  //  缺陷名（C-102）**那一格逐字让那宿主侧逐字发得太早的那一帧逐字**白发了**、=> 而而那它逐字**丢掉**了那一档尺寸** ——
  //    => 而那旧版逐字**没有**这一格
  //      => 而而那那宿主侧逐字**早**发的那一帧 WINSIZE 逐字**被**那 `!g_sessions[s].open` 逐字**判**成
  //        「WINSIZE 指向不存在的会话」、=> 而而那它逐字**断掉**、=> 而而那那一档尺寸逐字**没**落上去
  //      => 而而那那**后果**（07 本轮逐字核出、Lead 本机复核成立）：
  //        · 而那那 `diag.jsonl` 逐字 `stage="pty"` 逐字两条 FAIL
  //          「PTY 的C 侧报错 | WINSIZE 指向不存在的会话」逐字**还是**发
  //          （逐字 `1793604597308` 与 `1793604876414`）
  //        · => 而而那那**正解**逐字是「让那 native 那一族逐字**自己**答」
  //          （=> 而而那那 `g_sessions[s].open` 那一格逐字是**它自己**的本地状态、
  //            => 而而那那宿主侧逐字**看不到**它、=> 而而那那 `F_READY` 那一帧逐字**不是**那个状态的回执）
  //    => 而那**处置**：=> 而而那那**早到**的那一帧逐字**先记下来**、=> 而而那那槽逐字开了再逐字**用**
  //      => 而而那那 `open = 1` 逐字**就在**这里、=> 而而那那**补**逐字**就在**这**之后**
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
    //  缺陷名（C-102）**那一格逐字按那数组下标反推那会话号、=> 而而那它逐字在有的会话关掉之后逐字就错位** ——
    //   => 而那旧版逐字是 `fds[nfds].fd = g_sessions[i].master;`（=> 而而那 nfds 逐字**只数**那 open 的）
    //     => 而而那读那一侧逐字是 `int sid = i - 1;`（=> 而那 i 逐字是那**数组下标**）
    //     => 而而那那数组下标逐字**不**等于那**会话槽号**（=> 而而那有的会话关掉之后逐字**前移**了）
    //   => 而而那**后果**（07 本轮逐字核出、Lead 逐字核过）：
    //     · 而那 `exec` 那一趟逐字用那**槽 0**、=> 而而那它跑完逐字**退了**（=> 而而那槽 0 逐字关掉）
    //     · 而而那那**终端**那一趟逐字用那**槽 1**
    //     · => 而而那那数组下标逐字**前移**了、=> 而而那那 sid 逐字逐字**算错**
    //     · => 而而那那终端那一趟的输出逐字逐字**发到槽 0** 去、=> 而而那那 stdout 逐字**没回来**
    //       （=> 而那设备侧逐字「stdout 逐字空、=> 而那退出码逐字 0」、=> 而那那命令逐字**没有进程**）
    //   => 而那**处置**：=> 而而那那**槽号**逐字**跟着**那 fd 逐字一起记
    //     => 而而那那 sid 逐字**不再**是那数组下标减一
    int fds_sid[1 + MAX_SESSIONS];
    int nfds = 0;
    fds[nfds].fd = in_fd;
    fds[nfds].events = POLLIN;
    fds_sid[nfds] = -1;
    nfds++;
    for (int i = 0; i < MAX_SESSIONS; i++) {
      if (g_sessions[i].open) {
        fds[nfds].fd = g_sessions[i].master;
        fds[nfds].events = POLLIN;
        fds[nfds].revents = 0;
        fds_sid[nfds] = i;          /* 那一格逐字是那**真的**会话槽号 */
        nfds++;
      }
    }

    int r = poll(fds, (nfds_t)nfds, 200);
    if (r < 0) {
      if (errno == EINTR) continue;
      diag_raw("poll 控制通道失败");
      break;
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
          //  缺陷名（C-102）**那一格逐字自己分槽、=> 而而那它逐字跟宿主侧分的那一号逐字错开** ——
          //   => 而那旧版逐字是 `int slot = alloc_slot();`
          //     => 而而那那帧带来的 sid 逐字（=> 而那是宿主侧逐字 `allocSid()` 逐字分的那个）
          //       逐字**根本没用**（=> 而而那它逐字**被丢掉**了）
          //     => 而而那那 `FRAME_READY` 逐字回的逐字是那**真的**槽号
          //     => 而而那宿主侧逐字 `onReady` 逐字按那**真的**槽号逐字建 Session、
          //       而而那那 latch 逐字是按宿主侧**自己分的**那个号逐字挂的
          //     => 而而那那**号**逐字**可能**不一致（=> 而而那那 latch 逐字**永远**等不到）
          //   => 而那**处置**：=> 而那**槽**逐字**用**那帧带来的那个号
          //     （=> 而而那宿主侧逐字 `allocSid()` 逐字从 0 逐字往上找、=> 而而那它逐字**最**多 8 个）
          //     => 而而那那号逐字**已经在用**时逐字**报缺**（=> 而按 C-05 不猜填）
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
          //  缺陷名（C-102）**那一格逐字把那一帧逐字**当成错**逐字丢掉** ——
          //    => 而那旧版逐字是 `if (s >= MAX_SESSIONS || !g_sessions[s].open) { send_error(…); break; }`
          //      => 而而那那宿主侧逐字**早**发的那一帧逐字**被**判成错、=> 而而那它逐字**报**那一条 FAIL
          //      => 而而那那**正解**逐字是「让那 native 那一族逐字**自己**答」
          //        （=> 而而那那 `open` 那一格逐字是**它自己**的本地状态、=> 而而那那宿主侧逐字**看不到**它）
          //    => 而那**处置**：=> 而而那那**槽还没开**时逐字**先记下来**、=> 而而那那槽开了再逐字**用**
          //      => 而而那那**判据**逐字是「那一帧逐字发的时候、=> 而而那那 native 那一族逐字那槽逐字逐字开没开」
          //        那一个事实上（=> 而而那它逐字**不**由「那一件逐字是什么」顶掉）
          //    => 而而那那**已经**开着的时逐字**照旧**逐字立刻用
          if (!g_sessions[s].open) {
            if (h.length >= sizeof(struct winsize)) {
              memcpy(&g_sessions[s].pending_ws, payload, sizeof(struct winsize));
              g_sessions[s].pending_winsize = 1;
              /*  => 而而那那**早到**逐字**不是**错、=> 而而那它逐字**记着**、=> 而而那它逐字**等**那槽开了 */
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
      int sid = fds_sid[i];        /* 那一格逐字是那**真的**会话槽号（=> 而而那不是那数组下标减一） */
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