// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.lhotari.jonoffcpu.capture.CaptureProto;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Validates a finalized source stream and its self-contained receipt before correlation. */
final class CaptureInput {
    static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    /** Version 2 interns stacks: each distinct stack is one record that observations reference by id. */
    private static final int SCHEMA_VERSION = 2;

    final JsonObject start;
    final JsonObject end;
    final JsonObject inputs;
    /** The number of observations the stream carried; the counters in {@code captureEnd} must agree. */
    final int observationCount;
    /** Every announced stack id, mapped to its frame count. The frames themselves are not retained. */
    final LongIntMap announcedStacks;

    final String sourceDigest;
    final String jfrDigest;
    final String apStoppedAtNanos;
    final Budget budget;
    final boolean partial;
    final JsonObject diagnostics;

    private CaptureInput(
            JsonObject start,
            JsonObject end,
            JsonObject inputs,
            int observationCount,
            LongIntMap announcedStacks,
            String sourceDigest,
            String jfrDigest,
            String apStoppedAtNanos,
            Budget budget,
            boolean partial,
            JsonObject diagnostics) {
        this.start = start;
        this.end = end;
        this.inputs = inputs;
        this.observationCount = observationCount;
        this.announcedStacks = announcedStacks;
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

    /** The validated sampling policy from {@code captureStart}. */
    SamplingPolicy sampling() throws IOException {
        return SamplingPolicy.parse(object(start, "sampling"));
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
        JsonObject start = null;
        JsonObject end = null;
        JsonObject footer = null;
        int observations = 0;
        LongIntMap announcedStacks = new LongIntMap(1 << 12);
        List<String> reasons = new ArrayList<>();
        visitor.reading(budget, announcedStacks);
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            byte[] header = CaptureStream.readHeader(input);
            wholeHash.update(header);
            rawHash.update(header);
            sourceBytes = header.length;
            rawBytes = header.length;
            CaptureStream.Framed framed;
            while ((framed = CaptureStream.next(input, limits.maxLineBytes())) != null) {
                require(footer == null, "Rows follow captureFinalized");
                byte[] bytes = framed.bytes();
                wholeHash.update(bytes);
                sourceBytes = Math.addExact(sourceBytes, bytes.length);
                if (framed.truncated()) {
                    require(partial, "Incomplete source record: truncated tail");
                    trailingBytes = bytes.length;
                    reasons.add("unterminated-source-tail");
                    break;
                }
                String type = CaptureStream.recordType(framed.record());
                budget.countRow();
                boolean control = !type.equals("stack") && !type.equals("observation");
                // Control records still carry JSON, and it is still parsed with the strict reader.
                JsonObject row = control
                        ? parse(CaptureStream.controlJson(framed.record())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        : null;
                if (control) {
                    budget.charge(row);
                    require(number(row, "schemaVersion") == SCHEMA_VERSION, "Unsupported source schema");
                }
                if (!type.equals("captureFinalized")) {
                    rawHash.update(bytes);
                    rawBytes = Math.addExact(rawBytes, bytes.length);
                }
                if (type.equals("captureFinalized")
                        && start == null
                        && "profilerOnly".equals(optionalText(row, "state"))) {
                    throw new IOException("This capture was recorded with sampling.admission.policy none"
                            + " (profiler-only mode): the eBPF source was disabled, the JFR holds only"
                            + " async-profiler events, and there is nothing to correlate");
                }
                switch (type) {
                    case "captureStart" -> {
                        require(start == null && end == null && observations == 0, "Duplicate/out-of-order start");
                        start = row;
                        requireStartFields(start);
                        visitor.start(start);
                    }
                    case "stack" -> {
                        require(start != null && end == null, "Stack outside source capture");
                        CaptureProto.Stack stack = framed.record().getStack();
                        long stackId = stack.getId();
                        require(stackId >= 0, "Invalid stack id");
                        require(stack.getFrameCount() <= limits.maxFrames(), "Stack frame count limit exceeded");
                        require(!announcedStacks.contains(stackId), "Duplicate stack record");
                        announcedStacks.put(stackId, stack.getFrameCount());
                        visitor.stack(stackId, stack);
                    }
                    case "observation" -> {
                        require(start != null && end == null, "Observation outside source capture");
                        // Source row numbers have always started at two; the classified records echo them.
                        visitor.observation(observations + 2, framed.record().getObservation());
                        observations++;
                    }
                    case "captureEnd" -> {
                        require(start != null && end == null, "Duplicate/out-of-order source end");
                        end = row;
                    }
                    case "captureFinalized" -> {
                        require(end != null, "Finalization before source end");
                        footer = row;
                    }
                    default -> throw new IOException("Unknown source row: " + type);
                }
            }
        }
        require(start != null, "Missing source captureStart");
        require(partial || end != null && footer != null, "Missing source finalization");
        if (end == null) reasons.add("missing-capture-end");
        if (footer == null) reasons.add("missing-capture-finalized");
        // This is internal observed context, not a manufactured finalization witness.
        JsonObject inputs = footer == null ? start.deepCopy() : object(footer, "analysisInputs");
        String session = text(inputs, "sessionId");
        try {
            require(UUID.fromString(session).toString().equals(session), "Noncanonical session UUID");
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid session UUID", e);
        }
        String delivery;
        try {
            delivery = requireDelivery(text(inputs, "signalDelivery"));
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid signal delivery policy", error);
        }
        require(delivery.equals(text(start, "signalDelivery")), "Source delivery policy mismatch");
        long epoch = number(inputs, "captureEpoch");
        require(epoch > 0 && epoch <= 0xffffffffL, "Invalid epoch");
        identity(start, inputs);
        if (end != null) identity(end, inputs);
        if (footer != null) {
            identity(footer, inputs);
            require(text(footer, "state").equals("complete"), "Invalid finalization state");
        }
        require(text(start, "sourceId").equals("jonoffcpu.offcpu.v1"), "Unsupported source");
        require(text(start, "registrationToken").matches("[0-9a-f]{16}"), "Invalid process registration token");
        for (String key : List.of("signal", "hostTgid", "targetPid")) {
            require(number(start, key) == number(inputs, key), "Source/footer mismatch: " + key);
        }
        require(number(inputs, "signal") > 0 && number(inputs, "signal") <= Integer.MAX_VALUE, "Invalid signal");
        require(number(inputs, "hostTgid") > 0 && number(inputs, "targetPid") > 0, "Invalid target PID");
        // The agent writes one resolved sampling object everywhere; the copies must agree exactly.
        SamplingPolicy.parse(object(start, "sampling"));
        require(object(start, "sampling").equals(object(inputs, "sampling")), "Source/footer mismatch: sampling");
        for (String key : List.of("pidNamespaceDevice", "pidNamespaceInode")) {
            require(decimal(start, key).signum() > 0, "Invalid source namespace: " + key);
        }
        decimal(start, "processGenerationNs");
        decimal(start, "timeNamespaceInode");
        decimal(start, "startedMonotonicNanos");
        if (footer != null) {
            require(bool(inputs, "clockVerified"), "Clock translation not verified");
            signedDecimal(inputs, "monotonicOffsetNanos");
            JsonObject verified = object(inputs, "verifiedIdentity");
            for (String key : List.of("pidNamespaceDevice", "pidNamespaceInode")) {
                require(decimal(start, key).equals(decimal(verified, key)), "Verified namespace mismatch: " + key);
            }
            require(
                    text(start, "registrationToken").equals(text(verified, "registrationToken")),
                    "Target binding mismatch");
            for (String key : List.of("processGenerationNs", "timeNamespaceInode")) {
                require(decimal(start, key).equals(decimal(verified, key)), "Verified identity mismatch: " + key);
            }
            require(
                    bool(verified, "clockVerified")
                            && signedDecimal(verified, "monotonicOffsetNanos")
                                    .equals(signedDecimal(inputs, "monotonicOffsetNanos")),
                    "Verified clock mismatch");
        } else {
            // A namespace identifier alone is not clock calibration or proof of an offset.
            inputs.remove("clockVerified");
            inputs.remove("monotonicOffsetNanos");
            inputs.remove("apStats");
        }
        if (end != null) {
            String state = text(end, "state");
            require(state.equals("complete") || state.equals("incomplete"), "Invalid source end state");
            boolean completeEnd = state.equals("complete");
            require(partial || completeEnd, "Incomplete capture");
            require(footer == null || completeEnd, "Finalization contradicts incomplete source end");
            if (!completeEnd) reasons.add("source-capture-incomplete");
            boolean timedOut = bool(end, "drainTimedOut");
            require(!completeEnd || !timedOut, "Source drain timed out");
            if (timedOut) reasons.add("source-drain-timed-out");
            BigInteger stopped = decimal(end, "stoppedMonotonicNanos");
            BigInteger detached = decimal(end, "detachedMonotonicNanos");
            BigInteger drained = decimal(end, "drainCompletedMonotonicNanos");
            BigInteger started = decimal(start, "startedMonotonicNanos");
            require(
                    started.equals(decimal(end, "startedMonotonicNanos"))
                            && started.compareTo(stopped) <= 0
                            && stopped.compareTo(detached) <= 0
                            && detached.compareTo(drained) <= 0,
                    "Invalid source shutdown times");
            JsonObject kernel = object(object(end, "counters"), "kernel");
            BigInteger namespaceFailures = decimal(kernel, "targetNamespaceFailures");
            require(!completeEnd || namespaceFailures.signum() == 0, "Source target namespace mapping failed");
            JsonObject counters = object(object(end, "counters"), "userspace");
            require(
                    decimal(counters, "writtenObservations").equals(BigInteger.valueOf(observations)),
                    "Source observation count mismatch");
            BigInteger received = decimal(counters, "receivedObservations");
            BigInteger written = decimal(counters, "writtenObservations");
            require(
                    completeEnd ? received.equals(written) : received.compareTo(written) >= 0,
                    "Source received/written discrepancy");
            BigInteger writeFailures = decimal(counters, "writeFailures");
            BigInteger pollFailures = decimal(counters, "pollFailures");
            require(
                    !completeEnd || writeFailures.signum() == 0 && pollFailures.signum() == 0,
                    "Source writer/poller failed");
        }
        long jfrBytes = Files.size(jfr);
        String jfrHash = digest(jfr);
        require(jfrBytes == Files.size(jfr), "Inputs changed during analysis");
        boolean jfrVerified = false;
        String apStoppedAt = null;
        if (footer != null) {
            JsonObject sourceArtifact = object(inputs, "sourceArtifact");
            require(
                    decimal(sourceArtifact, "rawBytes").equals(BigInteger.valueOf(rawBytes)),
                    "Source byte count mismatch");
            require(text(sourceArtifact, "rawSha256").equals(hex(rawHash.digest())), "Source digest mismatch");
            JsonObject jfrArtifact = object(inputs, "jfrArtifact");
            BigInteger expectedBytes = decimal(jfrArtifact, "bytes");
            String expectedHash = text(jfrArtifact, "sha256");
            require(expectedHash.matches("[0-9a-f]{64}"), "Invalid JFR digest");
            int sizeComparison = BigInteger.valueOf(jfrBytes).compareTo(expectedBytes);
            boolean exactArtifact = sizeComparison == 0 && expectedHash.equals(jfrHash);
            if (exactArtifact) {
                jfrVerified = true;
            } else if (partialJfr) {
                reasons.add("selected-jfr-differs-from-finalized-artifact");
            } else if (partial && sizeComparison < 0) {
                reasons.add("jfr-shorter-than-finalized-artifact");
            } else {
                require(sizeComparison == 0, "JFR byte count mismatch");
                require(expectedHash.equals(jfrHash), "JFR digest mismatch");
            }
            try {
                CaptureReceipt receipt = parseStopped(text(footer, "apStopResponse"));
                apStoppedAt = receipt.stoppedAt();
                require(
                        receipt.finalized()
                                && receipt.sessionId().equals(session)
                                && receipt.epoch() == epoch
                                && receipt.signal() == number(inputs, "signal")
                                && receipt.delivery().equals(delivery),
                        "AP finalization receipt mismatch");
                JsonObject stats = object(inputs, "apStats");
                for (var entry : receipt.manifestCounters().entrySet()) {
                    require(
                            decimal(stats, entry.getKey()).toString().equals(entry.getValue()),
                            "AP receipt counter mismatch");
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid AP finalization receipt", e);
            }
        }
        JsonObject diagnostics = new JsonObject();
        JsonArray incompleteReasons = new JsonArray();
        reasons.forEach(incompleteReasons::add);
        diagnostics.add("incompleteReasons", incompleteReasons);
        JsonObject sourceParse = new JsonObject();
        sourceParse.addProperty("cleanEof", trailingBytes == 0);
        sourceParse.addProperty("ignoredTrailingBytes", Integer.toString(trailingBytes));
        sourceParse.addProperty("captureEndPresent", end != null);
        sourceParse.addProperty("captureFinalizedPresent", footer != null);
        diagnostics.add("sourceParse", sourceParse);
        String sourceHash = hex(wholeHash.digest());
        JsonObject artifacts = new JsonObject();
        artifacts.add("source", observedArtifact(sourceBytes, sourceHash));
        artifacts.add("jfr", observedArtifact(jfrBytes, jfrHash));
        diagnostics.add("observedArtifacts", artifacts);
        diagnostics.addProperty(
                "artifactVerification",
                footer != null && jfrVerified ? "verified-finalized-inputs" : "unverified-incomplete");
        diagnostics.addProperty("clockVerification", footer != null ? "verified-footer" : "unavailable");
        diagnostics.addProperty("apStopVerification", footer != null ? "verified-footer" : "unavailable");
        if (footer != null) diagnostics.add("observedFinalization", footer);
        return new CaptureInput(
                start,
                end,
                inputs,
                observations,
                announcedStacks,
                sourceHash,
                jfrHash,
                apStoppedAt,
                budget,
                partial,
                diagnostics);
    }

    private record CaptureReceipt(
            String sessionId,
            int signal,
            long epoch,
            String delivery,
            boolean finalized,
            String stoppedAt,
            Map<String, String> counters) {
        Map<String, String> manifestCounters() {
            return Map.of(
                    "admittedSignals", counters.get("admitted"),
                    "invalidSignalCode", counters.get("invalid-code"),
                    "zeroCookie", counters.get("zero-cookie"),
                    "zeroSequence", counters.get("zero-sequence"),
                    "staleEpoch", counters.get("stale-epoch"),
                    "acceptedCookies", counters.get("accepted"),
                    "captureFailures", counters.get("capture-failures"),
                    "submittedSamples", counters.get("submitted"));
        }
    }

    private static String requireDelivery(String value) {
        if (!"queued".equals(value) && !"coalescing".equals(value)) {
            throw new IllegalArgumentException("Invalid signal delivery policy: " + value);
        }
        return value;
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
        String stoppedAt = unsignedDecimal(values, "stopped-at");
        Map<String, String> counters = new LinkedHashMap<>();
        for (String key : List.of(
                "admitted",
                "invalid-code",
                "zero-cookie",
                "zero-sequence",
                "stale-epoch",
                "accepted",
                "capture-failures",
                "submitted")) {
            counters.put(key, unsignedDecimal(values, key));
        }
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
                Map.copyOf(counters));
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

    private static String unsignedDecimal(Map<String, String> values, String key) {
        String value = required(values, key);
        if (!value.matches("0|[1-9][0-9]*") || new BigInteger(value).compareTo(U64_MAX) > 0) {
            throw new IllegalArgumentException("Invalid AP capture field: " + key);
        }
        return value;
    }

    private static JsonObject observedArtifact(long bytes, String hash) {
        JsonObject value = new JsonObject();
        value.addProperty("bytes", Long.toString(bytes));
        value.addProperty("sha256", hash);
        return value;
    }

    void verifyUnchanged(Path source, Path jfr) throws IOException {
        require(sourceDigest.equals(digest(source)) && jfrDigest.equals(digest(jfr)), "Inputs changed during analysis");
    }

    static void identity(JsonObject row, JsonObject inputs) throws IOException {
        require(
                text(row, "sessionId").equals(text(inputs, "sessionId"))
                        && number(row, "captureEpoch") == number(inputs, "captureEpoch"),
                "Capture identity mismatch");
    }

    /**
     * The {@code captureStart} fields an observation is validated against, checked as soon as the row is read so
     * the engine can hoist them into constants. Each of these checks also runs in its original place below; only
     * the point at which a malformed {@code captureStart} is reported moves earlier.
     */
    private static void requireStartFields(JsonObject start) throws IOException {
        require(text(start, "sourceId").equals("jonoffcpu.offcpu.v1"), "Unsupported source");
        require(text(start, "registrationToken").matches("[0-9a-f]{16}"), "Invalid process registration token");
        SamplingPolicy.parse(object(start, "sampling"));
        number(start, "hostTgid");
        number(start, "targetPid");
        decimal(start, "processGenerationNs");
        decimal(start, "startedMonotonicNanos");
    }

    /** The frames of a stack record or a JFR sample row, held to the configured per-stack limit. */
    static JsonArray frames(JsonObject row, OfflineCorrelator.Limits limits) throws IOException {
        JsonElement frames = row.get("frames");
        require(frames != null && frames.isJsonArray(), "Missing stack frames");
        JsonArray array = frames.getAsJsonArray();
        require(array.size() <= limits.maxFrames(), "Stack frame count limit exceeded");
        for (JsonElement frame : array) {
            require(frame.isJsonObject(), "Invalid stack frame");
        }
        return array;
    }

    static JsonObject object(JsonObject row, String key) throws IOException {
        JsonElement value = row.get(key);
        require(value != null && value.isJsonObject(), "Missing object: " + key);
        return value.getAsJsonObject();
    }

    static String text(JsonObject row, String key) throws IOException {
        JsonElement value = row.get(key);
        require(value instanceof JsonPrimitive && value.getAsJsonPrimitive().isString(), "Missing string: " + key);
        return value.getAsString();
    }

    private static String optionalText(JsonObject row, String key) {
        JsonElement value = row.get(key);
        return value instanceof JsonPrimitive && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    static long number(JsonObject row, String key) throws IOException {
        JsonElement value = row.get(key);
        require(value instanceof JsonPrimitive && value.getAsJsonPrimitive().isNumber(), "Missing number: " + key);
        try {
            return new BigInteger(value.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IOException("Invalid integer: " + key, e);
        }
    }

    static boolean bool(JsonObject row, String key) throws IOException {
        JsonElement value = row.get(key);
        require(value instanceof JsonPrimitive && value.getAsJsonPrimitive().isBoolean(), "Missing boolean: " + key);
        return value.getAsBoolean();
    }

    static BigInteger decimal(JsonObject row, String key) throws IOException {
        String value = text(row, key);
        require(value.matches("0|[1-9][0-9]{0,19}"), "Invalid unsigned decimal: " + key);
        BigInteger result = new BigInteger(value);
        require(result.compareTo(U64_MAX) <= 0, "Unsigned overflow: " + key);
        return result;
    }

    static BigInteger signedDecimal(JsonObject row, String key) throws IOException {
        String value = text(row, key);
        require(value.matches("0|-?[1-9][0-9]{0,19}"), "Invalid signed decimal: " + key);
        BigInteger result = new BigInteger(value);
        require(result.abs().compareTo(U64_MAX) <= 0, "Clock offset overflow");
        return result;
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

    private static JsonObject parse(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = value(reader, 0);
            require(value.isJsonObject() && reader.peek() == JsonToken.END_DOCUMENT, "Expected one JSON object");
            return value.getAsJsonObject();
        } catch (IllegalStateException | NumberFormatException e) {
            throw new IOException("Invalid source JSON", e);
        }
    }

    private static JsonElement value(JsonReader reader, int depth) throws IOException {
        require(depth <= 32, "JSON nesting limit exceeded");
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                JsonObject result = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    require(!result.has(name), "Duplicate JSON field: " + name);
                    result.add(name, value(reader, depth + 1));
                }
                reader.endObject();
                return result;
            }
            case BEGIN_ARRAY: {
                JsonArray result = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) result.add(value(reader, depth + 1));
                reader.endArray();
                return result;
            }
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER:
                return new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw new IOException("Unexpected JSON token");
        }
    }

    /**
     * Receives the capture's per-interval records while the stream is read once, so no observation or native
     * stack is retained as a decoded object. {@code start} is delivered as soon as {@code captureStart} has been
     * read and its own fields checked, because every constant an observation is validated against — the target
     * PID, the host TGID, the process generation, the registration token, the capture start time and the
     * sampling policy — is in that row. The footer copies of those values are required to be identical further
     * down, so validating an observation against {@code captureStart} is the same test.
     */
    interface SourceVisitor {
        /** Called once, before the first record, with the budget and stack-id set this read will fill. */
        void reading(Budget budget, LongIntMap announcedStacks) throws IOException;

        void start(JsonObject captureStart) throws IOException;

        void stack(long stackId, CaptureProto.Stack stack) throws IOException;

        void observation(int rowNumber, CaptureProto.Observation observation) throws IOException;
    }

    /**
     * Admission accounting for the streaming correlator: a row counter over both inputs, the conservative
     * decoded size of the few control objects that are retained, and the live size of the primitive structures
     * the engine holds.
     *
     * <p>Before the columnar engine this charged every decoded record, which made it a proxy for input size
     * rather than for retention and made {@code --max-retained-bytes} unusable as a guard: a 110 MB capture
     * charged about 8 GB. It now charges what is actually retained — control documents, columns, cookie index,
     * interned stacks, merge window — which is what the degradation ladder needs to steer on.
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

        /** Charges a control object that is retained for the whole run. */
        void charge(JsonElement value) throws IOException {
            documents = Math.addExact(documents, estimate(value));
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

        private static long estimate(JsonElement value) {
            long size = 128;
            if (value.isJsonObject()) {
                for (var entry : value.getAsJsonObject().entrySet()) {
                    size = Math.addExact(size, 128L + 2L * entry.getKey().length() + estimate(entry.getValue()));
                }
            } else if (value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) size = Math.addExact(size, estimate(item));
            } else if (value.isJsonPrimitive()) {
                size += 2L * value.getAsString().length();
            }
            return size;
        }
    }
}
