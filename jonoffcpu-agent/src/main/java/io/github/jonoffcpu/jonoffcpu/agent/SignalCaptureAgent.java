// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Process-lifetime owner for one generic signal capture. */
public final class SignalCaptureAgent {
    private static RuntimeController runtime;

    private SignalCaptureAgent() {}

    public static synchronized void premain(String arguments, Instrumentation instrumentation) throws Exception {
        start(arguments);
    }

    public static synchronized void agentmain(String arguments, Instrumentation instrumentation) throws Exception {
        start(arguments);
    }

    /** Starts one capture using YAML text or the path to a YAML configuration file. */
    public static synchronized void start(String arguments) throws Exception {
        start(AgentConfig.parse(arguments), false);
    }

    /** Entry point used by the native {@code -agentpath} VMInit bootstrap. */
    public static synchronized void nativeAgentStart(String arguments, String nativeLibrary) throws Exception {
        start(AgentConfig.parseNativeOptions(arguments, Path.of(nativeLibrary)), true);
    }

    /** Explicitly finalize the owned capture and return its manifest path. */
    public static Path stop() throws Exception {
        RuntimeController current;
        synchronized (SignalCaptureAgent.class) {
            current = Objects.requireNonNull(runtime, "JONOFFCPU signal agent is not running");
        }
        return current.stop(false);
    }

    public static synchronized Path manifestPath() {
        return runtime == null ? null : runtime.controller.manifestPath();
    }

    private static void start(AgentConfig config, boolean nativeLibraryAlreadyLoaded) throws Exception {
        if (runtime != null) throw new IllegalStateException("JONOFFCPU signal agent is already initialized");
        if (!nativeLibraryAlreadyLoaded) NativeCollector.load(config.nativeCollectorLibrary());
        SignalCaptureController controller = new SignalCaptureController(
                config,
                SignalCaptureController.liveProfiler(config.asyncProfilerLibrary()),
                SignalCaptureController.liveSource());
        RuntimeController created = new RuntimeController(config, controller);
        runtime = created;
        java.lang.Runtime.getRuntime().addShutdownHook(new Thread(created::shutdown, "jonoffcpu-signal-shutdown"));
        created.start();
    }

    private static final class RuntimeController {
        private final AgentConfig config;
        private final SignalCaptureController controller;
        private final ScheduledExecutorService worker;
        private boolean shuttingDown;

        private RuntimeController(AgentConfig config, SignalCaptureController controller) {
            this.config = config;
            this.controller = controller;
            this.worker = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "jonoffcpu-signal-controller");
                thread.setDaemon(true);
                return thread;
            });
        }

        private void start() throws Exception {
            Future<Path> startup = worker.submit(controller::start);
            worker.scheduleWithFixedDelay(this::pollProfiler, 0, 100, TimeUnit.MILLISECONDS);
            await(startup);
        }

        private void pollProfiler() {
            try {
                controller.pollProfiler();
            } catch (Exception failure) {
                System.err.println(
                        "JONOFFCPU signal capture ownership monitor could not finalize: " + failure.getMessage());
            }
        }

        private Path stop(boolean shutdown) throws Exception {
            Future<Path> result;
            synchronized (this) {
                if (shuttingDown && !shutdown) {
                    throw new IllegalStateException("JONOFFCPU signal agent shutdown has begun");
                }
                if (shutdown) shuttingDown = true;
                result = worker.submit(controller::stop);
            }
            if (!shutdown) return await(result);
            try {
                return result.get(config.shutdownTimeoutMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                // Do not cancel: the existing daemon controller thread retains native ownership
                // and may still finish safe detach/JFR finalization while shutdown continues.
                throw new IllegalStateException(
                        "Timed out waiting for capture shutdown; manifest remains incomplete", timeout);
            } catch (ExecutionException failure) {
                throw unwrap(failure);
            }
        }

        private void shutdown() {
            try {
                stop(true);
            } catch (Exception failure) {
                System.err.println("JONOFFCPU signal capture shutdown incomplete: " + failure.getMessage());
            }
        }

        private static <T> T await(Future<T> future) throws Exception {
            try {
                return future.get();
            } catch (ExecutionException failure) {
                throw unwrap(failure);
            }
        }

        private static Exception unwrap(ExecutionException failure) {
            Throwable cause = failure.getCause();
            return cause instanceof Exception exception ? exception : new IllegalStateException(cause);
        }
    }
}
