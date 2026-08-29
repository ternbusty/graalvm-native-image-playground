package playground;

import java.util.Arrays;

public final class Main {

    void main(String[] args) throws Throwable {
        String mode = args.length == 0 ? "fork" : args[0];
        switch (mode) {
            case "basic"     -> BasicDemo.run();
            case "syscall"   -> SyscallDemo.run();
            case "fileio"    -> FileIoDemo.run();
            case "callc"     -> CallCDemo.run();
            case "threads"   -> ThreadsDemo.run();
            case "fork"      -> ForkDemo.run();
            case "leak"      -> LeakDemo.run();
            case "safepoint" -> SafepointHangDemo.run();
            case "unshare"   -> UnshareDemo.run(Arrays.copyOfRange(args, 1, args.length));
            case "bench"     -> BenchDemo.run(Arrays.copyOfRange(args, 1, args.length));
            case "varargs"   -> VariadicDemo.run();
            default -> {
                System.err.println("Usage: playground <mode> [args...]");
                System.err.println();
                System.err.println("Modes:");
                System.err.println("  basic      Panama FFM getpid via Linker.defaultLookup");
                System.err.println("  syscall    Raw syscall(2) getpid");
                System.err.println("  fileio     File I/O with Arena");
                System.err.println("  callc      Call a custom C function via .so");
                System.err.println("  threads    Inspect runtime threads");
                System.err.println("  unshare    Namespace isolation test");
                System.err.println("  fork       Fork + WeakReference + ReferenceQueue");
                System.err.println("  leak       Fork + Arena.ofAuto() leak");
                System.err.println("  safepoint  Fork + safepoint hang (Serial GC)");
                System.err.println("  bench      FFM vs CInterop getpid benchmark [all|ci|cif|ffm|ffc]");
                System.err.println("  varargs    @CFunction variadic function test");
                System.exit(1);
            }
        }
    }
}
