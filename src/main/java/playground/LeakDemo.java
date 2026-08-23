package playground;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Arena.ofAuto() memory is freed by the Cleaner thread and close() throws,
 * so the Cleaner is the only way to release it. The Cleaner thread does not
 * exist in a fork() child, so the allocation leaks there. Measured via VmRSS
 * from /proc/self/status.
 */
public final class LeakDemo {
    private LeakDemo() {}

    private static final int CHUNK_MB = 100;
    private static final int CHUNKS = 10;

    public static void run() throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandle fork = handle(linker, "fork",
                FunctionDescriptor.of(JAVA_INT));
        MethodHandle waitpid = handle(linker, "waitpid",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        MethodHandle exit = handle(linker, "_exit",
                FunctionDescriptor.ofVoid(JAVA_INT));

        System.out.println("=".repeat(60));
        System.out.println("Control: Arena.ofAuto() in the main process");
        System.out.println("=".repeat(60));
        runLeakTest("Main");

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Arena.ofAuto() in a fork() child");
        System.out.println("=".repeat(60));
        int pid = (int) fork.invoke();
        if (pid == 0) {
            int code = 0;
            try {
                runLeakTest("Child");
            } catch (Throwable t) {
                System.err.println("[Child] exception: " + t);
                code = 1;
            }
            exit.invoke(code);
        }
        waitpid.invoke(pid, MemorySegment.NULL, 0);
        System.out.println("[Parent] child " + pid + " exited");
    }

    private static MethodHandle handle(Linker linker, String name, FunctionDescriptor desc) {
        return linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), desc);
    }

    private static void runLeakTest(String tag) {
        long rssBefore = readVmRssKb();
        System.out.printf("[%s] RSS before allocation: %d KB%n", tag, rssBefore);

        for (int i = 0; i < CHUNKS; i++) {
            Arena auto = Arena.ofAuto();
            MemorySegment seg = auto.allocate((long) CHUNK_MB * 1024 * 1024);
            // Touch every page so memory is actually mapped, not just reserved.
            seg.fill((byte) 1);
        }
        long rssAfterAlloc = readVmRssKb();
        System.out.printf("[%s] RSS after allocating %d x %d MB: %d KB%n",
                tag, CHUNKS, CHUNK_MB, rssAfterAlloc);

        // Force GC + give the Cleaner time to run.
        for (int i = 0; i < 5; i++) {
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        long rssAfterGc = readVmRssKb();
        System.out.printf("[%s] RSS after gc + sleep: %d KB%n", tag, rssAfterGc);

        long allocatedKb = (long) CHUNKS * CHUNK_MB * 1024;
        if (rssAfterGc - rssBefore > allocatedKb / 2) {
            System.out.printf("[%s] LEAK: most of the %d KB was NOT freed%n", tag, allocatedKb);
        } else {
            System.out.printf("[%s] OK: most of the %d KB was freed by the Cleaner%n", tag, allocatedKb);
        }
    }

    private static long readVmRssKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.trim().split("\\s+")[1]);
                }
            }
        } catch (IOException ignored) {}
        return -1;
    }

    public static final class Registration implements Feature {
        @Override
        public void duringSetup(DuringSetupAccess access) {
            RuntimeForeignAccess.registerForDowncall(FunctionDescriptor.of(JAVA_INT));
            RuntimeForeignAccess.registerForDowncall(
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
            RuntimeForeignAccess.registerForDowncall(FunctionDescriptor.ofVoid(JAVA_INT));
        }
    }
}
