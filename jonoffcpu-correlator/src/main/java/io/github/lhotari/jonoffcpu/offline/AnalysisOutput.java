// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
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
    JsonObject analysisInputs();

    JsonObject sourceCounters();

    String apStoppedAtNanos();

    int sourceRows();

    int jfrSamples();

    int matched();

    int unmatchedSource();

    long sourceRowsWithoutSelectedJfrSample();

    int orphanJfr();

    int invalidSource();

    int invalidJfr();

    int identityUnverified();

    String selectedObservedDurationNanos();

    /** The unscaled selected observed duration of the kept subsample, before any thinning reweight. */
    long observedKeptDurationNanos();

    /** {@link Thinning#NONE} unless a correlation-time thinning stage was applied. */
    Thinning thinning();

    String submittedButNotParsed();

    /** Collapsed weights by root-first stack key, only for stacks with positive selected duration. */
    Map<String, String> collapsedNanos();

    JsonObject jfrSelection();

    OfflineCorrelator.PopulationEstimate populationEstimate();

    /** Every matched pair's delivery delay, ascending, for the report's percentiles. */
    long[] sortedHandlerDelays();

    /** The high-water mark of retained bytes this pass measured, for the degradation report; {@code 0} if unmeasured. */
    long peakRetainedBytes();

    void writeClassifiedRecords(BufferedWriter writer) throws IOException;

    void writeMatches(BufferedWriter writer) throws IOException;

    SyntheticJfrSource synthetic() throws IOException;

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

    /** The report's switch-out reason accounting, or null for a capture without classification. */
    default JsonObject offCpuReasons() {
        return null;
    }

    /** The stack profile of this analysis, or null when the view cannot produce one. */
    default StackProfile stackProfile(String reportJson) throws IOException {
        return null;
    }

    /** The retained view: every counter and row comes straight off the materialised {@link OfflineCorrelator.Analysis}. */
    static AnalysisOutput of(OfflineCorrelator.Analysis analysis) {
        return new AnalysisOutput() {
            @Override
            public JsonObject analysisInputs() {
                return analysis.analysisInputs();
            }

            @Override
            public JsonObject sourceCounters() {
                return analysis.sourceCounters();
            }

            @Override
            public String apStoppedAtNanos() {
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
                        .filter(row -> "source".equals(row.stream())
                                && "sample-not-present-in-selected-jfr".equals(row.reason()))
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
            public String selectedObservedDurationNanos() {
                return analysis.selectedObservedDurationNanos();
            }

            @Override
            public long observedKeptDurationNanos() {
                return Long.parseLong(analysis.selectedObservedDurationNanos());
            }

            @Override
            public Thinning thinning() {
                return Thinning.NONE;
            }

            @Override
            public String submittedButNotParsed() {
                return analysis.submittedButNotParsed();
            }

            @Override
            public Map<String, String> collapsedNanos() {
                return analysis.collapsedNanos();
            }

            @Override
            public JsonObject jfrSelection() {
                return analysis.jfrSelection();
            }

            @Override
            public OfflineCorrelator.PopulationEstimate populationEstimate() {
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
                Gson gson = new GsonBuilder().serializeNulls().create();
                for (var row : analysis.records()) {
                    gson.toJson(row, writer);
                    writer.newLine();
                }
            }

            @Override
            public void writeMatches(BufferedWriter writer) throws IOException {
                Gson gson = new GsonBuilder().serializeNulls().create();
                for (var match : analysis.matches()) {
                    JsonObject row = new JsonObject();
                    row.addProperty(
                            "correlationId",
                            match.observation().get("correlationId").getAsString());
                    row.addProperty("fromNanos", match.fromNanos().toString());
                    row.addProperty("toNanos", match.toNanos().toString());
                    row.addProperty("durationNanos", match.durationNanos().toString());
                    row.addProperty(
                            "handlerDelayNanos", match.handlerDelayNanos().toString());
                    row.addProperty("threadIdentityVerified", match.threadIdentityVerified());
                    gson.toJson(row, writer);
                    writer.newLine();
                }
            }

            @Override
            public SyntheticJfrSource synthetic() throws IOException {
                return SyntheticJfrSource.of(analysis);
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
            public JsonObject analysisInputs() {
                return result.capture().inputs;
            }

            @Override
            public JsonObject sourceCounters() {
                // captureEnd's "counters" object is already validated (as a nested object) while
                // reading the capture, so no second checked validation is needed here.
                return result.capture().end == null
                        ? null
                        : result.capture().end.getAsJsonObject("counters");
            }

            @Override
            public String apStoppedAtNanos() {
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
            public String selectedObservedDurationNanos() {
                return result.thinning()
                        .scale(result.selectedObservedDurationNanos())
                        .toString();
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
            public String submittedButNotParsed() {
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
            public JsonObject jfrSelection() {
                return result.selectionMetadata();
            }

            @Override
            public OfflineCorrelator.PopulationEstimate populationEstimate() {
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
                Gson gson = new GsonBuilder().serializeNulls().create();
                AuditPass.run(result, source, jfr, selection, new AuditPass.Sink() {
                    @Override
                    public void source(int row, int slot, String classification, String reason, JsonObject value)
                            throws IOException {
                        emit(gson, writer, "source", row, classification, reason, value);
                    }

                    @Override
                    public void jfr(int row, int slot, String classification, String reason, JsonObject value)
                            throws IOException {
                        emit(gson, writer, "jfr", row, classification, reason, value);
                    }
                });
            }

            @Override
            public void writeMatches(BufferedWriter writer) throws IOException {
                Gson gson = new GsonBuilder().serializeNulls().create();
                for (int slot = 0; slot < result.sources().size(); slot++) {
                    if (result.sources().outcome(slot) != Outcome.MATCHED) continue;
                    JsonObject row = new JsonObject();
                    row.addProperty("correlationId", result.correlationId(slot));
                    row.addProperty("fromNanos", Long.toString(result.fromNanos(slot)));
                    row.addProperty("toNanos", Long.toString(result.toNanos(slot)));
                    row.addProperty("durationNanos", Long.toString(result.durationNanos(slot)));
                    row.addProperty("handlerDelayNanos", Long.toString(result.handlerDelayNanos(slot)));
                    row.addProperty("threadIdentityVerified", result.sources().verified(slot));
                    gson.toJson(row, writer);
                    writer.newLine();
                }
            }

            @Override
            public SyntheticJfrSource synthetic() {
                return SyntheticJfrSource.of(result);
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
            public JsonObject offCpuReasons() {
                if (!result.sampling().classified()) return null;
                JsonObject value = new JsonObject();
                value.addProperty(
                        "semantics",
                        "switch-out reason: why the scheduler took the thread off the CPU. Each reason's time is"
                                + " further split into sleeping (a blocked interval before its wakeup) and runqueue"
                                + " (waiting for a CPU) when the capture recorded run-queue readings; see timeSplit.");
                com.google.gson.JsonArray selected = new com.google.gson.JsonArray();
                for (OffCpuReason reason : result.sampling().reasons()) selected.add(reason.label());
                value.add("selected", selected);
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
                JsonObject matched = new JsonObject();
                for (OffCpuReason reason : result.sampling().reasons()) {
                    JsonObject counts = new JsonObject();
                    counts.addProperty("intervals", Long.toString(intervals[reason.ordinal()]));
                    counts.addProperty("observedNanos", Long.toString(nanos[reason.ordinal()]));
                    for (TimeSplit.Part part : TimeSplit.Part.values()) {
                        counts.addProperty(
                                part.label() + "Nanos", Long.toString(split[reason.ordinal()][part.ordinal()]));
                    }
                    matched.add(reason.label(), counts);
                }
                value.add("matched", matched);
                TimeSplit.Source source = result.timeSplit();
                JsonObject timeSplit = new JsonObject();
                timeSplit.addProperty("source", source.label());
                timeSplit.addProperty("available", source.available());
                timeSplit.addProperty(
                        "rule",
                        "runqueue is the growth of the scheduler's sched_info.run_delay across the interval. A"
                                + " blocked interval sleeps for its duration minus that and then waits that long for"
                                + " a CPU; a runnable or preempted interval is runqueue throughout. An interval"
                                + " without a reading, or a blocked one whose reading exceeds its duration, is"
                                + " unsplit.");
                JsonObject unsplit = new JsonObject();
                unsplit.addProperty(
                        "withoutReading", Long.toString(unsplitIntervals[TimeSplit.Outcome.NO_READING.ordinal()]));
                unsplit.addProperty(
                        "readingExceedsInterval",
                        Long.toString(unsplitIntervals[TimeSplit.Outcome.EXCEEDS_INTERVAL.ordinal()]));
                timeSplit.add("unsplitIntervals", unsplit);
                JsonObject counters = result.capture().end == null
                        ? null
                        : result.capture().end.getAsJsonObject("counters").getAsJsonObject("kernel");
                com.google.gson.JsonElement inversions = counters == null ? null : counters.get("runqueueInversions");
                timeSplit.add(
                        "runqueueInversions", inversions == null ? com.google.gson.JsonNull.INSTANCE : inversions);
                value.add("timeSplit", timeSplit);
                // The kernel counts every switch-out by reason before its filter, so a blocked-only capture
                // still shows how often its threads were preempted.
                JsonObject kernel = result.capture().end == null
                        ? null
                        : result.capture().end.getAsJsonObject("counters").getAsJsonObject("kernel");
                JsonObject switchOuts = new JsonObject();
                for (String reason : List.of("blocked", "runnable", "preempted")) {
                    String key = "switchOuts" + Character.toUpperCase(reason.charAt(0)) + reason.substring(1);
                    switchOuts.add(reason, kernel == null ? com.google.gson.JsonNull.INSTANCE : kernel.get(key));
                }
                value.add("kernelSwitchOuts", switchOuts);
                for (String key : List.of("reasonRejections", "reasonRejectedDurationMicros")) {
                    value.add(key, kernel == null ? com.google.gson.JsonNull.INSTANCE : kernel.get(key));
                }
                return value;
            }

            @Override
            public StackProfile stackProfile(String reportJson) throws IOException {
                OfflineCorrelator.PopulationEstimate estimate = result.populationEstimate();
                return StackProfile.of(
                        result,
                        reportJson,
                        label,
                        result.capture().sourceDigest,
                        result.capture().jfrDigest,
                        estimate != null
                                && estimate.sourceCoverageComplete()
                                && !result.thinning().active());
            }
        };
    }

    /** Reproduces the field order Gson gives a {@link OfflineCorrelator.ClassifiedRecord} today. */
    private static void emit(
            Gson gson,
            BufferedWriter writer,
            String stream,
            int row,
            String classification,
            String reason,
            JsonObject value)
            throws IOException {
        JsonObject line = new JsonObject();
        line.addProperty("stream", stream);
        line.addProperty("row", row);
        line.addProperty("classification", classification);
        line.addProperty("reason", reason);
        line.add("record", value);
        gson.toJson(line, writer);
        writer.newLine();
    }
}
