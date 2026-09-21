// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import jdk.jfr.Event;
import jdk.jfr.Name;

/**
 * Actual JVM workload that blocks the cookie signal on known threads, then releases queued work.
 */
public final class SignalPressureWorkload {
    private static final int PRESSURE_THREADS = 2;
    private static volatile Object retained;
    private static volatile long result;

    @Name("jonoffcpu.IntegrationMarker")
    public static class Marker extends Event {
        public String phase;
    }

    private SignalPressureWorkload() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Expected signal, blocked milliseconds, recovery milliseconds, and metadata path");
        }
        int signal = Integer.parseInt(args[0]);
        long blockedMillis = Long.parseLong(args[1]);
        long recoveryMillis = Long.parseLong(args[2]);
        Path metadata = Path.of(args[3]);
        if (signal <= 0
                || blockedMillis < 250
                || blockedMillis > 30_000
                || recoveryMillis < 250
                || recoveryMillis > 30_000) {
            throw new IllegalArgumentException("Invalid pressure workload arguments");
        }
        if (SignalCaptureAgent.manifestPath() == null) {
            throw new IllegalStateException("Native agent did not start");
        }

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean releasePressure = new AtomicBoolean(false);
        CountDownLatch masksInstalled = new CountDownLatch(PRESSURE_THREADS + 1);
        CountDownLatch masksReleased = new CountDownLatch(PRESSURE_THREADS);
        Map<String, Long> tids = new ConcurrentHashMap<>();
        Map<String, AtomicLong> parks = new ConcurrentHashMap<>();
        List<Thread> masked = new ArrayList<>();
        for (int index = 0; index < PRESSURE_THREADS; index++) {
            String name = "jonoffcpu-pressure-" + index;
            masked.add(new Thread(
                    () -> pressureThread(
                            signal, name, running, releasePressure, masksInstalled, masksReleased, tids, parks),
                    name));
        }
        Thread auditBlocked = new Thread(
                () -> auditThread(signal, running, masksInstalled, tids, parks), "jonoffcpu-pressure-audit-blocked");
        masked.add(auditBlocked);

        Object monitor = new Object();
        Thread cpu = cpuWorker(running);
        Thread holder = monitorHolder(running, monitor);
        Thread waiter = monitorWaiter(running, monitor);
        List<Thread> all = new ArrayList<>(masked);
        all.add(cpu);
        all.add(holder);
        all.add(waiter);

        long masksReadyNanos = 0;
        long masksReleasedNanos = 0;
        long stoppedNanos = 0;
        Path manifest;
        try {
            for (Thread thread : all) thread.start();
            if (!masksInstalled.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for signal masks");
            }
            masksReadyNanos = System.nanoTime();
            marker("cookie-signal-blocked");
            Thread.sleep(blockedMillis);
            releasePressure.set(true);
            for (Thread thread : masked) LockSupport.unpark(thread);
            if (!masksReleased.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out unblocking pressure threads");
            }
            masksReleasedNanos = System.nanoTime();
            marker("cookie-signal-unblocked");
            Thread.sleep(recoveryMillis);
            marker("capture-stop");
            manifest = SignalCaptureAgent.stop();
            stoppedNanos = System.nanoTime();
            if (!Files.isRegularFile(manifest)) {
                throw new IllegalStateException("No final capture manifest");
            }
        } finally {
            running.set(false);
            releasePressure.set(true);
            for (Thread thread : all) {
                LockSupport.unpark(thread);
                thread.interrupt();
            }
            for (Thread thread : all) thread.join(10_000);
            for (Thread thread : all) {
                if (thread.isAlive())
                    throw new IllegalStateException("Worker failed to terminate: " + thread.getName());
            }
        }

        Map<String, Object> output = new TreeMap<>();
        output.put("schemaVersion", 1);
        output.put("signal", signal);
        output.put("blockedMillis", blockedMillis);
        output.put("recoveryMillis", recoveryMillis);
        output.put("pressureThreadCount", PRESSURE_THREADS);
        output.put("auditThreadRemainedBlockedThroughStop", true);
        output.put("masksReadyMonotonicNanos", Long.toUnsignedString(masksReadyNanos));
        output.put("masksReleasedMonotonicNanos", Long.toUnsignedString(masksReleasedNanos));
        output.put("captureStoppedMonotonicNanos", Long.toUnsignedString(stoppedNanos));
        output.put("targetTids", new TreeMap<>(tids));
        Map<String, Long> parkCounts = new TreeMap<>();
        parks.forEach((name, count) -> parkCounts.put(name, count.get()));
        output.put("parkCounts", parkCounts);
        Files.writeString(
                metadata, new GsonBuilder().setPrettyPrinting().create().toJson(output) + "\n");
        System.out.println("Signal pressure capture finalized: " + manifest);
    }

    private static void pressureThread(
            int signal,
            String name,
            AtomicBoolean running,
            AtomicBoolean releasePressure,
            CountDownLatch masksInstalled,
            CountDownLatch masksReleased,
            Map<String, Long> tids,
            Map<String, AtomicLong> parks) {
        AtomicLong count = new AtomicLong();
        parks.put(name, count);
        SignalMaskHelper.blocked(signal, true);
        tids.put(name, SignalMaskHelper.currentTid());
        masksInstalled.countDown();
        try {
            while (running.get() && !releasePressure.get()) park(count);
            SignalMaskHelper.blocked(signal, false);
            masksReleased.countDown();
            while (running.get()) park(count);
        } finally {
            SignalMaskHelper.blocked(signal, false);
        }
    }

    private static void auditThread(
            int signal,
            AtomicBoolean running,
            CountDownLatch masksInstalled,
            Map<String, Long> tids,
            Map<String, AtomicLong> parks) {
        String name = Thread.currentThread().getName();
        AtomicLong count = new AtomicLong();
        parks.put(name, count);
        SignalMaskHelper.blocked(signal, true);
        tids.put(name, SignalMaskHelper.currentTid());
        masksInstalled.countDown();
        try {
            while (running.get()) park(count);
        } finally {
            SignalMaskHelper.blocked(signal, false);
        }
    }

    private static void park(AtomicLong count) {
        count.incrementAndGet();
        LockSupport.parkNanos(5_000_000);
        Thread.interrupted();
    }

    private static Thread cpuWorker(AtomicBoolean running) {
        return new Thread(
                () -> {
                    long value = 1;
                    while (running.get()) {
                        for (int i = 0; i < 100_000; i++) value = value * 6364136223846793005L + 1;
                        result = value;
                        retained = new byte[8192];
                    }
                },
                "jonoffcpu-pressure-cpu-alloc");
    }

    private static Thread monitorHolder(AtomicBoolean running, Object monitor) {
        return new Thread(
                () -> {
                    while (running.get()) {
                        synchronized (monitor) {
                            LockSupport.parkNanos(10_000_000);
                        }
                        LockSupport.parkNanos(1_000_000);
                    }
                },
                "jonoffcpu-pressure-monitor-holder");
    }

    private static Thread monitorWaiter(AtomicBoolean running, Object monitor) {
        return new Thread(
                () -> {
                    while (running.get()) {
                        synchronized (monitor) {
                            retained = new byte[1024];
                        }
                        LockSupport.parkNanos(1_000_000);
                    }
                },
                "jonoffcpu-pressure-monitor-waiter");
    }

    private static void marker(String phase) {
        Marker marker = new Marker();
        marker.phase = phase;
        marker.commit();
    }
}
