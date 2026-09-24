// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import jdk.jfr.Event;
import jdk.jfr.Name;

/**
 * Finite CPU/allocation/park/monitor workload for an opt-in, actual native-agent integration run.
 */
public final class NativeAgentWorkload {
    private static volatile Object retained;
    private static volatile long result;

    @Name("jonoffcpu.IntegrationMarker")
    public static class Marker extends Event {
        public String phase;
    }

    private NativeAgentWorkload() {}

    public static void main(String[] args) throws Exception {
        long seconds = args.length == 0 ? 5 : Long.parseLong(args[0]);
        if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("Duration must be 1..60 seconds");
        if (SignalCaptureAgent.manifestPath() == null) throw new IllegalStateException("Native agent did not start");
        Marker begin = new Marker();
        begin.phase = "workload-start";
        begin.commit();
        AtomicBoolean running = new AtomicBoolean(true);
        Object monitor = new Object();
        Thread cpu = new Thread(
                () -> {
                    long value = 1;
                    while (running.get()) {
                        for (int i = 0; i < 100_000; i++) value = value * 6364136223846793005L + 1;
                        result = value;
                        retained = new byte[8192];
                    }
                },
                "jonoffcpu-fixture-cpu-alloc");
        Thread sleeper = new Thread(
                () -> {
                    while (running.get()) LockSupport.parkNanos(1_000_000);
                },
                "jonoffcpu-fixture-park");
        Thread holder = new Thread(
                () -> {
                    while (running.get()) {
                        synchronized (monitor) {
                            LockSupport.parkNanos(2_000_000);
                        }
                        LockSupport.parkNanos(100_000);
                    }
                },
                "jonoffcpu-fixture-monitor-holder");
        Thread waiter = new Thread(
                () -> {
                    while (running.get()) {
                        synchronized (monitor) {
                            retained = new byte[1024];
                        }
                        LockSupport.parkNanos(100_000);
                    }
                },
                "jonoffcpu-fixture-monitor-waiter");
        Thread[] workers = {cpu, sleeper, holder, waiter};
        try {
            for (Thread thread : workers) thread.start();
            Thread.sleep(seconds * 1000);
            Marker end = new Marker();
            end.phase = "workload-end";
            end.commit();
            // Keep target threads alive until source detach and signal drain finish.
            Path manifest = SignalCaptureAgent.stop();
            if (!Files.isRegularFile(manifest)) throw new IllegalStateException("No final capture manifest");
            System.out.println("Native agent capture finalized: " + manifest);
        } finally {
            running.set(false);
            for (Thread thread : workers) {
                LockSupport.unpark(thread);
                thread.join(5000);
            }
            for (Thread thread : workers)
                if (thread.isAlive()) throw new IllegalStateException("Worker failed to terminate");
        }
    }
}
