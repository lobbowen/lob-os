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
#include <sys/auxv.h>
#include <linux/stat.h>

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
    /* AT_EXECFN is the kernel-provided original executable name; a loader
       preserves it even when /proc/self/exe points at the loader. This is the
       correct self-location source under an explicit-loader execution. */
    const char *execfn = (const char *)getauxval(AT_EXECFN);
    emit("at_execfn", execfn ? (strstr(execfn,"probe") ? "probe" : execfn) : "(null)");
    char *argv[] = { "SENTINEL_ARGV0", "@child", "MARK", NULL };
    char *envp[] = { "ACC_SENTINEL=7", NULL };
    pid_t p = fork();
    if (p == 0) {
        /* Re-exec via AT_EXECFN when it names a real file; /proc/self/exe
           would point at the loader under explicit-loader execution. */
        const char *target = (execfn && execfn[0]=='/') ? execfn : path;
        execve(target, argv, envp);
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
    /* Inspect the environment this process actually received. Under a strict
       execve(envp={...}) a program sees exactly that envp. Report key count
       and presence of LOBOS_* injection, which the compat layer adds. */
    extern char **environ;
    int total=0, lobos=0, sentinel=0;
    for (char **e=environ; *e; e++) {
        total++;
        if (!strncmp(*e,"LOBOS_",6)) lobos++;
        if (!strncmp(*e,"ACC_SENTINEL=",13)) sentinel++;
    }
    emit_i("env_total", total);
    emit_i("env_lobos_keys", lobos);
    emit_i("env_acc_sentinel", sentinel);
    emit("env_mode", lobos>0 ? "compat-injected" : "clean");
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
    struct sigaction verify; memset(&verify,0,sizeof verify);
    sigaction(SIGUSR1, NULL, &verify);
    emit_i("getpid_libc", (long)getpid());
    emit_i("gettid_libc", (long)gettid());
    emit("usr1_handler_installed", verify.sa_handler==on_usr1 ? "yes":"no");
    sigset_t mask, pmask; sigemptyset(&mask); sigaddset(&mask, SIGUSR1);
    int blocked = sigprocmask(SIG_BLOCK, NULL, &pmask);
    emit_i("sigprocmask_rc", blocked);
    emit("usr1_blocked", sigismember(&pmask, SIGUSR1)?"yes":"no");
    int nb=0; for(int sg=1; sg<64; sg++) if(sigismember(&pmask,sg)==1) nb++;
    emit_i("blocked_signal_count", nb);
    { char bl[128]={0}; int k=0; for(int sg=1; sg<64; sg++) if(sigismember(&pmask,sg)==1) k+=snprintf(bl+k,sizeof(bl)-k,"%s%d",k?",":"",sg); emit("blocked_signals",bl); }
    /* Primary observation: does raise work with the inherited mask? */
    got_usr1 = 0;
    raise(SIGUSR1);
    emit_i("sigaction_usr1", r1);
    emit_i("usr1_handler_ran", got_usr1);
    /* Diagnostic only: unblock then retry, to prove the mask is the cause. */
    sigset_t ub; sigemptyset(&ub); sigaddset(&ub, SIGUSR1);
    sigprocmask(SIG_UNBLOCK, &ub, NULL);
    got_usr1 = 0;
    raise(SIGUSR1);
    emit_i("usr1_after_unblock", got_usr1);
    /* Control: send via kill(getpid()) directly; resets flag first. */
    got_usr1 = 0;
    kill(getpid(), SIGUSR1);
    emit_i("usr1_via_kill", got_usr1);
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
    else if (!strcmp(d,"statx")) {
        /* statx() must go through the same vfs translation as stat(). Compare
           statx("/tmp/...") vs stat("/tmp/...") results. */
        struct statx sx; memset(&sx,0,sizeof sx);
        int r = statx(AT_FDCWD, "/usr/bin/bash", 0,
                      STATX_BASIC_STATS, &sx);
        emit_i("statx_rc", r);
        emit_i("statx_errno", r<0 ? errno : 0);
        if (r==0) emit_i("statx_size", (long)sx.stx_size);
        emit("lobos_vroot", getenv("LOBOS_VROOT") ? getenv("LOBOS_VROOT") : "(null)");
        /* Layered diagnosis: is failure in translation, absolute-path stat,
           or symlink resolution? Each rung is a different code path. */
        struct stat st;
        const char *vr = getenv("LOBOS_VROOT");
        char abspath[1024], linktarget[1024];
        int r2 = stat("/usr/bin/bash", &st);           /* full hook path */
        emit_i("stat_virtual_rc", r2);
        emit_i("stat_virtual_errno", r2<0 ? errno : 0);
        if (vr) {
            snprintf(abspath,sizeof abspath,"%s/usr/bin/bash",vr);
            int r3 = stat(abspath,&st);                /* translated absolute, passthrough */
            emit_i("stat_abs_rc", r3);
            emit_i("stat_abs_errno", r3<0 ? errno : 0);
            char rl[512]; ssize_t n=readlink(abspath,rl,sizeof rl-1);
            if(n>0){rl[n]=0; snprintf(linktarget,sizeof linktarget,"%s",rl);
                emit("readlink_abs", "ok");
                int r4=stat(linktarget,&st);           /* symlink target, passthrough */
                emit_i("stat_target_rc", r4);
                emit_i("stat_target_errno", r4<0 ? errno : 0);
            } else emit("readlink_abs", "ERR");
        }
        char rl2[512]; ssize_t n2=readlink("/usr/bin/bash",rl2,sizeof rl2-1);
        if(n2>0){rl2[n2]=0; emit("readlink_virtual", strstr(rl2,"/data/")?"realpath":"virtual");}
        else emit("readlink_virtual","ERR");
        emit("statx_vs_stat", (r==0)==(r2==0) ? "consistent" : "MISMATCH");
    }
    else if (!strcmp(d,"jobctl")) {
        /* Probe whether process-group / terminal control syscalls work in this domain. */
        emit_i("getpgrp", (long)getpgrp());
        emit_i("getpid", (long)getpid());
        int r1 = setpgid(0, 0);
        emit_i("setpgid_self", r1);
        if (r1==0) emit_i("getpgrp_after", (long)getpgrp());
        emit_i("tcgetpgrp_0", (long)tcgetpgrp(0));
        emit_i("isatty_0", isatty(0));
    }
    else if (!strcmp(d,"exit")) dim_exit(argc>2?argv[2]:"ok");
    else { fprintf(stderr,"unknown dim %s\n", d); return 2; }
    return 0;
}
