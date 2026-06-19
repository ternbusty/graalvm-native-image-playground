package playground.leak;

import playground.ffm.Libc;
import playground.fork.ForkResult;
import playground.fork.ForkTester;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Arena.ofAuto() leak after fork().
 *
 * Arena.ofAuto() relies on the Cleaner thread to free its native memory once
 * the Arena object becomes unreachable. After fork(), the Cleaner thread does
 * not exist in the child, so the memory cannot be freed and there is no API
 * to free it manually (Arena.ofAuto().close() throws UnsupportedOperationException).
 *
 * The demo allocates 10 * 100MB via Arena.ofAuto(), drops the references,
 * forces GC multiple times, and measures VmRSS from /proc/self/status. In
 * the main process the Cleaner runs and RSS returns to near baseline. In a
 * fork() child the Cleaner is missing and RSS stays at +1GB.
 */
public final class ArenaLeakExample {
    private ArenaLeakExample() {}

    private static final int CHUNK_MB = 100;
    private static final int CHUNKS = 10;

    public static void run() {
        System.out.println("=".repeat(60));
        System.out.println("Control: Arena.ofAuto() in the main process");
        System.out.println("=".repeat(60));
        runLeakTest("Main");

        ForkTester tester = new ForkTester(
                "Arena.ofAuto() in a fork() child", 50, 10_000);
        ForkResult cr = tester.runTest(() -> runLeakTest("Child"));

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Test Summary");
        System.out.println("=".repeat(60));
        System.out.println("Child fork result: " + cr);
    }

    private static void runLeakTest(String tag) {
        long rssBefore = readVmRssKb();
        System.out.printf("[%s] RSS before allocation: %d KB%n", tag, rssBefore);

        // Allocate CHUNKS * CHUNK_MB via Arena.ofAuto(), then drop refs.
        for (int i = 0; i < CHUNKS; i++) {
            Arena auto = Arena.ofAuto();
            MemorySegment seg = auto.allocate((long) CHUNK_MB * 1024 * 1024);
            // Touch every page so memory is actually mapped, not just reserved.
            seg.fill((byte) 1);
        }
        long rssAfterAlloc = readVmRssKb();
        System.out.printf("[%s] RSS after allocating %d x %d MB: %d KB%n",
                tag, CHUNKS, CHUNK_MB, rssAfterAlloc);

        // Force GC + give Cleaner time to run.
        for (int i = 0; i < 5; i++) {
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        long rssAfterGc = readVmRssKb();
        System.out.printf("[%s] RSS after gc + sleep: %d KB%n", tag, rssAfterGc);

        long allocatedKb = (long) CHUNKS * CHUNK_MB * 1024;
        long peakDeltaKb = rssAfterAlloc - rssBefore;
        long finalDeltaKb = rssAfterGc - rssBefore;
        long freedKb = rssAfterAlloc - rssAfterGc;

        System.out.printf("[%s] allocated %d KB, peak delta %d KB, freed by Cleaner %d KB, final delta %d KB%n",
                tag, allocatedKb, peakDeltaKb, freedKb, finalDeltaKb);
        if (finalDeltaKb > allocatedKb / 2) {
            System.out.printf("[%s] LEAK: most of the %d KB was NOT freed%n", tag, allocatedKb);
        } else {
            System.out.printf("[%s] OK: most of the %d KB was freed by the Cleaner%n", tag, allocatedKb);
        }
    }

    private static long readVmRssKb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]);
                }
            }
        } catch (IOException ignored) {}
        return -1;
    }
}
