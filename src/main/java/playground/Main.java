package playground;

import java.util.Arrays;

public final class Main {

    void main(String[] args) throws Throwable {
        String mode = args.length == 0 ? "fork" : args[0];
        switch (mode) {
            case "basic"   -> BasicDemo.run();
            case "syscall" -> SyscallDemo.run();
            case "fileio"  -> FileIoDemo.run();
            case "callc"   -> CallCDemo.run();
            case "threads" -> ThreadsDemo.run();
            case "fork"    -> ForkDemo.run();
            case "leak"    -> LeakDemo.run();
            case "safepoint" -> SafepointHangDemo.run();
            case "unshare" -> UnshareDemo.run(Arrays.copyOfRange(args, 1, args.length));
            default -> {
                System.err.println("Usage: playground <basic|syscall|fileio|callc|threads|fork|leak|safepoint|unshare> [namespace...]");
                System.exit(1);
            }
        }
    }
}
