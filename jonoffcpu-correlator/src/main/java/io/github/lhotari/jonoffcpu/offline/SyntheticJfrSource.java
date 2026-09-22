// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            String correlationId,
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

    JsonObject analysisInputs();

    String selectedObservedDurationNanos();

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
            public JsonObject analysisInputs() {
                return result.capture().inputs;
            }

            @Override
            public String selectedObservedDurationNanos() {
                return Long.toString(result.selectedObservedDurationNanos());
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
            JsonObject sample = match.sample();
            JsonArray frames = sample.getAsJsonArray("frames");
            if (frames == null) throw new IOException("Missing array: frames");
            List<Map<String, Object>> raw = new ArrayList<>(frames.size());
            for (JsonElement element : frames) {
                if (!element.isJsonObject()) throw new IOException("Invalid frame in matched sample");
                JsonObject frame = element.getAsJsonObject();
                Map<String, Object> converted = new LinkedHashMap<>();
                for (String field : List.of("type", "className", "methodName", "descriptor")) {
                    JsonElement value = frame.get(field);
                    converted.put(field, value == null || value.isJsonNull() ? null : value.getAsString());
                }
                for (String field : List.of("lineNumber", "bytecodeIndex")) {
                    JsonElement value = frame.get(field);
                    converted.put(field, value == null || value.isJsonNull() ? 0 : value.getAsInt());
                }
                raw.add(converted);
            }
            JsonElement truncated = sample.get("stackTruncated");
            int stackId = dictionaries.internStack(
                    raw, truncated != null && !truncated.isJsonNull() && truncated.getAsBoolean(), Integer.MAX_VALUE);
            int threadId = dictionaries.internThread(
                    optionalPositiveLong(sample, "osThreadId"),
                    optionalPositiveLong(sample, "javaThreadId"),
                    sample.has("threadName") && !sample.get("threadName").isJsonNull()
                            ? sample.get("threadName").getAsString()
                            : null);
            if (stackId >= stackNanos.length) stackNanos = java.util.Arrays.copyOf(stackNanos, stackId * 2 + 1);
            stackNanos[stackId] =
                    Math.addExact(stackNanos[stackId], match.durationNanos().longValueExact());
            BigInteger epochOffset = epochNanos(sample)
                    .subtract(new BigInteger(sample.get("monotonicTimeNanos").getAsString()));
            intervals.add(new Interval(
                    match.observation().get("correlationId").getAsString(),
                    match.fromNanos().longValueExact(),
                    match.toNanos().longValueExact(),
                    match.durationNanos().longValueExact(),
                    epochOffset.longValueExact(),
                    stackId,
                    threadId));
        }
        intervals.sort(Comparator.comparingLong(Interval::fromNanos)
                .thenComparingLong(Interval::toNanos)
                .thenComparing(Interval::correlationId));
        long[] weights = java.util.Arrays.copyOf(stackNanos, dictionaries.stackCount());
        List<Interval> ordered = List.copyOf(intervals);
        return new SyntheticJfrSource() {
            @Override
            public JsonObject analysisInputs() {
                return analysis.analysisInputs();
            }

            @Override
            public String selectedObservedDurationNanos() {
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

    private static BigInteger epochNanos(JsonObject sample) throws IOException {
        try {
            java.time.Instant instant =
                    java.time.Instant.parse(sample.get("startTime").getAsString());
            return BigInteger.valueOf(instant.getEpochSecond())
                    .multiply(BigInteger.valueOf(1_000_000_000L))
                    .add(BigInteger.valueOf(instant.getNano()));
        } catch (RuntimeException error) {
            throw new IOException("Invalid sample start time", error);
        }
    }

    /**
     * A stable three-key merge sort over slot indices; 1.1 M boxed comparators are not worth it. The
     * cookie comparison is unsigned, which is the same order as comparing the sixteen lowercase hex
     * digits {@code HexFormat.toHexDigits} produces.
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
