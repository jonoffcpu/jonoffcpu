// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.agent;

import com.google.protobuf.InvalidProtocolBufferException;
import io.github.jonoffcpu.agent.ManifestProto.ActualIdentity;
import io.github.jonoffcpu.agent.ManifestProto.AsyncProfilerStop;
import io.github.jonoffcpu.agent.ManifestProto.ManifestState;
import io.github.jonoffcpu.agent.ManifestProto.NegotiatedCapture;
import io.github.jonoffcpu.agent.ManifestProto.ProcessStartTime;
import io.github.jonoffcpu.capture.CaptureProto.AnalysisInputs;
import io.github.jonoffcpu.capture.CaptureProto.AsyncProfilerStats;
import io.github.jonoffcpu.capture.CaptureProto.CaptureEnd;
import io.github.jonoffcpu.capture.CaptureProto.CaptureFinalized;
import io.github.jonoffcpu.capture.CaptureProto.CaptureState;
import io.github.jonoffcpu.capture.CaptureProto.FileArtifact;
import io.github.jonoffcpu.capture.CaptureProto.FinalizedState;
import io.github.jonoffcpu.capture.CaptureProto.Sampling;
import io.github.jonoffcpu.capture.CaptureProto.SourceArtifact;
import io.github.jonoffcpu.capture.CaptureProto.TimeSplit;
import io.github.jonoffcpu.capture.CaptureProto.VerifiedIdentity;
import io.github.jonoffcpu.capture.CollectorProto.Closed;
import io.github.jonoffcpu.capture.CollectorProto.CollectorReply;
import io.github.jonoffcpu.capture.CollectorProto.CollectorState;
import io.github.jonoffcpu.capture.CollectorProto.EnableRequest;
import io.github.jonoffcpu.capture.CollectorProto.Enabled;
import io.github.jonoffcpu.capture.CollectorProto.PrepareRequest;
import io.github.jonoffcpu.capture.CollectorProto.Prepared;
import io.github.jonoffcpu.capture.CollectorProto.Stopped;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.UUID;

final class SignalCaptureController {
    /** The collector's C ABI version this agent speaks, which every reply carries. */
    static final int COLLECTOR_ABI_VERSION = 2;
    /** The largest reply the JNI bridge passes on. */
    static final int MAX_REPLY_BYTES = 64 * 1024;

    interface ProfilerClient {
        String version();

        String execute(String command) throws Exception;
    }

    /** The collector's calls: encoded requests in, encoded {@link CollectorReply} messages out. */
    interface SourceClient {
        byte[] prepare(byte[] prepareRequest);

        byte[] enable(long handle, byte[] enableRequest);

        byte[] stop(long handle, long timeoutMillis);

        byte[] close(long handle);
    }

    interface Delay {
        void sleep(long millis) throws InterruptedException;
    }

    enum State {
        NEW,
        PREPARED,
        AP_STARTED_AND_VERIFIED,
        SOURCE_ENABLED,
        PROFILER_ONLY,
        STOPPING,
        SOURCE_STOPPED,
        AP_FINALIZED,
        COMPLETE,
        INCOMPLETE
    }

    private final AgentConfig config;
    private final ProfilerClient profiler;
    private final SourceClient source;
    private final Delay delay;
    private State state = State.NEW;
    private ManifestStore manifest;
    private String sessionId;
    private long handle;
    private CaptureProtocol.Active capture;
    private Prepared prepared;
    private Stopped sourceStop;
    private Closed sourceClose;
    private boolean sourceEnabled;
    private boolean nativeStopAttempted;
    private boolean retryableNativeStop;
    private boolean nativeCaptureComplete;
    private boolean sourceClosed;
    private boolean deliveryGraceComplete;
    private String apStopReceipt;
    private CaptureProtocol.Stopped apStopped;
    private boolean apCleanupTerminal;
    private Exception nativeStopFailure;
    private boolean startFailed;
    private boolean failedStartApCleanupDone;

    SignalCaptureController(AgentConfig config, ProfilerClient profiler, SourceClient source) {
        this(config, profiler, source, Thread::sleep);
    }

    SignalCaptureController(AgentConfig config, ProfilerClient profiler, SourceClient source, Delay delay) {
        this.config = config;
        this.profiler = profiler;
        this.source = source;
        this.delay = delay;
    }

    static ProfilerClient liveProfiler(Path requestedLibrary) {
        NativeProfiler.initialize(requestedLibrary);
        return new ProfilerClient() {
            @Override
            public String version() {
                return NativeProfiler.execute("version");
            }

            @Override
            public String execute(String command) {
                return NativeProfiler.execute(command);
            }
        };
    }

    static SourceClient liveSource() {
        return new SourceClient() {
            @Override
            public byte[] prepare(byte[] prepareRequest) {
                return NativeCollector.prepare(prepareRequest);
            }

            @Override
            public byte[] enable(long handle, byte[] enableRequest) {
                return NativeCollector.enable(handle, enableRequest);
            }

            @Override
            public byte[] stop(long handle, long timeoutMillis) {
                return NativeCollector.stop(handle, timeoutMillis);
            }

            @Override
            public byte[] close(long handle) {
                return NativeCollector.close(handle);
            }
        };
    }

    synchronized Path start() throws Exception {
        if (state != State.NEW) throw new IllegalStateException("Capture controller already started: " + state);
        String actualVersion = profiler.version();
        if (actualVersion == null || actualVersion.isBlank()) {
            throw new IllegalStateException("The bound async-profiler did not report a version");
        }
        String preflight = profiler.execute("status,signalcookie");
        if (!CaptureProtocol.isInactive(preflight)) {
            throw new IllegalStateException(
                    "Async-profiler is already active or lacks the cookie protocol: " + preflight);
        }

        sessionId = UUID.randomUUID().toString();
        manifest = ManifestStore.create(config, sessionId);
        manifest.root()
                .getAsyncProfilerBuilder()
                .setActualIdentity(ActualIdentity.newBuilder()
                        .setVersion(actualVersion)
                        .setCookieProtocol("signal-capture-v1")
                        .setSingletonCapabilityVerified(true));
        manifest.checkpoint();

        if (config.profilerOnly()) return startProfilerOnly();

        boolean apStartAttempted = false;
        try {
            CollectorReply prepareReply = reply(source.prepare(prepareRequest().toByteArray()), "prepare");
            manifest.root().setNativePrepare(prepareReply);
            prepared = requireSuccess(prepareReply, "prepare", CollectorReply.ResultCase.PREPARED)
                    .getPrepared();
            if (prepareReply.getState() != CollectorState.COLLECTOR_STATE_PREPARED) {
                throw new IllegalStateException("prepare returned unexpected state: " + prepareReply.getState());
            }
            if (prepared.getHandle() == 0) throw new IllegalArgumentException("Invalid native collector handle");
            handle = prepared.getHandle();
            validatePrepared(prepared);
            state = State.PREPARED;
            manifest.state(ManifestState.MANIFEST_STATE_PREPARED);

            apStartAttempted = true;
            String fileOption = config.jfrOutput() == null ? ",file=" + manifest.jfrPath() : "";
            String started = profiler.execute("start,"
                    + config.asyncProfilerOptions()
                    + fileOption
                    + ",signalcookie="
                    + CaptureProtocol.deliveryName(config.signalDelivery())
                    + ",signalid="
                    + sessionId);
            CaptureProtocol.Active startCapture = CaptureProtocol.parseActive(started);
            requireCapture(startCapture);
            CaptureProtocol.Active statusCapture = CaptureProtocol.parseActive(profiler.execute("status,signalcookie"));
            if (!startCapture.equals(statusCapture)) {
                throw new IllegalStateException("AP start/status capture identity mismatch");
            }
            capture = startCapture;
            manifest.root()
                    .getAsyncProfilerBuilder()
                    .setCapture(NegotiatedCapture.newBuilder()
                            .setSessionId(sessionId)
                            .setCaptureEpoch((int) capture.epoch())
                            .setSignal(capture.signal())
                            .setSignalDelivery(capture.delivery()));
            state = State.AP_STARTED_AND_VERIFIED;
            manifest.state(ManifestState.MANIFEST_STATE_AP_STARTED_AND_VERIFIED);

            CollectorReply enableReply =
                    reply(source.enable(handle, enableRequest().toByteArray()), "enable");
            manifest.root().setNativeEnable(enableReply);
            Enabled enabled = requireSuccess(enableReply, "enable", CollectorReply.ResultCase.ENABLED)
                    .getEnabled();
            sourceEnabled = true;
            if (enableReply.getState() != CollectorState.COLLECTOR_STATE_ENABLED) {
                throw new IllegalStateException("enable returned unexpected state: " + enableReply.getState());
            }
            validateEnabled(enabled);
            state = State.SOURCE_ENABLED;
            manifest.state(ManifestState.MANIFEST_STATE_SOURCE_ENABLED);
            return manifest.directory();
        } catch (Exception failure) {
            startFailed = true;
            cleanupFailedStart(apStartAttempted);
            state = State.INCOMPLETE;
            try {
                manifest.failure(ManifestState.MANIFEST_STATE_FAILED, failure);
            } catch (IOException manifestFailure) {
                failure.addSuppressed(manifestFailure);
            }
            throw failure;
        }
    }

    synchronized Path stop() throws Exception {
        if (state == State.COMPLETE) return manifest.manifestPath();
        if (state == State.NEW) throw new IllegalStateException("Capture controller was not started");
        if (startFailed) {
            cleanupFailedStart(true);
            throw new IllegalStateException(
                    sourceClosed && failedStartApCleanupDone
                            ? "Capture startup failed after owned resources were safely cleaned up"
                            : "Capture startup cleanup remains incomplete");
        }
        if (config.profilerOnly()) return stopProfilerOnly();
        if (state == State.INCOMPLETE && !canMakeCleanupProgress()) {
            throw new IllegalStateException("Capture is terminally incomplete and has no remaining safe cleanup step");
        }
        try {
            if (sourceEnabled && (!nativeStopAttempted || retryableNativeStop)) {
                state = State.STOPPING;
                auditState(ManifestState.MANIFEST_STATE_STOPPING_SOURCE);
                nativeStopAttempted = true;
                try {
                    CollectorReply stopReply = reply(source.stop(handle, config.nativeStopTimeoutMillis()), "stop");
                    manifest.root().setNativeStop(stopReply);
                    auditCheckpoint();
                    if (stopReply.hasError()) {
                        retryableNativeStop = stopReply.getState() == CollectorState.COLLECTOR_STATE_STOPPING;
                        nativeStopFailure = new CollectorException("stop", stopReply.getError());
                        if (retryableNativeStop) {
                            state = State.INCOMPLETE;
                            throw nativeStopFailure;
                        }
                    } else {
                        Stopped stopped = requireSuccess(stopReply, "stop", CollectorReply.ResultCase.STOPPED)
                                .getStopped();
                        CollectorState stopState = stopReply.getState();
                        if (stopState != CollectorState.COLLECTOR_STATE_COMPLETE
                                && stopState != CollectorState.COLLECTOR_STATE_INCOMPLETE) {
                            throw new IllegalStateException("Native stop returned unexpected state: " + stopState);
                        }
                        validateStopped(stopped);
                        sourceStop = stopped;
                        nativeCaptureComplete = stopState == CollectorState.COLLECTOR_STATE_COMPLETE;
                        retryableNativeStop = false;
                        nativeStopFailure = null;
                    }
                } catch (Exception failure) {
                    if (retryableNativeStop) throw failure;
                    nativeStopFailure = failure;
                }
            }

            if (!sourceClosed) {
                CollectorReply closeReply = reply(source.close(handle), "close");
                manifest.root().setNativeClose(closeReply);
                auditCheckpoint();
                if (closeReply.hasError()) {
                    state = State.INCOMPLETE;
                    throw new CollectorException("close", closeReply.getError());
                }
                Closed closed = requireSuccess(closeReply, "close", CollectorReply.ResultCase.CLOSED)
                        .getClosed();
                ArtifactVerifier.requireEqual(
                        "native close state", CollectorState.COLLECTOR_STATE_CLOSED, closeReply.getState());
                ArtifactVerifier.requireEqual("native close handle", handle, closed.getHandle());
                sourceClose = closed;
                sourceClosed = true;
                if (sourceStop == null && closed.hasStopped()) {
                    validateStopped(closed.getStopped());
                    sourceStop = closed.getStopped();
                    nativeCaptureComplete =
                            sourceStop.getCaptureEnd().getState() == CaptureState.CAPTURE_STATE_COMPLETE;
                }
                state = State.SOURCE_STOPPED;
                auditState(
                        nativeCaptureComplete
                                ? ManifestState.MANIFEST_STATE_SOURCE_STOPPED
                                : ManifestState.MANIFEST_STATE_SOURCE_INCOMPLETE);
            }

            if (apStopped == null) {
                if (!deliveryGraceComplete) {
                    delay.sleep(config.deliveryGraceMillis());
                    deliveryGraceComplete = true;
                }
                String response;
                try {
                    response = profiler.execute(
                            "stop,signalcookie,signalid=" + sessionId + ",signalepoch=" + capture.epoch());
                } catch (Exception lostResponse) {
                    try {
                        response = profiler.execute(
                                "status,signalcookie,signalid=" + sessionId + ",signalepoch=" + capture.epoch());
                    } catch (Exception queryFailure) {
                        lostResponse.addSuppressed(queryFailure);
                        throw lostResponse;
                    }
                }
                if (CaptureProtocol.isInactive(response) || response.startsWith("signal-capture-v1 mismatch")) {
                    apCleanupTerminal = true;
                    throw new IllegalStateException("Lost async-profiler capture ownership: " + response.strip());
                }
                CaptureProtocol.Stopped stopped = CaptureProtocol.parseStopped(response);
                requireStopped(stopped);
                apStopReceipt = response;
                apStopped = stopped;
                manifest.root()
                        .setAsyncProfilerStop(AsyncProfilerStop.newBuilder()
                                .setResponse(response.strip())
                                .setFinalized(stopped.finalized())
                                .setStoppedAtMonotonicNanos(stopped.stoppedAtMonotonicNanos())
                                .setReason(stopped.reason())
                                .setCounters(stopped.counters()));
                auditCheckpoint();
                if (!stopped.finalized()) {
                    throw new IllegalStateException(
                            "Async-profiler did not finalize the recording: " + stopped.reason());
                }
                validateCounterInvariants(stopped.counters());
                state = State.AP_FINALIZED;
                auditState(ManifestState.MANIFEST_STATE_AP_FINALIZED);
            }

            if (nativeStopFailure != null) {
                throw new IllegalStateException(
                        "Native source stop failed after safe close and AP finalization", nativeStopFailure);
            }
            if (!nativeCaptureComplete || sourceStop == null) {
                throw new IllegalStateException("Native source finalization was incomplete");
            }
            finalizeCapture();
            return manifest.manifestPath();
        } catch (Exception failure) {
            state = State.INCOMPLETE;
            try {
                manifest.failure(ManifestState.MANIFEST_STATE_INCOMPLETE, failure);
            } catch (IOException manifestFailure) {
                failure.addSuppressed(manifestFailure);
            }
            throw failure;
        }
    }

    /** Verifies both artifacts, appends the footer, and publishes completion last. */
    private void finalizeCapture() throws IOException {
        CaptureEnd end = ArtifactVerifier.verifySource(
                manifest.sourcePath(),
                sourceStop.getCaptureEnd(),
                new ArtifactVerifier.Expected(
                        sessionId,
                        capture.epoch(),
                        capture.signal(),
                        capture.delivery(),
                        config.sampling(),
                        config.timeSplit(),
                        prepared.getHostTgid(),
                        (int) config.targetPid(),
                        prepared.getVerifiedIdentity()));
        if (sourceClose.hasStopped()
                && !sourceClose.getStopped().getCaptureEnd().equals(end)) {
            throw new IOException("Native close captureEnd differs from finalized source");
        }
        manifest.verifyJfrIdentity();
        ArtifactVerifier.JfrResult jfr = ArtifactVerifier.verifyJfr(
                manifest.jfrPath(),
                sessionId,
                capture.epoch(),
                capture.signal(),
                capture.delivery(),
                apStopped.counters());
        if (jfr.processId() != config.targetPid()) {
            throw new IOException("AP JFR process identity does not match targetPid");
        }
        SourceArtifact sourceArtifact = ArtifactVerifier.rawArtifact(manifest.directory(), manifest.sourcePath());
        FileArtifact jfrArtifact = ArtifactVerifier.artifact(manifest.directory(), manifest.jfrPath());
        manifest.root()
                .setSourceCaptureEnd(end)
                .setProcessStartTime(ProcessStartTime.newBuilder()
                        .setValueMillis(jfr.processStartTimeMillis())
                        .setSource("async-profiler OS::processStartTime informational timestamp")
                        .setKernelGenerationProof(false));
        AnalysisInputs analysisInputs = AnalysisInputs.newBuilder()
                .setSessionId(sessionId)
                .setCaptureEpoch((int) capture.epoch())
                .setSignal(capture.signal())
                .setSignalDelivery(capture.delivery())
                .setHostTgid(prepared.getHostTgid())
                .setTargetPid((int) config.targetPid())
                .setSampling(config.sampling())
                .setTimeSplit(config.timeSplit())
                .setVerifiedIdentity(prepared.getVerifiedIdentity())
                .setApStats(apStopped.counters())
                .setSourceArtifact(sourceArtifact)
                .setJfrArtifact(jfrArtifact)
                .build();
        CaptureFinalized footer = CaptureFinalized.newBuilder()
                .setSessionId(sessionId)
                .setCaptureEpoch((int) capture.epoch())
                .setState(FinalizedState.FINALIZED_STATE_COMPLETE)
                .setAnalysisInputs(analysisInputs)
                .setApStopResponse(apStopReceipt)
                .setFinalizedAt(ManifestStore.now())
                .build();
        ArtifactVerifier.appendFinalized(manifest.sourcePath(), sourceArtifact, footer);
        manifest.root().setCorrelationArtifact(ArtifactVerifier.artifact(manifest.directory(), manifest.sourcePath()));
        state = State.COMPLETE;
        try {
            manifest.complete(analysisInputs);
        } catch (IOException auditFailure) {
            System.err.println("JONOFFCPU signal capture footer is complete but audit manifest publication failed: "
                    + auditFailure.getMessage());
        }
    }

    synchronized State state() {
        return state;
    }

    synchronized Path manifestPath() {
        return manifest == null ? null : manifest.manifestPath();
    }

    synchronized void pollProfiler() throws Exception {
        if (startFailed) {
            cleanupFailedStart(true);
            return;
        }
        if (state == State.SOURCE_ENABLED) {
            boolean stillOwned;
            try {
                stillOwned = capture.equals(CaptureProtocol.parseActive(profiler.execute("status,signalcookie")));
            } catch (Exception unavailableOrChanged) {
                stillOwned = false;
            }
            if (!stillOwned) stop();
        } else if (state == State.PROFILER_ONLY) {
            boolean running;
            try {
                running = profilerRunning(profiler.execute("status"));
            } catch (Exception unavailable) {
                running = false;
            }
            if (!running) stop();
        } else if (state == State.INCOMPLETE && canMakeCleanupProgress()) {
            stop();
        }
    }

    // ---------------------------------------------------------------- profiler-only mode
    //
    // Admission policy none turns the agent into a plain async-profiler launcher: no eBPF program is loaded, no
    // signal is negotiated, and no BPF privileges are needed. The JFR is the only measurement. The correlation
    // path still receives a single captureFinalized record so tooling can tell "deliberately disabled" from "lost".

    private Path startProfilerOnly() throws Exception {
        boolean apStartAttempted = false;
        try {
            String fileOption = config.jfrOutput() == null ? ",file=" + manifest.jfrPath() : "";
            apStartAttempted = true;
            String started = profiler.execute("start," + config.asyncProfilerOptions() + fileOption);
            manifest.root().getAsyncProfilerBuilder().setStartResponse(started.strip());
            if (!profilerRunning(profiler.execute("status"))) {
                throw new IllegalStateException("Async-profiler did not report a running profile: " + started.strip());
            }
            state = State.PROFILER_ONLY;
            manifest.state(ManifestState.MANIFEST_STATE_PROFILER_ONLY);
            return manifest.directory();
        } catch (Exception failure) {
            startFailed = true;
            cleanupFailedStart(apStartAttempted);
            state = State.INCOMPLETE;
            try {
                manifest.failure(ManifestState.MANIFEST_STATE_FAILED, failure);
            } catch (IOException manifestFailure) {
                failure.addSuppressed(manifestFailure);
            }
            throw failure;
        }
    }

    private Path stopProfilerOnly() throws Exception {
        try {
            if (apStopReceipt == null) {
                String response;
                try {
                    response = profiler.execute("stop");
                } catch (Exception stopFailure) {
                    // A timeout or external stop may already have finalized the recording; only a profiler
                    // that is still running turns the lost stop into a failure.
                    String status;
                    try {
                        status = profiler.execute("status");
                    } catch (Exception queryFailure) {
                        stopFailure.addSuppressed(queryFailure);
                        throw stopFailure;
                    }
                    if (profilerRunning(status)) throw stopFailure;
                    response = status;
                }
                apStopReceipt = response.strip();
                manifest.root()
                        .setAsyncProfilerStop(AsyncProfilerStop.newBuilder().setResponse(apStopReceipt));
                state = State.AP_FINALIZED;
                auditState(ManifestState.MANIFEST_STATE_AP_FINALIZED);
            }
            manifest.verifyJfrIdentity();
            ArtifactVerifier.requireReadableJfr(manifest.jfrPath());
            FileArtifact jfrArtifact = ArtifactVerifier.artifact(manifest.directory(), manifest.jfrPath());
            AnalysisInputs analysisInputs = AnalysisInputs.newBuilder()
                    .setSessionId(sessionId)
                    .setTargetPid((int) config.targetPid())
                    .setSampling(config.sampling())
                    .setTimeSplit(config.timeSplit())
                    .setJfrArtifact(jfrArtifact)
                    .build();
            CaptureFinalized footer = CaptureFinalized.newBuilder()
                    .setSessionId(sessionId)
                    .setState(FinalizedState.FINALIZED_STATE_PROFILER_ONLY)
                    .setAnalysisInputs(analysisInputs)
                    .setApStopResponse(apStopReceipt)
                    .setFinalizedAt(ManifestStore.now())
                    .build();
            ArtifactVerifier.writeFooterOnly(manifest.sourcePath(), footer);
            manifest.root()
                    .setCorrelationArtifact(ArtifactVerifier.artifact(manifest.directory(), manifest.sourcePath()));
            state = State.COMPLETE;
            try {
                manifest.complete(analysisInputs);
            } catch (IOException auditFailure) {
                System.err.println("JONOFFCPU profiler-only capture is complete but audit manifest publication failed: "
                        + auditFailure.getMessage());
            }
            return manifest.manifestPath();
        } catch (Exception failure) {
            state = State.INCOMPLETE;
            try {
                manifest.failure(ManifestState.MANIFEST_STATE_INCOMPLETE, failure);
            } catch (IOException manifestFailure) {
                failure.addSuppressed(manifestFailure);
            }
            throw failure;
        }
    }

    private static boolean profilerRunning(String status) {
        return status.strip().startsWith("Profiling is running");
    }

    private PrepareRequest prepareRequest() {
        return PrepareRequest.newBuilder()
                .setTargetPid((int) config.targetPid())
                .setOutputPath(manifest.sourcePath().toString())
                .setSampling(config.sampling())
                .setTimeSplit(config.timeSplit())
                // prepare runs on the controller thread, whose profiler polls must not appear in its own capture.
                .setExcludeCallingThread(true)
                .build();
    }

    private EnableRequest enableRequest() {
        return EnableRequest.newBuilder()
                .setSessionId(sessionId)
                .setCaptureEpoch((int) capture.epoch())
                .setSignal(capture.signal())
                .setSignalDelivery(capture.delivery())
                .setSampling(config.sampling())
                .setTimeSplit(config.timeSplit())
                .build();
    }

    /** Decodes a reply of the collector's ABI; an error reply is returned for the caller to judge. */
    static CollectorReply reply(byte[] bytes, String operation) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_REPLY_BYTES) {
            throw new IllegalArgumentException(
                    operation + " reply is missing or exceeds " + MAX_REPLY_BYTES + " bytes");
        }
        CollectorReply reply;
        try {
            reply = CollectorReply.parseFrom(bytes);
        } catch (InvalidProtocolBufferException error) {
            throw new IllegalArgumentException("Invalid " + operation + " reply", error);
        }
        if (reply.getAbiVersion() != COLLECTOR_ABI_VERSION) {
            throw new IllegalStateException(
                    operation + " reply has unsupported collector ABI version " + reply.getAbiVersion());
        }
        return reply;
    }

    /** A reply that succeeded with the expected result; an error reply becomes a {@link CollectorException}. */
    private static CollectorReply requireSuccess(
            CollectorReply reply, String operation, CollectorReply.ResultCase expected) {
        if (reply.hasError()) throw new CollectorException(operation, reply.getError());
        if (reply.getResultCase() != expected) {
            throw new IllegalStateException(operation + " returned an unexpected result: " + reply.getResultCase());
        }
        return reply;
    }

    private void validatePrepared(Prepared result) {
        requireSourcePath("sourcePath", result.getSourcePath());
        validateEffectivePolicy(result.getSampling(), result.getTimeSplit());
        ArtifactVerifier.requireEqual("targetPid", config.targetPid(), Integer.toUnsignedLong(result.getTargetPid()));
        if (result.getHostTgid() == 0) throw new IllegalArgumentException("Invalid native hostTgid");
        VerifiedIdentity identity = result.getVerifiedIdentity();
        if (identity.getPidNamespaceDevice() == 0)
            throw new IllegalArgumentException("Invalid native pidNamespaceDevice");
        if (identity.getPidNamespaceInode() == 0)
            throw new IllegalArgumentException("Invalid native pidNamespaceInode");
        if (!identity.getClockVerified()) {
            throw new IllegalStateException("Native collector did not verify the CLOCK_MONOTONIC namespace");
        }
        ArtifactVerifier.requireEqual("monotonicOffsetNanos", 0L, identity.getMonotonicOffsetNanos());
    }

    private void validateEnabled(Enabled result) {
        ArtifactVerifier.requireEqual("native sessionId", sessionId, result.getSessionId());
        ArtifactVerifier.requireEqual(
                "native captureEpoch", capture.epoch(), Integer.toUnsignedLong(result.getCaptureEpoch()));
        ArtifactVerifier.requireEqual("native signal", capture.signal(), result.getSignal());
        ArtifactVerifier.requireEqual("native signalDelivery", capture.delivery(), result.getSignalDelivery());
        validateEffectivePolicy(result.getSampling(), result.getTimeSplit());
        requireSourcePath("enabled sourcePath", result.getSourcePath());
        ArtifactVerifier.requireEqual("enabled targetPid", prepared.getTargetPid(), result.getTargetPid());
        ArtifactVerifier.requireEqual("enabled hostTgid", prepared.getHostTgid(), result.getHostTgid());
        ArtifactVerifier.requireEqual(
                "enabled verifiedIdentity", prepared.getVerifiedIdentity(), result.getVerifiedIdentity());
    }

    private void validateStopped(Stopped result) {
        requireSourcePath("stopped sourcePath", result.getSourcePath());
        ArtifactVerifier.requireEqual(
                "native captureEnd sessionId", sessionId, result.getCaptureEnd().getSessionId());
        ArtifactVerifier.requireEqual(
                "native captureEnd captureEpoch",
                capture.epoch(),
                Integer.toUnsignedLong(result.getCaptureEnd().getCaptureEpoch()));
    }

    private void requireSourcePath(String field, String reported) {
        ArtifactVerifier.requireEqual(
                field,
                manifest.sourcePath().toString(),
                Path.of(reported).toAbsolutePath().normalize().toString());
    }

    // The native collector echoes the sampling and timeSplit messages it was prepared with; any drift is a protocol
    // error.
    private void validateEffectivePolicy(Sampling sampling, TimeSplit timeSplit) {
        ArtifactVerifier.requireEqual("sampling", config.sampling(), sampling);
        ArtifactVerifier.requireEqual("timeSplit", config.timeSplit(), timeSplit);
    }

    private void requireCapture(CaptureProtocol.Active found) {
        ArtifactVerifier.requireEqual("AP sessionId", sessionId, found.sessionId());
        ArtifactVerifier.requireEqual("AP signal delivery", config.signalDelivery(), found.delivery());
    }

    private void requireStopped(CaptureProtocol.Stopped stopped) {
        ArtifactVerifier.requireEqual("stopped sessionId", sessionId, stopped.sessionId());
        ArtifactVerifier.requireEqual("stopped signal", capture.signal(), stopped.signal());
        ArtifactVerifier.requireEqual("stopped epoch", capture.epoch(), stopped.epoch());
        ArtifactVerifier.requireEqual("stopped delivery", capture.delivery(), stopped.delivery());
    }

    private void cleanupFailedStart(boolean apStartAttempted) {
        if (handle != 0 && !sourceClosed) {
            try {
                if (sourceEnabled && (!nativeStopAttempted || retryableNativeStop)) {
                    nativeStopAttempted = true;
                    CollectorReply cleanup =
                            reply(source.stop(handle, config.nativeStopTimeoutMillis()), "cleanup stop");
                    manifest.root().setNativeCleanupStop(cleanup);
                    if (cleanup.hasError() && cleanup.getState() == CollectorState.COLLECTOR_STATE_STOPPING) {
                        retryableNativeStop = true;
                        return;
                    }
                    retryableNativeStop = false;
                }
                CollectorReply close = reply(source.close(handle), "cleanup close");
                manifest.root().setNativeCleanupClose(close);
                sourceClosed = !close.hasError() && close.getState() == CollectorState.COLLECTOR_STATE_CLOSED;
            } catch (Throwable cleanupFailure) {
                manifest.root().setNativeCleanupFailure(cleanupFailure.toString());
            }
        }
        if (handle != 0 && !sourceClosed) return;
        if (apStartAttempted && !failedStartApCleanupDone) {
            try {
                String command = config.profilerOnly()
                        ? "stop"
                        : "stop,signalcookie,signalid="
                                + sessionId
                                + (capture == null ? "" : ",signalepoch=" + capture.epoch());
                String response = profiler.execute(command);
                manifest.root().setAsyncProfilerCleanup(response.strip());
                failedStartApCleanupDone = true;
            } catch (Throwable cleanupFailure) {
                manifest.root().setAsyncProfilerCleanupFailure(cleanupFailure.toString());
            }
        }
    }

    private boolean canMakeCleanupProgress() {
        return retryableNativeStop
                || nativeStopAttempted && !sourceClosed
                || sourceClosed && apStopped == null && !apCleanupTerminal;
    }

    private void auditState(ManifestState nextState) {
        try {
            manifest.state(nextState);
        } catch (IOException failure) {
            System.err.println("JONOFFCPU audit manifest checkpoint failed: " + failure.getMessage());
        }
    }

    private void auditCheckpoint() {
        try {
            manifest.checkpoint();
        } catch (IOException failure) {
            System.err.println("JONOFFCPU audit manifest checkpoint failed: " + failure.getMessage());
        }
    }

    /** async-profiler's admission counters reconcile: every admitted signal is rejected or accepted exactly once. */
    private static void validateCounterInvariants(AsyncProfilerStats counters) {
        BigInteger admitted = unsigned(counters.getAdmittedSignals());
        BigInteger rejections = unsigned(counters.getInvalidSignalCode())
                .add(unsigned(counters.getZeroCookie()))
                .add(unsigned(counters.getZeroSequence()))
                .add(unsigned(counters.getStaleEpoch()))
                .add(unsigned(counters.getAcceptedCookies()));
        if (!admitted.equals(rejections)) throw new IllegalStateException("AP admission counters do not reconcile");
        BigInteger accepted = unsigned(counters.getAcceptedCookies());
        if (!accepted.equals(unsigned(counters.getCaptureFailures()).add(unsigned(counters.getSubmittedSamples())))) {
            throw new IllegalStateException("AP accepted counters do not reconcile");
        }
    }

    private static BigInteger unsigned(long value) {
        return new BigInteger(Long.toUnsignedString(value));
    }
}
