#define _GNU_SOURCE 1
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <linux/stat.h>

int statx(int dirfd, const char *pathname, int flags, unsigned int mask, struct statx *buf) {
    struct stat st;
    int f = flags & AT_EMPTY_PATH ? AT_EMPTY_PATH : 0;
    if (fstatat(dirfd, pathname ? pathname : "", &st, f) < 0) return -1;
    memset(buf, 0, sizeof(*buf));
    buf->stx_mask = STATX_TYPE | STATX_MODE | STATX_NLINK | STATX_UID | STATX_GID |
                    STATX_ATIME | STATX_MTIME | STATX_CTIME | STATX_SIZE | STATX_BLOCKS | STATX_INO;
    buf->stx_blksize = (unsigned int)st.st_blksize;
    buf->stx_nlink = (unsigned int)st.st_nlink;
    buf->stx_uid = st.st_uid;
    buf->stx_gid = st.st_gid;
    buf->stx_mode = st.st_mode;
    buf->stx_ino = st.st_ino;
    buf->stx_size = st.st_size;
    buf->stx_blocks = st.st_blocks;
    buf->stx_atime.tv_sec = st.st_atim.tv_sec;
    buf->stx_atime.tv_nsec = st.st_atim.tv_nsec;
    buf->stx_mtime.tv_sec = st.st_mtim.tv_sec;
    buf->stx_mtime.tv_nsec = st.st_mtim.tv_nsec;
    buf->stx_ctime.tv_sec = st.st_ctim.tv_sec;
    buf->stx_ctime.tv_nsec = st.st_ctim.tv_nsec;
    buf->stx_btime.tv_sec = st.st_ctim.tv_sec;
    buf->stx_btime.tv_nsec = st.st_ctim.tv_nsec;
    return 0;
}
