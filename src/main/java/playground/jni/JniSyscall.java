package playground.jni;

/**
 * JNI approach: declare native methods in Java, implement in C.
 * <p>
 * Requires:
 * <ul>
 *   <li>A C source file with JNIEXPORT functions matching the mangled names</li>
 *   <li>javac -h to generate the JNI header</li>
 *   <li>gcc to compile the shared library</li>
 *   <li>System.loadLibrary() at runtime (or -H:NativeLinkerOption for native-image)</li>
 * </ul>
 */
public final class JniSyscall {

    static {
        System.loadLibrary("jnisyscall");
    }

    private JniSyscall() {}

    public static native int getpid();

    public static native String gethostname();

    public static native long rawGettid();
}
