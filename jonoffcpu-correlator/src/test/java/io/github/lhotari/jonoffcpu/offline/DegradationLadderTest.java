// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The streaming correlator's degradation ladder under a retention budget: thinning, the watermark-driven retry,
 * window narrowing and refusal, and the audit pass over what each of them kept. The tests share one generated
 * fixture, and run in a class of their own so that the build can run them beside the other correlator tests.
 */
class DegradationLadderTest {
    /**
     * The rows of the fixture the ladder runs against, and how often the engine checks retention against the budget
     * while reading them. Narrowing cuts the window only at those watermarks, so a watermark far below the default
     * 65,536 rows lets a small fixture have several.
     */
    private static final int LADDER_ROWS = 10_000;

    private static final String WATERMARK_ROWS = "1024";

    /**
     * The budget, as a share of what an unconstrained run of the fixture retains at its peak: above the heaviest
     * thinning rung's floor, so thinning alone fits; below what an unthinned run retains by the last watermarks, so a
     * loose --thinning must retry and truncate must narrow; and below a full run, so --on-limit fail refuses. Measured
     * against this fixture, every rung holds from 0.85 to 0.99 of the peak. The absolute bytes depend on how the JVM
     * recorded the fixture's stacks, so they are measured, not pinned, and the assertions check each outcome, so a
     * share that drifts out of the window fails rather than passing vacuously.
     */
    private static final double BUDGET_SHARE = 0.92;

    private static long budgetBytes;

    /** The generated fixture the tests only read, built once for the class. */
    @TempDir
    static Path inputs;

    private static int correlate(String... args) throws Exception {
        String[] withWatermark = java.util.Arrays.copyOf(args, args.length + 2);
        withWatermark[args.length] = "--watermark-rows";
        withWatermark[args.length + 1] = WATERMARK_ROWS;
        return OffCpuCorrelator.run(withWatermark);
    }

    /** The ladder's budget for the shared fixture, measured once. */
    private static synchronized long budget(ScaleFixture.Input input) throws Exception {
        if (budgetBytes == 0) {
            Path unconstrained = inputs.resolve("unconstrained");
            assertThat(correlate(
                            "--source", input.source().toString(),
                            "--jfr", input.jfr().toString(),
                            "--output", unconstrained.toString(),
                            "--format", "collapsed",
                            "--audit", "none"))
                    .isZero();
            long peak = CorrelationFixture.report(unconstrained.resolve(OutputFiles.REPORT))
                    .getDegradation()
                    .getPeakRetainedBytes();
            budgetBytes = (long) (peak * BUDGET_SHARE);
        }
        return budgetBytes;
    }

    /** Spec acceptance 6: a small budget still produces a flame graph, and says what it cost. */
    @Test
    void ladder(@TempDir Path dir) throws Exception {
        ScaleFixture.Input input = ScaleFixture.input(inputs, LADDER_ROWS, 8, false);
        String source = input.source().toString();
        String jfr = input.jfr().toString();
        String budget = Long.toString(budget(input));

        Path thinnedOnly = dir.resolve("ladder-thinned");
        assertThat(correlate(
                        "--source", source,
                        "--jfr", jfr,
                        "--output", thinnedOnly.toString(),
                        "--format", "collapsed",
                        "--max-retained-bytes", budget))
                .as("thinning alone is still a complete analysis")
                .isZero();
        assertThat(thinnedOnly.resolve(OutputFiles.COMPLETE))
                .as("thinning keeps the ordinary names and the completion marker")
                .isRegularFile();
        assertThat(Files.size(thinnedOnly.resolve(OutputFiles.COLLAPSED)))
                .as("a degraded run still produces a flame graph")
                .isPositive();
        ReportProto.Report report = CorrelationFixture.report(thinnedOnly.resolve(OutputFiles.REPORT));
        assertThat(report.getDegradation().getStepsAppliedList())
                .as("the report names every ladder step applied")
                .extracting(ReportProto.DegradationStep::getStepCase)
                .contains(ReportProto.DegradationStep.StepCase.THIN_SOURCE);
        assertThat(report.hasSourceThinning())
                .as("a thinned analysis states its estimator")
                .isTrue();
        assertThat(report.getSourceThinning().getEstimator()).isEqualTo("inverse-probability");
        assertThat(report.getDegradation().hasNarrowedToNanos())
                .as("a thinned-only run reports no narrowed window")
                .isFalse();

        // The case above never leaves Degradation's constructor: the pre-decode estimate already picks
        // a rung that fits, so the retry loop's own thin-source rung (advance(), triggered by a
        // watermark exceeded mid-decode) is never exercised. Force that path by requesting a thinning
        // ratio explicit enough to bypass the constructor's proactive pick (Degradation only picks for
        // itself when the caller did not) but loose enough to still exceed the same budget once decoded.
        Path reactiveThinned = dir.resolve("ladder-reactive-thinned");
        assertThat(correlate(
                        "--source",
                        source,
                        "--jfr",
                        jfr,
                        "--output",
                        reactiveThinned.toString(),
                        "--format",
                        "collapsed",
                        "--thinning",
                        "0.9",
                        "--max-retained-bytes",
                        budget))
                .as("a watermark-driven thin-source retry is still a complete analysis")
                .isZero();
        ReportProto.DegradationReport reactiveDegradation = CorrelationFixture.report(
                        reactiveThinned.resolve(OutputFiles.REPORT))
                .getDegradation();
        assertThat(reactiveDegradation.getAttempts())
                .as("a too-loose --thinning forces a watermark-driven retry, not just the constructor's"
                        + " pre-decode pick: " + reactiveDegradation)
                .isGreaterThan(1);
        List<String> reactiveThinSourceReasons = new ArrayList<>();
        for (ReportProto.DegradationStep step : reactiveDegradation.getStepsAppliedList()) {
            if (step.hasThinSource()) reactiveThinSourceReasons.add(step.getReason());
        }
        assertThat(reactiveThinSourceReasons)
                .as("the watermark-driven rung records the watermark reason, not the pre-decode one")
                .contains("retained bytes reached the budget during the pass");

        Path narrowed = dir.resolve("ladder-narrowed");
        assertThat(correlate(
                        "--source",
                        source,
                        "--jfr",
                        jfr,
                        "--output",
                        narrowed.toString(),
                        "--format",
                        "collapsed",
                        "--on-limit",
                        "truncate",
                        "--max-retained-bytes",
                        budget))
                .as("a narrowed window is not the question that was asked")
                .isEqualTo(2);
        assertThat(narrowed.resolve(OutputFiles.COMPLETE))
                .as("a narrowed window does not look complete")
                .doesNotExist();
        assertThat(narrowed.resolve(OutputFiles.INCOMPLETE_COLLAPSED)).isRegularFile();
        assertThat(narrowed.resolve(OutputFiles.INCOMPLETE_REPORT)).isRegularFile();
        assertThat(narrowed.resolve(OutputFiles.NARROWED))
                .as("a narrowed window takes the INCOMPLETE names and its own marker")
                .isRegularFile();
        ReportProto.Marker marker = CorrelationFixture.marker(narrowed.resolve(OutputFiles.NARROWED));
        assertThat(marker.getState()).isEqualTo(ReportProto.MarkerState.MARKER_STATE_NARROWED);
        assertThat(marker.getCoverageComplete()).isFalse();
        assertThat(marker.getEffectiveToNanos())
                .as("the marker names where the narrowed window ends")
                .isEqualTo(CorrelationFixture.report(narrowed.resolve(OutputFiles.INCOMPLETE_REPORT))
                        .getDegradation()
                        .getNarrowedToNanos());

        Path refused = dir.resolve("ladder-fail");
        assertThatIOException()
                .as("--on-limit fail still refuses")
                .isThrownBy(() -> correlate(
                        "--source",
                        source,
                        "--jfr",
                        jfr,
                        "--output",
                        refused.toString(),
                        "--format",
                        "collapsed",
                        "--on-limit",
                        "fail",
                        "--max-retained-bytes",
                        budget))
                .withMessageContaining("budget");
    }

    /**
     * The audit re-read walks the rows correlation kept, not every row in the files.
     *
     * <p>Thinning and window narrowing make the columns hold only the kept rows, so an audit pass that
     * still assumed one column slot per file row indexed past the end of the columns and died with a
     * raw {@code ArrayIndexOutOfBoundsException} — on exactly the headline case this work exists for,
     * a full audit of a large capture under a tight budget. Both degradation mechanisms are covered:
     * an explicit {@code --thinning}, and the watermark-driven narrowing a {@code truncate} run takes.
     */
    @Test
    void auditUnderDegradation(@TempDir Path dir) throws Exception {
        ScaleFixture.Input input = ScaleFixture.input(inputs, LADDER_ROWS, 8, false);
        String source = input.source().toString();
        String jfr = input.jfr().toString();

        Path thinned = dir.resolve("audit-thinned");
        assertThat(correlate(
                        "--source",
                        source,
                        "--jfr",
                        jfr,
                        "--output",
                        thinned.toString(),
                        "--format",
                        "collapsed",
                        "--audit",
                        "full",
                        "--thinning",
                        "0.1"))
                .as("a full audit of a thinned run succeeds")
                .isZero();
        checkAuditMatchesColumns(thinned, OutputFiles.REPORT, OutputFiles.CLASSIFIED_RECORDS, OutputFiles.MATCHES);

        // The same budget and policy ladder() proves narrows: under truncate the ladder skips the audit-drop and
        // thin-source rungs, so --audit full survives all the way into a narrowed run.
        Path narrowed = dir.resolve("audit-narrowed");
        assertThat(correlate(
                        "--source",
                        source,
                        "--jfr",
                        jfr,
                        "--output",
                        narrowed.toString(),
                        "--format",
                        "collapsed",
                        "--audit",
                        "full",
                        "--on-limit",
                        "truncate",
                        "--max-retained-bytes",
                        Long.toString(budget(input))))
                .as("a narrowed full audit still reports an incomplete window")
                .isEqualTo(2);
        ReportProto.Report narrowedReport = CorrelationFixture.report(narrowed.resolve(OutputFiles.INCOMPLETE_REPORT));
        assertThat(narrowedReport.getDegradation().hasNarrowedToNanos())
                .as("the truncate sub-case must actually narrow, or it proves nothing about the audit pass")
                .isTrue();
        assertThat(narrowedReport.getAudit())
                .as("truncate must not quietly drop the requested audit level")
                .isEqualTo("full");
        assertThat(narrowedReport.getSourceRows())
                .as("a narrowed run must have dropped source rows, or the audit mapping is untested")
                .isLessThan(LADDER_ROWS);
        checkAuditMatchesColumns(
                narrowed,
                OutputFiles.INCOMPLETE_REPORT,
                OutputFiles.INCOMPLETE_CLASSIFIED_RECORDS,
                OutputFiles.INCOMPLETE_MATCHES);
    }

    /**
     * The audit file describes exactly the rows the columns hold: one line per kept source row and per
     * kept JFR sample, classified the way the counters say, and naming the same cookies the matches
     * file does. A pass that re-read the files row for row would fail every one of these.
     */
    private static void checkAuditMatchesColumns(Path output, String reportName, String recordsName, String matchesName)
            throws Exception {
        ReportProto.Report report = CorrelationFixture.report(output.resolve(reportName));
        long sourceRows = report.getSourceRows();
        long jfrSamples = report.getJfrSamples();
        long matched = report.getMatched();
        long unmatchedSource = report.getUnmatchedSource();
        long invalidSource = report.getInvalidSource();
        assertThat(List.of(sourceRows, jfrSamples, matched))
                .as("something was kept, so something is being checked")
                .allMatch(count -> count > 0);

        long sourceLines = 0;
        long jfrLines = 0;
        long matchedSourceLines = 0;
        Set<Long> auditMatchedCookies = new HashSet<>();
        Set<Long> auditSourceCookies = new HashSet<>();
        for (ReportProto.ClassifiedRecord entry : CorrelationFixture.classifiedRecords(output.resolve(recordsName))) {
            if (entry.hasSource()) {
                long cookie = entry.getSource().getObservation().getCorrelationId();
                sourceLines++;
                assertThat(auditSourceCookies.add(cookie))
                        .as("the audit pass emitted the same source row twice: " + Long.toHexString(cookie))
                        .isTrue();
                if (entry.getClassification() == ReportProto.Classification.CLASSIFICATION_MATCHED) {
                    matchedSourceLines++;
                    auditMatchedCookies.add(cookie);
                }
            } else {
                assertThat(entry.hasJfr())
                        .as("every audit row is a source row or a JFR row")
                        .isTrue();
                jfrLines++;
            }
        }
        assertThat(sourceLines)
                .as("audit source lines against kept source rows")
                .isEqualTo(sourceRows);
        assertThat(jfrLines).as("audit JFR lines against kept JFR samples").isEqualTo(jfrSamples);
        assertThat(sourceLines)
                .as("audit source lines add up to the report's own source classification counts")
                .isEqualTo(matched + unmatchedSource + invalidSource);
        assertThat(matchedSourceLines).as("audit matched source lines").isEqualTo(matched);

        Set<Long> matchesCookies = new HashSet<>();
        for (ReportProto.Pair pair : CorrelationFixture.pairs(output.resolve(matchesName))) {
            matchesCookies.add(pair.getCorrelationId());
        }
        assertThat(auditMatchedCookies)
                .as("the audit pass classifies as matched exactly the cookies the matches file names")
                .isEqualTo(matchesCookies);
    }
}
