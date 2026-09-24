// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.agent;

import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto.TimeSplit;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto.TimeSplitSource;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the configuration's {@code timeSplit} block into the {@link TimeSplit} message: where each interval's
 * run-queue part comes from. It changes what is measured, not which intervals are kept, so it is a block of its own
 * beside {@link SamplingConfig}; like it, the resolved message is sent to the native collector, echoed by it, written
 * into the capture stream and manifest, and compared as a message. The configuration spells the source {@code off}
 * or {@code schedInfo}.
 */
final class TimeSplitConfig {
    private static final Set<String> KEYS = Set.of("source");
    /** The scheduler's own accounting: cheap, and present on mainstream kernels. */
    static final TimeSplit DEFAULT = of(TimeSplitSource.TIME_SPLIT_SOURCE_SCHED_INFO);

    private TimeSplitConfig() {}

    static TimeSplit of(TimeSplitSource source) {
        return TimeSplit.newBuilder().setSource(source).build();
    }

    static TimeSplit parse(Map<?, ?> value) {
        ConfigValues.requireKeys(value, KEYS, "timeSplit");
        return of(parseSource(ConfigValues.requireString(value, "source")));
    }

    static TimeSplitSource parseSource(String value) {
        return switch (value) {
            // Nothing is read; intervals carry no run-queue part. For kernels without CONFIG_SCHED_INFO.
            case "off" -> TimeSplitSource.TIME_SPLIT_SOURCE_OFF;
            // The growth of task_struct.sched_info.run_delay across the interval.
            case "schedInfo" -> TimeSplitSource.TIME_SPLIT_SOURCE_SCHED_INFO;
            default -> throw new IllegalArgumentException("Unknown timeSplit source: " + value);
        };
    }
}
