// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * YAML parsing and JSON printing through the relocated dependencies of the published agent JAR, which is all the
 * classpath holds.
 */
@Tag("packaged-jar")
class ShadedAgentJarTest {
    private static AgentConfig config(Path root) throws Exception {
        Path profiler = Files.createFile(root.resolve("libasyncProfiler.so"));
        Path collector = Files.createFile(root.resolve("libjonoffcpu.so"));
        return AgentConfig.parse(
                """
                correlationOutput: %s
                asyncProfilerLibrary: %s
                nativeCollectorLibrary: %s
                asyncProfilerOptions: event=cpu,file=%s
                sampling:
                  admission:
                    policy: uniform
                    probability: "0.125"
                """.formatted(root.resolve("capture.pb"), profiler, collector, root.resolve("capture.jfr")));
    }

    @Test
    void relocatedYamlParserReadsSampling(@TempDir Path root) throws Exception {
        assertThat(config(root).sampling().getUniform().getProbabilityThreshold())
                .as("the relocated YAML parser's sampling probability")
                .isEqualTo(536_870_912L);
    }

    /** The manifest is printed by protobuf's JsonFormat, which needs its relocated Gson at run time. */
    @Test
    void relocatedJsonPrinterWritesTheManifest(@TempDir Path root) throws Exception {
        ManifestStore store = ManifestStore.create(config(root), "01234567-89ab-cdef-0123-456789abcdef");
        assertThat(store.manifestPath())
                .content()
                .contains("\"state\": \"MANIFEST_STATE_PREPARING\"", "\"probabilityThreshold\": \"536870912\"");
    }
}
