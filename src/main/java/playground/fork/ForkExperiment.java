package playground.fork;

import playground.ffm.Libc;

/**
 * Demonstrate the actual problem with fork() in a multi-threaded process.
 *
 * pthread fork(2) only duplicates the calling thread. The child inherits the
 * full memory snapshot, including any locks held by other threads at fork
 * time. Those locks are "held" in the child's memory but will never be
 * released, because the lock-holding threads do not exist in the child.
 *
 * The first test shows fork + Java code in the child with NO lock held by
 * anyone at fork time. It completes. The second test shows fork + Java code
 * in the child while a parent-side thread holds a synchronized lock. The
 * child tries to take the same lock and blocks forever.
 */
public final class ForkExperiment {
    private ForkExperiment() {}

    public static void run() {
        // Java-specific failure mode: the JVM has background threads (Reference
        // Handler, Signal Dispatcher, ...) that the user did not create, and
        // some of the runtime's invariants depend on them. After fork(), only
        // the calling thread survives. So invariants that the missing threads
        // were responsible for silently break in the child.
        //
        // The demo: in the child, create a WeakReference paired with a
        // ReferenceQueue, drop the referent, force a GC, then poll the queue.
        // In the parent the Reference Handler dispatches the cleared reference
        // into the queue. In the child the Reference Handler does not exist,
        // so the queue never receives anything and poll times out.

        // Control: run the same demo in the main process itself, no fork. The
        // Reference Handler is alive and dispatches the cleared reference into
        // the queue, so queue.remove returns quickly.
        System.out.println("=".repeat(60));
        System.out.println("Control: same code in the main process (no fork)");
        System.out.println("=".repeat(60));
        weakReferenceQueueDemo("Main");

        // After fork: only the calling thread is duplicated. Reference Handler
        // is gone, so the queue stays empty and queue.remove times out.
        ForkTester tester = new ForkTester(
                "Same code in a fork() child", 1, 8000);
        ForkResult cr = tester.runTest(() -> weakReferenceQueueDemo("Child"));

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Test Summary");
        System.out.println("=".repeat(60));
        summarize("In fork child", cr);
    }

    private static java.lang.ref.WeakReference<Object> makeRef(java.lang.ref.ReferenceQueue<Object> q) {
        // The referent is created and returned via the WeakReference only.
        // No strong reference escapes to the caller scope.
        return new java.lang.ref.WeakReference<>(new Object(), q);
    }

    private static void weakReferenceQueueDemo(String tag) {
        java.lang.ref.ReferenceQueue<Object> queue = new java.lang.ref.ReferenceQueue<>();
        java.lang.ref.WeakReference<Object> ref = makeRef(queue);

        // Force multiple GC cycles + allocation pressure to make sure the
        // referent is reclaimed and the WeakReference is moved to the
        // PendingList where Reference Handler picks it up.
        for (int i = 0; i < 5; i++) {
            byte[] pressure = new byte[8 * 1024 * 1024];
            pressure[0] = 1;
            System.gc();
        }
        System.out.println("[" + tag + "] gc done; ref.get()=" + ref.get() +
                "; polling queue (5s timeout)");

        long t0 = System.currentTimeMillis();
        java.lang.ref.Reference<?> dequeued;
        try {
            dequeued = queue.remove(5000);
        } catch (InterruptedException e) {
            dequeued = null;
        }
        long elapsed = System.currentTimeMillis() - t0;
        if (dequeued == null) {
            System.out.println("[" + tag + "] queue.remove timed out after " + elapsed + "ms (Reference Handler missing?)");
        } else {
            System.out.println("[" + tag + "] got reference back from queue after " + elapsed + "ms");
        }
    }

    private static void summarize(String name, ForkResult r) {
        System.out.println();
        System.out.println(name + ":");
        switch (r) {
            case ForkResult.Hang h -> {
                System.out.println("  HANG after " + h.durationMs() + "ms");
                long futex = h.snapshots().stream().filter(s -> s.hasFutexWait()).count();
                System.out.println("  snapshots with futex wait: " + futex + "/" + h.snapshots().size());
            }
            case ForkResult.Completed c -> System.out.println("  COMPLETED, exit=" + c.exitCode());
            case ForkResult.Error e -> System.out.println("  ERROR: " + e.message());
        }
    }

    private static int pidViaFfm() {
        try { return (int) Libc.GETPID.invoke(); }
        catch (Throwable t) { return -1; }
    }
}
