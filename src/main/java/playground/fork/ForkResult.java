package playground.fork;

import playground.monitor.ProcessMonitor;

import java.util.List;

public sealed interface ForkResult {
    record Hang(long durationMs, List<ProcessMonitor.ProcessSnapshot> snapshots) implements ForkResult {}
    record Completed(int exitCode) implements ForkResult {}
    record Error(String message) implements ForkResult {}
}
