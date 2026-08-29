package playground;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public final class FfmGetpid {

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MemorySegment GETPID_ADDR =
            LINKER.defaultLookup().find("getpid")
                    .or(() -> SymbolLookup.loaderLookup().find("getpid"))
                    .orElseThrow(() -> new UnsatisfiedLinkError("getpid"));

    private static final FunctionDescriptor DESC =
            FunctionDescriptor.of(ValueLayout.JAVA_INT);

    private static final MethodHandle MH_GETPID =
            LINKER.downcallHandle(GETPID_ADDR, DESC);

    private static final MethodHandle MH_GETPID_CRITICAL =
            LINKER.downcallHandle(GETPID_ADDR, DESC, Linker.Option.critical(false));

    public static int getpid() throws Throwable {
        return (int) MH_GETPID.invokeExact();
    }

    public static int getpidCritical() throws Throwable {
        return (int) MH_GETPID_CRITICAL.invokeExact();
    }

    private FfmGetpid() {}
}
