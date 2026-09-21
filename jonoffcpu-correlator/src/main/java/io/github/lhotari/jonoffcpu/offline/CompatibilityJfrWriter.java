// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final String EXECUTION_SAMPLE = "jdk.ExecutionSample";
    private static final String METADATA = "jonoffcpu.SyntheticOffCpuMetadata";

    public record Options(long quantumNanos, long maxSyntheticEvents) {
        public Options {
            if (quantumNanos <= 0 || maxSyntheticEvents <= 0) {
                throw new IllegalArgumentException("Quantum and maximum event count must be positive");
            }
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
            String exactSelectedNanos,
            String representedNanos,
            String quantizationErrorNanos,
            String omittedRemainderNanos) {}

    private record Planned(OfflineCorrelator.Match match, String stackKey, long count, BigInteger epochOffsetNanos) {}

    private record Plan(
            List<Planned> intervals,
            long events,
            int stacks,
            BigInteger exact,
            BigInteger represented,
            BigInteger error,
            BigInteger omitted,
            BigInteger firstEpoch,
            BigInteger lastEpoch) {}

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

    /**
     * Creates a new compatibility recording. Publication uses a same-directory hard link, so an
     * existing destination is never replaced and unsupported atomic publication fails closed.
     */
    public static Result write(OfflineCorrelator.Analysis analysis, Path output, Options options) throws IOException {
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(options, "options");
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Compatibility output parent does not exist: " + parent);
        }
        if (Files.exists(absolute)) throw new FileAlreadyExistsException(absolute.toString());

        Plan plan = plan(analysis, options);
        Path temporary = Files.createTempFile(parent, ".jonoffcpu-compatibility-", ".jfr.tmp");
        try {
            writeTemporary(analysis, temporary, options, plan);
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
                options.quantumNanos(),
                plan.exact().toString(),
                plan.represented().toString(),
                plan.error().toString(),
                plan.omitted().toString());
    }

    private static Plan plan(OfflineCorrelator.Analysis analysis, Options options) throws IOException {
        if (analysis.schemaVersion() != 1 || analysis.matches() == null || analysis.analysisInputs() == null) {
            throw new IOException("Unsupported or incomplete correlation analysis");
        }
        List<OfflineCorrelator.Match> matches = new ArrayList<>(analysis.matches());
        for (OfflineCorrelator.Match match : matches) {
            validateMatch(match);
            requiredString(match.observation(), "correlationId");
        }
        matches.sort(Comparator.comparing(OfflineCorrelator.Match::fromNanos)
                .thenComparing(OfflineCorrelator.Match::toNanos)
                .thenComparing(match -> match.observation().get("correlationId").getAsString()));
        BigInteger quantum = BigInteger.valueOf(options.quantumNanos());
        Map<String, BigInteger> remainders = new LinkedHashMap<>();
        List<Planned> intervals = new ArrayList<>(matches.size());
        BigInteger exact = BigInteger.ZERO;
        BigInteger eventCount = BigInteger.ZERO;
        BigInteger firstEpoch = null;
        BigInteger lastEpoch = null;
        for (OfflineCorrelator.Match match : matches) {
            String stack = canonicalStack(match.sample());
            BigInteger duration = match.durationNanos();
            exact = exact.add(duration);
            BigInteger available =
                    remainders.getOrDefault(stack, BigInteger.ZERO).add(duration);
            BigInteger[] divided = available.divideAndRemainder(quantum);
            remainders.put(stack, divided[1]);
            eventCount = eventCount.add(divided[0]);
            if (eventCount.compareTo(BigInteger.valueOf(options.maxSyntheticEvents())) > 0
                    || divided[0].compareTo(LONG_MAX) > 0) {
                throw new IOException("Synthetic event limit exceeded before output publication");
            }
            BigInteger epochOffset = epochNanos(match.sample()).subtract(decimal(match.sample(), "monotonicTimeNanos"));
            BigInteger intervalFirst = match.fromNanos()
                    .add(signedOffset(analysis.analysisInputs()))
                    .add(epochOffset);
            BigInteger intervalLast =
                    match.toNanos().add(signedOffset(analysis.analysisInputs())).add(epochOffset);
            firstEpoch = firstEpoch == null ? intervalFirst : firstEpoch.min(intervalFirst);
            lastEpoch = lastEpoch == null ? intervalLast : lastEpoch.max(intervalLast);
            intervals.add(new Planned(match, stack, divided[0].longValueExact(), epochOffset));
        }
        BigInteger declared = parseUnsigned(analysis.selectedObservedDurationNanos(), "selected duration");
        if (!declared.equals(exact)) throw new IOException("Analysis selected duration does not match intervals");
        BigInteger represented = eventCount.multiply(quantum);
        BigInteger omitted = exact.subtract(represented);
        BigInteger remainderTotal = remainders.values().stream().reduce(BigInteger.ZERO, BigInteger::add);
        if (!omitted.equals(remainderTotal)) throw new IOException("Internal quantization accounting mismatch");
        BigInteger now = BigInteger.valueOf(System.currentTimeMillis()).multiply(BigInteger.valueOf(1_000_000L));
        return new Plan(
                List.copyOf(intervals),
                eventCount.longValueExact(),
                remainders.size(),
                exact,
                represented,
                represented.subtract(exact),
                omitted,
                firstEpoch == null ? now : firstEpoch,
                lastEpoch == null ? now : lastEpoch);
    }

    private static void writeTemporary(OfflineCorrelator.Analysis analysis, Path output, Options options, Plan plan)
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
            writeMetadata(recording, types, analysis, options, plan);
            BigInteger monotonicOffset = signedOffset(analysis.analysisInputs());
            BigInteger startEpoch = plan.firstEpoch();
            for (Planned interval : plan.intervals()) {
                if (interval.count() == 0) continue;
                TypedValue thread = thread(types, interval.match().sample());
                TypedValue stack = stack(types, interval.match().sample());
                // AP's CPU converter admits STATE_DEFAULT for jdk.ExecutionSample.
                TypedValue state = types.threadState().asValue(value -> value.putField("name", "STATE_DEFAULT"));
                BigInteger width = interval.match().durationNanos();
                for (long index = 0; index < interval.count(); index++) {
                    // Midpoints keep every synthetic time strictly within a nonempty source interval.
                    BigInteger numerator = BigInteger.valueOf(index)
                            .multiply(BigInteger.TWO)
                            .add(BigInteger.ONE)
                            .multiply(width);
                    BigInteger point = interval.match()
                            .fromNanos()
                            .add(numerator.divide(
                                    BigInteger.valueOf(interval.count()).multiply(BigInteger.TWO)));
                    BigInteger epoch = point.add(monotonicOffset).add(interval.epochOffsetNanos());
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
            Recording recording, JfrTypes types, OfflineCorrelator.Analysis analysis, Options options, Plan plan)
            throws IOException {
        JsonObject inputs = analysis.analysisInputs();
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
                        .putField("quantumNanos", options.quantumNanos())
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

    private static TypedValue thread(JfrTypes types, JsonObject sample) throws IOException {
        Long osTid = optionalPositiveLong(sample, "osThreadId");
        Long javaTid = optionalPositiveLong(sample, "javaThreadId");
        String name = optionalString(sample, "threadName", "[unknown thread]");
        return types.thread()
                .asValue(value -> value.putField("osName", name)
                        .putField("osThreadId", osTid == null ? 0L : osTid)
                        .putField("javaName", name)
                        .putField("javaThreadId", javaTid == null ? 0L : javaTid));
    }

    private static TypedValue stack(JfrTypes types, JsonObject sample) throws IOException {
        JsonArray frames = requiredArray(sample, "frames");
        if (frames.isEmpty()) {
            frames = new JsonArray();
            JsonObject unavailable = new JsonObject();
            unavailable.addProperty("className", "jonoffcpu.synthetic");
            unavailable.addProperty("methodName", "[stack unavailable]");
            unavailable.addProperty("descriptor", "()V");
            unavailable.addProperty("type", "unknown");
            unavailable.addProperty("lineNumber", -1);
            unavailable.addProperty("bytecodeIndex", -1);
            frames.add(unavailable);
        }
        TypedValue[] values = new TypedValue[frames.size()];
        for (int index = 0; index < frames.size(); index++) {
            JsonObject frame = frames.get(index).getAsJsonObject();
            TypedValue method = method(types, frame);
            int line = optionalInt(frame, "lineNumber", -1);
            int bci = optionalInt(frame, "bytecodeIndex", -1);
            String kind = optionalString(frame, "type", "unknown");
            values[index] = types.stackFrame()
                    .asValue(value -> value.putField("method", method)
                            .putField("lineNumber", line)
                            .putField("bytecodeIndex", bci)
                            .putField("type", kind));
        }
        boolean truncated = optionalBoolean(sample, "stackTruncated", false);
        return types.stackTrace()
                .asValue(value -> value.putField("truncated", truncated).putField("frames", values));
    }

    private static TypedValue method(JfrTypes types, JsonObject frame) {
        String className = optionalString(frame, "className", "[unknown]");
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
        String methodName = optionalString(frame, "methodName", "[unresolved]");
        String descriptor = optionalString(frame, "descriptor", "");
        return types.method()
                .asValue(value -> value.putField("type", klass)
                        .putField("name", methodName)
                        .putField("descriptor", descriptor)
                        .putField("modifiers", 0)
                        .putField("hidden", false));
    }

    private static void validateMatch(OfflineCorrelator.Match match) throws IOException {
        if (match == null
                || match.observation() == null
                || match.sample() == null
                || match.fromNanos() == null
                || match.toNanos() == null
                || match.durationNanos() == null
                || match.fromNanos().signum() < 0
                || match.toNanos().compareTo(match.fromNanos()) < 0
                || !match.durationNanos().equals(match.toNanos().subtract(match.fromNanos()))) {
            throw new IOException("Invalid matched interval");
        }
        requiredArray(match.sample(), "frames");
    }

    private static String canonicalStack(JsonObject sample) throws IOException {
        StringBuilder key = new StringBuilder(optionalBoolean(sample, "stackTruncated", false) ? "1" : "0");
        for (JsonElement element : requiredArray(sample, "frames")) {
            if (!element.isJsonObject()) throw new IOException("Invalid frame in matched sample");
            JsonObject frame = element.getAsJsonObject();
            for (String field :
                    List.of("type", "className", "methodName", "descriptor", "lineNumber", "bytecodeIndex")) {
                String value = frame.has(field) && !frame.get(field).isJsonNull()
                        ? frame.get(field).getAsString()
                        : "";
                key.append('|').append(value.length()).append(':').append(value);
            }
        }
        return key.toString();
    }

    private static BigInteger epochNanos(JsonObject sample) throws IOException {
        try {
            Instant instant = Instant.parse(requiredString(sample, "startTime"));
            return BigInteger.valueOf(instant.getEpochSecond())
                    .multiply(BILLION)
                    .add(BigInteger.valueOf(instant.getNano()));
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new IOException("Invalid sample start time", e);
        }
    }

    private static BigInteger signedOffset(JsonObject inputs) throws IOException {
        String text = requiredString(inputs, "monotonicOffsetNanos");
        if (!text.matches("0|-?[1-9][0-9]{0,19}")) throw new IOException("Invalid monotonic offset");
        return new BigInteger(text);
    }

    private static BigInteger decimal(JsonObject object, String field) throws IOException {
        return parseUnsigned(requiredString(object, field), field);
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
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonObject()) throw new IOException("Missing object: " + field);
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject object, String field) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) throw new IOException("Missing array: " + field);
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String field) throws IOException {
        JsonElement value = object.get(field);
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

    private static Long optionalPositiveLong(JsonObject object, String field) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) return null;
        try {
            long parsed = value.getAsLong();
            if (parsed <= 0) throw new IOException("Invalid positive integer: " + field);
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid integer: " + field, e);
        }
    }

    private static int optionalInt(JsonObject object, String field, int fallback) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static boolean optionalBoolean(JsonObject object, String field, boolean fallback) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }

    private static String optionalString(JsonObject object, String field, String fallback) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }
}
