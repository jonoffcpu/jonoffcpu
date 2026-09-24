// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
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
            report.getSyntheticJfrBuilder()
                    .clearQuantumNanos()
                    .clearRequestedQuantumNanos()
                    .clearObservedQuantumNanos()
                    .clearQuantumRaisedForEventLimit();
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
                .as("reports beyond the synthetic quantum and degradation-measurement fields")
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

    /** The synthetic view is built from interned stacks, not from retained sample documents. */
    @Test
    void syntheticFromColumns(@TempDir Path dir) throws Exception {
        Path jfr = CorrelationFixture.recording(dir, 1);
        Path source = capture(dir, jfr, sampleThreadId(jfr), 3);
        var analysis = OfflineCorrelator.correlate(source, jfr, OfflineCorrelator.Limits.defaults());
        Path fromAnalysis = dir.resolve("synthetic-analysis.jfr");
        var viaAnalysis = CompatibilityJfrWriter.write(
                analysis, fromAnalysis, new CompatibilityJfrWriter.Options(1000L, 1_000_000L));
        Path fromColumns = dir.resolve("synthetic-columns.jfr");
        var result = CorrelationEngine.correlate(source, jfr, OfflineCorrelator.Limits.defaults(), null, false);
        var viaColumns = CompatibilityJfrWriter.write(
                SyntheticJfrSource.of(result), fromColumns, new CompatibilityJfrWriter.Options(1000L, 1_000_000L));
        assertThat(viaColumns.syntheticEvents()).isEqualTo(viaAnalysis.syntheticEvents());
        assertThat(viaColumns.canonicalStacks()).isEqualTo(viaAnalysis.canonicalStacks());
        assertThat(viaColumns.representedNanos()).isEqualTo(viaAnalysis.representedNanos());
        assertThat(viaColumns.omittedRemainderNanos()).isEqualTo(viaAnalysis.omittedRemainderNanos());

        // The count/total equality above says nothing about *when* the events land: a dropped or
        // sign-flipped epoch offset would shift every synthetic timestamp by a constant and still pass
        // it. The matched sample's real JFR startTime (wall clock) and monotonicTimeNanos (JVM-relative
        // nanoTime) are naturally an astronomically large, non-zero distance apart, so a broken offset
        // formula lands far outside any plausible epoch and this check catches it.
        List<Long> analysisTimestamps = executionSampleEpochNanos(fromAnalysis);
        List<Long> columnsTimestamps = executionSampleEpochNanos(fromColumns);
        assertThat(analysisTimestamps)
                .as("the fixture produces at least one synthetic event")
                .isNotEmpty();
        assertThat(columnsTimestamps)
                .as("synthetic event timestamps of the column-backed and retained plans")
                .isEqualTo(analysisTimestamps);
        // Sanity-check that the offset is genuinely distinctive (a real wall-clock epoch, not a
        // near-1970 artifact of a dropped/zeroed offset).
        assertThat(analysisTimestamps.get(0) / 1_000_000L)
                .as("a synthetic event timestamp that looks like a real wall-clock epoch, in milliseconds")
                .isGreaterThan(1_600_000_000_000L);
    }

    private static List<Long> executionSampleEpochNanos(Path file) throws IOException {
        List<Long> timestamps = new ArrayList<>();
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                if (event.getEventType().getName().equals("jdk.ExecutionSample")) {
                    timestamps.add(event.getStartTime().getEpochSecond() * 1_000_000_000L
                            + event.getStartTime().getNano());
                }
            }
        }
        return timestamps;
    }

    /**
     * Two matched intervals with identical {@code fromNanos}/{@code toNanos} but different cookies,
     * inserted in descending-cookie source order. The column-backed and retained plans must both sort
     * them ascending by cookie, exercising the tie-break the sequential test fixtures never force.
     */
    @Test
    void syntheticOrderTiesBreakOnCookie(@TempDir Path dir) throws Exception {
        Path jfr = recordingWithSequentialCorrelationIds(dir, 2);
        long tid = sampleThreadId(jfr);
        CaptureProto.Observation second = CorrelationFixture.observation(tid)
                .setCorrelationId(0x8000000100000002L)
                .build();
        CaptureProto.Observation first = CorrelationFixture.observation(tid)
                .setCorrelationId(0x8000000100000001L)
                .build();
        // Source-file order deliberately puts the higher cookie first: preserving capture order instead
        // of sorting by cookie would still pass without this check.
        Path source = CorrelationFixture.source(dir, jfr, List.of(second, first));
        var analysis = OfflineCorrelator.correlate(source, jfr, OfflineCorrelator.Limits.defaults());
        assertThat(analysis.matched())
                .as("the tie-break fixture matches both intervals")
                .isEqualTo(2);
        var result = CorrelationEngine.correlate(source, jfr, OfflineCorrelator.Limits.defaults(), null, false);

        List<Long> viaAnalysisOrder = new ArrayList<>();
        SyntheticJfrSource.of(analysis).forEachInterval(interval -> viaAnalysisOrder.add(interval.correlationId()));
        List<Long> viaColumnsOrder = new ArrayList<>();
        SyntheticJfrSource.of(result).forEachInterval(interval -> viaColumnsOrder.add(interval.correlationId()));
        assertThat(viaAnalysisOrder)
                .as("the tie-break sorts ascending by cookie")
                .containsExactly(0x8000000100000001L, 0x8000000100000002L);
        assertThat(viaColumnsOrder)
                .as("the column-backed tie-break order matches the retained one")
                .isEqualTo(viaAnalysisOrder);
    }

    private static Path recordingWithSequentialCorrelationIds(Path dir, int count) throws IOException {
        Path file = dir.resolve("sequential-" + count + ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(CorrelationFixture.Capture.class);
            recording.enable(CorrelationFixture.Sample.class).withStackTrace();
            recording.enable(CorrelationFixture.Stats.class);
            recording.start();
            new CorrelationFixture.Capture().commit();
            for (int i = 0; i < count; i++) {
                CorrelationFixture.Sample sample = new CorrelationFixture.Sample();
                sample.correlationId = (sample.correlationId & 0xffffffff00000000L) | (i + 1);
                sample.commit();
            }
            CorrelationFixture.Stats stats = new CorrelationFixture.Stats();
            stats.admittedSignals = stats.acceptedCookies = stats.submittedSamples = count;
            stats.commit();
            recording.stop();
            recording.dump(file);
        }
        return file;
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
