// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.awaitility.Awaitility.await;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lhotari.jonoffcpu.agent.SignalCaptureController.State;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SignalCaptureControllerTest {
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

    @Test
    void successfulCapture(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        assertThat(controller.state()).as("source was not enabled").isEqualTo(State.SOURCE_ENABLED);
        assertThat(root.resolve("correlation.jfr"))
                .as("JFR path was not reserved")
                .isRegularFile();
        Path manifest = controller.stop();
        assertThat(controller.state()).as("capture did not complete").isEqualTo(State.COMPLETE);
        JsonObject json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        assertThat(json.get("complete").getAsBoolean())
                .as("manifest not complete")
                .isTrue();
        assertThat(json.getAsJsonObject("analysisInputs").get("captureEpoch").getAsLong())
                .as("wrong epoch")
                .isEqualTo(7);
        assertThat(json.getAsJsonObject("analysisInputs")
                        .getAsJsonObject("apStats")
                        .get("submittedSamples")
                        .getAsString())
                .as("AP counters missing")
                .isEqualTo("0");
        Path correlation = root.resolve("correlation.ndjson");
        assertThat(correlation).as("source artifact missing").isRegularFile();
        assertThat(profiler.ordinaryStops).as("ordinary AP stop was used").isZero();
        assertThat(source.closeCalls).as("native handle was not closed once").isEqualTo(1);
        List<JsonObject> rows = CaptureStreamFixture.rows(correlation);
        JsonObject footer = rows.get(rows.size() - 1);
        assertThat(footer.get("recordType").getAsString())
                .as("final footer missing")
                .isEqualTo("captureFinalized");
        assertThat(footer.get("apStopResponse").getAsString())
                .as("receipt not retained")
                .contains("finalized=true");
    }

    @Test
    void profilerOnlyCapture(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source, NONE);
        controller.start();
        assertThat(controller.state()).as("profiler-only state expected").isEqualTo(State.PROFILER_ONLY);
        assertThat(profiler.plainStarts)
                .as("AP must start without signalcookie")
                .isEqualTo(1);
        assertThat(profiler.cookieStarts)
                .as("AP must start without signalcookie")
                .isZero();
        assertThat(profiler.active).as("AP is not running").isTrue();
        controller.pollProfiler();
        assertThat(controller.state()).as("poll must keep a running profile").isEqualTo(State.PROFILER_ONLY);
        Path manifest = controller.stop();
        assertThat(controller.state())
                .as("profiler-only capture did not complete")
                .isEqualTo(State.COMPLETE);
        assertThat(profiler.ordinaryStops)
                .as("AP must stop with the ordinary command")
                .isEqualTo(1);
        assertThat(profiler.guardedStops)
                .as("AP must stop with the ordinary command")
                .isZero();
        assertThat(source.prepareCalls).as("eBPF source must never be prepared").isZero();
        assertThat(source.enableCalls).as("eBPF source must never be enabled").isZero();
        assertThat(source.stopCalls).as("eBPF source must never be stopped").isZero();
        assertThat(source.closeCalls).as("eBPF source must never be closed").isZero();
        JsonObject json = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        assertThat(json.get("complete").getAsBoolean())
                .as("manifest not complete")
                .isTrue();
        assertThat(json.get("mode").getAsString()).as("manifest mode missing").isEqualTo("profilerOnly");
        assertThat(json.getAsJsonObject("analysisInputs").get("mode").getAsString())
                .as("analysis inputs mode missing")
                .isEqualTo("profilerOnly");
        Path correlation = root.resolve("correlation.ndjson");
        var rows = CaptureStreamFixture.rows(correlation);
        assertThat(rows).as("profiler-only stream must hold exactly one record").hasSize(1);
        JsonObject footer = rows.get(0);
        assertThat(footer.get("recordType").getAsString()).as("footer missing").isEqualTo("captureFinalized");
        assertThat(footer.get("state").getAsString())
                .as("footer state must be profilerOnly")
                .isEqualTo("profilerOnly");
        assertThat(footer.get("sourceDisabled").getAsBoolean())
                .as("footer must flag the disabled source")
                .isTrue();
        assertThat(Files.size(root.resolve("correlation.jfr")))
                .as("JFR recording missing")
                .isPositive();
        assertThat(controller.stop()).as("repeated stop must be idempotent").isEqualTo(manifest);
    }

    @Test
    void profilerOnlyExternalStopIsFinalized(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        // A zero uniform probability resolves to the explicit none policy.
        SignalCaptureController controller = controller(
                root, profiler, source, new SamplingConfig(null, null, null, SamplingConfig.uniform(BigDecimal.ZERO)));
        controller.start();
        profiler.stopExternally();
        controller.pollProfiler();
        assertThat(controller.state()).as("external stop was not finalized").isEqualTo(State.COMPLETE);
        assertThat(profiler.guardedStops)
                .as("a guarded cookie stop must never be issued in profiler-only mode")
                .isZero();
        assertThat(source.prepareCalls).as("eBPF source must stay untouched").isZero();
        assertThat(source.closeCalls).as("eBPF source must stay untouched").isZero();
        JsonObject footer =
                CaptureStreamFixture.rows(root.resolve("correlation.ndjson")).get(0);
        assertThat(footer.get("state").getAsString())
                .as("footer missing after external stop")
                .isEqualTo("profilerOnly");
    }

    @Test
    void coalescingCapture(@TempDir Path root) throws Exception {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        AgentConfig config = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("source.ndjson")
                        + ",jonoffcpudelivery=coalescing,sampling-policy=uniform,sampling-probability=1,asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("original.jfr"),
                nativeLibrary);
        assertThat(config.signalDelivery())
                .as("Native delivery option not parsed")
                .isEqualTo("coalescing");
        SignalCaptureController controller =
                new SignalCaptureController(config, new FakeProfiler(), new FakeSource(), millis -> {});
        controller.start();
        controller.stop();
        assertThat(controller.state()).as("Coalescing capture did not complete").isEqualTo(State.COMPLETE);
        List<JsonObject> rows = CaptureStreamFixture.rows(root.resolve("source.ndjson"));
        assertThat(rows.get(0).get("signalDelivery").getAsString())
                .as("Delivery policy missing from source")
                .isEqualTo("coalescing");
        assertThat(rows.get(rows.size() - 1).get("apStopResponse").getAsString())
                .as("Delivery policy missing from footer/receipt")
                .contains("delivery=coalescing");
    }

    @Test
    void nativeStackFailureIsRetained(@TempDir Path root) throws Exception {
        FakeSource source = new FakeSource();
        source.nativeStackFailure = true;
        SignalCaptureController controller = controller(root, new FakeProfiler(), source);
        controller.start();
        controller.stop();
        assertThat(controller.state())
                .as("Missing native stack rejected complete source")
                .isEqualTo(State.COMPLETE);
        List<JsonObject> rows = CaptureStreamFixture.rows(root.resolve("correlation.ndjson"));
        JsonObject observation = rows.stream()
                .filter(row -> row.get("recordType").getAsString().equals("observation"))
                .findFirst()
                .orElseThrow();
        assertThat(rows)
                .as("Native stack failure evidence not retained: stack record")
                .anyMatch(row -> row.get("recordType").getAsString().equals("stack"));
        assertThat(observation.get("userStackError").getAsString())
                .as("Native stack failure evidence not retained: userStackError")
                .isEqualTo("bpf_stack_error_-7");
        assertThat(rows.get(rows.size() - 2)
                        .getAsJsonObject("counters")
                        .getAsJsonObject("userspace")
                        .get("symbolizationFailures")
                        .getAsString())
                .as("Native stack failure evidence not retained: symbolizationFailures")
                .isEqualTo("1");
    }

    @Test
    void stopTimeoutRetainsOwnership(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        source.timeoutOnce = true;
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        assertThatIllegalStateException()
                .as("Expected native stop timeout")
                .isThrownBy(controller::stop)
                .withMessageContaining("stop_timeout");
        assertThat(profiler.active)
                .as("AP stopped while native ownership was unsettled")
                .isTrue();
        assertThat(source.closeCalls).as("timed-out native handle was freed").isZero();
        controller.stop();
        assertThat(controller.state()).as("retry did not complete").isEqualTo(State.COMPLETE);
    }

    @Test
    void ownershipMismatchNeverUsesOrdinaryStop(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        profiler.mismatchOnStop = true;
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        assertThatIllegalStateException()
                .as("Expected AP ownership failure")
                .isThrownBy(controller::stop)
                .withMessageContaining("ownership");
        assertThat(profiler.active).as("mismatch mutated the active AP capture").isTrue();
        assertThat(profiler.ordinaryStops)
                .as("ordinary AP stop fallback was used")
                .isZero();
        assertThat(source.closeCalls)
                .as("settled source handle was not closed before AP ownership check")
                .isEqualTo(1);
        assertThatIllegalStateException()
                .as("Expected terminal ownership failure")
                .isThrownBy(controller::stop);
        assertThat(profiler.guardedStops)
                .as("terminal ownership mismatch was retried against foreign AP state")
                .isEqualTo(1);
    }

    @Test
    void closeTimeoutRetainsOwnership(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        source.closeTimeoutOnce = true;
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        assertThatIllegalStateException()
                .as("Expected native close timeout")
                .isThrownBy(controller::stop)
                .withMessageContaining("close_timeout");
        assertThat(profiler.active)
                .as("AP stopped before native close ownership settled")
                .isTrue();
        controller.stop();
        assertThat(controller.state()).as("close retry did not complete").isEqualTo(State.COMPLETE);
        assertThat(source.closeCalls).as("native close was not retried").isEqualTo(2);
    }

    @Test
    void incompleteSourceStillFinalizesProfiler(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        source.incompleteStop = true;
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        assertThatIllegalStateException()
                .as("Expected incomplete native source")
                .isThrownBy(controller::stop)
                .withMessageContaining("Native source finalization was incomplete");
        assertThat(profiler.active)
                .as("owned AP capture remained active after terminal native failure")
                .isFalse();
        assertThat(profiler.guardedStops)
                .as("owned AP capture remained active after terminal native failure")
                .isEqualTo(1);
        assertThat(source.closeCalls)
                .as("incomplete native source was not closed")
                .isEqualTo(1);
        assertThat(CaptureStreamFixture.rows(root.resolve("correlation.ndjson")))
                .as("incomplete native source received a success footer")
                .hasSize(2);
    }

    @Test
    void nativeStopErrorStillCleansUp(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        source.stopError = true;
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        assertThatIllegalStateException()
                .as("Expected native stop error")
                .isThrownBy(controller::stop)
                .withMessageContaining("after safe close and AP finalization");
        assertThat(profiler.active)
                .as("native stop error stranded the owned AP capture")
                .isFalse();
        assertThat(profiler.guardedStops)
                .as("native stop error stranded the owned AP capture")
                .isEqualTo(1);
        assertThat(source.closeCalls)
                .as("native stop error did not fall back to safe close")
                .isEqualTo(1);
    }

    @Test
    void profilerTerminationTriggersPollCleanup(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        profiler.finalizeExternally();
        controller.pollProfiler();
        assertThat(controller.state())
                .as("owned-status poll did not quiesce and finalize source")
                .isEqualTo(State.COMPLETE);
        assertThat(source.closeCalls)
                .as("owned-status poll did not close native source")
                .isEqualTo(1);
    }

    @Test
    void lostStopResponseUsesIdentityQuery(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        profiler.loseStopResponse = true;
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        controller.stop();
        assertThat(controller.state())
                .as("retained identity-specific receipt did not recover finalization")
                .isEqualTo(State.COMPLETE);
        assertThat(profiler.receiptQueries)
                .as("controller did not query retained receipt exactly once")
                .isEqualTo(1);
    }

    @Test
    void unfinalizedApNeverPublishesFooter(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        profiler.finalized = false;
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        assertThatIllegalStateException()
                .as("Expected AP finalization failure")
                .isThrownBy(controller::stop)
                .withMessageContaining("did not finalize");
        assertThat(CaptureStreamFixture.rows(root.resolve("correlation.ndjson")))
                .as("incomplete AP capture received a success footer")
                .hasSize(2);
        assertThat(source.closeCalls).as("settled native handle was not closed").isEqualTo(1);
    }

    @Test
    void busyProfilerIsNeverTakenOver(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        profiler.busy = true;
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        assertThatIllegalStateException()
                .as("Expected busy AP rejection")
                .isThrownBy(controller::start)
                .withMessageContaining("already active");
        assertThat(source.prepareCalls)
                .as("native source prepared before AP ownership preflight")
                .isZero();
        assertThat(profiler.ordinaryStops).as("busy AP was stopped").isZero();
        assertThat(profiler.guardedStops).as("busy AP was stopped").isZero();
    }

    @Test
    void malformedStartUsesUuidGuard(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        profiler.malformedStart = true;
        FakeSource source = new FakeSource();
        SignalCaptureController controller = controller(root, profiler, source);
        // Expected protocol rejection after AP may have started.
        assertThatIllegalArgumentException()
                .as("Expected malformed start response")
                .isThrownBy(controller::start);
        assertThat(profiler.guardedStops)
                .as("failed start did not use UUID-guarded recovery")
                .isEqualTo(1);
        assertThat(profiler.ordinaryStops)
                .as("failed start used ordinary AP stop")
                .isZero();
        assertThat(source.closeCalls)
                .as("prepared native source was not closed")
                .isEqualTo(1);
        JsonObject manifest = JsonParser.parseString(Files.readString(controller.manifestPath()))
                .getAsJsonObject();
        assertThat(manifest.get("complete").getAsBoolean())
                .as("failed start marked complete")
                .isFalse();
    }

    @Test
    void concurrentLifecycleHasOneOwner(@TempDir Path root) throws Exception {
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
        assertThat(failure.get()).as("concurrent start failed unexpectedly").isNull();
        assertThat(started.get())
                .as("concurrent starts did not select exactly one owner")
                .isEqualTo(1);
        assertThat(rejectedStarts.get())
                .as("concurrent starts did not select exactly one owner")
                .isEqualTo(1);
        assertThat(source.prepareCalls)
                .as("concurrent starts prepared more than one source")
                .isEqualTo(1);
        assertThat(source.enableCalls)
                .as("concurrent starts enabled more than one source")
                .isEqualTo(1);

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
        assertThat(failure.get()).as("concurrent stop failed unexpectedly").isNull();
        assertThat(stopped.get())
                .as("idempotent concurrent stops did not both complete")
                .isEqualTo(2);
        assertThat(source.stopCalls)
                .as("concurrent stops finalized owned resources more than once")
                .isEqualTo(1);
        assertThat(source.closeCalls)
                .as("concurrent stops finalized owned resources more than once")
                .isEqualTo(1);
        assertThat(profiler.guardedStops)
                .as("concurrent stops finalized owned resources more than once")
                .isEqualTo(1);
        assertThatIllegalStateException()
                .as("Expected start after terminal completion to fail")
                .isThrownBy(controller::start);
        assertThat(source.prepareCalls)
                .as("terminal controller admitted a new source start")
                .isEqualTo(1);
    }

    private static void runConcurrently(Runnable action, AtomicReference<Throwable> failure) {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[2];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(
                    () -> {
                        ready.countDown();
                        try {
                            // Poll tightly so both racers are released as close together as possible.
                            await().atMost(10, SECONDS)
                                    .pollDelay(Duration.ZERO)
                                    .pollInterval(Duration.ofMillis(1))
                                    .until(() -> go.getCount() == 0);
                            action.run();
                        } catch (Throwable unexpected) {
                            failure.compareAndSet(null, unexpected);
                        }
                    },
                    "controller-race-" + i);
            threads[i].start();
        }
        await().atMost(10, SECONDS).until(() -> ready.getCount() == 0);
        go.countDown();
        for (Thread thread : threads) {
            await().atMost(20, SECONDS).until(() -> !thread.isAlive());
        }
    }

    @Test
    void nativeOptionParsing(@TempDir Path root) throws Exception {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        Path jfr = root.resolve("combined.jfr");
        AgentConfig config = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("correlation.ndjson")
                        + ",sampling-policy=uniform,sampling-probability=0.1,min-off-cpu-micros=7,asprofpath="
                        + ap
                        + ",event=cpu,alloc=1m,jfrsync=profile,file="
                        + jfr,
                nativeLibrary);
        assertThat(config.sampling().admission()).isInstanceOf(SamplingConfig.Uniform.class);
        SamplingConfig.Uniform uniform =
                (SamplingConfig.Uniform) config.sampling().admission();
        assertThat(uniform.probability().toPlainString())
                .as("requested probability not retained")
                .isEqualTo("0.1");
        assertThat(uniform.probabilityThreshold())
                .as("probability threshold was not rounded down")
                .isEqualTo(429_496_729L);
        JsonObject persisted = config.sampling().json();
        assertThat(persisted.getAsJsonObject("admission").get("probability").getAsString())
                .as("requested/effective sampling policy not persisted: %s", persisted)
                .isEqualTo("0.1");
        assertThat(persisted
                        .getAsJsonObject("admission")
                        .get("probabilityThreshold")
                        .getAsLong())
                .as("requested/effective sampling policy not persisted: %s", persisted)
                .isEqualTo(429_496_729L);
        assertThat(persisted.get("minOffCpuMicros").getAsLong())
                .as("requested/effective sampling policy not persisted: %s", persisted)
                .isEqualTo(7);
        assertThat(persisted.get("maxOffCpuMicros").isJsonNull())
                .as("requested/effective sampling policy not persisted: %s", persisted)
                .isTrue();
        assertThat(config.sampling().minOffCpuMicros())
                .as("optional duration policy lost")
                .isEqualTo(7);
        assertThat(config.sampling().maxOffCpuMicros())
                .as("optional duration policy lost")
                .isNull();
        AgentConfig proportional = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("proportional.ndjson")
                        + ",sampling-policy=proportional,record-all-above-micros=250,asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("proportional.jfr"),
                nativeLibrary);
        assertThat(proportional.sampling().admission())
                .as("proportional native options not parsed")
                .isEqualTo(new SamplingConfig.Proportional(250));
        assertThat(config.sampling().reasons())
                .as("native options must default to blocked intervals")
                .isEqualTo(SamplingConfig.DEFAULT_REASONS);
        AgentConfig reasons = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("reasons.ndjson")
                        + ",sampling-policy=proportional,record-all-above-micros=250,"
                        + "sampling-reasons=runnable+blocked,asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("reasons.jfr"),
                nativeLibrary);
        assertThat(reasons.sampling().orderedReasons())
                .as("sampling-reasons native option not parsed: %s", reasons.sampling())
                .isEqualTo(List.of(SamplingConfig.OffCpuReason.BLOCKED, SamplingConfig.OffCpuReason.RUNNABLE));
        assertThat(config.timeSplit())
                .as("native options must default to schedInfo")
                .isEqualTo(TimeSplitConfig.DEFAULT);
        AgentConfig splitOff = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("split-off.ndjson")
                        + ",sampling-policy=proportional,record-all-above-micros=250,time-split=off,asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("split-off.jfr"),
                nativeLibrary);
        assertThat(splitOff.timeSplit().json().toString())
                .as("time-split native option not parsed: %s", splitOff.timeSplit())
                .isEqualTo("{\"source\":\"off\"}");
        assertThatIllegalArgumentException()
                .as("Expected unknown time-split source rejection")
                .isThrownBy(() -> AgentConfig.parseNativeOptions(
                        "jonoffcpuoutput="
                                + root.resolve("split-wakeup.ndjson")
                                + ",sampling-policy=proportional,record-all-above-micros=250,time-split=wakeup,"
                                + "asprofpath="
                                + ap
                                + ",event=cpu,file="
                                + root.resolve("split-wakeup.jfr"),
                        nativeLibrary))
                .withMessageContaining("timeSplit source");
        assertThat(config.asyncProfilerOptions()).as("AP tail changed").contains("event=cpu,alloc=1m,jfrsync=profile");
        assertThat(config.jfrOutput()).as("AP output path not retained").isEqualTo(jfr);

        assertThatIllegalArgumentException()
                .as("Expected AP output pattern rejection")
                .isThrownBy(() -> AgentConfig.parseNativeOptions(
                        "jonoffcpuoutput="
                                + root.resolve("pattern.ndjson")
                                + ",sampling-policy=uniform,sampling-probability=1,asprofpath="
                                + ap
                                + ",event=cpu,file="
                                + root.resolve("profile-%p.jfr"),
                        nativeLibrary))
                .withMessageContaining("patterns");

        Path racedJfr = root.resolve("raced.jfr");
        AgentConfig raced = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput=" + root.resolve("raced.ndjson")
                        + ",sampling-policy=uniform,sampling-probability=1,asprofpath=" + ap + ",event=cpu,file="
                        + racedJfr,
                nativeLibrary);
        Files.writeString(racedJfr, "foreign");
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        assertThatIOException()
                .as("Expected raced JFR reservation failure")
                .isThrownBy(() -> new SignalCaptureController(raced, profiler, source, millis -> {}).start());
        assertThat(racedJfr).as("existing raced JFR was changed").hasContent("foreign");
        assertThat(source.prepareCalls)
                .as("native prepare ran after JFR reservation failure")
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "resume", "stop", "dump", "status", "metrics", "list", "version"})
    void forwardedProfilerActionsAreRejected(String action, @TempDir Path root) throws IOException {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        assertThatIllegalArgumentException()
                .as("Expected forwarded AP action rejection: " + action)
                .isThrownBy(() -> AgentConfig.parseNativeOptions(
                        "jonoffcpuoutput="
                                + root.resolve("rejected-" + action + ".ndjson")
                                + ",sampling-policy=uniform,sampling-probability=1,asprofpath="
                                + ap
                                + ","
                                + action
                                + ",file="
                                + root.resolve("rejected-" + action + ".jfr"),
                        nativeLibrary))
                .withMessageContaining("controller-owned option");
    }

    @Test
    void yamlConfigParsing(@TempDir Path root) throws Exception {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        Path configFile = root.resolve("jonoffcpu.yaml");
        Files.writeString(configFile, """
                correlationOutput: %s
                asyncProfilerLibrary: %s
                nativeCollectorLibrary: %s
                asyncProfilerOptions: event=cpu,file=%s
                signalDelivery: coalescing
                sampling:
                  minOffCpuMicros: 10
                  maxOffCpuMicros: 1000
                  admission:
                    policy: uniform
                    probability: "0.125"
                """.formatted(
                        root.resolve("correlation.ndjson"), ap, nativeLibrary, root.resolve("combined.jfr")));
        AgentConfig config = AgentConfig.parse(configFile.toString());
        assertThat(config.signalDelivery())
                .as("YAML delivery policy not parsed")
                .isEqualTo("coalescing");
        assertThat(config.sampling())
                .as("YAML sampling policy not parsed exactly: %s", config.sampling())
                .isEqualTo(new SamplingConfig(
                        SamplingConfig.DEFAULT_REASONS,
                        10L,
                        1000L,
                        new SamplingConfig.Uniform(new BigDecimal("0.125"), 536_870_912L)));
        assertThat(config.timeSplit())
                .as("YAML must default to timeSplit schedInfo")
                .isEqualTo(TimeSplitConfig.DEFAULT);
        AgentConfig splitOff =
                AgentConfig.parse(splitBase(root, ap, nativeLibrary) + "timeSplit:\n  source: \"off\"\n");
        assertThat(splitOff.timeSplit().source())
                .as("YAML timeSplit not parsed: %s", splitOff.timeSplit())
                .isEqualTo(TimeSplitConfig.Source.OFF);
        assertThatIllegalArgumentException()
                .as("Expected unknown YAML key rejection")
                .isThrownBy(() -> AgentConfig.parse("correlationOutput: /tmp/a\nunknownOption: true\n"))
                .withMessageContaining("Unknown");
        assertThatIllegalArgumentException()
                .as("Expected duplicate YAML key rejection")
                .isThrownBy(() -> AgentConfig.parse("correlationOutput: /tmp/a\ncorrelationOutput: /tmp/b\n"))
                .withMessageContaining("Invalid agent YAML");
    }

    @ParameterizedTest
    @MethodSource
    void yamlTimeSplitRejections(String rejected, @TempDir Path root) throws IOException {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        String splitBase = splitBase(root, ap, nativeLibrary);
        assertThatIllegalArgumentException()
                .as("Expected timeSplit rejection: " + rejected)
                .isThrownBy(() -> AgentConfig.parse(splitBase + rejected));
    }

    static Stream<Named<String>> yamlTimeSplitRejections() {
        return yamlCases(
                "timeSplit:\n  source: wakeup\n",
                "timeSplit:\n  source: \"off\"\n  extra: 1\n",
                "timeSplit: {}\n",
                "timeSplit: \"off\"\n");
    }

    private static String splitBase(Path root, Path ap, Path nativeLibrary) {
        return """
                correlationOutput: %s
                asyncProfilerLibrary: %s
                nativeCollectorLibrary: %s
                asyncProfilerOptions: event=cpu,file=%s
                sampling:
                  admission:
                    policy: proportional
                    recordAllAboveMicros: 250
                """.formatted(root.resolve("split.ndjson"), ap, nativeLibrary, root.resolve("split.jfr"));
    }

    /** YAML snippets as parameterized cases, each named by a one-line rendering of the snippet. */
    private static Stream<Named<String>> yamlCases(String... yamls) {
        return Stream.of(yamls).map(yaml -> Named.of(displayYaml(yaml), yaml));
    }

    private static String displayYaml(String yaml) {
        return yaml.isEmpty() ? "<empty>" : yaml.strip().replace("\n", " ⏎ ");
    }

    @ParameterizedTest
    @CsvSource({"0.0000000003, 1", "0.5, 2147483648", "1.000, 4294967296"})
    void validSamplingProbabilityParsing(String probability, String threshold, @TempDir Path root) throws Exception {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        AgentConfig config = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("valid.ndjson")
                        + ",sampling-policy=uniform,sampling-probability="
                        + probability
                        + ",asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("valid.jfr"),
                nativeLibrary);
        assertThat(config.sampling().admission()).isInstanceOf(SamplingConfig.Uniform.class);
        SamplingConfig.Uniform uniform =
                (SamplingConfig.Uniform) config.sampling().admission();
        assertThat(uniform.probability().toPlainString())
                .as("requested probability spelling was not retained: " + probability)
                .isEqualTo(probability);
        assertThat(Long.toString(uniform.probabilityThreshold()))
                .as("wrong effective threshold for " + probability)
                .isEqualTo(threshold);
    }

    // Exactly zero is the explicit off switch; a positive value that rounds to no draws is a mistake.
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000"})
    void zeroSamplingProbabilityIsNone(String zero, @TempDir Path root) throws Exception {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        AgentConfig config = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("zero.ndjson")
                        + ",sampling-policy=uniform,sampling-probability="
                        + zero
                        + ",asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("zero.jfr"),
                nativeLibrary);
        assertThat(config.profilerOnly()).as("zero != none: " + zero).isTrue();
        assertThat(config.sampling().admission()).as("zero != none: " + zero).isEqualTo(NONE.admission());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.1", ".5", "1.0001", "1e-1", "NaN", "0.0000000001", "0.0000000002"})
    void invalidSamplingProbabilityIsRejected(String invalid, @TempDir Path root) throws Exception {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        assertThatIllegalArgumentException()
                .as("Expected invalid probability rejection: " + invalid)
                .isThrownBy(() -> AgentConfig.parseNativeOptions(
                        "jonoffcpuoutput="
                                + root.resolve("invalid.ndjson")
                                + ",sampling-policy=uniform,sampling-probability="
                                + invalid
                                + ",asprofpath="
                                + ap
                                + ",event=cpu,file="
                                + root.resolve("invalid.jfr"),
                        nativeLibrary))
                .satisfies(expected -> assertThat(expected.getMessage().toLowerCase(Locale.ROOT))
                        .as("wrong invalid probability failure for " + invalid)
                        .contains("probability"));
    }

    private static final String SAMPLING_YAML_PREFIX = """
            correlationOutput: %s
            asyncProfilerLibrary: %s
            nativeCollectorLibrary: %s
            asyncProfilerOptions: event=cpu,file=%s
            """;

    private static String samplingYaml(Path root, String sampling) throws IOException {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        return SAMPLING_YAML_PREFIX.formatted(
                        root.resolve("sampling.ndjson"), ap, nativeLibrary, root.resolve("sampling.jfr"))
                + sampling;
    }

    @ParameterizedTest
    @MethodSource
    void validSamplingConfigParsing(String sampling, String policy, @TempDir Path root) throws Exception {
        String yaml = samplingYaml(root, sampling);
        AgentConfig config = AgentConfig.parse(yaml);
        assertThat(config.sampling().admission().policy())
                .as("policy not parsed: " + yaml)
                .isEqualTo(policy);
        assertThat(config.profilerOnly()).as("profiler-only mismatch: " + yaml).isEqualTo(policy.equals("none"));
    }

    static Stream<Arguments> validSamplingConfigParsing() {
        return Stream.of(
                        new String[] {
                            "sampling:\n  admission:\n    policy: proportional\n    recordAllAboveMicros: 10000\n",
                            "proportional"
                        },
                        new String[] {
                            "sampling:\n  minOffCpuMicros: 100\n  admission:\n    policy: proportional\n"
                                    + "    recordAllAboveMicros: 1\n",
                            "proportional"
                        },
                        new String[] {"sampling:\n  admission:\n    policy: none\n", "none"},
                        new String[] {"sampling:\n  admission:\n    policy: uniform\n    probability: 1\n", "uniform"},
                        new String[] {
                            "sampling:\n  reasons: [preempted, blocked]\n  admission:\n    policy: uniform\n"
                                    + "    probability: 1\n",
                            "uniform"
                        })
                .map(c -> Arguments.of(Named.of(displayYaml(c[0]), c[0]), c[1]));
    }

    @ParameterizedTest
    @MethodSource
    void invalidSamplingConfigIsRejected(String invalid, @TempDir Path root) throws Exception {
        String yaml = samplingYaml(root, invalid);
        assertThatIllegalArgumentException()
                .as("Expected sampling config rejection: " + invalid)
                .isThrownBy(() -> AgentConfig.parse(yaml));
    }

    static Stream<Named<String>> invalidSamplingConfigIsRejected() {
        return yamlCases(
                "",
                "sampling: {}\n",
                "sampling:\n  admission: {}\n",
                "sampling:\n  admission:\n    policy: linear\n",
                "sampling:\n  admission:\n    policy: proportional\n",
                "sampling:\n  admission:\n    policy: proportional\n    recordAllAboveMicros: 0\n",
                "sampling:\n  admission:\n    policy: proportional\n    recordAllAboveMicros: 5\n    probability: 1\n",
                "sampling:\n  admission:\n    policy: uniform\n",
                "sampling:\n  admission:\n    policy: uniform\n    probability: 1\n    recordAllAboveMicros: 5\n",
                "sampling:\n  minOffCpuMicros: 1\n  admission:\n    policy: none\n",
                "sampling:\n  maxOffCpuMicros: 1\n  admission:\n    policy: none\n",
                "sampling:\n  minOffCpuMicros: 5\n  maxOffCpuMicros: 5\n  admission:\n    policy: none\n",
                "sampling:\n  sampleProbability: 1\n  admission:\n    policy: none\n",
                "sampleProbability: 1\n",
                "sampling:\n  reasons: []\n  admission:\n    policy: uniform\n    probability: 1\n",
                "sampling:\n  reasons: [blocked, blocked]\n  admission:\n    policy: uniform\n    probability: 1\n",
                "sampling:\n  reasons: [sleeping]\n  admission:\n    policy: uniform\n    probability: 1\n",
                "sampling:\n  reasons: blocked\n  admission:\n    policy: uniform\n    probability: 1\n",
                "sampling:\n  reasons: [blocked]\n  admission:\n    policy: none\n");
    }

    @Test
    void samplingConfigJson(@TempDir Path root) throws Exception {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        String prefix = SAMPLING_YAML_PREFIX;
        AgentConfig proportional = AgentConfig.parse(
                prefix.formatted(root.resolve("p.ndjson"), ap, nativeLibrary, root.resolve("p.jfr"))
                        + "sampling:\n  minOffCpuMicros: 100\n  admission:\n    policy: proportional\n    recordAllAboveMicros: 10000\n");
        assertThat(proportional.sampling().json().toString())
                .as("unexpected sampling JSON")
                .isEqualTo("{\"reasons\":[\"blocked\"],\"minOffCpuMicros\":100,\"maxOffCpuMicros\":null,"
                        + "\"admission\":{\"policy\":\"proportional\",\"recordAllAboveMicros\":10000}}");
        // Reasons are serialized in canonical order whatever the order they were given in.
        AgentConfig everything =
                AgentConfig.parse(prefix.formatted(root.resolve("r.ndjson"), ap, nativeLibrary, root.resolve("r.jfr"))
                        + "sampling:\n  reasons: [preempted, blocked, runnable]\n  admission:\n"
                        + "    policy: uniform\n    probability: 1\n");
        assertThat(everything.sampling().json().get("reasons").toString())
                .as("reasons not canonical: %s", everything.sampling().json())
                .isEqualTo("[\"blocked\",\"runnable\",\"preempted\"]");
        assertThat(AgentConfig.parse(
                                prefix.formatted(root.resolve("n.ndjson"), ap, nativeLibrary, root.resolve("n.jfr"))
                                        + "sampling:\n  admission:\n    policy: none\n")
                        .sampling()
                        .json()
                        .get("reasons")
                        .isJsonNull())
                .as("policy none must not select reasons")
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "/data/jonoffcpu-capture.ndjson, .manifest.json, /data/jonoffcpu-capture.manifest.json",
        "/data/capture,                  .manifest.json, /data/capture.manifest.json",
        "/data/.hidden,                  .manifest.json, /data/.hidden.manifest.json",
        "/data/run.v1/capture,           .manifest.json, /data/run.v1/capture.manifest.json",
        "/data/capture.tar.gz,           .manifest.json, /data/capture.tar.manifest.json",
        // The default JFR sibling.
        "/data/capture.ndjson,           .jfr,           /data/capture.jfr",
    })
    void siblingCaptureFileNames(String path, String suffix, String expected) {
        assertThat(ManifestStore.sibling(Path.of(path), suffix))
                .as("sibling of " + path)
                .isEqualTo(Path.of(expected));
    }

    @Test
    void proportionalAdmissionThreshold() {
        long certain = SamplingConfig.CERTAIN_ADMISSION;
        long reference = 10_000_000L;
        assertThat(SamplingConfig.admissionThreshold(reference, reference))
                .as("reference must be certain")
                .isEqualTo(certain);
        assertThat(SamplingConfig.admissionThreshold(Long.MAX_VALUE, reference))
                .as("long wait must be certain")
                .isEqualTo(certain);
        assertThat(SamplingConfig.admissionThreshold(-1L, reference))
                .as("u64 max must be certain")
                .isEqualTo(certain);
        assertThat(SamplingConfig.admissionThreshold(reference / 10, reference))
                .as("tenth must be 2^32/10")
                .isEqualTo(certain / 10);
        assertThat(SamplingConfig.admissionThreshold(0, reference))
                .as("zero duration never admits")
                .isZero();
        assertThat(SamplingConfig.admissionThreshold(1, reference))
                .as("one nanosecond threshold")
                .isEqualTo(429);
        long shifted = 10_000_000_000L;
        assertThat(SamplingConfig.admissionThreshold(shifted / 2, shifted))
                .as("shifted half")
                .isEqualTo(certain / 2);
        assertThat(SamplingConfig.admissionThreshold(shifted - 1, shifted))
                .as("shifted just below")
                .isGreaterThanOrEqualTo(certain - 2)
                .isLessThan(certain);
        assertThat(SamplingConfig.admissionThreshold(-2L, -1L))
                .as("u64 max reference")
                .isEqualTo(certain);
        SamplingConfig proportional =
                new SamplingConfig(SamplingConfig.DEFAULT_REASONS, null, null, new SamplingConfig.Proportional(10_000));
        assertThat(proportional.admissionThreshold(1_000_000))
                .as("policy delegation")
                .isEqualTo(certain / 10);
        assertThat(new SamplingConfig(
                                SamplingConfig.DEFAULT_REASONS,
                                null,
                                null,
                                new SamplingConfig.Uniform(BigDecimal.ONE, certain))
                        .admissionThreshold(1))
                .as("uniform ignores duration")
                .isEqualTo(certain);
    }

    @Test
    void unsignedStopCountersParse() {
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
        assertThat(stopped.manifestCounters()).as("u64 AP counter rejected").containsEntry("submittedSamples", max);
    }

    private static final SamplingConfig NONE = new SamplingConfig(null, null, null, new SamplingConfig.None());
    private static final SamplingConfig SPARSE = new SamplingConfig(
            SamplingConfig.DEFAULT_REASONS, null, null, SamplingConfig.uniform(new BigDecimal("0.0000000233")));

    private static SignalCaptureController controller(Path root, FakeProfiler profiler, FakeSource source)
            throws IOException {
        return controller(root, profiler, source, SPARSE);
    }

    private static SignalCaptureController controller(
            Path root, FakeProfiler profiler, FakeSource source, SamplingConfig sampling) throws IOException {
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
                sampling,
                TimeSplitConfig.DEFAULT,
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
        private JsonObject sampling;
        private JsonObject timeSplit;
        private long targetPid;
        private int signal;
        private String session;
        private JsonObject captureEnd;

        @Override
        public String prepare(String configJson) {
            prepareCalls++;
            JsonObject config = JsonParser.parseString(configJson).getAsJsonObject();
            source = Path.of(config.get("outputPath").getAsString());
            sampling = config.getAsJsonObject("sampling");
            timeSplit = config.getAsJsonObject("timeSplit");
            targetPid = config.get("targetPid").getAsLong();
            JsonObject result = success("prepared");
            result.addProperty("handle", HANDLE);
            result.addProperty("sourcePath", source.toString());
            result.addProperty("targetPid", config.get("targetPid").getAsLong());
            result.addProperty("hostTgid", config.get("targetPid").getAsLong());
            result.add("sampling", sampling.deepCopy());
            result.add("timeSplit", timeSplit.deepCopy());
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
            result.add("sampling", sampling.deepCopy());
            result.add("timeSplit", timeSplit.deepCopy());
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
            start.addProperty("schemaVersion", 4);
            start.addProperty("recordType", "captureStart");
            start.addProperty("sourceId", "jonoffcpu.offcpu.v1");
            start.addProperty("sessionId", session);
            start.addProperty("captureEpoch", 7);
            start.addProperty("signal", signal);
            start.addProperty("signalDelivery", signal >= 34 ? "queued" : "coalescing");
            start.add("sampling", sampling.deepCopy());
            start.add("timeSplit", timeSplit.deepCopy());
            start.addProperty("startedMonotonicNanos", "9");
            start.addProperty("hostTgid", targetPid);
            start.addProperty("targetPid", targetPid);
            start.addProperty("registrationToken", "0123456789abcdef");
            start.addProperty("processGenerationNs", "9");
            start.addProperty("timeNamespaceInode", "42");
            start.addProperty("pidNamespaceDevice", "4");
            start.addProperty("pidNamespaceInode", "43");
            try {
                Files.write(source, CaptureStreamFixture.encode(List.of(start)));
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
                    observation.addProperty("schemaVersion", 4);
                    observation.addProperty("recordType", "observation");
                    observation.addProperty("sourceId", "jonoffcpu.offcpu.v1");
                    observation.addProperty("sessionId", session);
                    observation.addProperty("captureEpoch", 7);
                    observation.addProperty("correlationId", "0000000700000001");
                    observation.addProperty("hostTgid", targetPid);
                    observation.addProperty("hostTid", targetPid);
                    observation.addProperty("targetTgid", targetPid);
                    observation.addProperty("targetTid", targetPid);
                    observation.addProperty(
                            "admissionThreshold",
                            sampling.getAsJsonObject("admission")
                                    .get("probabilityThreshold")
                                    .getAsLong());
                    observation.addProperty("processGenerationNs", "9");
                    observation.addProperty("threadGenerationNs", "9");
                    observation.addProperty("registrationToken", "0123456789abcdef");
                    observation.addProperty("startMonotonicNanos", "9");
                    observation.addProperty("endMonotonicNanos", "10");
                    observation.addProperty("signalResult", 0);
                    observation.addProperty("offCpuReason", "blocked");
                    observation.addProperty("prevTaskState", 1);
                    observation.addProperty("preempted", false);
                    observation.addProperty("runqueueNanos", "1");
                    observation.addProperty("comm", "fixture");
                    // One announced stack for the kernel side; the user side failed, so it has no record.
                    JsonObject stack = new JsonObject();
                    stack.addProperty("schemaVersion", 4);
                    stack.addProperty("recordType", "stack");
                    stack.addProperty("sourceId", "jonoffcpu.offcpu.v1");
                    stack.addProperty("sessionId", session);
                    stack.addProperty("captureEpoch", 7);
                    stack.addProperty("stackId", 5);
                    stack.add("frames", new com.google.gson.JsonArray());
                    CaptureStreamFixture.append(source, stack);
                    observation.addProperty("kernelStackId", 5);
                    observation.addProperty("userStackId", -7);
                    observation.addProperty("userStackError", "bpf_stack_error_-7");
                    JsonObject userspace =
                            captureEnd.getAsJsonObject("counters").getAsJsonObject("userspace");
                    userspace.addProperty("receivedObservations", "1");
                    userspace.addProperty("writtenObservations", "1");
                    userspace.addProperty("symbolizationFailures", "1");
                    CaptureStreamFixture.append(source, observation);
                }
                CaptureStreamFixture.append(source, captureEnd);
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
                    CaptureStreamFixture.append(source, captureEnd);
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
            end.addProperty("schemaVersion", 4);
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
                "admissionRejections",
                "selectedIntervals",
                "sequenceExhaustions",
                "sequenceContentions",
                "threadStateFailures",
                "kernelStackFailures",
                "userStackFailures",
                "signalFailures",
                "ringReserveFailures",
                "targetNamespaceFailures",
                "switchOutsBlocked",
                "switchOutsRunnable",
                "switchOutsPreempted",
                "reasonRejections",
                "reasonRejectedDurationMicros",
                "runqueueInversions"
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
}
