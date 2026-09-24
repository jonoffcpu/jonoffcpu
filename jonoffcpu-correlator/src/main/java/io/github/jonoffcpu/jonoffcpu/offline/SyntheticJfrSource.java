// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the compatibility JFR writer needs from a correlation: the matched intervals in a fixed
 * order, and the interned stacks and threads they point at. Nothing document-shaped survives here,
 * so the writer no longer pins an observation and a sample per match.
 *
 * <p>Intervals arrive sorted by start, then end, then cookie. The per-stack remainder carry depends
 * on that order, so it is part of this contract.
 */
interface SyntheticJfrSource {
    record Interval(
            long correlationId,
            long fromNanos,
            long toNanos,
            long durationNanos,
            long epochOffsetNanos,
            int stackId,
            int threadId) {}

    @FunctionalInterface
    interface IntervalConsumer {
        void accept(Interval interval) throws IOException;
    }

    CaptureProto.AnalysisInputs analysisInputs();

    long selectedObservedDurationNanos();

    int intervalCount();

    int stackCount();

    /** Selected observed nanoseconds per stack id; the event count for a quantum is derivable from it. */
    long[] stackNanos();

    JfrDictionaries.Frame[] frames(int stackId);

    boolean truncated(int stackId);

    JfrDictionaries.Thread thread(int threadId);

    void forEachInterval(IntervalConsumer consumer) throws IOException;

    /** The engine's own result: intervals are read straight out of the columns. */
    static SyntheticJfrSource of(CorrelationResult result) {
        int[] order = new int[result.matched()];
        int next = 0;
        for (int slot = 0; slot < result.sources().size(); slot++) {
            if (result.sources().outcome(slot) == Outcome.MATCHED) order[next++] = slot;
        }
        sort(order, result);
        return new SyntheticJfrSource() {
            @Override
            public CaptureProto.AnalysisInputs analysisInputs() {
                return result.capture().inputs;
            }

            @Override
            public long selectedObservedDurationNanos() {
                return result.selectedObservedDurationNanos();
            }

            @Override
            public int intervalCount() {
                return order.length;
            }

            @Override
            public int stackCount() {
                return result.dictionaries().stackCount();
            }

            @Override
            public long[] stackNanos() {
                return result.stackNanos();
            }

            @Override
            public JfrDictionaries.Frame[] frames(int stackId) {
                return result.dictionaries().frames(stackId);
            }

            @Override
            public boolean truncated(int stackId) {
                return result.dictionaries().truncated(stackId);
            }

            @Override
            public JfrDictionaries.Thread thread(int threadId) {
                return result.dictionaries().thread(threadId);
            }

            @Override
            public void forEachInterval(IntervalConsumer consumer) throws IOException {
                for (int slot : order) {
                    int sample = result.matchedSample(slot);
                    consumer.accept(new Interval(
                            result.correlationId(slot),
                            result.fromNanos(slot),
                            result.toNanos(slot),
                            result.durationNanos(slot),
                            result.samples().startEpochNanos(sample)
                                    - result.samples().monotonicNanos(sample),
                            result.samples().stackId(sample),
                            result.samples().threadId(sample)));
                }
            }
        };
    }

    /** A retained analysis, for callers that build matches by hand. */
    static SyntheticJfrSource of(OfflineCorrelator.Analysis analysis) throws IOException {
        JfrDictionaries dictionaries = new JfrDictionaries();
        List<Interval> intervals = new ArrayList<>(analysis.matches().size());
        long[] stackNanos = new long[analysis.matches().size()];
        for (OfflineCorrelator.Match match : analysis.matches()) {
            validateMatch(match);
            SignalProto.SignalSample sample = match.sample();
            int stackId = dictionaries.internStack(
                    sample.getFramesList(),
                    sample.hasStackTruncated() && sample.getStackTruncated(),
                    Integer.MAX_VALUE);
            int threadId = dictionaries.internThread(
                    optionalPositive(sample.hasOsThreadId(), sample.getOsThreadId(), "osThreadId"),
                    optionalPositive(sample.hasJavaThreadId(), sample.getJavaThreadId(), "javaThreadId"),
                    sample.hasThreadName() ? sample.getThreadName() : null);
            long durationNanos = checkedLong(match.durationNanos(), "durationNanos");
            if (stackId >= stackNanos.length) stackNanos = java.util.Arrays.copyOf(stackNanos, stackId * 2 + 1);
            stackNanos[stackId] = Math.addExact(stackNanos[stackId], durationNanos);
            BigInteger epochOffset = epochNanos(sample).subtract(U64.big(sample.getMonotonicTimeNanos()));
            intervals.add(new Interval(
                    match.observation().getCorrelationId(),
                    checkedLong(match.fromNanos(), "fromNanos"),
                    checkedLong(match.toNanos(), "toNanos"),
                    durationNanos,
                    checkedLong(epochOffset, "epochOffsetNanos"),
                    stackId,
                    threadId));
        }
        intervals.sort(Comparator.comparingLong(Interval::fromNanos)
                .thenComparingLong(Interval::toNanos)
                .thenComparing(Interval::correlationId, Long::compareUnsigned));
        long[] weights = java.util.Arrays.copyOf(stackNanos, dictionaries.stackCount());
        List<Interval> ordered = List.copyOf(intervals);
        return new SyntheticJfrSource() {
            @Override
            public CaptureProto.AnalysisInputs analysisInputs() {
                return analysis.analysisInputs();
            }

            @Override
            public long selectedObservedDurationNanos() {
                return analysis.selectedObservedDurationNanos();
            }

            @Override
            public int intervalCount() {
                return ordered.size();
            }

            @Override
            public int stackCount() {
                return dictionaries.stackCount();
            }

            @Override
            public long[] stackNanos() {
                return weights;
            }

            @Override
            public JfrDictionaries.Frame[] frames(int stackId) {
                return dictionaries.frames(stackId);
            }

            @Override
            public boolean truncated(int stackId) {
                return dictionaries.truncated(stackId);
            }

            @Override
            public JfrDictionaries.Thread thread(int threadId) {
                return dictionaries.thread(threadId);
            }

            @Override
            public void forEachInterval(IntervalConsumer consumer) throws IOException {
                for (Interval interval : ordered) consumer.accept(interval);
            }
        };
    }

    /**
     * The old retained {@code CompatibilityJfrWriter.validateMatch}, unchanged: rejects a null match,
     * a null observation/sample, a null or negative {@code fromNanos}, a {@code toNanos} below it, or
     * a {@code durationNanos} that does not equal their difference.
     */
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
    }

    private static Long optionalPositive(boolean present, long value, String field) throws IOException {
        if (!present) return null;
        if (value <= 0) throw new IOException("Invalid positive integer: " + field);
        return value;
    }

    private static BigInteger epochNanos(SignalProto.SignalSample sample) {
        return BigInteger.valueOf(sample.getStartTime().getSeconds())
                .multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(sample.getStartTime().getNanos()));
    }

    private static long checkedLong(BigInteger value, String label) throws IOException {
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw new IOException(label + " does not fit signed 64-bit JFR time", e);
        }
    }

    /**
     * A stable three-key merge sort over slot indices; 1.1 M boxed comparators are not worth it. The
     * cookie comparison is unsigned, as the retained view's is.
     */
    private static void sort(int[] order, CorrelationResult result) {
        int[] buffer = new int[order.length];
        for (int width = 1; width < order.length; width *= 2) {
            for (int left = 0; left < order.length; left += width * 2) {
                int middle = Math.min(left + width, order.length);
                int right = Math.min(left + width * 2, order.length);
                int a = left;
                int b = middle;
                for (int index = left; index < right; index++) {
                    boolean takeLeft = b >= right || (a < middle && compare(result, order[a], order[b]) <= 0);
                    buffer[index] = takeLeft ? order[a++] : order[b++];
                }
            }
            System.arraycopy(buffer, 0, order, 0, order.length);
        }
    }

    private static int compare(CorrelationResult result, int left, int right) {
        int byFrom = Long.compare(result.fromNanos(left), result.fromNanos(right));
        if (byFrom != 0) return byFrom;
        int byTo = Long.compare(result.toNanos(left), result.toNanos(right));
        if (byTo != 0) return byTo;
        return Long.compareUnsigned(
                result.sources().cookie(left), result.sources().cookie(right));
    }
}
