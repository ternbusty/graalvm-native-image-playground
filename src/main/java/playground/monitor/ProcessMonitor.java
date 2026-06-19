package playground.monitor;

import playground.ffm.Libc;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads /proc/<pid>/task/<tid>/ to inspect per-thread state.
 * Stays plain Java (java.nio.file) here. The takoyaki runtime itself avoids
 * java.nio.file to keep FileSystemProvider out of the image, but the playground
 * just wants to observe, so the simpler API is fine.
 */
public final class ProcessMonitor {

    public record NamespaceInfo(String net, String mnt, String uts, String ipc,
                                String cgroup, String user) {}

    public record ThreadInfo(int tid, String syscall, String wchan,
                             String state, NamespaceInfo namespaces) {}

    public record ProcessSnapshot(int pid, long timestamp, List<ThreadInfo> threads) {
        public boolean hasFutexWait() {
            for (ThreadInfo t : threads) {
                if (t.syscall.contains("202") || t.wchan.contains("futex")) return true;
            }
            return false;
        }

        public List<ThreadInfo> getFutexWaitingThreads() {
            List<ThreadInfo> r = new ArrayList<>();
            for (ThreadInfo t : threads) {
                if (t.syscall.contains("202") || t.wchan.contains("futex")) r.add(t);
            }
            return r;
        }
    }

    private final int pid;

    public ProcessMonitor(int pid) {
        this.pid = pid;
    }

    public ProcessSnapshot captureSnapshot() {
        List<Integer> tids = listThreads();
        List<ThreadInfo> infos = new ArrayList<>();
        for (int tid : tids) {
            infos.add(new ThreadInfo(
                    tid,
                    readProcFile("/proc/" + pid + "/task/" + tid + "/syscall"),
                    readProcFile("/proc/" + pid + "/task/" + tid + "/wchan"),
                    readState(tid),
                    readNamespaces(tid)));
        }
        return new ProcessSnapshot(pid, System.currentTimeMillis() / 1000, infos);
    }

    private List<Integer> listThreads() {
        Path taskDir = Path.of("/proc/" + pid + "/task");
        if (!Files.isDirectory(taskDir)) return Collections.emptyList();
        List<Integer> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(taskDir)) {
            s.forEach(p -> {
                try {
                    out.add(Integer.parseInt(p.getFileName().toString()));
                } catch (NumberFormatException ignored) {}
            });
        } catch (IOException ignored) {
        }
        Collections.sort(out);
        return out;
    }

    private String readProcFile(String path) {
        try {
            return Files.readString(Path.of(path)).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private String readState(int tid) {
        String status = readProcFile("/proc/" + pid + "/task/" + tid + "/status");
        for (String line : status.split("\n")) {
            if (line.startsWith("State:")) return line.substring(6).trim();
        }
        return "Unknown";
    }

    private NamespaceInfo readNamespaces(int tid) {
        return new NamespaceInfo(
                readNamespaceLink(tid, "net"),
                readNamespaceLink(tid, "mnt"),
                readNamespaceLink(tid, "uts"),
                readNamespaceLink(tid, "ipc"),
                readNamespaceLink(tid, "cgroup"),
                readNamespaceLink(tid, "user"));
    }

    /**
     * readlink(2) via Panama FFM. Java's Files.readSymbolicLink also works on
     * /proc/[pid]/ns/* on Linux, but we keep this path symmetric with the
     * Kotlin/Native version which uses readlink(2) directly.
     */
    private String readNamespaceLink(int tid, String nsType) {
        String path = "/proc/" + pid + "/task/" + tid + "/ns/" + nsType;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathPtr = arena.allocateFrom(path);
            MemorySegment buf = arena.allocate(256);
            long len = (long) Libc.READLINK.invoke(pathPtr, buf, 255L);
            if (len < 0) return "unknown";
            byte[] bytes = new byte[(int) len];
            MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, bytes, 0, (int) len);
            return new String(bytes);
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
