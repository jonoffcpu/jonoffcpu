// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static io.github.lhotari.jonoffcpu.offline.CaptureInput.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Correlates a finalized JONOFFCPU correlation file with its original combined async-profiler JFR.
 * The cookie selects the match; timestamps and thread identities only validate it. Duplicate
 * cookies invalidate every copy and its counterpart. Ordinary CPU and JVM events stay untouched in
 * the original recording. No profiler exporter subprocess or external manifest is required.
 *
 * <p>Stack weights are selected observed off-CPU nanoseconds, never inverse-probability estimates.
 * An optional source-duration population estimate is kept as a separate exact rational aggregate;
 * it never changes collapsed stacks or synthetic JFR events. The JFR stack is the signal-delivery
 * stack, which may differ from the native stack captured by eBPF at scheduler exit. Neither the
 * cookie nor a small measured delay proves simultaneity. Optional windows clip durations only after
 * joining the full capture, so a delayed signal outside a measurement window can still identify an
 * interval overlapping it. Inputs must be closed before calling this bounded, offline API.
 * Resource-limit failures abort analysis.
 */
final class OfflineCorrelator {
    public record Limits(
            int maxRows,
            int maxLineBytes,
            long maxRetainedBytes,
            int maxFrames,
            BigInteger maxHandlerDelayNanos,
            BigInteger fromNanos,
            BigInteger toNanos) {
        public Limits {
            if (maxRows <= 0 || maxLineBytes <= 0 || maxRetainedBytes <= 0 || maxFrames <= 0) {
                throw new IllegalArgumentException("Resource limits must be positive");
            }
            for (BigInteger time : new BigInteger[] {maxHandlerDelayNanos, fromNanos, toNanos}) {
                if (time != null && (time.signum() < 0 || time.compareTo(U64_MAX) > 0)) {
                    throw new IllegalArgumentException("Time boundary outside u64 range");
                }
            }
            if (fromNanos != null && toNanos != null && fromNanos.compareTo(toNanos) >= 0) {
                throw new IllegalArgumentException("Measurement window must be nonempty");
            }
        }

        public static Limits defaults() {
            return new Limits(1_000_000, 1024 * 1024, 256L * 1024 * 1024, 4096, null, null, null);
        }
    }

    /**
     * Selects signal events by their JFR timestamp and optionally accepts a deliberately cut JFR
     * input.
     */
    public record JfrSelection(Instant from, Instant to, boolean partialInput) {
        public JfrSelection {
            if ((from == null) != (to == null) || from != null && !from.isBefore(to)) {
                throw new IllegalArgumentException("JFR selection range must be nonempty or omitted");
            }
        }

        boolean constrained() {
            return partialInput || from != null;
        }
    }

    public record ClassifiedRecord(String stream, int row, String classification, String reason, JsonObject record) {}

    /**
     * Full rows plus the clipped source-clock interval; useful to build explicit synthetic
     * compatibility output.
     */
    public record Match(
            JsonObject observation,
            JsonObject sample,
            BigInteger fromNanos,
            BigInteger toNanos,
            BigInteger durationNanos,
            BigInteger handlerDelayNanos,
            boolean threadIdentityVerified) {}

    /**
     * Inverse-probability source-duration estimate, kept separate from matched stack weights. Each valid source
     * row is weighted by {@code duration * 2^32 / admissionThreshold} using the exact per-row threshold the kernel
     * drew against; the sum is accumulated in exact fixed-point arithmetic and truncated to whole nanoseconds.
     */
    public record PopulationEstimate(
            String method,
            String status,
            String scope,
            String admissionPolicy,
            String sourceSelectedObservedDurationNanos,
            String matchedSelectedObservedDurationNanos,
            String sourceRowsUsed,
            String estimatedDurationNanos,
            boolean sourceCoverageComplete,
            boolean stackDeliveryCorrectionApplied,
            List<String> unavailableReasons) {}

    public record Analysis(
            int schemaVersion,
            JsonObject analysisInputs,
            JsonObject sourceCounters,
            String apStoppedAtNanos,
            int sourceRows,
            int jfrSamples,
            int matched,
            int unmatchedSource,
            int orphanJfr,
            int invalidSource,
            int invalidJfr,
            int identityUnverified,
            String selectedObservedDurationNanos,
            String submittedButNotParsed,
            Map<String, String> collapsedNanos,
            List<ClassifiedRecord> records,
            List<Match> matches,
            PopulationEstimate populationEstimate,
            JsonObject jfrSelection) {}

    /**
     * A recovered prefix is never interchangeable with a complete Analysis. All pairs are
     * provisional.
     */
    public record PartialAnalysis(
            int schemaVersion,
            JsonObject sourceCapture,
            JsonObject sourceEnd,
            JsonObject diagnostics,
            int sourceRows,
            int jfrSamples,
            int provisionalPairs,
            int unmatchedSource,
            int orphanJfr,
            int invalidSource,
            int invalidJfr,
            int identityUnverified,
            String sourceSelectedObservedDurationNanos,
            String pairedSelectedObservedDurationNanos,
            String submittedButNotParsed,
            Map<String, String> collapsedNanos,
            List<ClassifiedRecord> records,
            List<Match> pairs) {
        public String state() {
            return "incomplete";
        }

        public boolean coverageComplete() {
            return false;
        }
    }

    private record Joined(Analysis analysis, SourceAggregate sourceAggregate) {}

    private OfflineCorrelator() {}

    /**
     * Validates both complete artifacts, then returns classified rows and exact duration-weighted
     * stacks.
     */
    public static Analysis correlate(Path source, Path jfr, Limits limits) throws IOException {
        CaptureInput capture = CaptureInput.read(source, jfr, limits);
        Joined joined = correlate(capture, jfr, limits, null);
        capture.verifyUnchanged(source, jfr);
        return joined.analysis();
    }

    /** Correlates a complete source capture with a selected or deliberately shortened JFR. */
    public static Analysis correlate(Path source, Path jfr, Limits limits, JfrSelection selection) throws IOException {
        if (selection == null) {
            return correlate(source, jfr, limits);
        }
        CaptureInput capture = CaptureInput.read(source, jfr, limits, selection.partialInput());
        Joined joined = correlate(capture, jfr, limits, selection);
        capture.verifyUnchanged(source, jfr);
        return joined.analysis();
    }

    /**
     * Explicit incomplete-run API. Only fully decoded prefix records are retained; all pairs remain
     * provisional because an unread suffix could contain duplicates. No finalization or clock proof
     * is inferred from missing metadata. Semantic and resource failures remain hard errors.
     */
    public static PartialAnalysis correlatePartial(Path source, Path jfr, Limits limits) throws IOException {
        CaptureInput capture = CaptureInput.readPartial(source, jfr, limits);
        require(
                limits.maxHandlerDelayNanos() == null || capture.inputs.has("monotonicOffsetNanos"),
                "Partial analysis cannot apply a handler-delay limit without verified clock metadata");
        Joined joined = correlate(capture, jfr, limits, null);
        capture.verifyUnchanged(source, jfr);
        Analysis result = joined.analysis();
        JsonObject window = new JsonObject();
        window.addProperty(
                "fromNanos",
                limits.fromNanos() == null ? null : limits.fromNanos().toString());
        window.addProperty(
                "toNanos", limits.toNanos() == null ? null : limits.toNanos().toString());
        window.addProperty("clock", "source CLOCK_MONOTONIC; no wall-time translation");
        capture.diagnostics.add("window", window);
        return new PartialAnalysis(
                1,
                capture.start,
                capture.end,
                capture.diagnostics,
                result.sourceRows(),
                result.jfrSamples(),
                result.matched(),
                result.unmatchedSource(),
                result.orphanJfr(),
                result.invalidSource(),
                result.invalidJfr(),
                result.identityUnverified(),
                joined.sourceAggregate().duration().toString(),
                result.selectedObservedDurationNanos(),
                result.submittedButNotParsed(),
                result.collapsedNanos(),
                result.records(),
                result.matches());
    }

    private static Joined correlate(CaptureInput capture, Path jfr, Limits limits, JfrSelection selection)
            throws IOException {
        List<Row> sources = new ArrayList<>();
        SamplingPolicy sampling = capture.sampling();
        for (JsonObject observation : capture.observations) {
            Row row = new Row(sources.size() + 2, observation, capture.inputs);
            validateSource(row, capture, sampling, limits);
            sources.add(row);
        }
        List<Row> samples = new ArrayList<>();
        Gson gson = new GsonBuilder().serializeNulls().create();
        JsonObject[] observedStats = new JsonObject[1];
        SignalJfrExporter.RowConsumer consumer = raw -> {
            JsonObject row = gson.toJsonTree(raw).getAsJsonObject();
            capture.budget.charge(row);
            switch (text(row, "recordType")) {
                case "capture" -> {
                    identity(row, capture.inputs);
                    require(
                            number(row, "signal") == number(capture.inputs, "signal")
                                    && number(row, "processId") == number(capture.inputs, "targetPid"),
                            "JFR/source target mismatch");
                    require(
                            text(row, "signalDelivery").equals(text(capture.inputs, "signalDelivery")),
                            "JFR/source delivery policy mismatch");
                    if (capture.partial) capture.diagnostics.add("observedJfrCapture", row);
                }
                case "sample" -> {
                    frames(row, limits);
                    decimal(row, "monotonicTimeNanos");
                    optionalTid(row, "osThreadId");
                    samples.add(new Row(samples.size() + 1, row, capture.inputs));
                }
                case "stats" -> {
                    validateStats(row, capture.inputs);
                    observedStats[0] = row;
                    if (capture.partial) capture.diagnostics.add("observedApStats", row);
                }
                case "end" ->
                    require(
                            bool(row, "parseComplete")
                                    && decimal(row, "samples").equals(BigInteger.valueOf(samples.size())),
                            "Incomplete JFR parse");
                default -> throw new IOException("Unknown JFR row");
            }
        };
        JsonObject selectionMetadata = null;
        if (capture.partial) {
            SignalJfrExporter.PrefixOutcome outcome = SignalJfrExporter.visitPrefix(jfr, consumer);
            JsonObject parse = new JsonObject();
            parse.addProperty("cleanEof", outcome.cleanEof());
            parse.addProperty("contextPresent", outcome.contextPresent());
            parse.addProperty("terminalStatsPresent", outcome.terminalStatsPresent());
            parse.addProperty("captures", Long.toString(outcome.captures()));
            parse.addProperty("samples", Long.toString(outcome.samples()));
            parse.addProperty("readFailure", outcome.readFailure());
            capture.diagnostics.add("jfrParse", parse);
            JsonArray reasons = capture.diagnostics.getAsJsonArray("incompleteReasons");
            if (!outcome.cleanEof()) reasons.add("unreadable-jfr-tail");
            if (!outcome.contextPresent()) reasons.add("missing-ap-context");
            if (!outcome.terminalStatsPresent()) reasons.add("missing-ap-stats");
            if (reasons.isEmpty()) reasons.add("explicit-partial-mode");
        } else if (selection != null) {
            SignalJfrExporter.PrefixOutcome outcome = SignalJfrExporter.visitSelected(
                    jfr,
                    consumer,
                    new SignalJfrExporter.ReadOptions(
                            text(capture.inputs, "sessionId"),
                            number(capture.inputs, "captureEpoch"),
                            selection.from(),
                            selection.to(),
                            selection.partialInput()));
            selectionMetadata = new JsonObject();
            selectionMetadata.addProperty("partialInput", selection.partialInput());
            selectionMetadata.addProperty(
                    "from", selection.from() == null ? null : selection.from().toString());
            selectionMetadata.addProperty(
                    "to", selection.to() == null ? null : selection.to().toString());
            selectionMetadata.addProperty("boundarySemantics", "JFR event start time in half-open interval [from,to)");
            selectionMetadata.addProperty(
                    "artifactIdentityVerification",
                    selection.partialInput()
                            ? "not-required-for-deliberately-partial-input"
                            : "verified-finalized-jfr");
            selectionMetadata.addProperty("captureContextPresent", outcome.contextPresent());
            selectionMetadata.addProperty("terminalStatsPresent", outcome.terminalStatsPresent());
            selectionMetadata.addProperty("missingSourceMatchesExpected", selection.constrained());
        } else {
            SignalJfrExporter.visit(jfr, consumer);
        }
        JsonObject stats = capture.inputs.has("apStats") ? object(capture.inputs, "apStats") : observedStats[0];
        BigInteger notParsed = stats == null || selection != null
                ? null
                : decimal(stats, "submittedSamples").subtract(BigInteger.valueOf(samples.size()));
        require(notParsed == null || notParsed.signum() >= 0, "JFR samples exceed submitted samples");
        return join(capture, sources, samples, limits, notParsed, selectionMetadata);
    }

    private static void validateStats(JsonObject row, JsonObject inputs) throws IOException {
        identity(row, inputs);
        if (inputs.has("apStats")) {
            JsonObject expected = object(inputs, "apStats");
            for (var counter : expected.entrySet()) {
                require(
                        decimal(row, counter.getKey()).equals(decimal(expected, counter.getKey())),
                        "JFR counter mismatch");
            }
        }
        BigInteger admitted = BigInteger.ZERO;
        for (String key : List.of("invalidSignalCode", "zeroCookie", "zeroSequence", "staleEpoch", "acceptedCookies")) {
            admitted = admitted.add(decimal(row, key));
        }
        require(admitted.equals(decimal(row, "admittedSignals")), "AP admission counter inconsistency");
        require(
                decimal(row, "captureFailures")
                        .add(decimal(row, "submittedSamples"))
                        .equals(decimal(row, "acceptedCookies")),
                "AP submission counter inconsistency");
    }

    private static void validateSource(Row row, CaptureInput capture, SamplingPolicy sampling, Limits limits)
            throws IOException {
        JsonObject value = row.value;
        require(optionalTid(value, "targetTid") != null, "Missing target namespace TID");
        require(number(value, "targetTgid") > 0 && number(value, "targetTgid") <= 0xffffffffL, "Invalid target TGID");
        require(number(value, "hostTid") > 0 && number(value, "hostTid") <= 0xffffffffL, "Invalid host TID");
        number(value, "signalResult");
        decimal(value, "threadGenerationNs");
        BigInteger generation = decimal(value, "processGenerationNs");
        BigInteger start = decimal(value, "startMonotonicNanos");
        BigInteger end = decimal(value, "endMonotonicNanos");
        // Stacks are interned: an observation names an announced stack id, or explains why there is none.
        for (String stack : List.of("kernelStack", "userStack")) {
            long stackId = number(value, stack + "Id");
            JsonElement error = value.get(stack + "Error");
            if (error == null || error.isJsonNull()) {
                require(stackId >= 0, "Unexplained negative stack id");
                require(capture.stacks.containsKey(stackId), "Observation references an unannounced stack");
            } else {
                require(error.isJsonPrimitive() && error.getAsJsonPrimitive().isString(), "Invalid stack error");
            }
        }
        long threshold = number(value, "admissionThreshold");
        require(
                threshold >= 1 && threshold <= SamplingPolicy.CERTAIN_ADMISSION.longValueExact(),
                "Invalid admission threshold");
        BigInteger duration = end.subtract(start);
        if (start.compareTo(end) > 0) row.invalid = "negative-source-duration";
        else if (start.compareTo(decimal(capture.start, "startedMonotonicNanos")) < 0
                || capture.end != null && end.compareTo(decimal(capture.end, "detachedMonotonicNanos")) > 0) {
            row.invalid = "source-interval-outside-capture";
        } else if (number(value, "targetTgid") != number(capture.inputs, "targetPid")
                || number(value, "hostTgid") != number(capture.inputs, "hostTgid")
                // The kernel recorded the threshold it drew against; it must be the policy's value for this duration.
                || !BigInteger.valueOf(threshold).equals(sampling.admissionThreshold(duration))
                || !text(value, "sourceId").equals("jonoffcpu.offcpu.v1")
                || !generation.equals(decimal(capture.start, "processGenerationNs"))
                || !text(value, "registrationToken").equals(text(capture.start, "registrationToken"))) {
            row.invalid = "source-policy-or-target-mismatch";
        }
        if (row.invalid == null && !sampling.withinBounds(duration)) row.invalid = "duration-policy-mismatch";
    }

    private static Long optionalTid(JsonObject row, String key) throws IOException {
        require(row.has(key), "Missing thread identity: " + key);
        if (row.get(key).isJsonNull()) return null;
        long tid = number(row, key);
        require(tid > 0 && tid <= 0xffffffffL, "Invalid TID");
        return tid;
    }

    private static Joined join(
            CaptureInput capture,
            List<Row> sources,
            List<Row> samples,
            Limits limits,
            BigInteger notParsed,
            JsonObject selectionMetadata)
            throws IOException {
        Map<String, List<Row>> sourceIndex = index(sources);
        Map<String, List<Row>> sampleIndex = index(samples);
        invalidateDuplicates(sourceIndex, sourceIndex, sampleIndex);
        SourceAggregate sourceAggregate = sourceAggregate(sources, limits);
        // AP-only ambiguity invalidates the stack pair, not an independently valid source duration.
        invalidateDuplicates(sampleIndex, sourceIndex, sampleIndex);
        Map<String, BigInteger> collapsed = new TreeMap<>();
        List<Match> matches = new ArrayList<>();
        BigInteger total = BigInteger.ZERO;
        BigInteger offset = capture.inputs.has("monotonicOffsetNanos")
                ? signedDecimal(capture.inputs, "monotonicOffsetNanos")
                : null;
        int unverified = 0;
        for (Row source : sources) {
            if (offset != null
                    && capture.apStoppedAtNanos != null
                    && decimal(source.value, "endMonotonicNanos")
                                    .add(offset)
                                    .compareTo(new BigInteger(capture.apStoppedAtNanos))
                            > 0) {
                source.unmatchedReason = "source-interval-after-ap-stop";
            }
            List<Row> found = sampleIndex.get(source.cookie);
            if (found == null || found.size() != 1) {
                if (selectionMetadata != null && source.unmatchedReason == null) {
                    source.unmatchedReason = "sample-not-present-in-selected-jfr";
                }
                continue;
            }
            Row sample = found.get(0);
            if (source.invalid != null || sample.invalid != null) {
                if (source.invalid == null) source.invalid = "invalid-pair";
                if (sample.invalid == null) sample.invalid = "invalid-pair";
                continue;
            }
            if (source.unmatchedReason != null) {
                source.invalid = sample.invalid = "sample-for-source-after-ap-stop";
                continue;
            }
            BigInteger start = decimal(source.value, "startMonotonicNanos");
            BigInteger end = decimal(source.value, "endMonotonicNanos");
            BigInteger delay = offset == null
                    ? null
                    : decimal(sample.value, "monotonicTimeNanos").subtract(end).subtract(offset);
            Long targetTid = optionalTid(source.value, "targetTid");
            Long sampleTid = optionalTid(sample.value, "osThreadId");
            String invalid = null;
            if (targetTid != null && sampleTid != null && !targetTid.equals(sampleTid))
                invalid = "thread-identity-mismatch";
            else if (number(source.value, "signalResult") != 0) invalid = "sample-for-failed-signal-request";
            else if (delay != null && (delay.signum() < 0 || delay.compareTo(U64_MAX) > 0))
                invalid = "invalid-handler-delay";
            else if (delay != null
                    && limits.maxHandlerDelayNanos() != null
                    && delay.compareTo(limits.maxHandlerDelayNanos()) > 0) {
                invalid = "handler-delay-limit-exceeded";
            }
            if (invalid != null) {
                source.invalid = sample.invalid = invalid;
                continue;
            }
            source.matched = sample.matched = true;
            boolean verified = targetTid != null && sampleTid != null;
            if (!verified) unverified++;
            BigInteger from = limits.fromNanos() == null ? start : start.max(limits.fromNanos());
            BigInteger to = limits.toNanos() == null ? end : end.min(limits.toNanos());
            BigInteger duration = to.subtract(from).max(BigInteger.ZERO);
            total = total.add(duration);
            if (duration.signum() > 0) collapsed.merge(stack(sample.value), duration, BigInteger::add);
            matches.add(new Match(source.value, sample.value, from, to.max(from), duration, delay, verified));
        }
        List<ClassifiedRecord> records = new ArrayList<>();
        int invalidSource = classify("source", sources, records, capture.partial, capture.stacks);
        int invalidJfr = classify("jfr", samples, records, capture.partial, capture.stacks);
        Map<String, String> weights = new TreeMap<>();
        collapsed.forEach((key, value) -> weights.put(key, value.toString()));
        PopulationEstimate populationEstimate =
                capture.partial ? null : populationEstimate(capture, sources, sourceAggregate, total);
        Analysis analysis = new Analysis(
                1,
                capture.inputs,
                capture.end == null ? null : object(capture.end, "counters"),
                capture.apStoppedAtNanos,
                sources.size(),
                samples.size(),
                matches.size(),
                sources.size() - matches.size() - invalidSource,
                samples.size() - matches.size() - invalidJfr,
                invalidSource,
                invalidJfr,
                unverified,
                total.toString(),
                notParsed == null ? null : notParsed.toString(),
                Map.copyOf(weights),
                List.copyOf(records),
                List.copyOf(matches),
                populationEstimate,
                selectionMetadata);
        return new Joined(analysis, sourceAggregate);
    }

    private static void invalidateDuplicates(
            Map<String, List<Row>> index, Map<String, List<Row>> sources, Map<String, List<Row>> samples) {
        for (var entry : index.entrySet()) {
            if (entry.getValue().size() > 1) {
                for (Row row : sources.getOrDefault(entry.getKey(), List.of())) row.invalid = "duplicate-cookie";
                for (Row row : samples.getOrDefault(entry.getKey(), List.of())) row.invalid = "duplicate-cookie";
            }
        }
    }

    // Fixed-point scale for the inverse-probability sum: each row's truncation error is below 2^-64 ns, so the
    // truncated total is the exact floor of the true sum unless that sum lies within rows * 2^-64 ns above an
    // integer, in which case it is one nanosecond low. Exact rationals would instead need the lcm of every
    // distinct per-row threshold as a denominator.
    private static final int ESTIMATE_FRACTION_BITS = 64;

    private static SourceAggregate sourceAggregate(List<Row> sources, Limits limits) {
        BigInteger duration = BigInteger.ZERO;
        BigInteger weighted = BigInteger.ZERO;
        int rows = 0;
        for (Row source : sources) {
            if (source.invalid != null) {
                continue;
            }
            BigInteger start = decimalUnchecked(source.value, "startMonotonicNanos");
            BigInteger end = decimalUnchecked(source.value, "endMonotonicNanos");
            BigInteger from = limits.fromNanos() == null ? start : start.max(limits.fromNanos());
            BigInteger to = limits.toNanos() == null ? end : end.min(limits.toNanos());
            BigInteger selected = to.subtract(from).max(BigInteger.ZERO);
            duration = duration.add(selected);
            BigInteger threshold =
                    BigInteger.valueOf(source.value.get("admissionThreshold").getAsLong());
            weighted =
                    weighted.add(selected.shiftLeft(32 + ESTIMATE_FRACTION_BITS).divide(threshold));
            rows++;
        }
        return new SourceAggregate(duration, weighted.shiftRight(ESTIMATE_FRACTION_BITS), rows);
    }

    private static PopulationEstimate populationEstimate(
            CaptureInput capture, List<Row> sources, SourceAggregate aggregate, BigInteger matchedDuration)
            throws IOException {
        JsonObject counters = object(capture.end, "counters");
        JsonObject kernel = object(counters, "kernel");
        JsonObject userspace = object(counters, "userspace");
        List<String> reasons = new ArrayList<>();
        if (aggregate.rows() != sources.size()) {
            reasons.add("intrinsically-invalid-or-duplicate-source-rows");
        }
        BigInteger sourceRows = BigInteger.valueOf(sources.size());
        BigInteger selected = optionalCounter(kernel, "selectedIntervals", reasons);
        BigInteger eligible = optionalCounter(kernel, "eligibleIntervals", reasons);
        BigInteger rejected = optionalCounter(kernel, "admissionRejections", reasons);
        BigInteger received = optionalCounter(userspace, "receivedObservations", reasons);
        BigInteger written = optionalCounter(userspace, "writtenObservations", reasons);
        if (selected != null
                && (!selected.equals(sourceRows)
                        || received != null && !selected.equals(received)
                        || written != null && !selected.equals(written))) {
            reasons.add("selected-source-row-count-mismatch");
        }
        if (eligible != null && rejected != null && selected != null && !eligible.equals(rejected.add(selected))) {
            reasons.add("eligible-selection-counter-mismatch");
        }
        for (String key : List.of(
                "targetNamespaceFailures",
                "ringReserveFailures",
                "sequenceExhaustions",
                "sequenceContentions",
                "lifetimeRejections",
                "threadStateFailures")) {
            BigInteger value = optionalCounter(kernel, key, reasons);
            if (value != null && value.signum() != 0) {
                reasons.add("nonzero-" + key);
            }
        }
        for (String key : List.of("writeFailures", "pollFailures")) {
            BigInteger value = optionalCounter(userspace, key, reasons);
            if (value != null && value.signum() != 0) {
                reasons.add("nonzero-" + key);
            }
        }
        reasons = reasons.stream().distinct().sorted().toList();
        boolean available = reasons.isEmpty();
        return new PopulationEstimate(
                "inverse-probability-source-duration",
                available ? "available" : "unavailable",
                "completed duration-eligible source intervals",
                capture.sampling().policy(),
                aggregate.duration().toString(),
                matchedDuration.toString(),
                Integer.toString(aggregate.rows()),
                available ? aggregate.estimatedDuration().toString() : null,
                available,
                false,
                reasons);
    }

    private static BigInteger optionalCounter(JsonObject counters, String key, List<String> reasons) {
        JsonElement value = counters.get(key);
        if (value == null || value.isJsonNull()) {
            reasons.add("missing-counter-" + key);
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            reasons.add("invalid-counter-" + key);
            return null;
        }
        try {
            String text = value.getAsString();
            if (!text.matches("0|[1-9][0-9]{0,19}")) {
                throw new NumberFormatException();
            }
            BigInteger parsed = new BigInteger(text);
            if (parsed.signum() < 0 || parsed.compareTo(U64_MAX) > 0) {
                throw new NumberFormatException();
            }
            if (parsed.equals(U64_MAX)) {
                reasons.add("saturated-counter-" + key);
            }
            return parsed;
        } catch (RuntimeException error) {
            reasons.add("invalid-counter-" + key);
            return null;
        }
    }

    private static BigInteger decimalUnchecked(JsonObject value, String key) {
        return new BigInteger(value.get(key).getAsString());
    }

    private record SourceAggregate(BigInteger duration, BigInteger estimatedDuration, int rows) {}

    private static Map<String, List<Row>> index(List<Row> rows) {
        Map<String, List<Row>> index = new HashMap<>();
        for (Row row : rows)
            if (row.cookie != null)
                index.computeIfAbsent(row.cookie, ignored -> new ArrayList<>()).add(row);
        return index;
    }

    /**
     * Puts an interned stack back into the echoed row, so a classified record stays self-contained: a reader of
     * the audit file never has to resolve a stack id against the capture stream.
     */
    private static JsonObject expandStacks(JsonObject observation, Map<Long, JsonArray> stacks) {
        JsonObject expanded = observation.deepCopy();
        for (String stack : List.of("kernelStack", "userStack")) {
            JsonElement id = expanded.remove(stack + "Id");
            JsonElement error = expanded.remove(stack + "Error");
            JsonObject value = new JsonObject();
            value.add("stackId", id);
            value.add("errorCode", error == null ? JsonNull.INSTANCE : error);
            boolean failed = error != null && !error.isJsonNull();
            value.addProperty("status", failed ? "error" : "ok");
            JsonArray frames = failed ? new JsonArray() : stacks.get(id.getAsLong());
            value.add("frames", frames == null ? new JsonArray() : frames);
            expanded.add(stack, value);
        }
        return expanded;
    }

    private static int classify(
            String stream,
            List<Row> rows,
            List<ClassifiedRecord> records,
            boolean partial,
            Map<Long, JsonArray> stacks) {
        int invalid = 0;
        for (Row row : rows) {
            String classification;
            if (row.invalid != null) {
                invalid++;
                classification = "invalid";
            } else if (row.matched) classification = partial ? "provisional-match" : "matched";
            else classification = (stream.equals("source") ? "unmatched" : "orphan") + (partial ? "-prefix" : "");
            records.add(new ClassifiedRecord(
                    stream,
                    row.number,
                    classification,
                    row.invalid != null ? row.invalid : row.matched ? null : row.unmatchedReason,
                    stream.equals("source") ? expandStacks(row.value, stacks) : row.value));
        }
        return invalid;
    }

    private static String stack(JsonObject sample) {
        JsonArray frames = sample.getAsJsonArray("frames");
        if (frames.isEmpty()) return "[stack unavailable]";
        List<String> names = new ArrayList<>();
        for (int i = frames.size() - 1; i >= 0; i--) {
            JsonObject frame = frames.get(i).getAsJsonObject();
            String type = nullableText(frame, "className", "");
            String method = nullableText(frame, "methodName", "[unresolved]");
            names.add((type.isEmpty() ? method : type + "." + method)
                    .replace(';', ':')
                    .replace('\n', ' ')
                    .replace('\r', ' '));
        }
        return String.join(";", names);
    }

    private static String nullableText(JsonObject row, String key, String fallback) {
        JsonElement value = row.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static final class Row {
        final int number;
        final JsonObject value;
        String cookie;
        String invalid;
        String unmatchedReason;
        boolean matched;

        Row(int number, JsonObject value, JsonObject inputs) throws IOException {
            this.number = number;
            this.value = value;
            try {
                require(text(value, "sessionId").equals(text(inputs, "sessionId")), "Capture session mismatch");
                String candidate = text(value, "correlationId");
                require(candidate.matches("[0-9a-f]{16}"), "Invalid cookie");
                // The join key is (session, cookie). Keep even an invalid copy in its duplicate bucket.
                cookie = candidate;
                identity(value, inputs);
                long bits = Long.parseUnsignedLong(candidate, 16);
                require(
                        bits >>> 32 == number(inputs, "captureEpoch") && (bits & 0xffffffffL) != 0,
                        "Invalid cookie epoch or sequence");
            } catch (IOException e) {
                invalid = "invalid-cookie-or-capture-identity";
            }
        }
    }
}
