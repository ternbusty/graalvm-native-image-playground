package playground.basic;

import playground.ffm.Libc;

/**
 * Equivalent of kotlin-native-playground's "basic":
 * call getpid() two ways. Once via the libc symbol, once via the syscall(2)
 * trampoline with SYS_getpid (39 on x86_64, 172 on aarch64).
 */
public final class BasicExample {
    private BasicExample() {}

    // SYS_getpid number is architecture-dependent.
    // x86_64 = 39, aarch64 = 172, riscv64 = 172.
    private static final long SYS_GETPID;
    static {
        String arch = System.getProperty("os.arch", "");
        SYS_GETPID = switch (arch) {
            case "x86_64", "amd64" -> 39L;
            case "aarch64", "arm64" -> 172L;
            default -> throw new RuntimeException("unsupported arch for SYS_getpid: " + arch);
        };
    }

    public static void run() {
        try {
            int pid = (int) Libc.GETPID.invoke();
            System.out.println(pid);

            long pidFromSyscall = (long) Libc.SYSCALL.invoke(SYS_GETPID, 0L, 0L, 0L, 0L, 0L, 0L);
            System.out.println("pid = " + pidFromSyscall);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
