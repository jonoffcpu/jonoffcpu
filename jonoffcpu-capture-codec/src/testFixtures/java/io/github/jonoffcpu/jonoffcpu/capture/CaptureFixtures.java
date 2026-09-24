// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.capture;

import com.google.protobuf.Timestamp;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Valid capture records for fixtures: a consistent {@code capture_start}, {@code capture_end} and {@code
 * capture_finalized} around whatever stacks and observations a test needs, so a test states only what it changes.
 * Every default is one a real capture could hold; the footer's digests are computed from the bytes they cover.
 */
public final class CaptureFixtures {
    public static final String SOURCE_ID = "jonoffcpu.offcpu.v1";
    public static final int SIGNAL = 35;
    public static final int HOST_TGID = 123;
    public static final long STARTED_MONOTONIC_NANOS = 500;
    public static final long STOPPED_MONOTONIC_NANOS = 6000;
    public static final long DETACHED_MONOTONIC_NANOS = 7000;
    public static final long DRAIN_COMPLETED_MONOTONIC_NANOS = 8000;
    /** async-profiler's {@code stopped-at} in the default stop reply. */
    public static final long AP_STOPPED_AT_NANOS = 9000;
    /** {@code 0.01} as the collector rounds it: {@code ceil(0.01 * 2^32)}. */
    public static final long ONE_PERCENT_THRESHOLD = 42949673;

    private CaptureFixtures() {}

    /** One percent uniform admission of blocked intervals, without duration bounds. */
    public static CaptureProto.Sampling uniformSampling() {
        return uniformSampling("0.01", ONE_PERCENT_THRESHOLD, CaptureProto.OffCpuReason.OFF_CPU_REASON_BLOCKED);
    }

    public static CaptureProto.Sampling uniformSampling(
            String probability, long threshold, CaptureProto.OffCpuReason... reasons) {
        return CaptureProto.Sampling.newBuilder()
                .addAllReasons(List.of(reasons))
                .setUniform(CaptureProto.UniformAdmission.newBuilder()
                        .setProbability(probability)
                        .setProbabilityThreshold(threshold))
                .build();
    }

    public static CaptureProto.Sampling proportionalSampling(
            long recordAllAboveMicros, CaptureProto.OffCpuReason... reasons) {
        return CaptureProto.Sampling.newBuilder()
                .addAllReasons(List.of(reasons))
                .setProportional(
                        CaptureProto.ProportionalAdmission.newBuilder().setRecordAllAboveMicros(recordAllAboveMicros))
                .build();
    }

    public static CaptureProto.TimeSplit timeSplit(CaptureProto.TimeSplitSource source) {
        return CaptureProto.TimeSplit.newBuilder().setSource(source).build();
    }

    /** A verified target in its own PID namespace that shares the collector's clock. */
    public static CaptureProto.VerifiedIdentity.Builder identity() {
        return CaptureProto.VerifiedIdentity.newBuilder()
                .setRegistrationToken(1)
                .setProcessGenerationNanos(100)
                .setPidNamespaceDevice(4)
                .setPidNamespaceInode(43)
                .setTimeNamespaceInode(42)
                .setClockVerified(true)
                .setMonotonicOffsetNanos(0);
    }

    /** A capture of one percent of blocked intervals, without the sleeping/run-queue split. */
    public static CaptureProto.CaptureStart.Builder captureStart(String sessionId, int captureEpoch, int targetPid) {
        return CaptureProto.CaptureStart.newBuilder()
                .setSourceId(SOURCE_ID)
                .setSessionId(sessionId)
                .setCaptureEpoch(captureEpoch)
                .setSignal(SIGNAL)
                .setSignalDelivery(CaptureProto.SignalDelivery.SIGNAL_DELIVERY_QUEUED)
                .setHostTgid(HOST_TGID)
                .setTargetPid(targetPid)
                .setVerifiedIdentity(identity())
                .setStartedMonotonicNanos(STARTED_MONOTONIC_NANOS)
                .setSampling(uniformSampling())
                .setTimeSplit(timeSplit(CaptureProto.TimeSplitSource.TIME_SPLIT_SOURCE_OFF))
                .setLoader("libbpf-rs")
                .setHook("tp_btf/sched_switch")
                .setSwitchOutHook("tp_btf/sched_switch");
    }

    /** A complete end whose counters account for exactly {@code observations} selected and written intervals. */
    public static CaptureProto.CaptureEnd.Builder captureEnd(
            CaptureProto.CaptureStartOrBuilder start, long observations) {
        return CaptureProto.CaptureEnd.newBuilder()
                .setSessionId(start.getSessionId())
                .setCaptureEpoch(start.getCaptureEpoch())
                .setState(CaptureProto.CaptureState.CAPTURE_STATE_COMPLETE)
                .setStartedMonotonicNanos(start.getStartedMonotonicNanos())
                .setStoppedMonotonicNanos(STOPPED_MONOTONIC_NANOS)
                .setDetachedMonotonicNanos(DETACHED_MONOTONIC_NANOS)
                .setDrainCompletedMonotonicNanos(DRAIN_COMPLETED_MONOTONIC_NANOS)
                .setKernelCounters(CaptureProto.KernelCounters.newBuilder()
                        .setEligibleIntervals(observations)
                        .setSelectedIntervals(observations))
                .setUserspaceCounters(CaptureProto.UserspaceCounters.newBuilder()
                        .setReceivedObservations(observations)
                        .setWrittenObservations(observations));
    }

    /** async-profiler's counters when every one of {@code samples} signals was accepted and submitted. */
    public static CaptureProto.AsyncProfilerStats apStats(long samples) {
        return CaptureProto.AsyncProfilerStats.newBuilder()
                .setAdmittedSignals(samples)
                .setAcceptedCookies(samples)
                .setSubmittedSamples(samples)
                .build();
    }

    /** async-profiler's stop reply for this capture, as the agent records it verbatim. */
    public static String apStopResponse(
            CaptureProto.CaptureStartOrBuilder start, long stoppedAtNanos, CaptureProto.AsyncProfilerStats stats) {
        String delivery = start.getSignalDelivery() == CaptureProto.SignalDelivery.SIGNAL_DELIVERY_COALESCING
                ? "coalescing"
                : "queued";
        return "signal-capture-v1 stopped id=" + start.getSessionId()
                + " delivery=" + delivery
                + " signal=" + start.getSignal()
                + " epoch=" + Integer.toUnsignedString(start.getCaptureEpoch())
                + " finalized=true stopped-at=" + Long.toUnsignedString(stoppedAtNanos)
                + " reason=explicit"
                + " admitted=" + Long.toUnsignedString(stats.getAdmittedSignals())
                + " invalid-code=" + Long.toUnsignedString(stats.getInvalidSignalCode())
                + " zero-cookie=" + Long.toUnsignedString(stats.getZeroCookie())
                + " zero-sequence=" + Long.toUnsignedString(stats.getZeroSequence())
                + " stale-epoch=" + Long.toUnsignedString(stats.getStaleEpoch())
                + " accepted=" + Long.toUnsignedString(stats.getAcceptedCookies())
                + " capture-failures=" + Long.toUnsignedString(stats.getCaptureFailures())
                + " submitted=" + Long.toUnsignedString(stats.getSubmittedSamples())
                + "\n";
    }

    /**
     * The footer the agent appends: the start's identity and configuration, the digest of {@code prefix} (the header
     * and every record before the footer), the JFR's size and digest, and async-profiler's counters and stop reply.
     * Stored paths are advisory, so they name locations that do not exist.
     */
    public static CaptureProto.CaptureFinalized.Builder captureFinalized(
            CaptureProto.CaptureStartOrBuilder start, byte[] prefix, Path jfr, CaptureProto.AsyncProfilerStats stats)
            throws IOException {
        return CaptureProto.CaptureFinalized.newBuilder()
                .setSessionId(start.getSessionId())
                .setCaptureEpoch(start.getCaptureEpoch())
                .setState(CaptureProto.FinalizedState.FINALIZED_STATE_COMPLETE)
                .setAnalysisInputs(CaptureProto.AnalysisInputs.newBuilder()
                        .setSessionId(start.getSessionId())
                        .setCaptureEpoch(start.getCaptureEpoch())
                        .setSignal(start.getSignal())
                        .setSignalDelivery(start.getSignalDelivery())
                        .setHostTgid(start.getHostTgid())
                        .setTargetPid(start.getTargetPid())
                        .setSampling(start.getSampling())
                        .setTimeSplit(start.getTimeSplit())
                        .setVerifiedIdentity(start.getVerifiedIdentity())
                        .setApStats(stats)
                        .setSourceArtifact(sourceArtifact(prefix))
                        .setJfrArtifact(CaptureProto.FileArtifact.newBuilder()
                                .setPath("an-unrelated-old-jfr-location")
                                .setBytes(Files.size(jfr))
                                .setSha256(sha256(jfr))))
                .setApStopResponse(apStopResponse(start, AP_STOPPED_AT_NANOS, stats))
                .setFinalizedAt(Timestamp.newBuilder().setSeconds(1_790_000_000L));
    }

    /** The footer's description of the stream prefix it covers. */
    public static CaptureProto.SourceArtifact sourceArtifact(byte[] prefix) {
        return CaptureProto.SourceArtifact.newBuilder()
                .setPath("an-unrelated-old-location")
                .setRawBytes(prefix.length)
                .setRawSha256(sha256(prefix))
                .build();
    }

    /**
     * A finalized stream: {@code records}, which start with {@code capture_start} and end with {@code capture_end},
     * then the footer covering them.
     */
    public static byte[] finalizedStream(
            List<CaptureProto.Record> records, Path jfr, CaptureProto.AsyncProfilerStats stats) throws IOException {
        byte[] prefix = CaptureRecordFixture.encode(records);
        List<CaptureProto.Record> all = new ArrayList<>(records);
        all.add(record(captureFinalized(records.get(0).getCaptureStart(), prefix, jfr, stats)
                .build()));
        return CaptureRecordFixture.encode(all);
    }

    /**
     * Re-seals a stream whose last record is its footer after a test changed a record before it: the footer's source
     * digest is recomputed, so validation cannot pass merely by rejecting a stale hash.
     */
    public static byte[] refinalize(List<CaptureProto.Record> records) throws IOException {
        int last = records.size() - 1;
        byte[] prefix = CaptureRecordFixture.encode(records.subList(0, last));
        CaptureProto.CaptureFinalized.Builder footer = records.get(last).getCaptureFinalized().toBuilder();
        footer.getAnalysisInputsBuilder().setSourceArtifact(sourceArtifact(prefix));
        List<CaptureProto.Record> all = new ArrayList<>(records.subList(0, last));
        all.add(record(footer.build()));
        return CaptureRecordFixture.encode(all);
    }

    public static CaptureProto.Record record(CaptureProto.CaptureStart start) {
        return CaptureProto.Record.newBuilder().setCaptureStart(start).build();
    }

    public static CaptureProto.Record record(CaptureProto.Stack stack) {
        return CaptureProto.Record.newBuilder().setStack(stack).build();
    }

    public static CaptureProto.Record record(CaptureProto.Observation observation) {
        return CaptureProto.Record.newBuilder().setObservation(observation).build();
    }

    public static CaptureProto.Record record(CaptureProto.CaptureEnd end) {
        return CaptureProto.Record.newBuilder().setCaptureEnd(end).build();
    }

    public static CaptureProto.Record record(CaptureProto.CaptureFinalized footer) {
        return CaptureProto.Record.newBuilder().setCaptureFinalized(footer).build();
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
