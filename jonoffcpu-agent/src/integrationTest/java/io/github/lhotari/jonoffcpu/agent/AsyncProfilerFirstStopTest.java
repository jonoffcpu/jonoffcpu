// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import static io.github.lhotari.jonoffcpu.agent.CaptureChecks.JFR;
import static io.github.lhotari.jonoffcpu.agent.CaptureChecks.SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

        JsonObject footer = CaptureChecks.completeFooter(out);
        String receipt = footer.get("apStopResponse").getAsString();
        assertThat(receipt).as("the retained async-profiler receipt").contains(" reason=" + mode, " finalized=true ");
        JsonObject manifest = CaptureChecks.completeManifest(out);
        assertThat(manifest.getAsJsonObject("asyncProfilerStop").get("response").getAsString())
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
        JsonObject report = CaptureChecks.json(analysis.resolve("jonoffcpu-report.json"));
        assertThat(report.get("matched").getAsLong()
                        + report.get("unmatchedSource").getAsLong()
                        + report.get("invalidSource").getAsLong())
                .as("source classifications reconcile")
                .isEqualTo(report.get("sourceRows").getAsLong());
        assertThat(report.get("matched").getAsLong()
                        + report.get("orphanJfr").getAsLong()
                        + report.get("invalidJfr").getAsLong())
                .as("JFR classifications reconcile")
                .isEqualTo(report.get("jfrSamples").getAsLong());
        long cutoff = Long.parseLong(report.get("apStoppedAtNanos").getAsString());
        List<JsonObject> afterCutoff = new ArrayList<>();
        for (String line : Files.readAllLines(analysis.resolve("jonoffcpu-classified-records.jsonl"))) {
            JsonObject row = JsonParser.parseString(line).getAsJsonObject();
            if (row.get("stream").getAsString().equals("source")
                    && Long.parseLong(row.getAsJsonObject("record")
                                    .get("endMonotonicNanos")
                                    .getAsString())
                            > cutoff) {
                afterCutoff.add(row);
            }
        }
        assertThat(afterCutoff)
                .as("source rows after the async-profiler cutoff")
                .isNotEmpty();
        assertThat(afterCutoff)
                .as("every post-cutoff source row is explicitly unmatched")
                .allSatisfy(row -> {
                    assertThat(row.get("classification").getAsString()).isEqualTo("unmatched");
                    assertThat(row.get("reason").getAsString()).isEqualTo("source-interval-after-ap-stop");
                });
    }
}
