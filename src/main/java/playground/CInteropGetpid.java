package playground;

import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;

import java.util.List;

@CContext(CInteropGetpid.Directives.class)
public final class CInteropGetpid {

    public static final class Directives implements CContext.Directives {
        @Override
        public List<String> getHeaderFiles() {
            return List.of("<unistd.h>");
        }
    }

    @CFunction("getpid")
    public static native int getpid();

    @CFunction(value = "getpid", transition = CFunction.Transition.NO_TRANSITION)
    public static native int getpidFast();

    private CInteropGetpid() {}
}
