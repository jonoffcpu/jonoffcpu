// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.agent;

import static io.github.jonoffcpu.jonoffcpu.agent.CaptureChecks.JFR;
import static io.github.jonoffcpu.jonoffcpu.agent.CaptureChecks.SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jonoffcpu.jonoffcpu.agent.ManifestProto.Manifest;
import io.github.jonoffcpu.jonoffcpu.agent.ManifestProto.ManifestState;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto.Record;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Abrupt JVM loss: a halted or SIGKILLed JVM leaves partial artifacts that are never published or accepted as
 * complete.
 */
@Tag("privileged-container")
@EnabledOnOs(OS.LINUX)
@Testcontainers(disabledWithoutDocker = true)
@ParameterizedClass(allowZeroInvocations = true)
@MethodSource("io.github.jonoffcpu.jonoffcpu.agent.AgentRuntime#selected")
class AgentAbruptExitTest {
    private final AgentRuntime runtime;

    AgentAbruptExitTest(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    private static String config(String profilerOptions, String sampling) {
        return """
                correlationOutput: /out/jonoffcpu-capture.pb
                asyncProfilerOptions: %s
                shutdownTimeoutMillis: 30000
                nativeStopTimeoutMillis: 10000
                sampling:
                %s
                """.formatted(profilerOptions, sampling.strip().indent(2).stripTrailing());
    }

    private static final String PROPORTIONAL = """
            minOffCpuMicros: 100
            admission:
              policy: proportional
              recordAllAboveMicros: 10000
            """;

    @Test
    void haltLeavesOnlyPartialArtifacts(@TempDir Path dir) throws Exception {
        Path out = Files.createDirectory(dir.resolve("out"));
        String config = config(CaptureChecks.PROFILER_OPTIONS, PROPORTIONAL);
        try (var agent =
                runtime.agent(out, config, NativeAgentShutdownWorkload.class.getName(), "halt", "1", "/out/ready")) {
            assertThat(AgentRuntime.runToExit(agent, Duration.ofSeconds(45)))
                    .as("Runtime.halt's exit code; log:%n%s", agent.getLogs())
                    .isEqualTo(23);
        } finally {
            runtime.makeReadable(out);
        }
        verifyAbrupt(out, dir.resolve("analysis"));
    }

    @Test
    void sigkillLeavesOnlyPartialArtifacts(@TempDir Path dir) throws Exception {
        Path out = Files.createDirectory(dir.resolve("out"));
        String config = config(CaptureChecks.PROFILER_OPTIONS, PROPORTIONAL);
        try (var agent =
                runtime.agent(out, config, NativeAgentShutdownWorkload.class.getName(), "wait", "1", "/out/ready")) {
            AgentRuntime.startUntilReady(agent, out.resolve("ready"));
            assertThat(AgentRuntime.stop(agent, "KILL"))
                    .as("the exit code after SIGKILL")
                    .isEqualTo(137);
        } finally {
            runtime.makeReadable(out);
        }
        verifyAbrupt(out, dir.resolve("analysis"));
    }

    /** The partial source and JFR remain, but nothing claims completeness, and the correlator refuses them. */
    private static void verifyAbrupt(Path out, Path analysis) throws Exception {
        Path source = out.resolve(SOURCE);
        assertThat(source).as("the partial capture stream").isNotEmptyFile();
        assertThat(out.resolve(JFR)).as("the partial recording").exists();
        assertThat(CaptureChecks.captureRecords(out, true))
                .as("an abrupt exit publishes no footer")
                .noneMatch(Record::hasCaptureFinalized);
        Manifest manifest = CaptureChecks.manifest(out);
        assertThat(manifest.getComplete()).as("manifest complete").isFalse();
        assertThat(manifest.getState()).as("manifest state").isNotEqualTo(ManifestState.MANIFEST_STATE_COMPLETE);
        CaptureChecks.Run run = CaptureChecks.correlator(
                "--source", source.toString(), "--jfr", out.resolve(JFR).toString(), "--output", analysis.toString());
        assertThat(run.exitCode())
                .as("the correlator refuses partial artifacts:%n%s", run.output())
                .isNotZero();
        assertThat(analysis.resolve("jonoffcpu-report.json"))
                .as("no complete analysis of partial artifacts")
                .doesNotExist();
    }
}
