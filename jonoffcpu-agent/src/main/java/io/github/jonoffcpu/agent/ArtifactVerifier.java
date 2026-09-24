// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.agent;

import com.google.protobuf.Descriptors.FieldDescriptor;
import io.github.jonoffcpu.capture.CaptureFormat;
import io.github.jonoffcpu.capture.CaptureProto.AsyncProfilerStats;
import io.github.jonoffcpu.capture.CaptureProto.CaptureEnd;
import io.github.jonoffcpu.capture.CaptureProto.CaptureFinalized;
import io.github.jonoffcpu.capture.CaptureProto.CaptureStart;
import io.github.jonoffcpu.capture.CaptureProto.CaptureState;
import io.github.jonoffcpu.capture.CaptureProto.FileArtifact;
import io.github.jonoffcpu.capture.CaptureProto.KernelCounters;
import io.github.jonoffcpu.capture.CaptureProto.Observation;
import io.github.jonoffcpu.capture.CaptureProto.OffCpuReason;
import io.github.jonoffcpu.capture.CaptureProto.Record;
import io.github.jonoffcpu.capture.CaptureProto.Sampling;
import io.github.jonoffcpu.capture.CaptureProto.SignalDelivery;
import io.github.jonoffcpu.capture.CaptureProto.SourceArtifact;
import io.github.jonoffcpu.capture.CaptureProto.Stack;
import io.github.jonoffcpu.capture.CaptureProto.TimeSplit;
import io.github.jonoffcpu.capture.CaptureProto.TimeSplitSource;
import io.github.jonoffcpu.capture.CaptureProto.UserspaceCounters;
import io.github.jonoffcpu.capture.CaptureProto.VerifiedIdentity;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * Verifies what the collector and async-profiler produced before the agent vouches for it: the capture stream,
 * record by record, against the identity and policy the controller negotiated, and the JFR's signal-capture events
 * against async-profiler's stop receipt. Then it appends the finalization footer, covering the verified prefix.
 */
final class ArtifactVerifier {
    private static final int MAX_RECORD_BYTES = 1024 * 1024;
    /** The largest footer the agent appends; it holds only identities, counters and artifact digests. */
    static final int MAX_FOOTER_BYTES = 64 * 1024;

    static final String SOURCE_ID = "jonoffcpu.offcpu.v1";

    private static final int MAX_STACK_FRAMES = 4096;

    record JfrResult(long processId, long processStartTimeMillis) {}

    /** What the stream must agree with: the negotiated capture identity, the resolved policy and the prepared ids. */
    record Expected(
            String sessionId,
            long epoch,
            int signal,
            SignalDelivery delivery,
            Sampling sampling,
            TimeSplit timeSplit,
            int hostTgid,
            int targetPid,
            VerifiedIdentity verifiedIdentity) {}

    private ArtifactVerifier() {}

    /**
     * Reads the collector's closed stream and returns its terminal record, which must equal the one the collector
     * reported when it stopped.
     */
    static CaptureEnd verifySource(Path source, CaptureEnd expectedEnd, Expected expected) throws IOException {
        CaptureStart start = null;
        CaptureEnd end = null;
        long observations = 0;
        Set<Long> announcedStacks = new HashSet<>();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            CaptureFormat.readHeader(input);
            CaptureFormat.Framed framed;
            while ((framed = CaptureFormat.next(input, MAX_RECORD_BYTES)) != null) {
                if (framed.truncated()) throw new IOException("Source stream ends inside a record");
                Record record = framed.record();
                if (end != null) {
                    throw new IOException("Source records follow captureEnd");
                }
                switch (record.getRecordCase()) {
                    case CAPTURE_START -> {
                        if (start != null || observations != 0)
                            throw new IOException("Duplicate/out-of-order captureStart");
                        start = record.getCaptureStart();
                        verifyStart(start, expected);
                    }
                    case STACK -> {
                        if (start == null) throw new IOException("Stack before captureStart");
                        Stack stack = record.getStack();
                        if (stack.getId() < 0 || stack.getId() > Integer.MAX_VALUE) {
                            throw new IOException("Invalid stack id: " + stack.getId());
                        }
                        if (!announcedStacks.add(stack.getId())) {
                            throw new IOException("Duplicate stack record: " + stack.getId());
                        }
                        if (stack.getFrameCount() > MAX_STACK_FRAMES) {
                            throw new IOException("Stack record exceeds the frame limit: " + stack.getId());
                        }
                    }
                    case OBSERVATION -> {
                        if (start == null) throw new IOException("Observation before captureStart");
                        validateObservation(record.getObservation(), expected, announcedStacks);
                        observations++;
                    }
                    case CAPTURE_END -> {
                        if (start == null) throw new IOException("captureEnd before captureStart");
                        end = record.getCaptureEnd();
                        requireIdentity(end.getSessionId(), end.getCaptureEpoch(), expected);
                        if (end.getState() != CaptureState.CAPTURE_STATE_COMPLETE) {
                            throw new IOException("Source end state is not complete: " + end.getState());
                        }
                    }
                    case CAPTURE_FINALIZED -> throw new IOException("Source stream is already finalized");
                    default -> throw new IOException("Unknown source record");
                }
            }
        }
        if (start == null || end == null) throw new IOException("Incomplete source stream");
        if (!end.equals(expectedEnd)) throw new IOException("Source captureEnd differs from native stop response");
        KernelCounters kernel = end.getKernelCounters();
        if (kernel.getTargetNamespaceFailures() != 0) {
            throw new IOException("Source target namespace mapping failed");
        }
        UserspaceCounters userspace = end.getUserspaceCounters();
        if (userspace.getWrittenObservations() != observations) {
            throw new IOException("Source written observation count mismatch");
        }
        if (userspace.getReceivedObservations() != userspace.getWrittenObservations()) {
            throw new IOException("Source received/written observation count mismatch");
        }
        // Missing native stacks remain explicit observations (symbolizationFailures); they do not mean the source
        // file was lost.
        if (userspace.getWriteFailures() != 0) throw new IOException("Source writeFailures is nonzero");
        if (userspace.getPollFailures() != 0) throw new IOException("Source pollFailures is nonzero");
        if (userspace.getDrainTimedOut() != 0) throw new IOException("Source drainTimedOut is nonzero");
        if (end.getDrainTimedOut()) throw new IOException("Source drain timed out");
        if (Long.compareUnsigned(end.getStartedMonotonicNanos(), end.getStoppedMonotonicNanos()) > 0
                || Long.compareUnsigned(end.getStoppedMonotonicNanos(), end.getDetachedMonotonicNanos()) > 0
                || Long.compareUnsigned(end.getDetachedMonotonicNanos(), end.getDrainCompletedMonotonicNanos()) > 0) {
            throw new IOException("Source lifecycle timestamps are out of order");
        }
        return end;
    }

    private static void verifyStart(CaptureStart start, Expected expected) throws IOException {
        requireIdentity(start.getSessionId(), start.getCaptureEpoch(), expected);
        requireEqual("sourceId", SOURCE_ID, start.getSourceId());
        requireEqual("signal", expected.signal(), start.getSignal());
        requireEqual("signalDelivery", expected.delivery(), start.getSignalDelivery());
        requireEqual("sampling", expected.sampling(), start.getSampling());
        requireEqual("timeSplit", expected.timeSplit(), start.getTimeSplit());
        requireEqual("hostTgid", expected.hostTgid(), start.getHostTgid());
        requireEqual("targetPid", expected.targetPid(), start.getTargetPid());
        requireEqual("verifiedIdentity", expected.verifiedIdentity(), start.getVerifiedIdentity());
    }

    static JfrResult verifyJfr(
            Path jfr,
            String sessionId,
            long epoch,
            int signal,
            SignalDelivery delivery,
            AsyncProfilerStats expectedCounters)
            throws IOException {
        JfrValidation validation = new JfrValidation(sessionId, epoch, signal, delivery, expectedCounters);
        try (RecordingFile recording = new RecordingFile(jfr)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                if (event.getEventType().getName().startsWith("profiler.Signal")) {
                    validation.accept(event);
                }
            }
        }
        return validation.result();
    }

    /** Confirms the recording parses with the public JDK reader; profiler-only captures carry no jonoffcpu events. */
    static void requireReadableJfr(Path jfr) throws IOException {
        try (RecordingFile recording = new RecordingFile(jfr)) {
            while (recording.hasMoreEvents()) {
                recording.readEvent();
            }
        }
    }

    /** Creates a correlation stream that holds only a finalization footer, for captures without an eBPF source. */
    static void writeFooterOnly(Path source, CaptureFinalized footer) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(CaptureFormat.header());
        stream.write(footerRecord(footer));
        try (FileChannel file = FileChannel.open(source, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(stream.toByteArray());
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
        }
    }

    static FileArtifact artifact(Path directory, Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Artifact is not a regular file: " + path);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        long size = 0;
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
                size += count;
            }
        }
        if (size != Files.size(path)) throw new IOException("Artifact changed while it was digested: " + path);
        return FileArtifact.newBuilder()
                .setPath(directory.relativize(path).toString())
                .setBytes(size)
                .setSha256(hex(digest.digest()))
                .build();
    }

    static SourceArtifact rawArtifact(Path directory, Path path) throws IOException {
        FileArtifact artifact = artifact(directory, path);
        return SourceArtifact.newBuilder()
                .setPath(artifact.getPath())
                .setRawBytes(artifact.getBytes())
                .setRawSha256(artifact.getSha256())
                .build();
    }

    /** Appends the footer to a stream whose prefix must still be exactly the one the footer's digest covers. */
    static void appendFinalized(Path source, SourceArtifact raw, CaptureFinalized footer) throws IOException {
        SourceArtifact current = rawArtifact(source.getParent(), source);
        if (raw.getRawBytes() != current.getRawBytes() || !raw.getRawSha256().equals(current.getRawSha256())) {
            throw new IOException("Correlation file changed after native finalization validation");
        }
        byte[] bytes = footerRecord(footer);
        long expected = raw.getRawBytes();
        try (FileChannel file = FileChannel.open(source, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            if (file.size() != expected || file.position() != expected) {
                throw new IOException("Correlation file changed before finalization footer append");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
            if (file.size() != Math.addExact(expected, bytes.length)) {
                throw new IOException("Correlation footer append size mismatch");
            }
        }
    }

    /** The footer as one length-delimited record. */
    private static byte[] footerRecord(CaptureFinalized footer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CaptureFormat.write(
                bytes, Record.newBuilder().setCaptureFinalized(footer).build());
        if (bytes.size() > MAX_FOOTER_BYTES) throw new IOException("captureFinalized footer exceeds 64 KiB");
        return bytes.toByteArray();
    }

    private static void requireIdentity(String sessionId, int epoch, Expected expected) {
        requireEqual("sessionId", expected.sessionId(), sessionId);
        requireEqual("captureEpoch", expected.epoch(), Integer.toUnsignedLong(epoch));
    }

    static void requireEqual(String field, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(field + " mismatch: expected " + expected + ", got " + actual);
        }
    }

    private static void validateObservation(Observation row, Expected expected, Set<Long> announcedStacks)
            throws IOException {
        // Session, epoch and source id are in captureStart: a record cannot disagree with them.
        requireEqual("observation hostTgid", expected.hostTgid(), row.getHostTgid());
        if (row.getHostTid() == 0) throw new IOException("Observation has no host thread id");
        requireEqual("observation targetTgid", expected.targetPid(), row.getTargetTgid());
        if (row.getTargetTid() == 0) throw new IOException("Observation has no target thread id");
        long cookie = row.getCorrelationId();
        if ((cookie >>> 32) != expected.epoch() || (cookie & 0xffffffffL) == 0) {
            throw new IOException("Observation cookie does not belong to capture epoch");
        }
        requireEqual(
                "observation processGenerationNanos",
                expected.verifiedIdentity().getProcessGenerationNanos(),
                row.getProcessGenerationNanos());
        requireEqual(
                "observation registrationToken",
                expected.verifiedIdentity().getRegistrationToken(),
                row.getRegistrationToken());
        long start = row.getStartMonotonicNanos();
        long end = row.getEndMonotonicNanos();
        if (Long.compareUnsigned(start, end) > 0) throw new IOException("Observation has negative duration");
        // The kernel records the exact threshold it drew against; recompute it from the policy and duration.
        long threshold = row.getAdmissionThreshold();
        if (threshold < 1 || threshold > SamplingConfig.CERTAIN_ADMISSION) {
            throw new IOException("Observation admissionThreshold out of range: " + threshold);
        }
        requireEqual(
                "observation admissionThreshold",
                SamplingConfig.admissionThreshold(expected.sampling(), end - start),
                threshold);
        // The kernel derives the reason from the two raw sched_switch arguments it records next to it, and only
        // selected reasons pass its filter; recompute both.
        OffCpuReason reason = row.getReason();
        requireEqual("observation reason", SamplingConfig.classify(row.getPreempted(), row.getPrevTaskState()), reason);
        if (!expected.sampling().getReasonsList().contains(reason)) {
            throw new IOException("Observation reason was not selected by sampling.reasons: " + reason);
        }
        // The run-queue part is the raw growth of the scheduler's run delay; the consumers apply the split rule, so
        // only its presence is checked here: never without the source.
        if (row.hasRunqueueNanos() && expected.timeSplit().getSource() == TimeSplitSource.TIME_SPLIT_SOURCE_OFF) {
            throw new IOException("Observation carries a run-queue part although timeSplit.source is off");
        }
        // A stack is either announced by an earlier record or explained by an error on this row.
        checkStack("kernelStack", row.getKernelStackId(), row.getKernelStackError(), announcedStacks);
        checkStack("userStack", row.getUserStackId(), row.getUserStackError(), announcedStacks);
    }

    private static void checkStack(String stack, long stackId, String error, Set<Long> announcedStacks)
            throws IOException {
        if (stackId < Integer.MIN_VALUE || stackId > Integer.MAX_VALUE) {
            throw new IOException("Invalid " + stack + " id: " + stackId);
        }
        if (!error.isEmpty()) {
            // A stack the kernel or the map lookup could not produce has no record of its own.
            return;
        }
        if (stackId < 0) {
            throw new IOException("Unexplained negative stack id for " + stack + ": " + stackId);
        }
        if (!announcedStacks.contains(stackId)) {
            throw new IOException("Observation references an unannounced " + stack + ": " + stackId);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private record JfrContext(long processId, long processStartTimeMillis) {}

    private static final class JfrValidation {
        private final String sessionId;
        private final long epoch;
        private final int signal;
        private final SignalDelivery delivery;
        private final AsyncProfilerStats expectedCounters;
        private JfrContext context;
        private boolean countersSeen;

        private JfrValidation(
                String sessionId,
                long epoch,
                int signal,
                SignalDelivery delivery,
                AsyncProfilerStats expectedCounters) {
            this.sessionId = sessionId;
            this.epoch = epoch;
            this.signal = signal;
            this.delivery = delivery;
            this.expectedCounters = expectedCounters;
        }

        private void accept(RecordedEvent event) throws IOException {
            String type = event.getEventType().getName();
            try {
                switch (type) {
                    case "profiler.SignalCapture" -> acceptCapture(event);
                    case "profiler.SignalSample" -> acceptSample(event);
                    case "profiler.SignalCaptureStats" -> acceptStats(event);
                    default -> throw new IOException("Unsupported signal event type: " + type);
                }
            } catch (IllegalArgumentException | ClassCastException error) {
                throw new IOException("Invalid " + type + " event", error);
            }
        }

        private void acceptCapture(RecordedEvent event) throws IOException {
            requireIdentity(event);
            if (event.getInt("signal") != signal) {
                throw new IOException("JFR signal does not match the configured signal");
            }
            if (!CaptureProtocol.deliveryName(delivery).equals(event.getString("signalDelivery"))) {
                throw new IOException("JFR signal delivery does not match the configured policy");
            }
            long processId = event.getLong("processId");
            long processStartTimeMillis = event.getLong("processStartTimeMillis");
            if (processId < 1 || processStartTimeMillis < 0) {
                throw new IOException("Invalid JFR process identity");
            }
            JfrContext found = new JfrContext(processId, processStartTimeMillis);
            if (context != null && !context.equals(found)) {
                throw new IOException("Conflicting JFR context");
            }
            context = found;
        }

        private void acceptSample(RecordedEvent event) throws IOException {
            if (context == null) {
                throw new IOException("Signal sample precedes JFR capture context");
            }
            long cookie = event.getLong("correlationId");
            if (cookie >>> 32 != epoch || (cookie & 0xffffffffL) == 0) {
                throw new IOException("Signal sample cookie does not belong to capture epoch");
            }
        }

        /** The stats event names its fields as the manifest's counters do, so each is compared by that name. */
        private void acceptStats(RecordedEvent event) throws IOException {
            if (context == null) {
                throw new IOException("Signal capture stats precede JFR capture context");
            }
            if (countersSeen) {
                throw new IOException("Duplicate JFR stats");
            }
            requireIdentity(event);
            for (FieldDescriptor field : AsyncProfilerStats.getDescriptor().getFields()) {
                long expected = (Long) expectedCounters.getField(field);
                if (event.getLong(field.getJsonName()) != expected) {
                    throw new IOException("JFR/AP counter mismatch: " + field.getJsonName());
                }
            }
            countersSeen = true;
        }

        private void requireIdentity(RecordedEvent event) throws IOException {
            if (event.getInt("schemaVersion") != 1
                    || !sessionId.equals(event.getString("sessionId"))
                    || epoch != event.getLong("captureEpoch")) {
                throw new IOException("Conflicting signal capture identity or unsupported schema");
            }
        }

        private JfrResult result() throws IOException {
            if (context == null || !countersSeen) {
                throw new IOException("Incomplete JFR validation stream");
            }
            return new JfrResult(context.processId(), context.processStartTimeMillis());
        }
    }
}
