// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.offline;

import java.math.BigInteger;

/**
 * What one streaming correlation produced: the columns, the interned JFR dictionaries, the cookie
 * index that resolves a source slot to its sample, the per-stack weights, and the counters the
 * report prints. Nothing here is a per-row message; the audit outputs re-read the two files.
 *
 * <p>The per-match values — the clipped interval, the delivery delay — are derived rather than
 * stored, so the audit writer, the synthetic JFR and the report cannot drift apart on the clipping
 * rule.
 */
record CorrelationResult(
        CaptureInput capture,
        ReportProto.JfrSelection selectionMetadata,
        Long submittedButNotParsed,
        SourceColumns sources,
        SampleColumns samples,
        LongIntMap sourceIndex,
        JfrDictionaries dictionaries,
        LongIntMap sampleIndex,
        long[] collapsedNanos,
        // Per switch-out reason ordinal; null for a reason no matched interval had.
        long[][] collapsedNanosByReason,
        long[] stackNanos,
        NativeStacks nativeStacks,
        ProfileAccumulator profile,
        SamplingPolicy sampling,
        long selectedObservedDurationNanos,
        Long monotonicOffsetNanos,
        Long clipFromNanos,
        Long clipToNanos,
        int matched,
        int unmatchedSource,
        int orphanJfr,
        int invalidSource,
        int invalidJfr,
        int identityUnverified,
        int sourceRowsWithoutSelectedJfrSample,
        ReportProto.PopulationEstimate populationEstimate,
        CorrelationEngine.SourceAggregate sourceAggregate,
        long peakRetainedBytes,
        Thinning thinning,
        Long narrowedToNanos) {

    /**
     * The source-side kept-row predicate the streaming pass applied, recomputed from the same inputs.
     *
     * <p>The columns hold only the rows the engine kept, so a second read of the capture file has to
     * skip exactly the rows the first read dropped or its file-row to column-slot mapping is wrong.
     * Both terms are pure functions of the observation itself, so they are recomputable rather than
     * retained.
     */
    boolean keepsSource(long cookie, long startNanos) {
        return keepsSource(thinning, narrowedToNanos, cookie, startNanos);
    }

    /** The one definition of that predicate, so the streaming pass and the re-read cannot drift. */
    static boolean keepsSource(Thinning thinning, Long narrowedToNanos, long cookie, long startNanos) {
        boolean withinWindow = narrowedToNanos == null || startNanos < narrowedToNanos;
        return withinWindow && thinning.keeps(cookie);
    }

    /**
     * The sample-side kept-row predicate: the cookie survived thinning and its observation was kept.
     * A cookie with no source row at all is merely absent from the index, not dropped, and its sample
     * is an orphan the join still carries.
     */
    boolean keepsSample(long cookie) {
        return keepsSample(thinning, sourceIndex, cookie);
    }

    /** The one definition of that predicate, so the streaming pass and the re-read cannot drift. */
    static boolean keepsSample(Thinning thinning, LongIntMap sourceIndex, long cookie) {
        return thinning.keeps(cookie) && sourceIndex.get(cookie) != CorrelationEngine.DROPPED;
    }

    int sourceRows() {
        return sources.size();
    }

    int jfrSamples() {
        return samples.size();
    }

    /** The sample slot this source slot matched, or {@code -1}. */
    int matchedSample(int sourceSlot) {
        if (sources.outcome(sourceSlot) != Outcome.MATCHED) return -1;
        return sampleIndex.get(sources.cookie(sourceSlot));
    }

    long fromNanos(int sourceSlot) {
        long start = sources.start(sourceSlot);
        return clipFromNanos == null ? start : Math.max(start, clipFromNanos);
    }

    /** The clipped upper bound, never below the lower one, exactly as the retained join reported it. */
    long toNanos(int sourceSlot) {
        long end = sources.end(sourceSlot);
        long to = clipToNanos == null ? end : Math.min(end, clipToNanos);
        return Math.max(to, fromNanos(sourceSlot));
    }

    long durationNanos(int sourceSlot) {
        long from = fromNanos(sourceSlot);
        long end = sources.end(sourceSlot);
        long to = clipToNanos == null ? end : Math.min(end, clipToNanos);
        return to > from ? to - from : 0;
    }

    /** The delivery delay of a matched pair, or null when no verified clock offset was published. */
    Long handlerDelayNanos(int sourceSlot) {
        if (monotonicOffsetNanos == null) return null;
        int sample = matchedSample(sourceSlot);
        if (sample < 0) return null;
        return samples.monotonicNanos(sample) - sources.end(sourceSlot) - monotonicOffsetNanos;
    }

    long correlationId(int sourceSlot) {
        return sources.cookie(sourceSlot);
    }

    BigInteger selectedObservedDuration() {
        return BigInteger.valueOf(selectedObservedDurationNanos);
    }

    /** The capture's run-queue source; {@code capture_start} was validated when it was read. */
    TimeSplit.Source timeSplit() {
        try {
            return TimeSplit.source(capture.start.getTimeSplit());
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Splits a source slot's clipped interval into {@code parts}: sleeping, run queue, unsplit. */
    TimeSplit.Outcome split(int sourceSlot, long[] parts) {
        return TimeSplit.split(
                sources.offCpuReason(sourceSlot),
                sources.start(sourceSlot),
                sources.end(sourceSlot),
                sources.hasRunqueue(sourceSlot),
                sources.runqueue(sourceSlot),
                fromNanos(sourceSlot),
                toNanos(sourceSlot),
                parts);
    }
}
