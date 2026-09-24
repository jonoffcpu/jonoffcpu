// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/** Workload that deliberately leaves capture finalization to the JVM shutdown hook. */
public final class NativeAgentShutdownWorkload {
    private static volatile Object retained;
    private static volatile long result;

    @Name("jonoffcpu.IntegrationMarker")
    public static class Marker extends Event {
        public String phase;
    }

    private NativeAgentShutdownWorkload() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected mode, duration seconds, and ready path");
        }
        String mode = args[0];
        long seconds = Long.parseLong(args[1]);
        if (!mode.equals("return")
                && !mode.equals("exit")
                && !mode.equals("sigterm")
                && !mode.equals("halt")
                && !mode.equals("wait")
                && !mode.equals("timeout")
                && !mode.equals("master-stop")) {
            throw new IllegalArgumentException(
                    "Mode must be return, exit, sigterm, halt, wait, timeout, or master-stop");
        }
        if (seconds < 1 || seconds > 60) {
            throw new IllegalArgumentException("Duration must be 1..60 seconds");
        }
        if (SignalCaptureAgent.manifestPath() == null) {
            throw new IllegalStateException("Native agent did not start");
        }

        AtomicBoolean running = new AtomicBoolean(true);
        Object monitor = new Object();
        Thread[] workers = workers(running, monitor);
        for (Thread worker : workers) worker.start();
        marker("workload-start");
        Files.writeString(Path.of(args[2]), "ready\n");

        if (mode.equals("sigterm") || mode.equals("wait")) {
            while (true) Thread.sleep(60_000);
        }
        if (mode.equals("halt")) {
            Thread.sleep(seconds * 1000);
            Runtime.getRuntime().halt(23);
        }
        if (mode.equals("timeout") || mode.equals("master-stop")) {
            exerciseApFirstStop(mode, seconds);
            marker("workload-end");
            stopWorkers(running, workers);
            return;
        }

        Thread.sleep(seconds * 1000);
        marker("workload-end");
        stopWorkers(running, workers);
        if (mode.equals("exit")) System.exit(0);
    }

    private static Thread[] workers(AtomicBoolean running, Object monitor) {
        Thread cpu = new Thread(
                () -> {
                    long value = 1;
                    while (running.get()) {
                        for (int i = 0; i < 100_000; i++) value = value * 6364136223846793005L + 1;
                        result = value;
                        retained = new byte[8192];
                    }
                },
                "jonoffcpu-shutdown-cpu-alloc");
        Thread sleeper = new Thread(
                () -> {
                    while (running.get()) LockSupport.parkNanos(1_000_000);
                },
                "jonoffcpu-shutdown-park");
        Thread holder = new Thread(
                () -> {
                    while (running.get()) {
                        synchronized (monitor) {
                            LockSupport.parkNanos(2_000_000);
                        }
                        LockSupport.parkNanos(100_000);
                    }
                },
                "jonoffcpu-shutdown-monitor-holder");
        Thread waiter = new Thread(
                () -> {
                    while (running.get()) {
                        synchronized (monitor) {
                            retained = new byte[1024];
                        }
                        LockSupport.parkNanos(100_000);
                    }
                },
                "jonoffcpu-shutdown-monitor-waiter");
        return new Thread[] {cpu, sleeper, holder, waiter};
    }

    private static void stopWorkers(AtomicBoolean running, Thread[] workers) throws InterruptedException {
        running.set(false);
        for (Thread worker : workers) {
            LockSupport.unpark(worker);
            worker.join(5000);
        }
        for (Thread worker : workers) {
            if (worker.isAlive()) throw new IllegalStateException("Worker failed to terminate: " + worker.getName());
        }
    }

    private static void marker(String phase) {
        Marker marker = new Marker();
        marker.phase = phase;
        marker.commit();
    }

    private static void exerciseApFirstStop(String mode, long seconds) throws Exception {
        Thread[] cutoff = new Thread[16];
        for (int i = 0; i < cutoff.length; i++) {
            cutoff[i] = new Thread(LockSupport::park, "jonoffcpu-post-cutoff-" + i);
            cutoff[i].start();
        }
        long parkedDeadline = System.nanoTime() + 5_000_000_000L;
        for (Thread thread : cutoff) {
            while (thread.getState() != Thread.State.WAITING) {
                if (System.nanoTime() >= parkedDeadline) {
                    throw new IllegalStateException("Cutoff thread did not park: " + thread.getName());
                }
                Thread.onSpinWait();
            }
        }
        Thread.sleep(mode.equals("master-stop") ? 1000 : 100);

        if (mode.equals("master-stop")) {
            Recording master = FlightRecorder.getFlightRecorder().getRecordings().stream()
                    .filter(recording -> recording.getState() == RecordingState.RUNNING)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No running JFR master recording"));
            master.stop();
        } else {
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            while (!NativeProfiler.execute("status,signalcookie").contains("signal-capture-v1 inactive")) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Timed AP capture did not stop");
                }
                Thread.sleep(1);
            }
        }

        for (Thread thread : cutoff) LockSupport.unpark(thread);
        for (Thread thread : cutoff) thread.join(5000);
        for (Thread thread : cutoff) {
            if (thread.isAlive()) throw new IllegalStateException("Cutoff thread failed to terminate");
        }
        Thread.sleep(1000);
    }
}
