#ifndef LOBOS_ANDROID_COMPAT_H
#define LOBOS_ANDROID_COMPAT_H
#include <spawn.h>
#include <linux/stat.h>
#ifdef __cplusplus
extern "C" {
#endif
int statx(int dirfd, const char *pathname, int flags, unsigned int mask, struct statx *buf);
#ifdef __cplusplus
}
#endif
#endif
