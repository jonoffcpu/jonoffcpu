// SPDX-License-Identifier: MIT

package io.github.lhotari.jonoffcpu.jfr;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Streams generic async-profiler signal events through the public JFR reader.
 *
 * <p>This offline boundary lets a native correlator use JFR's thread and stack dictionaries without
 * implementing a JFR parser or relying on JDK internals. Cookies retain all 64 bits, counters use
 * decimal strings, and frames remain in JFR's leaf-first order. The consumer must see the final
 * {@code end} record before publishing derived artifacts: an interrupted or invalid export is not a
 * valid zero-sample capture. This class does not close the caller's writer.
 */
public final class SignalJfrExporter {
    private static final List<String> COUNTERS = List.of(
            "admittedSignals",
            "invalidSignalCode",
            "zeroCookie",
            "zeroSequence",
            "staleEpoch",
            "acceptedCookies",
            "captureFailures",
            "submittedSamples");

    private SignalJfrExporter() {}

    /**
     * Receives a resolved record while its recording is read, without an intermediate JSON
     * representation.
     */
    @FunctionalInterface
    public interface RowConsumer {
        void accept(Map<String, Object> row) throws IOException;
    }

    /**
     * Export one finalized, single-session capture. Throws on conflicting context or missing terminal
     * stats.
     */
    public static void export(Path input, Writer output) throws IOException {
        visit(input, row -> writeRow(output, row));
        output.flush();
    }

    /**
     * Read resolved signal records directly. Ordinary CPU, allocation and JVM events are left in the
     * input and ignored here. The end record means parsing finished, not that external source
     * integrity or the independently retained stop receipt has been verified.
     */
    public static void visit(Path input, RowConsumer output) throws IOException {
        decode(input, output, false, null);
    }

    /** Options for selecting signal samples from a complete or deliberately shortened JFR. */
    public record ReadOptions(
            String expectedSessionId,
            long expectedCaptureEpoch,
            Instant from,
            Instant to,
            boolean allowMissingMetadata) {
        public ReadOptions {
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
    public record PrefixOutcome(
            boolean cleanEof,
            boolean contextPresent,
            boolean terminalStatsPresent,
            long captures,
            long samples,
            String readFailure) {}

    /**
     * Explicit diagnostic prefix reader. Only low-level RecordingFile read failures and missing
     * terminal witnesses are recoverable. Invalid schemas/identities and consumer failures remain
     * hard errors. No successful end row is emitted by this API.
     */
    public static PrefixOutcome visitPrefix(Path input, RowConsumer output) throws IOException {
        return decode(input, output, true, null);
    }

    /**
     * Reads selected samples. Missing capture-context and terminal-stat events are accepted only when
     * {@link ReadOptions#allowMissingMetadata()} is true; sample cookies are still checked against
     * the expected capture epoch supplied by the finalized correlation stream.
     */
    public static PrefixOutcome visitSelected(Path input, RowConsumer output, ReadOptions options) throws IOException {
        return decode(input, output, false, options);
    }

    private static PrefixOutcome decode(Path input, RowConsumer output, boolean partial, ReadOptions options)
            throws IOException {
        Context context = null;
        Context expected = options == null
                ? null
                : new Context(options.expectedSessionId(), options.expectedCaptureEpoch(), 0, null, 0, 0);
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
                        Map<String, Object> row = row("capture", context);
                        row.put("startTime", event.getStartTime().toString());
                        row.put("signal", context.signal());
                        row.put("signalDelivery", context.signalDelivery());
                        row.put("processId", context.processId());
                        row.put("processStartTimeMillis", context.processStartTimeMillis());
                        output.accept(row);
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
                        Map<String, Object> row = row("sample", sampleContext);
                        row.put("correlationId", HexFormat.of().toHexDigits(cookie));
                        row.put("monotonicTimeNanos", Long.toUnsignedString(event.getLong("monotonicTimeNanos")));
                        row.put("startTime", event.getStartTime().toString());
                        RecordedThread thread = event.getThread();
                        row.put("osThreadId", thread == null ? null : positiveOrNull(thread.getOSThreadId()));
                        row.put("javaThreadId", thread == null ? null : positiveOrNull(thread.getJavaThreadId()));
                        row.put(
                                "threadName",
                                thread == null
                                        ? null
                                        : thread.getJavaName() != null ? thread.getJavaName() : thread.getOSName());
                        RecordedStackTrace trace = event.getStackTrace();
                        row.put("stackTruncated", trace == null ? null : trace.isTruncated());
                        row.put(
                                "frames",
                                trace == null
                                        ? List.of()
                                        : trace.getFrames().stream()
                                                .map(SignalJfrExporter::frame)
                                                .toList());
                        output.accept(row);
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
                        Map<String, Object> row = row("stats", statsContext);
                        row.put("startTime", event.getStartTime().toString());
                        for (String counter : COUNTERS) {
                            row.put(counter, Long.toUnsignedString(event.getLong(counter)));
                        }
                        output.accept(row);
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
        Map<String, Object> end = row("end", null);
        end.put("parseComplete", true);
        end.put("captures", Long.toUnsignedString(captures));
        end.put("samples", Long.toUnsignedString(samples));
        end.put("stats", Long.toUnsignedString(stats));
        output.accept(end);
        return new PrefixOutcome(true, context != null, stats == 1, captures, samples, null);
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
            String signalDelivery,
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
            if (!"queued".equals(delivery) && !"coalescing".equals(delivery)) {
                throw new IOException("Invalid signal delivery policy");
            }
            return new Context(
                    id,
                    epoch,
                    event.getInt("signal"),
                    delivery,
                    event.getLong("processId"),
                    event.getLong("processStartTimeMillis"));
        }
    }

    private static Map<String, Object> frame(RecordedFrame frame) {
        RecordedMethod method = frame.getMethod();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", frame.getType());
        result.put(
                "className",
                method == null || method.getType() == null
                        ? null
                        : method.getType().getName());
        result.put("methodName", method == null ? null : method.getName());
        result.put("descriptor", method == null ? null : method.getDescriptor());
        result.put("lineNumber", frame.getLineNumber());
        result.put("bytecodeIndex", frame.getBytecodeIndex());
        return result;
    }

    private static Long positiveOrNull(long value) {
        return value > 0 ? value : null;
    }

    private static Map<String, Object> row(String type, Context context) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("recordType", type);
        row.put("schemaVersion", 1);
        if (context != null) {
            row.put("sessionId", context.sessionId());
            row.put("captureEpoch", context.captureEpoch());
        }
        return row;
    }

    private static void writeRow(Writer output, Map<String, Object> row) throws IOException {
        writeJson(output, row);
        output.write('\n');
    }

    private static void writeJson(Writer out, Object value) throws IOException {
        if (value == null) {
            out.write("null");
        } else if (value instanceof String string) {
            out.write('"');
            for (int i = 0; i < string.length(); i++) {
                char c = string.charAt(i);
                switch (c) {
                    case '"' -> out.write("\\\"");
                    case '\\' -> out.write("\\\\");
                    case '\n' -> out.write("\\n");
                    case '\r' -> out.write("\\r");
                    case '\t' -> out.write("\\t");
                    default -> {
                        if (c < 0x20 || Character.isSurrogate(c)) {
                            out.write("\\u" + HexFormat.of().toHexDigits(c));
                        } else {
                            out.write(c);
                        }
                    }
                }
            }
            out.write('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            out.write(value.toString());
        } else if (value instanceof Map<?, ?> map) {
            out.write('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.write(',');
                }
                first = false;
                writeJson(out, entry.getKey());
                out.write(':');
                writeJson(out, entry.getValue());
            }
            out.write('}');
        } else if (value instanceof List<?> list) {
            out.write('[');
            boolean first = true;
            for (Object entry : list) {
                if (!first) {
                    out.write(',');
                }
                first = false;
                writeJson(out, entry);
            }
            out.write(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass());
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: SignalJfrExporter <capture.jfr>");
        }
        export(Path.of(args[0]), new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8)));
    }
}
