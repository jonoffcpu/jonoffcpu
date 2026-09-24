// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static io.github.lhotari.jonoffcpu.offline.CaptureInput.*;

import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
import java.io.IOException;
import java.math.BigDecimal;
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
    /** The engine's columns hold clock values as signed longs, so a time boundary must fit that range. */
    private static final BigInteger SIGNED_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    public record Limits(
            int maxRows,
            int maxLineBytes,
            long maxRetainedBytes,
            int maxFrames,
            BigInteger maxHandlerDelayNanos,
            BigInteger fromNanos,
            BigInteger toNanos,
            BigDecimal maxAccountedLoss,
            int watermarkRows) {
        /**
         * How many decoded rows of either input pass between checks of retention against the budget. The degradation
         * ladder can only narrow the window at one of these watermarks, so a smaller interval narrows more finely and
         * costs more checks.
         */
        public static final int DEFAULT_WATERMARK_ROWS = 1 << 16;

        /**
         * The largest fraction of kernel-selected intervals that an exactly counted loss (sequence
         * contention) may remove before the population estimate is refused.
         */
        public static final BigDecimal DEFAULT_MAX_ACCOUNTED_LOSS = new BigDecimal("0.01");

        public Limits {
            if (maxRows <= 0 || maxLineBytes <= 0 || maxRetainedBytes <= 0 || maxFrames <= 0 || watermarkRows <= 0) {
                throw new IllegalArgumentException("Resource limits must be positive");
            }
            // Clock values are held as signed longs in the columns, so the engine's arithmetic is exact.
            for (BigInteger time : new BigInteger[] {maxHandlerDelayNanos, fromNanos, toNanos}) {
                if (time != null && (time.signum() < 0 || time.compareTo(SIGNED_MAX) > 0)) {
                    throw new IllegalArgumentException("Time boundary outside signed 64-bit nanoseconds");
                }
            }
            if (fromNanos != null && toNanos != null && fromNanos.compareTo(toNanos) >= 0) {
                throw new IllegalArgumentException("Measurement window must be nonempty");
            }
            if (maxAccountedLoss == null) {
                maxAccountedLoss = DEFAULT_MAX_ACCOUNTED_LOSS;
            }
            // A loss of every selected interval leaves nothing to scale, so the limit stays below one.
            if (maxAccountedLoss.signum() < 0 || maxAccountedLoss.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("Accounted-loss limit must be at least 0 and below 1");
            }
        }

        public Limits(
                int maxRows,
                int maxLineBytes,
                long maxRetainedBytes,
                int maxFrames,
                BigInteger maxHandlerDelayNanos,
                BigInteger fromNanos,
                BigInteger toNanos) {
            this(
                    maxRows,
                    maxLineBytes,
                    maxRetainedBytes,
                    maxFrames,
                    maxHandlerDelayNanos,
                    fromNanos,
                    toNanos,
                    DEFAULT_MAX_ACCOUNTED_LOSS);
        }

        public Limits(
                int maxRows,
                int maxLineBytes,
                long maxRetainedBytes,
                int maxFrames,
                BigInteger maxHandlerDelayNanos,
                BigInteger fromNanos,
                BigInteger toNanos,
                BigDecimal maxAccountedLoss) {
            this(
                    maxRows,
                    maxLineBytes,
                    maxRetainedBytes,
                    maxFrames,
                    maxHandlerDelayNanos,
                    fromNanos,
                    toNanos,
                    maxAccountedLoss,
                    DEFAULT_WATERMARK_ROWS);
        }

        public static Limits defaults() {
            // With streaming, row count is no longer the binding constraint, and the retention guard
            // should track the heap it protects rather than a fixed 256 MiB.
            return new Limits(100_000_000, 1024 * 1024, RetentionEstimate.budget(0), 4096, null, null, null);
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
     * When the only gap in coverage is an {@link AccountedLoss}, the sum is scaled by {@code selected / received}
     * under the independence assumption {@code assumptions} names, and {@code sourceCoverageComplete} is false.
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
            List<String> unavailableReasons,
            AccountedLoss accountedLoss,
            List<String> assumptions) {}

    /**
     * Kernel-selected intervals that never reached the capture, but whose number the kernel counted exactly:
     * {@code selectedIntervals - receivedObservations == sequenceContentions}. {@code fraction} is
     * {@code intervals / selectedIntervals}, rounded to six significant digits for display; the limit check and the
     * scaling of the estimate use the exact counters.
     */
    public record AccountedLoss(String intervals, BigDecimal fraction, String reason) {}

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

    private OfflineCorrelator() {}

    /**
     * Validates both complete artifacts, then returns classified rows and exact duration-weighted
     * stacks. The join itself is columnar; this overload materialises the per-row documents again
     * for callers that want them in memory.
     */
    public static Analysis correlate(Path source, Path jfr, Limits limits) throws IOException {
        return correlate(source, jfr, limits, null);
    }

    /** Correlates a complete source capture with a selected or deliberately shortened JFR. */
    public static Analysis correlate(Path source, Path jfr, Limits limits, JfrSelection selection) throws IOException {
        CorrelationResult result = CorrelationEngine.correlate(source, jfr, limits, selection, false);
        Collector collector = collect(result, source, jfr, selection);
        result.capture().verifyUnchanged(source, jfr);
        return analysis(result, collector);
    }

    /**
     * Explicit incomplete-run API. Only fully decoded prefix records are retained; all pairs remain
     * provisional because an unread suffix could contain duplicates. No finalization or clock proof
     * is inferred from missing metadata. Semantic and resource failures remain hard errors.
     */
    public static PartialAnalysis correlatePartial(Path source, Path jfr, Limits limits) throws IOException {
        CorrelationResult result = CorrelationEngine.correlate(source, jfr, limits, null, true);
        require(
                limits.maxHandlerDelayNanos() == null || result.capture().inputs.has("monotonicOffsetNanos"),
                "Partial analysis cannot apply a handler-delay limit without verified clock metadata");
        Collector collector = collect(result, source, jfr, null);
        CaptureInput capture = result.capture();
        capture.verifyUnchanged(source, jfr);
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
                result.sources().size(),
                result.samples().size(),
                result.matched(),
                result.unmatchedSource(),
                result.orphanJfr(),
                result.invalidSource(),
                result.invalidJfr(),
                result.identityUnverified(),
                result.sourceAggregate().duration().toString(),
                Long.toString(result.selectedObservedDurationNanos()),
                result.submittedButNotParsed(),
                collapsedNanos(result),
                List.copyOf(collector.records()),
                List.copyOf(collector.matches()));
    }

    private static Collector collect(CorrelationResult result, Path source, Path jfr, JfrSelection selection)
            throws IOException {
        Collector collector = new Collector(result);
        AuditPass.run(result, source, jfr, selection, collector);
        return collector;
    }

    /** Assembles the retained {@link Analysis} from the engine's counters and the collector's rows. */
    private static Analysis analysis(CorrelationResult result, Collector collector) throws IOException {
        CaptureInput capture = result.capture();
        return new Analysis(
                1,
                capture.inputs,
                capture.end == null ? null : object(capture.end, "counters"),
                capture.apStoppedAtNanos,
                result.sources().size(),
                result.samples().size(),
                result.matched(),
                result.unmatchedSource(),
                result.orphanJfr(),
                result.invalidSource(),
                result.invalidJfr(),
                result.identityUnverified(),
                Long.toString(result.selectedObservedDurationNanos()),
                result.submittedButNotParsed(),
                collapsedNanos(result),
                List.copyOf(collector.records()),
                List.copyOf(collector.matches()),
                result.populationEstimate(),
                result.selectionMetadata());
    }

    /** Rebuilds the retained view: classified records in stream order and matches in source order. */
    private static final class Collector implements AuditPass.Sink {
        private final CorrelationResult result;
        private final List<ClassifiedRecord> records = new ArrayList<>();
        private final Map<Integer, JsonObject> matchedObservations = new HashMap<>();
        private final Match[] matches;

        Collector(CorrelationResult result) {
            this.result = result;
            this.matches = new Match[result.sources().size()];
        }

        @Override
        public void source(int rowNumber, int slot, String classification, String reason, JsonObject observation) {
            records.add(new ClassifiedRecord("source", rowNumber, classification, reason, observation));
            if (result.sources().outcome(slot) == Outcome.MATCHED) matchedObservations.put(slot, observation);
        }

        @Override
        public void jfr(int rowNumber, int slot, String classification, String reason, JsonObject sample) {
            records.add(new ClassifiedRecord("jfr", rowNumber, classification, reason, sample));
            if (result.samples().outcome(slot) != Outcome.MATCHED) return;
            int sourceSlot = result.sourceIndex().get(result.samples().cookie(slot));
            Long delay = result.handlerDelayNanos(sourceSlot);
            matches[sourceSlot] = new Match(
                    matchedObservations.get(sourceSlot),
                    sample,
                    BigInteger.valueOf(result.fromNanos(sourceSlot)),
                    BigInteger.valueOf(result.toNanos(sourceSlot)),
                    BigInteger.valueOf(result.durationNanos(sourceSlot)),
                    delay == null ? null : BigInteger.valueOf(delay),
                    result.sources().verified(sourceSlot));
        }

        List<ClassifiedRecord> records() {
            return records;
        }

        List<Match> matches() {
            List<Match> ordered = new ArrayList<>();
            for (Match match : matches) {
                if (match != null) ordered.add(match);
            }
            return ordered;
        }
    }

    /** The collapsed weights as the retained join reported them: only stacks with positive duration. */
    static Map<String, String> collapsedNanos(CorrelationResult result) {
        Map<String, String> weights = new TreeMap<>();
        for (int id = 0; id < result.collapsedNanos().length; id++) {
            long nanos = result.collapsedNanos()[id];
            if (nanos > 0) weights.put(result.dictionaries().collapsedKey(id), Long.toString(nanos));
        }
        return Map.copyOf(weights);
    }

    /**
     * Reads the JFR through the right entry point for this run and returns the selection metadata
     * the report echoes, or null for an unselected read. The dispatch is unchanged; only its home
     * moved, so the engine can drive it.
     */
    static JsonObject readJfr(
            CaptureInput capture, Path jfr, JfrSelection selection, SignalJfrExporter.RowConsumer consumer)
            throws IOException {
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
            com.google.gson.JsonArray reasons = capture.diagnostics.getAsJsonArray("incompleteReasons");
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
        return selectionMetadata;
    }

    static void validateStats(JsonObject row, JsonObject inputs) throws IOException {
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
}
