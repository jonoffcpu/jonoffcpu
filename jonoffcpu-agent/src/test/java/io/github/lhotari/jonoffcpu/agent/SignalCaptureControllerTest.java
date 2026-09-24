// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.lhotari.jonoffcpu.agent.ManifestProto.CaptureMode;
import io.github.lhotari.jonoffcpu.agent.ManifestProto.FailureCode;
import io.github.lhotari.jonoffcpu.agent.ManifestProto.Manifest;
import io.github.lhotari.jonoffcpu.agent.ManifestProto.ManifestState;
import io.github.lhotari.jonoffcpu.agent.SignalCaptureController.State;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.CaptureEnd;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.CaptureFinalized;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.CaptureStart;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.CaptureState;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.FinalizedState;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.IncompleteReason;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.KernelCounters;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.NoAdmission;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.Observation;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.OffCpuReason;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.ProportionalAdmission;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.Record;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.Sampling;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.SignalDelivery;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.Stack;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.TimeSplit;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.TimeSplitSource;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.UniformAdmission;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.UserspaceCounters;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.VerifiedIdentity;
import io.github.lhotari.jonoffcpu.capture.CaptureRecordFixture;
import io.github.lhotari.jonoffcpu.capture.CollectorProto.Closed;
import io.github.lhotari.jonoffcpu.capture.CollectorProto.CollectorError;
import io.github.lhotari.jonoffcpu.capture.CollectorProto.CollectorErrorCode;
import io.github.lhotari.jonoffcpu.capture.CollectorProto.CollectorReply;
import io.github.lhotari.jonoffcpu.capture.CollectorProto.CollectorState;
import io.github.lhotari.jonoffcpu.capture.CollectorProto.EnableRequest;
import io.github.lhotari.jonoffcpu.capture.CollectorProto.Enabled;
import io.github.lhotari.jonoffcpu.capture.CollectorProto.PrepareRequest;
import io.github.lhotari.jonoffcpu.capture.CollectorProto.Prepared;
import io.github.lhotari.jonoffcpu.capture.CollectorProto.Stopped;
import io.github.lhotari.jonoffcpu.capture.ProtoJson;
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
        Manifest manifest = manifest(controller.stop());
        assertThat(controller.state()).as("capture did not complete").isEqualTo(State.COMPLETE);
        assertThat(manifest.getComplete()).as("manifest not complete").isTrue();
        assertThat(manifest.getState()).isEqualTo(ManifestState.MANIFEST_STATE_COMPLETE);
        assertThat(manifest.getMode()).isEqualTo(CaptureMode.CAPTURE_MODE_SIGNAL_CAPTURE);
        assertThat(manifest.hasFailure())
                .as("a completed manifest keeps no failure")
                .isFalse();
        assertThat(manifest.getAnalysisInputs().getCaptureEpoch())
                .as("wrong epoch")
                .isEqualTo(7);
        assertThat(manifest.getAnalysisInputs().getApStats().getSubmittedSamples())
                .as("AP counters missing")
                .isZero();
        // The manifest records the collector's replies exactly as they were returned.
        assertThat(manifest.getNativePrepare()).isEqualTo(source.prepareReply);
        assertThat(manifest.getNativeEnable()).isEqualTo(source.enableReply);
        assertThat(manifest.getNativeStop()).isEqualTo(source.stopReply);
        assertThat(manifest.getNativeClose()).isEqualTo(source.closeReply);
        assertThat(manifest.getSourceCaptureEnd()).isEqualTo(source.captureEnd);
        Path correlation = root.resolve("correlation.pb");
        assertThat(correlation).as("source artifact missing").isRegularFile();
        assertThat(profiler.ordinaryStops).as("ordinary AP stop was used").isZero();
        assertThat(source.closeCalls).as("native handle was not closed once").isEqualTo(1);
        List<Record> records = CaptureRecordFixture.read(correlation);
        Record last = records.get(records.size() - 1);
        assertThat(last.hasCaptureFinalized()).as("final footer missing").isTrue();
        CaptureFinalized footer = last.getCaptureFinalized();
        assertThat(footer.getState()).isEqualTo(FinalizedState.FINALIZED_STATE_COMPLETE);
        assertThat(footer.getApStopResponse()).as("receipt not retained").contains("finalized=true");
        assertThat(footer.getAnalysisInputs())
                .as("the footer and the manifest carry the same analysis inputs")
                .isEqualTo(manifest.getAnalysisInputs());
        assertThat(footer.getAnalysisInputs().getSourceArtifact().getRawBytes())
                .as("the footer covers the stream before it")
                .isEqualTo(Files.size(correlation) - CaptureRecordFixture.encode(last).length);
        assertThat(manifest.getCorrelationArtifact().getBytes()).isEqualTo(Files.size(correlation));
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
        Path manifestPath = controller.stop();
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
        Manifest manifest = manifest(manifestPath);
        assertThat(manifest.getComplete()).as("manifest not complete").isTrue();
        assertThat(manifest.getMode()).as("manifest mode missing").isEqualTo(CaptureMode.CAPTURE_MODE_PROFILER_ONLY);
        assertThat(manifest.hasNativePrepare())
                .as("no collector reply in profiler-only mode")
                .isFalse();
        assertThat(manifest.getAnalysisInputs().hasSourceArtifact())
                .as("profiler-only analysis inputs name no source")
                .isFalse();
        assertThat(manifest.getAnalysisInputs().getSampling()).isEqualTo(NONE);
        List<Record> records = CaptureRecordFixture.read(root.resolve("correlation.pb"));
        assertThat(records)
                .as("profiler-only stream must hold exactly one record")
                .hasSize(1);
        CaptureFinalized footer = records.get(0).getCaptureFinalized();
        assertThat(footer.getState())
                .as("footer state must be profiler-only")
                .isEqualTo(FinalizedState.FINALIZED_STATE_PROFILER_ONLY);
        assertThat(footer.getAnalysisInputs()).isEqualTo(manifest.getAnalysisInputs());
        assertThat(Files.size(root.resolve("correlation.jfr")))
                .as("JFR recording missing")
                .isPositive();
        assertThat(controller.stop()).as("repeated stop must be idempotent").isEqualTo(manifestPath);
    }

    @Test
    void profilerOnlyExternalStopIsFinalized(@TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        // A zero uniform probability resolves to the explicit none policy.
        Sampling.Builder zero = Sampling.newBuilder();
        SamplingConfig.uniform(BigDecimal.ZERO, zero);
        SignalCaptureController controller = controller(root, profiler, source, zero.build());
        controller.start();
        profiler.stopExternally();
        controller.pollProfiler();
        assertThat(controller.state()).as("external stop was not finalized").isEqualTo(State.COMPLETE);
        assertThat(profiler.guardedStops)
                .as("a guarded cookie stop must never be issued in profiler-only mode")
                .isZero();
        assertThat(source.prepareCalls).as("eBPF source must stay untouched").isZero();
        assertThat(source.closeCalls).as("eBPF source must stay untouched").isZero();
        assertThat(CaptureRecordFixture.read(root.resolve("correlation.pb"))
                        .get(0)
                        .getCaptureFinalized()
                        .getState())
                .as("footer missing after external stop")
                .isEqualTo(FinalizedState.FINALIZED_STATE_PROFILER_ONLY);
    }

    @Test
    void coalescingCapture(@TempDir Path root) throws Exception {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        AgentConfig config = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("source.pb")
                        + ",jonoffcpudelivery=coalescing,sampling-policy=uniform,sampling-probability=1,asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("original.jfr"),
                nativeLibrary);
        assertThat(config.signalDelivery())
                .as("Native delivery option not parsed")
                .isEqualTo(SignalDelivery.SIGNAL_DELIVERY_COALESCING);
        SignalCaptureController controller =
                new SignalCaptureController(config, new FakeProfiler(), new FakeSource(), millis -> {});
        controller.start();
        controller.stop();
        assertThat(controller.state()).as("Coalescing capture did not complete").isEqualTo(State.COMPLETE);
        List<Record> records = CaptureRecordFixture.read(root.resolve("source.pb"));
        assertThat(records.get(0).getCaptureStart().getSignalDelivery())
                .as("Delivery policy missing from source")
                .isEqualTo(SignalDelivery.SIGNAL_DELIVERY_COALESCING);
        assertThat(records.get(records.size() - 1).getCaptureFinalized().getApStopResponse())
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
        List<Record> records = CaptureRecordFixture.read(root.resolve("correlation.pb"));
        assertThat(records)
                .as("Native stack failure evidence not retained: stack record")
                .anyMatch(Record::hasStack);
        Observation observation = records.stream()
                .filter(Record::hasObservation)
                .findFirst()
                .orElseThrow()
                .getObservation();
        assertThat(observation.getUserStackError())
                .as("Native stack failure evidence not retained: userStackError")
                .isEqualTo("bpf_stack_error_-7");
        assertThat(records.get(records.size() - 2)
                        .getCaptureEnd()
                        .getUserspaceCounters()
                        .getSymbolizationFailures())
                .as("Native stack failure evidence not retained: symbolizationFailures")
                .isEqualTo(1);
    }

    /** The verifier recomputes each observation's reason and threshold, and rejects a row the kernel would not write. */
    @ParameterizedTest
    @ValueSource(strings = {"reason", "threshold", "unselectedReason", "unannouncedStack", "cookie"})
    void inconsistentObservationIsRejected(String defect, @TempDir Path root) throws Exception {
        FakeSource source = new FakeSource();
        source.nativeStackFailure = true;
        source.observationDefect = defect;
        FakeProfiler profiler = new FakeProfiler();
        SignalCaptureController controller = controller(root, profiler, source);
        controller.start();
        assertThatThrownBy(controller::stop)
                .as("an inconsistent observation must not be finalized: " + defect)
                .isInstanceOfAny(IOException.class, IllegalStateException.class);
        assertThat(controller.state()).isEqualTo(State.INCOMPLETE);
        assertThat(CaptureRecordFixture.read(root.resolve("correlation.pb")))
                .as("an unverified stream receives no footer")
                .noneMatch(Record::hasCaptureFinalized);
        assertThat(manifest(controller.manifestPath()).getComplete()).isFalse();
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
                .withMessageContaining("STOP_TIMEOUT");
        assertThat(profiler.active)
                .as("AP stopped while native ownership was unsettled")
                .isTrue();
        assertThat(source.closeCalls).as("timed-out native handle was freed").isZero();
        Manifest interrupted = manifest(controller.manifestPath());
        assertThat(interrupted.getState()).isEqualTo(ManifestState.MANIFEST_STATE_INCOMPLETE);
        assertThat(interrupted.getFailure().getCode()).isEqualTo(FailureCode.FAILURE_CODE_COLLECTOR_ERROR);
        assertThat(interrupted.getFailure().getCollectorError().getCode())
                .isEqualTo(CollectorErrorCode.COLLECTOR_ERROR_CODE_STOP_TIMEOUT);
        assertThat(interrupted.getNativeStop().getState()).isEqualTo(CollectorState.COLLECTOR_STATE_STOPPING);
        controller.stop();
        assertThat(controller.state()).as("retry did not complete").isEqualTo(State.COMPLETE);
        assertThat(manifest(controller.manifestPath()).getNativeStop())
                .as("the retried stop's reply replaces the timed-out one")
                .isEqualTo(source.stopReply);
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
                .withMessageContaining("CLOSE_TIMEOUT");
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
        assertThat(CaptureRecordFixture.read(root.resolve("correlation.pb")))
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
        assertThat(CaptureRecordFixture.read(root.resolve("correlation.pb")))
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
        Manifest manifest = manifest(controller.manifestPath());
        assertThat(manifest.getComplete()).as("failed start marked complete").isFalse();
        assertThat(manifest.getState()).isEqualTo(ManifestState.MANIFEST_STATE_FAILED);
        assertThat(manifest.getFailure().getCode()).isEqualTo(FailureCode.FAILURE_CODE_INVALID_ARGUMENT);
        assertThat(manifest.getNativeCleanupClose()).isEqualTo(source.closeReply);
    }

    /** The collector echoes the policy it was sent; an echo that differs as a message is a protocol error. */
    @ParameterizedTest
    @ValueSource(strings = {"prepare", "enable"})
    void samplingEchoDriftIsRejected(String call, @TempDir Path root) throws Exception {
        FakeProfiler profiler = new FakeProfiler();
        FakeSource source = new FakeSource();
        source.driftingEcho = call;
        SignalCaptureController controller = controller(root, profiler, source);
        assertThatIllegalStateException()
                .as("a drifting sampling echo must fail the start")
                .isThrownBy(controller::start)
                .withMessageContaining("sampling mismatch");
        assertThat(source.closeCalls)
                .as("the prepared collector was not closed")
                .isEqualTo(1);
        assertThat(profiler.active).as("async-profiler was left running").isFalse();
    }

    @Test
    void unsupportedCollectorAbiIsRejected(@TempDir Path root) throws Exception {
        FakeSource source = new FakeSource();
        source.abiVersion = 1;
        SignalCaptureController controller = controller(root, new FakeProfiler(), source);
        assertThatIllegalStateException()
                .isThrownBy(controller::start)
                .withMessageContaining("collector ABI version 1");
        assertThat(source.enableCalls).isZero();
    }

    @Test
    void preparedCollectorErrorIsRecorded(@TempDir Path root) throws Exception {
        FakeSource source = new FakeSource();
        source.prepareError = true;
        SignalCaptureController controller = controller(root, new FakeProfiler(), source);
        assertThatIllegalStateException()
                .isThrownBy(controller::start)
                .withMessageContaining("prepare failed [COLLECTOR_ERROR_CODE_BPF_UNSUPPORTED]");
        Manifest manifest = manifest(controller.manifestPath());
        assertThat(manifest.getNativePrepare()).isEqualTo(source.prepareReply);
        assertThat(manifest.getFailure().getCollectorError()).isEqualTo(source.prepareReply.getError());
        assertThat(source.closeCalls).as("no handle to close").isZero();
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
                        + root.resolve("correlation.pb")
                        + ",sampling-policy=uniform,sampling-probability=0.1,min-off-cpu-micros=7,asprofpath="
                        + ap
                        + ",event=cpu,alloc=1m,jfrsync=profile,file="
                        + jfr,
                nativeLibrary);
        assertThat(config.sampling())
                .as("requested/effective sampling policy")
                .isEqualTo(Sampling.newBuilder()
                        .addReasons(OffCpuReason.OFF_CPU_REASON_BLOCKED)
                        .setMinOffCpuMicros(7)
                        // The probability's spelling is kept, and its threshold rounded down.
                        .setUniform(UniformAdmission.newBuilder()
                                .setProbability("0.1")
                                .setProbabilityThreshold(429_496_729L))
                        .build());
        assertThat(config.sampling().hasMaxOffCpuMicros())
                .as("an unset bound stays unset")
                .isFalse();
        AgentConfig proportional = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("proportional.pb")
                        + ",sampling-policy=proportional,record-all-above-micros=250,asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("proportional.jfr"),
                nativeLibrary);
        assertThat(proportional.sampling())
                .as("proportional native options not parsed")
                .isEqualTo(proportional(250, OffCpuReason.OFF_CPU_REASON_BLOCKED));
        AgentConfig reasons = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("reasons.pb")
                        + ",sampling-policy=proportional,record-all-above-micros=250,"
                        + "sampling-reasons=runnable+blocked,asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("reasons.jfr"),
                nativeLibrary);
        assertThat(reasons.sampling().getReasonsList())
                .as("sampling-reasons native option not parsed into canonical order")
                .containsExactly(OffCpuReason.OFF_CPU_REASON_BLOCKED, OffCpuReason.OFF_CPU_REASON_RUNNABLE);
        assertThat(config.timeSplit())
                .as("native options must default to schedInfo")
                .isEqualTo(TimeSplitConfig.DEFAULT);
        AgentConfig splitOff = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("split-off.pb")
                        + ",sampling-policy=proportional,record-all-above-micros=250,time-split=off,asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("split-off.jfr"),
                nativeLibrary);
        assertThat(splitOff.timeSplit())
                .as("time-split native option not parsed")
                .isEqualTo(TimeSplit.newBuilder()
                        .setSource(TimeSplitSource.TIME_SPLIT_SOURCE_OFF)
                        .build());
        assertThatIllegalArgumentException()
                .as("Expected unknown time-split source rejection")
                .isThrownBy(() -> AgentConfig.parseNativeOptions(
                        "jonoffcpuoutput="
                                + root.resolve("split-wakeup.pb")
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
                                + root.resolve("pattern.pb")
                                + ",sampling-policy=uniform,sampling-probability=1,asprofpath="
                                + ap
                                + ",event=cpu,file="
                                + root.resolve("profile-%p.jfr"),
                        nativeLibrary))
                .withMessageContaining("patterns");

        Path racedJfr = root.resolve("raced.jfr");
        AgentConfig raced = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput=" + root.resolve("raced.pb")
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
                                + root.resolve("rejected-" + action + ".pb")
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
                        root.resolve("correlation.pb"), ap, nativeLibrary, root.resolve("combined.jfr")));
        AgentConfig config = AgentConfig.parse(configFile.toString());
        assertThat(config.signalDelivery())
                .as("YAML delivery policy not parsed")
                .isEqualTo(SignalDelivery.SIGNAL_DELIVERY_COALESCING);
        assertThat(config.sampling())
                .as("YAML sampling policy not parsed exactly")
                .isEqualTo(Sampling.newBuilder()
                        .addReasons(OffCpuReason.OFF_CPU_REASON_BLOCKED)
                        .setMinOffCpuMicros(10)
                        .setMaxOffCpuMicros(1000)
                        .setUniform(UniformAdmission.newBuilder()
                                .setProbability("0.125")
                                .setProbabilityThreshold(536_870_912L))
                        .build());
        assertThat(config.timeSplit())
                .as("YAML must default to timeSplit schedInfo")
                .isEqualTo(TimeSplitConfig.DEFAULT);
        AgentConfig splitOff =
                AgentConfig.parse(splitBase(root, ap, nativeLibrary) + "timeSplit:\n  source: \"off\"\n");
        assertThat(splitOff.timeSplit().getSource())
                .as("YAML timeSplit not parsed")
                .isEqualTo(TimeSplitSource.TIME_SPLIT_SOURCE_OFF);
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
                "timeSplit: \"off\"\n",
                // The schema's enum names are not the configuration's spelling.
                "timeSplit:\n  source: TIME_SPLIT_SOURCE_OFF\n");
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
                """.formatted(root.resolve("split.pb"), ap, nativeLibrary, root.resolve("split.jfr"));
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
    void validSamplingProbabilityParsing(String probability, long threshold, @TempDir Path root) throws Exception {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        AgentConfig config = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("valid.pb")
                        + ",sampling-policy=uniform,sampling-probability="
                        + probability
                        + ",asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("valid.jfr"),
                nativeLibrary);
        assertThat(config.sampling().getUniform())
                .as("requested probability spelling and effective threshold for " + probability)
                .isEqualTo(UniformAdmission.newBuilder()
                        .setProbability(probability)
                        .setProbabilityThreshold(threshold)
                        .build());
    }

    // Exactly zero is the explicit off switch; a positive value that rounds to no draws is a mistake.
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000"})
    void zeroSamplingProbabilityIsNone(String zero, @TempDir Path root) throws Exception {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        AgentConfig config = AgentConfig.parseNativeOptions(
                "jonoffcpuoutput="
                        + root.resolve("zero.pb")
                        + ",sampling-policy=uniform,sampling-probability="
                        + zero
                        + ",asprofpath="
                        + ap
                        + ",event=cpu,file="
                        + root.resolve("zero.jfr"),
                nativeLibrary);
        assertThat(config.profilerOnly()).as("zero != none: " + zero).isTrue();
        assertThat(config.sampling()).as("zero != none: " + zero).isEqualTo(NONE);
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
                                + root.resolve("invalid.pb")
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
                        root.resolve("sampling.pb"), ap, nativeLibrary, root.resolve("sampling.jfr"))
                + sampling;
    }

    @ParameterizedTest
    @MethodSource
    void validSamplingConfigParsing(String sampling, Sampling.AdmissionCase policy, @TempDir Path root)
            throws Exception {
        String yaml = samplingYaml(root, sampling);
        AgentConfig config = AgentConfig.parse(yaml);
        assertThat(config.sampling().getAdmissionCase())
                .as("policy not parsed: " + yaml)
                .isEqualTo(policy);
        assertThat(config.profilerOnly())
                .as("profiler-only mismatch: " + yaml)
                .isEqualTo(policy == Sampling.AdmissionCase.NONE);
    }

    static Stream<Arguments> validSamplingConfigParsing() {
        return Stream.of(
                        Arguments.of(
                                "sampling:\n  admission:\n    policy: proportional\n    recordAllAboveMicros: 10000\n",
                                Sampling.AdmissionCase.PROPORTIONAL),
                        Arguments.of(
                                "sampling:\n  minOffCpuMicros: 100\n  admission:\n    policy: proportional\n"
                                        + "    recordAllAboveMicros: 1\n",
                                Sampling.AdmissionCase.PROPORTIONAL),
                        Arguments.of("sampling:\n  admission:\n    policy: none\n", Sampling.AdmissionCase.NONE),
                        Arguments.of(
                                "sampling:\n  admission:\n    policy: uniform\n    probability: 1\n",
                                Sampling.AdmissionCase.UNIFORM),
                        Arguments.of(
                                "sampling:\n  reasons: [preempted, blocked]\n  admission:\n    policy: uniform\n"
                                        + "    probability: 1\n",
                                Sampling.AdmissionCase.UNIFORM))
                .map(c -> Arguments.of(Named.of(displayYaml((String) c.get()[0]), c.get()[0]), c.get()[1]));
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
                "sampling:\n  admission:\n    policy: proportional\n    recordAllAboveMicros: \"5\"\n",
                "sampling:\n  admission:\n    policy: proportional\n    recordAllAboveMicros: 5.0\n",
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
                "sampling:\n  reasons: [OFF_CPU_REASON_BLOCKED]\n  admission:\n    policy: uniform\n"
                        + "    probability: 1\n",
                "sampling:\n  reasons: blocked\n  admission:\n    policy: uniform\n    probability: 1\n",
                "sampling:\n  reasons: [blocked]\n  admission:\n    policy: none\n");
    }

    /** The configuration's friendly spellings resolve to the schema's message, whose JSON uses the enum names. */
    @Test
    void samplingConfigResolvesToTheMessage(@TempDir Path root) throws Exception {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        String prefix = SAMPLING_YAML_PREFIX;
        AgentConfig proportional = AgentConfig.parse(
                prefix.formatted(root.resolve("p.pb"), ap, nativeLibrary, root.resolve("p.jfr"))
                        + "sampling:\n  minOffCpuMicros: 100\n  admission:\n    policy: proportional\n    recordAllAboveMicros: 10000\n");
        assertThat(ProtoJson.line(proportional.sampling()))
                .as("unexpected sampling JSON")
                .isEqualTo("{\"reasons\":[\"OFF_CPU_REASON_BLOCKED\"],\"minOffCpuMicros\":\"100\","
                        + "\"proportional\":{\"recordAllAboveMicros\":\"10000\"}}");
        // Reasons are kept in canonical order whatever the order they were given in.
        AgentConfig everything =
                AgentConfig.parse(prefix.formatted(root.resolve("r.pb"), ap, nativeLibrary, root.resolve("r.jfr"))
                        + "sampling:\n  reasons: [preempted, blocked, runnable]\n  admission:\n"
                        + "    policy: uniform\n    probability: 1\n");
        assertThat(everything.sampling().getReasonsList())
                .as("reasons not canonical")
                .containsExactly(
                        OffCpuReason.OFF_CPU_REASON_BLOCKED,
                        OffCpuReason.OFF_CPU_REASON_RUNNABLE,
                        OffCpuReason.OFF_CPU_REASON_PREEMPTED);
        assertThat(AgentConfig.parse(prefix.formatted(root.resolve("n.pb"), ap, nativeLibrary, root.resolve("n.jfr"))
                                + "sampling:\n  admission:\n    policy: none\n")
                        .sampling())
                .as("policy none selects no reasons")
                .isEqualTo(NONE);
    }

    @ParameterizedTest
    @CsvSource({
        "/data/jonoffcpu-capture.pb,     .manifest.json, /data/jonoffcpu-capture.manifest.json",
        "/data/capture,                  .manifest.json, /data/capture.manifest.json",
        "/data/.hidden,                  .manifest.json, /data/.hidden.manifest.json",
        "/data/run.v1/capture,           .manifest.json, /data/run.v1/capture.manifest.json",
        "/data/capture.tar.gz,           .manifest.json, /data/capture.tar.manifest.json",
        // The default JFR sibling.
        "/data/capture.pb,               .jfr,           /data/capture.jfr",
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
        assertThat(SamplingConfig.admissionThreshold(
                        proportional(10_000, OffCpuReason.OFF_CPU_REASON_BLOCKED), 1_000_000))
                .as("policy delegation")
                .isEqualTo(certain / 10);
        Sampling uniform = Sampling.newBuilder()
                .setUniform(UniformAdmission.newBuilder().setProbability("1").setProbabilityThreshold(certain))
                .build();
        assertThat(SamplingConfig.admissionThreshold(uniform, 1))
                .as("uniform ignores duration")
                .isEqualTo(certain);
    }

    @ParameterizedTest
    @CsvSource({
        "false, 1, OFF_CPU_REASON_BLOCKED",
        "false, 0, OFF_CPU_REASON_RUNNABLE",
        "true, 1, OFF_CPU_REASON_PREEMPTED"
    })
    void switchOutClassification(boolean preempted, int prevTaskState, OffCpuReason reason) {
        assertThat(SamplingConfig.classify(preempted, prevTaskState)).isEqualTo(reason);
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
        assertThat(Long.toUnsignedString(stopped.counters().getSubmittedSamples()))
                .as("u64 AP counter rejected")
                .isEqualTo(max);
        assertThat(stopped.delivery()).isEqualTo(SignalDelivery.SIGNAL_DELIVERY_COALESCING);
    }

    private static final Sampling NONE =
            Sampling.newBuilder().setNone(NoAdmission.getDefaultInstance()).build();
    private static final Sampling SPARSE = sparse();

    private static Sampling sparse() {
        Sampling.Builder sampling = Sampling.newBuilder().addAllReasons(SamplingConfig.DEFAULT_REASONS);
        SamplingConfig.uniform(new BigDecimal("0.0000000233"), sampling);
        return sampling.build();
    }

    private static Sampling proportional(long recordAllAboveMicros, OffCpuReason... reasons) {
        return Sampling.newBuilder()
                .addAllReasons(List.of(reasons))
                .setProportional(ProportionalAdmission.newBuilder().setRecordAllAboveMicros(recordAllAboveMicros))
                .build();
    }

    private static Manifest manifest(Path path) throws IOException {
        return ProtoJson.parse(Files.readString(path), Manifest.newBuilder()).build();
    }

    private static SignalCaptureController controller(Path root, FakeProfiler profiler, FakeSource source)
            throws IOException {
        return controller(root, profiler, source, SPARSE);
    }

    private static SignalCaptureController controller(
            Path root, FakeProfiler profiler, FakeSource source, Sampling sampling) throws IOException {
        Path ap = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path nativeLibrary = Files.createFile(root.resolve("libjonoffcpu.so"));
        AgentConfig config = new AgentConfig(
                root.resolve("correlation.pb"),
                ap,
                nativeLibrary,
                null,
                ProcessHandle.current().pid(),
                "event=cpu",
                SignalDelivery.SIGNAL_DELIVERY_QUEUED,
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

    /**
     * The native collector's control protocol, in encoded messages as the JNI bridge passes them, writing the capture
     * stream the real collector would. It keeps the last reply of each call for the tests to compare with the manifest.
     */
    private static final class FakeSource implements SignalCaptureController.SourceClient {
        private static final long HANDLE = 0x8000000000000001L;
        private static final VerifiedIdentity IDENTITY = VerifiedIdentity.newBuilder()
                .setRegistrationToken(0x0123456789abcdefL)
                .setProcessGenerationNanos(9)
                .setPidNamespaceDevice(4)
                .setPidNamespaceInode(43)
                .setTimeNamespaceInode(42)
                .setClockVerified(true)
                .setMonotonicOffsetNanos(0)
                .build();
        private int abiVersion = SignalCaptureController.COLLECTOR_ABI_VERSION;
        private int prepareCalls;
        private int enableCalls;
        private int stopCalls;
        private int closeCalls;
        private boolean prepareError;
        private boolean timeoutOnce;
        private boolean closeTimeoutOnce;
        private boolean incompleteStop;
        private boolean stopError;
        private boolean nativeStackFailure;
        private String observationDefect;
        private String driftingEcho;
        private Path source;
        private Sampling sampling;
        private TimeSplit timeSplit;
        private int targetPid;
        private int signal;
        private String session;
        private CaptureEnd captureEnd;
        private CollectorReply prepareReply;
        private CollectorReply enableReply;
        private CollectorReply stopReply;
        private CollectorReply closeReply;

        @Override
        public byte[] prepare(byte[] prepareRequest) {
            prepareCalls++;
            PrepareRequest request = parse(prepareRequest, PrepareRequest.parser());
            if (prepareError) {
                prepareReply = error(
                        CollectorState.COLLECTOR_STATE_ERROR,
                        CollectorErrorCode.COLLECTOR_ERROR_CODE_BPF_UNSUPPORTED,
                        "no BTF");
                return prepareReply.toByteArray();
            }
            source = Path.of(request.getOutputPath());
            sampling = request.getSampling();
            timeSplit = request.getTimeSplit();
            targetPid = request.getTargetPid();
            prepareReply = reply(CollectorState.COLLECTOR_STATE_PREPARED)
                    .setPrepared(Prepared.newBuilder()
                            .setHandle(HANDLE)
                            .setSourcePath(source.toString())
                            .setTargetPid(targetPid)
                            .setHostTgid(targetPid)
                            .setSampling(echo("prepare"))
                            .setTimeSplit(timeSplit)
                            .setVerifiedIdentity(IDENTITY))
                    .build();
            return prepareReply.toByteArray();
        }

        @Override
        public byte[] enable(long handle, byte[] enableRequest) {
            enableCalls++;
            EnableRequest request = parse(enableRequest, EnableRequest.parser());
            session = request.getSessionId();
            signal = request.getSignal();
            SignalDelivery delivery =
                    signal >= 34 ? SignalDelivery.SIGNAL_DELIVERY_QUEUED : SignalDelivery.SIGNAL_DELIVERY_COALESCING;
            enableReply = reply(CollectorState.COLLECTOR_STATE_ENABLED)
                    .setEnabled(Enabled.newBuilder()
                            .setSessionId(session)
                            .setCaptureEpoch(7)
                            .setSignal(signal)
                            .setSignalDelivery(delivery)
                            .setSourcePath(source.toString())
                            .setTargetPid(targetPid)
                            .setHostTgid(targetPid)
                            .setSampling(echo("enable"))
                            .setTimeSplit(timeSplit)
                            .setVerifiedIdentity(IDENTITY))
                    .build();
            CaptureStart start = CaptureStart.newBuilder()
                    .setSourceId(ArtifactVerifier.SOURCE_ID)
                    .setSessionId(session)
                    .setCaptureEpoch(7)
                    .setSignal(signal)
                    .setSignalDelivery(delivery)
                    .setHostTgid(targetPid)
                    .setTargetPid(targetPid)
                    .setVerifiedIdentity(IDENTITY)
                    .setStartedMonotonicNanos(9)
                    .setSampling(sampling)
                    .setTimeSplit(timeSplit)
                    .build();
            try {
                Files.write(
                        source,
                        CaptureRecordFixture.encode(List.of(
                                Record.newBuilder().setCaptureStart(start).build())));
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            return enableReply.toByteArray();
        }

        @Override
        public byte[] stop(long handle, long timeoutMillis) {
            stopCalls++;
            if (timeoutOnce) {
                timeoutOnce = false;
                stopReply = error(
                        CollectorState.COLLECTOR_STATE_STOPPING,
                        CollectorErrorCode.COLLECTOR_ERROR_CODE_STOP_TIMEOUT,
                        "test timeout");
                return stopReply.toByteArray();
            }
            if (stopError) {
                stopReply = error(
                        CollectorState.COLLECTOR_STATE_ERROR,
                        CollectorErrorCode.COLLECTOR_ERROR_CODE_IO_ERROR,
                        "test fsync failure");
                return stopReply.toByteArray();
            }
            CaptureEnd.Builder end = captureEnd(session, !incompleteStop).toBuilder();
            try {
                if (nativeStackFailure) {
                    // One announced stack for the kernel side; the user side failed, so it has no record.
                    append(Record.newBuilder()
                            .setStack(Stack.newBuilder().setId(5))
                            .build());
                    append(Record.newBuilder().setObservation(observation()).build());
                    end.setUserspaceCounters(end.getUserspaceCounters().toBuilder()
                            .setReceivedObservations(1)
                            .setWrittenObservations(1)
                            .setSymbolizationFailures(1));
                }
                captureEnd = end.build();
                append(Record.newBuilder().setCaptureEnd(captureEnd).build());
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            stopReply = reply(
                            incompleteStop
                                    ? CollectorState.COLLECTOR_STATE_INCOMPLETE
                                    : CollectorState.COLLECTOR_STATE_COMPLETE)
                    .setStopped(stopped())
                    .build();
            return stopReply.toByteArray();
        }

        @Override
        public byte[] close(long handle) {
            closeCalls++;
            if (closeTimeoutOnce) {
                closeTimeoutOnce = false;
                closeReply = error(
                        CollectorState.COLLECTOR_STATE_CLOSING,
                        CollectorErrorCode.COLLECTOR_ERROR_CODE_CLOSE_TIMEOUT,
                        "test close timeout");
                return closeReply.toByteArray();
            }
            if (captureEnd == null && session != null) {
                captureEnd = captureEnd(session, false);
                try {
                    append(Record.newBuilder().setCaptureEnd(captureEnd).build());
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            }
            Closed.Builder closed = Closed.newBuilder().setHandle(handle);
            if (captureEnd != null) closed.setStopped(stopped());
            closeReply = reply(CollectorState.COLLECTOR_STATE_CLOSED)
                    .setClosed(closed)
                    .build();
            return closeReply.toByteArray();
        }

        private Sampling echo(String call) {
            if (!call.equals(driftingEcho)) return sampling;
            return sampling.toBuilder().setMinOffCpuMicros(1).build();
        }

        private Stopped stopped() {
            return Stopped.newBuilder()
                    .setSourcePath(source.toString())
                    .setCaptureEnd(captureEnd)
                    .build();
        }

        private Observation observation() {
            Observation.Builder observation = Observation.newBuilder()
                    .setCorrelationId(0x0000000700000001L)
                    .setHostTgid(targetPid)
                    .setHostTid(targetPid)
                    .setTargetTgid(targetPid)
                    .setTargetTid(targetPid)
                    .setAdmissionThreshold(sampling.getUniform().getProbabilityThreshold())
                    .setProcessGenerationNanos(9)
                    .setThreadGenerationNanos(9)
                    .setRegistrationToken(IDENTITY.getRegistrationToken())
                    .setStartMonotonicNanos(9)
                    .setEndMonotonicNanos(10)
                    .setSignalResult(0)
                    .setReason(OffCpuReason.OFF_CPU_REASON_BLOCKED)
                    .setPrevTaskState(1)
                    .setPreempted(false)
                    .setRunqueueNanos(1)
                    .setComm("fixture")
                    .setKernelStackId(5)
                    .setUserStackId(-7)
                    .setUserStackError("bpf_stack_error_-7");
            if (observationDefect != null) {
                switch (observationDefect) {
                    // The reason disagrees with the raw sched_switch arguments it was derived from.
                    case "reason" -> observation.setReason(OffCpuReason.OFF_CPU_REASON_RUNNABLE);
                    case "threshold" -> observation.setAdmissionThreshold(observation.getAdmissionThreshold() + 1);
                    // A consistent reason that sampling.reasons did not select.
                    case "unselectedReason" ->
                        observation.setPrevTaskState(0).setReason(OffCpuReason.OFF_CPU_REASON_RUNNABLE);
                    case "unannouncedStack" -> observation.setKernelStackId(6);
                    case "cookie" -> observation.setCorrelationId(0x0000000800000001L);
                    default -> throw new IllegalArgumentException(observationDefect);
                }
            }
            return observation.build();
        }

        private void append(Record record) throws IOException {
            CaptureRecordFixture.append(source, record);
        }

        private CollectorReply.Builder reply(CollectorState state) {
            return CollectorReply.newBuilder().setAbiVersion(abiVersion).setState(state);
        }

        private CollectorReply error(CollectorState state, CollectorErrorCode code, String message) {
            return reply(state)
                    .setError(CollectorError.newBuilder().setCode(code).setMessage(message))
                    .build();
        }

        private static CaptureEnd captureEnd(String session, boolean complete) {
            CaptureEnd.Builder end = CaptureEnd.newBuilder()
                    .setSessionId(session)
                    .setCaptureEpoch(7)
                    .setState(complete ? CaptureState.CAPTURE_STATE_COMPLETE : CaptureState.CAPTURE_STATE_INCOMPLETE)
                    .setStartedMonotonicNanos(9)
                    .setStoppedMonotonicNanos(10)
                    .setDetachedMonotonicNanos(11)
                    .setDrainCompletedMonotonicNanos(12)
                    .setKernelCounters(KernelCounters.getDefaultInstance())
                    .setUserspaceCounters(UserspaceCounters.getDefaultInstance());
            if (!complete) end.setIncompleteReason(IncompleteReason.INCOMPLETE_REASON_SOURCE_WRITE_FAILURE);
            return end.build();
        }

        private static <T> T parse(byte[] bytes, com.google.protobuf.Parser<T> parser) {
            try {
                return parser.parseFrom(bytes);
            } catch (InvalidProtocolBufferException error) {
                throw new IllegalArgumentException(error);
            }
        }
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
