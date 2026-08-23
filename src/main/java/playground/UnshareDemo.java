package playground;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * unshare(2) into new namespaces and print /proc/self/task/<tid>/ns/ before
 * and after. Only the calling thread moves; the other threads stay in the
 * original namespaces. "user" fails because unshare(CLONE_NEWUSER) requires a
 * single-threaded process.
 */
public final class UnshareDemo {
    private UnshareDemo() {}

    private static final List<String> NS_TYPES = List.of("net", "mnt", "uts", "ipc", "cgroup", "user");

    public static void run(String[] args) throws Throwable {
        int flags = 0;
        for (String arg : args) {
            flags |= switch (arg) {
                case "net"    -> 0x40000000; // CLONE_NEWNET
                case "mnt"    -> 0x00020000; // CLONE_NEWNS
                case "uts"    -> 0x04000000; // CLONE_NEWUTS
                case "ipc"    -> 0x08000000; // CLONE_NEWIPC
                case "user"   -> 0x10000000; // CLONE_NEWUSER
                case "pid"    -> 0x20000000; // CLONE_NEWPID
                case "cgroup" -> 0x02000000; // CLONE_NEWCGROUP
                default -> throw new IllegalArgumentException("unknown namespace type: " + arg);
            };
        }

        Linker linker = Linker.nativeLinker();
        MethodHandle unshare = linker.downcallHandle(
                linker.defaultLookup().find("unshare").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        MethodHandle errnoLocation = linker.downcallHandle(
                linker.defaultLookup().find("__errno_location").orElseThrow(),
                FunctionDescriptor.of(ADDRESS));

        System.out.println("[Before unshare]");
        printNamespaceTable();

        System.out.println();
        System.out.println("[Calling unshare()...]");
        int rc = (int) unshare.invoke(flags);
        if (rc == -1) {
            int errno = ((MemorySegment) errnoLocation.invoke()).reinterpret(4).get(JAVA_INT, 0);
            System.out.println("unshare failed (errno: " + errno + ")");
            return;
        }
        System.out.println("unshare() succeeded");

        System.out.println();
        System.out.println("[After unshare]");
        printNamespaceTable();
    }

    private static void printNamespaceTable() throws IOException {
        List<Path> tasks;
        try (Stream<Path> s = Files.list(Path.of("/proc/self/task"))) {
            tasks = s.sorted().toList();
        }
        System.out.printf("%-8s", "TID");
        for (String ns : NS_TYPES) System.out.printf(" | %-20s", ns);
        System.out.println();
        System.out.println("-".repeat(150));
        for (Path task : tasks) {
            System.out.printf("%-8s", task.getFileName());
            for (String ns : NS_TYPES) System.out.printf(" | %-20s", readLink(task.resolve("ns/" + ns)));
            System.out.println();
        }
    }

    private static String readLink(Path p) {
        try {
            return Files.readSymbolicLink(p).toString();
        } catch (IOException e) {
            return "unknown";
        }
    }

    public static final class Registration implements Feature {
        @Override
        public void duringSetup(DuringSetupAccess access) {
            RuntimeForeignAccess.registerForDowncall(FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            RuntimeForeignAccess.registerForDowncall(FunctionDescriptor.of(ADDRESS));
        }
    }
}
