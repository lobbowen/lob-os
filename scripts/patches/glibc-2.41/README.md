# glibc 2.41 SIGSYS handler patch

The prebuilt loader in `container/native/prebuilt/arm64-v8a/libglibc-loader.so`
was built from Debian glibc 2.41.orig.tar.xz (sha256
f24aa441021121a79266f0d75242706cab8843a47901fefe74527491807f1998),
patched as follows, cross-compiled with gcc-aarch64-linux-gnu-15.

## Patch (elf/rtld.c)

1. Add a SIGSYS handler near the top (after the #include block):

```c
static void lobos_sigsys_handler (int sig, siginfo_t *info, void *ctx) {
  (void) sig;
  ucontext_t *uc = (ucontext_t *) ctx;
  if (!uc) return;
  uc->uc_mcontext.regs[0] = (unsigned long) -38;   /* -ENOSYS */
  unsigned long pc = uc->uc_mcontext.pc;
  if (pc >= 4) {
    unsigned int at = *(unsigned int *) pc, pr = *(unsigned int *) (pc - 4);
    if ((at & 0xffe0001fU) == 0xd4000001U && (pr & 0xffe0001fU) != 0xd4000001U)
      uc->uc_mcontext.pc = pc + 4;
  }
}
static void lobos_install_sigsys (void) {
  unsigned long ka[4] = {0,0,0,0};
  ka[0] = (unsigned long) lobos_sigsys_handler;
  ka[1] = 4 | 0x10000000;   /* SA_SIGINFO | SA_RESTART */
  INLINE_SYSCALL_CALL (rt_sigaction, SIGSYS, ka, NULL, 8);
}
```

2. Forward-declare `static void lobos_install_sigsys (void);` before `_dl_start`.

3. Call `lobos_install_sigsys ();` in `_dl_start` right after
   `bootstrap_map.l_relocated = 1;` (after self-relocation, before any
   libc/program initialization, so no libc calls are needed in the handler).

## Why

Android untrusted_app seccomp sends SIGSYS for rseq/set_robust_list/etc.
glibc startup calls these -> signal 31 death. The handler emulates -ENOSYS
and advances PC past the faulting svc, so glibc falls back gracefully.
Technique from ssfuisu/cortex signals.c + syscalls.c (Apache-2.0).

## Build

See docs/BASE-CAPABILITY-GAPS.md. Toolchain: apt-get download of
gcc-15-aarch64-linux-gnu, binutils, libc6-dev/libgcc/linux-libc-dev cross debs.
Configure: --host=aarch64-linux-gnu --disable-werror CC=aarch64-linux-gnu-gcc-15.
The resulting elf/ld.so is the prebuilt loader.
