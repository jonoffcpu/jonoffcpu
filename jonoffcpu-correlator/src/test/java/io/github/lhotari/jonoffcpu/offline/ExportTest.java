// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static io.github.lhotari.jonoffcpu.offline.ExportFixture.export;
import static io.github.lhotari.jonoffcpu.offline.ExportFixture.frame;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.lhotari.jonoffcpu.capture.CaptureFixtures;
import io.github.lhotari.jonoffcpu.capture.CaptureProto;
import io.github.lhotari.jonoffcpu.profile.ProfileProto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SQL-friendly export: frame arrays, canonical stacks, pools, the run column, counters, run metadata. The checks
 * that DuckDB reads the export are in the integration test {@code ExportDuckDbTest}.
 */
class ExportTest {
    /** Two entries: one with every stack and a lambda frame, one past 2^53 - 1 observed nanoseconds. */
    private static Path profile(Path dir) throws Exception {
        var java = StackProfile.Kind.JAVA;
        var jfrNative = StackProfile.Kind.JFR_NATIVE;
        List<StackProfile.Entry> entries = List.of(
                new StackProfile.Entry(
                        List.of(
                                frame(java, "a.B$$Lambda.0x0000000081a06030.run"),
                                frame(jfrNative, "libjvm.so.Unsafe_Park")),
                        List.of(
                                frame(StackProfile.Kind.KERNEL, "__schedule+0x1f"),
                                frame(StackProfile.Kind.KERNEL, "__traceiter_sched_switch+0x3")),
                        List.of(frame(StackProfile.Kind.USER, "futex_wait+0x10")),
                        OffCpuReason.BLOCKED,
                        1,
                        "pulsar-io-3-25",
                        2,
                        2000,
                        4000),
                new StackProfile.Entry(
                        List.of(frame(java, "x.App.main")),
                        null,
                        null,
                        OffCpuReason.BLOCKED,
                        1,
                        "[tid=12345]",
                        1,
                        // Past 2^53 - 1: a double would lose it, which is why 64-bit counters are strings.
                        (1L << 53) + 1,
                        0));
        Path profile = dir.resolve("profile.pb");
        List<ProfileProto.Provenance> sources = List.of(ProfileProto.Provenance.newBuilder()
                .setSessionId("session-1")
                .setCaptureEpoch(7)
                .setSampling(CaptureFixtures.uniformSampling())
                .setThinningProbability("1")
                .setWindowFromNanos(10)
                .setWindowToNanos(20)
                .setTimeSplit(CaptureFixtures.timeSplit(CaptureProto.TimeSplitSource.TIME_SPLIT_SOURCE_OFF))
                .build());
        new StackProfile(
                        new StackProfile.Header(
                                sources, List.of("reason", "kernel", "user", "thread"), false, null, "", List.of()),
                        entries)
                .write(profile);
        return profile;
    }

    @Test
    void jsonlRows(@TempDir Path dir) throws Exception {
        List<AnalysisProto.ExportRow> rows = CorrelationFixture.lines(
                export(dir, "rows.jsonl", profile(dir), "--format", "jsonl"), AnalysisProto.ExportRow::newBuilder);
        AnalysisProto.ExportRow first = rows.get(0);
        AnalysisProto.ExportRow second = rows.get(1);
        assertThat(first.getJavaFramesList())
                .as("Java frames as an array: %s", first)
                .containsExactly("a.B$$Lambda.0x0000000081a06030.run", "libjvm.so.Unsafe_Park");
        assertThat(first.getJavaFrameKindsList()).as("Kinds: %s", first).containsExactly("java", "native");
        assertThat(first.getKernelFramesList())
                .as("Native frames as the joined columns render them: %s", first)
                .containsExactly("__schedule");
        assertThat(first.getUserFramesList())
                .as("Native frames as the joined columns render them: %s", first)
                .containsExactly("futex_wait");
        assertThat(first.getCanonicalJavaStack())
                .as("Canonical stack: %s", first)
                .isEqualTo("a.B$$Lambda.run;libjvm.so.Unsafe_Park");
        assertThat(first.getThreadPool()).as("Pool: %s", first).isEqualTo("pulsar-io-#-#");
        assertThat(second.getThreadPool()).as("Pool of a thread id").isEqualTo("[tid=#]");
        assertThat(first.getReason()).isEqualTo(CaptureProto.OffCpuReason.OFF_CPU_REASON_BLOCKED);
        assertThat(first.getObservedNanos()).as("Counters: %s", first).isEqualTo(2000);
        assertThat(second.getObservedNanos())
                .as("A counter past 2^53 - 1 stays exact: %s", second)
                .isEqualTo((1L << 53) + 1);
        assertThat(first.getRun())
                .as("Run defaults to the first session: %s", first)
                .isEqualTo("session-1");
        assertThat(first.getEstimateAvailable())
                .as("The estimate's validity is on every row: %s", first)
                .isFalse();
        assertThat(second.hasKernelStack()).as("An absent stack is unset").isFalse();
        assertThat(second.getKernelFramesList())
                .as("An absent stack has no frames")
                .isEmpty();
        // proto3 JSON prints 64-bit integers as decimal strings.
        String line = Files.readAllLines(dir.resolve("rows.jsonl")).get(1);
        assertThat(line).contains("\"observedNanos\":\"" + ((1L << 53) + 1) + "\"");
    }

    @Test
    void numbersOptionIsGone(@TempDir Path dir) throws Exception {
        CommandLineFixture.usageError(
                "Unknown options: '--numbers'",
                "export",
                "--profile",
                profile(dir).toString(),
                "--output",
                dir.resolve("refused").toString(),
                "--numbers",
                "string");
    }

    @Test
    void csvAndRunMetadata(@TempDir Path dir) throws Exception {
        Path metadata = dir.resolve("run.json");
        List<String> csv = Files.readAllLines(export(
                dir, "rows.csv", profile(dir), "--run-label", "baseline", "--run-metadata", metadata.toString()));
        assertThat(csv.get(0)).endsWith(",canonical_java_stack,thread_pool,run,estimate_available");
        assertThat(csv.get(1))
                .as("CSV row")
                .endsWith(",a.B$$Lambda.run;libjvm.so.Unsafe_Park,pulsar-io-#-#,baseline,false");
        AnalysisProto.RunMetadata run = CorrelationFixture.parse(metadata, AnalysisProto.RunMetadata.newBuilder())
                .build();
        ProfileProto.Provenance source = run.getSources(0);
        assertThat(run.getRun()).as("Run metadata: %s", run).isEqualTo("baseline");
        assertThat(run.getEntries()).as("Run metadata: %s", run).isEqualTo(2);
        assertThat(run.getIntervals()).as("Run metadata: %s", run).isEqualTo(3);
        assertThat(run.getObservedNanos()).as("Run metadata: %s", run).isEqualTo(Long.toString((1L << 53) + 2001));
        assertThat(source.getCaptureEpoch()).as("Run metadata: %s", run).isEqualTo(7);
        assertThat(source.getWindowToNanos()).as("Run metadata: %s", run).isEqualTo(20);
        assertThat(source.getSampling()).as("Run metadata: %s", run).isEqualTo(CaptureFixtures.uniformSampling());
        assertThat(run.getEstimateAvailable()).as("Run metadata: %s", run).isFalse();
    }
}
