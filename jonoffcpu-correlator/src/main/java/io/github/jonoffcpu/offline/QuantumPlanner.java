// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.offline;

import java.io.IOException;

/**
 * Chooses the synthetic JFR's quantum before the first event is written.
 *
 * <p>The writer carries an integer remainder per canonical stack, so a stack contributes exactly
 * {@code floor(totalNanos / quantum)} events however its intervals are ordered, and the whole
 * recording's event count is the sum of those floors. That makes the expansion cap a question that
 * can be answered from the per-stack weights rather than discovered halfway through writing — which
 * is where it used to be discovered, after the collapsed stacks had already been published and the
 * run then failed with nothing to show for it.
 *
 * <p>A synthetic JFR is a rendering of totals the report states exactly, so a coarser quantum loses
 * granularity, not time.
 */
final class QuantumPlanner {
    private static final int MAX_ROUNDS = 64;

    record Plan(long quantumNanos, long requestedQuantumNanos, long syntheticEvents, boolean raised) {}

    private QuantumPlanner() {}

    static Plan plan(long[] stackNanos, long requestedQuantumNanos, long maxSyntheticEvents) throws IOException {
        long quantum = requestedQuantumNanos;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            long events = events(stackNanos, quantum);
            if (events <= maxSyntheticEvents) {
                return new Plan(quantum, requestedQuantumNanos, events, quantum != requestedQuantumNanos);
            }
            long factor = Math.max(2, -Math.floorDiv(-events, maxSyntheticEvents));
            if (quantum > Long.MAX_VALUE / factor) {
                throw new IOException("Synthetic event limit exceeded before output publication");
            }
            quantum *= factor;
        }
        throw new IOException("Synthetic event limit exceeded before output publication");
    }

    private static long events(long[] stackNanos, long quantum) {
        long events = 0;
        for (long nanos : stackNanos) events = Math.addExact(events, nanos / quantum);
        return events;
    }
}
