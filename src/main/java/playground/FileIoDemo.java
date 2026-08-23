package playground;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * fopen(3) + fwrite(3) + fclose(3) via FFM downcalls with a confined Arena.
 * The stdio family takes the open mode as a string ("w"), so no O_* header
 * constants are needed. The native memory is released when the
 * try-with-resources block exits.
 */
public final class FileIoDemo {
    private FileIoDemo() {}

    public static void run() throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandle fopen = handle(linker, "fopen",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        MethodHandle fwrite = handle(linker, "fwrite",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS));
        MethodHandle fclose = handle(linker, "fclose",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

        String path = "/tmp/arena_example.txt";
        byte[] content = "Hello from Java FFM with Arena!\n".getBytes(StandardCharsets.UTF_8);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment file = (MemorySegment) fopen.invoke(
                    arena.allocateFrom(path), arena.allocateFrom("w"));
            if (file.equals(MemorySegment.NULL)) throw new RuntimeException("fopen failed: " + path);
            long written = (long) fwrite.invoke(
                    arena.allocateFrom(JAVA_BYTE, content), 1L, (long) content.length, file);
            fclose.invoke(file);
            System.out.println("Wrote " + written + " bytes to " + path);
        }
    }

    private static MethodHandle handle(Linker linker, String name, FunctionDescriptor desc) {
        return linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), desc);
    }

    public static final class Registration implements Feature {
        @Override
        public void duringSetup(DuringSetupAccess access) {
            RuntimeForeignAccess.registerForDowncall(
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            RuntimeForeignAccess.registerForDowncall(
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS));
            RuntimeForeignAccess.registerForDowncall(
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }
    }
}
