// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static io.github.lhotari.jonoffcpu.offline.CaptureInput.require;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Locale;

/**
 * The sleeping/run-queue split of an off-CPU interval, and the one rule that applies it.
 *
 * <p>A schemaVersion 4 capture with {@code timeSplit.source} {@code schedInfo} records on each observation the
 * growth of the scheduler's own {@code sched_info.run_delay} across the interval: how long the thread waited on a
 * run queue. A blocked interval sleeps until its wakeup and then waits for a CPU, so its run-queue part is its tail
 * {@code [end - runqueue, end]} and the rest is sleeping. A runnable or preempted interval never left the run queue:
 * it is run-queue time throughout, whatever the reading says (the scheduler's clock can be a few microseconds stale
 * when a runnable task departs, so its reading may slightly exceed the duration). An interval without a reading, or
 * a blocked one whose reading exceeds its duration, is unsplit: nothing is guessed or clamped.
 *
 * <p>Each part is clipped to the analysis window separately, so for every interval {@code sleeping + runqueue +
 * unsplit} is exactly its clipped duration.
 */
final class TimeSplit {
    private TimeSplit() {}

    /** The three parts an interval's time is split into. */
    enum Part {
        SLEEPING,
        RUNQUEUE,
        UNSPLIT;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Part parse(String value) {
            for (Part part : values()) {
                if (part.label().equals(value)) return part;
            }
            throw new IllegalArgumentException("Unknown time part: " + value);
        }
    }

    /** Where a capture's run-queue readings come from. */
    enum Source {
        /** A capture older than schemaVersion 4, recorded before the split existed. */
        NOT_RECORDED("notRecorded"),
        /** {@code timeSplit.source: off}: the capture was told not to read anything. */
        OFF("off"),
        /** {@code timeSplit.source: schedInfo}: each observation may carry its run-queue part. */
        SCHED_INFO("schedInfo");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        boolean available() {
            return this == SCHED_INFO;
        }
    }

    /** What happened to one interval. */
    enum Outcome {
        SPLIT,
        /** The capture does not record the split, or the kernel dropped this interval's reading. */
        NO_READING,
        /** A blocked interval whose reading exceeds its duration. */
        EXCEEDS_INTERVAL
    }

    /** The capture's source, from {@code captureStart}; a capture without the block predates it. */
    static Source source(JsonObject captureStart) throws IOException {
        JsonElement element = captureStart.get("timeSplit");
        if (element == null) return Source.NOT_RECORDED;
        require(element.isJsonObject(), "Invalid timeSplit");
        JsonObject value = element.getAsJsonObject();
        require(value.keySet().equals(java.util.Set.of("source")), "Unexpected timeSplit shape");
        String source = CaptureInput.text(value, "source");
        return switch (source) {
            case "off" -> Source.OFF;
            case "schedInfo" -> Source.SCHED_INFO;
            default -> throw new IOException("Unknown timeSplit source: " + source);
        };
    }

    /**
     * Splits one interval into {@code parts[0..2]} (sleeping, run queue, unsplit), clipped to {@code [from, to)}.
     *
     * @param start the interval's switch-out time
     * @param end the interval's switch-in time
     * @param hasReading whether the observation carries a run-queue reading
     * @param reading the reading, raw u64 nanoseconds
     * @param from the clipped lower bound, at or after {@code start}
     * @param to the clipped upper bound, at or before {@code end}
     */
    static Outcome split(
            OffCpuReason reason,
            long start,
            long end,
            boolean hasReading,
            long reading,
            long from,
            long to,
            long[] parts) {
        long duration = to > from ? to - from : 0;
        parts[0] = 0;
        parts[1] = 0;
        parts[2] = 0;
        if (!hasReading || reason == OffCpuReason.UNSPECIFIED) {
            parts[2] = duration;
            return Outcome.NO_READING;
        }
        if (reason != OffCpuReason.BLOCKED) {
            parts[1] = duration;
            return Outcome.SPLIT;
        }
        if (Long.compareUnsigned(reading, end - start) > 0) {
            parts[2] = duration;
            return Outcome.EXCEEDS_INTERVAL;
        }
        long wakeup = end - reading;
        long sleeping = Math.max(0, Math.min(wakeup, to) - from);
        parts[0] = Math.min(sleeping, duration);
        parts[1] = duration - parts[0];
        return Outcome.SPLIT;
    }
}
