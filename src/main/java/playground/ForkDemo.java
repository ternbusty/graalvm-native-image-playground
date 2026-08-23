package playground;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * fork(2) only duplicates the calling thread, so the Reference Handler thread
 * does not exist in the child. A cleared WeakReference is then never dispatched
 * into its ReferenceQueue and queue.remove() times out.
 */
public final class ForkDemo {
    private ForkDemo() {}

    public static void run() throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandle fork = handle(linker, "fork",
                FunctionDescriptor.of(JAVA_INT));
        MethodHandle waitpid = handle(linker, "waitpid",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        MethodHandle exit = handle(linker, "_exit",
                FunctionDescriptor.ofVoid(JAVA_INT));

        System.out.println("=".repeat(60));
        System.out.println("Control: same code in the main process (no fork)");
        System.out.println("=".repeat(60));
        weakReferenceQueueDemo("Main");

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Same code in a fork() child");
        System.out.println("=".repeat(60));
        int pid = (int) fork.invoke();
        if (pid == 0) {
            int code = 0;
            try {
                weakReferenceQueueDemo("Child");
            } catch (Throwable t) {
                System.err.println("[Child] exception: " + t);
                code = 1;
            }
            exit.invoke(code);
        }
        waitpid.invoke(pid, MemorySegment.NULL, 0);
        System.out.println("[Parent] child " + pid + " exited");
    }

    private static MethodHandle handle(Linker linker, String name, FunctionDescriptor desc) {
        return linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), desc);
    }

    private static WeakReference<Object> makeRef(ReferenceQueue<Object> q) {
        // The referent is reachable only through the WeakReference.
        return new WeakReference<>(new Object(), q);
    }

    private static void weakReferenceQueueDemo(String tag) {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        WeakReference<Object> ref = makeRef(queue);

        // GC + allocation pressure so the referent is reclaimed and the
        // WeakReference reaches the pending list.
        for (int i = 0; i < 5; i++) {
            byte[] pressure = new byte[8 * 1024 * 1024];
            pressure[0] = 1;
            System.gc();
        }
        System.out.println("[" + tag + "] gc done; ref.get()=" + ref.get() +
                "; polling queue (5s timeout)");

        long t0 = System.currentTimeMillis();
        Reference<?> dequeued;
        try {
            dequeued = queue.remove(5000);
        } catch (InterruptedException e) {
            dequeued = null;
        }
        long elapsed = System.currentTimeMillis() - t0;
        if (dequeued == null) {
            System.out.println("[" + tag + "] queue.remove timed out after " + elapsed
                    + "ms (Reference Handler missing?)");
        } else {
            System.out.println("[" + tag + "] got reference back from queue after " + elapsed + "ms");
        }
    }

    public static final class Registration implements Feature {
        @Override
        public void duringSetup(DuringSetupAccess access) {
            RuntimeForeignAccess.registerForDowncall(FunctionDescriptor.of(JAVA_INT));
            RuntimeForeignAccess.registerForDowncall(
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
            RuntimeForeignAccess.registerForDowncall(FunctionDescriptor.ofVoid(JAVA_INT));
        }
    }
}
