// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The stack profile's counters, accumulated during the join: one entry per distinct grouping key, however many
 * matched intervals share it. The Java stack and the switch-out reason are always part of the key; the kernel
 * stack, the user stack and the thread name are optional dimensions.
 *
 * <p>When the entry count passes the limit, the optional dimensions are dropped in the fixed order thread, user,
 * kernel, merging the entries they separated. Totals are unchanged by a drop: it only merges.
 */
final class ProfileAccumulator {
    static final String KERNEL = "kernel";
    static final String USER = "user";
    static final String THREAD = "thread";
    /** The optional dimensions, in the order they are dropped when the entry limit is reached. */
    static final List<String> DROP_ORDER = List.of(THREAD, USER, KERNEL);

    static final int DEFAULT_MAX_ENTRIES = 2_000_000;

    /** Fixed-point scale of the inverse-probability sum, as in the population estimate. */
    private static final int ESTIMATE_FRACTION_BITS = 64;

    /** The grouping the caller asked for and the entry limit that may narrow it. */
    record Options(Set<String> dimensions, int maxEntries) {
        Options {
            for (String dimension : dimensions) {
                if (!DROP_ORDER.contains(dimension)) {
                    throw new IllegalArgumentException("Unknown profile dimension: " + dimension);
                }
            }
            if (maxEntries < 1) throw new IllegalArgumentException("Profile entry limit must be positive");
            dimensions = Set.copyOf(dimensions);
        }

        static Options defaults() {
            return new Options(Set.of(KERNEL, USER, THREAD), DEFAULT_MAX_ENTRIES);
        }

        static Options parse(String groupBy, String maxEntries) {
            Set<String> dimensions = new LinkedHashSet<>();
            if (!groupBy.isEmpty() && !groupBy.equals("none")) {
                for (String dimension : groupBy.split(",", -1)) {
                    if (!dimensions.add(dimension)) {
                        throw new IllegalArgumentException("Duplicate profile dimension: " + dimension);
                    }
                }
            }
            return new Options(dimensions, Integer.parseInt(maxEntries));
        }
    }

    /** One grouping key; a dimension that is not grouped holds -1. */
    record Key(int collapsed, int kernel, int user, int reason, int taskState, int thread) {}

    /**
     * One entry's counters, kept per {@link TimeSplit.Part}: the observed nanoseconds and the fixed-point
     * inverse-probability weight of each part. An interval without the split is all unsplit, so its weight is exactly
     * the whole-duration weight it always had.
     */
    static final class Counters {
        long intervals;
        final long[] nanos = new long[3];
        final BigInteger[] weighted = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};

        void add(long[] parts, long threshold) {
            intervals++;
            BigInteger divisor = BigInteger.valueOf(threshold);
            for (int part = 0; part < 3; part++) {
                if (parts[part] == 0) continue;
                nanos[part] = Math.addExact(nanos[part], parts[part]);
                weighted[part] = weighted[part].add(BigInteger.valueOf(parts[part])
                        .shiftLeft(32 + ESTIMATE_FRACTION_BITS)
                        .divide(divisor));
            }
        }

        void add(Counters other) {
            intervals += other.intervals;
            for (int part = 0; part < 3; part++) {
                nanos[part] = Math.addExact(nanos[part], other.nanos[part]);
                weighted[part] = weighted[part].add(other.weighted[part]);
            }
        }

        long observedNanos() {
            return Math.addExact(Math.addExact(nanos[0], nanos[1]), nanos[2]);
        }

        /** The inverse-probability weighted duration, floored to whole nanoseconds. */
        BigInteger estimatedNanos() {
            return weighted[0].add(weighted[1]).add(weighted[2]).shiftRight(ESTIMATE_FRACTION_BITS);
        }

        /**
         * The parts of both totals. The estimated parts are floored cumulatively — sleeping, then sleeping plus run
         * queue, then the whole — so they add up exactly to {@link #estimatedNanos()}, and a part with no weight
         * stays exactly zero.
         */
        StackProfile.Split split() {
            BigInteger sleeping = weighted[0].shiftRight(ESTIMATE_FRACTION_BITS);
            BigInteger throughRunqueue = weighted[0].add(weighted[1]).shiftRight(ESTIMATE_FRACTION_BITS);
            BigInteger whole = estimatedNanos();
            return new StackProfile.Split(
                    nanos[0],
                    nanos[1],
                    nanos[2],
                    sleeping.longValueExact(),
                    throughRunqueue.subtract(sleeping).longValueExact(),
                    whole.subtract(throughRunqueue).longValueExact());
        }
    }

    private final Set<String> active;
    private final int maxEntries;
    private final List<String> dropped = new ArrayList<>();
    private final Map<String, Integer> threadNames = new HashMap<>();
    private final List<String> threadNameList = new ArrayList<>();
    private Map<Key, Counters> entries = new HashMap<>();

    ProfileAccumulator(Options options) {
        this.active = new LinkedHashSet<>(options.dimensions());
        this.maxEntries = options.maxEntries();
    }

    void add(
            int collapsed,
            int kernelStack,
            int userStack,
            OffCpuReason reason,
            int taskState,
            String threadName,
            long[] parts,
            long threshold) {
        int thread = -1;
        if (active.contains(THREAD) && threadName != null) {
            thread = threadNames.computeIfAbsent(threadName, name -> {
                threadNameList.add(name);
                return threadNameList.size() - 1;
            });
        }
        Key key = new Key(
                collapsed,
                active.contains(KERNEL) ? kernelStack : -1,
                active.contains(USER) ? userStack : -1,
                reason.ordinal(),
                taskState,
                thread);
        entries.computeIfAbsent(key, ignored -> new Counters()).add(parts, threshold);
        while (entries.size() > maxEntries && dropNext()) {
            // Each drop merges entries; stop when nothing optional is left to drop.
        }
    }

    private boolean dropNext() {
        for (String dimension : DROP_ORDER) {
            if (!active.remove(dimension)) continue;
            dropped.add(dimension);
            Map<Key, Counters> merged = new HashMap<>();
            for (var entry : entries.entrySet()) {
                Key key = entry.getKey();
                Key narrowed = new Key(
                        key.collapsed(),
                        active.contains(KERNEL) ? key.kernel() : -1,
                        active.contains(USER) ? key.user() : -1,
                        key.reason(),
                        key.taskState(),
                        active.contains(THREAD) ? key.thread() : -1);
                merged.computeIfAbsent(narrowed, ignored -> new Counters()).add(entry.getValue());
            }
            entries = merged;
            return true;
        }
        return false;
    }

    /**
     * Scales every entry's inverse-probability weight by {@code numerator / denominator}, the correction for an
     * accounted loss. Called once, after the last interval was added; observed nanoseconds are left alone.
     */
    void scaleEstimates(BigInteger numerator, BigInteger denominator) {
        for (Counters counters : entries.values()) {
            for (int part = 0; part < 3; part++) {
                counters.weighted[part] =
                        counters.weighted[part].multiply(numerator).divide(denominator);
            }
        }
    }

    Map<Key, Counters> entries() {
        return entries;
    }

    String threadName(int thread) {
        return thread < 0 ? null : threadNameList.get(thread);
    }

    /** The dimensions the entries are grouped by, always starting with the reason. */
    List<String> dimensions() {
        List<String> dimensions = new ArrayList<>();
        dimensions.add("reason");
        for (String dimension : List.of(KERNEL, USER, THREAD)) {
            if (active.contains(dimension)) dimensions.add(dimension);
        }
        return dimensions;
    }

    List<String> dropped() {
        return List.copyOf(dropped);
    }

    long retainedBytes() {
        return entries.size() * 240L + threadNameList.size() * 96L;
    }
}
