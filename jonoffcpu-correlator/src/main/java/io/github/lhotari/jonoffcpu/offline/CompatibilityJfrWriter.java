// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Writes a derived CPU-view JFR whose synthetic {@code jdk.ExecutionSample} count represents
 * selected observed off-CPU time. The original combined recording remains the authoritative JFR.
 *
 * <p>For each canonical resolved stack, integer remainder is carried across intervals in stable
 * source order. An event represents {@link Options#quantumNanos()} nanoseconds. Its timestamp is an
 * interior rendering point in the interval that released that quantum, after translating through
 * the matched signal sample's verified clock. Carry can therefore move up to one quantum of weight
 * from an earlier interval to a later timestamp. Exact duration remains in the correlation output
 * and in the metadata event; this file is a compatibility view, not historical CPU sampling.
 */
final class CompatibilityJfrWriter {
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final String EXECUTION_SAMPLE = "jdk.ExecutionSample";
    private static final String METADATA = "jonoffcpu.SyntheticOffCpuMetadata";

    /** Whether a quantum that would exceed the expansion cap is raised until it fits, or rejected. */
    public enum EventLimitPolicy {
        COARSEN,
        FAIL
    }

    public record Options(long quantumNanos, long maxSyntheticEvents, EventLimitPolicy onEventLimit) {
        public Options {
            if (quantumNanos <= 0 || maxSyntheticEvents <= 0) {
                throw new IllegalArgumentException("Quantum and maximum event count must be positive");
            }
            Objects.requireNonNull(onEventLimit, "onEventLimit");
        }

        public Options(long quantumNanos, long maxSyntheticEvents) {
            this(quantumNanos, maxSyntheticEvents, EventLimitPolicy.COARSEN);
        }

        public static Options defaults() {
            return new Options(1_000_000L, 10_000_000L);
        }
    }

    public record Result(
            Path output,
            long syntheticEvents,
            int matchedIntervals,
            int canonicalStacks,
            long quantumNanos,
            long requestedQuantumNanos,
            boolean quantumRaised,
            String exactSelectedNanos,
            String representedNanos,
            String quantizationErrorNanos,
            String omittedRemainderNanos) {}

    private record Planned(SyntheticJfrSource.Interval interval, long count) {}

    private record Plan(
            List<Planned> intervals,
            long events,
            int stacks,
            BigInteger exact,
            BigInteger represented,
            BigInteger error,
            BigInteger omitted,
            BigInteger firstEpoch,
            BigInteger lastEpoch,
            long quantumNanos,
            long requestedQuantumNanos,
            boolean quantumRaised) {}

    private record JfrTypes(
            Type executionSample,
            Type metadata,
            Type thread,
            Type stackTrace,
            Type stackFrame,
            Type method,
            Type classType,
            Type packageType,
            Type frameType,
            Type threadState) {}

    private CompatibilityJfrWriter() {}

    /** A retained analysis, for the existing fixture that builds matches by hand. */
    public static Result write(OfflineCorrelator.Analysis analysis, Path output, Options options) throws IOException {
        Objects.requireNonNull(analysis, "analysis");
        if (analysis.schemaVersion() != 1 || analysis.matches() == null || analysis.analysisInputs() == null) {
            throw new IOException("Unsupported or incomplete correlation analysis");
        }
        return write(SyntheticJfrSource.of(analysis), output, options);
    }

    /**
     * Creates a new compatibility recording. Publication uses a same-directory hard link, so an
     * existing destination is never replaced and unsupported atomic publication fails closed.
     */
    public static Result write(SyntheticJfrSource source, Path output, Options options) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Compatibility output parent does not exist: " + parent);
        }
        if (Files.exists(absolute)) throw new FileAlreadyExistsException(absolute.toString());

        Plan plan = plan(source, options);
        Path temporary = Files.createTempFile(parent, ".jonoffcpu-compatibility-", ".jfr.tmp");
        try {
            writeTemporary(source, temporary, options, plan);
            // Hard-link creation is an atomic, no-replace publication on the required Linux filesystems.
            Files.createLink(absolute, temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Result(
                absolute,
                plan.events(),
                plan.intervals().size(),
                plan.stacks(),
                plan.quantumNanos(),
                plan.requestedQuantumNanos(),
                plan.quantumRaised(),
                plan.exact().toString(),
                plan.represented().toString(),
                plan.error().toString(),
                plan.omitted().toString());
    }

    private static Plan plan(SyntheticJfrSource source, Options options) throws IOException {
        QuantumPlanner.Plan chosen = options.onEventLimit() == EventLimitPolicy.FAIL
                ? new QuantumPlanner.Plan(options.quantumNanos(), options.quantumNanos(), 0, false)
                : QuantumPlanner.plan(source.stackNanos(), options.quantumNanos(), options.maxSyntheticEvents());
        BigInteger quantum = BigInteger.valueOf(chosen.quantumNanos());
        BigInteger monotonicOffset = signedOffset(source.analysisInputs());
        long[] remainders = new long[source.stackCount()];
        BitSet seen = new BitSet(source.stackCount());
        List<Planned> intervals = new ArrayList<>(source.intervalCount());

        class Totals {
            BigInteger exact = BigInteger.ZERO;
            BigInteger eventCount = BigInteger.ZERO;
            BigInteger firstEpoch;
            BigInteger lastEpoch;
        }
        Totals totals = new Totals();

        source.forEachInterval(interval -> {
            validateInterval(interval);
            int stackId = interval.stackId();
            seen.set(stackId);
            BigInteger duration = BigInteger.valueOf(interval.durationNanos());
            totals.exact = totals.exact.add(duration);
            BigInteger available = BigInteger.valueOf(remainders[stackId]).add(duration);
            BigInteger[] divided = available.divideAndRemainder(quantum);
            remainders[stackId] = divided[1].longValueExact();
            totals.eventCount = totals.eventCount.add(divided[0]);
            if (totals.eventCount.compareTo(BigInteger.valueOf(options.maxSyntheticEvents())) > 0
                    || divided[0].compareTo(LONG_MAX) > 0) {
                throw new IOException("Synthetic event limit exceeded before output publication");
            }
            BigInteger intervalFirst = BigInteger.valueOf(interval.fromNanos())
                    .add(monotonicOffset)
                    .add(BigInteger.valueOf(interval.epochOffsetNanos()));
            BigInteger intervalLast = BigInteger.valueOf(interval.toNanos())
                    .add(monotonicOffset)
                    .add(BigInteger.valueOf(interval.epochOffsetNanos()));
            totals.firstEpoch = totals.firstEpoch == null ? intervalFirst : totals.firstEpoch.min(intervalFirst);
            totals.lastEpoch = totals.lastEpoch == null ? intervalLast : totals.lastEpoch.max(intervalLast);
            intervals.add(new Planned(interval, divided[0].longValueExact()));
        });

        BigInteger declared = parseUnsigned(source.selectedObservedDurationNanos(), "selected duration");
        if (!declared.equals(totals.exact))
            throw new IOException("Analysis selected duration does not match intervals");
        BigInteger represented = totals.eventCount.multiply(quantum);
        BigInteger omitted = totals.exact.subtract(represented);
        BigInteger remainderTotal = BigInteger.ZERO;
        for (long remainder : remainders) remainderTotal = remainderTotal.add(BigInteger.valueOf(remainder));
        if (!omitted.equals(remainderTotal)) throw new IOException("Internal quantization accounting mismatch");
        BigInteger now = BigInteger.valueOf(System.currentTimeMillis()).multiply(BigInteger.valueOf(1_000_000L));
        return new Plan(
                List.copyOf(intervals),
                totals.eventCount.longValueExact(),
                seen.cardinality(),
                totals.exact,
                represented,
                represented.subtract(totals.exact),
                omitted,
                totals.firstEpoch == null ? now : totals.firstEpoch,
                totals.lastEpoch == null ? now : totals.lastEpoch,
                chosen.quantumNanos(),
                chosen.requestedQuantumNanos(),
                chosen.raised());
    }

    private static void writeTemporary(SyntheticJfrSource source, Path output, Options options, Plan plan)
            throws IOException {
        long duration =
                checkedLong(plan.lastEpoch().subtract(plan.firstEpoch()).add(BigInteger.ONE), "recording time range");
        long firstEpoch = checkedLong(plan.firstEpoch(), "recording epoch");
        try (Recording recording = Recordings.newRecording(
                output,
                settings -> settings.withTimestamp(firstEpoch)
                        .withStartTicks(1L)
                        .withDuration(duration)
                        .withJdkTypeInitialization())) {
            JfrTypes types = registerTypes(recording);
            writeMetadata(recording, types, source, options, plan);
            BigInteger monotonicOffset = signedOffset(source.analysisInputs());
            BigInteger startEpoch = plan.firstEpoch();
            for (Planned planned : plan.intervals()) {
                if (planned.count() == 0) continue;
                SyntheticJfrSource.Interval interval = planned.interval();
                TypedValue thread = thread(types, source.thread(interval.threadId()));
                TypedValue stack =
                        stack(types, source.frames(interval.stackId()), source.truncated(interval.stackId()));
                // AP's CPU converter admits STATE_DEFAULT for jdk.ExecutionSample.
                TypedValue state = types.threadState().asValue(value -> value.putField("name", "STATE_DEFAULT"));
                BigInteger width = BigInteger.valueOf(interval.durationNanos());
                for (long index = 0; index < planned.count(); index++) {
                    // Midpoints keep every synthetic time strictly within a nonempty source interval.
                    BigInteger numerator = BigInteger.valueOf(index)
                            .multiply(BigInteger.TWO)
                            .add(BigInteger.ONE)
                            .multiply(width);
                    BigInteger point = BigInteger.valueOf(interval.fromNanos())
                            .add(numerator.divide(
                                    BigInteger.valueOf(planned.count()).multiply(BigInteger.TWO)));
                    BigInteger epoch = point.add(monotonicOffset).add(BigInteger.valueOf(interval.epochOffsetNanos()));
                    long ticks = checkedLong(epoch.subtract(startEpoch).add(BigInteger.ONE), "event timestamp");
                    recording.writeEvent(types.executionSample()
                            .asValue(value -> value.putField("startTime", ticks)
                                    .putField("sampledThread", thread)
                                    .putField("stackTrace", stack)
                                    .putField("state", state)));
                }
            }
        }
    }

    private static JfrTypes registerTypes(Recording recording) {
        Types types = recording.getTypes();
        Type timestamp = types.getType(Types.JDK.ANNOTATION_TIMESTAMP);
        Type packageType = types.getType(Types.JDK.PACKAGE);
        Type classType = types.getType(Types.JDK.CLASS);
        Type method = types.getType(Types.JDK.METHOD);
        Type frameType = types.getType(Types.JDK.FRAME_TYPE);
        Type stackFrame = types.getType(Types.JDK.STACK_FRAME);
        Type stackTrace = types.getType(Types.JDK.STACK_TRACE);
        Type thread = types.getType(Types.JDK.THREAD);
        Type threadState = recording.registerType(
                "jdk.types.ThreadState", builder -> builder.addField("name", Types.Builtin.STRING));
        Type execution = recording.registerType(
                EXECUTION_SAMPLE,
                "jdk.jfr.Event",
                builder -> builder.addField(
                                "startTime", Types.Builtin.LONG, field -> field.addAnnotation(timestamp, "TICKS"))
                        .addField("sampledThread", thread)
                        .addField("stackTrace", stackTrace)
                        .addField("state", threadState));
        Type metadata = recording.registerType(
                METADATA,
                "jdk.jfr.Event",
                builder -> builder.addField(
                                "startTime", Types.Builtin.LONG, field -> field.addAnnotation(timestamp, "TICKS"))
                        .addField("schemaVersion", Types.Builtin.INT)
                        .addField("synthetic", Types.Builtin.BOOLEAN)
                        .addField("weightingKind", Types.Builtin.STRING)
                        .addField("timestampConvention", Types.Builtin.STRING)
                        .addField("sessionId", Types.Builtin.STRING)
                        .addField("captureEpoch", Types.Builtin.LONG)
                        .addField("quantumNanos", Types.Builtin.LONG)
                        .addField("requestedQuantumNanos", Types.Builtin.LONG)
                        .addField("maxSyntheticEvents", Types.Builtin.LONG)
                        .addField("syntheticEvents", Types.Builtin.LONG)
                        .addField("matchedIntervals", Types.Builtin.INT)
                        .addField("canonicalStacks", Types.Builtin.INT)
                        .addField("exactSelectedNanos", Types.Builtin.STRING)
                        .addField("representedNanos", Types.Builtin.STRING)
                        .addField("quantizationErrorNanos", Types.Builtin.STRING)
                        .addField("omittedRemainderNanos", Types.Builtin.STRING)
                        .addField("sourceSha256", Types.Builtin.STRING)
                        .addField("originalJfrSha256", Types.Builtin.STRING));
        return new JfrTypes(
                execution,
                metadata,
                thread,
                stackTrace,
                stackFrame,
                method,
                classType,
                packageType,
                frameType,
                threadState);
    }

    private static void writeMetadata(
            Recording recording, JfrTypes types, SyntheticJfrSource source, Options options, Plan plan)
            throws IOException {
        JsonObject inputs = source.analysisInputs();
        JsonObject sourceArtifact = requiredObject(inputs, "sourceArtifact");
        JsonObject jfrArtifact = requiredObject(inputs, "jfrArtifact");
        String session = requiredString(inputs, "sessionId");
        long epoch = requiredLong(inputs, "captureEpoch");
        String sourceHash = requiredString(sourceArtifact, "rawSha256");
        String jfrHash = requiredString(jfrArtifact, "sha256");
        recording.writeEvent(types.metadata()
                .asValue(value -> value.putField("startTime", 1L)
                        .putField("schemaVersion", 1)
                        .putField("synthetic", true)
                        .putField("weightingKind", "selected-observed-offcpu-nanoseconds")
                        .putField("timestampConvention", "interval-interior-remainder-carry")
                        .putField("sessionId", session)
                        .putField("captureEpoch", epoch)
                        .putField("quantumNanos", plan.quantumNanos())
                        .putField("requestedQuantumNanos", plan.requestedQuantumNanos())
                        .putField("maxSyntheticEvents", options.maxSyntheticEvents())
                        .putField("syntheticEvents", plan.events())
                        .putField("matchedIntervals", plan.intervals().size())
                        .putField("canonicalStacks", plan.stacks())
                        .putField("exactSelectedNanos", plan.exact().toString())
                        .putField("representedNanos", plan.represented().toString())
                        .putField("quantizationErrorNanos", plan.error().toString())
                        .putField("omittedRemainderNanos", plan.omitted().toString())
                        .putField("sourceSha256", sourceHash)
                        .putField("originalJfrSha256", jfrHash)));
    }

    private static TypedValue thread(JfrTypes types, JfrDictionaries.Thread thread) {
        String name = thread.name() == null ? "[unknown thread]" : thread.name();
        long osTid = thread.osThreadId();
        long javaTid = thread.javaThreadId();
        return types.thread()
                .asValue(value -> value.putField("osName", name)
                        .putField("osThreadId", osTid)
                        .putField("javaName", name)
                        .putField("javaThreadId", javaTid));
    }

    private static TypedValue stack(JfrTypes types, JfrDictionaries.Frame[] frames, boolean truncated) {
        JfrDictionaries.Frame[] resolved = frames;
        if (resolved.length == 0) {
            resolved = new JfrDictionaries.Frame[] {
                new JfrDictionaries.Frame("unknown", "jonoffcpu.synthetic", "[stack unavailable]", "()V", -1, -1)
            };
        }
        TypedValue[] values = new TypedValue[resolved.length];
        for (int index = 0; index < resolved.length; index++) {
            JfrDictionaries.Frame frame = resolved[index];
            TypedValue method = method(types, frame);
            int line = frame.lineNumber();
            int bci = frame.bytecodeIndex();
            String kind = frame.type() == null ? "unknown" : frame.type();
            values[index] = types.stackFrame()
                    .asValue(value -> value.putField("method", method)
                            .putField("lineNumber", line)
                            .putField("bytecodeIndex", bci)
                            .putField("type", kind));
        }
        return types.stackTrace()
                .asValue(value -> value.putField("truncated", truncated).putField("frames", values));
    }

    private static TypedValue method(JfrTypes types, JfrDictionaries.Frame frame) {
        String className = frame.className() == null ? "[unknown]" : frame.className();
        int separator = className.lastIndexOf('.');
        String packageName = separator < 0 ? "" : className.substring(0, separator);
        TypedValue loader = types.classType()
                .getTypes()
                .getType(Types.JDK.CLASS_LOADER)
                .asValue(value -> value.putField("name", "synthetic"));
        TypedValue module = types.classType()
                .getTypes()
                .getType(Types.JDK.MODULE)
                .asValue(value -> value.putField("name", "synthetic")
                        .putField("version", "synthetic")
                        .putField("location", "synthetic")
                        .putField("classLoader", loader));
        TypedValue pkg = types.packageType()
                .asValue(value -> value.putField("name", packageName)
                        .putField("module", module)
                        .putField("exported", false));
        TypedValue klass = types.classType()
                .asValue(value -> value.putField("name", className)
                        .putField("classLoader", loader)
                        .putField("package", pkg)
                        .putField("modifiers", 0)
                        .putField("hidden", false));
        String methodName = frame.methodName() == null ? "[unresolved]" : frame.methodName();
        String descriptor = frame.descriptor() == null ? "" : frame.descriptor();
        return types.method()
                .asValue(value -> value.putField("type", klass)
                        .putField("name", methodName)
                        .putField("descriptor", descriptor)
                        .putField("modifiers", 0)
                        .putField("hidden", false));
    }

    private static void validateInterval(SyntheticJfrSource.Interval interval) throws IOException {
        if (interval.fromNanos() < 0
                || interval.toNanos() < interval.fromNanos()
                || interval.durationNanos() != interval.toNanos() - interval.fromNanos()) {
            throw new IOException("Invalid matched interval");
        }
    }

    private static BigInteger signedOffset(JsonObject inputs) throws IOException {
        String text = requiredString(inputs, "monotonicOffsetNanos");
        if (!text.matches("0|-?[1-9][0-9]{0,19}")) throw new IOException("Invalid monotonic offset");
        return new BigInteger(text);
    }

    private static BigInteger parseUnsigned(String text, String label) throws IOException {
        if (text == null || !text.matches("0|[1-9][0-9]*")) throw new IOException("Invalid " + label);
        return new BigInteger(text);
    }

    private static long checkedLong(BigInteger value, String label) throws IOException {
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new IOException(label + " does not fit signed 64-bit JFR time", e);
        }
    }

    private static JsonObject requiredObject(JsonObject object, String field) throws IOException {
        com.google.gson.JsonElement value = object.get(field);
        if (value == null || !value.isJsonObject()) throw new IOException("Missing object: " + field);
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String field) throws IOException {
        com.google.gson.JsonElement value = object.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IOException("Missing string: " + field);
        }
        return value.getAsString();
    }

    private static long requiredLong(JsonObject object, String field) throws IOException {
        try {
            return object.get(field).getAsLong();
        } catch (RuntimeException e) {
            throw new IOException("Invalid integer: " + field, e);
        }
    }
}
