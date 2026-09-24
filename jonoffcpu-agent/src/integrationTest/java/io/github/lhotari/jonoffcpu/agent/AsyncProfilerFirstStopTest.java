// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import static io.github.lhotari.jonoffcpu.agent.CaptureChecks.JFR;
import static io.github.lhotari.jonoffcpu.agent.CaptureChecks.SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Struct;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.CaptureFinalized;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * async-profiler stopping before the agent: on its own timeout, or with the JFR master recording. The agent retains
 * its receipt, and every source interval after the cutoff is explicitly unmatched.
 */
@Tag("privileged-container")
@EnabledOnOs(OS.LINUX)
@Testcontainers(disabledWithoutDocker = true)
@ParameterizedClass(allowZeroInvocations = true)
@MethodSource("io.github.lhotari.jonoffcpu.agent.AgentRuntime#selected")
class AsyncProfilerFirstStopTest {
    private final AgentRuntime runtime;

    AsyncProfilerFirstStopTest(AgentRuntime runtime) {
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

    /** {@code timeout}: async-profiler's own timer stops it; {@code master-stop}: the workload stops the recording. */
    @ParameterizedTest
    @ValueSource(strings = {"timeout", "master-stop"})
    void asyncProfilerStoppingFirstCutsOffLaterIntervals(String mode, @TempDir Path dir) throws Exception {
        Path out = Files.createDirectory(dir.resolve("out"));
        String profilerOptions = (mode.equals("timeout") ? "timeout=2s," : "") + CaptureChecks.PROFILER_OPTIONS;
        String sampling = """
                minOffCpuMicros: 5000
                admission:
                  policy: uniform
                  probability: "1"
                """;
        try (var agent = runtime.agent(
                out,
                config(profilerOptions, sampling),
                NativeAgentShutdownWorkload.class.getName(),
                mode,
                "3",
                "/out/ready")) {
            assertThat(AgentRuntime.runToExit(agent, Duration.ofSeconds(45)))
                    .as("the workload's exit code; log:%n%s", agent.getLogs())
                    .isZero();
        } finally {
            runtime.makeReadable(out);
        }

        CaptureFinalized footer = CaptureChecks.completeFooter(out);
        String receipt = footer.getApStopResponse();
        assertThat(receipt).as("the retained async-profiler receipt").contains(" reason=" + mode, " finalized=true ");
        assertThat(CaptureChecks.completeManifest(out).getAsyncProfilerStop().getResponse())
                .as("the footer and the audit manifest retain the same receipt")
                .isEqualTo(receipt.strip());
        var counts = CaptureChecks.checkMixedRecording(out.resolve(JFR));
        assertThat(counts.get("profiler.SignalCaptureStats"))
                .as("terminal stats records in the combined recording")
                .isEqualTo(1L);

        Path analysis = dir.resolve("analysis");
        CaptureChecks.correlate(
                "--source", out.resolve(SOURCE).toString(),
                "--jfr", out.resolve(JFR).toString(),
                "--output", analysis.toString(),
                "--audit", "full");
        Struct report = CaptureChecks.json(analysis.resolve("jonoffcpu-report.json"));
        assertThat(CaptureChecks.number(report, "matched")
                        + CaptureChecks.number(report, "unmatchedSource")
                        + CaptureChecks.number(report, "invalidSource"))
                .as("source classifications reconcile")
                .isEqualTo(CaptureChecks.number(report, "sourceRows"));
        assertThat(CaptureChecks.number(report, "matched")
                        + CaptureChecks.number(report, "orphanJfr")
                        + CaptureChecks.number(report, "invalidJfr"))
                .as("JFR classifications reconcile")
                .isEqualTo(CaptureChecks.number(report, "jfrSamples"));
        long cutoff = CaptureChecks.number(report, "apStoppedAtNanos");
        List<Struct> afterCutoff = new ArrayList<>();
        for (String line : Files.readAllLines(analysis.resolve("jonoffcpu-classified-records.jsonl"))) {
            Struct row = CaptureChecks.jsonLine(line);
            // A source row carries the observation it classifies; a JFR row carries the sample instead.
            if (row.getFieldsMap().containsKey("source")
                    && CaptureChecks.number(row, "source", "observation", "endMonotonicNanos") > cutoff) {
                afterCutoff.add(row);
            }
        }
        assertThat(afterCutoff)
                .as("source rows after the async-profiler cutoff")
                .isNotEmpty();
        assertThat(afterCutoff)
                .as("every post-cutoff source row is explicitly unmatched")
                .allSatisfy(row -> {
                    assertThat(CaptureChecks.string(row, "classification")).isEqualTo("CLASSIFICATION_UNMATCHED");
                    assertThat(CaptureChecks.string(row, "reason"))
                            .isEqualTo("ROW_REASON_SOURCE_INTERVAL_AFTER_AP_STOP");
                });
    }
}
