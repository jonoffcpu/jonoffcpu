// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import jdk.jfr.consumer.RecordingFile;

/**
 * Verifies event categories by each event's resolved name, including recordings with different
 * chunk schemas.
 */
public final class MixedRecordingCheck {
    private MixedRecordingCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected recording.jfr and counts.json");
        Map<String, Long> counts = new TreeMap<>();
        try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents())
                counts.merge(file.readEvent().getEventType().getName(), 1L, Long::sum);
        }
        Files.writeString(
                Path.of(args[1]),
                counts.entrySet().stream()
                        .map(entry -> "  \"" + entry.getKey() + "\": " + entry.getValue())
                        .collect(Collectors.joining(",\n", "{\n", "\n}\n")));
        for (String name : new String[] {
            "jdk.ExecutionSample",
            "profiler.SignalSample",
            "profiler.WallClockSample",
            "jdk.JavaMonitorEnter",
            "jdk.JVMInformation",
            "jdk.GCHeapSummary",
            "jonoffcpu.IntegrationMarker"
        }) {
            if (counts.getOrDefault(name, 0L) == 0) throw new AssertionError("Missing event category: " + name);
        }
        long allocations = counts.getOrDefault("jdk.ObjectAllocationInNewTLAB", 0L)
                + counts.getOrDefault("jdk.ObjectAllocationOutsideTLAB", 0L);
        if (allocations == 0) throw new AssertionError("Missing allocation samples");
        System.out.println("Mixed CPU/allocation/wall/lock/signal/JVM recording verified");
    }
}
