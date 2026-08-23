# GraalVM Native Image Playground

A playground for systems programming with Java + Panama FFM + GraalVM Native Image.

First, build the project.

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

1. SubstrateVM のプロセスには main 以外にランタイムスレッド (Signal Dispatcher, Reference Handler) とユーザスレッド (busy-worker) が存在する
2. `fork()` は呼び出しスレッドだけを子に複製する。他のスレッドは消える
3. しかし子のメモリにはスレッドレジストリのコピーが残っており、消えたスレッドが登録されたまま
4. 子でヒープ割当 → Serial GC が発動 → stop-the-world で全スレッドに safepoint 到達を要求
5. 死んだスレッドは応答できない → 1 ms の `clock_nanosleep` で永遠にポーリング

`fork` デモでは busy-worker がいないので daemon スレッドは全て wait 中 (safepoint 到達済み) で、子の GC は正常に通る。safepoint の外にいるスレッドが 1 つでもあると再現する。

### Contrast with `fork` demo

| | `fork` demo | `safepoint` demo |
| --- | --- | --- |
| Worker thread | なし | busy-worker (safepoint 外) |
| Child GC | 正常に完了 | 永遠にハング |
| Reference Handler | 消えるが safepoint 済み | 消えるが safepoint 済み |
| 原因 | Reference Handler 不在 (queue timeout) | スレッドレジストリの stale entry |
