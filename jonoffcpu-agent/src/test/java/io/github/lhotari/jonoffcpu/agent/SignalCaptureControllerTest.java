// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

public final class SignalCaptureControllerTest {
    @Name("profiler.SignalCapture")
    @StackTrace(false)
    static final class Capture extends Event {
        int schemaVersion = 1;
        String sessionId;
        long captureEpoch;
        int signal;
        String signalDelivery = "queued";
        long processId = ProcessHandle.current().pid();
        long processStartTimeMillis = 1234;
    }

    @Name("profiler.SignalCaptureStats")
    @StackTrace(false)
    static final class Stats extends Event {
        int schemaVersion = 1;
        String sessionId;
        long captureEpoch;
        long admittedSignals;
        long invalidSignalCode;
        long zeroCookie;
        long zeroSequence;
        long staleEpoch;
        long acceptedCookies;
        long captureFailures;
        long submittedSamples;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("jonoffcpu-agent-controller-");
        try {
            successfulCapture(root.resolve("success"));
            profilerOnlyCapture(root.resolve("profiler-only"));
            profilerOnlyExternalStopIsFinalized(root.resolve("profiler-only-external-stop"));
            coalescingCapture(root.resolve("coalescing"));
            nativeStackFailureIsRetained(root.resolve("missing-native-stack"));
            stopTimeoutRetainsOwnership(root.resolve("timeout"));
            closeTimeoutRetainsOwnership(root.resolve("close-timeout"));
            nativeStopErrorStillCleansUp(root.resolve("stop-error"));
            incompleteSourceStillFinalizesProfiler(root.resolve("incomplete-source"));
            profilerTerminationTriggersPollCleanup(root.resolve("poll"));
            lostStopResponseUsesIdentityQuery(root.resolve("receipt"));
            unfinalizedApNeverPublishesFooter(root.resolve("unfinalized"));
            ownershipMismatchNeverUsesOrdinaryStop(root.resolve("ownership"));
            busyProfilerIsNeverTakenOver(root.resolve("busy"));
            malformedStartUsesUuidGuard(root.resolve("malformed"));
            concurrentLifecycleHasOneOwner(root.resolve("concurrent"));
            nativeOptionParsing(root.resolve("options"));
            yamlConfigParsing(root.resolve("yaml"));
            samplingProbabilityParsing(root.resolve("sampling-probability"));
            unsignedStopCountersParse();
            System.out.println("SignalCaptureController fixtures passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void successfulCapture(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        Path directory = controller.start();
        check(controller.state() == SignalCaptureController.State.SOURCE_ENABLED, "source was not enabled");
        check(Files.isRegularFile(root.resolve("correlation.ndjson.jfr")), "JFR path was not reserved");
        Path manifest = controller.stop();
        check(controller.state() == SignalCaptureController.State.COMPLETE, "capture did not complete");
        JsonObject json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        check(json.get("complete").getAsBoolean(), "manifest not complete");
        check(json.getAsJsonObject("analysisInputs").get("captureEpoch").getAsLong() == 7, "wrong epoch");
        check(
                json.getAsJsonObject("analysisInputs")
                        .getAsJsonObject("apStats")
                        .get("submittedSamples")
                        .getAsString()
                        .equals("0"),
                "AP counters missing");
        Path correlation = root.resolve("correlation.ndjson");
        check(Files.isRegularFile(correlation), "source artifact missing");
        check(profiler.ordinaryStops == 0, "ordinary AP stop was used");
        check(source.closeCalls == 1, "native handle was not closed once");
        String last = Files.readAllLines(correlation).get(2);
        JsonObject footer = JsonParser.parseString(last).getAsJsonObject();
        check(footer.get("recordType").getAsString().equals("captureFinalized"), "final footer missing");
        check(footer.get("apStopResponse").getAsString().contains("finalized=true"), "receipt not retained");
    }

    private static void profilerOnlyCapture(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source, BigDecimal.ZERO);
        controller.start();
        check(controller.state() == SignalCaptureController.State.PROFILER_ONLY, "profiler-only state expected");
        check(profiler.plainStarts == 1 && profiler.cookieStarts == 0, "AP must start without signalcookie");
        check(profiler.active, "AP is not running");
        controller.pollProfiler();
        check(controller.state() == SignalCaptureController.State.PROFILER_ONLY, "poll must keep a running profile");
        Path manifest = controller.stop();
        check(controller.state() == SignalCaptureController.State.COMPLETE, "profiler-only capture did not complete");
        check(profiler.ordinaryStops == 1 && profiler.guardedStops == 0, "AP must stop with the ordinary command");
        check(source.prepareCalls == 0 && source.enableCalls == 0, "eBPF source must never be prepared or enabled");
        check(source.stopCalls == 0 && source.closeCalls == 0, "eBPF source must never be stopped or closed");
        JsonObject json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        check(json.get("complete").getAsBoolean(), "manifest not complete");
        check(json.get("mode").getAsString().equals("profilerOnly"), "manifest mode missing");
        check(
                json.getAsJsonObject("analysisInputs").get("mode").getAsString().equals("profilerOnly"),
                "analysis inputs mode missing");
        Path correlation = root.resolve("correlation.ndjson");
        var rows = Files.readAllLines(correlation);
        check(rows.size() == 1, "profiler-only stream must hold exactly one row");
        JsonObject footer = JsonParser.parseString(rows.get(0)).getAsJsonObject();
        check(footer.get("recordType").getAsString().equals("captureFinalized"), "footer missing");
        check(footer.get("state").getAsString().equals("profilerOnly"), "footer state must be profilerOnly");
        check(footer.get("sourceDisabled").getAsBoolean(), "footer must flag the disabled source");
        check(Files.size(root.resolve("correlation.ndjson.jfr")) > 0, "JFR recording missing");
        check(controller.stop().equals(manifest), "repeated stop must be idempotent");
    }

    private static void profilerOnlyExternalStopIsFinalized(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source, BigDecimal.ZERO);
        controller.start();
        profiler.stopExternally();
        controller.pollProfiler();
        check(controller.state() == SignalCaptureController.State.COMPLETE, "external stop was not finalized");
        check(profiler.guardedStops == 0, "a guarded cookie stop must never be issued in profiler-only mode");
        check(source.prepareCalls == 0 && source.closeCalls == 0, "eBPF source must stay untouched");
        JsonObject footer = JsonParser.parseString(
                        Files.readAllLines(root.resolve("correlation.ndjson")).get(0))
                .getAsJsonObject();
        check(footer.get("state").getAsString().equals("profilerOnly"), "footer missing after external stop");
    }

    private static void coalescingCapture(Path root) throws Exception {
        Files.createDirectory(root);
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        AgentConfig config = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("source.ndjson")
                        + ",jonoffcpudelivery=coalescing,asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("original.jfr"),
                nativeLibrary);
        check(config.signalDelivery().equals("coalescing"), "Native delivery option not parsed");
        SignalCaptureController controller =
                new SignalCaptureController(config, new FakeProfiler(), new FakeSource(), millis -> {});
        controller.start();
        controller.stop();
        check(controller.state() == SignalCaptureController.State.COMPLETE, "Coalescing capture did not complete");
        String source = Files.readString(root.resolve("source.ndjson"));
        check(
                source.contains("\"signalDelivery\":\"coalescing\"") && source.contains("delivery=coalescing"),
                "Delivery policy missing from source/footer/receipt");
    }

    private static void nativeStackFailureIsRetained(Path root) throws Exception {
        Files.createDirectory(root);
        FakeSource source = new FakeSource();
        source.nativeStackFailure = true;
        SignalCaptureController controller = controller(root, new FakeProfiler(), source);
        controller.start();
        controller.stop();
        check(
                controller.state() == SignalCaptureController.State.COMPLETE,
                "Missing native stack rejected complete source");
        String rows = Files.readString(root.resolve("correlation.ndjson"));
        check(
                rows.contains("\"symbolizationFailures\":\"1\"") && rows.contains("\"status\":\"error\""),
                "Native stack failure evidence not retained");
    }

    private static void stopTimeoutRetainsOwnership(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        source.timeoutOnce = true;
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        try {
            controller.stop();
            throw new AssertionError("Expected native stop timeout");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("stop_timeout"), "wrong timeout failure");
        }
        check(profiler.active, "AP stopped while native ownership was unsettled");
        check(source.closeCalls == 0, "timed-out native handle was freed");
        controller.stop();
        check(controller.state() == SignalCaptureController.State.COMPLETE, "retry did not complete");
    }

    private static void ownershipMismatchNeverUsesOrdinaryStop(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        profiler.mismatchOnStop = true;
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        try {
            controller.stop();
            throw new AssertionError("Expected AP ownership failure");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("ownership"), "wrong ownership failure");
        }
        check(profiler.active, "mismatch mutated the active AP capture");
        check(profiler.ordinaryStops == 0, "ordinary AP stop fallback was used");
        check(source.closeCalls == 1, "settled source handle was not closed before AP ownership check");
        try {
            controller.stop();
            throw new AssertionError("Expected terminal ownership failure");
        } catch (IllegalStateException expected) {
            check(profiler.guardedStops == 1, "terminal ownership mismatch was retried against foreign AP state");
        }
    }

    private static void closeTimeoutRetainsOwnership(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        source.closeTimeoutOnce = true;
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        try {
            controller.stop();
            throw new AssertionError("Expected native close timeout");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("close_timeout"), "wrong close timeout failure");
        }
        check(profiler.active, "AP stopped before native close ownership settled");
        controller.stop();
        check(controller.state() == SignalCaptureController.State.COMPLETE, "close retry did not complete");
        check(source.closeCalls == 2, "native close was not retried");
    }

    private static void incompleteSourceStillFinalizesProfiler(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        source.incompleteStop = true;
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        try {
            controller.stop();
            throw new AssertionError("Expected incomplete native source");
        } catch (IllegalStateException expected) {
            check(
                    expected.getMessage().contains("Native source finalization was incomplete"),
                    "wrong incomplete-source failure");
        }
        check(
                !profiler.active && profiler.guardedStops == 1,
                "owned AP capture remained active after terminal native failure");
        check(source.closeCalls == 1, "incomplete native source was not closed");
        check(
                Files.readAllLines(root.resolve("correlation.ndjson")).size() == 2,
                "incomplete native source received a success footer");
    }

    private static void nativeStopErrorStillCleansUp(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        source.stopError = true;
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        try {
            controller.stop();
            throw new AssertionError("Expected native stop error");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("after safe close and AP finalization"), "wrong native stop error");
        }
        check(!profiler.active && profiler.guardedStops == 1, "native stop error stranded the owned AP capture");
        check(source.closeCalls == 1, "native stop error did not fall back to safe close");
    }

    private static void profilerTerminationTriggersPollCleanup(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        profiler.finalizeExternally();
        controller.pollProfiler();
        check(
                controller.state() == SignalCaptureController.State.COMPLETE,
                "owned-status poll did not quiesce and finalize source");
        check(source.closeCalls == 1, "owned-status poll did not close native source");
    }

    private static void lostStopResponseUsesIdentityQuery(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        profiler.loseStopResponse = true;
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        controller.stop();
        check(
                controller.state() == SignalCaptureController.State.COMPLETE,
                "retained identity-specific receipt did not recover finalization");
        check(profiler.receiptQueries == 1, "controller did not query retained receipt exactly once");
    }

    private static void unfinalizedApNeverPublishesFooter(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        profiler.finalized = false;
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        try {
            controller.stop();
            throw new AssertionError("Expected AP finalization failure");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("did not finalize"), "wrong AP finalization failure");
        }
        check(
                Files.readAllLines(root.resolve("correlation.ndjson")).size() == 2,
                "incomplete AP capture received a success footer");
        check(source.closeCalls == 1, "settled native handle was not closed");
    }

    private static void busyProfilerIsNeverTakenOver(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        profiler.busy = true;
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        try {
            controller.start();
            throw new AssertionError("Expected busy AP rejection");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("already active"), "wrong busy failure");
        }
        check(source.prepareCalls == 0, "native source prepared before AP ownership preflight");
        check(profiler.ordinaryStops == 0 && profiler.guardedStops == 0, "busy AP was stopped");
    }

    private static void malformedStartUsesUuidGuard(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        profiler.malformedStart = true;
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        try {
            controller.start();
            throw new AssertionError("Expected malformed start response");
        } catch (IllegalArgumentException expected) {
            // Expected protocol rejection after AP may have started.
        }
        check(profiler.guardedStops == 1, "failed start did not use UUID-guarded recovery");
        check(profiler.ordinaryStops == 0, "failed start used ordinary AP stop");
        check(source.closeCalls == 1, "prepared native source was not closed");
        JsonObject manifest = JsonParser.parseString(Files.readString(controller.manifestPath()))
                .getAsJsonObject();
        check(!manifest.get("complete").getAsBoolean(), "failed start marked complete");
    }

    private static void concurrentLifecycleHasOneOwner(Path root) throws Exception {
        Files.createDirectory(root);
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);

        AtomicInteger started = new AtomicInteger();
        AtomicInteger rejectedStarts = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runConcurrently(
                () -> {
                    try {
                        controller.start();
                        started.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        rejectedStarts.incrementAndGet();
                    } catch (Throwable unexpected) {
                        failure.compareAndSet(null, unexpected);
                    }
                },
                failure);
        check(failure.get() == null, "concurrent start failed unexpectedly: " + failure.get());
        check(started.get() == 1 && rejectedStarts.get() == 1, "concurrent starts did not select exactly one owner");
        check(
                source.prepareCalls == 1 && source.enableCalls == 1,
                "concurrent starts prepared or enabled more than one source");

        AtomicInteger stopped = new AtomicInteger();
        runConcurrently(
                () -> {
                    try {
                        controller.stop();
                        stopped.incrementAndGet();
                    } catch (Throwable unexpected) {
                        failure.compareAndSet(null, unexpected);
                    }
                },
                failure);
        check(failure.get() == null, "concurrent stop failed unexpectedly: " + failure.get());
        check(stopped.get() == 2, "idempotent concurrent stops did not both complete");
        check(
                source.stopCalls == 1 && source.closeCalls == 1 && profiler.guardedStops == 1,
                "concurrent stops finalized owned resources more than once");
        try {
            controller.start();
            throw new AssertionError("Expected start after terminal completion to fail");
        } catch (IllegalStateException expected) {
            check(source.prepareCalls == 1, "terminal controller admitted a new source start");
        }
    }

    private static void runConcurrently(Runnable action, AtomicReference<Throwable> failure)
            throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[2];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(
                    () -> {
                        ready.countDown();
                        try {
                            go.await();
                            action.run();
                        } catch (Throwable unexpected) {
                            failure.compareAndSet(null, unexpected);
                        }
                    },
                    "controller-race-" + i);
            threads[i].start();
        }
        ready.await();
        go.countDown();
        for (Thread thread : threads) thread.join();
    }

    private static void nativeOptionParsing(Path root) throws Exception {
        Files.createDirectory(root);
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        Path jfr = root.resolve("combined.jfr");
        AgentConfig config = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("correlation.ndjson")
                        + ",samplethreshold=0.1,min-off-cpu-micros=7,asprofpath="
                        + ap
                        + ",event=cpu,alloc=1m,jfrsync=profile,file="
                        + jfr,
                nativeLibrary);
        check(config.requestedSampleProbability().toPlainString().equals("0.1"), "requested probability not retained");
        check(config.sampleThreshold() == 429_496_729L, "probability threshold was not rounded down");
        check(
                config.sourcePolicy()
                                .get("requestedSampleProbability")
                                .getAsString()
                                .equals("0.1")
                        && config.sourcePolicy().get("sampleThreshold").getAsLong() == 429_496_729L,
                "requested/effective sampling policy not persisted");
        check(config.minOffCpuMicros() == 7 && config.maxOffCpuMicros() == null, "optional duration policy lost");
        check(config.asyncProfilerOptions().contains("event=cpu,alloc=1m,jfrsync=profile"), "AP tail changed");
        check(config.jfrOutput().equals(jfr), "AP output path not retained");

        try {
            AgentConfig.parseNativeOptions(
                    "jonoffcpuoutput="
                            + root.resolve("pattern.ndjson")
                            + ",asprofpath="
                            + ap
                            + ",event=cpu,file="
                            + root.resolve("profile-%p.jfr"),
                    nativeLibrary);
            throw new AssertionError("Expected AP output pattern rejection");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("patterns"), "wrong AP output pattern failure");
        }

        for (String action : new String[] {"start", "resume", "stop", "dump", "status", "metrics", "list", "version"}) {
            try {
                AgentConfig.parseNativeOptions(
                        "jonoffcpuoutput="
                                + root.resolve("rejected-" + action + ".ndjson")
                                + ",asprofpath="
                                + ap
                                + ","
                                + action
                                + ",file="
                                + root.resolve("rejected-" + action + ".jfr"),
                        nativeLibrary);
                throw new AssertionError("Expected forwarded AP action rejection: " + action);
            } catch (IllegalArgumentException expected) {
                check(
                        expected.getMessage().contains("controller-owned option"),
                        "wrong forwarded action rejection for " + action + ": " + expected.getMessage());
            }
        }

        Path racedJfr = root.resolve("raced.jfr");
        AgentConfig raced = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput=" + root.resolve("raced.ndjson") + ",asprofpath=" + ap + ",event=cpu,file=" + racedJfr,
                nativeLibrary);
        Files.writeString(racedJfr, "foreign");
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        try {
            new SignalCaptureController(raced, profiler, source, millis -> {}).start();
            throw new AssertionError("Expected raced JFR reservation failure");
        } catch (IOException expected) {
            check(Files.readString(racedJfr).equals("foreign"), "existing raced JFR was changed");
            check(source.prepareCalls == 0, "native prepare ran after JFR reservation failure");
        }
    }

    private static void yamlConfigParsing(Path root) throws Exception {
        Files.createDirectory(root);
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        Path configFile = root.resolve("jonoffcpu.yaml");
        Files.writeString(configFile, """
                correlationOutput: %s
                asyncProfilerLibrary: %s
                nativeCollectorLibrary: %s
                asyncProfilerOptions: event=cpu,file=%s
                signalDelivery: coalescing
                sampleProbability: "0.125"
                minOffCpuMicros: 10
                maxOffCpuMicros: 1000
                """.formatted(
                        root.resolve("correlation.ndjson"), ap, nativeLibrary, root.resolve("combined.jfr")));
        AgentConfig config = AgentConfig.parse(configFile.toString());
        check(config.signalDelivery().equals("coalescing"), "YAML delivery policy not parsed");
        check(config.sampleThreshold() == 536_870_912L, "YAML sampling probability not parsed exactly");
        check(config.minOffCpuMicros() == 10 && config.maxOffCpuMicros() == 1000, "YAML duration bounds not parsed");
        try {
            AgentConfig.parse("correlationOutput: /tmp/a\nunknownOption: true\n");
            throw new AssertionError("Expected unknown YAML key rejection");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("Unknown"), "wrong unknown YAML key failure");
        }
        try {
            AgentConfig.parse("correlationOutput: /tmp/a\ncorrelationOutput: /tmp/b\n");
            throw new AssertionError("Expected duplicate YAML key rejection");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("Invalid agent YAML"), "wrong duplicate YAML key failure");
        }
    }

    private static void samplingProbabilityParsing(Path root) throws Exception {
        Files.createDirectory(root);
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        String[][] valid = {
            {"0", "0"},
            {"0.0000000001", "0"},
            {"0.0000000002", "0"},
            {"0.0000000003", "1"},
            {"0.5", "2147483648"},
            {"1.000", "4294967296"}
        };
        for (int i = 0; i < valid.length; i++) {
            AgentConfig config = AgentConfig.parseNativeOptions(
                    "jonoffcpuoutput="
                            + root.resolve("valid-" + i + ".ndjson")
                            + ",samplethreshold="
                            + valid[i][0]
                            + ",asprofpath="
                            + ap
                            + ",event=cpu,file="
                            + root.resolve("valid-" + i + ".jfr"),
                    nativeLibrary);
            check(
                    config.requestedSampleProbability().toPlainString().equals(valid[i][0]),
                    "requested probability spelling was not retained: " + valid[i][0]);
            check(
                    Long.toString(config.sampleThreshold()).equals(valid[i][1]),
                    "wrong effective threshold for " + valid[i][0]);
        }
        for (String invalid : new String[] {"-0.1", ".5", "1.0001", "1e-1", "NaN"}) {
            try {
                AgentConfig.parseNativeOptions(
                        "jonoffcpuoutput="
                                + root.resolve("invalid-" + invalid.hashCode() + ".ndjson")
                                + ",samplethreshold="
                                + invalid
                                + ",asprofpath="
                                + ap
                                + ",event=cpu,file="
                                + root.resolve("invalid-" + invalid.hashCode() + ".jfr"),
                        nativeLibrary);
                throw new AssertionError("Expected invalid probability rejection: " + invalid);
            } catch (IllegalArgumentException expected) {
                check(
                        expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains("probability"),
                        "wrong invalid probability failure for " + invalid + ": " + expected.getMessage());
            }
        }
    }

    private static void unsignedStopCountersParse() {
        String max = "18446744073709551615";
        CaptureProtocol.Stopped stopped = CaptureProtocol.parseStopped(
                "signal-capture-v1 stopped id=01234567-89ab-cdef-0123-456789abcdef delivery=coalescing signal=27 epoch=1"
                        + " admitted="
                        + max
                        + " invalid-code="
                        + max
                        + " zero-cookie="
                        + max
                        + " zero-sequence="
                        + max
                        + " stale-epoch="
                        + max
                        + " accepted="
                        + max
                        + " capture-failures="
                        + max
                        + " submitted="
                        + max
                        + " finalized=true stopped-at="
                        + max
                        + " reason=completed\n");
        check(stopped.manifestCounters().get("submittedSamples").equals(max), "u64 AP counter rejected");
    }

    private static SignalCaptureController controller(Path root, FakeProfiler profiler, FakeSource source)
            throws IOException {
        return controller(root, profiler, source, new BigDecimal("0.0000000233"));
    }

    private static SignalCaptureController controller(
            Path root, FakeProfiler profiler, FakeSource source, BigDecimal probability) throws IOException {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        AgentConfig config = new AgentConfig(
                root.resolve("correlation.ndjson"),
                ap,
                nativeLibrary,
                null,
                ProcessHandle.current().pid(),
                "event=cpu",
                "queued",
                probability,
                probability.multiply(new BigDecimal(1L << 32)).toBigInteger().longValueExact(),
                null,
                null,
                50,
                0,
                1000);
        return new SignalCaptureController(config, profiler, source, millis -> {});
    }

    private static final class FakeProfiler implements SignalCaptureController.ProfilerClient {
        private int signal = 35;
        private String delivery = "queued";
        private boolean active;
        private boolean busy;
        private boolean mismatchOnStop;
        private boolean malformedStart;
        private boolean loseStopResponse;
        private boolean finalized = true;
        private int ordinaryStops;
        private int guardedStops;
        private int plainStarts;
        private int cookieStarts;
        private int receiptQueries;
        private String retainedReceipt;
        private String session;
        private Path jfr;

        private void stopExternally() throws IOException {
            active = false;
            writePlainJfr(jfr);
        }

        private void finalizeExternally() throws IOException {
            active = false;
            writeJfr(jfr, session, signal, delivery);
            retainedReceipt = terminalReceipt();
        }

        @Override
        public String version() {
            return "test-ap";
        }

        @Override
        public String execute(String command) throws Exception {
            if (command.equals("status,signalcookie")) {
                if (busy) return "signal-capture-v1 busy mode=other\n";
                return active ? activeLine() : "signal-capture-v1 inactive\n";
            }
            if (command.startsWith("status,signalcookie,signalid=")) {
                receiptQueries++;
                return retainedReceipt == null ? "signal-capture-v1 inactive\n" : retainedReceipt;
            }
            if (command.equals("status")) {
                return active ? "Profiling is running for 1 seconds\n" : "Profiler is not active\n";
            }
            if (command.startsWith("start,") && !command.contains(",signalcookie=")) {
                plainStarts++;
                active = true;
                jfr = Path.of(option(command, "file"));
                return "Profiling started\n";
            }
            if (command.startsWith("start,")) {
                cookieStarts++;
                active = true;
                session = option(command, "signalid");
                delivery = option(command, "signalcookie");
                signal = delivery.equals("queued") ? 35 : 30;
                jfr = Path.of(option(command, "file"));
                return malformedStart ? "Profiling started\n" : activeLine();
            }
            if (command.equals("stop")) {
                ordinaryStops++;
                if (!active) throw new IOException("Profiler is not active");
                active = false;
                if (session == null) writePlainJfr(jfr);
                return "stopped";
            }
            if (command.startsWith("stop,signalcookie,")) {
                guardedStops++;
                if (mismatchOnStop) return "signal-capture-v1 mismatch\n";
                if (!active) return retainedReceipt == null ? "signal-capture-v1 inactive\n" : retainedReceipt;
                active = false;
                writeJfr(jfr, session, signal, delivery);
                retainedReceipt = terminalReceipt();
                if (loseStopResponse) throw new IOException("simulated lost AP response");
                return retainedReceipt;
            }
            throw new IllegalArgumentException("Unexpected AP command " + command);
        }

        private String terminalReceipt() {
            return "signal-capture-v1 stopped id="
                    + session
                    + " delivery="
                    + delivery
                    + " signal="
                    + signal
                    + " epoch=7 admitted=0 invalid-code=0 zero-cookie=0"
                    + " zero-sequence=0 stale-epoch=0 accepted=0 capture-failures=0 submitted=0"
                    + " finalized="
                    + finalized
                    + " stopped-at=123456 reason="
                    + (finalized ? "completed" : "jfr_error")
                    + "\n";
        }

        private String activeLine() {
            return "signal-capture-v1 id=" + session + " delivery=" + delivery + " signal=" + signal + " epoch=7\n";
        }
    }

    private static final class FakeSource implements SignalCaptureController.SourceClient {
        private static final String HANDLE = "8000000000000001";
        private int prepareCalls;
        private int enableCalls;
        private int stopCalls;
        private int closeCalls;
        private boolean timeoutOnce;
        private boolean closeTimeoutOnce;
        private boolean incompleteStop;
        private boolean stopError;
        private boolean nativeStackFailure;
        private Path source;
        private long threshold;
        private long targetPid;
        private int signal;
        private String session;
        private JsonObject captureEnd;

        @Override
        public String prepare(String configJson) {
            prepareCalls++;
            JsonObject config = JsonParser.parseString(configJson).getAsJsonObject();
            source = Path.of(config.get("outputPath").getAsString());
            threshold = config.get("sampleThreshold").getAsLong();
            targetPid = config.get("targetPid").getAsLong();
            JsonObject result = success("prepared");
            result.addProperty("handle", HANDLE);
            result.addProperty("sourcePath", source.toString());
            result.addProperty("targetPid", config.get("targetPid").getAsLong());
            result.addProperty("hostTgid", config.get("targetPid").getAsLong());
            result.addProperty("sampleThreshold", threshold);
            result.add("minOffCpuMicros", com.google.gson.JsonNull.INSTANCE);
            result.add("maxOffCpuMicros", com.google.gson.JsonNull.INSTANCE);
            JsonObject identity = new JsonObject();
            identity.addProperty("registrationToken", "0123456789abcdef");
            identity.addProperty("processGenerationNs", "9");
            identity.addProperty("timeNamespaceInode", "42");
            identity.addProperty("pidNamespaceDevice", "4");
            identity.addProperty("pidNamespaceInode", "43");
            identity.addProperty("clockVerified", true);
            identity.addProperty("monotonicOffsetNanos", "0");
            result.add("verifiedIdentity", identity);
            return result.toString();
        }

        @Override
        public String enable(long handle, String captureJson) {
            enableCalls++;
            JsonObject capture = JsonParser.parseString(captureJson).getAsJsonObject();
            session = capture.get("sessionId").getAsString();
            signal = capture.get("signal").getAsInt();
            JsonObject result = success("enabled");
            result.addProperty("handle", HANDLE);
            result.addProperty("sessionId", session);
            result.addProperty("captureEpoch", 7);
            result.addProperty("signal", signal);
            result.addProperty("signalDelivery", signal >= 34 ? "queued" : "coalescing");
            result.addProperty("sourcePath", source.toString());
            result.addProperty("targetPid", targetPid);
            result.addProperty("hostTgid", targetPid);
            result.addProperty("sampleThreshold", threshold);
            result.add("minOffCpuMicros", com.google.gson.JsonNull.INSTANCE);
            result.add("maxOffCpuMicros", com.google.gson.JsonNull.INSTANCE);
            JsonObject identity = new JsonObject();
            identity.addProperty("registrationToken", "0123456789abcdef");
            identity.addProperty("processGenerationNs", "9");
            identity.addProperty("timeNamespaceInode", "42");
            identity.addProperty("pidNamespaceDevice", "4");
            identity.addProperty("pidNamespaceInode", "43");
            identity.addProperty("clockVerified", true);
            identity.addProperty("monotonicOffsetNanos", "0");
            result.add("verifiedIdentity", identity);
            JsonObject start = new JsonObject();
            start.addProperty("schemaVersion", 1);
            start.addProperty("recordType", "captureStart");
            start.addProperty("sourceId", "jonoffcpu.offcpu.v1");
            start.addProperty("sessionId", session);
            start.addProperty("captureEpoch", 7);
            start.addProperty("signal", signal);
            start.addProperty("signalDelivery", signal >= 34 ? "queued" : "coalescing");
            start.addProperty("sampleThreshold", threshold);
            start.addProperty("sampleDenominator", 4294967296L);
            start.addProperty("startedMonotonicNanos", "9");
            start.addProperty("hostTgid", targetPid);
            start.addProperty("targetPid", targetPid);
            start.addProperty("registrationToken", "0123456789abcdef");
            start.addProperty("processGenerationNs", "9");
            start.addProperty("timeNamespaceInode", "42");
            start.addProperty("pidNamespaceDevice", "4");
            start.addProperty("pidNamespaceInode", "43");
            start.add("minOffCpuMicros", com.google.gson.JsonNull.INSTANCE);
            start.add("maxOffCpuMicros", com.google.gson.JsonNull.INSTANCE);
            try {
                Files.writeString(source, start + "\n");
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            return result.toString();
        }

        @Override
        public String stop(long handle, long timeoutMillis) {
            stopCalls++;
            if (timeoutOnce) {
                timeoutOnce = false;
                JsonObject result = failure("stopping", "stop_timeout", "test timeout");
                result.addProperty("handle", HANDLE);
                return result.toString();
            }
            if (stopError)
                return failure("error", "io_error", "test fsync failure").toString();
            captureEnd = captureEnd(session, !incompleteStop);
            try {
                if (nativeStackFailure) {
                    JsonObject observation = new JsonObject();
                    observation.addProperty("schemaVersion", 1);
                    observation.addProperty("recordType", "observation");
                    observation.addProperty("sourceId", "jonoffcpu.offcpu.v1");
                    observation.addProperty("sessionId", session);
                    observation.addProperty("captureEpoch", 7);
                    observation.addProperty("correlationId", "0000000700000001");
                    observation.addProperty("hostTgid", targetPid);
                    observation.addProperty("hostTid", targetPid);
                    observation.addProperty("targetTgid", targetPid);
                    observation.addProperty("targetTid", targetPid);
                    observation.addProperty("sampleThreshold", threshold);
                    observation.addProperty("processGenerationNs", "9");
                    observation.addProperty("threadGenerationNs", "9");
                    observation.addProperty("registrationToken", "0123456789abcdef");
                    observation.addProperty("startMonotonicNanos", "9");
                    observation.addProperty("endMonotonicNanos", "10");
                    for (String key : new String[] {"kernelStack", "userStack"}) {
                        JsonObject stack = new JsonObject();
                        stack.addProperty("status", key.equals("userStack") ? "error" : "ok");
                        stack.add("frames", new com.google.gson.JsonArray());
                        observation.add(key, stack);
                    }
                    JsonObject userspace =
                            captureEnd.getAsJsonObject("counters").getAsJsonObject("userspace");
                    userspace.addProperty("receivedObservations", "1");
                    userspace.addProperty("writtenObservations", "1");
                    userspace.addProperty("symbolizationFailures", "1");
                    Files.writeString(source, observation + "\n", java.nio.file.StandardOpenOption.APPEND);
                }
                Files.writeString(source, captureEnd + "\n", java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            JsonObject result = success(incompleteStop ? "incomplete" : "complete");
            result.addProperty("handle", HANDLE);
            result.addProperty("sourcePath", source.toString());
            result.add("captureEnd", captureEnd);
            return result.toString();
        }

        @Override
        public String close(long handle) {
            closeCalls++;
            if (closeTimeoutOnce) {
                closeTimeoutOnce = false;
                JsonObject result = failure("closing", "close_timeout", "test close timeout");
                result.addProperty("handle", HANDLE);
                return result.toString();
            }
            if (captureEnd == null && session != null) {
                captureEnd = captureEnd(session, false);
                try {
                    Files.writeString(source, captureEnd + "\n", java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }
            JsonObject result = success("closed");
            if (captureEnd != null) result.add("captureEnd", captureEnd);
            return result.toString();
        }

        private static JsonObject captureEnd(String session, boolean complete) {
            JsonObject end = new JsonObject();
            end.addProperty("schemaVersion", 1);
            end.addProperty("recordType", "captureEnd");
            end.addProperty("sourceId", "jonoffcpu.offcpu.v1");
            end.addProperty("sessionId", session);
            end.addProperty("captureEpoch", 7);
            end.addProperty("state", complete ? "complete" : "incomplete");
            end.addProperty("startedMonotonicNanos", "9");
            end.addProperty("stoppedMonotonicNanos", "10");
            end.addProperty("detachedMonotonicNanos", "11");
            end.addProperty("drainCompletedMonotonicNanos", "12");
            end.addProperty("drainTimedOut", false);
            end.addProperty("targetExited", false);
            JsonObject counters = new JsonObject();
            JsonObject kernel = new JsonObject();
            for (String key : new String[] {
                "switchOuts",
                "schedulerExitSwitches",
                "schedulerExitNoSwitches",
                "lifetimeRejections",
                "eligibleIntervals",
                "eligibleDurationMicros",
                "probabilityRejections",
                "selectedIntervals",
                "sequenceExhaustions",
                "sequenceContentions",
                "threadStateFailures",
                "kernelStackFailures",
                "userStackFailures",
                "signalFailures",
                "ringReserveFailures",
                "targetNamespaceFailures"
            }) kernel.addProperty(key, "0");
            JsonObject userspace = new JsonObject();
            for (String key : new String[] {
                "receivedObservations",
                "writtenObservations",
                "symbolizationFailures",
                "writeFailures",
                "pollFailures",
                "drainTimedOut"
            }) {
                userspace.addProperty(key, "0");
            }
            counters.add("kernel", kernel);
            counters.add("userspace", userspace);
            end.add("counters", counters);
            return end;
        }
    }

    private static JsonObject success(String state) {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("abiVersion", 1);
        result.addProperty("ok", true);
        result.addProperty("state", state);
        return result;
    }

    private static JsonObject failure(String state, String code, String message) {
        JsonObject result = success(state);
        result.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        result.add("error", error);
        return result;
    }

    private static String option(String command, String name) {
        for (String token : command.split(",")) {
            if (token.startsWith(name + "=")) return token.substring(name.length() + 1);
        }
        throw new IllegalArgumentException("Missing option " + name);
    }

    private static void writePlainJfr(Path path) throws IOException {
        try (Recording recording = new Recording()) {
            recording.start();
            recording.stop();
            recording.dump(path);
        }
    }

    private static void writeJfr(Path path, String session, int signal, String delivery) throws IOException {
        try (Recording recording = new Recording()) {
            recording.enable(Capture.class);
            recording.enable(Stats.class);
            recording.start();
            Capture capture = new Capture();
            capture.sessionId = session;
            capture.captureEpoch = 7;
            capture.signal = signal;
            capture.signalDelivery = delivery;
            capture.commit();
            Stats stats = new Stats();
            stats.sessionId = session;
            stats.captureEpoch = 7;
            stats.commit();
            recording.stop();
            recording.dump(path);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
