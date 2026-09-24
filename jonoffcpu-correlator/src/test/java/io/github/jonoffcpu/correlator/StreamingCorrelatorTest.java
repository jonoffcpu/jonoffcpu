// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jonoffcpu.capture.CaptureProto;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The streaming, primitive-keyed correlation engine. Its degradation ladder is DegradationLadderTest; spec
 * acceptance 4, retention at scale, is the integration test StreamingCorrelatorScaleTest, which runs under the heap
 * bound it proves.
 */
class StreamingCorrelatorTest {
    /** Generated inputs the tests only read, shared so that each large fixture is built once per class. */
    @TempDir
    static Path inputs;

    /** A capture with the given number of observations, each matched by one JFR sample. */
    static Path capture(Path dir, Path jfr, long tid, int rows) throws IOException {
        List<CaptureProto.Observation> observations = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            observations.add(CorrelationFixture.observation(tid)
                    .setCorrelationId(CorrelationFixture.COOKIE + row)
                    .setStartMonotonicNanos(1000 + row)
                    .setEndMonotonicNanos(4000 + row)
                    .build());
        }
        return CorrelationFixture.source(dir, jfr, observations);
    }

    /** The OS thread id of the recording's samples. */
    private static long sampleThreadId(Path jfr) throws IOException {
        return CorrelationFixture.sampleThread(jfr);
    }

    private static int correlate(String... args) throws Exception {
        return OffCpuCorrelator.run(args);
    }

    /** Spec acceptance 2: the streamed engine and the retained one agree on every output. */
    @Test
    void goldenEquivalence(@TempDir Path dir) throws Exception {
        Path jfr = CorrelationFixture.recording(dir, 1);
        Path source = capture(dir, jfr, sampleThreadId(jfr), 1);
        var limits = OfflineCorrelator.Limits.defaults();

        // The library facade still returns a fully materialised analysis.
        var analysis = OfflineCorrelator.correlate(source, jfr, limits);
        assertThat(analysis.matched()).as("the only match").isEqualTo(1);
        assertThat(analysis.records())
                .as("classified records cover both streams")
                .hasSize(2);
        assertThat(analysis.records().get(0).hasSource())
                .as("source records come first")
                .isTrue();
        assertThat(analysis.records().get(1).hasJfr())
                .as("then the JFR records")
                .isTrue();
        assertThat(analysis.records().get(0).getRow())
                .as("source row numbering")
                .isEqualTo(2);
        assertThat(analysis.records().get(1).getRow()).as("JFR row numbering").isEqualTo(1);
        assertThat(analysis.records().get(0).getSource().getKernelFramesList())
                .as("classified source records re-expand the interned kernel stack")
                .extracting(CaptureProto.Frame::getSymbol)
                .containsExactly("kernel_wait");
        assertThat(analysis.records().get(0).getSource().getUserFramesList())
                .as("classified source records re-expand the interned user stack")
                .extracting(CaptureProto.Frame::getSymbol)
                .containsExactly("user_wait");
        assertThat(analysis.matches().get(0).sample().getFramesList())
                .as("a match still carries its resolved JFR frames")
                .isNotEmpty();

        // The CLI writes the same bytes with --audit full as the retained path did.
        Path streamed = dir.resolve("streamed");
        assertThat(correlate(
                        "--source", source.toString(),
                        "--jfr", jfr.toString(),
                        "--output", streamed.toString(),
                        "--audit", "full"))
                .isZero();
        Path retained = dir.resolve("retained");
        OffCpuCorrelator.write(analysis, retained);
        for (String name : List.of(OutputFiles.COLLAPSED, OutputFiles.CLASSIFIED_RECORDS, OutputFiles.MATCHES)) {
            assertThat(streamed.resolve(name))
                    .as("streamed and retained " + name)
                    .hasSameTextualContentAs(retained.resolve(name));
        }
        ReportProto.Report.Builder streamedReport =
                CorrelationFixture.report(streamed.resolve(OutputFiles.REPORT)).toBuilder();
        ReportProto.Report.Builder retainedReport =
                CorrelationFixture.report(retained.resolve(OutputFiles.REPORT)).toBuilder();
        // Only the streamed path has the columns a stack profile and the switch-out reasons are built from.
        assertThat(streamedReport.hasStackProfile())
                .as("the streamed report describes its stack profile")
                .isTrue();
        assertThat(streamedReport.hasOffCpuReasons())
                .as("the streamed report accounts for switch-out reasons")
                .isTrue();
        // The digest is made from the stack profile, so only the streamed run has one.
        assertThat(streamedReport.getDigest().getPath())
                .as("the streamed report names its digest")
                .isEqualTo(OutputFiles.SUMMARY_MD);
        streamedReport.clearStackProfile().clearDigest().clearOffCpuReasons();
        assertThat(streamed.resolve(OutputFiles.PROFILE))
                .as("the streamed run writes a stack profile")
                .isRegularFile();
        for (ReportProto.Report.Builder report : List.of(streamedReport, retainedReport)) {
            // The streamed path builds a real ladder from the CLI's own limits and pre-decode estimate
            // (one settings() call even when nothing was needed); the retained/library path's
            // OutputOptions.defaults() ladder is inert (Degradation.none()), with no limit or estimate
            // of its own and no measured peak. Both report an empty stepsApplied either way, which is
            // what this equivalence check cares about.
            report.getDegradationBuilder()
                    .clearAttempts()
                    .clearPeakRetainedBytes()
                    .clearRetainedBytesLimit()
                    .clearEstimatedRetainedBytes();
        }
        assertThat(streamedReport.build())
                .as("reports beyond the degradation-measurement fields")
                .isEqualTo(retainedReport.build());
    }

    /** Spec §1: the audit outputs are the only consumers of the per-row documents. */
    @Test
    void auditLevels(@TempDir Path dir) throws Exception {
        Path jfr = CorrelationFixture.recording(dir, 1);
        Path source = capture(dir, jfr, sampleThreadId(jfr), 1);
        for (String level : List.of("full", "matches", "none")) {
            Path output = dir.resolve("audit-" + level);
            assertThat(correlate(
                            "--source", source.toString(),
                            "--jfr", jfr.toString(),
                            "--output", output.toString(),
                            "--format", "collapsed",
                            "--audit", level))
                    .isZero();
            assertThat(Files.exists(output.resolve(OutputFiles.CLASSIFIED_RECORDS)))
                    .as("--audit " + level + " writes classified records only at full")
                    .isEqualTo(level.equals("full"));
            assertThat(Files.exists(output.resolve(OutputFiles.MATCHES)))
                    .as("--audit " + level + " writes matches unless none")
                    .isEqualTo(!level.equals("none"));
            assertThat(output.resolve(OutputFiles.COLLAPSED))
                    .as("--audit never affects the collapsed stacks")
                    .isRegularFile();
            assertThat(CorrelationFixture.report(output.resolve(OutputFiles.REPORT))
                            .getAudit())
                    .as("the report records the audit level")
                    .isEqualTo(level);
        }
    }

    /** Spec acceptance 7: thinning is usable only if the towers keep their proportions. */
    @Test
    void thinningAccuracy() throws Exception {
        int rows = 200_000;
        ScaleFixture.Input input = ScaleFixture.input(inputs, rows, 8, true);
        var limits = new OfflineCorrelator.Limits(rows * 2 + 16, 1024 * 1024, 400L << 20, 4096, null, null, null);
        var exact = CorrelationEngine.correlate(input.source(), input.jfr(), limits, null, false, Thinning.NONE);
        var thinned =
                CorrelationEngine.correlate(input.source(), input.jfr(), limits, null, false, Thinning.of("0.1", 0));
        List<String> exactTop = topStacks(exact, 5);
        assertThat(topStacks(thinned, 5))
                .as("thinning keeps the top stacks' order")
                .isEqualTo(exactTop);
        for (String key : exactTop) {
            BigInteger full = weight(exact, key);
            BigInteger estimate = weight(thinned, key);
            assertThat(estimate.subtract(full).abs().multiply(BigInteger.valueOf(100)))
                    .as("the reweighted total for " + key + " (" + estimate + ") is within 10% of the exact " + full)
                    .isLessThanOrEqualTo(full.multiply(BigInteger.TEN));
        }
    }

    /** Spec acceptance 8: the same capture, the same q, byte-identical output, order-independent. */
    @Test
    void thinningDeterminism(@TempDir Path dir) throws Exception {
        int rows = 20_000;
        ScaleFixture.Input input = ScaleFixture.input(inputs, rows, 8, false);
        Path first = dir.resolve("thinned-1");
        Path second = dir.resolve("thinned-2");
        Path third = dir.resolve("thinned-3");
        Path shuffled = ScaleFixture.shuffledCapture(dir, input.jfr(), rows, 20260922L);
        for (var run :
                List.of(List.of(input.source(), first), List.of(input.source(), second), List.of(shuffled, third))) {
            assertThat(correlate(
                            "--source",
                            run.get(0).toString(),
                            "--jfr",
                            input.jfr().toString(),
                            "--output",
                            run.get(1).toString(),
                            "--format",
                            "collapsed",
                            "--audit",
                            "none",
                            "--thinning",
                            "0.1"))
                    .isZero();
        }
        assertThat(first.resolve(OutputFiles.COLLAPSED))
                .as("the same capture and q produce the same collapsed stacks")
                .hasSameTextualContentAs(second.resolve(OutputFiles.COLLAPSED));
        assertThat(third.resolve(OutputFiles.COLLAPSED))
                .as("shuffling the capture does not change the thinned result")
                .hasSameTextualContentAs(first.resolve(OutputFiles.COLLAPSED));
    }

    /** The top {@code limit} collapsed stacks by thinning-reweighted duration, descending, ties broken on key. */
    private static List<String> topStacks(CorrelationResult result, int limit) {
        record Entry(String key, BigInteger weight) {}
        List<Entry> entries = new ArrayList<>();
        for (int id = 0; id < result.collapsedNanos().length; id++) {
            long nanos = result.collapsedNanos()[id];
            if (nanos > 0) {
                entries.add(new Entry(
                        result.dictionaries().collapsedKey(id),
                        result.thinning().scale(nanos)));
            }
        }
        entries.sort(
                Comparator.comparing(Entry::weight, Comparator.reverseOrder()).thenComparing(Entry::key));
        return entries.stream().limit(limit).map(Entry::key).toList();
    }

    /** The thinning-reweighted duration of the given collapsed stack key. */
    private static BigInteger weight(CorrelationResult result, String key) {
        for (int id = 0; id < result.collapsedNanos().length; id++) {
            if (result.dictionaries().collapsedKey(id).equals(key)) {
                return result.thinning().scale(result.collapsedNanos()[id]);
            }
        }
        throw new AssertionError("Stack not found: " + key);
    }
}
