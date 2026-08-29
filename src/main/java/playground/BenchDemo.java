package playground;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Measures the per-call overhead of four native call mechanisms on
 * GraalVM Native Image, all calling libc getpid().
 *
 *   1. CInterop @CFunction  (TO_NATIVE)      -- thread transition
 *   2. CInterop @CFunction  (NO_TRANSITION)   -- no thread transition
 *   3. FFM downcallHandle                      -- thread transition
 *   4. FFM downcallHandle + critical(false)    -- no thread transition
 *
 * Sub-modes: all (default), ci, cif, ffm, ffc.
 */
public final class BenchDemo {

    public static final class Registration implements Feature {
        @Override
        public void duringSetup(DuringSetupAccess access) {
            FunctionDescriptor desc = FunctionDescriptor.of(ValueLayout.JAVA_INT);
            RuntimeForeignAccess.registerForDowncall(desc);
            RuntimeForeignAccess.registerForDowncall(desc, Linker.Option.critical(false));
        }
    }

    private static final int WARMUP;
    private static final int ITERATIONS;
    private static final int ROUNDS;

    static {
        String iter = System.getenv("BENCH_ITER");
        if (iter != null) {
            ITERATIONS = Integer.parseInt(iter);
            WARMUP = Math.min(1000, ITERATIONS);
            ROUNDS = 1;
        } else {
            WARMUP = 1_000_000;
            ITERATIONS = 10_000_000;
            ROUNDS = 10;
        }
    }

    public static void run(String[] args) throws Throwable {
        String mode = args.length > 0 ? args[0] : "all";

        System.out.println("=== FFM vs CInterop  getpid() Microbenchmark ===");
        System.out.printf("warmup=%,d  iterations=%,d  rounds=%d  mode=%s%n%n",
                WARMUP, ITERATIONS, ROUNDS, mode);

        warmup();

        switch (mode) {
            case "ci" -> {
                long[] ci = runRounds("CInterop  (TO_NATIVE)", BenchDemo::loopCInterop);
                summary("CInterop  (TO_NATIVE)", ci);
            }
            case "cif" -> {
                long[] cif = runRounds("CInterop  (NO_TRANSITION)", BenchDemo::loopCInteropFast);
                summary("CInterop  (NO_TRANSITION)", cif);
            }
            case "ffm" -> {
                long[] ffm = runRounds("FFM       (normal)", BenchDemo::loopFfm);
                summary("FFM       (normal)", ffm);
            }
            case "ffc" -> {
                long[] ffc = runRounds("FFM       (critical)", BenchDemo::loopFfmCritical);
                summary("FFM       (critical)", ffc);
            }
            default -> {
                long[] ci  = runRounds("CInterop  (TO_NATIVE)",     BenchDemo::loopCInterop);
                long[] cif = runRounds("CInterop  (NO_TRANSITION)", BenchDemo::loopCInteropFast);
                long[] ffm = runRounds("FFM       (normal)",        BenchDemo::loopFfm);
                long[] ffc = runRounds("FFM       (critical)",      BenchDemo::loopFfmCritical);
                System.out.println();
                System.out.println("--- Summary (ns/call, median of " + ROUNDS + " rounds) ---");
                summary("CInterop  (TO_NATIVE)",     ci);
                summary("CInterop  (NO_TRANSITION)", cif);
                summary("FFM       (normal)",        ffm);
                summary("FFM       (critical)",      ffc);
            }
        }
    }

    // benchmark loops

    private static int loopCInterop(int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            v = CInteropGetpid.getpid();
        }
        return v;
    }

    private static int loopCInteropFast(int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            v = CInteropGetpid.getpidFast();
        }
        return v;
    }

    private static int loopFfm(int n) throws Throwable {
        int v = 0;
        for (int i = 0; i < n; i++) {
            v = FfmGetpid.getpid();
        }
        return v;
    }

    private static int loopFfmCritical(int n) throws Throwable {
        int v = 0;
        for (int i = 0; i < n; i++) {
            v = FfmGetpid.getpidCritical();
        }
        return v;
    }

    // runner

    @FunctionalInterface
    private interface BenchLoop {
        int run(int n) throws Throwable;
    }

    private static void warmup() throws Throwable {
        loopCInterop(WARMUP);
        loopCInteropFast(WARMUP);
        loopFfm(WARMUP);
        loopFfmCritical(WARMUP);
        System.out.println("warmup done\n");
    }

    private static long[] runRounds(String label, BenchLoop loop) throws Throwable {
        long[] ns = new long[ROUNDS];
        System.out.println(label);
        for (int r = 0; r < ROUNDS; r++) {
            long t0 = System.nanoTime();
            int v = loop.run(ITERATIONS);
            long elapsed = System.nanoTime() - t0;
            ns[r] = elapsed;
            System.out.printf("  round %2d: %6.1f ns/call  (pid=%d)%n",
                    r + 1, (double) elapsed / ITERATIONS, v);
        }
        return ns;
    }

    private static void summary(String label, long[] ns) {
        long[] sorted = ns.clone();
        Arrays.sort(sorted);
        double median = (double) sorted[ROUNDS / 2] / ITERATIONS;
        double min    = (double) sorted[0] / ITERATIONS;
        double max    = (double) sorted[ROUNDS - 1] / ITERATIONS;
        System.out.printf("  %-30s  median=%5.1f  min=%5.1f  max=%5.1f%n",
                label, median, min, max);
    }

    private BenchDemo() {}
}
