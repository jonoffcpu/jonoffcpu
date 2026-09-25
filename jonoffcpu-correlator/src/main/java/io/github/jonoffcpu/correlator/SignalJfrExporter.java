// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import com.google.protobuf.Timestamp;
import io.github.jonoffcpu.capture.CaptureProto;
import io.github.jonoffcpu.codec.ProtoJson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Streams generic async-profiler signal events through the public JFR reader as typed {@code jonoffcpu-signals.proto}
 * records.
 *
 * <p>This offline boundary lets the correlator use JFR's thread and stack dictionaries without implementing a JFR
 * parser or relying on JDK internals. Cookies keep all 64 bits and frames remain in JFR's leaf-first order. The
 * consumer must see the final {@code end} record before publishing derived artifacts: an interrupted or invalid
 * export is not a valid zero-sample capture. The public API uses JDK types only: {@link #export} prints the records as
 * JSON Lines.
 */
public final class SignalJfrExporter {
    private SignalJfrExporter() {}

    /** Receives a resolved record while its recording is read. */
    @FunctionalInterface
    interface RowConsumer {
        void accept(SignalProto.SignalRecord row) throws IOException;
    }

    /**
     * Prints one finalized, single-session capture's signal records as JSON Lines, one {@code SignalRecord} per line.
     * Throws on conflicting context or missing terminal stats. This method does not close the caller's writer.
     */
    public static void export(Path input, Writer output) throws IOException {
        visit(input, row -> {
            output.write(ProtoJson.line(row));
            output.write('\n');
        });
        output.flush();
    }

    /**
     * Read resolved signal records directly. Ordinary CPU, allocation and JVM events are left in the input and
     * ignored here. The end record means parsing finished, not that external source integrity or the independently
     * retained stop receipt has been verified.
     */
    static void visit(Path input, RowConsumer output) throws IOException {
        decode(input, output, false, null);
    }

    /** Options for selecting signal samples from a complete or deliberately shortened JFR. */
    record ReadOptions(
            String expectedSessionId,
            long expectedCaptureEpoch,
            Instant from,
            Instant to,
            boolean allowMissingMetadata) {
        ReadOptions {
            if (expectedSessionId == null || expectedCaptureEpoch < 1 || expectedCaptureEpoch > 0xffffffffL) {
                throw new IllegalArgumentException("Expected capture identity is invalid");
            }
            if ((from == null) != (to == null) || from != null && !from.isBefore(to)) {
                throw new IllegalArgumentException("JFR event time range must be nonempty or omitted");
            }
        }

        boolean includes(Instant timestamp) {
            return from == null || !timestamp.isBefore(from) && timestamp.isBefore(to);
        }
    }

    /** Parser evidence only; missing terminal witnesses never imply a complete capture. */
    record PrefixOutcome(
            boolean cleanEof,
            boolean contextPresent,
            boolean terminalStatsPresent,
            long captures,
            long samples,
            String readFailure) {}

    /**
     * Explicit diagnostic prefix reader. Only low-level RecordingFile read failures and missing terminal witnesses
     * are recoverable. Invalid schemas/identities and consumer failures remain hard errors. No successful end row is
     * emitted by this method.
     */
    static PrefixOutcome visitPrefix(Path input, RowConsumer output) throws IOException {
        return decode(input, output, true, null);
    }

    /**
     * Reads selected samples. Missing capture-context and terminal-stat events are accepted only when {@link
     * ReadOptions#allowMissingMetadata()} is true; sample cookies are still checked against the expected capture
     * epoch supplied by the finalized correlation stream.
     */
    static PrefixOutcome visitSelected(Path input, RowConsumer output, ReadOptions options) throws IOException {
        return decode(input, output, false, options);
    }

    private static PrefixOutcome decode(Path input, RowConsumer output, boolean partial, ReadOptions options)
            throws IOException {
        Context context = null;
        Context expected = options == null
                ? null
                : new Context(
                        options.expectedSessionId(),
                        options.expectedCaptureEpoch(),
                        0,
                        CaptureProto.SignalDelivery.SIGNAL_DELIVERY_UNSPECIFIED,
                        0,
                        0);
        long captures = 0;
        long samples = 0;
        long stats = 0;
        String readFailure = null;
        RecordingFile opened;
        try {
            opened = new RecordingFile(input);
        } catch (IOException error) {
            if (!partial) throw error;
            return new PrefixOutcome(false, false, false, 0, 0, boundedMessage(error));
        }
        JfrSnapshot snapshot = new JfrSnapshot();
        try (RecordingFile recording = opened) {
            while (true) {
                RecordedEvent event;
                try {
                    if (!recording.hasMoreEvents()) break;
                    event = recording.readEvent();
                } catch (IOException error) {
                    if (!partial) throw error;
                    readFailure = boundedMessage(error);
                    break;
                }
                String type = event.getEventType().getName();
                if (type.startsWith("jdk.")) snapshot.accept(event);
                try {
                    if (type.equals("profiler.SignalCapture")) {
                        Context found = Context.read(event);
                        if (expected != null
                                && (!expected.sessionId().equals(found.sessionId())
                                        || expected.captureEpoch() != found.captureEpoch())) {
                            throw new IOException("JFR capture context differs from expected capture identity");
                        }
                        if (context != null && !context.equals(found)) {
                            throw new IOException("Conflicting signal capture context");
                        }
                        context = found;
                        output.accept(SignalProto.SignalRecord.newBuilder()
                                .setCapture(SignalProto.SignalCapture.newBuilder()
                                        .setSessionId(context.sessionId())
                                        .setCaptureEpoch((int) context.captureEpoch())
                                        .setStartTime(timestamp(event.getStartTime()))
                                        .setSignal(context.signal())
                                        .setSignalDelivery(context.signalDelivery())
                                        .setProcessId(context.processId())
                                        .setProcessStartTimeMillis(context.processStartTimeMillis()))
                                .build());
                        captures++;
                    } else if (type.equals("profiler.SignalSample")) {
                        Context sampleContext = context;
                        if (sampleContext == null && options != null && options.allowMissingMetadata()) {
                            sampleContext = expected;
                        }
                        requireContext(sampleContext);
                        long cookie = event.getLong("correlationId");
                        if (cookie >>> 32 != sampleContext.captureEpoch() || (cookie & 0xffffffffL) == 0) {
                            throw new IOException("Signal sample cookie does not belong to capture epoch");
                        }
                        if (options != null && !options.includes(event.getStartTime())) {
                            continue;
                        }
                        SignalProto.SignalSample.Builder sample = SignalProto.SignalSample.newBuilder()
                                .setSessionId(sampleContext.sessionId())
                                .setCaptureEpoch((int) sampleContext.captureEpoch())
                                .setCorrelationId(cookie)
                                .setMonotonicTimeNanos(event.getLong("monotonicTimeNanos"))
                                .setStartTime(timestamp(event.getStartTime()));
                        RecordedThread thread = event.getThread();
                        if (thread != null) {
                            if (thread.getOSThreadId() > 0) sample.setOsThreadId(thread.getOSThreadId());
                            if (thread.getJavaThreadId() > 0) sample.setJavaThreadId(thread.getJavaThreadId());
                            String name = thread.getJavaName() != null ? thread.getJavaName() : thread.getOSName();
                            if (name != null) sample.setThreadName(name);
                        }
                        RecordedStackTrace trace = event.getStackTrace();
                        if (trace != null) {
                            sample.setStackTruncated(trace.isTruncated());
                            for (RecordedFrame frame : trace.getFrames()) sample.addFrames(frame(frame));
                        }
                        output.accept(SignalProto.SignalRecord.newBuilder()
                                .setSample(sample)
                                .build());
                        samples++;
                    } else if (type.equals("profiler.SignalCaptureStats")) {
                        Context statsContext = context;
                        if (statsContext == null && options != null && options.allowMissingMetadata()) {
                            statsContext = expected;
                        }
                        requireContext(statsContext);
                        validateIdentity(event, statsContext);
                        if (++stats != 1) {
                            throw new IOException("Multiple terminal signal capture stats events");
                        }
                        output.accept(SignalProto.SignalRecord.newBuilder()
                                .setStats(SignalProto.SignalCaptureStats.newBuilder()
                                        .setSessionId(statsContext.sessionId())
                                        .setCaptureEpoch((int) statsContext.captureEpoch())
                                        .setStartTime(timestamp(event.getStartTime()))
                                        .setCounters(CaptureProto.AsyncProfilerStats.newBuilder()
                                                .setAdmittedSignals(event.getLong("admittedSignals"))
                                                .setInvalidSignalCode(event.getLong("invalidSignalCode"))
                                                .setZeroCookie(event.getLong("zeroCookie"))
                                                .setZeroSequence(event.getLong("zeroSequence"))
                                                .setStaleEpoch(event.getLong("staleEpoch"))
                                                .setAcceptedCookies(event.getLong("acceptedCookies"))
                                                .setCaptureFailures(event.getLong("captureFailures"))
                                                .setSubmittedSamples(event.getLong("submittedSamples"))))
                                .build());
                    } else if (type.startsWith("profiler.Signal")) {
                        throw new IOException("Unsupported signal event type: " + type);
                    }
                } catch (IllegalArgumentException | ClassCastException e) {
                    throw new IOException("Invalid " + type + " event", e);
                }
            }
        }
        if (partial) {
            return new PrefixOutcome(readFailure == null, context != null, stats == 1, captures, samples, readFailure);
        }
        boolean allowMissingMetadata = options != null && options.allowMissingMetadata();
        if (!allowMissingMetadata) {
            requireContext(context);
        }
        if (!allowMissingMetadata && stats != 1) {
            throw new IOException("Missing terminal signal capture stats event");
        }
        JfrTimeRange.RecordingBounds bounds = JfrTimeRange.recordingBounds(input);
        output.accept(SignalProto.SignalRecord.newBuilder()
                .setRecording(snapshot.build(bounds.start(), bounds.end()))
                .build());
        output.accept(SignalProto.SignalRecord.newBuilder()
                .setEnd(SignalProto.SignalEnd.newBuilder()
                        .setParseComplete(true)
                        .setCaptures(captures)
                        .setSamples(samples)
                        .setStats(stats))
                .build());
        return new PrefixOutcome(true, context != null, stats == 1, captures, samples, null);
    }

    static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static String boundedMessage(IOException error) {
        String message = error.toString();
        return message.substring(0, Math.min(message.length(), 240));
    }

    private static void requireContext(Context context) throws IOException {
        if (context == null) {
            throw new IOException("Missing signal capture context");
        }
    }

    private static void validateIdentity(RecordedEvent event, Context context) throws IOException {
        if (event.getInt("schemaVersion") != 1
                || !context.sessionId().equals(event.getString("sessionId"))
                || context.captureEpoch() != event.getLong("captureEpoch")) {
            throw new IOException("Conflicting signal capture identity or unsupported schema");
        }
    }

    private record Context(
            String sessionId,
            long captureEpoch,
            int signal,
            CaptureProto.SignalDelivery signalDelivery,
            long processId,
            long processStartTimeMillis) {
        static Context read(RecordedEvent event) throws IOException {
            String id = event.getString("sessionId");
            long epoch = event.getLong("captureEpoch");
            if (event.getInt("schemaVersion") != 1
                    || id == null
                    || !UUID.fromString(id).toString().equals(id)
                    || epoch < 1
                    || epoch > 0xffffffffL) {
                throw new IOException("Invalid signal capture identity or unsupported schema");
            }
            String delivery = event.getString("signalDelivery");
            CaptureProto.SignalDelivery policy;
            if ("queued".equals(delivery)) {
                policy = CaptureProto.SignalDelivery.SIGNAL_DELIVERY_QUEUED;
            } else if ("coalescing".equals(delivery)) {
                policy = CaptureProto.SignalDelivery.SIGNAL_DELIVERY_COALESCING;
            } else {
                throw new IOException("Invalid signal delivery policy");
            }
            return new Context(
                    id,
                    epoch,
                    event.getInt("signal"),
                    policy,
                    event.getLong("processId"),
                    event.getLong("processStartTimeMillis"));
        }
    }

    private static SignalProto.JfrFrame frame(RecordedFrame frame) {
        RecordedMethod method = frame.getMethod();
        SignalProto.JfrFrame.Builder result = SignalProto.JfrFrame.newBuilder()
                .setLineNumber(frame.getLineNumber())
                .setBytecodeIndex(frame.getBytecodeIndex());
        if (frame.getType() != null) result.setType(frame.getType());
        if (method != null) {
            if (method.getType() != null && method.getType().getName() != null) {
                result.setClassName(method.getType().getName());
            }
            if (method.getName() != null) result.setMethodName(method.getName());
            if (method.getDescriptor() != null) result.setMethodDescriptor(method.getDescriptor());
        }
        return result.build();
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: SignalJfrExporter <capture.jfr>");
        }
        export(Path.of(args[0]), new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8)));
    }
}
