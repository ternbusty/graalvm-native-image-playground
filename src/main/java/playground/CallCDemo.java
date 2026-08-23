package playground;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Call say_hello() from src/main/c/sample/sample.c. The function lives in
 * libsample.so loaded at runtime via SymbolLookup.libraryLookup, because a
 * statically linked symbol that is only referenced through FFM gets stripped
 * by native-image.
 */
public final class CallCDemo {
    private CallCDemo() {}

    public static void run() throws Throwable {
        Path so = Path.of("./libsample.so");
        if (!Files.exists(so)) so = Path.of("build/sample/libsample.so");
        SymbolLookup lib = SymbolLookup.libraryLookup(so.toAbsolutePath().toString(), Arena.global());

        MethodHandle sayHello = Linker.nativeLinker().downcallHandle(
                lib.find("say_hello").orElseThrow(),
                FunctionDescriptor.ofVoid());

        System.out.println("Calling C function...");
        sayHello.invoke();
        System.out.println("Back in Java.");
    }

    public static final class Registration implements Feature {
        @Override
        public void duringSetup(DuringSetupAccess access) {
            RuntimeForeignAccess.registerForDowncall(FunctionDescriptor.ofVoid());
        }
    }
}
