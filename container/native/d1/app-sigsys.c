#define _GNU_SOURCE
/* LobOS app-process SIGSYS hook (bionic ELF, loaded via System.loadLibrary).
 * Installed at library load time — before any terminal command — so the whole
 * app process and every fork/exec descendant (bash, node, claude) inherit the
 * handler. This is the generic system-level fix for Android seccomp TRAPs.
 * Based on ssfuisu/cortex (Apache-2.0) signals.c + syscalls.c, adapted for
 * aarch64 bionic. */
#include <signal.h>
#include <string.h>
#include <errno.h>
#include <stdint.h>
#include <dlfcn.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#include <fcntl.h>

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

/* syscalls a SIGSYS fake-success is safe for (identity/robust/madvise). */
static int lobos_synth_ok(int nr) {
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
    default:
        return 0;
    }
}

static void lobos_sigsys(int sig, siginfo_t *info, void *ctx) {
    if (!ctx || !info) return;
    if (info->si_code != SYS_SECCOMP) {   /* not seccomp: chain to default */
        int (*rs)(int, const struct sigaction *, struct sigaction *) =
            (int (*)(int, const struct sigaction *, struct sigaction *))dlsym(RTLD_NEXT, "sigaction");
        if (rs) {
            struct sigaction d; memset(&d, 0, sizeof d);
            d.sa_handler = SIG_DFL; sigemptyset(&d.sa_mask);
            rs(sig, &d, NULL);
        }
        return;
    }
    ucontext_t *uc = (ucontext_t *)ctx;
    int nr = info->si_syscall;
    long ret = lobos_synth_ok(nr) ? 0 : -ENOSYS;
#if defined(__aarch64__)
    /* aarch64: getpid=172/gettid=178 are TRAPped; a stable synthetic pid/tid
       keeps glibc/Bun thread trampolines valid (fixed value, no nested syscall). */
    if (nr == 172 || nr == 178) ret = 4242;
#endif
    uc->uc_mcontext.regs[0] = (unsigned long)ret;
    if (uc->uc_mcontext.pc >= 4) {
        uint32_t at = 0, prev = 0;
        memcpy(&at, (const void *)uc->uc_mcontext.pc, sizeof at);
        memcpy(&prev, (const void *)(uc->uc_mcontext.pc - 4), sizeof prev);
        int at_svc = ((at & 0xffe0001fU) == 0xd4000001U);
        int prev_svc = ((prev & 0xffe0001fU) == 0xd4000001U);
        if (at_svc && !prev_svc) uc->uc_mcontext.pc += 4;
    }
}

/* Signal-family hooks: keep guest programs from replacing/masking our handler. */
static int (*real_sigaction)(int, const struct sigaction *, struct sigaction *) = NULL;
static void init_real_sigaction(void) {
    if (!real_sigaction)
        real_sigaction = (int (*)(int, const struct sigaction *, struct sigaction *))dlsym(RTLD_NEXT, "sigaction");
}

int sigaction(int sig, const struct sigaction *act, struct sigaction *old) {
    init_real_sigaction();
    if (sig == SIGSYS) {
        if (act && act->sa_sigaction == lobos_sigsys)
            return real_sigaction ? real_sigaction(sig, act, old) : 0;
        if (old) { memset(old, 0, sizeof *old); old->sa_sigaction = lobos_sigsys; old->sa_flags = SA_SIGINFO | SA_RESTART; }
        return 0;
    }
    return real_sigaction ? real_sigaction(sig, act, old) : -1;
}

__attribute__((constructor(101)))
static void lobos_install_app_sigsys(void) {
    /* Install via raw rt_sigaction syscall — do NOT depend on dlsym/sigaction
       (our own sigaction interposer may not be resolvable this early). */
    struct {
        void (*handler)(int, siginfo_t *, void *);
        unsigned long flags;
        void *restorer;
        unsigned long mask[2];
    } ka;
    memset(&ka, 0, sizeof ka);
    ka.handler = lobos_sigsys;
    ka.flags = SA_SIGINFO | SA_RESTART; /* 4 | 0x10000000 */
    syscall(SYS_rt_sigaction, SIGSYS, &ka, NULL, 8);
}
