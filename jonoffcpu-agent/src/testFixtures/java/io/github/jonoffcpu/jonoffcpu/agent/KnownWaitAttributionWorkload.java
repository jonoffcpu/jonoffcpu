// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Bounded known waits whose source intervals can be compared with signal-delivery stacks. */
public final class KnownWaitAttributionWorkload {
    private static final List<String> THREADS = List.of(
            "jonoffcpu-known-sleep",
            "jonoffcpu-known-park",
            "jonoffcpu-known-native-wait",
            "jonoffcpu-known-rapid-reblock");

    private KnownWaitAttributionWorkload() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected iterations, wait nanoseconds, and metadata path");
        }
        int iterations = Integer.parseInt(args[0]);
        long waitNanos = Long.parseLong(args[1]);
        Path metadataPath = Path.of(args[2]);
        if (iterations < 10 || iterations > 10_000 || waitNanos < 1_000_000 || waitNanos > 1_000_000_000) {
            throw new IllegalArgumentException("Known-wait arguments are outside bounded fixture limits");
        }
        if (SignalCaptureAgent.manifestPath() == null) {
            throw new IllegalStateException("Native agent did not start");
        }

        CountDownLatch ready = new CountDownLatch(THREADS.size());
        CountDownLatch start = new CountDownLatch(1);
        List<WaitRecord> records = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        Map<String, Long> targetTids = new java.util.concurrent.ConcurrentHashMap<>();
        List<Thread> workers = List.of(
                worker(
                        THREADS.get(0),
                        iterations,
                        ready,
                        start,
                        targetTids,
                        records,
                        failures,
                        (iteration, output) ->
                                timed(output, "sleep", "wait", iteration, waitNanos, () -> sleepNanos(waitNanos))),
                worker(
                        THREADS.get(1),
                        iterations,
                        ready,
                        start,
                        targetTids,
                        records,
                        failures,
                        (iteration, output) -> timed(
                                output, "park", "wait", iteration, waitNanos, () -> LockSupport.parkNanos(waitNanos))),
                worker(
                        THREADS.get(2),
                        iterations,
                        ready,
                        start,
                        targetTids,
                        records,
                        failures,
                        (iteration, output) -> timed(output, "native-wait", "wait", iteration, waitNanos, () -> {
                            int result = KnownWaitHelper.nativeWaitNanos(waitNanos);
                            if (result != 0) throw new IllegalStateException("clock_nanosleep failed: " + result);
                        })),
                worker(THREADS.get(3), iterations, ready, start, targetTids, records, failures, (iteration, output) -> {
                    timed(
                            output,
                            "rapid-reblock",
                            "primary",
                            iteration,
                            waitNanos,
                            () -> LockSupport.parkNanos(waitNanos));
                    timed(
                            output,
                            "rapid-reblock",
                            "reblock",
                            iteration,
                            waitNanos,
                            () -> LockSupport.parkNanos(waitNanos));
                }));

        for (Thread worker : workers) worker.start();
        if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Workers did not become ready");
        long workloadStart = KnownWaitHelper.monotonicNanos();
        start.countDown();
        for (Thread worker : workers) worker.join(TimeUnit.SECONDS.toMillis(120));
        for (Thread worker : workers) {
            if (worker.isAlive()) throw new IllegalStateException("Worker did not finish: " + worker.getName());
        }
        long workloadEnd = KnownWaitHelper.monotonicNanos();
        Path manifest = SignalCaptureAgent.stop();
        if (!Files.isRegularFile(manifest)) throw new IllegalStateException("No final capture manifest");
        if (!failures.isEmpty()) throw new IllegalStateException("Known-wait worker failed", failures.get(0));

        records.sort(Comparator.comparing(WaitRecord::threadName)
                .thenComparingInt(WaitRecord::iteration)
                .thenComparing(WaitRecord::phase));
        Map<String, Object> metadata = new TreeMap<>();
        metadata.put("schemaVersion", 1);
        metadata.put("clock", "Linux CLOCK_MONOTONIC");
        metadata.put("iterations", iterations);
        metadata.put("requestedWaitNanos", Long.toUnsignedString(waitNanos));
        metadata.put("workloadStartMonotonicNanos", Long.toUnsignedString(workloadStart));
        metadata.put("workloadEndMonotonicNanos", Long.toUnsignedString(workloadEnd));
        metadata.put("targetTids", new TreeMap<>(targetTids));
        metadata.put("operations", records);
        Files.writeString(metadataPath, FixtureJson.pretty(metadata));
        System.out.println("Known-wait capture finalized: " + manifest);
    }

    private static Thread worker(
            String name,
            int iterations,
            CountDownLatch ready,
            CountDownLatch start,
            Map<String, Long> tids,
            List<WaitRecord> records,
            List<Throwable> failures,
            WaitLoop loop) {
        return new Thread(
                () -> {
                    tids.put(name, KnownWaitHelper.currentTid());
                    ready.countDown();
                    try {
                        start.await();
                        for (int iteration = 0; iteration < iterations; iteration++) loop.waitOnce(iteration, records);
                    } catch (Throwable error) {
                        if (error instanceof InterruptedException)
                            Thread.currentThread().interrupt();
                        failures.add(error);
                    }
                },
                name);
    }

    private static void timed(
            List<WaitRecord> records,
            String waitKind,
            String phase,
            int iteration,
            long requestedNanos,
            CheckedRunnable wait)
            throws InterruptedException {
        long start = KnownWaitHelper.monotonicNanos();
        wait.run();
        long end = KnownWaitHelper.monotonicNanos();
        records.add(new WaitRecord(
                Thread.currentThread().getName(),
                KnownWaitHelper.currentTid(),
                waitKind,
                phase,
                iteration,
                Long.toUnsignedString(requestedNanos),
                Long.toUnsignedString(start),
                Long.toUnsignedString(start + requestedNanos),
                Long.toUnsignedString(end),
                Long.toUnsignedString(end - start)));
    }

    private static void sleepNanos(long nanos) throws InterruptedException {
        Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
    }

    private record WaitRecord(
            String threadName,
            long targetTid,
            String waitKind,
            String phase,
            int iteration,
            String requestedDurationNanos,
            String startMonotonicNanos,
            String requestedEndMonotonicNanos,
            String endMonotonicNanos,
            String actualDurationNanos) {}

    @FunctionalInterface
    private interface WaitLoop {
        void waitOnce(int iteration, List<WaitRecord> records) throws InterruptedException;
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws InterruptedException;
    }
}
