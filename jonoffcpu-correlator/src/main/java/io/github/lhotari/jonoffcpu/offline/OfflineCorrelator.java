// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static io.github.lhotari.jonoffcpu.offline.CaptureInput.require;

import io.github.lhotari.jonoffcpu.capture.CaptureProto;
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

    /**
     * A matched pair with both of its rows and the clipped source-clock interval; useful to build explicit synthetic
     * compatibility output. {@code handlerDelayNanos} is null when no verified clock offset relates the clocks.
     */
    public record Match(
            CaptureProto.Observation observation,
            SignalProto.SignalSample sample,
            BigInteger fromNanos,
            BigInteger toNanos,
            BigInteger durationNanos,
            BigInteger handlerDelayNanos,
            boolean threadIdentityVerified) {
        /** The row {@code jonoffcpu-matches.jsonl} and the partial pairs file hold for this match. */
        ReportProto.Pair pair() {
            ReportProto.Pair.Builder pair = ReportProto.Pair.newBuilder()
                    .setCorrelationId(observation.getCorrelationId())
                    .setFromNanos(fromNanos.longValue())
                    .setToNanos(toNanos.longValue())
                    .setDurationNanos(durationNanos.longValue())
                    .setThreadIdentityVerified(threadIdentityVerified);
            if (handlerDelayNanos != null) pair.setHandlerDelayNanos(handlerDelayNanos.longValueExact());
            return pair.build();
        }
    }

    public record Analysis(
            CaptureProto.AnalysisInputs analysisInputs,
            ReportProto.SourceCounters sourceCounters,
            Long apStoppedAtNanos,
            int sourceRows,
            int jfrSamples,
            int matched,
            int unmatchedSource,
            int orphanJfr,
            int invalidSource,
            int invalidJfr,
            int identityUnverified,
            long selectedObservedDurationNanos,
            Long submittedButNotParsed,
            Map<String, String> collapsedNanos,
            List<ReportProto.ClassifiedRecord> records,
            List<Match> matches,
            ReportProto.PopulationEstimate populationEstimate,
            ReportProto.JfrSelection jfrSelection) {}

    /**
     * A recovered prefix is never interchangeable with a complete Analysis. All pairs are provisional; {@code
     * report} is what {@code INCOMPLETE-jonoffcpu-report.json} holds.
     */
    public record PartialAnalysis(
            ReportProto.PartialReport report,
            Map<String, String> collapsedNanos,
            List<ReportProto.ClassifiedRecord> records,
            List<Match> pairs) {
        public long provisionalPairs() {
            return report.getProvisionalPairs();
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
                limits.maxHandlerDelayNanos() == null || result.capture().monotonicOffsetNanos() != null,
                "Partial analysis cannot apply a handler-delay limit without verified clock metadata");
        Collector collector = collect(result, source, jfr, null);
        CaptureInput capture = result.capture();
        capture.verifyUnchanged(source, jfr);
        ReportProto.Window.Builder window =
                ReportProto.Window.newBuilder().setClock("source CLOCK_MONOTONIC; no wall-time translation");
        if (limits.fromNanos() != null) window.setFromNanos(limits.fromNanos().longValueExact());
        if (limits.toNanos() != null) window.setToNanos(limits.toNanos().longValueExact());
        ReportProto.PartialReport.Builder report = capture.diagnostics
                .clone()
                .setCoverageComplete(false)
                .setPairFinality("provisional-within-recovered-records")
                .setStackSemantics(OffCpuCorrelator.STACK_SEMANTICS)
                .setWeightSemantics(
                        "collapsed stacks use rounded integer microseconds; observed prefix only; no loss correction")
                .setWindow(window)
                .setSourceRows(result.sources().size())
                .setJfrSamples(result.samples().size())
                .setProvisionalPairs(result.matched())
                .setUnmatchedSource(result.unmatchedSource())
                .setOrphanJfr(result.orphanJfr())
                .setInvalidSource(result.invalidSource())
                .setInvalidJfr(result.invalidJfr())
                .setIdentityUnverified(result.identityUnverified())
                .setSourceSelectedObservedDurationNanos(
                        result.sourceAggregate().duration().longValueExact())
                .setPairedSelectedObservedDurationNanos(result.selectedObservedDurationNanos());
        if (result.submittedButNotParsed() != null) report.setSubmittedButNotParsed(result.submittedButNotParsed());
        return new PartialAnalysis(
                report.build(),
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
    private static Analysis analysis(CorrelationResult result, Collector collector) {
        CaptureInput capture = result.capture();
        return new Analysis(
                capture.inputs,
                sourceCounters(capture),
                capture.apStoppedAtNanos,
                result.sources().size(),
                result.samples().size(),
                result.matched(),
                result.unmatchedSource(),
                result.orphanJfr(),
                result.invalidSource(),
                result.invalidJfr(),
                result.identityUnverified(),
                result.selectedObservedDurationNanos(),
                result.submittedButNotParsed(),
                collapsedNanos(result),
                List.copyOf(collector.records()),
                List.copyOf(collector.matches()),
                result.populationEstimate(),
                result.selectionMetadata());
    }

    /** The collector's counters from {@code capture_end}, or null without one. */
    static ReportProto.SourceCounters sourceCounters(CaptureInput capture) {
        if (capture.end == null) return null;
        return ReportProto.SourceCounters.newBuilder()
                .setKernel(capture.end.getKernelCounters())
                .setUserspace(capture.end.getUserspaceCounters())
                .build();
    }

    /** Rebuilds the retained view: classified records in stream order and matches in source order. */
    private static final class Collector implements AuditPass.Sink {
        private final CorrelationResult result;
        private final List<ReportProto.ClassifiedRecord> records = new ArrayList<>();
        private final Map<Integer, CaptureProto.Observation> matchedObservations = new HashMap<>();
        private final Match[] matches;

        Collector(CorrelationResult result) {
            this.result = result;
            this.matches = new Match[result.sources().size()];
        }

        @Override
        public void source(int slot, ReportProto.ClassifiedRecord record) {
            records.add(record);
            if (result.sources().outcome(slot) == Outcome.MATCHED) {
                matchedObservations.put(slot, record.getSource().getObservation());
            }
        }

        @Override
        public void jfr(int slot, ReportProto.ClassifiedRecord record) {
            records.add(record);
            if (result.samples().outcome(slot) != Outcome.MATCHED) return;
            int sourceSlot = result.sourceIndex().get(result.samples().cookie(slot));
            Long delay = result.handlerDelayNanos(sourceSlot);
            matches[sourceSlot] = new Match(
                    matchedObservations.get(sourceSlot),
                    record.getJfr(),
                    BigInteger.valueOf(result.fromNanos(sourceSlot)),
                    BigInteger.valueOf(result.toNanos(sourceSlot)),
                    BigInteger.valueOf(result.durationNanos(sourceSlot)),
                    delay == null ? null : BigInteger.valueOf(delay),
                    result.sources().verified(sourceSlot));
        }

        List<ReportProto.ClassifiedRecord> records() {
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
     * the report echoes, or null for an unselected read.
     */
    static ReportProto.JfrSelection readJfr(
            CaptureInput capture, Path jfr, JfrSelection selection, SignalJfrExporter.RowConsumer consumer)
            throws IOException {
        ReportProto.JfrSelection selectionMetadata = null;
        if (capture.partial) {
            SignalJfrExporter.PrefixOutcome outcome = SignalJfrExporter.visitPrefix(jfr, consumer);
            ReportProto.JfrParse.Builder parse = ReportProto.JfrParse.newBuilder()
                    .setCleanEof(outcome.cleanEof())
                    .setContextPresent(outcome.contextPresent())
                    .setTerminalStatsPresent(outcome.terminalStatsPresent())
                    .setCaptures(outcome.captures())
                    .setSamples(outcome.samples());
            if (outcome.readFailure() != null) parse.setReadFailure(outcome.readFailure());
            // The audit pass reads the JFR a second time, which must not repeat a reason.
            ReportProto.PartialReport.Builder diagnostics = capture.diagnostics;
            diagnostics.setJfrParse(parse);
            List<ReportProto.IncompleteCause> causes = new ArrayList<>();
            if (!outcome.cleanEof()) causes.add(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_UNREADABLE_JFR_TAIL);
            if (!outcome.contextPresent()) causes.add(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_MISSING_AP_CONTEXT);
            if (!outcome.terminalStatsPresent()) {
                causes.add(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_MISSING_AP_STATS);
            }
            for (ReportProto.IncompleteCause cause : causes) {
                if (!diagnostics.getIncompleteReasonsList().contains(cause)) diagnostics.addIncompleteReasons(cause);
            }
            if (diagnostics.getIncompleteReasonsCount() == 0) {
                diagnostics.addIncompleteReasons(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_EXPLICIT_PARTIAL_MODE);
            }
        } else if (selection != null) {
            SignalJfrExporter.PrefixOutcome outcome = SignalJfrExporter.visitSelected(
                    jfr,
                    consumer,
                    new SignalJfrExporter.ReadOptions(
                            capture.sessionId(),
                            capture.captureEpoch(),
                            selection.from(),
                            selection.to(),
                            selection.partialInput()));
            ReportProto.JfrSelection.Builder metadata = ReportProto.JfrSelection.newBuilder()
                    .setPartialInput(selection.partialInput())
                    .setBoundarySemantics("JFR event start time in half-open interval [from,to)")
                    .setArtifactIdentityVerification(
                            selection.partialInput()
                                    ? "not-required-for-deliberately-partial-input"
                                    : "verified-finalized-jfr")
                    .setCaptureContextPresent(outcome.contextPresent())
                    .setTerminalStatsPresent(outcome.terminalStatsPresent())
                    .setMissingSourceMatchesExpected(selection.constrained());
            if (selection.from() != null) metadata.setFrom(selection.from().toString());
            if (selection.to() != null) metadata.setTo(selection.to().toString());
            selectionMetadata = metadata.build();
        } else {
            SignalJfrExporter.visit(jfr, consumer);
        }
        return selectionMetadata;
    }

    /** async-profiler's terminal counters must be the footer's and must add up among themselves. */
    static void validateStats(SignalProto.SignalCaptureStats row, CaptureInput capture) throws IOException {
        CaptureInput.jfrIdentity(row.getSessionId(), Integer.toUnsignedLong(row.getCaptureEpoch()), capture);
        CaptureProto.AsyncProfilerStats counters = row.getCounters();
        if (capture.apStats() != null) {
            require(counters.equals(capture.apStats()), "JFR counter mismatch");
        }
        BigInteger admitted = U64.big(counters.getInvalidSignalCode())
                .add(U64.big(counters.getZeroCookie()))
                .add(U64.big(counters.getZeroSequence()))
                .add(U64.big(counters.getStaleEpoch()))
                .add(U64.big(counters.getAcceptedCookies()));
        require(admitted.equals(U64.big(counters.getAdmittedSignals())), "AP admission counter inconsistency");
        require(
                U64.big(counters.getCaptureFailures())
                        .add(U64.big(counters.getSubmittedSamples()))
                        .equals(U64.big(counters.getAcceptedCookies())),
                "AP submission counter inconsistency");
    }
}
