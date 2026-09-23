// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import com.google.gson.JsonObject;
import java.util.Set;

/**
 * Where each interval's run-queue part comes from. It changes what is measured, not which intervals are kept, so
 * it is a block of its own beside {@link SamplingConfig}; like it, {@link #json()} is the single representation sent
 * to the native collector, echoed by it, written into the capture stream and manifest, and compared structurally.
 */
record TimeSplitConfig(Source source) {
    private static final Set<String> KEYS = Set.of("source");
    /** The scheduler's own accounting: cheap, and present on mainstream kernels. */
    static final TimeSplitConfig DEFAULT = new TimeSplitConfig(Source.SCHED_INFO);

    enum Source {
        /** Nothing is read; intervals carry no run-queue part. For kernels without {@code CONFIG_SCHED_INFO}. */
        OFF("off"),
        /** The growth of {@code task_struct.sched_info.run_delay} across the interval. */
        SCHED_INFO("schedInfo");

        private final String json;

        Source(String json) {
            this.json = json;
        }

        String json() {
            return json;
        }

        static Source parse(String value) {
            for (Source source : values()) {
                if (source.json.equals(value)) return source;
            }
            throw new IllegalArgumentException("Unknown timeSplit source: " + value);
        }
    }

    TimeSplitConfig {
        if (source == null) throw new IllegalArgumentException("timeSplit.source is required");
    }

    static TimeSplitConfig parse(JsonObject value) {
        for (String key : value.keySet()) {
            if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown timeSplit key: " + key);
        }
        return new TimeSplitConfig(Source.parse(JsonSupport.requireString(value, "source")));
    }

    JsonObject json() {
        JsonObject value = new JsonObject();
        value.addProperty("source", source.json());
        return value;
    }
}
