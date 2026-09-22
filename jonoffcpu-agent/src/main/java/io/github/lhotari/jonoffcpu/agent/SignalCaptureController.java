// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class SignalCaptureController {
    interface ProfilerClient {
        String version();

        String execute(String command) throws Exception;
    }

    interface SourceClient {
        String prepare(String configJson);

        String enable(long handle, String captureJson);

        String stop(long handle, long timeoutMillis);

        String close(long handle);
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
    private JsonObject prepared;
    private JsonObject sourceStop;
    private JsonObject sourceClose;
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
            public String prepare(String configJson) {
                return NativeCollector.prepare(configJson);
            }

            @Override
            public String enable(long handle, String captureJson) {
                return NativeCollector.enable(handle, captureJson);
            }

            @Override
            public String stop(long handle, long timeoutMillis) {
                return NativeCollector.stop(handle, timeoutMillis);
            }

            @Override
            public String close(long handle) {
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
        JsonObject actualIdentity = new JsonObject();
        actualIdentity.addProperty("version", actualVersion);
        actualIdentity.addProperty("cookieProtocol", "signal-capture-v1");
        actualIdentity.addProperty("singletonCapabilityVerified", true);
        manifest.root().getAsJsonObject("asyncProfiler").add("actualIdentity", actualIdentity);
        manifest.checkpoint();

        if (config.profilerOnly()) return startProfilerOnly();

        boolean apStartAttempted = false;
        try {
            prepared = JsonSupport.requireControlSuccess(
                    source.prepare(prepareConfig().toString()), "prepare", "prepared");
            handle = JsonSupport.parseHandle(prepared);
            validatePrepared(prepared);
            manifest.root().add("nativePrepare", prepared.deepCopy());
            state = State.PREPARED;
            manifest.state("prepared");

            apStartAttempted = true;
            String fileOption = config.jfrOutput() == null ? ",file=" + manifest.jfrPath() : "";
            String started = profiler.execute("start,"
                    + config.asyncProfilerOptions()
                    + fileOption
                    + ",signalcookie="
                    + config.signalDelivery()
                    + ",signalid="
                    + sessionId);
            CaptureProtocol.Active startCapture = CaptureProtocol.parseActive(started);
            requireCapture(startCapture);
            CaptureProtocol.Active statusCapture = CaptureProtocol.parseActive(profiler.execute("status,signalcookie"));
            if (!startCapture.equals(statusCapture)) {
                throw new IllegalStateException("AP start/status capture identity mismatch");
            }
            capture = startCapture;
            JsonObject ap = manifest.root().getAsJsonObject("asyncProfiler");
            ap.addProperty("sessionId", sessionId);
            ap.addProperty("captureEpoch", capture.epoch());
            ap.addProperty("signal", capture.signal());
            ap.addProperty("signalDelivery", capture.delivery());
            state = State.AP_STARTED_AND_VERIFIED;
            manifest.state("apStartedAndVerified");

            JsonObject enabled = JsonSupport.requireControlSuccess(
                    source.enable(handle, enableConfig().toString()), "enable", "enabled");
            sourceEnabled = true;
            validateEnabled(enabled);
            manifest.root().add("nativeEnable", enabled.deepCopy());
            state = State.SOURCE_ENABLED;
            manifest.state("sourceEnabled");
            return manifest.directory();
        } catch (Exception failure) {
            startFailed = true;
            cleanupFailedStart(apStartAttempted);
            state = State.INCOMPLETE;
            try {
                manifest.failure("failed", failure);
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
                auditState("stoppingSource");
                nativeStopAttempted = true;
                try {
                    JsonObject stopEnvelope =
                            JsonSupport.controlEnvelope(source.stop(handle, config.nativeStopTimeoutMillis()), "stop");
                    manifest.root().add("nativeStop", stopEnvelope.deepCopy());
                    auditCheckpoint();
                    if (!JsonSupport.requireBoolean(stopEnvelope, "ok")) {
                        retryableNativeStop = "stopping".equals(JsonSupport.requireString(stopEnvelope, "state"));
                        nativeStopFailure = nativeFailure("stop", stopEnvelope);
                        if (retryableNativeStop) {
                            state = State.INCOMPLETE;
                            throw nativeStopFailure;
                        }
                    } else {
                        String stopState = JsonSupport.requireString(stopEnvelope, "state");
                        if (!stopState.equals("complete") && !stopState.equals("incomplete")) {
                            throw new IllegalStateException("Native stop returned unexpected state: " + stopState);
                        }
                        validateNativeIdentity(stopEnvelope);
                        sourceStop = stopEnvelope;
                        nativeCaptureComplete = stopState.equals("complete");
                        retryableNativeStop = false;
                        nativeStopFailure = null;
                    }
                } catch (Exception failure) {
                    if (retryableNativeStop) throw failure;
                    nativeStopFailure = failure;
                }
            }

            if (!sourceClosed) {
                JsonObject closeEnvelope = JsonSupport.controlEnvelope(source.close(handle), "close");
                manifest.root().add("nativeClose", closeEnvelope.deepCopy());
                auditCheckpoint();
                if (!JsonSupport.requireBoolean(closeEnvelope, "ok")) {
                    state = State.INCOMPLETE;
                    throw nativeFailure("close", closeEnvelope);
                }
                JsonSupport.requireEqual(
                        "native close state", "closed", JsonSupport.requireString(closeEnvelope, "state"));
                sourceClose = closeEnvelope;
                sourceClosed = true;
                if (sourceStop == null && sourceClose.has("captureEnd")) {
                    sourceStop = sourceClose;
                    nativeCaptureComplete = "complete"
                            .equals(JsonSupport.requireString(
                                    JsonSupport.requireObject(sourceClose, "captureEnd"), "state"));
                }
                manifest.root().add("nativeClose", sourceClose.deepCopy());
                state = State.SOURCE_STOPPED;
                auditState(nativeCaptureComplete ? "sourceStopped" : "sourceIncomplete");
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
                JsonObject apStop = new JsonObject();
                apStop.addProperty("response", response.strip());
                apStop.addProperty("finalized", stopped.finalized());
                apStop.addProperty("stoppedAtMonotonicNanos", stopped.stoppedAt());
                apStop.addProperty("reason", stopped.reason());
                apStop.add("counters", stringMap(stopped.manifestCounters()));
                manifest.root().add("asyncProfilerStop", apStop);
                auditCheckpoint();
                if (!stopped.finalized()) {
                    throw new IllegalStateException(
                            "Async-profiler did not finalize the recording: " + stopped.reason());
                }
                validateCounterInvariants(stopped.manifestCounters());
                state = State.AP_FINALIZED;
                auditState("apFinalized");
            }

            if (nativeStopFailure != null) {
                throw new IllegalStateException(
                        "Native source stop failed after safe close and AP finalization", nativeStopFailure);
            }
            if (!nativeCaptureComplete || sourceStop == null) {
                throw new IllegalStateException("Native source finalization was incomplete");
            }
            {
                JsonObject end = ArtifactVerifier.verifySource(
                        manifest.sourcePath(),
                        sourceStop,
                        sessionId,
                        capture.epoch(),
                        capture.signal(),
                        capture.delivery(),
                        config.sampling(),
                        JsonSupport.requireNumber(prepared, "hostTgid", 1, 0xffffffffL),
                        config.targetPid(),
                        JsonSupport.requireObject(prepared, "verifiedIdentity"));
                if (sourceClose.has("captureEnd")
                        && !sourceClose.get("captureEnd").equals(end)) {
                    throw new IOException("Native close captureEnd differs from finalized source");
                }
                manifest.verifyJfrIdentity();
                ArtifactVerifier.JfrResult jfr = ArtifactVerifier.verifyJfr(
                        manifest.jfrPath(),
                        sessionId,
                        capture.epoch(),
                        capture.signal(),
                        capture.delivery(),
                        apStopped.manifestCounters());
                if (jfr.processId() != config.targetPid()) {
                    throw new IOException("AP JFR process identity does not match targetPid");
                }
                ArtifactVerifier.RawArtifact sourceArtifact =
                        ArtifactVerifier.rawArtifact(manifest.directory(), manifest.sourcePath());
                ArtifactVerifier.Artifact jfrArtifact =
                        ArtifactVerifier.artifact(manifest.directory(), manifest.jfrPath());
                manifest.root().add("sourceCaptureEnd", end.deepCopy());
                JsonObject processStart = new JsonObject();
                processStart.addProperty("valueMillis", jfr.processStartTimeMillis());
                processStart.addProperty("source", "async-profiler OS::processStartTime informational timestamp");
                processStart.addProperty("kernelGenerationProof", false);
                manifest.root().add("processStartTime", processStart);
                JsonObject analysisInputs = analysisInputs(apStopped.manifestCounters(), sourceArtifact, jfrArtifact);
                JsonObject footer = new JsonObject();
                footer.addProperty("schemaVersion", 1);
                footer.addProperty("recordType", "captureFinalized");
                footer.addProperty("sessionId", sessionId);
                footer.addProperty("captureEpoch", capture.epoch());
                footer.addProperty("state", "complete");
                footer.add("analysisInputs", analysisInputs.deepCopy());
                footer.addProperty("apStopResponse", apStopReceipt);
                footer.addProperty("finalizedAt", Instant.now().toString());
                ArtifactVerifier.appendFinalized(manifest.sourcePath(), sourceArtifact, footer);
                manifest.root()
                        .add(
                                "correlationArtifact",
                                ArtifactVerifier.artifact(manifest.directory(), manifest.sourcePath())
                                        .json());
                state = State.COMPLETE;
                try {
                    manifest.complete(analysisInputs);
                } catch (IOException auditFailure) {
                    System.err.println(
                            "JONOFFCPU signal capture footer is complete but audit manifest publication failed: "
                                    + auditFailure.getMessage());
                }
            }
            return manifest.manifestPath();
        } catch (Exception failure) {
            state = State.INCOMPLETE;
            try {
                manifest.failure("incomplete", failure);
            } catch (IOException manifestFailure) {
                failure.addSuppressed(manifestFailure);
            }
            throw failure;
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
    // path still receives a single captureFinalized row so tooling can tell "deliberately disabled" from "lost".

    private Path startProfilerOnly() throws Exception {
        manifest.root().addProperty("mode", "profilerOnly");
        boolean apStartAttempted = false;
        try {
            String fileOption = config.jfrOutput() == null ? ",file=" + manifest.jfrPath() : "";
            apStartAttempted = true;
            String started = profiler.execute("start," + config.asyncProfilerOptions() + fileOption);
            manifest.root().addProperty("asyncProfilerStart", started.strip());
            if (!profilerRunning(profiler.execute("status"))) {
                throw new IllegalStateException("Async-profiler did not report a running profile: " + started.strip());
            }
            state = State.PROFILER_ONLY;
            manifest.state("profilerOnly");
            return manifest.directory();
        } catch (Exception failure) {
            startFailed = true;
            cleanupFailedStart(apStartAttempted);
            state = State.INCOMPLETE;
            try {
                manifest.failure("failed", failure);
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
                manifest.root().addProperty("asyncProfilerStop", apStopReceipt);
                state = State.AP_FINALIZED;
                auditState("apFinalized");
            }
            manifest.verifyJfrIdentity();
            ArtifactVerifier.requireReadableJfr(manifest.jfrPath());
            ArtifactVerifier.Artifact jfrArtifact = ArtifactVerifier.artifact(manifest.directory(), manifest.jfrPath());
            JsonObject analysisInputs = new JsonObject();
            analysisInputs.addProperty("mode", "profilerOnly");
            analysisInputs.addProperty("sessionId", sessionId);
            analysisInputs.addProperty("targetPid", config.targetPid());
            analysisInputs.add("sampling", config.sampling().json());
            analysisInputs.add("jfrArtifact", jfrArtifact.json());
            JsonObject footer = new JsonObject();
            footer.addProperty("schemaVersion", 1);
            footer.addProperty("recordType", "captureFinalized");
            footer.addProperty("sessionId", sessionId);
            footer.addProperty("state", "profilerOnly");
            footer.addProperty("sourceDisabled", true);
            footer.addProperty("reason", "sampling.admission.policy=none");
            footer.add("analysisInputs", analysisInputs.deepCopy());
            footer.addProperty("apStopResponse", apStopReceipt);
            footer.addProperty("finalizedAt", Instant.now().toString());
            ArtifactVerifier.writeFooterOnly(manifest.sourcePath(), footer);
            manifest.root()
                    .add(
                            "correlationArtifact",
                            ArtifactVerifier.artifact(manifest.directory(), manifest.sourcePath())
                                    .json());
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
                manifest.failure("incomplete", failure);
            } catch (IOException manifestFailure) {
                failure.addSuppressed(manifestFailure);
            }
            throw failure;
        }
    }

    private static boolean profilerRunning(String status) {
        return status.strip().startsWith("Profiling is running");
    }

    private JsonObject prepareConfig() {
        JsonObject value = new JsonObject();
        value.addProperty("schemaVersion", 1);
        value.addProperty("targetPid", config.targetPid());
        value.addProperty("outputPath", manifest.sourcePath().toString());
        value.add("sampling", config.sampling().json());
        return value;
    }

    private JsonObject enableConfig() {
        JsonObject value = new JsonObject();
        value.addProperty("schemaVersion", 1);
        value.addProperty("sessionId", sessionId);
        value.addProperty("captureEpoch", capture.epoch());
        value.addProperty("signal", capture.signal());
        value.addProperty("signalDelivery", capture.delivery());
        value.add("sampling", config.sampling().json());
        return value;
    }

    private void validatePrepared(JsonObject result) {
        JsonSupport.requireEqual(
                "sourcePath",
                manifest.sourcePath().toString(),
                Path.of(JsonSupport.requireString(result, "sourcePath"))
                        .toAbsolutePath()
                        .normalize()
                        .toString());
        validateEffectivePolicy(result);
        JsonSupport.requireEqual(
                "targetPid", config.targetPid(), JsonSupport.requireNumber(result, "targetPid", 1, 0xffffffffL));
        JsonSupport.requireNumber(result, "hostTgid", 1, 0xffffffffL);
        JsonObject identity = JsonSupport.requireObject(result, "verifiedIdentity");
        if (!JsonSupport.requireString(identity, "registrationToken").matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Invalid native registrationToken");
        }
        JsonSupport.requireDecimal(identity, "processGenerationNs");
        JsonSupport.requireDecimal(identity, "timeNamespaceInode");
        for (String key : new String[] {"pidNamespaceDevice", "pidNamespaceInode"}) {
            if (new BigInteger(JsonSupport.requireDecimal(identity, key)).signum() == 0) {
                throw new IllegalArgumentException("Invalid native " + key);
            }
        }
        if (!JsonSupport.requireBoolean(identity, "clockVerified")) {
            throw new IllegalStateException("Native collector did not verify the CLOCK_MONOTONIC namespace");
        }
        JsonSupport.requireEqual(
                "monotonicOffsetNanos", "0", JsonSupport.requireDecimal(identity, "monotonicOffsetNanos"));
    }

    private void validateEnabled(JsonObject result) {
        JsonSupport.requireEqual(
                "native signalDelivery", capture.delivery(), JsonSupport.requireString(result, "signalDelivery"));
        validateNativeIdentity(result);
        validateEffectivePolicy(result);
        JsonSupport.requireEqual(
                "enabled sourcePath",
                manifest.sourcePath().toString(),
                Path.of(JsonSupport.requireString(result, "sourcePath"))
                        .toAbsolutePath()
                        .normalize()
                        .toString());
        JsonSupport.requireEqual(
                "enabled targetPid",
                config.targetPid(),
                JsonSupport.requireNumber(result, "targetPid", 1, 0xffffffffL));
        JsonSupport.requireEqual(
                "enabled hostTgid",
                JsonSupport.requireNumber(prepared, "hostTgid", 1, 0xffffffffL),
                JsonSupport.requireNumber(result, "hostTgid", 1, 0xffffffffL));
        JsonSupport.requireEqual(
                "enabled verifiedIdentity",
                JsonSupport.requireObject(prepared, "verifiedIdentity"),
                JsonSupport.requireObject(result, "verifiedIdentity"));
    }

    private void validateNativeIdentity(JsonObject result) {
        if (result.has("handle")) {
            JsonSupport.requireEqual("native handle", handle, JsonSupport.parseHandle(result));
        }
        if (result.has("sessionId")) {
            JsonSupport.requireEqual("native sessionId", sessionId, JsonSupport.requireString(result, "sessionId"));
        }
        if (result.has("captureEpoch")) {
            JsonSupport.requireEqual(
                    "native captureEpoch",
                    capture.epoch(),
                    JsonSupport.requireNumber(result, "captureEpoch", 1, 0xffffffffL));
        }
        if (result.has("signal")) {
            JsonSupport.requireEqual(
                    "native signal",
                    (long) capture.signal(),
                    JsonSupport.requireNumber(result, "signal", 1, Integer.MAX_VALUE));
        }
    }

    // The native collector echoes the sampling object it was prepared with; any drift is a protocol error.
    private void validateEffectivePolicy(JsonObject result) {
        JsonSupport.requireEqual("sampling", config.sampling().json(), JsonSupport.requireObject(result, "sampling"));
    }

    private void requireCapture(CaptureProtocol.Active found) {
        JsonSupport.requireEqual("AP sessionId", sessionId, found.sessionId());
        JsonSupport.requireEqual("AP signal delivery", config.signalDelivery(), found.delivery());
    }

    private void requireStopped(CaptureProtocol.Stopped stopped) {
        JsonSupport.requireEqual("stopped sessionId", sessionId, stopped.sessionId());
        JsonSupport.requireEqual("stopped signal", capture.signal(), stopped.signal());
        JsonSupport.requireEqual("stopped epoch", capture.epoch(), stopped.epoch());
        JsonSupport.requireEqual("stopped delivery", capture.delivery(), stopped.delivery());
    }

    private JsonObject analysisInputs(
            Map<String, String> counters,
            ArtifactVerifier.RawArtifact sourceArtifact,
            ArtifactVerifier.Artifact jfrArtifact) {
        JsonObject identity = JsonSupport.requireObject(prepared, "verifiedIdentity");
        JsonObject value = new JsonObject();
        value.addProperty("sessionId", sessionId);
        value.addProperty("captureEpoch", capture.epoch());
        value.addProperty("signal", capture.signal());
        value.addProperty("signalDelivery", capture.delivery());
        value.addProperty("hostTgid", JsonSupport.requireNumber(prepared, "hostTgid", 1, 0xffffffffL));
        value.addProperty("targetPid", config.targetPid());
        value.add("sampling", config.sampling().json());
        value.addProperty("monotonicOffsetNanos", JsonSupport.requireDecimal(identity, "monotonicOffsetNanos"));
        value.addProperty("clockVerified", JsonSupport.requireBoolean(identity, "clockVerified"));
        value.add("verifiedIdentity", identity.deepCopy());
        value.add("apStats", stringMap(counters));
        value.add("sourceArtifact", sourceArtifact.json());
        value.add("jfrArtifact", jfrArtifact.json());
        return value;
    }

    private void cleanupFailedStart(boolean apStartAttempted) {
        if (handle != 0 && !sourceClosed) {
            try {
                if (sourceEnabled && (!nativeStopAttempted || retryableNativeStop)) {
                    nativeStopAttempted = true;
                    JsonObject cleanup = JsonSupport.controlEnvelope(
                            source.stop(handle, config.nativeStopTimeoutMillis()), "cleanup stop");
                    manifest.root().add("nativeCleanupStop", cleanup.deepCopy());
                    if (!JsonSupport.requireBoolean(cleanup, "ok")
                            && "stopping".equals(JsonSupport.requireString(cleanup, "state"))) {
                        retryableNativeStop = true;
                        return;
                    }
                }
                JsonObject close = JsonSupport.controlEnvelope(source.close(handle), "cleanup close");
                manifest.root().add("nativeCleanupClose", close.deepCopy());
                sourceClosed = JsonSupport.requireBoolean(close, "ok")
                        && "closed".equals(JsonSupport.requireString(close, "state"));
            } catch (Throwable cleanupFailure) {
                manifest.root().addProperty("nativeCleanupFailure", cleanupFailure.toString());
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
                manifest.root().addProperty("asyncProfilerCleanup", response.strip());
                failedStartApCleanupDone = true;
            } catch (Throwable cleanupFailure) {
                manifest.root().addProperty("asyncProfilerCleanupFailure", cleanupFailure.toString());
            }
        }
    }

    private boolean canMakeCleanupProgress() {
        return retryableNativeStop
                || nativeStopAttempted && !sourceClosed
                || sourceClosed && apStopped == null && !apCleanupTerminal;
    }

    private void auditState(String nextState) {
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

    private static IllegalStateException nativeFailure(String operation, JsonObject response) {
        JsonObject error = JsonSupport.requireObject(response, "error");
        return new IllegalStateException(operation
                + " failed ["
                + JsonSupport.requireString(error, "code")
                + "]: "
                + JsonSupport.requireString(error, "message"));
    }

    private static JsonObject stringMap(Map<String, String> values) {
        JsonObject result = new JsonObject();
        values.forEach(result::addProperty);
        return result;
    }

    private static void validateCounterInvariants(Map<String, String> counters) {
        BigInteger admitted = unsigned(counters.get("admittedSignals"));
        BigInteger rejections = unsigned(counters.get("invalidSignalCode"))
                .add(unsigned(counters.get("zeroCookie")))
                .add(unsigned(counters.get("zeroSequence")))
                .add(unsigned(counters.get("staleEpoch")))
                .add(unsigned(counters.get("acceptedCookies")));
        if (!admitted.equals(rejections)) throw new IllegalStateException("AP admission counters do not reconcile");
        BigInteger accepted = unsigned(counters.get("acceptedCookies"));
        if (!accepted.equals(
                unsigned(counters.get("captureFailures")).add(unsigned(counters.get("submittedSamples"))))) {
            throw new IllegalStateException("AP accepted counters do not reconcile");
        }
    }

    private static BigInteger unsigned(String value) {
        BigInteger number = new BigInteger(value);
        if (number.signum() < 0 || number.bitLength() > 64) throw new IllegalArgumentException("Invalid u64 counter");
        return number;
    }
}
