// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Verifies YAML parsing through the relocated dependencies in the published agent JAR. */
public final class ShadedAgentJarTest {
    private ShadedAgentJarTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("jonoffcpu-shaded-agent-");
        try {
            Path profiler = Files.createFile(root.resolve("libasyncProfiler.so"));
            Path collector = Files.createFile(root.resolve("libjonoffcpu.so"));
            AgentConfig config = AgentConfig.parse(
                    """
                    correlationOutput: %s
                    asyncProfilerLibrary: %s
                    nativeCollectorLibrary: %s
                    asyncProfilerOptions: event=cpu,file=%s
                    sampleProbability: "0.125"
                    """.formatted(root.resolve("capture.ndjson"), profiler, collector, root.resolve("capture.jfr")));
            if (config.sampleThreshold() != 536_870_912L) {
                throw new AssertionError("Relocated YAML parser changed the sampling probability");
            }
            System.out.println("Shaded agent YAML fixture passed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
