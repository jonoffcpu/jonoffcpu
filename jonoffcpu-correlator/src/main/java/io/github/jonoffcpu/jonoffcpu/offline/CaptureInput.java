// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

import com.google.protobuf.Message;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureFormat;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Validates a finalized source stream and its self-contained receipt before correlation. */
final class CaptureInput {
    static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    static final String SOURCE_ID = "jonoffcpu.offcpu.v1";

    final CaptureProto.CaptureStart start;
    /** Null when a partial capture has none. */
    final CaptureProto.CaptureEnd end;
    /** Null when a partial capture has none. */
    final CaptureProto.CaptureFinalized footer;
    /** The footer's analysis inputs, or null without a footer: a partial capture has no finalization witness. */
    final CaptureProto.AnalysisInputs inputs;

    final String sourceDigest;
    final String jfrDigest;
    /** async-profiler's {@code stopped-at} from the footer's stop reply; null without a footer. */
    final Long apStoppedAtNanos;

    final Budget budget;
    final boolean partial;
    /** What partial mode reports about the prefix; the engine adds what it observes in the JFR. */
    final ReportProto.PartialReport.Builder diagnostics;

    private CaptureInput(
            CaptureProto.CaptureStart start,
            CaptureProto.CaptureEnd end,
            CaptureProto.CaptureFinalized footer,
            String sourceDigest,
            String jfrDigest,
            Long apStoppedAtNanos,
            Budget budget,
            boolean partial,
            ReportProto.PartialReport.Builder diagnostics) {
        this.start = start;
        this.end = end;
        this.footer = footer;
        this.inputs = footer == null ? null : footer.getAnalysisInputs();
        this.sourceDigest = sourceDigest;
        this.jfrDigest = jfrDigest;
        this.apStoppedAtNanos = apStoppedAtNanos;
        this.budget = budget;
        this.partial = partial;
        this.diagnostics = diagnostics;
    }

    static CaptureInput read(Path source, Path jfr, OfflineCorrelator.Limits limits, SourceVisitor visitor)
            throws IOException {
        return read(source, jfr, limits, false, false, visitor);
    }

    /** The validated sampling policy from {@code capture_start}. */
    SamplingPolicy sampling() throws IOException {
        return SamplingPolicy.parse(start.getSampling());
    }

    String sessionId() {
        return start.getSessionId();
    }

    long captureEpoch() {
        return Integer.toUnsignedLong(start.getCaptureEpoch());
    }

    /** The verified offset of the target's clock from the collector's, or null without a footer to prove it. */
    Long monotonicOffsetNanos() {
        return inputs == null ? null : inputs.getVerifiedIdentity().getMonotonicOffsetNanos();
    }

    /** async-profiler's terminal counters as the footer recorded them, or null without a footer. */
    CaptureProto.AsyncProfilerStats apStats() {
        return inputs == null ? null : inputs.getApStats();
    }

    static CaptureInput read(
            Path source, Path jfr, OfflineCorrelator.Limits limits, boolean partialJfr, SourceVisitor visitor)
            throws IOException {
        return read(source, jfr, limits, false, partialJfr, visitor);
    }

    static CaptureInput readPartial(Path source, Path jfr, OfflineCorrelator.Limits limits, SourceVisitor visitor)
            throws IOException {
        return read(source, jfr, limits, true, false, visitor);
    }

    private static CaptureInput read(
            Path source,
            Path jfr,
            OfflineCorrelator.Limits limits,
            boolean partial,
            boolean partialJfr,
            SourceVisitor visitor)
            throws IOException {
        Budget budget = new Budget(limits);
        MessageDigest rawHash = sha256();
        MessageDigest wholeHash = sha256();
        long rawBytes = 0;
        long sourceBytes = 0;
        int trailingBytes = 0;
        CaptureProto.CaptureStart start = null;
        CaptureProto.CaptureEnd end = null;
        CaptureProto.CaptureFinalized footer = null;
        int observations = 0;
        LongIntMap announcedStacks = new LongIntMap(1 << 12);
        ReportProto.PartialReport.Builder diagnostics = ReportProto.PartialReport.newBuilder();
        visitor.reading(budget, announcedStacks);
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            byte[] header = CaptureFormat.readHeader(input);
            wholeHash.update(header);
            rawHash.update(header);
            sourceBytes = header.length;
            rawBytes = header.length;
            CaptureFormat.Framed framed;
            while ((framed = CaptureFormat.next(input, limits.maxLineBytes())) != null) {
                require(footer == null, "Rows follow captureFinalized");
                byte[] bytes = framed.bytes();
                wholeHash.update(bytes);
                sourceBytes = Math.addExact(sourceBytes, bytes.length);
                if (framed.truncated()) {
                    require(partial, "Incomplete source record: truncated tail");
                    trailingBytes = bytes.length;
                    diagnostics.addIncompleteReasons(
                            ReportProto.IncompleteCause.INCOMPLETE_CAUSE_UNTERMINATED_SOURCE_TAIL);
                    break;
                }
                CaptureProto.Record record = framed.record();
                budget.countRow();
                if (record.getRecordCase() != CaptureProto.Record.RecordCase.CAPTURE_FINALIZED) {
                    rawHash.update(bytes);
                    rawBytes = Math.addExact(rawBytes, bytes.length);
                }
                switch (record.getRecordCase()) {
                    case CAPTURE_START -> {
                        require(start == null && end == null && observations == 0, "Duplicate/out-of-order start");
                        start = record.getCaptureStart();
                        budget.charge(start);
                        requireStartFields(start);
                        visitor.start(start);
                    }
                    case STACK -> {
                        require(start != null && end == null, "Stack outside source capture");
                        CaptureProto.Stack stack = record.getStack();
                        long stackId = stack.getId();
                        require(stackId >= 0, "Invalid stack id");
                        require(stack.getFrameCount() <= limits.maxFrames(), "Stack frame count limit exceeded");
                        require(!announcedStacks.contains(stackId), "Duplicate stack record");
                        announcedStacks.put(stackId, stack.getFrameCount());
                        visitor.stack(stackId, stack);
                    }
                    case OBSERVATION -> {
                        require(start != null && end == null, "Observation outside source capture");
                        // Source row numbers have always started at two; the classified records echo them.
                        visitor.observation(observations + 2, record.getObservation());
                        observations++;
                    }
                    case CAPTURE_END -> {
                        require(start != null && end == null, "Duplicate/out-of-order source end");
                        end = record.getCaptureEnd();
                        budget.charge(end);
                    }
                    case CAPTURE_FINALIZED -> {
                        CaptureProto.CaptureFinalized finalized = record.getCaptureFinalized();
                        if (start == null
                                && finalized.getState() == CaptureProto.FinalizedState.FINALIZED_STATE_PROFILER_ONLY) {
                            throw new IOException("This capture was recorded with sampling.admission.policy none"
                                    + " (profiler-only mode): the eBPF source was disabled, the JFR holds only"
                                    + " async-profiler events, and there is nothing to correlate");
                        }
                        require(end != null, "Finalization before source end");
                        footer = finalized;
                        budget.charge(footer);
                    }
                    default -> throw new IOException("Unknown source record");
                }
            }
        }
        require(start != null, "Missing source captureStart");
        require(partial || end != null && footer != null, "Missing source finalization");
        if (end == null)
            diagnostics.addIncompleteReasons(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_MISSING_CAPTURE_END);
        if (footer == null) {
            diagnostics.addIncompleteReasons(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_MISSING_CAPTURE_FINALIZED);
        }
        String session = start.getSessionId();
        try {
            require(UUID.fromString(session).toString().equals(session), "Noncanonical session UUID");
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid session UUID", e);
        }
        CaptureProto.SignalDelivery delivery = start.getSignalDelivery();
        require(
                delivery == CaptureProto.SignalDelivery.SIGNAL_DELIVERY_QUEUED
                        || delivery == CaptureProto.SignalDelivery.SIGNAL_DELIVERY_COALESCING,
                "Invalid signal delivery policy");
        require(start.getCaptureEpoch() != 0, "Invalid epoch");
        require(start.getSignal() > 0, "Invalid signal");
        require(start.getHostTgid() != 0 && start.getTargetPid() != 0, "Invalid target PID");
        // The agent writes one resolved sampling object and one time split everywhere; each is validated here and
        // every copy must equal it as a message.
        SamplingPolicy.parse(start.getSampling());
        TimeSplit.source(start.getTimeSplit());
        CaptureProto.VerifiedIdentity identity = start.getVerifiedIdentity();
        require(identity.getPidNamespaceDevice() != 0, "Invalid source namespace: pidNamespaceDevice");
        require(identity.getPidNamespaceInode() != 0, "Invalid source namespace: pidNamespaceInode");
        if (end != null) identity(end.getSessionId(), end.getCaptureEpoch(), start);
        if (footer != null) {
            CaptureProto.AnalysisInputs inputs = footer.getAnalysisInputs();
            identity(footer.getSessionId(), footer.getCaptureEpoch(), start);
            identity(inputs.getSessionId(), inputs.getCaptureEpoch(), start);
            require(
                    footer.getState() == CaptureProto.FinalizedState.FINALIZED_STATE_COMPLETE,
                    "Invalid finalization state");
            require(inputs.getSignalDelivery() == delivery, "Source delivery policy mismatch");
            require(inputs.getSignal() == start.getSignal(), "Source/footer mismatch: signal");
            require(inputs.getHostTgid() == start.getHostTgid(), "Source/footer mismatch: hostTgid");
            require(inputs.getTargetPid() == start.getTargetPid(), "Source/footer mismatch: targetPid");
            require(inputs.getSampling().equals(start.getSampling()), "Source/footer mismatch: sampling");
            require(inputs.getTimeSplit().equals(start.getTimeSplit()), "Source/footer mismatch: timeSplit");
            CaptureProto.VerifiedIdentity verified = inputs.getVerifiedIdentity();
            require(verified.getClockVerified(), "Clock translation not verified");
            require(
                    verified.getPidNamespaceDevice() == identity.getPidNamespaceDevice(),
                    "Verified namespace mismatch: pidNamespaceDevice");
            require(
                    verified.getPidNamespaceInode() == identity.getPidNamespaceInode(),
                    "Verified namespace mismatch: pidNamespaceInode");
            require(verified.getRegistrationToken() == identity.getRegistrationToken(), "Target binding mismatch");
            require(
                    verified.getProcessGenerationNanos() == identity.getProcessGenerationNanos(),
                    "Verified identity mismatch: processGenerationNanos");
            require(
                    verified.getTimeNamespaceInode() == identity.getTimeNamespaceInode(),
                    "Verified identity mismatch: timeNamespaceInode");
            require(
                    identity.getClockVerified()
                            && verified.getMonotonicOffsetNanos() == identity.getMonotonicOffsetNanos(),
                    "Verified clock mismatch");
        }
        if (end != null) {
            boolean completeEnd;
            switch (end.getState()) {
                case CAPTURE_STATE_COMPLETE -> completeEnd = true;
                case CAPTURE_STATE_INCOMPLETE -> completeEnd = false;
                default -> throw new IOException("Invalid source end state");
            }
            require(
                    completeEnd
                            == (end.getIncompleteReason()
                                    == CaptureProto.IncompleteReason.INCOMPLETE_REASON_UNSPECIFIED),
                    "Invalid source end incomplete reason");
            require(partial || completeEnd, "Incomplete capture");
            require(footer == null || completeEnd, "Finalization contradicts incomplete source end");
            if (!completeEnd) {
                diagnostics.addIncompleteReasons(
                        ReportProto.IncompleteCause.INCOMPLETE_CAUSE_SOURCE_CAPTURE_INCOMPLETE);
            }
            boolean timedOut = end.getDrainTimedOut();
            require(!completeEnd || !timedOut, "Source drain timed out");
            if (timedOut) {
                diagnostics.addIncompleteReasons(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_SOURCE_DRAIN_TIMED_OUT);
            }
            BigInteger started = U64.big(start.getStartedMonotonicNanos());
            BigInteger stopped = U64.big(end.getStoppedMonotonicNanos());
            BigInteger detached = U64.big(end.getDetachedMonotonicNanos());
            BigInteger drained = U64.big(end.getDrainCompletedMonotonicNanos());
            require(
                    started.equals(U64.big(end.getStartedMonotonicNanos()))
                            && started.compareTo(stopped) <= 0
                            && stopped.compareTo(detached) <= 0
                            && detached.compareTo(drained) <= 0,
                    "Invalid source shutdown times");
            CaptureProto.KernelCounters kernel = end.getKernelCounters();
            require(!completeEnd || kernel.getTargetNamespaceFailures() == 0, "Source target namespace mapping failed");
            CaptureProto.UserspaceCounters counters = end.getUserspaceCounters();
            require(counters.getWrittenObservations() == observations, "Source observation count mismatch");
            long received = counters.getReceivedObservations();
            long written = counters.getWrittenObservations();
            require(
                    completeEnd ? received == written : Long.compareUnsigned(received, written) >= 0,
                    "Source received/written discrepancy");
            require(
                    !completeEnd || counters.getWriteFailures() == 0 && counters.getPollFailures() == 0,
                    "Source writer/poller failed");
        }
        long jfrBytes = Files.size(jfr);
        String jfrHash = digest(jfr);
        require(jfrBytes == Files.size(jfr), "Inputs changed during analysis");
        boolean jfrVerified = false;
        Long apStoppedAt = null;
        if (footer != null) {
            CaptureProto.AnalysisInputs inputs = footer.getAnalysisInputs();
            CaptureProto.SourceArtifact sourceArtifact = inputs.getSourceArtifact();
            require(sourceArtifact.getRawBytes() == rawBytes, "Source byte count mismatch");
            require(sourceArtifact.getRawSha256().equals(hex(rawHash.digest())), "Source digest mismatch");
            CaptureProto.FileArtifact jfrArtifact = inputs.getJfrArtifact();
            String expectedHash = jfrArtifact.getSha256();
            require(expectedHash.matches("[0-9a-f]{64}"), "Invalid JFR digest");
            int sizeComparison = Long.compareUnsigned(jfrBytes, jfrArtifact.getBytes());
            boolean exactArtifact = sizeComparison == 0 && expectedHash.equals(jfrHash);
            if (exactArtifact) {
                jfrVerified = true;
            } else if (partialJfr) {
                diagnostics.addIncompleteReasons(
                        ReportProto.IncompleteCause.INCOMPLETE_CAUSE_SELECTED_JFR_DIFFERS_FROM_FINALIZED_ARTIFACT);
            } else if (partial && sizeComparison < 0) {
                diagnostics.addIncompleteReasons(
                        ReportProto.IncompleteCause.INCOMPLETE_CAUSE_JFR_SHORTER_THAN_FINALIZED_ARTIFACT);
            } else {
                require(sizeComparison == 0, "JFR byte count mismatch");
                require(expectedHash.equals(jfrHash), "JFR digest mismatch");
            }
            try {
                CaptureReceipt receipt = parseStopped(footer.getApStopResponse());
                apStoppedAt = receipt.stoppedAt();
                require(
                        receipt.finalized()
                                && receipt.sessionId().equals(session)
                                && receipt.epoch() == Integer.toUnsignedLong(start.getCaptureEpoch())
                                && receipt.signal() == start.getSignal()
                                && receipt.delivery() == delivery,
                        "AP finalization receipt mismatch");
                require(receipt.counters().equals(inputs.getApStats()), "AP receipt counter mismatch");
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid AP finalization receipt", e);
            }
        }
        diagnostics.setSourceParse(ReportProto.SourceParse.newBuilder()
                .setCleanEof(trailingBytes == 0)
                .setIgnoredTrailingBytes(trailingBytes)
                .setCaptureEndPresent(end != null)
                .setCaptureFinalizedPresent(footer != null));
        String sourceHash = hex(wholeHash.digest());
        diagnostics.setObservedArtifacts(ReportProto.ObservedArtifacts.newBuilder()
                .setSource(observedArtifact(sourceBytes, sourceHash))
                .setJfr(observedArtifact(jfrBytes, jfrHash)));
        diagnostics.setArtifactVerification(
                footer != null && jfrVerified
                        ? ReportProto.ArtifactVerification.ARTIFACT_VERIFICATION_VERIFIED_FINALIZED_INPUTS
                        : ReportProto.ArtifactVerification.ARTIFACT_VERIFICATION_UNVERIFIED_INCOMPLETE);
        ReportProto.FooterVerification footerVerification = footer != null
                ? ReportProto.FooterVerification.FOOTER_VERIFICATION_VERIFIED_FOOTER
                : ReportProto.FooterVerification.FOOTER_VERIFICATION_UNAVAILABLE;
        diagnostics.setClockVerification(footerVerification);
        diagnostics.setApStopVerification(footerVerification);
        diagnostics.setObservedSourceCapture(start);
        if (end != null) diagnostics.setObservedSourceEnd(end);
        if (footer != null) diagnostics.setObservedFinalization(footer);
        return new CaptureInput(start, end, footer, sourceHash, jfrHash, apStoppedAt, budget, partial, diagnostics);
    }

    private record CaptureReceipt(
            String sessionId,
            int signal,
            long epoch,
            CaptureProto.SignalDelivery delivery,
            boolean finalized,
            long stoppedAt,
            CaptureProto.AsyncProfilerStats counters) {}

    private static CaptureProto.SignalDelivery requireDelivery(String value) {
        return switch (value) {
            case "queued" -> CaptureProto.SignalDelivery.SIGNAL_DELIVERY_QUEUED;
            case "coalescing" -> CaptureProto.SignalDelivery.SIGNAL_DELIVERY_COALESCING;
            default -> throw new IllegalArgumentException("Invalid signal delivery policy: " + value);
        };
    }

    private static CaptureReceipt parseStopped(String response) {
        if (response == null || response.isEmpty() || response.indexOf('\r') >= 0 || response.contains("  ")) {
            throw new IllegalArgumentException("Invalid AP capture response");
        }
        String line = response.endsWith("\n") ? response.substring(0, response.length() - 1) : response;
        if (line.indexOf('\n') >= 0) throw new IllegalArgumentException("Invalid AP capture response");
        String[] tokens = line.split(" ");
        if (tokens.length < 3 || !tokens[0].equals("signal-capture-v1") || !tokens[1].equals("stopped")) {
            throw new IllegalArgumentException("Unexpected AP capture response");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 2; i < tokens.length; i++) {
            int equals = tokens[i].indexOf('=');
            if (equals <= 0
                    || equals == tokens[i].length() - 1
                    || values.putIfAbsent(tokens[i].substring(0, equals), tokens[i].substring(equals + 1)) != null) {
                throw new IllegalArgumentException("Malformed or duplicate AP capture field");
            }
        }
        String finalized = required(values, "finalized");
        if (!finalized.equals("true") && !finalized.equals("false")) {
            throw new IllegalArgumentException("Invalid AP finalized field");
        }
        long stoppedAt = unsignedDecimal(values, "stopped-at");
        CaptureProto.AsyncProfilerStats counters = CaptureProto.AsyncProfilerStats.newBuilder()
                .setAdmittedSignals(unsignedDecimal(values, "admitted"))
                .setInvalidSignalCode(unsignedDecimal(values, "invalid-code"))
                .setZeroCookie(unsignedDecimal(values, "zero-cookie"))
                .setZeroSequence(unsignedDecimal(values, "zero-sequence"))
                .setStaleEpoch(unsignedDecimal(values, "stale-epoch"))
                .setAcceptedCookies(unsignedDecimal(values, "accepted"))
                .setCaptureFailures(unsignedDecimal(values, "capture-failures"))
                .setSubmittedSamples(unsignedDecimal(values, "submitted"))
                .build();
        String reason = required(values, "reason");
        if (!reason.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid AP stop reason");
        }
        return new CaptureReceipt(
                required(values, "id"),
                boundedDecimal(values, "signal", 1, 64).intValueExact(),
                boundedDecimal(values, "epoch", 1, 0xffffffffL).longValueExact(),
                requireDelivery(required(values, "delivery")),
                Boolean.parseBoolean(finalized),
                stoppedAt,
                counters);
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) throw new IllegalArgumentException("Missing AP capture field: " + key);
        return value;
    }

    private static BigInteger boundedDecimal(Map<String, String> values, String key, long minimum, long maximum) {
        String value = required(values, key);
        if (!value.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Invalid AP capture field: " + key);
        BigInteger result = new BigInteger(value);
        if (result.compareTo(BigInteger.valueOf(minimum)) < 0 || result.compareTo(BigInteger.valueOf(maximum)) > 0) {
            throw new IllegalArgumentException("Out-of-range AP capture field: " + key);
        }
        return result;
    }

    /** An unsigned 64-bit decimal as its raw bits. */
    private static long unsignedDecimal(Map<String, String> values, String key) {
        String value = required(values, key);
        if (!value.matches("0|[1-9][0-9]*") || new BigInteger(value).compareTo(U64_MAX) > 0) {
            throw new IllegalArgumentException("Invalid AP capture field: " + key);
        }
        return Long.parseUnsignedLong(value);
    }

    private static ReportProto.ObservedArtifact observedArtifact(long bytes, String hash) {
        return ReportProto.ObservedArtifact.newBuilder()
                .setBytes(bytes)
                .setSha256(hash)
                .build();
    }

    void verifyUnchanged(Path source, Path jfr) throws IOException {
        require(sourceDigest.equals(digest(source)) && jfrDigest.equals(digest(jfr)), "Inputs changed during analysis");
    }

    /** A record's capture identity must be the one {@code capture_start} names. */
    static void identity(String sessionId, int captureEpoch, CaptureProto.CaptureStart start) throws IOException {
        require(
                sessionId.equals(start.getSessionId()) && captureEpoch == start.getCaptureEpoch(),
                "Capture identity mismatch");
    }

    /**
     * The {@code capture_start} fields an observation is validated against, checked as soon as the record is read
     * so the engine can hoist them into constants. Each of these checks also runs in its original place below; only
     * the point at which a malformed {@code capture_start} is reported moves earlier.
     */
    private static void requireStartFields(CaptureProto.CaptureStart start) throws IOException {
        require(start.getSourceId().equals(SOURCE_ID), "Unsupported source");
        require(start.hasSampling(), "Missing object: sampling");
        require(start.hasTimeSplit(), "Missing object: timeSplit");
        require(start.hasVerifiedIdentity(), "Missing object: verifiedIdentity");
        SamplingPolicy.parse(start.getSampling());
        TimeSplit.source(start.getTimeSplit());
        U64.requireSigned(start.getStartedMonotonicNanos(), "captureStart");
    }

    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    static String digest(Path file) throws IOException {
        MessageDigest hash = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = new byte[65536];
            int size;
            while ((size = input.read(bytes)) >= 0) hash.update(bytes, 0, size);
        }
        return hex(hash.digest());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Receives the capture's per-interval records while the stream is read once, so no observation or native
     * stack is retained as a decoded object. {@code start} is delivered as soon as {@code capture_start} has been
     * read and its own fields checked, because every constant an observation is validated against — the target
     * PID, the host TGID, the process generation, the registration token, the capture start time and the
     * sampling policy — is in that record. The footer copies of those values are required to be identical further
     * down, so validating an observation against {@code capture_start} is the same test.
     */
    interface SourceVisitor {
        /** Called once, before the first record, with the budget and stack-id set this read will fill. */
        void reading(Budget budget, LongIntMap announcedStacks) throws IOException;

        void start(CaptureProto.CaptureStart captureStart) throws IOException;

        void stack(long stackId, CaptureProto.Stack stack) throws IOException;

        void observation(int rowNumber, CaptureProto.Observation observation) throws IOException;
    }

    /**
     * Admission accounting for the streaming correlator: a row counter over both inputs, the conservative
     * decoded size of the few control records that are retained, and the live size of the primitive structures
     * the engine holds. It charges what is actually retained — control records, columns, cookie index, interned
     * stacks — which is what the degradation ladder needs to steer on, not the input size.
     */
    static final class Budget {
        private final OfflineCorrelator.Limits limits;
        private long documents;
        private long structures;
        private long peak;
        private int rows;

        Budget(OfflineCorrelator.Limits limits) {
            this.limits = limits;
        }

        /** Counts one decoded record from either input against the total row limit. */
        void countRow() throws IOException {
            require(++rows <= limits.maxRows(), "Input row limit exceeded");
        }

        /** Charges a control record that is retained for the whole run: its decoded objects, conservatively. */
        void charge(Message value) throws IOException {
            documents = Math.addExact(documents, 256L + 8L * value.getSerializedSize());
            enforce();
        }

        /** Replaces the live size of the engine's primitive structures. */
        void structures(long bytes) throws IOException {
            structures = bytes;
            enforce();
        }

        long retained() {
            return documents + structures;
        }

        long peak() {
            return peak;
        }

        private void enforce() throws IOException {
            long retained = Math.addExact(documents, structures);
            peak = Math.max(peak, retained);
            if (retained > limits.maxRetainedBytes()) {
                throw new RetentionLimitExceeded(retained, limits.maxRetainedBytes(), null);
            }
        }
    }

    /** Every capture-identity row of the JFR must name this capture: the same session and epoch. */
    static void jfrIdentity(String sessionId, long captureEpoch, CaptureInput capture) throws IOException {
        require(
                sessionId.equals(capture.sessionId()) && captureEpoch == capture.captureEpoch(),
                "Capture identity mismatch");
    }
}
