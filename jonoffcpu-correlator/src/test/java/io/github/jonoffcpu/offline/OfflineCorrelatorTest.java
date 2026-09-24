// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.offline;

import static io.github.jonoffcpu.capture.CaptureFixtures.record;
import static io.github.jonoffcpu.offline.CorrelationFixture.KERNEL_STACK_ID;
import static io.github.jonoffcpu.offline.CorrelationFixture.concat;
import static io.github.jonoffcpu.offline.CorrelationFixture.mutateSource;
import static io.github.jonoffcpu.offline.CorrelationFixture.observation;
import static io.github.jonoffcpu.offline.CorrelationFixture.partialRecording;
import static io.github.jonoffcpu.offline.CorrelationFixture.readRecords;
import static io.github.jonoffcpu.offline.CorrelationFixture.recording;
import static io.github.jonoffcpu.offline.CorrelationFixture.report;
import static io.github.jonoffcpu.offline.CorrelationFixture.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;

import io.github.jonoffcpu.capture.CaptureFixtures;
import io.github.jonoffcpu.capture.CaptureFormat;
import io.github.jonoffcpu.capture.CaptureProto;
import io.github.jonoffcpu.capture.CaptureProto.Record.RecordCase;
import io.github.jonoffcpu.capture.CaptureRecordFixture;
import io.github.jonoffcpu.offline.ReportProto.EstimateStatus;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real JFR reader plus synthetic source fixtures, including ambiguity, corruption and clipping. */
class OfflineCorrelatorTest {
    private static void rejects(Path source, Path jfr, OfflineCorrelator.Limits limits, String reason) {
        assertThatIOException()
                .as("Expected rejection: " + reason)
                .isThrownBy(() -> OfflineCorrelator.correlate(source, jfr, limits))
                .withMessageContaining(reason);
    }

    private static final OfflineCorrelator.Limits DEFAULTS = OfflineCorrelator.Limits.defaults();

    /** A one-sample JFR, the observation that matches its sample, and the finalized source holding it. */
    private record Fixture(Path jfr, long tid, CaptureProto.Observation observation, Path source) {}

    private static Fixture fixture(Path dir) throws IOException {
        Path jfr = recording(dir, 1);
        long tid = CorrelationFixture.sampleThread(jfr);
        CaptureProto.Observation observation = observation(tid).build();
        return new Fixture(jfr, tid, observation, source(dir, jfr, List.of(observation)));
    }

    /** Adjusts the {@code capture_end} kernel counters. */
    private static Consumer<CaptureProto.Record.Builder> kernel(Consumer<CaptureProto.KernelCounters.Builder> change) {
        return record -> change.accept(record.getCaptureEndBuilder().getKernelCountersBuilder());
    }

    @Test
    void jfrTimeParsing() throws IOException {
        Instant parserBase = Instant.parse("2026-09-21T10:15:30Z");
        assertThat(JfrTimeRange.parse("5s", parserBase))
                .as("Relative JFR time was not measured from recording start")
                .isEqualTo(parserBase.plusSeconds(5));
        assertThat(JfrTimeRange.parse("PT0.5S", parserBase))
                .as("ISO-8601 JFR duration was not accepted")
                .isEqualTo(parserBase.plusMillis(500));
        assertThat(JfrTimeRange.parse(Long.toString(parserBase.toEpochMilli()), Instant.EPOCH))
                .as("Epoch-millisecond JFR boundary was not accepted")
                .isEqualTo(parserBase);
    }

    @Test
    void completeAnalysis(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        var result = OfflineCorrelator.correlate(fixture.source(), fixture.jfr(), DEFAULTS);
        assertThat(result.matched()).as("High-bit cookie match failed").isEqualTo(1);
        assertThat(result.identityUnverified())
                .as("High-bit cookie match failed")
                .isZero();
        assertThat(result.selectedObservedDurationNanos())
                .as("Duration weight mismatch")
                .isEqualTo(3000);
        assertThat(result.matches().get(0).handlerDelayNanos())
                .as("Delivery delay mismatch")
                .isEqualTo(BigInteger.valueOf(1000));
        assertThat(result.collapsedNanos()).as("Collapsed duration missing").containsValue("3000");
        Path output = dir.resolve("analysis");
        OffCpuCorrelator.write(result, output);
        assertThat(output.resolve(OutputFiles.COMPLETE))
                .as("Missing output completion marker")
                .isRegularFile();
        assertThat(CorrelationFixture.marker(output.resolve(OutputFiles.COMPLETE))
                        .getState())
                .isEqualTo(ReportProto.MarkerState.MARKER_STATE_COMPLETE);
        assertThat(Files.readString(output.resolve(OutputFiles.COLLAPSED)).strip())
                .as("Collapsed output is not weighted in integer microseconds")
                .endsWith(" 3");
        ReportProto.Report report = report(output.resolve(OutputFiles.REPORT));
        assertThat(report.getStackSemantics()).as("Missing stack caveat").contains("signal-delivery stack");
        assertThat(report.getHandlerDelayNanos().getP99())
                .as("Delivery delay percentile missing")
                .isEqualTo(1000);
        assertThat(report.hasPopulationEstimate())
                .as("Population estimate must be opt-in")
                .isFalse();
        assertThat(output.resolve(OutputFiles.SYNTHETIC_JFR))
                .as("Missing default JFR output")
                .isRegularFile();
        try (var listing = Files.list(output)) {
            Set<String> names =
                    listing.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
            assertThat(names)
                    .as("Complete analysis wrote an unexpected file set")
                    .containsExactlyInAnyOrder(
                            OutputFiles.REPORT,
                            OutputFiles.COLLAPSED,
                            OutputFiles.SYNTHETIC_JFR,
                            OutputFiles.CLASSIFIED_RECORDS,
                            OutputFiles.MATCHES,
                            OutputFiles.COMPLETE);
            assertThat(names)
                    .as("Every output must carry the jonoffcpu- prefix")
                    .allMatch(name -> name.startsWith(OutputFiles.PREFIX));
        }
        List<ReportProto.Pair> pairs = CorrelationFixture.pairs(output.resolve(OutputFiles.MATCHES));
        assertThat(pairs).as("Match row missing").hasSize(1);
        assertThat(pairs.get(0).getCorrelationId()).isEqualTo(CorrelationFixture.COOKIE);
        assertThat(pairs.get(0).getHandlerDelayNanos()).isEqualTo(1000);
        List<ReportProto.ClassifiedRecord> records =
                CorrelationFixture.classifiedRecords(output.resolve(OutputFiles.CLASSIFIED_RECORDS));
        assertThat(records)
                .as("Classified records must hold the matched source row and sample")
                .extracting(ReportProto.ClassifiedRecord::getClassification)
                .containsExactly(
                        ReportProto.Classification.CLASSIFICATION_MATCHED,
                        ReportProto.Classification.CLASSIFICATION_MATCHED);
        assertThat(records.get(0).getSource().getKernelFramesList())
                .as("The classified source row must re-expand its interned kernel stack")
                .extracting(CaptureProto.Frame::getSymbol)
                .containsExactly("kernel_wait");
        assertThat(report.hasSyntheticJfr())
                .as("Missing JFR quantization metadata")
                .isTrue();
        // Existing output must be preserved, even if its directory is empty.
        assertThatExceptionOfType(FileAlreadyExistsException.class)
                .as("Existing analysis overwritten")
                .isThrownBy(() -> OffCpuCorrelator.write(result, output));
    }

    @Test
    void jfrTimeSelection(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Path jfr = fixture.jfr();
        var startTime = CorrelationFixture.samples(jfr).get(0).getStartTime();
        Instant sampleTime = Instant.ofEpochSecond(startTime.getSeconds(), startTime.getNanos());
        JfrTimeRange.Range beforeSample = JfrTimeRange.resolve(jfr, null, sampleTime.toString());
        var result = OfflineCorrelator.correlate(
                source,
                jfr,
                DEFAULTS,
                new OfflineCorrelator.JfrSelection(beforeSample.from(), beforeSample.to(), false));
        assertThat(result.matched())
                .as("JFR event-time selection retained an event at the exclusive boundary")
                .isZero();
        assertThat(result.unmatchedSource())
                .as("JFR event-time selection retained an event at the exclusive boundary")
                .isEqualTo(1);
        assertThat(result.records())
                .as("Selected-range source omission was not explained")
                .anyMatch(
                        row -> row.getReason() == ReportProto.RowReason.ROW_REASON_SAMPLE_NOT_PRESENT_IN_SELECTED_JFR);
        Path selectedOutput = dir.resolve("analysis-selected-range");
        assertThat(OffCpuCorrelator.run(new String[] {
                    "--source",
                    source.toString(),
                    "--jfr",
                    jfr.toString(),
                    "--output",
                    selectedOutput.toString(),
                    "--format",
                    "collapsed",
                    "--to",
                    sampleTime.toString()
                }))
                .isZero();
        ReportProto.Report rangeReport = report(selectedOutput.resolve(OutputFiles.REPORT));
        assertThat(rangeReport.getJfrSelection().getMissingSourceMatchesExpected())
                .as("JFR selection metadata did not explain expected missing matches")
                .isTrue();
        assertThat(rangeReport.getSourceRowsWithoutSelectedJfrSample())
                .as("Expected selected-JFR omission count was not reported")
                .isEqualTo(1);
    }

    @Test
    void partialJfr(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Path partialJfr = partialRecording(dir, 1);
        rejects(source, partialJfr, DEFAULTS, "JFR byte count mismatch");
        var result = OfflineCorrelator.correlate(
                source, partialJfr, DEFAULTS, new OfflineCorrelator.JfrSelection(null, null, true));
        assertThat(result.matched())
                .as("A valid sample-only partial JFR did not correlate")
                .isEqualTo(1);
        assertThat(result.submittedButNotParsed())
                .as("A valid sample-only partial JFR did not correlate")
                .isNull();
        assertThat(result.jfrSelection().getCaptureContextPresent())
                .as("Partial JFR unexpectedly claimed omitted metadata: %s", result.jfrSelection())
                .isFalse();
        assertThat(result.jfrSelection().getTerminalStatsPresent())
                .as("Partial JFR unexpectedly claimed omitted metadata: %s", result.jfrSelection())
                .isFalse();
        Path partialOutput = dir.resolve("analysis-partial-jfr");
        assertThat(OffCpuCorrelator.run(new String[] {
                    "--source",
                    source.toString(),
                    "--jfr",
                    partialJfr.toString(),
                    "--output",
                    partialOutput.toString(),
                    "--format",
                    "collapsed",
                    "--partial-jfr",
                    "true"
                }))
                .isZero();
        assertThat(partialOutput.resolve(OutputFiles.COMPLETE))
                .as("Partial JFR analysis did not complete")
                .isRegularFile();
    }

    @Test
    void populationEstimate(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path estimated = dir.resolve("analysis-estimate");
        assertThat(OffCpuCorrelator.run(new String[] {
                    "--source",
                    fixture.source().toString(),
                    "--jfr",
                    fixture.jfr().toString(),
                    "--output",
                    estimated.toString(),
                    "--format",
                    "collapsed",
                    "--estimate-population",
                    "true"
                }))
                .isZero();
        ReportProto.PopulationEstimate estimate =
                report(estimated.resolve(OutputFiles.REPORT)).getPopulationEstimate();
        assertThat(estimate.getMethod())
                .as("Population estimate method missing")
                .isEqualTo("inverse-probability-source-duration");
        assertThat(estimate.getStatus())
                .as("Source estimate unavailable")
                .isEqualTo(EstimateStatus.ESTIMATE_STATUS_AVAILABLE);
        assertThat(estimate.getSourceSelectedObservedDurationNanos())
                .as("Source duration estimate basis mismatch")
                .isEqualTo(3000);
        assertThat(estimate.getMatchedSelectedObservedDurationNanos())
                .as("Matched duration estimate basis mismatch")
                .isEqualTo(3000);
        assertThat(estimate.getAdmissionPolicy())
                .as("Population estimate lost policy")
                .isEqualTo("uniform");
        assertThat(estimate.getEstimatedDurationNanos())
                .as("Population estimate is not the truncated exact inverse-probability sum")
                .isEqualTo(BigInteger.valueOf(3000)
                        .shiftLeft(32)
                        .divide(BigInteger.valueOf(CorrelationFixture.THRESHOLD))
                        .longValueExact());
    }

    /**
     * Under the proportional policy each row carries the threshold the kernel drew against: 2^32 at and above the
     * reference duration, duration * 2^32 / reference below it. The estimate weights each row by its own threshold.
     */
    @Test
    void proportionalEstimate(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        CaptureProto.Sampling proportional =
                CaptureFixtures.proportionalSampling(2, CaptureProto.OffCpuReason.OFF_CPU_REASON_BLOCKED);
        CaptureProto.Observation certain = fixture.observation().toBuilder()
                .setAdmissionThreshold(1L << 32)
                .build();
        CaptureProto.Observation half = observation(fixture.tid())
                .setCorrelationId(CorrelationFixture.COOKIE + 1)
                .setStartMonotonicNanos(4000)
                .setEndMonotonicNanos(5000)
                .setAdmissionThreshold(1L << 31)
                .build();
        Path source = source(dir, jfr, List.of(certain, half), proportional);
        var result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource())
                .as("Proportional rows were not accepted")
                .isZero();
        assertThat(result.matched()).as("Proportional rows were not accepted").isEqualTo(1);
        var estimate = result.populationEstimate();
        String weighting = "Proportional estimate must weight the half-probability row twice: " + estimate;
        assertThat(estimate.getStatus()).as(weighting).isEqualTo(EstimateStatus.ESTIMATE_STATUS_AVAILABLE);
        assertThat(estimate.getAdmissionPolicy()).as(weighting).isEqualTo("proportional");
        assertThat(estimate.getSourceSelectedObservedDurationNanos())
                .as(weighting)
                .isEqualTo(4000);
        assertThat(estimate.getEstimatedDurationNanos()).as(weighting).isEqualTo(5000);
        CaptureProto.Observation wrong =
                half.toBuilder().setAdmissionThreshold(1L << 32).build();
        source = source(dir, jfr, List.of(certain, wrong), proportional);
        result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource())
                .as("Row threshold that disagrees with the policy was accepted")
                .isEqualTo(1);
        assertThat(result.populationEstimate().getStatus())
                .as("Invalid row must disable the estimate")
                .isEqualTo(EstimateStatus.ESTIMATE_STATUS_UNAVAILABLE);
        assertThat(result.populationEstimate().getUnavailableReasonsList())
                .as("Invalid row must disable the estimate")
                .contains("intrinsically-invalid-or-duplicate-source-rows");
        source = source(dir, jfr, List.of(certain, half), proportional);
        mutateSource(
                source,
                RecordCase.CAPTURE_START,
                record -> record.getCaptureStartBuilder()
                        .getSamplingBuilder()
                        .getProportionalBuilder()
                        .setRecordAllAboveMicros(3));
        rejects(source, jfr, DEFAULTS, "Source/footer mismatch: sampling");
        CaptureProto.Sampling bounded =
                proportional.toBuilder().setMinOffCpuMicros(1).build();
        source = source(dir, jfr, List.of(certain, half), bounded);
        result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource())
                .as("Strict lower bound did not reject the 1000 ns row")
                .isEqualTo(1);
    }

    /** One row's fixed-point weight: 3000 ns under the uniform threshold, 64 fraction bits below one. */
    private static final BigInteger ROW_WEIGHT =
            BigInteger.valueOf(3000).shiftLeft(32 + 64).divide(BigInteger.valueOf(CorrelationFixture.THRESHOLD));

    /** One contended interval: selection counts three intervals, the source holds two rows. */
    private static final Consumer<CaptureProto.Record.Builder> CONTENDED = kernel(
            counters -> counters.setSelectedIntervals(3).setEligibleIntervals(3).setSequenceContentions(1));

    /** The matched observation plus one with no JFR sample, both selected. */
    private static List<CaptureProto.Observation> contentionRows(Fixture fixture) {
        CaptureProto.Observation unmatched = observation(fixture.tid())
                .setCorrelationId(CorrelationFixture.COOKIE + 1)
                .build();
        return List.of(fixture.observation(), unmatched);
    }

    /** A two-row source whose kernel counters say one more interval was selected and lost to contention. */
    private static Path contendedSource(Path dir, Fixture fixture) throws IOException {
        Path source = source(dir, fixture.jfr(), contentionRows(fixture));
        mutateSource(source, RecordCase.CAPTURE_END, CONTENDED);
        return source;
    }

    /**
     * Sequence contention drops a selected interval after the kernel counted it, and counts the drop. When that
     * count alone explains the gap between selection and the rows, the estimate is scaled by selected / received
     * and reports the loss; any other discrepancy keeps the reasons it always had, and a loss above the limit is
     * refused.
     */
    @Test
    void sequenceContentionEstimate(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        List<CaptureProto.Observation> rows = contentionRows(fixture);
        var lenient = new OfflineCorrelator.Limits(
                100_000_000, 1024 * 1024, 256L << 20, 4096, null, null, null, new BigDecimal("0.5"));
        BigInteger rowWeight = ROW_WEIGHT;

        // No contention: exactly today's estimate, with nothing accounted and nothing assumed.
        Path source = source(dir, jfr, rows);
        var exact = OfflineCorrelator.correlate(source, jfr, lenient).populationEstimate();
        String zero = "Zero contention must keep the exact estimate: " + exact;
        assertThat(exact.getStatus()).as(zero).isEqualTo(EstimateStatus.ESTIMATE_STATUS_AVAILABLE);
        assertThat(exact.getSourceCoverageComplete()).as(zero).isTrue();
        assertThat(exact.hasAccountedLoss()).as(zero).isFalse();
        assertThat(exact.getAssumptionsList()).as(zero).isEmpty();
        assertThat(exact.getEstimatedDurationNanos())
                .as(zero)
                .isEqualTo(rowWeight.shiftLeft(1).shiftRight(64).longValueExact());

        // One contended interval explains the gap exactly: available, scaled by 3 / 2, loss reported.
        mutateSource(source, RecordCase.CAPTURE_END, CONTENDED);
        var accounted = OfflineCorrelator.correlate(source, jfr, lenient).populationEstimate();
        String scaled = "Counted contention must scale the estimate by selected / received: " + accounted;
        assertThat(accounted.getStatus()).as(scaled).isEqualTo(EstimateStatus.ESTIMATE_STATUS_AVAILABLE);
        assertThat(accounted.getUnavailableReasonsList()).as(scaled).isEmpty();
        assertThat(accounted.getSourceCoverageComplete()).as(scaled).isFalse();
        assertThat(accounted.getSourceRowsUsed()).as(scaled).isEqualTo(2);
        assertThat(accounted.hasAccountedLoss()).as(scaled).isTrue();
        assertThat(accounted.getAccountedLoss().getIntervals()).as(scaled).isEqualTo(1);
        assertThat(accounted.getAccountedLoss().getReason()).as(scaled).isEqualTo("sequence-contention");
        assertThat(accounted.getAccountedLoss().getFraction()).as(scaled).isEqualTo("0.333333");
        assertThat(accounted.getAssumptionsList())
                .as(scaled)
                .containsExactly(CorrelationEngine.CONTENTION_INDEPENDENCE);
        assertThat(accounted.getEstimatedDurationNanos())
                .as(scaled)
                .isEqualTo(rowWeight
                        .shiftLeft(1)
                        .multiply(BigInteger.valueOf(3))
                        .divide(BigInteger.TWO)
                        .shiftRight(64)
                        .longValueExact());

        // The same loss above the default 1 % limit is refused, and still reported.
        var refused = OfflineCorrelator.correlate(source, jfr, DEFAULTS).populationEstimate();
        String aboveLimit = "Accounted loss above the limit must be refused: " + refused;
        assertThat(refused.getStatus()).as(aboveLimit).isEqualTo(EstimateStatus.ESTIMATE_STATUS_UNAVAILABLE);
        assertThat(refused.hasEstimatedDurationNanos()).as(aboveLimit).isFalse();
        assertThat(refused.getUnavailableReasonsList()).as(aboveLimit).containsExactly("accounted-loss-above-limit");
        assertThat(refused.hasAccountedLoss()).as(aboveLimit).isTrue();
        assertThat(refused.getAccountedLoss().getIntervals()).as(aboveLimit).isEqualTo(1);

        // A gap the contention count does not explain keeps both of today's reasons.
        mutateSource(
                source,
                RecordCase.CAPTURE_END,
                kernel(counters -> counters.setSelectedIntervals(4).setEligibleIntervals(4)));
        var unexplained = OfflineCorrelator.correlate(source, jfr, lenient).populationEstimate();
        String todays = "Unexplained mismatch must stay unavailable with today's reasons: " + unexplained;
        assertThat(unexplained.getStatus()).as(todays).isEqualTo(EstimateStatus.ESTIMATE_STATUS_UNAVAILABLE);
        assertThat(unexplained.hasAccountedLoss()).as(todays).isFalse();
        assertThat(unexplained.getAssumptionsList()).as(todays).isEmpty();
        assertThat(unexplained.getUnavailableReasonsList())
                .as(todays)
                .containsExactly("nonzero-sequenceContentions", "selected-source-row-count-mismatch");

        // An explained gap next to any other failure counter is not accounted for either.
        source = source(dir, jfr, rows);
        mutateSource(
                source,
                RecordCase.CAPTURE_END,
                CONTENDED.andThen(kernel(counters -> counters.setRingReserveFailures(1))));
        var otherLoss = OfflineCorrelator.correlate(source, jfr, lenient).populationEstimate();
        String otherCounter = "Another nonzero failure counter must keep today's reasons: " + otherLoss;
        assertThat(otherLoss.getStatus()).as(otherCounter).isEqualTo(EstimateStatus.ESTIMATE_STATUS_UNAVAILABLE);
        assertThat(otherLoss.hasAccountedLoss()).as(otherCounter).isFalse();
        assertThat(otherLoss.getUnavailableReasonsList())
                .as(otherCounter)
                .containsExactly(
                        "nonzero-ringReserveFailures",
                        "nonzero-sequenceContentions",
                        "selected-source-row-count-mismatch");
    }

    /**
     * Through the CLI: the report carries the loss and the assumption, the profile's estimates are valid and scaled
     * like the total, and the default limit (an empty {@code limit}: no option given) refuses the same capture.
     */
    @ParameterizedTest(name = "--max-accounted-loss ''{0}''")
    @ValueSource(strings = {"0.5", ""})
    void sequenceContentionThroughCli(String limit, @TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = contendedSource(dir, fixture);
        Path output = dir.resolve("analysis-contention" + limit);
        List<String> args = new ArrayList<>(List.of(
                "--source",
                source.toString(),
                "--jfr",
                fixture.jfr().toString(),
                "--output",
                output.toString(),
                "--format",
                "collapsed",
                "--estimate-population",
                "true"));
        if (!limit.isEmpty()) args.addAll(List.of("--max-accounted-loss", limit));
        assertThat(OffCpuCorrelator.run(args.toArray(String[]::new)))
                .as("Contended capture did not complete")
                .isZero();
        ReportProto.Report report = report(output.resolve(OutputFiles.REPORT));
        ReportProto.PopulationEstimate estimate = report.getPopulationEstimate();
        ReportProto.AccountedLoss loss = estimate.getAccountedLoss();
        String carried = "Report must carry the accounted loss and its assumption: " + estimate;
        assertThat(loss.getIntervals()).as(carried).isEqualTo(1);
        assertThat(new BigDecimal(loss.getFraction())).as(carried).isEqualTo(new BigDecimal("0.333333"));
        assertThat(loss.getReason()).as(carried).isEqualTo("sequence-contention");
        assertThat(estimate.getAssumptionsList()).as(carried).hasSize(1);
        boolean available = !limit.isEmpty();
        String verdict = "CLI limit " + (available ? limit : "default") + " gave the wrong verdict: " + estimate;
        assertThat(estimate.getStatus())
                .as(verdict)
                .isEqualTo(
                        available
                                ? EstimateStatus.ESTIMATE_STATUS_AVAILABLE
                                : EstimateStatus.ESTIMATE_STATUS_UNAVAILABLE);
        assertThat(report.getStackProfile().getEstimateAvailable()).as(verdict).isEqualTo(available);
        StackProfile profile = StackProfile.read(output.resolve(OutputFiles.PROFILE));
        BigInteger expected =
                available ? ROW_WEIGHT.multiply(BigInteger.valueOf(3)).divide(BigInteger.TWO) : ROW_WEIGHT;
        assertThat(profile.header().estimateAvailable())
                .as("Profile estimate availability must follow the verdict")
                .isEqualTo(available);
        assertThat(profile.totalEstimatedNanos())
                .as("Profile estimates must take the same scale as the total")
                .isEqualTo(expected.shiftRight(64).longValueExact());
        assertThat(profile.header().report())
                .as("The profile must carry the report it was produced with")
                .isEqualTo(report);
    }

    @Test
    void accountedLossLimitUsageError(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = contendedSource(dir, fixture);
        CommandLineFixture.usageError(
                "Accounted-loss limit",
                "--source",
                source.toString(),
                "--jfr",
                fixture.jfr().toString(),
                "--output",
                dir.resolve("analysis-contention-bad").toString(),
                "--max-accounted-loss",
                "1");
    }

    @Test
    void unmatchedSourceDurationInEstimate(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        Path twoSource = source(dir, jfr, contentionRows(fixture));
        var two = OfflineCorrelator.correlate(twoSource, jfr, DEFAULTS);
        assertThat(two.populationEstimate().getSourceSelectedObservedDurationNanos())
                .as("Unmatched durable source duration omitted from estimate")
                .isEqualTo(6000);
        assertThat(two.populationEstimate().getMatchedSelectedObservedDurationNanos())
                .as("Stack-matched duration was incorrectly scaled")
                .isEqualTo(3000);
        mutateSource(twoSource, RecordCase.CAPTURE_END, kernel(counters -> counters.setSelectedIntervals(3)));
        two = OfflineCorrelator.correlate(twoSource, jfr, DEFAULTS);
        assertThat(two.populationEstimate().getStatus())
                .as("Missing selected source row did not disable population estimate")
                .isEqualTo(EstimateStatus.ESTIMATE_STATUS_UNAVAILABLE);
        assertThat(two.populationEstimate().getUnavailableReasonsList())
                .as("Missing selected source row did not disable population estimate")
                .contains("selected-source-row-count-mismatch");
    }

    /** A counter saturated at the u64 maximum proves nothing, so it cannot vouch for complete coverage. */
    @Test
    void estimatorCounterValidation(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Path jfr = fixture.jfr();
        mutateSource(source, RecordCase.CAPTURE_END, kernel(counters -> counters.setEligibleIntervals(-1L)));
        var result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.populationEstimate().getStatus())
                .as("Saturated source counter was treated as exact coverage")
                .isEqualTo(EstimateStatus.ESTIMATE_STATUS_UNAVAILABLE);
        assertThat(result.populationEstimate().getUnavailableReasonsList())
                .as("Saturated source counter was treated as exact coverage")
                .contains("saturated-counter-eligibleIntervals");
    }

    @ParameterizedTest(name = "--format {0}")
    @ValueSource(strings = {"collapsed", "jfr"})
    void outputFormatSelection(String format, @TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path selected = dir.resolve("analysis-" + format);
        assertThat(OffCpuCorrelator.run(new String[] {
                    "--source",
                    fixture.source().toString(),
                    "--jfr",
                    fixture.jfr().toString(),
                    "--output",
                    selected.toString(),
                    "--format",
                    format,
                    "--quantum-ns",
                    "1000"
                }))
                .isZero();
        assertThat(Files.exists(selected.resolve(OutputFiles.SYNTHETIC_JFR)))
                .as("JFR output format selection ignored")
                .isEqualTo(format.equals("jfr"));
        assertThat(Files.exists(selected.resolve(OutputFiles.COLLAPSED)))
                .as("Collapsed output format selection ignored")
                .isEqualTo(format.equals("collapsed"));
        ReportProto.Report selectedReport = report(selected.resolve(OutputFiles.REPORT));
        assertThat(selectedReport.hasSyntheticJfr())
                .as("Incorrect JFR metadata selection")
                .isEqualTo(format.equals("jfr"));
        if (format.equals("jfr")) {
            assertThat(selectedReport.getSyntheticJfr().getSyntheticEvents())
                    .as("CLI quantum was not applied")
                    .isEqualTo(3);
        }
    }

    @Test
    void clippedBeforeJoin(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        var clipped = new OfflineCorrelator.Limits(
                DEFAULTS.maxRows(),
                DEFAULTS.maxLineBytes(),
                DEFAULTS.maxRetainedBytes(),
                DEFAULTS.maxFrames(),
                null,
                BigInteger.valueOf(2000),
                BigInteger.valueOf(3000));
        var result = OfflineCorrelator.correlate(fixture.source(), fixture.jfr(), clipped);
        assertThat(result.matched()).as("Clipped before join").isEqualTo(1);
        assertThat(result.selectedObservedDurationNanos())
                .as("Clipped before join")
                .isEqualTo(1000);
    }

    @Test
    void deliveryDelayLimit(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        var delayed = new OfflineCorrelator.Limits(
                DEFAULTS.maxRows(),
                DEFAULTS.maxLineBytes(),
                DEFAULTS.maxRetainedBytes(),
                DEFAULTS.maxFrames(),
                BigInteger.valueOf(999),
                null,
                null);
        var result = OfflineCorrelator.correlate(fixture.source(), fixture.jfr(), delayed);
        assertThat(result.invalidSource()).as("Delivery delay not rejected").isEqualTo(1);
        assertThat(result.invalidJfr()).as("Delivery delay not rejected").isEqualTo(1);
        assertThat(result.records())
                .as("The delay rejection must be named")
                .allMatch(row -> row.getReason() == ReportProto.RowReason.ROW_REASON_HANDLER_DELAY_LIMIT_EXCEEDED);
    }

    @Test
    void ambiguity(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        CaptureProto.Observation observation = fixture.observation();
        Path source = source(dir, jfr, List.of(observation, observation));
        var result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource()).as("Ambiguity joined").isEqualTo(2);
        assertThat(result.invalidJfr()).as("Ambiguity joined").isEqualTo(1);
        assertThat(result.matched()).as("Ambiguity joined").isZero();
        assertThat(result.records())
                .as("Every copy of a duplicated cookie must be named")
                .allMatch(row -> row.getReason() == ReportProto.RowReason.ROW_REASON_DUPLICATE_COOKIE);
        // A duplicate that is invalid on its own still invalidates the valid copy.
        CaptureProto.Observation invalidCopy =
                observation.toBuilder().setRegistrationToken(2).build();
        source = source(dir, jfr, List.of(observation, invalidCopy));
        result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource())
                .as("Invalid duplicate escaped ambiguity check")
                .isEqualTo(2);
        assertThat(result.invalidJfr())
                .as("Invalid duplicate escaped ambiguity check")
                .isEqualTo(1);
    }

    @Test
    void wrongProcessRegistration(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        CaptureProto.Observation wrongBinding =
                fixture.observation().toBuilder().setRegistrationToken(2).build();
        Path source = source(dir, fixture.jfr(), List.of(wrongBinding));
        var result = OfflineCorrelator.correlate(source, fixture.jfr(), DEFAULTS);
        assertThat(result.invalidSource())
                .as("Wrong process registration joined")
                .isEqualTo(1);
        assertThat(result.invalidJfr()).as("Wrong process registration joined").isEqualTo(1);
    }

    @Test
    void sourceIntervalOutsideCapture(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        CaptureProto.Observation outside =
                fixture.observation().toBuilder().setStartMonotonicNanos(1).build();
        Path source = source(dir, fixture.jfr(), List.of(outside));
        var result = OfflineCorrelator.correlate(source, fixture.jfr(), DEFAULTS);
        assertThat(result.invalidSource())
                .as("Source interval outside capture joined")
                .isEqualTo(1);
        assertThat(result.invalidJfr())
                .as("Source interval outside capture joined")
                .isEqualTo(1);
    }

    @Test
    void duplicateJfrSample(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path duplicateJfr = recording(dir, 2);
        Path source = source(dir, duplicateJfr, List.of(fixture.observation()));
        var result = OfflineCorrelator.correlate(source, duplicateJfr, DEFAULTS);
        assertThat(result.invalidSource()).as("Duplicate JFR sample joined").isEqualTo(1);
        assertThat(result.invalidJfr()).as("Duplicate JFR sample joined").isEqualTo(2);
        String erased = "AP-only duplicate erased independently valid source duration";
        assertThat(result.populationEstimate().getStatus())
                .as(erased)
                .isEqualTo(EstimateStatus.ESTIMATE_STATUS_AVAILABLE);
        assertThat(result.populationEstimate().getSourceSelectedObservedDurationNanos())
                .as(erased)
                .isEqualTo(3000);
        assertThat(result.populationEstimate().getMatchedSelectedObservedDurationNanos())
                .as(erased)
                .isZero();
    }

    @Test
    void threadAndNamespaceIdentity(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        CaptureProto.Observation.Builder observation =
                fixture.observation().toBuilder().setTargetTid((int) fixture.tid() + 1);
        Path source = source(dir, jfr, List.of(observation));
        var result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource()).as("Thread mismatch joined").isEqualTo(1);
        assertThat(result.invalidJfr()).as("Thread mismatch joined").isEqualTo(1);
        observation.setTargetTid(0);
        source = source(dir, jfr, List.of(observation));
        rejects(source, jfr, DEFAULTS, "Missing target namespace TID");
        observation
                .setTargetTid((int) fixture.tid())
                .setTargetTgid((int) ProcessHandle.current().pid() + 1);
        source = source(dir, jfr, List.of(observation));
        result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource()).as("Wrong namespace TGID joined").isEqualTo(1);
        assertThat(result.invalidJfr()).as("Wrong namespace TGID joined").isEqualTo(1);
        source = source(dir, jfr, List.of(fixture.observation()));
        mutateSource(
                source,
                RecordCase.CAPTURE_START,
                record -> record.getCaptureStartBuilder()
                        .getVerifiedIdentityBuilder()
                        .setPidNamespaceInode(1));
        rejects(source, jfr, DEFAULTS, "Verified namespace mismatch");
    }

    /** Interned stacks: a reference must be announced first, and an id may be announced only once. */
    @Test
    void internedStacks(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        Path source = fixture.source();
        mutateSource(
                source,
                RecordCase.OBSERVATION,
                record -> record.getObservationBuilder().setKernelStackId(99));
        rejects(source, jfr, DEFAULTS, "Observation references an unannounced stack");
        source = source(dir, jfr, List.of(fixture.observation()));
        mutateSource(
                source,
                RecordCase.OBSERVATION,
                record -> record.getObservationBuilder().setKernelStackId(-7));
        rejects(source, jfr, DEFAULTS, "Unexplained negative stack id");
        source = source(dir, jfr, List.of(fixture.observation()));
        mutateSource(
                source, RecordCase.STACK, record -> record.getStackBuilder().setId(KERNEL_STACK_ID));
        rejects(source, jfr, DEFAULTS, "Duplicate stack record");
    }

    @Test
    void targetNamespaceFailures(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        mutateSource(source, RecordCase.CAPTURE_END, kernel(counters -> counters.setTargetNamespaceFailures(1)));
        rejects(source, fixture.jfr(), DEFAULTS, "Source target namespace mapping failed");
    }

    @Test
    void sourceIntegrity(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        Path source = fixture.source();
        byte[] valid = Files.readAllBytes(source);
        assertThat(readRecords(source)).as("fixture records missing").isNotEmpty();
        mutateSource(source, RecordCase.CAPTURE_FINALIZED, record -> {
            var footer = record.getCaptureFinalizedBuilder();
            footer.setApStopResponse(footer.getApStopResponse().replace("stopped-at=9000", "stopped-at=3000"));
        });
        var result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.matched())
                .as("Source interval after AP stop still joined")
                .isZero();
        assertThat(result.invalidSource())
                .as("Source interval after AP stop still joined")
                .isEqualTo(1);
        assertThat(result.invalidJfr())
                .as("Source interval after AP stop still joined")
                .isEqualTo(1);
        assertThat(result.records())
                .as("A pair after the AP stop must be named")
                .allMatch(row -> row.getReason() == ReportProto.RowReason.ROW_REASON_SAMPLE_FOR_SOURCE_AFTER_AP_STOP);
        // A record edited without re-sealing the prefix must fail the digest, not the semantics.
        List<CaptureProto.Record> tampered = new ArrayList<>();
        for (CaptureProto.Record record : readRecords(source)) {
            tampered.add(
                    record.hasObservation()
                            ? record.toBuilder()
                                    .setObservation(
                                            record.getObservation().toBuilder().setHostTid(457))
                                    .build()
                            : record);
        }
        Files.write(source, CaptureRecordFixture.encode(tampered));
        rejects(source, jfr, DEFAULTS, "Source digest mismatch");
        Files.write(source, java.util.Arrays.copyOf(valid, valid.length - 1));
        rejects(source, jfr, DEFAULTS, "truncated tail");
        Files.write(source, concat(valid, CaptureRecordFixture.encode(record(fixture.observation()))));
        rejects(source, jfr, DEFAULTS, "Rows follow");
        // A record without any of the known kinds is refused, not skipped.
        Files.write(
                source,
                concat(CaptureFormat.header(), CaptureRecordFixture.encode(CaptureProto.Record.getDefaultInstance())));
        rejects(source, jfr, DEFAULTS, "Unknown source record");
    }

    @ParameterizedTest(name = "{3}")
    @CsvSource({
        "1,   1048576, 1048576, row limit",
        "100, 10,      1048576, record byte limit",
        "100, 1048576, 256,     budget"
    })
    void inputLimits(int maxRows, int maxLineBytes, long maxRetainedBytes, String reason, @TempDir Path dir)
            throws IOException {
        Fixture fixture = fixture(dir);
        rejects(
                fixture.source(),
                fixture.jfr(),
                new OfflineCorrelator.Limits(maxRows, maxLineBytes, maxRetainedBytes, 4096, null, null, null),
                reason);
    }

    @Test
    void jfrDigest(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        byte[] original = Files.readAllBytes(jfr);
        original[original.length - 1] ^= 1;
        Files.write(jfr, original);
        rejects(fixture.source(), jfr, DEFAULTS, "JFR digest mismatch");
    }
}
