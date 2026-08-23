package playground;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Call getpid(2) through the libc symbol, resolved by name at runtime via
 * Linker.defaultLookup(). No header constants involved.
 */
public final class BasicDemo {
    private BasicDemo() {}

    public static void run() throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandle getpid = linker.downcallHandle(
                linker.defaultLookup().find("getpid").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT));

        System.out.println("pid = " + (int) getpid.invoke());
    }

    public static final class Registration implements Feature {
        @Override
        public void duringSetup(DuringSetupAccess access) {
            RuntimeForeignAccess.registerForDowncall(FunctionDescriptor.of(JAVA_INT));
        }
    }
}
