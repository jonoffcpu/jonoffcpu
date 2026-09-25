// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import java.util.Arrays;
import java.util.BitSet;

/**
 * One slot per observation, in capture file order, holding only what the join and the aggregates
 * read. Fifty-nine bytes a row replaces a retained object graph.
 *
 * <p>Timestamps, the cookie and the admission threshold are raw u64 bits; compare them through
 * {@link U64}. The interned kernel and user stack ids are kept for the stack profile, which groups
 * by them; a stack the kernel could not take is {@link #NO_STACK}. The host TID is deliberately
 * absent: it is validated where it is decoded and nothing downstream reads it, and the audit pass
 * re-reads the observation when it needs its content.
 */
final class SourceColumns {
    /** The stack id of an observation whose kernel or user stack is missing. */
    static final int NO_STACK = -1;

    private long[] cookie;
    private long[] start;
    private long[] end;
    private long[] threshold;
    private int[] targetTid;
    private byte[] reason;
    private byte[] outcome;
    private byte[] offCpuReason;
    private int[] taskState;
    private int[] kernelStack;
    private int[] userStack;
    private long[] runqueue;
    private final BitSet verified = new BitSet();
    private final BitSet signalFailed = new BitSet();
    private final BitSet hasRunqueue = new BitSet();
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
        offCpuReason = new byte[capacity];
        taskState = new int[capacity];
        kernelStack = new int[capacity];
        userStack = new int[capacity];
        runqueue = new long[capacity];
    }

    void add(
            long cookieBits,
            long startNanos,
            long endNanos,
            long admissionThreshold,
            int tid,
            boolean failedSignalRequest,
            Reason decoded) {
        add(
                cookieBits,
                startNanos,
                endNanos,
                admissionThreshold,
                tid,
                failedSignalRequest,
                decoded,
                OffCpuReason.UNSPECIFIED,
                0,
                NO_STACK,
                NO_STACK,
                false,
                0);
    }

    void add(
            long cookieBits,
            long startNanos,
            long endNanos,
            long admissionThreshold,
            int tid,
            boolean failedSignalRequest,
            Reason decoded,
            OffCpuReason switchOut,
            int prevTaskState,
            int kernelStackId,
            int userStackId,
            boolean runqueueRecorded,
            long runqueueNanos) {
        if (size == cookie.length) grow();
        hasRunqueue.set(size, runqueueRecorded);
        runqueue[size] = runqueueNanos;
        offCpuReason[size] = (byte) switchOut.ordinal();
        taskState[size] = prevTaskState;
        kernelStack[size] = kernelStackId;
        userStack[size] = userStackId;
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

    /** Why the kernel recorded the thread leaving the CPU; unspecified for a row whose reason did not validate. */
    OffCpuReason offCpuReason(int slot) {
        return OffCpuReason.ofOrdinal(offCpuReason[slot]);
    }

    /** The raw task state {@code sched_switch} reported; 0 for runnable, preempted and unclassified rows. */
    int taskState(int slot) {
        return taskState[slot];
    }

    int kernelStack(int slot) {
        return kernelStack[slot];
    }

    int userStack(int slot) {
        return userStack[slot];
    }

    /** Whether the observation carries a run-queue reading; see {@link TimeSplit}. */
    boolean hasRunqueue(int slot) {
        return hasRunqueue.get(slot);
    }

    /** The raw u64 run-queue reading, meaningful only when {@link #hasRunqueue}. */
    long runqueue(int slot) {
        return runqueue[slot];
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
        return (long) cookie.length * (Long.BYTES * 5 + Integer.BYTES * 4 + 3)
                + (verified.size() + signalFailed.size() + hasRunqueue.size()) / 8L;
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
        offCpuReason = Arrays.copyOf(offCpuReason, capacity);
        taskState = Arrays.copyOf(taskState, capacity);
        kernelStack = Arrays.copyOf(kernelStack, capacity);
        userStack = Arrays.copyOf(userStack, capacity);
        runqueue = Arrays.copyOf(runqueue, capacity);
    }
}
