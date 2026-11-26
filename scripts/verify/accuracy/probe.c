/*
 * LobOS accuracy probe. One binary, one dimension per argv[1].
 * Output is deterministic KEY=VALUE lines to stdout; exits 0 after reporting,
 * so the harness compares stdout/stderr/exit/signal as the accuracy tuple.
 * Built for aarch64 Linux (glibc) and run both under a reference Linux and
 * inside LobOS through its exec hook.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <sched.h>

static void emit(const char *k, const char *v) {
    printf("%s=%s\n", k, v ? v : "(null)");
}
static void emit_i(const char *k, long v) {
    printf("%s=%ld\n", k, v);
}

/* argv0: what did the kernel hand us as argv[0]? */
static void dim_argv0(void) {
    emit("argv0", "(unset)");
    /* Re-exec self via execve with a controlled argv[0] and a minimal envp to
       observe whether argv0/envp survive exec exactly. */
    char path[4096];
    ssize_t n = readlink("/proc/self/exe", path, sizeof(path)-1);
    if (n <= 0) { emit("exe_link", "ERR"); return; }
    path[n] = 0;
    emit("exe_link", strstr(path, "probe") ? "probe" : path);
    char *argv[] = { "SENTINEL_ARGV0", "@child", "MARK", NULL };
    char *envp[] = { "ACC_SENTINEL=7", NULL };
    pid_t p = fork();
    if (p == 0) {
        execve(path, argv, envp);
        _exit(127);
    }
    int st; waitpid(p, &st, 0);
    emit_i("child_exit", WIFEXITED(st) ? WEXITSTATUS(st) : -1);
}

/* argv[0] and environ as seen by a child exec'd with controlled values.
   Activated when argv[1]=="@child" (argv[2] is a marker). */
static int child_see(int argc, char **argv) {
    extern char **environ;
    printf("C_ARGV0=%s\n", argv[0]);
    printf("C_ARGC=%d\n", argc);
    /* Print only the sentinel key plus total count: full env ordering differs
       by libc, and what we need to prove is exact envp passthrough. */
    int total=0; const char *sent=NULL;
    for (char **e=environ; *e; e++) { total++; if (!strncmp(*e,"ACC_SENTINEL=",13)) sent=*e; }
    printf("C_ENV_TOTAL=%d\n", total);
    printf("C_ENV_SENTINEL=%s\n", sent ? sent : "(absent)");
    return 0;
}

/* envp: child prints the exact environment it was exec'd with */
static void dim_env(void) {
    /* exec self with an exact, minimal envp and observe whether compat adds
       anything (baseline execve is a full envp replacement). */
    char path[4096];
    ssize_t n = readlink("/proc/self/exe", path, sizeof(path)-1);
    if (n <= 0) { emit("exe_link","ERR"); return; }
    path[n]=0;
    char *argv[] = { "envprobe", "@child", "MARK", NULL };
    char *envp[] = { "ACC_SENTINEL=7", NULL };
    pid_t p=fork();
    if (p==0) { execve(path,argv,envp); _exit(127); }
    int st; waitpid(p,&st,0);
    emit_i("child_exit", WIFEXITED(st)?WEXITSTATUS(st):-1);
}

/* path: stat/open a set of virtual paths, report result category */
static void dim_path(void) {
    const char *paths[] = { "/tmp/acc-probe", "/lib/x", "/usr/lib/x", "/etc/passwd", "/bin/sh" };
    for (unsigned i=0;i<sizeof(paths)/sizeof(paths[0]);i++) {
        struct stat st;
        int r = stat(paths[i], &st);
        printf("stat[%s]=%s", paths[i], r==0 ? "OK" : "ERR");
        if (r==0) printf(":mode=%o:nlink=%lu", (unsigned)(st.st_mode & 07777), (unsigned long)st.st_nlink);
        else printf(":errno=%d", errno);
        printf("\n");
    }
}

/* identity: getpid/getuid/getgid and /proc/self linearization */
static void dim_identity(void) {
    emit_i("getpid", (long)getpid());
    emit_i("getuid", (long)getuid());
    emit_i("getgid", (long)getgid());
    char p[64]; snprintf(p, sizeof(p), "/proc/self");
    char buf[512]; ssize_t n = readlink(p, buf, sizeof(buf)-1);
    if (n>0) { buf[n]=0; printf("proc_self=%s\n", buf); }
    else printf("proc_self=ERR:%d\n", errno);
}

/* signal: install a handler for SIGUSR1 and raise it; report if it ran.
   Also try to install a SIGSYS handler and report whether ours stays. */
static volatile sig_atomic_t got_usr1 = 0;
static void on_usr1(int s) { (void)s; got_usr1 = 1; }
static void on_sys(int s) { (void)s; }
static void dim_signal(void) {
    struct sigaction sa, old;
    memset(&sa,0,sizeof sa); sa.sa_handler=on_usr1; sigemptyset(&sa.sa_mask);
    int r1 = sigaction(SIGUSR1, &sa, &old);
    raise(SIGUSR1);
    emit_i("sigaction_usr1", r1);
    emit_i("usr1_handler_ran", got_usr1);
    memset(&sa,0,sizeof sa); sa.sa_handler=on_sys; sigemptyset(&sa.sa_mask);
    int r2 = sigaction(SIGSYS, &sa, NULL);
    struct sigaction now; memset(&now,0,sizeof now);
    sigaction(SIGSYS, NULL, &now);
    emit_i("sigaction_sigsys", r2);
    emit("sigsys_handler_is_ours", now.sa_handler==on_sys ? "yes" : "no");
}

/* exit: normal 0, explicit 42, and killed by SIGTERM in a child */
static void dim_exit(const char *mode) {
    if (!strcmp(mode,"ok")) exit(0);
    if (!strcmp(mode,"42")) exit(42);
    if (!strcmp(mode,"sigterm")) { pid_t p=fork(); if(p==0){while(1)pause();} kill(p,SIGTERM); int st;waitpid(p,&st,0);
        emit_i("child_signaled", WIFSIGNALED(st)?WTERMSIG(st):-1); exit(0); }
    if (!strcmp(mode,"enoent")) { execl("/definitely/not/here","x",(char*)NULL); emit_i("execl_errno",errno); exit(9); }
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr,"usage: probe <dim> [mode]\n"); return 2; }
    const char *d = argv[1];
    if (!strcmp(d,"@child")) return child_see(argc, argv);
    if (!strcmp(d,"argv0")) dim_argv0();
    else if (!strcmp(d,"env")) dim_env();
    else if (!strcmp(d,"path")) dim_path();
    else if (!strcmp(d,"identity")) dim_identity();
    else if (!strcmp(d,"signal")) dim_signal();
    else if (!strcmp(d,"exit")) dim_exit(argc>2?argv[2]:"ok");
    else { fprintf(stderr,"unknown dim %s\n", d); return 2; }
    return 0;
}
