#define _GNU_SOURCE
/* LobOS universal SIGSYS compatibility hook.
 * Ported from ssfuisu/cortex (Apache-2.0) signals.c + syscalls.c:
 *  - SIGSYS handler emulates Android-seccomp-blocked syscalls so native
 *    glibc binaries (node, claude, any ELF) run as on a normal Linux.
 *  - Hooks the sigaction/signal/sigprocmask family so guest programs cannot
 *    replace or mask our handler (the root cause of handler being lost).
 * This is the generic, one-time system fix; no per-product patching.
 */
#include <signal.h>
#include <string.h>
#include <errno.h>
#include <stdint.h>
#include <limits.h>
#include <dlfcn.h>
#include <execinfo.h>
#include <ucontext.h>
#include <sys/syscall.h>

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

/* ---- syscalls.c: which TRAPped syscalls get fake success vs -ENOSYS ---- */
static int is_sigsys_synthetic_success_syscall(int nr) {
    switch (nr) {
#ifdef __NR_setuid
    case __NR_setuid:
#endif
#ifdef __NR_setgid
    case __NR_setgid:
#endif
#ifdef __NR_setreuid
    case __NR_setreuid:
#endif
#ifdef __NR_setregid
    case __NR_setregid:
#endif
#ifdef __NR_setresuid
    case __NR_setresuid:
#endif
#ifdef __NR_setresgid
    case __NR_setresgid:
#endif
#ifdef __NR_setfsuid
    case __NR_setfsuid:
#endif
#ifdef __NR_setfsgid
    case __NR_setfsgid:
#endif
#ifdef __NR_setgroups
    case __NR_setgroups:
#endif
#ifdef __NR_set_robust_list
    case __NR_set_robust_list:
#endif
#ifdef __NR_get_robust_list
    case __NR_get_robust_list:
#endif
#ifdef __NR_madvise
    case __NR_madvise:   /* memory advice: fake success is always safe */
#endif
        return 1;
    default:
        return 0;
    }
}

static int is_handled_sigsys_syscall(int nr) {
    if (is_sigsys_synthetic_success_syscall(nr)) return 1;
    /* Unified Linux 5.1+ syscalls (includes clone3=435, epoll_pwait2, ...) */
    if (nr >= 424 && nr < 512) return 1;
#if defined(__aarch64__)
    if (nr == 97  /* unshare */ || nr == 217 /* add_key */ ||
        nr == 218 /* request_key */ || nr == 219 /* keyctl */ ||
        nr == 268 /* setns */ || nr == 277 /* seccomp */ ||
        nr == 280 /* bpf */ || nr == 293 /* rseq */) return 1;
#endif
    return 0;
}

/* ---- signals.c: handler + sigaction family hooks ---- */
static int (*get_real_sigaction(void))(int, const struct sigaction *, struct sigaction *) {
    static int (*real_sigaction)(int, const struct sigaction *, struct sigaction *) = NULL;
    if (!real_sigaction) {
        real_sigaction = (int (*)(int, const struct sigaction *, struct sigaction *))dlsym(RTLD_NEXT, "sigaction");
        if (!real_sigaction)
            real_sigaction = (int (*)(int, const struct sigaction *, struct sigaction *))dlsym(RTLD_DEFAULT, "sigaction");
    }
    return real_sigaction;
}

static void cortex_sigsys_handler(int sig, siginfo_t *info, void *ctx) {
    if (!ctx || !info) return;
    if (info->si_code != SYS_SECCOMP) {
        int (*real_sig)(int, const struct sigaction *, struct sigaction *) = get_real_sigaction();
        if (real_sig) {
            struct sigaction dfl; memset(&dfl, 0, sizeof dfl);
            dfl.sa_handler = SIG_DFL; sigemptyset(&dfl.sa_mask);
            real_sig(sig, &dfl, NULL);
        }
        raise(sig);
        return;
    }
    ucontext_t *uc = (ucontext_t *)ctx;
    int nr = info->si_syscall;
    /* Unknown/TRAPped syscall: -ENOSYS (glibc falls back). Known handled ones
       that aren't synthetic-success also get -ENOSYS. Everything is emulated. */
    long ret = is_sigsys_synthetic_success_syscall(nr) ? 0 : -ENOSYS;

#if defined(__aarch64__)
    uc->uc_mcontext.regs[0] = (unsigned long)ret;
    if (uc->uc_mcontext.pc >= 4) {
        uint32_t at = 0, prev = 0;
        memcpy(&at, (const void *)uc->uc_mcontext.pc, sizeof at);
        memcpy(&prev, (const void *)(uc->uc_mcontext.pc - 4), sizeof prev);
        int at_svc = ((at & 0xffe0001fU) == 0xd4000001U);
        int prev_svc = ((prev & 0xffe0001fU) == 0xd4000001U);
        if (at_svc && !prev_svc) uc->uc_mcontext.pc += 4;
    }
#endif
    (void)is_handled_sigsys_syscall; /* all SIGSYS are handled uniformly */
}

__attribute__((constructor(101))) static void install_sigsys(void) {
    int (*real_sig)(int, const struct sigaction *, struct sigaction *) = get_real_sigaction();
    if (!real_sig) return;
    struct sigaction sa; memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = cortex_sigsys_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    real_sig(SIGSYS, &sa, NULL);
}

/* Hooks: prevent guest from replacing/masking our handler. */
int sigaction(int sig, const struct sigaction *act, struct sigaction *old) {
    int (*rs)(int, const struct sigaction *, struct sigaction *) = get_real_sigaction();
    if (!rs) return -1;
    if (sig == SIGSYS) {
        if (act && act->sa_sigaction == cortex_sigsys_handler) return rs(sig, act, old);
        if (old) { memset(old, 0, sizeof *old); old->sa_sigaction = cortex_sigsys_handler; old->sa_flags = SA_SIGINFO | SA_RESTART; }
        return 0;
    }
    return rs(sig, act, old);
}

int __libc_sigaction(int sig, const struct sigaction *act, struct sigaction *old) {
    return sigaction(sig, act, old);
}

int rt_sigaction(int sig, const struct sigaction *act, struct sigaction *old, size_t n) {
    static int (*orig)(int, const struct sigaction *, struct sigaction *, size_t) = NULL;
    if (!orig) orig = (int (*)(int, const struct sigaction *, struct sigaction *, size_t))dlsym(RTLD_NEXT, "rt_sigaction");
    if (sig == SIGSYS) {
        if (act && act->sa_sigaction == cortex_sigsys_handler) return orig ? orig(sig, act, old, n) : 0;
        if (old) { memset(old, 0, sizeof *old); old->sa_sigaction = cortex_sigsys_handler; old->sa_flags = SA_SIGINFO | SA_RESTART; }
        return 0;
    }
    return orig ? orig(sig, act, old, n) : -1;
}

typedef void (*sighandler_t)(int);
sighandler_t signal(int sig, sighandler_t h) {
    static sighandler_t (*orig)(int, sighandler_t) = NULL;
    if (!orig) orig = (sighandler_t (*)(int, sighandler_t))dlsym(RTLD_NEXT, "signal");
    if (sig == SIGSYS) return (sighandler_t)cortex_sigsys_handler;
    return orig ? orig(sig, h) : NULL;
}

static void unmask_sigsys(const sigset_t *set, sigset_t *out) {
    if (set) { *out = *set; sigdelset(out, SIGSYS); }
}
int sigprocmask(int how, const sigset_t *set, sigset_t *old) {
    static int (*orig)(int, const sigset_t *, sigset_t *) = NULL;
    if (!orig) orig = (int (*)(int, const sigset_t *, sigset_t *))dlsym(RTLD_NEXT, "sigprocmask");
    if (set && (how == SIG_BLOCK || how == SIG_SETMASK)) { sigset_t m; unmask_sigsys(set, &m); return orig ? orig(how, &m, old) : 0; }
    return orig ? orig(how, set, old) : 0;
}
int pthread_sigmask(int how, const sigset_t *set, sigset_t *old) {
    static int (*orig)(int, const sigset_t *, sigset_t *) = NULL;
    if (!orig) orig = (int (*)(int, const sigset_t *, sigset_t *))dlsym(RTLD_NEXT, "pthread_sigmask");
    if (set && (how == SIG_BLOCK || how == SIG_SETMASK)) { sigset_t m; unmask_sigsys(set, &m); return orig ? orig(how, &m, old) : 0; }
    return orig ? orig(how, set, old) : 0;
}
