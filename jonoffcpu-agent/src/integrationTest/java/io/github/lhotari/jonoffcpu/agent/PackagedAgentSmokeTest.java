// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import static io.github.lhotari.jonoffcpu.agent.CaptureChecks.JFR;
import static io.github.lhotari.jonoffcpu.agent.CaptureChecks.SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.lhotari.jonoffcpu.agent.ManifestProto.Manifest;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.OffCpuReason;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The packaged agent and correlator against the host kernel: async-profiler starts with jfrsync=profile, the combined
 * recording holds every event category, and the capture correlates into verified off-CPU matches whose switch-out
 * reasons and sleeping/run-queue split account for every matched interval.
 */
@Tag("privileged-container")
@EnabledOnOs(OS.LINUX)
@Testcontainers(disabledWithoutDocker = true)
@ParameterizedClass(allowZeroInvocations = true)
@MethodSource("io.github.lhotari.jonoffcpu.agent.AgentRuntime#selected")
class PackagedAgentSmokeTest {
    private final AgentRuntime runtime;

    PackagedAgentSmokeTest(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @Test
    void captureCorrelatesIntoVerifiedMatches(@TempDir Path dir) throws Exception {
        Path out = Files.createDirectory(dir.resolve("out"));
        String config = """
                correlationOutput: /out/jonoffcpu-capture.pb
                asyncProfilerOptions: %s
                signalDelivery: queued
                sampling:
                  minOffCpuMicros: 100
                  admission:
                    policy: proportional
                    recordAllAboveMicros: 10000
                """.formatted(CaptureChecks.PROFILER_OPTIONS);
        try (var agent = runtime.agent(out, config, NativeAgentWorkload.class.getName(), "1")) {
            assertThat(AgentRuntime.runToExit(agent, Duration.ofSeconds(45)))
                    .as("the workload's exit code; log:%n%s", agent.getLogs())
                    .isZero();
        } finally {
            runtime.makeReadable(out);
        }

        Manifest manifest = CaptureChecks.completeManifest(out);
        assertThat(out.resolve(SOURCE)).isNotEmptyFile();
        assertThat(out.resolve(JFR)).isNotEmptyFile();
        // The configuration names no reasons, so the resolved policy records blocked intervals only.
        assertThat(manifest.getSampling().getReasonsList())
                .as("the default switch-out reasons")
                .containsExactly(OffCpuReason.OFF_CPU_REASON_BLOCKED);
        // Nor does it name a time-split source, so each interval's run-queue part is read from sched_info.
        assertThat(manifest.getTimeSplit()).as("the default time-split source").isEqualTo(TimeSplitConfig.DEFAULT);
        // The collector echoed the policy it was sent, and the footer carries the inputs the manifest recorded.
        assertThat(manifest.getNativeEnable().getEnabled().getSampling()).isEqualTo(manifest.getSampling());
        assertThat(CaptureChecks.completeFooter(out).getAnalysisInputs()).isEqualTo(manifest.getAnalysisInputs());
        CaptureChecks.checkMixedRecording(out.resolve(JFR));

        Path analysis = dir.resolve("analysis");
        Struct report = CaptureChecks.correlateVerified(out, analysis);
        assertThat(CaptureChecks.number(report, "identityUnverified"))
                .as("identity-unverified samples")
                .isZero();
        long matched = CaptureChecks.number(report, "matched");
        // Every matched interval is classified by its switch-out reason, and only selected reasons appear.
        Struct reasons = CaptureChecks.object(report, "offCpuReasons");
        List<Struct> matchedByReason = CaptureChecks.value(reasons, "matched").getListValue().getValuesList().stream()
                .map(Value::getStructValue)
                .toList();
        assertThat(matchedByReason)
                .as("reasons of the matched intervals")
                .extracting(entry -> CaptureChecks.string(entry, "reason"))
                .containsExactly(OffCpuReason.OFF_CPU_REASON_BLOCKED.name());
        Struct blocked = matchedByReason.get(0);
        assertThat(CaptureChecks.number(blocked, "intervals"))
                .as("blocked matched intervals")
                .isEqualTo(matched);
        assertThat(CaptureChecks.number(reasons, "kernelSwitchOuts", "blocked"))
                .as("blocked switch-outs the kernel counted")
                .isPositive();
        assertThat(analysis.resolve("jonoffcpu-offcpu-profile.pb")).isNotEmptyFile();
        // The digest is written by default, and the report names it rather than an error.
        assertThat(CaptureChecks.string(report, "digest", "path")).isEqualTo("jonoffcpu-summary.md");
        assertThat(analysis.resolve("jonoffcpu-summary.md")).isRegularFile();

        // The profile renders back to the collapsed stacks the correlator wrote.
        Path profile = analysis.resolve("jonoffcpu-offcpu-profile.pb");
        Path rendered = dir.resolve("rendered.collapsed");
        CaptureChecks.correlate("stacks", "--profile", profile.toString(), "--output", rendered.toString());
        assertThat(rendered)
                .as("the stack profile reproduces the collapsed stacks")
                .hasSameBinaryContentAs(analysis.resolve("jonoffcpu-offcpu-stacks.collapsed"));

        // Every matched interval carries its run-queue part, so its time splits into sleeping and run-queue time.
        Struct timeSplit = CaptureChecks.object(reasons, "timeSplit");
        assertThat(CaptureChecks.string(timeSplit, "source")).isEqualTo("TIME_SPLIT_SOURCE_SCHED_INFO");
        assertThat(CaptureChecks.value(timeSplit, "available").getBoolValue())
                .as("time split available")
                .isTrue();
        assertThat(CaptureChecks.number(timeSplit, "unsplitIntervals", "withoutReading"))
                .as("intervals without a reading")
                .isZero();
        assertThat(CaptureChecks.number(timeSplit, "unsplitIntervals", "readingExceedsInterval"))
                .as("intervals whose reading exceeds them")
                .isZero();
        assertThat(CaptureChecks.number(blocked, "sleepingNanos") + CaptureChecks.number(blocked, "runqueueNanos"))
                .as("blocked sleeping and run-queue time add up")
                .isEqualTo(CaptureChecks.number(blocked, "observedNanos"));

        // The split slice renders the same total, one [sleeping] or [runqueue] leaf per part.
        long[] totals = new long[2];
        List<String> slices = List.of("total", "split");
        for (int index = 0; index < slices.size(); index++) {
            String time = slices.get(index);
            Path summary = dir.resolve(time + ".summary.json");
            CaptureChecks.correlate(
                    "stacks",
                    "--profile",
                    profile.toString(),
                    "--time",
                    time,
                    "--output",
                    dir.resolve(time + ".collapsed").toString(),
                    "--summary",
                    summary.toString());
            totals[index] = CaptureChecks.number(CaptureChecks.json(summary), "totalNanos");
        }
        assertThat(totals[1]).as("the split slice adds up to the total").isEqualTo(totals[0]);
        assertThat(Files.readAllLines(dir.resolve("split.collapsed")))
                .as("every split line ends in a sleeping or run-queue leaf")
                .isNotEmpty()
                .allMatch(line -> line.contains(";[sleeping] ") || line.contains(";[runqueue] "));
    }
}
