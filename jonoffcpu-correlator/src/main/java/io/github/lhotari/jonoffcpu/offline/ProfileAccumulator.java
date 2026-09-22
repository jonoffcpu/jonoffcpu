// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

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

    static final class Counters {
        long intervals;
        long observedNanos;
        BigInteger weighted = BigInteger.ZERO;

        void add(long durationNanos, long threshold) {
            intervals++;
            observedNanos = Math.addExact(observedNanos, durationNanos);
            weighted = weighted.add(BigInteger.valueOf(durationNanos)
                    .shiftLeft(32 + ESTIMATE_FRACTION_BITS)
                    .divide(BigInteger.valueOf(threshold)));
        }

        void add(Counters other) {
            intervals += other.intervals;
            observedNanos = Math.addExact(observedNanos, other.observedNanos);
            weighted = weighted.add(other.weighted);
        }

        /** The inverse-probability weighted duration, floored to whole nanoseconds. */
        BigInteger estimatedNanos() {
            return weighted.shiftRight(ESTIMATE_FRACTION_BITS);
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
            long durationNanos,
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
        entries.computeIfAbsent(key, ignored -> new Counters()).add(durationNanos, threshold);
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
        return entries.size() * 160L + threadNameList.size() * 96L;
    }
}
