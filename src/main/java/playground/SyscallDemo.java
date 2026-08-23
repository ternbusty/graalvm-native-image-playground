package playground;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Call getpid(2) by invoking syscall(2) directly instead of the libc wrapper.
 *
 * Unlike function symbols, syscall numbers are preprocessor macros and cannot
 * be resolved by name at runtime, so the number has to live in source. 172 is
 * SYS_getpid on aarch64 (x86_64 uses 39). The jextract branch shows how to
 * pull the number from sys/syscall.h at build time instead.
 */
public final class SyscallDemo {
    private SyscallDemo() {}

    private static final long SYS_GETPID = 172;

    public static void run() throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandle syscall = linker.downcallHandle(
                linker.defaultLookup().find("syscall").orElseThrow(),
                FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));

        System.out.println("pid = " + (long) syscall.invoke(SYS_GETPID));
    }

    public static final class Registration implements Feature {
        @Override
        public void duringSetup(DuringSetupAccess access) {
            RuntimeForeignAccess.registerForDowncall(FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
        }
    }
}
