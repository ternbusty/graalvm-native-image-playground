package playground.ffm;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Shared Panama FFM bindings for the playground.
 * One downcall handle per libc symbol. Loaded once at class init.
 */
public final class Libc {
    private Libc() {}

    public static final Linker LINKER = Linker.nativeLinker();
    public static final SymbolLookup LIBC = LINKER.defaultLookup();

    public static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LIBC.find(name)
                .map(addr -> LINKER.downcallHandle(addr, desc))
                .orElseThrow(() -> new UnsatisfiedLinkError("libc symbol not found: " + name));
    }

    public static final MethodHandle GETPID = downcall("getpid",
            FunctionDescriptor.of(ValueLayout.JAVA_INT));

    public static final MethodHandle FORK = downcall("fork",
            FunctionDescriptor.of(ValueLayout.JAVA_INT));

    public static final MethodHandle UNSHARE = downcall("unshare",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    public static final MethodHandle OPEN = downcall("open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    public static final MethodHandle WRITE = downcall("write",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    public static final MethodHandle CLOSE = downcall("close",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    public static final MethodHandle READLINK = downcall("readlink",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    public static final MethodHandle WAITPID = downcall("waitpid",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    public static final MethodHandle KILL = downcall("kill",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    public static final MethodHandle SYSCALL = downcall("syscall",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG));

    public static final MethodHandle ERRNO_LOCATION = downcall("__errno_location",
            FunctionDescriptor.of(ValueLayout.ADDRESS));

    public static final MethodHandle STRERROR = downcall("strerror",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    public static final MethodHandle EXIT_GROUP = downcall("_exit",
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));

    public static int errno() {
        try {
            MemorySegment loc = (MemorySegment) ERRNO_LOCATION.invoke();
            return loc.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static String strerror(int err) {
        try {
            MemorySegment ptr = (MemorySegment) STRERROR.invoke(err);
            return ptr.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            return "errno=" + err;
        }
    }
}
