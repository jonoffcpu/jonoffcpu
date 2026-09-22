// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonObject;
import java.math.BigInteger;

/**
 * What one streaming correlation produced: the columns, the interned JFR dictionaries, the cookie
 * index that resolves a source slot to its sample, the per-stack weights, and the counters the
 * report prints. Nothing here is per-row Gson; the audit outputs re-read the two files.
 *
 * <p>The per-match values — the clipped interval, the delivery delay — are derived rather than
 * stored, so the audit writer, the synthetic JFR and the report cannot drift apart on the clipping
 * rule.
 */
record CorrelationResult(
        CaptureInput capture,
        JsonObject selectionMetadata,
        String submittedButNotParsed,
        SourceColumns sources,
        SampleColumns samples,
        LongIntMap sourceIndex,
        JfrDictionaries dictionaries,
        LongIntMap sampleIndex,
        long[] collapsedNanos,
        long[] stackNanos,
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
        OfflineCorrelator.PopulationEstimate populationEstimate,
        CorrelationEngine.SourceAggregate sourceAggregate,
        long peakRetainedBytes) {

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

    String correlationId(int sourceSlot) {
        return java.util.HexFormat.of().toHexDigits(sources.cookie(sourceSlot));
    }

    BigInteger selectedObservedDuration() {
        return BigInteger.valueOf(selectedObservedDurationNanos);
    }
}
