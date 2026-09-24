// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** YAML parsing through the relocated dependencies of the published agent JAR, which is all the classpath holds. */
@Tag("packaged-jar")
class ShadedAgentJarTest {
    @Test
    void relocatedYamlParserReadsSampling(@TempDir Path root) throws Exception {
        Path profiler = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path collector = Files.createFile(root.resolve("libjonoffcpu.so"));
        AgentConfig config = AgentConfig.parse(
                """
                correlationOutput: %s
                asyncProfilerLibrary: %s
                nativeCollectorLibrary: %s
                asyncProfilerOptions: event=cpu,file=%s
                sampling:
                  admission:
                    policy: uniform
                    probability: "0.125"
                """.formatted(root.resolve("capture.ndjson"), profiler, collector, root.resolve("capture.jfr")));
        assertThat(((SamplingConfig.Uniform) config.sampling().admission()).probabilityThreshold())
                .as("the relocated YAML parser's sampling probability")
                .isEqualTo(536_870_912L);
    }
}
