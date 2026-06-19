package playground.unshare;

import java.util.ArrayList;
import java.util.List;

public final class NamespaceFlags {
    private NamespaceFlags() {}

    // From <linux/sched.h>. Stable across kernel versions.
    public static final int CLONE_NEWNS     = 0x00020000; // mnt
    public static final int CLONE_NEWUTS    = 0x04000000;
    public static final int CLONE_NEWIPC    = 0x08000000;
    public static final int CLONE_NEWUSER   = 0x10000000;
    public static final int CLONE_NEWPID    = 0x20000000;
    public static final int CLONE_NEWNET    = 0x40000000;
    public static final int CLONE_NEWCGROUP = 0x02000000;

    public static int parse(String[] args) {
        int flags = 0;
        for (String arg : args) {
            flags |= parseOne(arg);
        }
        return flags;
    }

    private static int parseOne(String arg) {
        return switch (arg.toLowerCase()) {
            case "net"    -> CLONE_NEWNET;
            case "mnt"    -> CLONE_NEWNS;
            case "uts"    -> CLONE_NEWUTS;
            case "ipc"    -> CLONE_NEWIPC;
            case "user"   -> CLONE_NEWUSER;
            case "pid"    -> CLONE_NEWPID;
            case "cgroup" -> CLONE_NEWCGROUP;
            default -> {
                System.err.println("Error: Unknown namespace type '" + arg + "'");
                System.exit(1);
                yield 0;
            }
        };
    }

    public static String describe(int flags) {
        List<String> parts = new ArrayList<>();
        if ((flags & CLONE_NEWNET) != 0)    parts.add("CLONE_NEWNET");
        if ((flags & CLONE_NEWNS) != 0)     parts.add("CLONE_NEWNS");
        if ((flags & CLONE_NEWUTS) != 0)    parts.add("CLONE_NEWUTS");
        if ((flags & CLONE_NEWIPC) != 0)    parts.add("CLONE_NEWIPC");
        if ((flags & CLONE_NEWUSER) != 0)   parts.add("CLONE_NEWUSER");
        if ((flags & CLONE_NEWPID) != 0)    parts.add("CLONE_NEWPID");
        if ((flags & CLONE_NEWCGROUP) != 0) parts.add("CLONE_NEWCGROUP");
        return parts.isEmpty() ? "0" : String.join(" | ", parts);
    }
}
