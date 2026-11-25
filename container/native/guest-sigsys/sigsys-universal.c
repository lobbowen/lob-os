#define _GNU_SOURCE
/* LobOS universal SIGSYS compatibility hook for guest glibc/Bun binaries.
 * Combines:
 *  - cortex (Apache-2.0) syscall semantics (synthetic success / -ENOSYS),
 *  - the proven bun Android LD_PRELOAD shim pattern (oven-sh/bun#39060):
 *    save previous handler, chain non-target signals, and REINSTALL our
 *    handler from a short-lived pthread AFTER the runtime has started
 *    (Bun/Go runtimes install their own SIGSYS handler during startup).
 *  - sigaction family interposition that pretends to succeed without letting
 *    the guest replace our handler.
 */
#include <signal.h>
#include <string.h>
#include <errno.h>
#include <stdint.h>
#include <limits.h>
#include <dlfcn.h>
#include <ucontext.h>
#include <sys/syscall.h>
#include <pthread.h>
#include <time.h>
#include <unistd.h>

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

typedef int (*sigaction_fn)(int, const struct sigaction *, struct sigaction *);

static sigaction_fn real_sigaction_fn = NULL;
static struct sigaction previous_sigsys;
static int have_previous = 0;

static sigaction_fn get_real_sigaction(void) {
    if (!real_sigaction_fn) {
        real_sigaction_fn = (sigaction_fn)dlsym(RTLD_NEXT, "sigaction");
        if (!real_sigaction_fn)
            real_sigaction_fn = (sigaction_fn)dlsym(RTLD_DEFAULT, "sigaction");
    }
    return real_sigaction_fn;
}

static void install_sigsys_handler(void);

/* Synthetic-success syscalls (fake success 0 is always safe). */
static int synth_ok(int nr) {
    switch (nr) {
#ifdef __NR_setuid
    case __NR_setuid:
#endif
#ifdef __NR_setgid
    case __NR_setgid:
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
    case __NR_madvise:
#endif
        return 1;
    }
    return 0;
}

static void lobos_sigsys_handler(int sig, siginfo_t *info, void *ctx) {
    /* Non-seccomp SIGSYS: chain to the previous handler like the bun shim. */
    if (!info || info->si_code != SYS_SECCOMP) {
        if (have_previous) {
            if (previous_sigsys.sa_flags & SA_SIGINFO) {
                if (previous_sigsys.sa_sigaction)
                    previous_sigsys.sa_sigaction(sig, info, ctx);
                return;
            }
            if (previous_sigsys.sa_handler == SIG_IGN) return;
            if (previous_sigsys.sa_handler && previous_sigsys.sa_handler != SIG_DFL) {
                previous_sigsys.sa_handler(sig);
                return;
            }
        }
        sigaction_fn rs = get_real_sigaction();
        if (rs) {
            struct sigaction d; memset(&d, 0, sizeof d);
            d.sa_handler = SIG_DFL; sigemptyset(&d.sa_mask);
            rs(sig, &d, NULL);
        }
        raise(sig);
        return;
    }

    ucontext_t *uc = (ucontext_t *)ctx;
    if (!uc) return;
    int nr = info->si_syscall;
    long ret = synth_ok(nr) ? 0 : -ENOSYS;
#if defined(__aarch64__)
    /* getpid=172/gettid=178: stable synthetic pid avoids TLS cache corruption.
       Bun Android blockers (openat2=437, fchmodat2=452, close_range=436,
       epoll_pwait2=441) are all in 424-511 -> -ENOSYS so Bun uses its
       existing ENOSYS fallbacks (proven by oven-sh/bun#39060). */
    if (nr == 172 || nr == 178) ret = 4242;
    uc->uc_mcontext.regs[0] = (unsigned long)ret;
    if (uc->uc_mcontext.pc >= 4) {
        uint32_t at = 0, prev = 0;
        memcpy(&at, (const void *)uc->uc_mcontext.pc, sizeof at);
        memcpy(&prev, (const void *)(uc->uc_mcontext.pc - 4), sizeof prev);
        int a = (at & 0xffe0001fU) == 0xd4000001U;
        int p = (prev & 0xffe0001fU) == 0xd4000001U;
        if (a && !p) uc->uc_mcontext.pc += 4;
    }
#else
    (void)nr;
#endif
}

static void install_sigsys_handler(void) {
    sigaction_fn rs = get_real_sigaction();
    if (!rs) return;
    struct sigaction sa; memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = lobos_sigsys_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    /* Save what was installed before (loader handler or runtime handler), so
       non-target SIGSYS can be chained rather than killing the process. */
    rs(SIGSYS, &sa, &previous_sigsys);
    have_previous = 1;
}

/* Reinstall after the runtime has finished installing its own handler. */
static void *reinstall_after_startup(void *arg) {
    (void)arg;
    struct timespec d = { .tv_sec = 0, .tv_nsec = 100 * 1000 * 1000 };
    nanosleep(&d, NULL);
    install_sigsys_handler();
    return NULL;
}

__attribute__((constructor(101)))
static void preload_init(void) {
    install_sigsys_handler();
    pthread_t th;
    if (pthread_create(&th, NULL, reinstall_after_startup, NULL) == 0)
        pthread_detach(th);
}

/* Interpose sigaction family: pretend success, never let guest replace ours. */
int sigaction(int sig, const struct sigaction *act, struct sigaction *old) {
    sigaction_fn rs = get_real_sigaction();
    if (!rs) { errno = ENOSYS; return -1; }
    if (sig == SIGSYS) {
        if (act && act->sa_sigaction == lobos_sigsys_handler) return rs(sig, act, old);
        if (old) { memset(old, 0, sizeof *old); old->sa_sigaction = lobos_sigsys_handler; old->sa_flags = SA_SIGINFO | SA_RESTART; }
        return 0; /* pretend success */
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
        if (act && act->sa_sigaction == lobos_sigsys_handler) return orig ? orig(sig, act, old, n) : 0;
        if (old) { memset(old, 0, sizeof *old); old->sa_sigaction = lobos_sigsys_handler; old->sa_flags = SA_SIGINFO | SA_RESTART; }
        return 0;
    }
    return orig ? orig(sig, act, old, n) : -1;
}

typedef void (*sighandler_t)(int);
sighandler_t signal(int sig, sighandler_t h) {
    static sighandler_t (*orig)(int, sighandler_t) = NULL;
    if (!orig) orig = (sighandler_t (*)(int, sighandler_t))dlsym(RTLD_NEXT, "signal");
    if (sig == SIGSYS) return (sighandler_t)lobos_sigsys_handler;
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
