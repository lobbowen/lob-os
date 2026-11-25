# glibc 2.41 patch: disable clone3 syscall (LobOS Android guest)

Android untrusted_app seccomp TRAPs clone3(435) with SIGSYS. glibc 2.41 on
aarch64 has __ASSUME_CLONE3=1, so __clone3_internal calls __clone3 directly
with no fallback -> every pthread_create dies under LobOS guest.

We build our own glibc (cross toolchain) and apply two source changes so
thread creation always uses clone(220), which the guest kernel permits.

## Change 1: sysdeps/unix/sysv/linux/aarch64/kernel-features.h
After `#define __ASSUME_CLONE_BACKWARDS 1`, add:
```
#undef __ASSUME_CLONE3
#define __ASSUME_CLONE3 0
```

## Change 2: sysdeps/unix/sysv/linux/clone-internal.c
Replace the entire body of `__clone3_internal` with:
```c
  {
    __set_errno (ENOSYS);
    return -1;
  }
```
This guarantees __clone_internal always falls back to
__clone_internal_fallback -> clone(220); clone3 is never issued.

## Verification
Build glibc, then under seccomp TRAP clone3:
  pthread_create must succeed (PTHREAD_CREATE r=0).
