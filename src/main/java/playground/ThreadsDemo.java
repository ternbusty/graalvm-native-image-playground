package playground;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * List the threads of this process from /proc/self/task/ and from
 * Thread.getAllStackTraces(). Pure Java, no FFM needed.
 */
public final class ThreadsDemo {
    private ThreadsDemo() {}

    public static void run() throws Exception {
        System.out.println("PID = " + ProcessHandle.current().pid());

        List<Path> tasks;
        try (Stream<Path> s = Files.list(Path.of("/proc/self/task"))) {
            tasks = s.sorted().toList();
        }
        System.out.println("Threads: " + tasks.size());
        System.out.println();
        System.out.printf("%-8s | %-8s | %s%n", "TID", "state", "wchan");
        System.out.println("-".repeat(60));
        for (Path task : tasks) {
            System.out.printf("%-8s | %-8s | %s%n",
                    task.getFileName(), stateOf(task), read(task.resolve("wchan")));
        }

        System.out.println();
        System.out.println("Java-visible Threads (Thread.getAllStackTraces):");
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            System.out.printf("  name=%s daemon=%s state=%s%n", t.getName(), t.isDaemon(), t.getState());
        }

        // Linger so /proc/<pid>/task/ can be inspected from another shell.
        Thread.sleep(10_000);
    }

    private static String stateOf(Path task) {
        for (String line : read(task.resolve("status")).split("\n")) {
            if (line.startsWith("State:")) return line.substring(6).trim();
        }
        return "?";
    }

    private static String read(Path p) {
        try {
            return Files.readString(p).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
