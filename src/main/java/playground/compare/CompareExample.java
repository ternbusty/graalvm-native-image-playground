package playground.compare;

import playground.jni.JniSyscall;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Side-by-side comparison of JNI, JNA, and Panama FFM calling the same
 * three libc/kernel operations.
 *
 * <pre>
 *   getpid()                simple, no arguments
 *   gethostname(buf, len)   buffer passing
 *   syscall(SYS_gettid)     raw syscall by number
 * </pre>
 */
public final class CompareExample {

    private CompareExample() {}

    // ── Panama FFM handles (inline, no C code, no separate library) ────
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();

    private static final MethodHandle FFM_GETPID = LINKER.downcallHandle(
            LIBC.find("getpid").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT));

    private static final MethodHandle FFM_GETHOSTNAME = LINKER.downcallHandle(
            LIBC.find("gethostname").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    private static final MethodHandle FFM_SYSCALL = LINKER.downcallHandle(
            LIBC.find("syscall").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
            Linker.Option.firstVariadicArg(1));

    private static final long SYS_GETTID = 178; // aarch64

    // ── JNA lazy init (fails at native-image time) ─────────────────────
    private static playground.jna.JnaLibC jnaInstance;
    private static boolean jnaFailed;
    private static String jnaError;

    private static playground.jna.JnaLibC jnaLibC() {
        if (jnaInstance == null && !jnaFailed) {
            try {
                jnaInstance = playground.jna.JnaLibC.INSTANCE;
            } catch (Throwable t) {
                jnaFailed = true;
                jnaError = t.getClass().getSimpleName();
            }
        }
        return jnaInstance;
    }

    private static void jnaCall(Runnable action) {
        if (jnaFailed) {
            System.out.println("  JNA: N/A (" + jnaError
                    + " -- JNA uses reflection/dynamic proxy, incompatible with native-image)");
            return;
        }
        try {
            action.run();
        } catch (Throwable t) {
            jnaFailed = true;
            jnaError = t.getClass().getSimpleName();
            System.out.println("  JNA: N/A (" + jnaError
                    + " -- JNA uses reflection/dynamic proxy, incompatible with native-image)");
        }
    }

    public static void run() {
        System.out.println("=== Syscall comparison: JNI vs JNA vs Panama FFM ===");
        System.out.println();

        runGetpid();
        runGethostname();
        runRawSyscall();
    }

    private static void runGetpid() {
        System.out.println("[1] getpid()");
        System.out.println();

        // JNI: requires jni_syscall.c (16 lines) + JniSyscall.java (native declaration)
        System.out.println("  JNI: " + JniSyscall.getpid());

        // JNA: interface declaration only, no C code
        jnaCall(() -> System.out.println("  JNA: " + jnaLibC().getpid()));

        // FFM: MethodHandle declaration only, no C code
        try {
            System.out.println("  FFM: " + (int) FFM_GETPID.invoke());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        System.out.println();
    }

    private static void runGethostname() {
        System.out.println("[2] gethostname(buf, len)  -- buffer passing");
        System.out.println();

        // JNI: C side allocates char[256], calls gethostname, returns jstring.
        // Java side sees only a String.
        System.out.println("  JNI: " + JniSyscall.gethostname());

        // JNA: byte[] is auto-marshalled as a pointer with copy-back.
        // Caller must allocate the array and convert to String afterward.
        jnaCall(() -> {
            byte[] jnaBuf = new byte[256];
            jnaLibC().gethostname(jnaBuf, 256);
            System.out.println("  JNA: " + com.sun.jna.Native.toString(jnaBuf));
        });

        // FFM: Arena allocates off-heap memory. MemorySegment.getString()
        // reads a null-terminated C string. Arena.ofConfined() auto-frees.
        try (var arena = Arena.ofConfined()) {
            var buf = arena.allocate(256);
            int rc = (int) FFM_GETHOSTNAME.invoke(buf, 256L);
            System.out.println("  FFM: " + (rc == 0 ? buf.getString(0) : "(error)"));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        System.out.println();
    }

    private static void runRawSyscall() {
        System.out.println("[3] syscall(SYS_gettid)  -- raw syscall by number");
        System.out.println("    (same pattern for clone3, open_tree, move_mount, etc.)");
        System.out.println();

        // JNI: C side calls syscall(SYS_gettid). Changing the syscall
        // number means editing and recompiling the C code.
        System.out.println("  JNI: " + JniSyscall.rawGettid());

        // JNA: calling variadic syscall() through JNA interface binding.
        // On aarch64, variadic args use a different ABI (stack vs registers),
        // so this may produce wrong results if JNA does not handle it.
        jnaCall(() -> System.out.println("  JNA: " + jnaLibC().syscall(SYS_GETTID)));

        // FFM: Linker.Option.firstVariadicArg(1) tells the linker that
        // args after index 0 are variadic, so aarch64 ABI is handled
        // correctly. Just change the number to call any syscall.
        try {
            System.out.println("  FFM: " + (long) FFM_SYSCALL.invoke(SYS_GETTID));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        System.out.println();
    }
}
