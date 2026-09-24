// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto.CaptureFinalized;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Capture finalization from JVM shutdown, without an explicit stop call: the workload returns from main, calls
 * System.exit, or is sent SIGTERM, and the shutdown hook still finalizes a complete, correlatable capture.
 */
@Tag("privileged-container")
@EnabledOnOs(OS.LINUX)
@Testcontainers(disabledWithoutDocker = true)
@ParameterizedClass(allowZeroInvocations = true)
@MethodSource("io.github.jonoffcpu.jonoffcpu.agent.AgentRuntime#selected")
class AgentShutdownTest {
    private static final String CONFIG = """
            correlationOutput: /out/jonoffcpu-capture.pb
            asyncProfilerOptions: %s
            shutdownTimeoutMillis: 30000
            nativeStopTimeoutMillis: 10000
            sampling:
              minOffCpuMicros: 100
              admission:
                policy: proportional
                recordAllAboveMicros: 10000
            """.formatted(CaptureChecks.PROFILER_OPTIONS);

    private final AgentRuntime runtime;

    AgentShutdownTest(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @ParameterizedTest
    @ValueSource(strings = {"return", "exit"})
    void shutdownHookFinalizesTheCapture(String mode, @TempDir Path dir) throws Exception {
        Path out = Files.createDirectory(dir.resolve("out"));
        try (var agent =
                runtime.agent(out, CONFIG, NativeAgentShutdownWorkload.class.getName(), mode, "1", "/out/ready")) {
            assertThat(AgentRuntime.runToExit(agent, Duration.ofSeconds(45)))
                    .as("the workload's exit code; log:%n%s", agent.getLogs())
                    .isZero();
        } finally {
            runtime.makeReadable(out);
        }
        verifyFinalized(out, dir.resolve("analysis"));
    }

    @Test
    void sigtermFinalizesTheCapture(@TempDir Path dir) throws Exception {
        Path out = Files.createDirectory(dir.resolve("out"));
        try (var agent =
                runtime.agent(out, CONFIG, NativeAgentShutdownWorkload.class.getName(), "sigterm", "1", "/out/ready")) {
            AgentRuntime.startUntilReady(agent, out.resolve("ready"));
            assertThat(AgentRuntime.stop(agent, "TERM"))
                    .as("the exit code after SIGTERM; log:%n%s", agent.getLogs())
                    .isIn(0L, 143L);
        } finally {
            runtime.makeReadable(out);
        }
        verifyFinalized(out, dir.resolve("analysis"));
    }

    private static void verifyFinalized(Path out, Path analysis) throws Exception {
        CaptureFinalized footer = CaptureChecks.completeFooter(out);
        assertThat(footer.getAnalysisInputs())
                .as("the footer's analysis inputs")
                .isEqualTo(CaptureChecks.completeManifest(out).getAnalysisInputs());
        assertThat(footer.getApStopResponse())
                .as("the footer's async-profiler receipt")
                .startsWith("signal-capture-v1 stopped ")
                .contains(" finalized=true ");
        CaptureChecks.completeManifest(out);
        CaptureChecks.checkMixedRecording(out.resolve(CaptureChecks.JFR));
        CaptureChecks.correlateVerified(out, analysis);
    }
}
