// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import java.util.Arrays;

/**
 * One slot per {@code profiler.SignalSample}, in JFR read order. The frames and the thread
 * identity live in {@link JfrDictionaries}; a sample keeps their ids.
 *
 * <p>{@code startEpochNanos} is the sample's JFR event time resolved to nanoseconds since the
 * epoch. Only matched samples need it, and only for the synthetic JFR, but which samples match is
 * not known until the join, so it is resolved once here rather than by parsing the instant again.
 * {@code osThreadId} is duplicated out of the thread dictionary because the join's thread-identity
 * check reads it for every candidate pair.
 */
final class SampleColumns {
    private long[] cookie;
    private long[] monotonicNanos;
    private long[] startEpochNanos;
    private int[] osThreadId;
    private int[] stackId;
    private int[] threadId;
    private byte[] reason;
    private byte[] outcome;
    private int size;

    SampleColumns(int expected) {
        int capacity = Math.max(16, expected);
        cookie = new long[capacity];
        monotonicNanos = new long[capacity];
        startEpochNanos = new long[capacity];
        osThreadId = new int[capacity];
        stackId = new int[capacity];
        threadId = new int[capacity];
        reason = new byte[capacity];
        outcome = new byte[capacity];
    }

    void add(long cookieBits, long monotonic, long startEpoch, int tid, int stack, int thread) {
        if (size == cookie.length) grow();
        cookie[size] = cookieBits;
        monotonicNanos[size] = monotonic;
        startEpochNanos[size] = startEpoch;
        osThreadId[size] = tid;
        stackId[size] = stack;
        threadId[size] = thread;
        size++;
    }

    int size() {
        return size;
    }

    long cookie(int slot) {
        return cookie[slot];
    }

    long monotonicNanos(int slot) {
        return monotonicNanos[slot];
    }

    long startEpochNanos(int slot) {
        return startEpochNanos[slot];
    }

    /** The sample's OS thread id, or zero when the recording carried no thread for it. */
    int osThreadId(int slot) {
        return osThreadId[slot];
    }

    int stackId(int slot) {
        return stackId[slot];
    }

    int threadId(int slot) {
        return threadId[slot];
    }

    Reason reason(int slot) {
        return Reason.of(reason[slot]);
    }

    void reason(int slot, Reason value) {
        reason[slot] = (byte) value.ordinal();
    }

    Outcome outcome(int slot) {
        return Outcome.of(outcome[slot]);
    }

    void outcome(int slot, Outcome value) {
        outcome[slot] = (byte) value.ordinal();
    }

    long retainedBytes() {
        return (long) cookie.length * (Long.BYTES * 3 + Integer.BYTES * 3 + 2);
    }

    private void grow() {
        int capacity = cookie.length * 2;
        cookie = Arrays.copyOf(cookie, capacity);
        monotonicNanos = Arrays.copyOf(monotonicNanos, capacity);
        startEpochNanos = Arrays.copyOf(startEpochNanos, capacity);
        osThreadId = Arrays.copyOf(osThreadId, capacity);
        stackId = Arrays.copyOf(stackId, capacity);
        threadId = Arrays.copyOf(threadId, capacity);
        reason = Arrays.copyOf(reason, capacity);
        outcome = Arrays.copyOf(outcome, capacity);
    }
}
