# GraalVM Native Image Playground

A playground for systems programming with Java + Panama FFM + GraalVM Native Image.

First, build the project.

```bash
./gradlew nativeCompile
```

The binary is written to `build/native/nativeCompile/playground`. The `callc` mode also needs `build/sample/libsample.so`, which the same build produces.

Verified on Linux aarch64 only.

## Panama FFM Basic

```bash
./build/native/nativeCompile/playground basic
```

Calls `getpid(2)` two ways. The first goes through the libc symbol via `Linker.defaultLookup().find("getpid")`. The second invokes `syscall(SYS_getpid)` directly. The SYS_getpid number is architecture dependent (x86_64 uses 39, aarch64 uses 172).

### Expected Behavior

```
3839820
pid = 3839820
```

## File I/O with Arena

```bash
./build/native/nativeCompile/playground fileio
```

Opens a short-lived `Arena.ofConfined()` and calls `open(2)` / `write(2)` / `close(2)` through FFM downcalls. The arena is closed automatically when the try-with-resources block exits.

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
Hello from C!
Back in Java.
```

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
unshare failed: Invalid argument (errno: 22)
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
Running test: Same code in a fork() child
============================================================
[Child] gc done; ref.get()=null; polling queue (5s timeout)
[Child] queue.remove timed out after 5002ms (Reference Handler missing?)
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
[Main] allocated 1024000 KB, peak delta 1025388 KB, freed by Cleaner 1023244 KB, final delta 2144 KB
[Main] OK: most of the 1024000 KB was freed by the Cleaner

============================================================
Running test: Arena.ofAuto() in a fork() child
============================================================
[Child] RSS before allocation: 8204 KB
[Child] RSS after allocating 10 x 100 MB: 1033620 KB
[Child] RSS after gc + sleep: 1034704 KB
[Child] allocated 1024000 KB, peak delta 1025416 KB, freed by Cleaner -1084 KB, final delta 1026500 KB
[Child] LEAK: most of the 1024000 KB was NOT freed
```

`ByteBuffer.allocateDirect()` and `MappedByteBuffer` go through the same Cleaner machinery and leak the same way after `fork()`.
