package playground.nativeimage;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.ValueLayout;

/**
 * GraalVM Native Image requires that every FunctionDescriptor used in a
 * downcall handle is known at build time. This Feature registers them.
 * Add a new line here when Libc gains a new FFM signature.
 */
public final class ForeignFeature implements Feature {

    @Override
    public void duringSetup(DuringSetupAccess access) {
        // ()->int                 getpid, fork
        reg(FunctionDescriptor.of(ValueLayout.JAVA_INT));
        // (int)->int              close, unshare
        reg(FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // (int,int)->int          kill
        reg(FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // (ptr,int,int)->int      open
        reg(FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // (int,ptr,long)->long    write
        reg(FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        // (ptr,ptr,long)->long    readlink
        reg(FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        // (int,ptr,int)->int      waitpid
        reg(FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        // (long*6)->long          syscall
        reg(FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG));
        // ()->ptr                 __errno_location
        reg(FunctionDescriptor.of(ValueLayout.ADDRESS));
        // (int)->ptr              strerror
        reg(FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        // void(int)               _exit
        reg(FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
        // void()                  say_hello (callc demo)
        reg(FunctionDescriptor.ofVoid());
    }

    private static void reg(FunctionDescriptor desc) {
        RuntimeForeignAccess.registerForDowncall(desc);
    }
}
