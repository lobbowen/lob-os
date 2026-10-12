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
    const char *libdir = getenv("LOBOS_PREFIX");
    char path[4096], lib[4200], so[4300];
    if (libdir && *libdir) snprintf(path, sizeof(path), "%s/usr/lib", libdir);
    else snprintf(path, sizeof(path), "/data/user/0/lobos.os/files/usr/lib");

    printf("PROBE libdir=%s\n", path);
    fflush(stdout);

    const char *names[] = {
        "android_init_anonymous_namespace",
        "__loader_android_init_anonymous_namespace",
        "android_create_namespace",
        "__loader_android_create_namespace",
        "android_link_namespaces",
        "__loader_android_link_namespaces",
    };
    void *anon = NULL;
    for (unsigned i = 0; i < sizeof(names)/sizeof(names[0]); i++) {
        void *p = sym(names[i]);
        printf("PROBE sym %-46s = %p\n", names[i], p);
        fflush(stdout);
        if (p && !anon) anon = p;
    }

    typedef void *(*anon_fn)(const char *, const char *, const char *);
    if (anon) {
        printf("PROBE calling with 3-arg form (may abort)...\n"); fflush(stdout);
        void *ns = ((anon_fn)anon)(path, path, path);
        printf("PROBE anon_ns=%p\n", ns); fflush(stdout);
    }
    printf("PROBE exec self with ns...\n"); fflush(stdout);
    pid_t pid = fork();
    if (pid == 0) {
        char *av[] = { "/system/bin/linker64", "--list", lib, NULL };
        execv(av[0], av);
        _exit(127);
    }
    int st = 0; waitpid(pid, &st, 0);
    printf("PROBE child_status=%d exited=%d code=%d\n", st, WIFEXITED(st), WEXITSTATUS(st));
    printf("PROBE done\n");
    return 0;
}
