package playground.fork;

import playground.ffm.Libc;
import playground.monitor.ProcessMonitor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Fork a child, run a body in it, monitor /proc/<pid> from the parent for
 * symptoms (futex wait, exit, timeout). Equivalent to kotlin-native-playground's
 * ForkTester, written against Panama FFM.
 */
public final class ForkTester {

    private final String testName;
    private final int pollIntervalMs;
    private final long timeoutMs;

    public ForkTester(String testName, int pollIntervalMs, long timeoutMs) {
        this.testName = testName;
        this.pollIntervalMs = pollIntervalMs;
        this.timeoutMs = timeoutMs;
    }

    public ForkResult runTest(Runnable childBody) {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Running test: " + testName);
        System.out.println("=".repeat(60));

        int pid;
        try {
            pid = (int) Libc.FORK.invoke();
        } catch (Throwable t) {
            return new ForkResult.Error("fork() threw: " + t.getMessage());
        }

        if (pid < 0) {
            return new ForkResult.Error("fork() returned " + pid);
        }

        if (pid == 0) {
            try {
                childBody.run();
                exitChild(0);
            } catch (Throwable t) {
                System.err.println("[Child] exception: " + t);
                exitChild(1);
            }
            return new ForkResult.Error("unreachable");
        }

        return monitorChild(pid);
    }

    private ForkResult monitorChild(int childPid) {
        System.out.println("[Parent] Monitoring child PID=" + childPid +
                " (poll=" + pollIntervalMs + "ms, timeout=" + timeoutMs + "ms)");

        ProcessMonitor monitor = new ProcessMonitor(childPid);
        List<ProcessMonitor.ProcessSnapshot> snapshots = new ArrayList<>();
        long start = System.currentTimeMillis();
        boolean futexAnnounced = false;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment statusSeg = arena.allocate(ValueLayout.JAVA_INT);
            while (true) {
                long elapsed = System.currentTimeMillis() - start;

                int waited = (int) Libc.WAITPID.invoke(childPid, statusSeg, 1 /* WNOHANG */);
                if (waited == childPid) {
                    int s = statusSeg.get(ValueLayout.JAVA_INT, 0);
                    int code = ((s & 0x7f) == 0) ? (s >> 8) & 0xff : -1;
                    System.out.println("[Parent] Child exited with code " + code + " after " + elapsed + "ms");
                    return new ForkResult.Completed(code);
                } else if (waited == -1) {
                    return new ForkResult.Error("waitpid: " + Libc.strerror(Libc.errno()));
                }

                try {
                    ProcessMonitor.ProcessSnapshot snap = monitor.captureSnapshot();
                    snapshots.add(snap);
                    if (!futexAnnounced && snap.hasFutexWait()) {
                        futexAnnounced = true;
                        System.out.println();
                        System.out.println("[Parent] futex wait detected at " + elapsed + "ms");
                        for (ProcessMonitor.ThreadInfo t : snap.getFutexWaitingThreads()) {
                            System.out.println("  TID=" + t.tid() + " state=" + t.state() +
                                    " syscall=" + t.syscall() + " wchan=" + t.wchan());
                        }
                    }
                } catch (Exception ignored) {
                }

                if (elapsed >= timeoutMs) {
                    System.out.println();
                    System.out.println("[Parent] timeout after " + timeoutMs + "ms, killing child");
                    Libc.KILL.invoke(childPid, 9 /* SIGKILL */);
                    Libc.WAITPID.invoke(childPid, statusSeg, 0);
                    return new ForkResult.Hang(elapsed, snapshots);
                }

                Thread.sleep(pollIntervalMs);
            }
        } catch (Throwable t) {
            return new ForkResult.Error("monitor: " + t.getMessage());
        }
    }

    private static void exitChild(int code) {
        try {
            Libc.EXIT_GROUP.invoke(code);
        } catch (Throwable t) {
            System.exit(code);
        }
    }
}
