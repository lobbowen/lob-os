#define _GNU_SOURCE
#include <signal.h>
#include <string.h>
#include <errno.h>
#include <ucontext.h>
#include <stdint.h>

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif
#define NR_rseq 293

static void handler(int sig, siginfo_t *info, void *ctx) {
    if (!info || !ctx) return;
    if (info->si_code != SYS_SECCOMP) return;
    int nr = info->si_syscall;
    ucontext_t *uc = (ucontext_t *)ctx;
    long ret = -ENOSYS;
    /* uid/gid/robust -> fake success */
    if ((nr >= 105 && nr <= 152) || nr == 190 || nr == 191) ret = 0;
    uc->uc_mcontext.regs[0] = (uint64_t)ret;
    uint64_t pc = uc->uc_mcontext.pc;
    if (pc >= 4) {
        uint32_t at = *(uint32_t *)pc, pr = *(uint32_t *)(pc - 4);
        if ((at & 0xffe0001fU) == 0xd4000001U && (pr & 0xffe0001fU) != 0xd4000001U)
            uc->uc_mcontext.pc = pc + 4;
    }
}

__attribute__((constructor)) static void install(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSYS, &sa, NULL);
}
