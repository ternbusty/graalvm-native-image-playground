# GraalVM Native Image Playground

A playground for systems programming with Java + Panama FFM + GraalVM Native Image.

## Build

```bash
./gradlew nativeCompile
```

The binary is written to `build/native/nativeCompile/playground`. The `callc` mode also needs `build/sample/libsample.so`, which the same build produces.

Verified on Linux aarch64 only. Each demo is a single self-contained file under `src/main/java/playground/`, holding its own FFM downcall handles and its own build-time signature registration (the nested `Registration` class).

## Panama FFM Basic

```bash
./build/native/nativeCompile/playground basic
```

Calls `getpid(2)` through the libc symbol via `Linker.defaultLookup().find("getpid")`. Function symbols are resolved by name at runtime, so no header constants are needed.

### Expected Behavior

```
pid = 3839820
```

## Raw syscall

```bash
./build/native/nativeCompile/playground syscall
```

Calls `getpid(2)` by invoking `syscall(2)` directly instead of the libc wrapper. Unlike function symbols, syscall numbers are preprocessor macros with no runtime lookup, so `SYS_getpid` is hardcoded (172 on aarch64, 39 on x86_64). The `jextract` branch shows how to pull the number from `sys/syscall.h` at build time instead.

### Expected Behavior

```
pid = 3839820
```

## File I/O with Arena

```bash
./build/native/nativeCompile/playground fileio
```

Opens a short-lived `Arena.ofConfined()` and calls `fopen(3)` / `fwrite(3)` / `fclose(3)` through FFM downcalls. The stdio family takes the open mode as a string, so no `O_*` header constants are needed. The arena is closed automatically when the try-with-resources block exits.

### Expected Behavior

`/tmp/arena_example.txt` is created with the contents "Hello from Java FFM with Arena!".

```
Wrote 32 bytes to /tmp/arena_example.txt
```

## Call a Custom C Function

```bash
./build/native/nativeCompile/playground callc
```

Calls `say_hello()` defined in `src/main/c/sample/sample.c`. The `buildSample` Gradle task compiles it into `build/sample/libsample.so` and the binary loads it at runtime via `SymbolLookup.libraryLookup(...)`. The shared library must sit next to the binary or under `./build/sample/`.

### Expected Behavior

```
Calling C function...
Back in Java.
Hello from C!
```

The C `printf` and Java `System.out.println` hit different buffers, so the order of "Hello from C!" and "Back in Java." may vary.

## Inspect Runtime Threads

```bash
./build/native/nativeCompile/playground threads
```

Lists the threads of the running binary via `/proc/self/task/` and `Thread.getAllStackTraces()`. The process sleeps for ten seconds after printing so that another shell can also inspect `/proc/<pid>/task/`.

### Expected Behavior

The idle process holds the main thread plus the Signal Dispatcher and Reference Handler daemon threads.

```
PID = 3845722
Threads: 3

TID      | state    | wchan
------------------------------------------------------------
3845722  | R (running) | 0
3845724  | S (sleeping) | futex_wait_queue
3845725  | S (sleeping) | futex_wait_queue

Java-visible Threads (Thread.getAllStackTraces):
  name=main daemon=false state=RUNNABLE
  name=Signal Dispatcher daemon=true state=RUNNABLE
  name=Reference Handler daemon=true state=WAITING
```

## Namespace Isolation Test

```bash
sudo ./build/native/nativeCompile/playground unshare net
sudo ./build/native/nativeCompile/playground unshare user
sudo ./build/native/nativeCompile/playground unshare uts

sudo ./build/native/nativeCompile/playground unshare net uts ipc
sudo ./build/native/nativeCompile/playground unshare user mnt
```

### Available Namespace Types

`net`, `mnt`, `uts`, `ipc`, `user`, `pid`, `cgroup`.

### Expected Behavior

Specifying `user` fails. `CLONE_NEWUSER` requires the calling process to be single threaded ([unshare(2)](https://man7.org/linux/man-pages/man2/unshare.2.html)), but the binary holds Signal Dispatcher and Reference Handler threads as shown by the `threads` mode.

```
unshare failed (errno: 22)
```

Specifying `pid` succeeds but the calling process itself does not enter the new PID namespace. The first child it forks becomes PID 1 of that namespace.

Other namespaces succeed. Only the thread that called `unshare` moves into the new namespace. The other threads stay in the original namespace.

```
[Before unshare]
TID      | net                  | mnt                  | ...
------------------------------------------------------------
3839832  | net:[4026531840]     | mnt:[4026531841]     | ...
3839833  | net:[4026531840]     | mnt:[4026531841]     | ...
3839834  | net:[4026531840]     | mnt:[4026531841]     | ...

[Calling unshare()...]
unshare() succeeded

[After unshare]
TID      | net                  | mnt                  | ...
------------------------------------------------------------
3839832  | net:[4026532367]     | mnt:[4026531841]     | ...
3839833  | net:[4026531840]     | mnt:[4026531841]     | ...
3839834  | net:[4026531840]     | mnt:[4026531841]     | ...
```

## Fork + WeakReference + ReferenceQueue

```bash
./build/native/nativeCompile/playground fork
```

Runs the same `WeakReference` + `ReferenceQueue.remove()` snippet twice. Once in the main process. Once in a `fork()` child. In the main process the Reference Handler thread dispatches the cleared reference into the queue and `queue.remove()` returns immediately. In the child the Reference Handler does not exist because `fork(2)` only duplicates the calling thread, so the queue stays empty and `queue.remove(5000)` times out.

### Expected Behavior

```
============================================================
Control: same code in the main process (no fork)
============================================================
[Main] gc done; ref.get()=null; polling queue (5s timeout)
[Main] got reference back from queue after 0ms

============================================================
Same code in a fork() child
============================================================
[Child] gc done; ref.get()=null; polling queue (5s timeout)
[Child] queue.remove timed out after 5002ms (Reference Handler missing?)
[Parent] child 3839831 exited
```

`ref.get()` is null in the child as well, so Serial GC itself runs fine in the forked child. What breaks is the dispatch step that runs in the Reference Handler thread.

## Fork + Arena.ofAuto() Leak

```bash
./build/native/nativeCompile/playground leak
```

Allocates ten 100 MB chunks via `Arena.ofAuto()`, drops the references, forces GC several times, and reads `VmRSS` from `/proc/self/status`. `Arena.ofAuto()` is freed by the Cleaner thread and its `close()` throws `UnsupportedOperationException`, so the only way to release the memory is to let the Cleaner do it. In the main process the Cleaner runs and RSS returns near the baseline. In a `fork()` child the Cleaner thread does not exist and the gigabyte stays allocated.

### Expected Behavior

```
============================================================
Control: Arena.ofAuto() in the main process
============================================================
[Main] RSS before allocation: 9592 KB
[Main] RSS after allocating 10 x 100 MB: 1034980 KB
[Main] RSS after gc + sleep: 11736 KB
[Main] OK: most of the 1024000 KB was freed by the Cleaner

============================================================
Arena.ofAuto() in a fork() child
============================================================
[Child] RSS before allocation: 8204 KB
[Child] RSS after allocating 10 x 100 MB: 1033620 KB
[Child] RSS after gc + sleep: 1034704 KB
[Child] LEAK: most of the 1024000 KB was NOT freed
[Parent] child 3839856 exited
```

`ByteBuffer.allocateDirect()` and `MappedByteBuffer` go through the same Cleaner machinery and leak the same way after `fork()`.

## Fork + Safepoint Hang (Serial GC)

```bash
./build/native/nativeCompile/playground safepoint
```

Demonstrates how `fork(2)` from a SubstrateVM process can cause the child to hang forever. The `fork` and `leak` demos fork while daemon threads are parked (already at a safepoint), so the child's GC runs fine. This demo starts a busy-worker thread that stays off safepoint, then forks. The child's GC tries to stop the world, but the dead busy-worker thread in the copied thread registry never reaches a safepoint.

This is the root cause of the ARM CI idmap test hang in [takoyaki PR #69](https://github.com/ternbusty/takoyaki/pull/69).

GraalVM CE (Serial GC) only. Oracle GraalVM (G1 GC) does not reproduce.

### Expected Behavior

```
parent pid=3839820
threads before fork: 4
  main daemon=false
  Signal Dispatcher daemon=true
  Reference Handler daemon=true
  busy-worker daemon=true

Forking (up to 10 attempts)...
[child attempt 1] allocating...

*** HUNG on attempt 1 ***
child pid=3839823 is stuck in a 1 ms nanosleep loop.
Verify with:
  strace -fp 3839823

Expected strace output:
  clock_nanosleep(CLOCK_REALTIME, 0, {tv_sec=0, tv_nsec=1000000}, NULL) = 0
  (repeating forever)

The child's only thread is polling the safepoint
mechanism, waiting for dead threads (busy-worker,
Signal Dispatcher, Reference Handler) to reach a
safepoint. They never will.

Waiting 10s for inspection, then killing child...
```

### Why it hangs

1. A SubstrateVM process has runtime threads (Signal Dispatcher, Reference Handler) and user threads (busy-worker) in addition to main
2. `fork()` duplicates only the calling thread. The other threads vanish
3. The child's memory still contains a copy of the thread registry with the vanished threads still registered
4. Heap allocation in the child triggers Serial GC, which issues a stop-the-world request asking all threads to reach a safepoint
5. The dead threads cannot respond, so the GC polls in a 1 ms `clock_nanosleep` loop forever

In the `fork` demo there is no busy-worker, so all daemon threads are already parked (at a safepoint) and the child's GC completes normally. A single thread that is off-safepoint at the time of `fork()` is enough to reproduce the hang.

### Contrast with `fork` demo

| | `fork` demo | `safepoint` demo |
| --- | --- | --- |
| Worker thread | none | busy-worker (off safepoint) |
| Child GC | completes normally | hangs forever |
| Reference Handler | gone but was at safepoint | gone but was at safepoint |
| Root cause | Reference Handler absent (queue timeout) | stale thread registry entry |

## FFM vs CInterop Benchmark

```bash
./build/native/nativeCompile/playground bench          # all variants
./build/native/nativeCompile/playground bench ci       # CInterop only
./build/native/nativeCompile/playground bench ffm      # FFM only
BENCH_ITER=100000 ./build/native/nativeCompile/playground bench ffm  # fewer iterations
```

Compares the per-call overhead of FFM (Panama) and CInterop (`@CFunction`) on GraalVM Native Image. The target function is libc `getpid()`.

### Environment

- Ubuntu 24.04 LTS (aarch64), multipass VM
- GraalVM CE 25.2.4, Java 25 (`--enable-preview`)
- Native Image (`-O3`, `--no-fallback`)

### Results

Median of 10 rounds, 10,000,000 calls each.

| Method | ns/call | Ratio |
|---|---|---|
| CInterop (`TO_NATIVE`) | 147.9 | 1.0x |
| CInterop (`NO_TRANSITION`) | 148.1 | 1.0x |
| FFM (normal downcall) | 2201.2 | 14.9x |
| FFM (critical downcall) | 3263.6 | 22.1x |

FFM is roughly 15x slower than CInterop per call.

### perf stat comparison

| Metric | CInterop | FFM | Ratio |
|---|---|---|---|
| page faults | 26,332 | 884,362 | 33.6x |
| context switches | 1,276 | 79,502 | 62x |
| user time | 8.7s | 210.4s | 24x |
| sys time | 10.7s | 14.3s | 1.3x |

sys time is nearly the same. The gap is concentrated in user time (user-space processing). Page faults are 33x higher, indicating heavy memory allocation.

### Bottleneck analysis

strace confirmed that FFM does not issue mmap/munmap between `getpid()` calls. The overhead is in user-space FFM runtime, not in the kernel.

Disassembling the downcall stub `DowncallStubsHolder_downcall_J_I` revealed the root cause to be return-value autoboxing. The FFM downcall stub boxes the `int` return value into an `Integer` object on every call. `getpid()` returns a PID (typically above a few thousand), which falls outside the Integer cache (-128 to 127), so every call allocates from the TLAB.

```asm
; after receiving getpid() return value in w0
add w1, w0, #0x80       ; value + 128 (Integer cache offset)
cmp w1, #0x100          ; is it within [-128, 127]?
b.cs slow_path          ; out of range: allocate a new Integer on the heap

; slow_path
ldp x2, x1, [x28, #32] ; load TLAB top/end
add x3, x1, #0x8       ; new top = old + 8 bytes
...
b.cc GenScavengeAllocationSupport_slowNewInstance  ; GC if TLAB exhausted
```

CInterop's `@CFunction` stub returns `int` directly in a register with no boxing or GC.

The top perf profile functions confirm this hypothesis.

| Function | Share | Role |
|---|---|---|
| `__wake_up` | 24.5% | waking GC threads |
| `__pi_caches_clean_inval_pou` | 22.6% | instruction cache invalidation after GC moves objects |
| `folio_alloc` / `__alloc_pages` | ~5% | page allocation (TLAB refill, GC) |

## Error Detection Timing

Experiments on GraalVM Native Image comparing when binding errors are caught, depending on the method and whether a code generation tool is used. FFM has [jextract](https://jdk.java.net/jextract/), CInterop has [cinterop-gen](https://github.com/ternbusty/graalvm-cinterop-gen).

### Symbol name typo

Target function is `getpid()` (no arguments, returns `int`).

| Method | javac | Native Image build | Runtime |
|---|---|---|---|
| CInterop hand-written (`@CFunction("getpid_typo")`) | passes | ❌ `undefined reference` (linker) | not reached |
| CInterop + cinterop-gen | ❌ method not generated | not reached | not reached |
| FFM hand-written (`find("getpid_typo")`) | passes | passes | ❌ `UnsatisfiedLinkError` |
| FFM + jextract (`--include-function getpid_typo`) | ❌ method not generated | not reached | not reached |

CInterop resolves symbols through the native linker, so a nonexistent symbol fails at build time. FFM uses `SymbolLookup.find()` which returns `Optional` at runtime, so the build passes and the error surfaces as `UnsatisfiedLinkError` at runtime. With jextract or cinterop-gen, specifying a name absent from the header produces no binding, so the Java call site fails at javac. The symbol string inside generated code comes from the header, leaving no room for typos.

### Function signature mismatch

Tested with `abs()` (`int abs(int x)`).

| Method | arg `int` changed to `long` | arg omitted (1 to 0) |
|---|---|---|
| CInterop hand-written | ⚠️ undetected | ⚠️ undetected |
| FFM hand-written | ⚠️ undetected | ⚠️ undetected |
| CInterop + cinterop-gen | ❌ javac (type mismatch) | ❌ javac (argument count) |
| FFM + jextract | ❌ javac (type mismatch) | ❌ javac (argument count) |

Neither CInterop nor FFM detects signature mismatches when bindings are hand-written. The build succeeds, and at runtime the function silently returns wrong values instead of crashing.

Experimental results on aarch64 with `abs()`

```
Passing abs(0x1_FFFF_FFFF) as long
  Java intent: abs(8589934591) = 8589934591
  C actually reads: w0 = 0xFFFFFFFF = -1 (lower 32 bits only)
  Return value: 1
  → Wrong value returned with no error

Calling abs() with no arguments
  C actually reads: w0 = leftover return value from previous call (42)
  Return value: 42
  → Looks correct by coincidence, but just reading register garbage
```

With jextract or cinterop-gen, the generated method's type is determined by the C header, so a type mistake at the call site is caught by javac's normal type checking.

### Struct field mismatch

Tested with `struct timespec { time_t tv_sec; long tv_nsec; }` (both fields 8 bytes on aarch64). Changed `@CField("tv_sec")` type from `long` (correct) to `int` (wrong).

| Method | javac | Native Image build | Runtime |
|---|---|---|---|
| CInterop hand-written | passes | ❌ `Type int has a size of 4 bytes, but accessed C value has a size of 8 bytes` | not reached |
| FFM hand-written | passes | passes | ⚠️ undetected (value appears correct while it fits in 32 bits) |
| CInterop + cinterop-gen | ❌ javac (type mismatch) | not reached | not reached |
| FFM + jextract | ❌ javac (type mismatch) | not reached | not reached |

CInterop queries the C compiler at native-image build time to verify `@CStruct` field sizes. A Java `int` on a C field that is 8 bytes causes a build error. FFM does not verify layout correctness at all. Reading an 8-byte field with `ValueLayout.JAVA_INT` silently returns the lower 4 bytes.

## Variadic Function Support

```bash
./build/native/nativeCompile/playground varargs
```

The `@CFunction` Javadoc states that the annotation "must not be used for native functions that use variadic arguments". This demo tests whether it actually breaks on Linux aarch64.

### Test code

`prctl(int option, ...)` and `open(const char*, int, ...)` declared as `@CFunction` with a fixed number of arguments, built as native-image, and executed on an aarch64 VM.

```java
@CFunction("prctl")
public static native int prctl(int option, long arg2, long arg3, long arg4, long arg5);

@CFunction("open")
public static native int openFd(CCharPointer pathname, int flags, int mode);
```

### Results

```
[prctl]
  prctl(PR_SET_DUMPABLE, 1) = 0  (expect 0)
  prctl(PR_GET_DUMPABLE)    = 1  (expect 1)
  result: PASS

[open]
  open("/dev/null", O_RDONLY) = 3  (expect >= 0)
  result: PASS
```

Both pass on Linux aarch64.

### Why it works

In the Linux AAPCS64 ABI, variadic arguments are passed in registers (x0 through x7) just like fixed arguments. The callee saves argument registers to a register save area via `va_start` and reads them back with `va_arg`. Because the caller-side calling convention is identical for fixed and variadic arguments, the fixed-argument call code that `@CFunction` generates works correctly.

On Apple ARM64 (iOS/macOS), the ABI forces variadic arguments onto the stack, so the same code would break. The Javadoc warning is likely a safety measure against such platform differences.

### Comparison with FFM

FFM handles variadic functions through jextract's `makeInvoker` factory, where the caller specifies the types of the variadic arguments per call site. This approach works correctly regardless of platform.

```java
// prctl(int, ...) with 4 variadic longs
private static final NativeH.prctl PRCTL =
        NativeH.prctl.makeInvoker(NativeH.C_LONG, NativeH.C_LONG, NativeH.C_LONG, NativeH.C_LONG);
```

Declaring variadic functions as fixed-argument `@CFunction` works on Linux but is prohibited by the Javadoc, so behavior may change in future GraalVM versions. Generating these bindings with cinterop-gen is technically possible.

## Summary table

| Error type | CInterop hand-written | CInterop + cinterop-gen | FFM hand-written | FFM + jextract |
|---|---|---|---|---|
| Symbol name typo | native-image (linker) | javac | runtime | javac |
| Call-site typo | javac | javac | (N/A) | javac |
| Return type mismatch | ⚠️ undetected | javac | ⚠️ undetected | javac |
| Argument type/count mismatch | ⚠️ undetected | javac | ⚠️ undetected | javac |
| Struct field type mismatch | native-image (C query) | javac | ⚠️ undetected | javac |
| Variadic function call | ⚠️ Javadoc prohibits but works | ⚠️ same | ✅ supported | ✅ supported |

Comparing hand-written bindings, CInterop is safer than FFM because it verifies struct field sizes at build time. With code generation tools (jextract / cinterop-gen), all errors are caught at javac. Variadic function calls work with CInterop on Linux aarch64 in practice, but the Javadoc prohibition means platform and version portability is not guaranteed.

## Conclusion

The root cause of FFM's slowness is that the GraalVM Native Image FFM implementation boxes primitive return values inside the downcall stub. This is a consequence of going through a generic MethodHandle chain, and it is structurally disadvantaged compared to CInterop's approach of generating type-specialized direct call code.

The `@CFunction` Javadoc prohibits variadic function calls, but they work on Linux aarch64 when declared with a fixed number of arguments. The AAPCS64 ABI passes variadic arguments in registers just like fixed ones. Other ABIs (such as Apple ARM64) and future GraalVM versions may not behave the same way.

In real workloads, system calls like `mount`, `clone3`, and `pivot_root` take microseconds to milliseconds of kernel time, so whether the 2 microsecond per-call overhead matters depends on the use case.
