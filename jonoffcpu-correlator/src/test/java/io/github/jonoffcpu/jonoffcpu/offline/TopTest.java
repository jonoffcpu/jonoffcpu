// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jonoffcpu.jonoffcpu.capture.CaptureFixtures;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto;
import io.github.jonoffcpu.jonoffcpu.capture.ProtoJson;
import io.github.jonoffcpu.jonoffcpu.offline.AnalysisProto.TopResult;
import io.github.jonoffcpu.jonoffcpu.offline.AnalysisProto.TopRow;
import io.github.jonoffcpu.jonoffcpu.profile.ProfileProto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ranked tables and the digest: attribution rules on hand-built entries, formats, totals and the digest files. */
class TopTest {
    private static List<StackProfile.Frame> java(String... names) {
        return Arrays.stream(names)
                .map(name -> new StackProfile.Frame(
                        name.contains(" ") || StackTransforms.looksNative(name)
                                ? StackProfile.Kind.JFR_NATIVE
                                : StackProfile.Kind.JAVA,
                        name,
                        ""))
                .toList();
    }

    private static StackProfile.Entry entry(List<StackProfile.Frame> stack, String thread, long intervals, long nanos) {
        return new StackProfile.Entry(stack, null, null, OffCpuReason.BLOCKED, 1, thread, intervals, nanos, nanos * 2);
    }

    private static Path profile(Path dir, String name, CaptureProto.Sampling sampling, boolean estimate)
            throws IOException {
        return profile(dir, name, sampling, estimate, null);
    }

    private static Path profile(
            Path dir, String name, CaptureProto.Sampling sampling, boolean estimate, ReportProto.Report report)
            throws IOException {
        List<StackProfile.Entry> entries = List.of(
                // The boundary is the deepest application frame, past interleaved library frames.
                entry(
                        java(
                                "x.A.m",
                                "y.Lib.n",
                                "x.B.o",
                                "java.util.concurrent.locks.ReentrantLock.lock",
                                "jdk.internal.misc.Unsafe.park"),
                        "pool-1-thread-1",
                        3,
                        3_000_000_000L),
                entry(
                        java("x.A.m", "y.Lib.n", "x.B.o", "C2 Runtime complete_monitor_locking"),
                        "pool-1-thread-2",
                        1,
                        1_000_000_000L),
                // No application frame: listed by pool.
                entry(java("java.lang.Thread.run", "libjvm.so.ZDriver::run"), "ZDriverMinor", 2, 500_000_000L),
                // Idle: listed apart, not under busy; it also waited on a lock, which the over-exclusion check counts.
                entry(
                        java(
                                "x.A.loop",
                                "java.util.concurrent.locks.ReentrantLock.lock",
                                "java.util.concurrent.ThreadPoolExecutor.getTask"),
                        "pool-1-thread-3",
                        5,
                        7_000_000_000L),
                // Recursion: x.R.r appears twice and is counted once by --by method.
                entry(java("x.R.r", "x.R.r", "jdk.internal.misc.Unsafe.park"), "worker-7", 4, 250_000_000L));
        Path path = dir.resolve(name + ".pb");
        new StackProfile(
                        new StackProfile.Header(
                                List.of(ProfileProto.Provenance.newBuilder()
                                        .setSessionId(name)
                                        .setCaptureEpoch(1)
                                        .setSampling(sampling)
                                        .setThinningProbability("1")
                                        .build()),
                                List.of("reason", "thread"),
                                estimate,
                                report,
                                "",
                                List.of()),
                        entries)
                .write(path);
        return path;
    }

    static final CaptureProto.Sampling PROPORTIONAL =
            CaptureFixtures.proportionalSampling(10000, CaptureProto.OffCpuReason.OFF_CPU_REASON_BLOCKED);
    static final CaptureProto.Sampling NONE = CaptureProto.Sampling.newBuilder()
            .addReasons(CaptureProto.OffCpuReason.OFF_CPU_REASON_BLOCKED)
            .setNone(CaptureProto.NoAdmission.getDefaultInstance())
            .build();

    private static TopResult json(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("top", "--format", "json"));
        command.addAll(List.of(args));
        CommandLineFixture.Invocation invocation = CommandLineFixture.invoke(command.toArray(String[]::new));
        assertThat(invocation.code()).as("top failed: %s", invocation).isZero();
        return CorrelationFixture.parse(invocation.out(), TopResult.newBuilder())
                .build();
    }

    private static List<String> keys(List<TopRow> rows) {
        return rows.stream().map(TopRow::getKey).toList();
    }

    private static List<String> common(Path profile) {
        return List.of("--profile", profile.toString(), "--app", "^x\\.", "--idle", "getTask$");
    }

    @Test
    void boundaryTable(@TempDir Path dir) throws Exception {
        Path profile = profile(dir, "run", NONE, false);
        TopResult boundary = json(common(profile).toArray(String[]::new));
        TopRow first = boundary.getRows(0);
        String boundaryMessage = "Boundary past a library frame, with its blocker: " + first;
        assertThat(first.getKey()).as(boundaryMessage).isEqualTo("x.B.o");
        assertThat(first.getBlocker()).as(boundaryMessage).isEqualTo("java.util.concurrent.locks.ReentrantLock.lock");
        assertThat(first.getValue()).as(boundaryMessage).isEqualTo("3.000");
        assertThat(first.getIntervals()).as(boundaryMessage).isEqualTo(3);
        assertThat(first.getCaller()).as(boundaryMessage).isEqualTo("x.A.m");
        assertThat(first.getReason()).as(boundaryMessage).isEqualTo(CaptureProto.OffCpuReason.OFF_CPU_REASON_BLOCKED);
        assertThat(boundary.getRows(1).getBlocker())
                .as("A monitor wait's blocker")
                .isEqualTo("C2 Runtime complete_monitor_locking");
        assertThat(boundary.getNoApplicationFrame(0).getKey())
                .as("No application frame goes to the pool table: %s", boundary)
                .isEqualTo("ZDriverMinor");
        assertThat(keys(boundary.getIdleList()))
                .as("The idle entry is listed as idle")
                .containsExactly("x.A.loop");
        AnalysisProto.TopTotals totals = boundary.getTotals();
        String totalsMessage =
                "Totals add up: busy and idle make the selection, rows and pools make the busy: " + totals;
        assertThat(totals.getSelected().getValue()).as(totalsMessage).isEqualTo("11.750");
        assertThat(totals.getIdle().getValue()).as(totalsMessage).isEqualTo("7.000");
        assertThat(totals.getBusy().getValue()).as(totalsMessage).isEqualTo("4.750");
        assertThat(totals.getBusyApplication().getValue()).as(totalsMessage).isEqualTo("4.250");
        assertThat(totals.getBusyNoApplicationFrame().getValue())
                .as(totalsMessage)
                .isEqualTo("0.500");
        assertThat(totals.getOverExclusion().getEntries()).as(totalsMessage).isEqualTo(1);
        assertThat(keys(boundary.getRowsList()))
                .as("An idle entry is never busy")
                .doesNotContain("x.A.loop");
        assertThat(boundary.getRows(0).hasEstimated())
                .as("No estimate column without an estimate")
                .isFalse();
        assertThat(boundary.getSelection().getProfile()).isEqualTo(profile.toString());
        CommandLineFixture.usageError("--by boundary needs --app", "top", "--profile", profile.toString());
    }

    @Test
    void otherGroupings(@TempDir Path dir) throws Exception {
        Path profile = profile(dir, "run", NONE, false);
        TopResult method = json("--profile", profile.toString(), "--by", "method", "--idle", "getTask$");
        for (TopRow row : method.getRowsList()) {
            if (row.getKey().equals("x.R.r")) {
                assertThat(row.getIntervals())
                        .as("Recursion counts once: %s", row)
                        .isEqualTo(4);
                assertThat(row.getValue()).as("Recursion counts once: %s", row).isEqualTo("0.250");
            }
        }
        TopResult classes = json("--profile", profile.toString(), "--by", "class", "--limit", "50");
        assertThat(keys(classes.getRowsList()))
                .as("--by class counts Java frames only")
                .noneMatch(key -> key.startsWith("libjvm"));
        TopResult self = json(
                "--profile", profile.toString(), "--by", "self", "--collapse-leaf-from", "preset:jvm-wait-machinery");
        assertThat(keys(self.getRowsList()))
                .as("--by self uses the collapsed leaf")
                .satisfiesAnyOf(
                        keys -> assertThat(keys.get(0)).isEqualTo("x.A.loop"),
                        keys -> assertThat(List.<String>copyOf(keys))
                                .contains("java.util.concurrent.locks.ReentrantLock.lock"));
        TopResult pools = json("--profile", profile.toString(), "--by", "pool", "--idle", "getTask$");
        assertThat(pools.getRows(0).getKey()).as("Pools: %s", pools).isEqualTo("pool-#-thread-#");
    }

    /** Every format carries the same rows. */
    @Test
    void formats(@TempDir Path dir) throws Exception {
        Path profile = profile(dir, "run", NONE, false);
        List<String> common = common(profile);
        TopResult boundary = json(common.toArray(String[]::new));
        List<String> mdArgs = new ArrayList<>(List.of("top", "--format", "md"));
        mdArgs.addAll(common);
        String markdown =
                CommandLineFixture.invoke(mdArgs.toArray(String[]::new)).out();
        assertThat(markdown)
                .contains("| 1 | `x.B.o` | `java.util.concurrent.locks.ReentrantLock.lock` | 3.000 |")
                .contains("| blocked |")
                .contains("Reproduce: `java -jar jonoffcpu-correlator.jar top --format md --profile ");
        List<String> csvArgs = new ArrayList<>(List.of("top", "--format", "csv"));
        csvArgs.addAll(common);
        List<String> csv = CommandLineFixture.invoke(csvArgs.toArray(String[]::new))
                .out()
                .lines()
                .toList();
        assertThat(csv)
                .as("CSV")
                .hasSize(1 + boundary.getRowsCount() + boundary.getNoApplicationFrameCount() + boundary.getIdleCount());
        assertThat(csv.get(1))
                .startsWith("rows,1,x.B.o,java.util.concurrent.locks.ReentrantLock.lock,3.000,")
                .contains(",blocked,");
    }

    /** Estimated weights need the estimate; a comparison of sampled runs without estimates warns. */
    @Test
    void estimatedWeightsAndComparison(@TempDir Path dir) throws Exception {
        Path profile = profile(dir, "run", NONE, false);
        assertThatThrownBy(() -> CommandLineFixture.invoke(
                        "top", "--profile", profile.toString(), "--app", "^x\\.", "--weights", "estimated"))
                .as("--weights estimated must be refused without an estimate")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("estimate is unavailable");
        Path sampled = profile(dir, "sampled", PROPORTIONAL, false);
        Path baseline = profile(dir, "baseline", PROPORTIONAL, false);
        TopResult compared = json(
                "--profile",
                sampled.toString(),
                "--baseline",
                baseline.toString(),
                "--units",
                "2",
                "--baseline-units",
                "4",
                "--app",
                "^x\\.",
                "--idle",
                "getTask$");
        AnalysisProto.ComparedRow top = compared.getComparison(0);
        assertThat(top.getBoundary()).as("Seconds per unit: %s", top).isEqualTo("x.B.o");
        assertThat(top.getValue()).as("Seconds per unit: %s", top).isEqualTo("2.000");
        assertThat(top.getBaseline()).as("Seconds per unit: %s", top).isEqualTo("1.000");
        assertThat(compared.getUnit()).isEqualTo("seconds per unit");
        assertThat(compared.getSelection().getBaseline().getProfile()).isEqualTo(baseline.toString());
        assertThat(compared.getWarningsList())
                .as("Sampled runs without estimates warn")
                .anyMatch(warning -> warning.contains("length-biased"));
        Path estimated = profile(dir, "estimated", PROPORTIONAL, true);
        Path estimatedBaseline = profile(dir, "estimated-baseline", PROPORTIONAL, true);
        CommandLineFixture.usageError(
                "compare with --weights estimated",
                "top",
                "--profile",
                estimated.toString(),
                "--baseline",
                estimatedBaseline.toString(),
                "--app",
                "^x\\.");
    }

    /** The digest: written from its message, byte-stable, and with the reproduce commands. */
    @Test
    void digest(@TempDir Path dir) throws Exception {
        Path profile = profile(dir, "run", NONE, false);
        Path firstDigest = dir.resolve("digest-1");
        Path secondDigest = dir.resolve("digest-2");
        for (Path output : List.of(firstDigest, secondDigest)) {
            CommandLineFixture.Invocation invocation = CommandLineFixture.invoke(
                    "summarize", "--profile", profile.toString(), "--app", "^x\\.", "--output-dir", output.toString());
            assertThat(invocation.code()).as("summarize failed: %s", invocation).isZero();
        }
        for (String name : List.of(OutputFiles.SUMMARY_JSON, OutputFiles.SUMMARY_MD)) {
            assertThat(firstDigest.resolve(name))
                    .as("%s must be byte-stable", name)
                    .hasSameBinaryContentAs(secondDigest.resolve(name));
        }
        AnalysisProto.Digest digest = CorrelationFixture.parse(
                        firstDigest.resolve(OutputFiles.SUMMARY_JSON), AnalysisProto.Digest.newBuilder())
                .build();
        String markdown = Files.readString(firstDigest.resolve(OutputFiles.SUMMARY_MD));
        assertThat(Digest.markdown(digest))
                .as("The Markdown must be rendered from the JSON")
                .isEqualTo(markdown);
        assertThat(digest.hasCapture())
                .as("A profile without a report has no capture section")
                .isFalse();
        assertThat(digest.getIdle().getRowsList())
                .as("The default idle preset recognises getTask")
                .hasSize(1);
        assertThat(markdown).as("The digest says how to reproduce each table").contains("## How to reproduce");
    }

    private static ReportProto.Report report(String session, long sourceRows) {
        return ReportProto.Report.newBuilder()
                .setAnalysisInputs(CaptureProto.AnalysisInputs.newBuilder()
                        .setSessionId(session)
                        .setSampling(CaptureFixtures.uniformSampling()))
                .setSourceRows(sourceRows)
                .setMatched(sourceRows - 1)
                .build();
    }

    /** summarize takes the capture section from the report the profile carries, or from a strictly parsed file. */
    @Test
    void summarizeReport(@TempDir Path dir) throws Exception {
        Path profile = profile(dir, "run", NONE, false, report("embedded-session", 7));
        Path embedded = dir.resolve("embedded");
        assertThat(CommandLineFixture.invoke(
                                "summarize", "--profile", profile.toString(), "--output-dir", embedded.toString())
                        .code())
                .isZero();
        AnalysisProto.Digest digest = CorrelationFixture.parse(
                        embedded.resolve(OutputFiles.SUMMARY_JSON), AnalysisProto.Digest.newBuilder())
                .build();
        assertThat(digest.getCapture().getSessionId())
                .as("The profile's own report is the default")
                .isEqualTo("embedded-session");
        assertThat(digest.getCapture().getSourceRows()).isEqualTo(7);
        assertThat(Files.readString(embedded.resolve(OutputFiles.SUMMARY_MD))).contains("- Source rows 7, matched 6");

        Path file = dir.resolve("other-report.json");
        Files.writeString(file, ProtoJson.pretty(report("file-session", 3)));
        Path explicit = dir.resolve("explicit");
        assertThat(CommandLineFixture.invoke(
                                "summarize",
                                "--profile",
                                profile.toString(),
                                "--report",
                                file.toString(),
                                "--output-dir",
                                explicit.toString())
                        .code())
                .isZero();
        assertThat(CorrelationFixture.parse(
                                explicit.resolve(OutputFiles.SUMMARY_JSON), AnalysisProto.Digest.newBuilder())
                        .getCapture()
                        .getSessionId())
                .as("--report overrides the embedded report")
                .isEqualTo("file-session");

        Path notReport = dir.resolve("not-a-report.json");
        Files.writeString(notReport, "{\"entries\": 3}");
        assertThatThrownBy(() -> CommandLineFixture.invoke(
                        "summarize",
                        "--profile",
                        profile.toString(),
                        "--report",
                        notReport.toString(),
                        "--output-dir",
                        dir.resolve("refused").toString()))
                .as("A file that is not a report is refused, not summarised as empty")
                .isInstanceOf(IOException.class);
    }
}
