package playground.fileio;

import playground.ffm.Libc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Equivalent of kotlin-native-playground's "fileio":
 * open + write + close via FFM with a confined Arena.
 *
 * In Kotlin/Native this used memScoped { }. In Java's Panama FFM the
 * equivalent is try-with-resources on Arena.ofConfined(). Allocated memory
 * is released when the try block exits.
 */
public final class FileIoExample {
    private FileIoExample() {}

    // open(2) flags. These are stable across Linux glibc/musl.
    private static final int O_WRONLY = 1;
    private static final int O_CREAT  = 0x40;
    private static final int O_TRUNC  = 0x200;
    private static final int MODE_644 = 0644;

    public static void run() {
        String path = "/tmp/arena_example.txt";
        String content = "Hello from Java FFM with Arena!\n";

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathPtr = arena.allocateFrom(path);
            int fd = (int) Libc.OPEN.invoke(pathPtr, O_WRONLY | O_CREAT | O_TRUNC, MODE_644);
            if (fd < 0) {
                System.err.println("open: " + Libc.strerror(Libc.errno()));
                return;
            }

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            MemorySegment buf = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, buf, ValueLayout.JAVA_BYTE, 0, bytes.length);

            long written = (long) Libc.WRITE.invoke(fd, buf, (long) bytes.length);
            if (written < 0) {
                System.err.println("write: " + Libc.strerror(Libc.errno()));
            } else {
                System.out.println("Wrote " + written + " bytes to " + path);
            }

            int rc = (int) Libc.CLOSE.invoke(fd);
            if (rc != 0) {
                System.err.println("close: " + Libc.strerror(Libc.errno()));
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
