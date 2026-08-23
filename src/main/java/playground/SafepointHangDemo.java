package playground;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * fork(2) from a SubstrateVM process where a thread is actively running
 * Java code (not at a safepoint) leaves a stale entry in the child's
 * thread registry.  When the child allocates and GC fires, the safepoint
 * synchronisation polls the dead thread forever with a 1 ms nanosleep.
 *
 * <p>This reproduces the ARM CI hang observed in takoyaki PR #69.
 *
 * <p>Contrast with {@link ForkDemo}: that demo forks while the daemon
 * threads are parked (already at a safepoint), so the child's GC runs
 * fine.  Here we keep a busy-worker spinning through compiled Java code
 * so the fork snapshot captures it between safepoint checks.
 *
 * <p>The hang is specific to GraalVM CE (Serial GC).  Oracle GraalVM
 * (G1 GC) does not reproduce it.
 */
public final class SafepointHangDemo {
    private SafepointHangDemo() {}

    static volatile boolean keepRunning = true;

    public static void run() throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandle fork = handle(linker, "fork",
                FunctionDescriptor.of(JAVA_INT));
        MethodHandle getpid = handle(linker, "getpid",
                FunctionDescriptor.of(JAVA_INT));
        MethodHandle waitpid = handle(linker, "waitpid",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        MethodHandle exit = handle(linker, "_exit",
                FunctionDescriptor.ofVoid(JAVA_INT));

        int myPid = (int) getpid.invoke();
        System.out.println("parent pid=" + myPid);

        // Start a thread that stays off safepoint.  Compiled native-image
        // code inserts safepoint checks at loop back-edges, but the thread
        // spends most of its time between checks.
        Thread busy = new Thread(() -> {
            long sum = 0;
            while (keepRunning) {
                for (int i = 0; i < 1_000_000; i++) sum += i;
            }
        }, "busy-worker");
        busy.setDaemon(true);
        busy.start();
        Thread.sleep(100);

        int threadCount = Thread.getAllStackTraces().size();
        System.out.println("threads before fork: " + threadCount);
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            System.out.println("  " + t.getName() + " daemon=" + t.isDaemon());
        }

        // Fork up to 10 times.  Most attempts catch busy-worker between
        // safepoint checks and hang on the first try.
        System.out.println();
        System.out.println("Forking (up to 10 attempts)...");
        System.out.flush();

        for (int attempt = 1; attempt <= 10; attempt++) {
            int pid = (int) fork.invoke();

            if (pid == 0) {
                // ---- child ----
                System.out.println("[child attempt " + attempt + "] allocating...");
                System.out.flush();

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 100000; i++) {
                    sb.append("x".repeat(1000));
                }
                // Not reached when the hang reproduces.
                System.out.println("[child] done (length=" + sb.length() + ")");
                System.out.flush();
                exit.invoke(0);
            }

            // ---- parent ----
            // Poll for up to 3 seconds to see if the child exits or hangs.
            long deadline = System.currentTimeMillis() + 3000;
            boolean alive = true;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Files.readString(Path.of("/proc/" + pid + "/stat"));
                    Thread.sleep(100);
                } catch (java.io.IOException e) {
                    alive = false;
                    break;
                }
            }

            if (alive) {
                System.out.println();
                System.out.println("*** HUNG on attempt " + attempt + " ***");
                System.out.println("child pid=" + pid + " is stuck in a 1 ms nanosleep loop.");
                System.out.println("Verify with:");
                System.out.println("  strace -fp " + pid);
                System.out.println();
                System.out.println("Expected strace output:");
                System.out.println("  clock_nanosleep(CLOCK_REALTIME, 0, "
                        + "{tv_sec=0, tv_nsec=1000000}, NULL) = 0");
                System.out.println("  (repeating forever)");
                System.out.println();
                System.out.println("The child's only thread is polling the safepoint");
                System.out.println("mechanism, waiting for dead threads (busy-worker,");
                System.out.println("Signal Dispatcher, Reference Handler) to reach a");
                System.out.println("safepoint. They never will.");

                // Let the user run strace, then clean up.
                System.out.println();
                System.out.println("Waiting 10s for inspection, then killing child...");
                Thread.sleep(10000);
                // kill(pid, SIGKILL)
                new ProcessBuilder("kill", "-9", String.valueOf(pid)).start().waitFor();
                keepRunning = false;
                return;
            }

            System.out.println("attempt " + attempt + ": child exited normally");
        }

        keepRunning = false;
        System.out.println("No hang reproduced in 10 attempts.");
        System.out.println("(Daemon threads may have been parked at safepoints.)");
    }

    private static MethodHandle handle(Linker linker, String name, FunctionDescriptor desc) {
        return linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), desc);
    }

    public static final class Registration implements Feature {
        @Override
        public void duringSetup(DuringSetupAccess access) {
            // fork() → int, getpid() → int
            RuntimeForeignAccess.registerForDowncall(FunctionDescriptor.of(JAVA_INT));
            // waitpid(int, int*, int) → int
            RuntimeForeignAccess.registerForDowncall(
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
            // _exit(int) → void
            RuntimeForeignAccess.registerForDowncall(FunctionDescriptor.ofVoid(JAVA_INT));
        }
    }
}
