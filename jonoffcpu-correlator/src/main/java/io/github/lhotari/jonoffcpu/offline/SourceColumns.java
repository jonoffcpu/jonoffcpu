// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import java.util.Arrays;
import java.util.BitSet;

/**
 * One slot per observation, in capture file order, holding only what the join and the aggregates
 * read. Thirty-eight bytes a row replaces a retained Gson object graph.
 *
 * <p>Timestamps, the cookie and the admission threshold are raw u64 bits; compare them through
 * {@link U64}. The interned kernel and user stack ids and the host TID are deliberately absent:
 * they are validated where they are decoded and nothing downstream reads them, and the audit pass
 * re-reads the observation when it needs its content.
 */
final class SourceColumns {
    private long[] cookie;
    private long[] start;
    private long[] end;
    private long[] threshold;
    private int[] targetTid;
    private byte[] reason;
    private byte[] outcome;
    private final BitSet verified = new BitSet();
    private final BitSet signalFailed = new BitSet();
    private int size;

    SourceColumns(int expected) {
        int capacity = Math.max(16, expected);
        cookie = new long[capacity];
        start = new long[capacity];
        end = new long[capacity];
        threshold = new long[capacity];
        targetTid = new int[capacity];
        reason = new byte[capacity];
        outcome = new byte[capacity];
    }

    void add(
            long cookieBits,
            long startNanos,
            long endNanos,
            long admissionThreshold,
            int tid,
            boolean failedSignalRequest,
            Reason decoded) {
        if (size == cookie.length) grow();
        cookie[size] = cookieBits;
        start[size] = startNanos;
        end[size] = endNanos;
        threshold[size] = admissionThreshold;
        targetTid[size] = tid;
        signalFailed.set(size, failedSignalRequest);
        reason[size] = (byte) decoded.ordinal();
        size++;
    }

    int size() {
        return size;
    }

    long cookie(int slot) {
        return cookie[slot];
    }

    long start(int slot) {
        return start[slot];
    }

    long end(int slot) {
        return end[slot];
    }

    long threshold(int slot) {
        return threshold[slot];
    }

    int targetTid(int slot) {
        return targetTid[slot];
    }

    /** Whether the kernel recorded a nonzero {@code signalResult} for this interval. */
    boolean signalFailed(int slot) {
        return signalFailed.get(slot);
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

    boolean verified(int slot) {
        return verified.get(slot);
    }

    void verified(int slot, boolean value) {
        verified.set(slot, value);
    }

    long retainedBytes() {
        return (long) cookie.length * (Long.BYTES * 4 + Integer.BYTES + 2)
                + (verified.size() + signalFailed.size()) / 8L;
    }

    private void grow() {
        int capacity = cookie.length * 2;
        cookie = Arrays.copyOf(cookie, capacity);
        start = Arrays.copyOf(start, capacity);
        end = Arrays.copyOf(end, capacity);
        threshold = Arrays.copyOf(threshold, capacity);
        targetTid = Arrays.copyOf(targetTid, capacity);
        reason = Arrays.copyOf(reason, capacity);
        outcome = Arrays.copyOf(outcome, capacity);
    }
}
