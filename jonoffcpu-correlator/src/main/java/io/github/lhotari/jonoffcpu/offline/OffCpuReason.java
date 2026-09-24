// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import io.github.lhotari.jonoffcpu.capture.CaptureProto;
import java.io.IOException;
import java.util.Locale;

/**
 * Why the scheduler took a thread off the CPU, as the kernel classified the interval at switch-out. This is
 * the switch-out reason, not a split of the interval's time: a {@link #BLOCKED} interval also contains the
 * run-queue delay between its wakeup and its switch-in.
 *
 * <p>The ordinal is the capture schema's enum number. {@link #UNSPECIFIED} is never a valid switch-out: the kernel
 * always classifies. Not to be confused with {@link Reason}, the row-invalidity vocabulary.
 */
enum OffCpuReason {
    UNSPECIFIED,
    /** Left the CPU in a waiting state: a futex, a socket, a timer, or uninterruptibly on I/O. */
    BLOCKED,
    /**
     * Left the CPU at an ordinary scheduling point while still running. This is how a user-space thread is
     * preempted by the scheduler tick, on its return to user mode, and also {@code sched_yield}.
     */
    RUNNABLE,
    /** Preempted at a preemption point inside the kernel. Like {@link #RUNNABLE}, time waiting for a CPU. */
    PREEMPTED;

    private static final OffCpuReason[] VALUES = values();

    String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    static OffCpuReason ofOrdinal(int ordinal) {
        return VALUES[ordinal];
    }

    /** The reason a wire value names, or null for a value outside the schema. */
    static OffCpuReason fromWire(int value) {
        return value >= 0 && value < VALUES.length ? VALUES[value] : null;
    }

    /** The capture schema's value for this reason. */
    CaptureProto.OffCpuReason proto() {
        return CaptureProto.OffCpuReason.forNumber(ordinal());
    }

    static OffCpuReason parse(String label) throws IOException {
        for (OffCpuReason reason : VALUES) {
            if (reason.label().equals(label)) return reason;
        }
        throw new IOException("Unknown off-CPU reason: " + label);
    }

    /** The kernel's classification from the raw {@code sched_switch} arguments. */
    static OffCpuReason classify(boolean preempted, int prevTaskState) {
        if (preempted) return PREEMPTED;
        return prevTaskState == 0 ? RUNNABLE : BLOCKED;
    }
}
