/* Runs a driver pack's fpo_entry (a CUPS-filter main) in a forked child with stdin/stdout/stderr on pipes.
 * Android 10+ forbids exec() from the app's data dir, so packs are shared libraries, not executables.
 * The library is dlopen()ed in the parent before fork so the child only does async-signal-safe-ish work. */
#include <jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

typedef int (*entry_fn)(int, char **);

static char **to_c(JNIEnv *e, jobjectArray a, int *n) {
    int len = a ? (*e)->GetArrayLength(e, a) : 0;
    char **v = calloc(len + 1, sizeof(char *));
    for (int i = 0; i < len; i++) {
        jstring s = (*e)->GetObjectArrayElement(e, a, i);
        const char *u = (*e)->GetStringUTFChars(e, s, NULL);
        v[i] = strdup(u);
        (*e)->ReleaseStringUTFChars(e, s, u);
        (*e)->DeleteLocalRef(e, s);
    }
    if (n) *n = len;
    return v;
}

/* Returns the child pid, or -errno / -1000 (dlopen) / -1001 (dlsym). */
JNIEXPORT jint JNICALL
Java_com_freeprintotg_driver_NativeRunner_spawn(JNIEnv *e, jclass c, jstring lib, jobjectArray argv, jobjectArray env,
                                                jint inFd, jint outFd, jint errFd) {
    const char *path = (*e)->GetStringUTFChars(e, lib, NULL);
    void *h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    (*e)->ReleaseStringUTFChars(e, lib, path);
    if (!h) return -1000;
    entry_fn entry = (entry_fn)dlsym(h, "fpo_entry");
    if (!entry) return -1001;

    int argc, envc;
    char **av = to_c(e, argv, &argc);
    char **ev = to_c(e, env, &envc);

    pid_t pid = fork();
    if (pid < 0) return -errno;
    if (pid == 0) {
        dup2(inFd, 0); dup2(outFd, 1); dup2(errFd, 2);
        for (int fd = 3; fd < 256; fd++) close(fd);
        for (int i = 0; i < envc; i++) putenv(ev[i]); /* "NAME=value" strings stay alive: we never return */
        int rc = entry(argc, av);
        fflush(NULL);
        _exit(rc & 0xff);
    }
    for (int i = 0; i < argc; i++) free(av[i]);
    for (int i = 0; i < envc; i++) free(ev[i]);
    free(av); free(ev);
    return pid;
}

/* Exit status 0..255, 256+signal when killed, or -errno. */
JNIEXPORT jint JNICALL
Java_com_freeprintotg_driver_NativeRunner_waitFor(JNIEnv *e, jclass c, jint pid) {
    int st;
    while (waitpid(pid, &st, 0) < 0) if (errno != EINTR) return -errno;
    if (WIFEXITED(st)) return WEXITSTATUS(st);
    if (WIFSIGNALED(st)) return 256 + WTERMSIG(st);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_freeprintotg_driver_NativeRunner_kill(JNIEnv *e, jclass c, jint pid) { kill(pid, 9); }
