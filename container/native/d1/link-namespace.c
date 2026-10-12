#include <dlfcn.h>
#include <stdio.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// 进程自带库搜索路径。
//
// 为什么需要它：Android 的链接器不读 ld.so.cache（实测 /system/etc/ld.so.conf
// 不存在），只认 LD_LIBRARY_PATH。而 LD_LIBRARY_PATH 是环境变量，
// 意味着「每起一个程序都要告诉它这个系统的库在哪」——
// 那不是任何系统该有的样子（Linux 上 /usr/lib 在不在，程序不需要知道）。
//
// 所以改成：进程启动时把自己的库搜索路径注册进 linker namespace。
// 注册之后，这个进程以及它 fork 出来的所有子进程，
// dlopen / 动态链接都自动在那里找。不需要任何环境变量。
//
// Android 10（API 29）起提供这个机制；minSdk 已是 29。
// 做法与 Google 给 app 做库隔离一致。

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

static char g_prefix_lib[PATH_MAX];
static char g_prefix_bin[PATH_MAX];
static int g_inited = 0;

static void read_prefix(void) {
    const char *base = getenv("LOBOS_PREFIX");
    if (base == NULL || base[0] == '\0') {
        return;
    }
    snprintf(g_prefix_lib, sizeof(g_prefix_lib), "%s/usr/lib", base);
    snprintf(g_prefix_bin, sizeof(g_prefix_bin), "%s/usr/bin", base);
}

__attribute__((constructor))
static void lobos_init_namespace(void) {
    if (g_inited) {
        return;
    }
    g_inited = 1;

    read_prefix();
    if (g_prefix_lib[0] == '\0') {
        fprintf(stderr,
                            "LOBOS_PREFIX 未设，跳过 namespace 注册");
        return;
    }

    typedef void *(*init_ns_fn)(const char *, size_t, const char *);
    init_ns_fn init_ns = (init_ns_fn)dlsym(RTLD_DEFAULT, "android_init_anonymous_namespace");
    if (init_ns == NULL) {
        fprintf(stderr,
                            "链接器没有 android_init_anonymous_namespace（需 API 29+）");
        return;
    }

    void *ns = init_ns(g_prefix_lib, strlen(g_prefix_lib), g_prefix_lib);
    if (ns == NULL) {
        fprintf(stderr,
                            "namespace 注册失败: %s/%s", g_prefix_lib, dlerror());
        return;
    }

    fprintf(stderr,
                        "namespace 已就绪: %s", g_prefix_lib);
}
