#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <link.h>
#include <android/log.h>
#include <sys/wait.h>

static void *sym(const char *n) {
    void *p = dlsym(RTLD_DEFAULT, n);
    if (p) return p;
    void *h = dlopen("libdl.so", RTLD_NOW);
    if (!h) return NULL;
    return dlsym(h, n);
}

int main(int argc, char **argv) {
    char path[4096], lib[4300];
    const char *libdir = getenv("LOBOS_PREFIX");
    if (libdir && *libdir) snprintf(path, sizeof(path), "%s/usr/lib", libdir);
    else snprintf(path, sizeof(path), "/data/user/0/lobos.os/files/usr/lib");

    printf("PROBE libdir=%s\n", path); fflush(stdout);
    printf("PROBE LD_LIBRARY_PATH=[%s]\n", getenv("LD_LIBRARY_PATH") ? getenv("LD_LIBRARY_PATH") : ""); fflush(stdout);

    void *anon = sym("__loader_android_init_anonymous_namespace");
    printf("PROBE anon_sym=%p\n", anon); fflush(stdout);
    if (!anon) { printf("PROBE done\n"); return 0; }

    typedef void *(*anon_fn)(const char *, const char *, const char *);
    void *ns = ((anon_fn)anon)("lobos", path, path);
    printf("PROBE ns=%p\n", ns); fflush(stdout);

    // 关键：清掉 LD_LIBRARY_PATH，看还能不能加载我们装的库
    unsetenv("LD_LIBRARY_PATH");
    snprintf(lib, sizeof(lib), "%s/libz.so.1", path);
    void *h = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    printf("PROBE dlopen %s = %p  err=%s\n", lib, h, h ? "-" : dlerror());
    fflush(stdout);

    // 再试按 SONAME 加载（不写全路径）
    void *h2 = dlopen("libz.so.1", RTLD_NOW | RTLD_LOCAL);
    printf("PROBE dlopen by SONAME libz.so.1 = %p  err=%s\n", h2, h2 ? "-" : dlerror());
    fflush(stdout);

    printf("PROBE done\n");
    return 0;
}
