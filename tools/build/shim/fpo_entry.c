/* Common entry point every engine library exports. The app's runner dlopen()s the library, forks,
 * wires stdin/stdout/stderr to pipes, then calls fpo_entry like a CUPS filter main().
 * Compiled as C for C engines and as C++ (-x c++) for C++ engines so the linkage of fpo_filter_main matches. */
int fpo_filter_main(int argc, char **argv);

#ifdef __cplusplus
extern "C"
#endif
__attribute__((visibility("default")))
int fpo_entry(int argc, char **argv) { return fpo_filter_main(argc, argv); }
