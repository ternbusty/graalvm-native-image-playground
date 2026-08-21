package playground.jna;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * JNA approach: define a Java interface that mirrors the C function signatures.
 * JNA resolves symbols and marshals arguments at runtime via libffi.
 * <p>
 * No C code needed, but:
 * <ul>
 *   <li>Type mapping is implicit and can be wrong (e.g. size_t mapped as int)</li>
 *   <li>Variadic functions (like syscall()) need special handling on aarch64
 *       because variadic args use the stack, not registers</li>
 *   <li>libffi dependency causes issues with GraalVM Native Image</li>
 * </ul>
 */
public interface JnaLibC extends Library {

    JnaLibC INSTANCE = Native.load("c", JnaLibC.class);

    int getpid();

    /**
     * gethostname(char *name, size_t len).
     * JNA auto-marshals byte[] as a pointer with copy-back.
     * size_t is 8 bytes on LP64 but we pass int here for demo simplicity.
     * In production, use NativeLong or a size_t type mapper.
     */
    int gethostname(byte[] name, int len);

    /**
     * syscall(long number, ...).
     * Declared without variadic args for the zero-arg SYS_gettid case.
     * On aarch64, the ABI for variadic functions differs from non-variadic,
     * so this may or may not work depending on JNA's calling convention.
     */
    long syscall(long number);
}
