package playground.unshare;

import playground.ffm.Libc;
import playground.monitor.ProcessMonitor;

public final class UnshareExperiment {
    private UnshareExperiment() {}

    public static void run(String[] args) {
        int flags = NamespaceFlags.parse(args);
        System.out.println();
        System.out.println("Namespace flags: " + NamespaceFlags.describe(flags));
        System.out.println();

        int myPid;
        try {
            myPid = (int) Libc.GETPID.invoke();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        ProcessMonitor monitor = new ProcessMonitor(myPid);

        System.out.println("\n[Before unshare]");
        printNamespaceTable(monitor.captureSnapshot());

        System.out.println("\n[Calling unshare()...]");
        int rc;
        try {
            rc = (int) Libc.UNSHARE.invoke(flags);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        if (rc == -1) {
            int err = Libc.errno();
            System.out.println("unshare failed: " + Libc.strerror(err) + " (errno: " + err + ")");
            return;
        }

        System.out.println("unshare() succeeded");
        System.out.println("\n[After unshare]");
        printNamespaceTable(monitor.captureSnapshot());

        System.out.println("Namespace test completed");
    }

    private static void printNamespaceTable(ProcessMonitor.ProcessSnapshot snapshot) {
        if (snapshot.threads().isEmpty()) {
            System.out.println("No threads found");
            return;
        }
        System.out.println("\nTID      | net                  | mnt                  | uts                  | ipc                  | cgroup               | user");
        System.out.println("-".repeat(150));
        for (ProcessMonitor.ThreadInfo t : snapshot.threads()) {
            ProcessMonitor.NamespaceInfo ns = t.namespaces();
            System.out.println(
                    padRight(String.valueOf(t.tid()), 8) + " | " +
                            padRight(truncate(ns.net(), 20), 20) + " | " +
                            padRight(truncate(ns.mnt(), 20), 20) + " | " +
                            padRight(truncate(ns.uts(), 20), 20) + " | " +
                            padRight(truncate(ns.ipc(), 20), 20) + " | " +
                            padRight(truncate(ns.cgroup(), 20), 20) + " | " +
                            truncate(ns.user(), 20));
        }
    }

    private static String truncate(String s, int max) {
        if (s.length() > max) return s.substring(0, max - 3) + "...";
        return s;
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }
}
