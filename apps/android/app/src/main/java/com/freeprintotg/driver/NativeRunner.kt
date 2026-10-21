package com.freeprintotg.driver

/** JNI side of the pack runner (src/main/cpp/runner.c). */
object NativeRunner {
    init { System.loadLibrary("fporunner") }

    /** dlopen [lib], fork, wire the fds to stdin/stdout/stderr, call fpo_entry(argv). Returns pid or a negative error. */
    @JvmStatic external fun spawn(lib: String, argv: Array<String>, env: Array<String>, inFd: Int, outFd: Int, errFd: Int): Int
    @JvmStatic external fun waitFor(pid: Int): Int
    @JvmStatic external fun kill(pid: Int)
}
