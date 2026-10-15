#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

extern char **environ;

static char *dup_entry(const char *k, const char *v) {
    size_t kl = strlen(k), vl = strlen(v);
    char *e = (char *)malloc(kl + vl + 2);
    if (!e) return 0;
    memcpy(e, k, kl);
    e[kl] = '=';
    memcpy(e + kl + 1, v, vl + 1);
    return e;
}

static int replace_env(char *const envp[], const char *entry) {
    const char *eq = strchr(entry, '=');
    if (!eq) return 0;
    size_t kl = (size_t)(eq - entry);
    int n = 0;
    while (envp && envp[n]) n++;
    char **out = (char **)malloc((n + 2) * sizeof(char *));
    if (!out) return -1;
    for (int i = 0; i < n; i++) {
        if (!strncmp(envp[i], entry, kl) && envp[i][kl] == '=') {
            char *rep = dup_entry(entry, eq + 1);
            if (!rep) { free(out); return -1; }
            out[i] = rep;
            out[n] = 0;
            environ = out;
            return 1;
        }
        out[i] = envp[i];
    }
    out[n] = dup_entry(entry, eq + 1);
    if (!out[n]) { free(out); return -1; }
    out[n + 1] = 0;
    if (environ) environ = out;
    return 1;
}

JNIEXPORT jint JNICALL
Java_lobos_kernel_compat_LobosEnv_nativeSetenv(JNIEnv *env, jclass cls, jstring k, jstring v) {
    (void)env; (void)cls;
    const char *ck = (*env)->GetStringUTFChars(env, k, 0);
    const char *cv = (*env)->GetStringUTFChars(env, v, 0);
    char buf[4096];
    snprintf(buf, sizeof buf, "%s=%s", ck, cv);
    int r = replace_env(environ, buf);
    (*env)->ReleaseStringUTFChars(env, k, ck);
    (*env)->ReleaseStringUTFChars(env, v, cv);
    return r;
}

JNIEXPORT jstring JNICALL
Java_lobos_kernel_compat_LobosEnv_nativeGetenv(JNIEnv *env, jclass cls, jstring k) {
    (void)cls;
    const char *ck = (*env)->GetStringUTFChars(env, k, 0);
    const char *v = getenv(ck);
    jstring r = v ? (*env)->NewStringUTF(env, v) : 0;
    (*env)->ReleaseStringUTFChars(env, k, ck);
    return r;
}

