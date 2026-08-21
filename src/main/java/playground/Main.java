package playground;

import playground.basic.BasicExample;
import playground.callc.CallCExample;
import playground.compare.CompareExample;
import playground.fileio.FileIoExample;
import playground.fork.ForkExperiment;
import playground.unshare.UnshareExperiment;

import java.util.Arrays;

public final class Main {

    private Main() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            ForkExperiment.run();
            return;
        }

        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "fork"    -> ForkExperiment.run();
            case "unshare" -> UnshareExperiment.run(rest);
            case "basic"   -> BasicExample.run();
            case "fileio"  -> FileIoExample.run();
            case "callc"   -> CallCExample.run();
            case "compare" -> CompareExample.run();
            case "threads" -> playground.threads.ThreadsExample.run();
            case "leak"    -> playground.leak.ArenaLeakExample.run();
            default -> {
                System.err.println("Error: Invalid argument '" + args[0] + "'");
                System.err.println();
                System.err.println("Usage: playground [MODE] [OPTIONS]");
                System.err.println();
                System.err.println("Modes:");
                System.err.println("  fork              Fork + post-fork-survival tests (default)");
                System.err.println("  unshare           Namespace isolation observation");
                System.err.println("  basic             Panama FFM basic example");
                System.err.println("  fileio            File I/O with Arena example");
                System.err.println("  callc             Call a hand-written C function");
                System.err.println("  compare           JNI vs JNA vs Panama FFM comparison");
                System.exit(1);
            }
        }
    }
}
