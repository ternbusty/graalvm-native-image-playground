/*
 * JNI implementation for playground.jni.JniSyscall.
 *
 * Each native method needs:
 *   1. A matching JNIEXPORT function with the mangled name
 *   2. Manual type conversion between JNI types (jint, jstring, ...) and C types
 *   3. Explicit (void) casts to suppress unused-parameter warnings
 *
 * Compare this to JNA (zero C code) and Panama FFM (zero C code).
 */
#include <jni.h>
#include <unistd.h>
#include <sys/syscall.h>

/* getpid(): trivial case, no arguments, no buffers. */
JNIEXPORT jint JNICALL
Java_playground_jni_JniSyscall_getpid(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return getpid();
}

/*
 * gethostname(): demonstrates buffer handling.
 * In JNI, we allocate the buffer in C, call gethostname(), then convert
 * the result to a jstring via NewStringUTF(). The caller never sees
 * the raw buffer.
 */
JNIEXPORT jstring JNICALL
Java_playground_jni_JniSyscall_gethostname(JNIEnv *env, jclass cls)
{
    (void)cls;
    char buf[256];
    if (gethostname(buf, sizeof(buf)) == -1) {
        return (*env)->NewStringUTF(env, "(error)");
    }
    return (*env)->NewStringUTF(env, buf);
}

/*
 * Raw syscall(SYS_gettid): demonstrates calling a syscall by number.
 * SYS_gettid has a glibc wrapper since 2.30, but the pattern is
 * identical for syscalls that lack one (clone3, open_tree, move_mount,
 * pidfd_open, etc.) -- you just change the number.
 */
JNIEXPORT jlong JNICALL
Java_playground_jni_JniSyscall_rawGettid(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return (jlong)syscall(SYS_gettid);
}
