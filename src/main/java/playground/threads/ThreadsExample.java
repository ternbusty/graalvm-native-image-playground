package playground.threads;

import playground.ffm.Libc;
import playground.monitor.ProcessMonitor;

/**
 * Print the binary's own thread count and the per-thread state captured from
 * /proc/self/task/. Useful for answering "how many threads does a GraalVM
 * Native Image process have at idle?".
 */
public final class ThreadsExample {
    private ThreadsExample() {}

    public static void run() {
        int pid;
        try {
            pid = (int) Libc.GETPID.invoke();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        System.out.println("PID = " + pid);

        ProcessMonitor monitor = new ProcessMonitor(pid);
        ProcessMonitor.ProcessSnapshot snap = monitor.captureSnapshot();

        System.out.println("Threads: " + snap.threads().size());
        System.out.println();
        System.out.printf("%-8s | %-8s | %s%n", "TID", "state", "wchan");
        System.out.println("-".repeat(60));
        for (ProcessMonitor.ThreadInfo t : snap.threads()) {
            System.out.printf("%-8d | %-8s | %s%n",
                    t.tid(), t.state(), t.wchan());
        }

        // Also dump Java-visible Thread info if any of the background threads
        // are Java threads (e.g. SubstrateVM Reference handler).
        System.out.println();
        System.out.println("Java-visible Threads (Thread.getAllStackTraces):");
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            System.out.printf("  name=%s daemon=%s state=%s%n",
                    t.getName(), t.isDaemon(), t.getState());
        }

        // Linger so /proc/<pid>/task/<tid>/comm and /stack can be inspected
        // from another shell. Comment this out if you don't need it.
        try {
            Thread.sleep(10_000);
        } catch (InterruptedException ignored) {}
    }
}
