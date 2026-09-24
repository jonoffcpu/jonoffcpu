// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static io.github.lhotari.jonoffcpu.offline.CorrelationFixture.KERNEL_STACK_ID;
import static io.github.lhotari.jonoffcpu.offline.CorrelationFixture.concat;
import static io.github.lhotari.jonoffcpu.offline.CorrelationFixture.observation;
import static io.github.lhotari.jonoffcpu.offline.CorrelationFixture.partialRecording;
import static io.github.lhotari.jonoffcpu.offline.CorrelationFixture.readRows;
import static io.github.lhotari.jonoffcpu.offline.CorrelationFixture.recording;
import static io.github.lhotari.jonoffcpu.offline.CorrelationFixture.sampling;
import static io.github.lhotari.jonoffcpu.offline.CorrelationFixture.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lhotari.jonoffcpu.capture.CaptureRecordFixture;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
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
public class OfflineCorrelatorTest {
    /**
     * Rewrite a source prefix and its digest, so validation cannot pass merely by rejecting a stale
     * hash.
     */
    private static void mutateSource(Path source, String recordType, Consumer<JsonObject> mutation) throws IOException {
        List<JsonObject> rows = readRows(source);
        int rowIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).get("recordType").getAsString().equals(recordType)) rowIndex = i;
        }
        assertThat(rowIndex).as("No " + recordType + " row to mutate").isNotNegative();
        mutation.accept(rows.get(rowIndex));
        int last = rows.size() - 1;
        byte[] prefix = CaptureStreamFixture.encode(rows.subList(0, last));
        JsonObject footer = rows.get(last);
        JsonObject artifact = footer.getAsJsonObject("analysisInputs").getAsJsonObject("sourceArtifact");
        artifact.addProperty("rawBytes", Integer.toString(prefix.length));
        artifact.addProperty("rawSha256", CaptureInput.hex(CaptureInput.sha256().digest(prefix)));
        Files.write(source, CaptureStreamFixture.encode(rows));
    }

    private static void rejects(Path source, Path jfr, OfflineCorrelator.Limits limits, String reason) {
        assertThatIOException()
                .as("Expected rejection: " + reason)
                .isThrownBy(() -> OfflineCorrelator.correlate(source, jfr, limits))
                .withMessageContaining(reason);
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static final OfflineCorrelator.Limits DEFAULTS = OfflineCorrelator.Limits.defaults();

    /** A one-sample JFR, the observation that matches its sample, and the finalized source holding it. */
    private record Fixture(Path jfr, long tid, JsonObject observation, Path source) {}

    private static Fixture fixture(Path dir) throws IOException {
        Path jfr = recording(dir, 1);
        long[] tid = new long[1];
        SignalJfrExporter.visit(jfr, row -> {
            if (row.get("recordType").equals("sample")) tid[0] = (Long) row.get("osThreadId");
        });
        JsonObject observation = observation(tid[0]);
        return new Fixture(jfr, tid[0], observation, source(dir, jfr, List.of(observation)));
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
                .isEqualTo("3000");
        assertThat(result.matches().get(0).handlerDelayNanos())
                .as("Delivery delay mismatch")
                .isEqualTo(BigInteger.valueOf(1000));
        assertThat(result.collapsedNanos()).as("Collapsed duration missing").containsValue("3000");
        Path output = dir.resolve("analysis");
        OffCpuCorrelator.write(result, output);
        assertThat(output.resolve(OutputFiles.COMPLETE))
                .as("Missing output completion marker")
                .isRegularFile();
        assertThat(Files.readString(output.resolve(OutputFiles.COLLAPSED)).strip())
                .as("Collapsed output is not weighted in integer microseconds")
                .endsWith(" 3");
        assertThat(Files.readString(output.resolve(OutputFiles.REPORT)))
                .as("Missing stack caveat")
                .contains("signal-delivery stack");
        JsonObject report = json(output.resolve(OutputFiles.REPORT));
        assertThat(report.getAsJsonObject("handlerDelayNanos").get("p99").getAsString())
                .as("Delivery delay percentile missing")
                .isEqualTo("1000");
        assertThat(report.has("populationEstimate"))
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
        assertThat(output.resolve(OutputFiles.COLLAPSED))
                .as("Missing default collapsed output")
                .isRegularFile();
        assertThat(report.has("syntheticJfr"))
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
        List<Instant> sampleTimes = new ArrayList<>();
        SignalJfrExporter.visit(jfr, row -> {
            if (row.get("recordType").equals("sample")) {
                sampleTimes.add(Instant.parse((String) row.get("startTime")));
            }
        });
        Instant sampleTime = sampleTimes.get(0);
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
                .anyMatch(row -> "sample-not-present-in-selected-jfr".equals(row.reason()));
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
        JsonObject rangeReport = json(selectedOutput.resolve(OutputFiles.REPORT));
        assertThat(rangeReport
                        .getAsJsonObject("jfrSelection")
                        .get("missingSourceMatchesExpected")
                        .getAsBoolean())
                .as("JFR selection metadata did not explain expected missing matches")
                .isTrue();
        assertThat(rangeReport.get("sourceRowsWithoutSelectedJfrSample").getAsLong())
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
        assertThat(result.jfrSelection().get("captureContextPresent").getAsBoolean())
                .as("Partial JFR unexpectedly claimed omitted metadata: %s", result.jfrSelection())
                .isFalse();
        assertThat(result.jfrSelection().get("terminalStatsPresent").getAsBoolean())
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
        JsonObject estimate = json(estimated.resolve(OutputFiles.REPORT)).getAsJsonObject("populationEstimate");
        assertThat(estimate.get("method").getAsString())
                .as("Population estimate method missing")
                .isEqualTo("inverse-probability-source-duration");
        assertThat(estimate.get("status").getAsString())
                .as("Source estimate unavailable")
                .isEqualTo("available");
        assertThat(estimate.get("sourceSelectedObservedDurationNanos").getAsString())
                .as("Source duration estimate basis mismatch")
                .isEqualTo("3000");
        assertThat(estimate.get("matchedSelectedObservedDurationNanos").getAsString())
                .as("Matched duration estimate basis mismatch")
                .isEqualTo("3000");
        assertThat(estimate.get("admissionPolicy").getAsString())
                .as("Population estimate lost policy")
                .isEqualTo("uniform");
        assertThat(estimate.get("estimatedDurationNanos").getAsString())
                .as("Population estimate is not the truncated exact inverse-probability sum")
                .isEqualTo(BigInteger.valueOf(3000)
                        .shiftLeft(32)
                        .divide(BigInteger.valueOf(42949673))
                        .toString());
    }

    /**
     * Under the proportional policy each row carries the threshold the kernel drew against: 2^32 at and above the
     * reference duration, duration * 2^32 / reference below it. The estimate weights each row by its own threshold.
     */
    @Test
    void proportionalEstimate(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        JsonObject admission = new JsonObject();
        admission.addProperty("policy", "proportional");
        admission.addProperty("recordAllAboveMicros", 2);
        JsonObject certain = fixture.observation().deepCopy();
        certain.addProperty("admissionThreshold", 1L << 32);
        JsonObject half = observation(fixture.tid());
        half.addProperty("correlationId", "8000000100000002");
        half.addProperty("startMonotonicNanos", "4000");
        half.addProperty("endMonotonicNanos", "5000");
        half.addProperty("admissionThreshold", 1L << 31);
        Path source = source(dir, jfr, List.of(certain, half), sampling(admission));
        var result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource())
                .as("Proportional rows were not accepted")
                .isZero();
        assertThat(result.matched()).as("Proportional rows were not accepted").isEqualTo(1);
        var estimate = result.populationEstimate();
        String weighting = "Proportional estimate must weight the half-probability row twice: " + estimate;
        assertThat(estimate.status()).as(weighting).isEqualTo("available");
        assertThat(estimate.admissionPolicy()).as(weighting).isEqualTo("proportional");
        assertThat(estimate.sourceSelectedObservedDurationNanos()).as(weighting).isEqualTo("4000");
        assertThat(estimate.estimatedDurationNanos()).as(weighting).isEqualTo("5000");
        JsonObject wrong = half.deepCopy();
        wrong.addProperty("admissionThreshold", 1L << 32);
        source = source(dir, jfr, List.of(certain, wrong), sampling(admission));
        result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource())
                .as("Row threshold that disagrees with the policy was accepted")
                .isEqualTo(1);
        assertThat(result.populationEstimate().status())
                .as("Invalid row must disable the estimate")
                .isEqualTo("unavailable");
        assertThat(result.populationEstimate().unavailableReasons())
                .as("Invalid row must disable the estimate")
                .contains("intrinsically-invalid-or-duplicate-source-rows");
        source = source(dir, jfr, List.of(certain, half), sampling(admission));
        mutateSource(
                source,
                "captureStart",
                row -> row.getAsJsonObject("sampling")
                        .getAsJsonObject("admission")
                        .addProperty("recordAllAboveMicros", 3));
        rejects(source, jfr, DEFAULTS, "Source/footer mismatch: sampling");
        JsonObject bounded = sampling(admission);
        bounded.addProperty("minOffCpuMicros", 1);
        source = source(dir, jfr, List.of(certain, half), bounded);
        result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource())
                .as("Strict lower bound did not reject the 1000 ns row")
                .isEqualTo(1);
    }

    /**
     * Sequence contention drops a selected interval after the kernel counted it, and counts the drop. When that
     * count alone explains the gap between selection and the rows, the estimate is scaled by selected / received
     * and reports the loss; any other discrepancy keeps the reasons it always had, and a loss above the limit is
     * refused.
     */
    /** One row's fixed-point weight: 3000 ns under the uniform threshold, 64 fraction bits below one. */
    private static final BigInteger ROW_WEIGHT =
            BigInteger.valueOf(3000).shiftLeft(32 + 64).divide(BigInteger.valueOf(42949673));

    /** One contended interval: selection counts three intervals, the source holds two rows. */
    private static final Consumer<JsonObject> CONTENDED = row -> {
        JsonObject kernel = row.getAsJsonObject("counters").getAsJsonObject("kernel");
        kernel.addProperty("selectedIntervals", "3");
        kernel.addProperty("eligibleIntervals", "3");
        kernel.addProperty("sequenceContentions", "1");
    };

    /** The matched observation plus one with no JFR sample, both selected. */
    private static List<JsonObject> contentionRows(Fixture fixture) {
        JsonObject unmatched = observation(fixture.tid());
        unmatched.addProperty("correlationId", "8000000100000002");
        return List.of(fixture.observation(), unmatched);
    }

    /** A two-row source whose kernel counters say one more interval was selected and lost to contention. */
    private static Path contendedSource(Path dir, Fixture fixture) throws IOException {
        Path source = source(dir, fixture.jfr(), contentionRows(fixture));
        mutateSource(source, "captureEnd", CONTENDED);
        return source;
    }

    @Test
    void sequenceContentionEstimate(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        List<JsonObject> rows = contentionRows(fixture);
        var lenient = new OfflineCorrelator.Limits(
                100_000_000, 1024 * 1024, 256L << 20, 4096, null, null, null, new BigDecimal("0.5"));
        BigInteger rowWeight = ROW_WEIGHT;

        // No contention: exactly today's estimate, with nothing accounted and nothing assumed.
        Path source = source(dir, jfr, rows);
        var exact = OfflineCorrelator.correlate(source, jfr, lenient).populationEstimate();
        String zero = "Zero contention must keep the exact estimate: " + exact;
        assertThat(exact.status()).as(zero).isEqualTo("available");
        assertThat(exact.sourceCoverageComplete()).as(zero).isTrue();
        assertThat(exact.accountedLoss()).as(zero).isNull();
        assertThat(exact.assumptions()).as(zero).isEmpty();
        assertThat(exact.estimatedDurationNanos())
                .as(zero)
                .isEqualTo(rowWeight.shiftLeft(1).shiftRight(64).toString());

        // One contended interval explains the gap exactly: available, scaled by 3 / 2, loss reported.
        Consumer<JsonObject> contended = CONTENDED;
        mutateSource(source, "captureEnd", contended);
        var accounted = OfflineCorrelator.correlate(source, jfr, lenient).populationEstimate();
        String scaled = "Counted contention must scale the estimate by selected / received: " + accounted;
        assertThat(accounted.status()).as(scaled).isEqualTo("available");
        assertThat(accounted.unavailableReasons()).as(scaled).isEmpty();
        assertThat(accounted.sourceCoverageComplete()).as(scaled).isFalse();
        assertThat(accounted.sourceRowsUsed()).as(scaled).isEqualTo("2");
        assertThat(accounted.accountedLoss()).as(scaled).isNotNull();
        assertThat(accounted.accountedLoss().intervals()).as(scaled).isEqualTo("1");
        assertThat(accounted.accountedLoss().reason()).as(scaled).isEqualTo("sequence-contention");
        assertThat(accounted.accountedLoss().fraction()).as(scaled).isEqualTo(new BigDecimal("0.333333"));
        assertThat(accounted.assumptions()).as(scaled).containsExactly(CorrelationEngine.CONTENTION_INDEPENDENCE);
        assertThat(accounted.estimatedDurationNanos())
                .as(scaled)
                .isEqualTo(rowWeight
                        .shiftLeft(1)
                        .multiply(BigInteger.valueOf(3))
                        .divide(BigInteger.TWO)
                        .shiftRight(64)
                        .toString());

        // The same loss above the default 1 % limit is refused, and still reported.
        var refused = OfflineCorrelator.correlate(source, jfr, DEFAULTS).populationEstimate();
        String aboveLimit = "Accounted loss above the limit must be refused: " + refused;
        assertThat(refused.status()).as(aboveLimit).isEqualTo("unavailable");
        assertThat(refused.estimatedDurationNanos()).as(aboveLimit).isNull();
        assertThat(refused.unavailableReasons()).as(aboveLimit).containsExactly("accounted-loss-above-limit");
        assertThat(refused.accountedLoss()).as(aboveLimit).isNotNull();
        assertThat(refused.accountedLoss().intervals()).as(aboveLimit).isEqualTo("1");

        // A gap the contention count does not explain keeps both of today's reasons.
        mutateSource(source, "captureEnd", row -> {
            JsonObject kernel = row.getAsJsonObject("counters").getAsJsonObject("kernel");
            kernel.addProperty("selectedIntervals", "4");
            kernel.addProperty("eligibleIntervals", "4");
        });
        var unexplained = OfflineCorrelator.correlate(source, jfr, lenient).populationEstimate();
        String todays = "Unexplained mismatch must stay unavailable with today's reasons: " + unexplained;
        assertThat(unexplained.status()).as(todays).isEqualTo("unavailable");
        assertThat(unexplained.accountedLoss()).as(todays).isNull();
        assertThat(unexplained.assumptions()).as(todays).isEmpty();
        assertThat(unexplained.unavailableReasons())
                .as(todays)
                .containsExactly("nonzero-sequenceContentions", "selected-source-row-count-mismatch");

        // An explained gap next to any other failure counter is not accounted for either.
        source = source(dir, jfr, rows);
        mutateSource(
                source,
                "captureEnd",
                contended.andThen(row -> row.getAsJsonObject("counters")
                        .getAsJsonObject("kernel")
                        .addProperty("ringReserveFailures", "1")));
        var otherLoss = OfflineCorrelator.correlate(source, jfr, lenient).populationEstimate();
        String otherCounter = "Another nonzero failure counter must keep today's reasons: " + otherLoss;
        assertThat(otherLoss.status()).as(otherCounter).isEqualTo("unavailable");
        assertThat(otherLoss.accountedLoss()).as(otherCounter).isNull();
        assertThat(otherLoss.unavailableReasons())
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
        JsonObject report = json(output.resolve(OutputFiles.REPORT));
        JsonObject estimate = report.getAsJsonObject("populationEstimate");
        JsonObject loss = estimate.getAsJsonObject("accountedLoss");
        String carried = "Report must carry the accounted loss and its assumption: " + estimate;
        assertThat(loss.get("intervals").getAsString()).as(carried).isEqualTo("1");
        assertThat(loss.get("fraction").getAsBigDecimal()).as(carried).isEqualTo(new BigDecimal("0.333333"));
        assertThat(loss.get("reason").getAsString()).as(carried).isEqualTo("sequence-contention");
        assertThat(estimate.getAsJsonArray("assumptions")).as(carried).hasSize(1);
        boolean available = !limit.isEmpty();
        String verdict = "CLI limit " + (available ? limit : "default") + " gave the wrong verdict: " + estimate;
        assertThat(estimate.get("status").getAsString()).as(verdict).isEqualTo(available ? "available" : "unavailable");
        assertThat(report.getAsJsonObject("stackProfile")
                        .get("estimateAvailable")
                        .getAsBoolean())
                .as(verdict)
                .isEqualTo(available);
        StackProfile profile = StackProfile.read(output.resolve(OutputFiles.PROFILE));
        BigInteger expected =
                available ? ROW_WEIGHT.multiply(BigInteger.valueOf(3)).divide(BigInteger.TWO) : ROW_WEIGHT;
        assertThat(profile.header().estimateAvailable())
                .as("Profile estimate availability must follow the verdict")
                .isEqualTo(available);
        assertThat(profile.totalEstimatedNanos())
                .as("Profile estimates must take the same scale as the total")
                .isEqualTo(expected.shiftRight(64).longValueExact());
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
        JsonObject unmatched = observation(fixture.tid());
        unmatched.addProperty("correlationId", "8000000100000002");
        Path twoSource = source(dir, jfr, List.of(fixture.observation(), unmatched));
        var two = OfflineCorrelator.correlate(twoSource, jfr, DEFAULTS);
        assertThat(two.populationEstimate().sourceSelectedObservedDurationNanos())
                .as("Unmatched durable source duration omitted from estimate")
                .isEqualTo("6000");
        assertThat(two.populationEstimate().matchedSelectedObservedDurationNanos())
                .as("Stack-matched duration was incorrectly scaled")
                .isEqualTo("3000");
        mutateSource(
                twoSource,
                "captureEnd",
                row -> row.getAsJsonObject("counters").getAsJsonObject("kernel").addProperty("selectedIntervals", "3"));
        two = OfflineCorrelator.correlate(twoSource, jfr, DEFAULTS);
        assertThat(two.populationEstimate().status())
                .as("Missing selected source row did not disable population estimate")
                .isEqualTo("unavailable");
        assertThat(two.populationEstimate().unavailableReasons())
                .as("Missing selected source row did not disable population estimate")
                .contains("selected-source-row-count-mismatch");
    }

    @Test
    void estimatorCounterValidation(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Path jfr = fixture.jfr();
        mutateSource(
                source,
                "captureEnd",
                row -> row.getAsJsonObject("counters")
                        .getAsJsonObject("kernel")
                        .addProperty("eligibleIntervals", CaptureInput.U64_MAX.toString()));
        var result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.populationEstimate().status())
                .as("Saturated source counter was treated as exact coverage")
                .isEqualTo("unavailable");
        assertThat(result.populationEstimate().unavailableReasons())
                .as("Saturated source counter was treated as exact coverage")
                .contains("saturated-counter-eligibleIntervals");
        source = source(dir, jfr, List.of(fixture.observation()));
        mutateSource(
                source,
                "captureEnd",
                row -> row.getAsJsonObject("counters").getAsJsonObject("kernel").addProperty("eligibleIntervals", 1));
        result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.populationEstimate().status())
                .as("Non-string estimator counter was accepted")
                .isEqualTo("unavailable");
        assertThat(result.populationEstimate().unavailableReasons())
                .as("Non-string estimator counter was accepted")
                .contains("invalid-counter-eligibleIntervals");
        source = source(dir, jfr, List.of(fixture.observation()));
        mutateSource(
                source,
                "captureEnd",
                row -> row.getAsJsonObject("counters")
                        .getAsJsonObject("kernel")
                        .addProperty("targetNamespaceFailures", "+0"));
        rejects(source, jfr, DEFAULTS, "Invalid unsigned decimal");
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
        JsonObject selectedReport = json(selected.resolve(OutputFiles.REPORT));
        assertThat(selectedReport.has("syntheticJfr"))
                .as("Incorrect JFR metadata selection")
                .isEqualTo(format.equals("jfr"));
        if (format.equals("jfr")) {
            assertThat(selectedReport
                            .getAsJsonObject("syntheticJfr")
                            .get("syntheticEvents")
                            .getAsLong())
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
                .isEqualTo("1000");
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
    }

    @Test
    void ambiguity(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        JsonObject observation = fixture.observation();
        Path source = source(dir, jfr, List.of(observation, observation.deepCopy()));
        var result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource()).as("Ambiguity joined").isEqualTo(2);
        assertThat(result.invalidJfr()).as("Ambiguity joined").isEqualTo(1);
        assertThat(result.matched()).as("Ambiguity joined").isZero();
        JsonObject wrongEpoch = observation.deepCopy();
        wrongEpoch.addProperty("captureEpoch", 1);
        source = source(dir, jfr, List.of(observation, wrongEpoch));
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
        JsonObject wrongBinding = fixture.observation().deepCopy();
        wrongBinding.addProperty("registrationToken", "0000000000000002");
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
        JsonObject outside = fixture.observation().deepCopy();
        outside.addProperty("startMonotonicNanos", "1");
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
        assertThat(result.populationEstimate().status()).as(erased).isEqualTo("available");
        assertThat(result.populationEstimate().sourceSelectedObservedDurationNanos())
                .as(erased)
                .isEqualTo("3000");
        assertThat(result.populationEstimate().matchedSelectedObservedDurationNanos())
                .as(erased)
                .isEqualTo("0");
    }

    @Test
    void threadAndNamespaceIdentity(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        JsonObject observation = fixture.observation().deepCopy();
        observation.addProperty("targetTid", fixture.tid() + 1);
        Path source = source(dir, jfr, List.of(observation));
        var result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource()).as("Thread mismatch joined").isEqualTo(1);
        assertThat(result.invalidJfr()).as("Thread mismatch joined").isEqualTo(1);
        observation.add("targetTid", JsonNull.INSTANCE);
        source = source(dir, jfr, List.of(observation));
        rejects(source, jfr, DEFAULTS, "Missing target namespace TID");
        observation.addProperty("targetTid", fixture.tid());
        observation.addProperty("targetTgid", ProcessHandle.current().pid() + 1);
        source = source(dir, jfr, List.of(observation));
        result = OfflineCorrelator.correlate(source, jfr, DEFAULTS);
        assertThat(result.invalidSource()).as("Wrong namespace TGID joined").isEqualTo(1);
        assertThat(result.invalidJfr()).as("Wrong namespace TGID joined").isEqualTo(1);
        source = source(dir, jfr, List.of(fixture.observation()));
        mutateSource(source, "captureStart", row -> row.addProperty("pidNamespaceInode", "1"));
        rejects(source, jfr, DEFAULTS, "Verified namespace mismatch");
    }

    /** Interned stacks: a reference must be announced first, and an id may be announced only once. */
    @Test
    void internedStacks(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        Path source = fixture.source();
        mutateSource(source, "observation", row -> row.addProperty("kernelStackId", 99));
        rejects(source, jfr, DEFAULTS, "Observation references an unannounced stack");
        source = source(dir, jfr, List.of(fixture.observation()));
        mutateSource(source, "observation", row -> row.addProperty("kernelStackId", -7));
        rejects(source, jfr, DEFAULTS, "Unexplained negative stack id");
        source = source(dir, jfr, List.of(fixture.observation()));
        mutateSource(source, "stack", row -> row.addProperty("stackId", KERNEL_STACK_ID));
        rejects(source, jfr, DEFAULTS, "Duplicate stack record");
    }

    @Test
    void targetNamespaceFailures(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        Path source = fixture.source();
        mutateSource(
                source,
                "captureEnd",
                row -> row.getAsJsonObject("counters")
                        .getAsJsonObject("kernel")
                        .addProperty("targetNamespaceFailures", "1"));
        rejects(source, jfr, DEFAULTS, "Source target namespace mapping failed");
        source = source(dir, jfr, List.of(fixture.observation()));
        mutateSource(
                source,
                "captureEnd",
                row -> row.getAsJsonObject("counters").getAsJsonObject("kernel").remove("targetNamespaceFailures"));
        rejects(source, jfr, DEFAULTS, "targetNamespaceFailures");
    }

    @Test
    void sourceIntegrity(@TempDir Path dir) throws IOException {
        Fixture fixture = fixture(dir);
        Path jfr = fixture.jfr();
        Path source = fixture.source();
        byte[] valid = Files.readAllBytes(source);
        List<JsonObject> rows = readRows(source);
        assertThat(rows).as("fixture rows missing").isNotEmpty();
        mutateSource(
                source,
                "captureFinalized",
                row -> row.addProperty(
                        "apStopResponse",
                        row.get("apStopResponse").getAsString().replace("stopped-at=9000", "stopped-at=3000")));
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
        // A record edited without re-signing the prefix must fail the digest, not the semantics.
        List<JsonObject> tampered = readRows(source);
        for (JsonObject row : tampered) {
            if (row.get("recordType").getAsString().equals("observation")) row.addProperty("hostTid", 457);
        }
        Files.write(source, CaptureStreamFixture.encode(tampered));
        rejects(source, jfr, DEFAULTS, "Source digest mismatch");
        Files.write(source, java.util.Arrays.copyOf(valid, valid.length - 1));
        rejects(source, jfr, DEFAULTS, "truncated tail");
        Files.write(source, concat(valid, CaptureRecordFixture.controlRecord("{}")));
        rejects(source, jfr, DEFAULTS, "Rows follow");
        // A control record's JSON is still read with the strict parser.
        Files.write(
                source,
                concat(
                        CaptureStreamFixture.header(),
                        CaptureRecordFixture.controlRecord(
                                "{\"schemaVersion\":2,\"schemaVersion\":2,\"recordType\":\"captureStart\"}")));
        rejects(source, jfr, DEFAULTS, "Duplicate JSON field");
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
