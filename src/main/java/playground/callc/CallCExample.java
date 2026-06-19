package playground.callc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Call a custom C function (say_hello) declared in src/main/c/sample/sample.c.
 *
 * Why we don't just use Linker.defaultLookup():
 *   We tried statically linking libsample.a into the native image with
 *   -Wl,--whole-archive and __attribute__((used, visibility("default"))),
 *   but native-image's post-link stripping drops the symbol anyway because
 *   nothing inside the compiled image references it.
 *
 * What works instead:
 *   Build sample.c as a shared library (libsample.so) and load it at
 *   runtime via SymbolLookup.libraryLookup(). This is the same pattern
 *   takoyaki uses for libseccomp.so.
 *
 * The .so is expected to sit next to the binary, or anywhere LD_LIBRARY_PATH
 * points to. The buildSample Gradle task writes it to build/sample/libsample.so.
 */
public final class CallCExample {
    private CallCExample() {}

    public static void run() {
        Path so = locateLibsample();
        if (so == null) {
            System.err.println("could not find libsample.so; expected next to the binary or in build/sample/");
            return;
        }

        SymbolLookup lib;
        try {
            lib = SymbolLookup.libraryLookup(so.toAbsolutePath().toString(), Arena.global());
        } catch (Throwable t) {
            System.err.println("libraryLookup(" + so + ") failed: " + t.getMessage());
            return;
        }

        MethodHandle sayHello = lib.find("say_hello")
                .map(addr -> Linker.nativeLinker().downcallHandle(addr, FunctionDescriptor.ofVoid()))
                .orElse(null);
        if (sayHello == null) {
            System.err.println("say_hello not found in " + so);
            return;
        }

        try {
            System.out.println("Calling C function...");
            sayHello.invoke();
            System.out.println("Back in Java.");
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Look for libsample.so next to the binary (/proc/self/exe) and in common dev paths. */
    private static Path locateLibsample() {
        String[] candidates = {
                "./libsample.so",
                "./build/sample/libsample.so",
                "build/sample/libsample.so",
                "/usr/local/lib/libsample.so",
        };
        for (String c : candidates) {
            Path p = Paths.get(c);
            if (Files.exists(p)) return p;
        }
        return null;
    }
}
