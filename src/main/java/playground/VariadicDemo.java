package playground;

import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;

import java.util.List;

/**
 * Test whether @CFunction can call variadic C functions by declaring
 * them with a fixed number of arguments.
 *
 * The @CFunction Javadoc says "this annotation must not be used for
 * native functions that use variadic arguments", but the AAPCS64 ABI
 * (Linux aarch64) passes variadic arguments in registers just like
 * fixed arguments, so it works in practice.
 */
@CContext(VariadicDemo.Directives.class)
public final class VariadicDemo {

    public static final class Directives implements CContext.Directives {
        @Override
        public List<String> getHeaderFiles() {
            return List.of("<sys/prctl.h>", "<fcntl.h>", "<unistd.h>");
        }
    }

    // prctl(int option, ...) declared with 5 fixed args
    @CFunction("prctl")
    public static native int prctl(int option, long arg2, long arg3, long arg4, long arg5);

    // open(const char*, int, ...) declared with 3 fixed args
    @CFunction("open")
    public static native int openFd(CCharPointer pathname, int flags, int mode);

    @CFunction("close")
    public static native int closeFd(int fd);

    public static void run() {
        System.out.println("=== @CFunction variadic test ===\n");

        System.out.println("[prctl]");
        boolean prctl = testPrctl();
        System.out.println("  result: " + (prctl ? "PASS" : "FAIL") + "\n");

        System.out.println("[open]");
        boolean open = testOpen();
        System.out.println("  result: " + (open ? "PASS" : "FAIL") + "\n");

        System.out.println(prctl && open ? "ALL PASS" : "SOME FAILED");
    }

    /** Test prctl: set dumpable to 1, read it back, verify. */
    private static boolean testPrctl() {
        int PR_SET_DUMPABLE = 4;
        int PR_GET_DUMPABLE = 3;

        int rc = prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
        System.out.println("  prctl(PR_SET_DUMPABLE, 1) = " + rc + "  (expect 0)");

        int val = prctl(PR_GET_DUMPABLE, 0, 0, 0, 0);
        System.out.println("  prctl(PR_GET_DUMPABLE)    = " + val + "  (expect 1)");

        return rc == 0 && val == 1;
    }

    /** Test open: open /dev/null, verify fd is valid, close. */
    private static boolean testOpen() {
        int O_RDONLY = 0;
        try (CTypeConversion.CCharPointerHolder holder =
                     CTypeConversion.toCString("/dev/null")) {
            int fd = openFd(holder.get(), O_RDONLY, 0);
            System.out.println("  open(\"/dev/null\", O_RDONLY) = " + fd + "  (expect >= 0)");
            if (fd >= 0) {
                closeFd(fd);
                return true;
            }
            return false;
        }
    }

    private VariadicDemo() {}
}
