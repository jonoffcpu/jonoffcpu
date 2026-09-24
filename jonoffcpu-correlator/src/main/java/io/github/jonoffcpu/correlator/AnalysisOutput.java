// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import io.github.jonoffcpu.capture.CaptureProto;
import io.github.jonoffcpu.codec.ProtoJson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Everything the output directory is written from, so the streamed path and the retained library
 * path produce identical bytes from one writer instead of two.
 *
 * <p>The per-row audit files are written through callbacks rather than returned as lists: the
 * streamed implementation decodes each row from a second read of the two files and lets it go
 * again, which is the whole point of the second pass.
 */
interface AnalysisOutput {
    CaptureProto.AnalysisInputs analysisInputs();

    ReportProto.SourceCounters sourceCounters();

    Long apStoppedAtNanos();

    int sourceRows();

    int jfrSamples();

    int matched();

    int unmatchedSource();

    long sourceRowsWithoutSelectedJfrSample();

    int orphanJfr();

    int invalidSource();

    int invalidJfr();

    int identityUnverified();

    long selectedObservedDurationNanos();

    /** The unscaled selected observed duration of the kept subsample, before any thinning reweight. */
    long observedKeptDurationNanos();

    /** {@link Thinning#NONE} unless a correlation-time thinning stage was applied. */
    Thinning thinning();

    Long submittedButNotParsed();

    /** Collapsed weights by root-first stack key, only for stacks with positive selected duration. */
    Map<String, String> collapsedNanos();

    ReportProto.JfrSelection jfrSelection();

    ReportProto.PopulationEstimate populationEstimate();

    /** Every matched pair's delivery delay, ascending, for the report's percentiles. */
    long[] sortedHandlerDelays();

    /** The high-water mark of retained bytes this pass measured, for the degradation report; {@code 0} if unmeasured. */
    long peakRetainedBytes();

    void writeClassifiedRecords(BufferedWriter writer) throws IOException;

    void writeMatches(BufferedWriter writer) throws IOException;

    /**
     * The switch-out reasons with a positive selected duration, in canonical order. Empty for the retained view,
     * which predates the classification.
     */
    default List<OffCpuReason> reasonsPresent() {
        return List.of();
    }

    /**
     * Collapsed weights of one reason ({@code only}), or of every reason when {@code only} is null, in which case
     * each line starts with its {@code [offcpu: reason]} frame so that the reasons stay apart.
     */
    default Map<String, String> collapsedNanos(OffCpuReason only) {
        throw new UnsupportedOperationException("Per-reason collapsed stacks need the streamed view");
    }

    /** The report's switch-out reason accounting, or null when the view cannot produce it. */
    default ReportProto.OffCpuReasons offCpuReasons() {
        return null;
    }

    /** The stack profile of this analysis, or null when the view cannot produce one. */
    default StackProfile stackProfile() throws IOException {
        return null;
    }

    /** The retained view: every counter and row comes straight off the materialised {@link OfflineCorrelator.Analysis}. */
    static AnalysisOutput of(OfflineCorrelator.Analysis analysis) {
        return new AnalysisOutput() {
            @Override
            public CaptureProto.AnalysisInputs analysisInputs() {
                return analysis.analysisInputs();
            }

            @Override
            public ReportProto.SourceCounters sourceCounters() {
                return analysis.sourceCounters();
            }

            @Override
            public Long apStoppedAtNanos() {
                return analysis.apStoppedAtNanos();
            }

            @Override
            public int sourceRows() {
                return analysis.sourceRows();
            }

            @Override
            public int jfrSamples() {
                return analysis.jfrSamples();
            }

            @Override
            public int matched() {
                return analysis.matched();
            }

            @Override
            public int unmatchedSource() {
                return analysis.unmatchedSource();
            }

            @Override
            public long sourceRowsWithoutSelectedJfrSample() {
                return analysis.records().stream()
                        .filter(row -> row.hasSource()
                                && row.getReason()
                                        == ReportProto.RowReason.ROW_REASON_SAMPLE_NOT_PRESENT_IN_SELECTED_JFR)
                        .count();
            }

            @Override
            public int orphanJfr() {
                return analysis.orphanJfr();
            }

            @Override
            public int invalidSource() {
                return analysis.invalidSource();
            }

            @Override
            public int invalidJfr() {
                return analysis.invalidJfr();
            }

            @Override
            public int identityUnverified() {
                return analysis.identityUnverified();
            }

            @Override
            public long selectedObservedDurationNanos() {
                return analysis.selectedObservedDurationNanos();
            }

            @Override
            public long observedKeptDurationNanos() {
                return analysis.selectedObservedDurationNanos();
            }

            @Override
            public Thinning thinning() {
                return Thinning.NONE;
            }

            @Override
            public Long submittedButNotParsed() {
                return analysis.submittedButNotParsed();
            }

            @Override
            public Map<String, String> collapsedNanos() {
                return analysis.collapsedNanos();
            }

            @Override
            public ReportProto.JfrSelection jfrSelection() {
                return analysis.jfrSelection();
            }

            @Override
            public ReportProto.PopulationEstimate populationEstimate() {
                return analysis.populationEstimate();
            }

            @Override
            public long[] sortedHandlerDelays() {
                return analysis.matches().stream()
                        .mapToLong(match -> match.handlerDelayNanos().longValueExact())
                        .sorted()
                        .toArray();
            }

            @Override
            public long peakRetainedBytes() {
                // The retained/materialised path keeps everything in memory; it does not stream under a
                // budget watermark, so no peak was measured.
                return 0L;
            }

            @Override
            public void writeClassifiedRecords(BufferedWriter writer) throws IOException {
                for (var row : analysis.records()) line(writer, row);
            }

            @Override
            public void writeMatches(BufferedWriter writer) throws IOException {
                for (var match : analysis.matches()) line(writer, match.pair());
            }
        };
    }

    /** The streamed view: counters come off the columns, and the per-row output re-reads the two files. */
    static AnalysisOutput of(
            CorrelationResult result, Path source, Path jfr, OfflineCorrelator.JfrSelection selection) {
        return of(result, source, jfr, selection, null);
    }

    /**
     * The streamed view, labelling every collapsed line with what was done to produce it: thinning
     * (a stated estimator over the whole window) and/or window narrowing ({@code narrowedToNanos}, a
     * complete answer over less of it).
     */
    static AnalysisOutput of(
            CorrelationResult result,
            Path source,
            Path jfr,
            OfflineCorrelator.JfrSelection selection,
            Long narrowedToNanos) {
        String label = (result.thinning().active()
                        ? "[thinned q=" + result.thinning().probability() + "; inverse-probability estimate];"
                        : "")
                + (narrowedToNanos == null ? "" : "[INCOMPLETE capture: window narrowed to " + narrowedToNanos + "];");
        return new AnalysisOutput() {
            @Override
            public CaptureProto.AnalysisInputs analysisInputs() {
                return result.capture().inputs;
            }

            @Override
            public ReportProto.SourceCounters sourceCounters() {
                return OfflineCorrelator.sourceCounters(result.capture());
            }

            @Override
            public Long apStoppedAtNanos() {
                return result.capture().apStoppedAtNanos;
            }

            @Override
            public int sourceRows() {
                return result.sourceRows();
            }

            @Override
            public int jfrSamples() {
                return result.jfrSamples();
            }

            @Override
            public int matched() {
                return result.matched();
            }

            @Override
            public int unmatchedSource() {
                return result.unmatchedSource();
            }

            @Override
            public long sourceRowsWithoutSelectedJfrSample() {
                return result.sourceRowsWithoutSelectedJfrSample();
            }

            @Override
            public int orphanJfr() {
                return result.orphanJfr();
            }

            @Override
            public int invalidSource() {
                return result.invalidSource();
            }

            @Override
            public int invalidJfr() {
                return result.invalidJfr();
            }

            @Override
            public int identityUnverified() {
                return result.identityUnverified();
            }

            @Override
            public long selectedObservedDurationNanos() {
                return result.thinning()
                        .scale(result.selectedObservedDurationNanos())
                        .longValueExact();
            }

            @Override
            public long observedKeptDurationNanos() {
                return result.selectedObservedDurationNanos();
            }

            @Override
            public Thinning thinning() {
                return result.thinning();
            }

            @Override
            public Long submittedButNotParsed() {
                return result.submittedButNotParsed();
            }

            @Override
            public Map<String, String> collapsedNanos() {
                Map<String, String> weights = new java.util.TreeMap<>();
                for (int id = 0; id < result.collapsedNanos().length; id++) {
                    long nanos = result.collapsedNanos()[id];
                    if (nanos > 0) {
                        weights.put(
                                label + result.dictionaries().collapsedKey(id),
                                result.thinning().scale(nanos).toString());
                    }
                }
                return weights;
            }

            @Override
            public ReportProto.JfrSelection jfrSelection() {
                return result.selectionMetadata();
            }

            @Override
            public ReportProto.PopulationEstimate populationEstimate() {
                return result.populationEstimate();
            }

            @Override
            public long[] sortedHandlerDelays() {
                long[] delays = new long[result.matched()];
                int next = 0;
                for (int slot = 0; slot < result.sources().size(); slot++) {
                    if (result.sources().outcome(slot) == Outcome.MATCHED) {
                        delays[next++] = result.handlerDelayNanos(slot);
                    }
                }
                Arrays.sort(delays);
                return delays;
            }

            @Override
            public long peakRetainedBytes() {
                return result.peakRetainedBytes();
            }

            @Override
            public void writeClassifiedRecords(BufferedWriter writer) throws IOException {
                AuditPass.run(result, source, jfr, selection, new AuditPass.Sink() {
                    @Override
                    public void source(int slot, ReportProto.ClassifiedRecord record) throws IOException {
                        line(writer, record);
                    }

                    @Override
                    public void jfr(int slot, ReportProto.ClassifiedRecord record) throws IOException {
                        line(writer, record);
                    }
                });
            }

            @Override
            public void writeMatches(BufferedWriter writer) throws IOException {
                for (int slot = 0; slot < result.sources().size(); slot++) {
                    if (result.sources().outcome(slot) != Outcome.MATCHED) continue;
                    ReportProto.Pair.Builder row = ReportProto.Pair.newBuilder()
                            .setCorrelationId(result.correlationId(slot))
                            .setFromNanos(result.fromNanos(slot))
                            .setToNanos(result.toNanos(slot))
                            .setDurationNanos(result.durationNanos(slot))
                            .setThreadIdentityVerified(result.sources().verified(slot));
                    Long delay = result.handlerDelayNanos(slot);
                    if (delay != null) row.setHandlerDelayNanos(delay);
                    line(writer, row.build());
                }
            }

            @Override
            public List<OffCpuReason> reasonsPresent() {
                List<OffCpuReason> present = new ArrayList<>();
                for (OffCpuReason reason : OffCpuReason.values()) {
                    // A reason's array exists only once an interval of it had a positive duration.
                    if (result.collapsedNanosByReason()[reason.ordinal()] != null) present.add(reason);
                }
                return present;
            }

            @Override
            public Map<String, String> collapsedNanos(OffCpuReason only) {
                Map<String, String> weights = new java.util.TreeMap<>();
                for (OffCpuReason reason : OffCpuReason.values()) {
                    long[] nanos = result.collapsedNanosByReason()[reason.ordinal()];
                    if (nanos == null || only != null && reason != only) continue;
                    String prefix = only == null ? label + StackProfileRenderer.reasonFrame(reason) + ";" : label;
                    for (int id = 0; id < nanos.length; id++) {
                        if (nanos[id] > 0) {
                            weights.put(
                                    prefix + result.dictionaries().collapsedKey(id),
                                    result.thinning().scale(nanos[id]).toString());
                        }
                    }
                }
                return weights;
            }

            @Override
            public ReportProto.OffCpuReasons offCpuReasons() {
                ReportProto.OffCpuReasons.Builder value = ReportProto.OffCpuReasons.newBuilder()
                        .setSemantics("switch-out reason: why the scheduler took the thread off the CPU. Each reason's"
                                + " time is further split into sleeping (a blocked interval before its wakeup) and"
                                + " runqueue (waiting for a CPU) when the capture recorded run-queue readings; see"
                                + " timeSplit.");
                for (OffCpuReason reason : result.sampling().reasons()) value.addSelected(reason.proto());
                int reasons = OffCpuReason.values().length;
                long[] intervals = new long[reasons];
                long[] nanos = new long[reasons];
                long[][] split = new long[reasons][3];
                long[] unsplitIntervals = new long[TimeSplit.Outcome.values().length];
                long[] parts = new long[3];
                for (int slot = 0; slot < result.sources().size(); slot++) {
                    if (result.sources().outcome(slot) != Outcome.MATCHED) continue;
                    int reason = result.sources().offCpuReason(slot).ordinal();
                    intervals[reason]++;
                    nanos[reason] = Math.addExact(nanos[reason], result.durationNanos(slot));
                    unsplitIntervals[result.split(slot, parts).ordinal()]++;
                    for (int part = 0; part < 3; part++) {
                        split[reason][part] = Math.addExact(split[reason][part], parts[part]);
                    }
                }
                for (OffCpuReason reason : result.sampling().reasons()) {
                    int index = reason.ordinal();
                    value.addMatched(ReportProto.ReasonTotals.newBuilder()
                            .setReason(reason.proto())
                            .setIntervals(intervals[index])
                            .setObservedNanos(nanos[index])
                            .setSleepingNanos(split[index][TimeSplit.Part.SLEEPING.ordinal()])
                            .setRunqueueNanos(split[index][TimeSplit.Part.RUNQUEUE.ordinal()])
                            .setUnsplitNanos(split[index][TimeSplit.Part.UNSPLIT.ordinal()]));
                }
                // The kernel counts every switch-out by reason before its filter, so a blocked-only capture
                // still shows how often its threads were preempted.
                CaptureProto.KernelCounters kernel = result.capture().end == null
                        ? CaptureProto.KernelCounters.getDefaultInstance()
                        : result.capture().end.getKernelCounters();
                TimeSplit.Source source = result.timeSplit();
                value.setTimeSplit(ReportProto.TimeSplitReport.newBuilder()
                        .setSource(source.proto())
                        .setAvailable(source.available())
                        .setRule("runqueue is the growth of the scheduler's sched_info.run_delay across the"
                                + " interval. A blocked interval sleeps for its duration minus that and then waits"
                                + " that long for a CPU; a runnable or preempted interval is runqueue throughout. An"
                                + " interval without a reading, or a blocked one whose reading exceeds its duration,"
                                + " is unsplit.")
                        .setUnsplitIntervals(ReportProto.UnsplitIntervals.newBuilder()
                                .setWithoutReading(unsplitIntervals[TimeSplit.Outcome.NO_READING.ordinal()])
                                .setReadingExceedsInterval(
                                        unsplitIntervals[TimeSplit.Outcome.EXCEEDS_INTERVAL.ordinal()]))
                        .setRunqueueInversions(kernel.getRunqueueInversions()));
                value.setKernelSwitchOuts(ReportProto.KernelSwitchOuts.newBuilder()
                        .setBlocked(kernel.getSwitchOutsBlocked())
                        .setRunnable(kernel.getSwitchOutsRunnable())
                        .setPreempted(kernel.getSwitchOutsPreempted()));
                value.setReasonRejections(kernel.getReasonRejections());
                value.setReasonRejectedDurationMicros(kernel.getReasonRejectedDurationMicros());
                return value.build();
            }

            @Override
            public StackProfile stackProfile() throws IOException {
                ReportProto.PopulationEstimate estimate = result.populationEstimate();
                return StackProfile.of(
                        result,
                        label,
                        result.capture().sourceDigest,
                        result.capture().jfrDigest,
                        estimate != null
                                && estimate.getStatus() == ReportProto.EstimateStatus.ESTIMATE_STATUS_AVAILABLE
                                && !result.thinning().active());
            }
        };
    }

    /** One message as one line of JSON Lines. */
    private static void line(BufferedWriter writer, com.google.protobuf.Message message) throws IOException {
        writer.write(ProtoJson.line(message));
        writer.newLine();
    }
}
