// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.jonoffcpu.jonoffcpu.capture.CaptureFixtures;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureFormat;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto.Record.RecordCase;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureRecordFixture;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Real JFR/source truncation and semantic failure fixtures for the explicit incomplete-run path.
 */
class PartialCorrelatorTest {
    private static final OfflineCorrelator.Limits DEFAULTS = OfflineCorrelator.Limits.defaults();

    @Name("profiler.SignalSampleV2")
    @StackTrace(false)
    public static class UnknownSignal extends Event {}

    /** A complete one-sample JFR, its matching observation, and the finalized source built from them. */
    private record Fixture(
            Path jfr, CaptureProto.Observation observation, Path source, List<CaptureProto.Record> complete) {}

    private static Fixture fixture(Path dir) throws IOException {
        Path jfr = CorrelationFixture.recording(dir, 1);
        CaptureProto.Observation observation = CorrelationFixture.observation(CorrelationFixture.sampleThread(jfr))
                .build();
        Path source = CorrelationFixture.source(dir, jfr, List.of(observation));
        return new Fixture(jfr, observation, source, CorrelationFixture.readRecords(source));
    }

    private static void rejects(ThrowingCallable action, String reason) {
        assertThatThrownBy(action)
                .as("Expected failure: " + reason)
                .isInstanceOfAny(IOException.class, IllegalArgumentException.class)
                .hasMessageContaining(reason);
    }

    private static Path recording(
            Path path,
            int samples,
            boolean stats,
            boolean statsFirst,
            boolean unknown,
            boolean conflict,
            long sequence,
            int submitted)
            throws IOException {
        try (Recording recording = new Recording()) {
            recording.enable(CorrelationFixture.Capture.class);
            recording.enable(CorrelationFixture.Sample.class).withStackTrace();
            recording.enable(CorrelationFixture.Stats.class);
            recording.enable(UnknownSignal.class);
            recording.start();
            new CorrelationFixture.Capture().commit();
            if (stats && statsFirst) stats(submitted);
            for (int i = 0; i < samples; i++) {
                CorrelationFixture.Sample sample = new CorrelationFixture.Sample();
                sample.correlationId = (sample.correlationId & 0xffffffff00000000L) | sequence;
                sample.commit();
            }
            if (unknown) new UnknownSignal().commit();
            if (conflict) {
                CorrelationFixture.Capture capture = new CorrelationFixture.Capture();
                capture.sessionId = UUID.randomUUID().toString();
                capture.commit();
            } else {
                // Leave a complete event after the sample, so a bad following chunk cannot hide that
                // sample.
                new CorrelationFixture.Capture().commit();
            }
            if (stats && !statsFirst) stats(submitted);
            recording.stop();
            recording.dump(path);
        }
        return path;
    }

    private static void stats(int samples) {
        CorrelationFixture.Stats stats = new CorrelationFixture.Stats();
        stats.admittedSignals = stats.acceptedCookies = stats.submittedSamples = samples;
        stats.commit();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    /** One record, length-delimited, to append to a prefix. */
    private static byte[] record(CaptureProto.Record record) throws IOException {
        return CaptureRecordFixture.encode(record);
    }

    /** The last record of the given kind, so fixtures do not depend on record positions. */
    private static CaptureProto.Record row(List<CaptureProto.Record> records, RecordCase kind) {
        CaptureProto.Record found = null;
        for (CaptureProto.Record candidate : records) {
            if (candidate.getRecordCase() == kind) found = candidate;
        }
        assertThat(found).as("No " + kind + " record").isNotNull();
        return found;
    }

    /** The stream truncated after the last record of the given kind. */
    private static byte[] prefix(List<CaptureProto.Record> records, RecordCase kind) throws IOException {
        int count = 0;
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).getRecordCase() == kind) count = i + 1;
        }
        return CaptureRecordFixture.encode(records.subList(0, count));
    }

    @Test
    void completeInputsInPartialMode(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        var completeAsPartial = OfflineCorrelator.correlatePartial(fixture.source(), fixture.jfr(), DEFAULTS);
        ReportProto.PartialReport report = completeAsPartial.report();
        assertThat(report.getCoverageComplete())
                .as("Explicit partial mode promoted complete inputs")
                .isFalse();
        assertThat(report.getIncompleteReasonsList())
                .as("Explicit partial mode promoted complete inputs")
                .containsExactly(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_EXPLICIT_PARTIAL_MODE);
        assertThat(report.getArtifactVerification())
                .as("Complete inputs are still verified in partial mode")
                .isEqualTo(ReportProto.ArtifactVerification.ARTIFACT_VERIFICATION_VERIFIED_FINALIZED_INPUTS);
        assertThat(completeAsPartial.records())
                .as("Partial mode pairs are provisional even for complete inputs")
                .filteredOn(ReportProto.ClassifiedRecord::hasSource)
                .extracting(ReportProto.ClassifiedRecord::getClassification)
                .containsExactly(ReportProto.Classification.CLASSIFICATION_PROVISIONAL_MATCH);
    }

    @Test
    void sourcePrefixFixtures(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Path jfr = fixture.jfr();
        CaptureProto.Observation observation = fixture.observation();
        List<CaptureProto.Record> complete = fixture.complete();
        Files.write(source, prefix(complete, RecordCase.CAPTURE_END));
        rejects(() -> OfflineCorrelator.correlate(source, jfr, DEFAULTS), "Missing source finalization");
        var result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
        assertThat(result.provisionalPairs())
                .as("Missing-footer pair not retained")
                .isEqualTo(1);
        assertThat(result.report().getIdentityUnverified())
                .as("Missing-footer pair not retained")
                .isZero();
        assertThat(result.pairs().get(0).handlerDelayNanos())
                .as("Missing footer invented clock proof")
                .isNull();
        assertThat(result.report().getClockVerification())
                .as("Clock claim invented")
                .isEqualTo(ReportProto.FooterVerification.FOOTER_VERIFICATION_UNAVAILABLE);
        assertThat(result.report().hasObservedFinalization())
                .as("Missing footer fabricated")
                .isFalse();
        assertThat(result.records())
                .as("Prefix matches have final classifications")
                .allMatch(
                        row -> row.getClassification() == ReportProto.Classification.CLASSIFICATION_PROVISIONAL_MATCH);
        assertThat(result.report().hasSubmittedButNotParsed())
                .as("Observed terminal stats not retained")
                .isTrue();
        assertThat(result.report().getSubmittedButNotParsed())
                .as("Observed terminal stats not retained")
                .isZero();
        var delayLimit = new OfflineCorrelator.Limits(
                DEFAULTS.maxRows(),
                DEFAULTS.maxLineBytes(),
                DEFAULTS.maxRetainedBytes(),
                DEFAULTS.maxFrames(),
                BigInteger.ONE,
                null,
                null);
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, delayLimit), "without verified clock");

        Files.write(source, prefix(complete, RecordCase.OBSERVATION));
        var clipped = new OfflineCorrelator.Limits(
                DEFAULTS.maxRows(),
                DEFAULTS.maxLineBytes(),
                DEFAULTS.maxRetainedBytes(),
                DEFAULTS.maxFrames(),
                null,
                BigInteger.valueOf(2000),
                BigInteger.valueOf(3000));
        result = OfflineCorrelator.correlatePartial(source, jfr, clipped);
        assertThat(result.provisionalPairs())
                .as("Prefix source-window clipping lost delayed sample")
                .isEqualTo(1);
        assertThat(result.report().getPairedSelectedObservedDurationNanos())
                .as("Prefix source-window clipping lost delayed sample")
                .isEqualTo(1000);
        assertThat(result.report().getWindow().getFromNanos()).isEqualTo(2000);
        assertThat(result.report().getWindow().getToNanos()).isEqualTo(3000);
        assertThat(result.report().hasObservedSourceEnd())
                .as("Missing captureEnd fabricated")
                .isFalse();
        // A record whose length prefix promises more bytes than the file holds is a truncated tail.
        for (byte[] tail : List.of(new byte[] {40, 10, 24}, new byte[] {(byte) 0x9a, 0x02})) {
            byte[] prefix = prefix(complete, RecordCase.OBSERVATION);
            byte[] bytes = Arrays.copyOf(prefix, prefix.length + tail.length);
            System.arraycopy(tail, 0, bytes, prefix.length, tail.length);
            Files.write(source, bytes);
            result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
            assertThat(result.report().getSourceRows())
                    .as("Trailing fragment became a row")
                    .isEqualTo(1);
            assertThat(result.provisionalPairs())
                    .as("Trailing fragment became a row")
                    .isEqualTo(1);
            assertThat(result.report().getSourceParse().getIgnoredTrailingBytes())
                    .as("Trailing byte count wrong")
                    .isEqualTo(tail.length);
            assertThat(result.report().getIncompleteReasonsList())
                    .contains(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_UNTERMINATED_SOURCE_TAIL);
        }
        // A footer record whose last byte never landed is still an incomplete tail.
        byte[] whole = CaptureRecordFixture.encode(complete);
        Files.write(source, Arrays.copyOf(whole, whole.length - 1));
        result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
        assertThat(result.report().getApStopVerification())
                .as("Unterminated footer certified AP completion")
                .isEqualTo(ReportProto.FooterVerification.FOOTER_VERIFICATION_UNAVAILABLE);
        CaptureProto.Record.Builder end = row(complete, RecordCase.CAPTURE_END).toBuilder();
        end.getCaptureEndBuilder()
                .setState(CaptureProto.CaptureState.CAPTURE_STATE_INCOMPLETE)
                .setIncompleteReason(CaptureProto.IncompleteReason.INCOMPLETE_REASON_TARGET_NAMESPACE_MAPPING_FAILURE)
                .getKernelCountersBuilder()
                .setTargetNamespaceFailures(1);
        Files.write(source, concat(prefix(complete, RecordCase.OBSERVATION), record(end.build())));
        rejects(() -> OfflineCorrelator.correlate(source, jfr, DEFAULTS), "Missing source finalization");
        result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
        assertThat(result.report().getIncompleteReasonsList())
                .as("Incomplete source state missing")
                .contains(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_SOURCE_CAPTURE_INCOMPLETE);
        assertThat(result.report().getObservedSourceEnd().getKernelCounters().getTargetNamespaceFailures())
                .as("Source failure counter erased")
                .isEqualTo(1);
        // An incomplete end must say why.
        end.getCaptureEndBuilder().clearIncompleteReason();
        Files.write(source, concat(prefix(complete, RecordCase.OBSERVATION), record(end.build())));
        rejects(
                () -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS),
                "Invalid source end incomplete reason");

        Files.write(
                source, concat(prefix(complete, RecordCase.OBSERVATION), record(CaptureFixtures.record(observation))));
        result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
        assertThat(result.provisionalPairs())
                .as("Late prefix source duplicate joined")
                .isZero();
        assertThat(result.report().getInvalidSource())
                .as("Late prefix source duplicate joined")
                .isEqualTo(2);
        assertThat(result.report().getInvalidJfr())
                .as("Late prefix source duplicate joined")
                .isEqualTo(1);
        assertThat(result.records())
                .as("Both copies of a duplicated cookie are invalid")
                .filteredOn(ReportProto.ClassifiedRecord::hasSource)
                .extracting(ReportProto.ClassifiedRecord::getReason)
                .containsExactly(
                        ReportProto.RowReason.ROW_REASON_DUPLICATE_COOKIE,
                        ReportProto.RowReason.ROW_REASON_DUPLICATE_COOKIE);
        assertThat(result.report().getSourceSelectedObservedDurationNanos())
                .as("Duplicate source duration counted")
                .isZero();
        Files.write(source, prefix(complete, RecordCase.OBSERVATION));
        Path duplicate = recording(dir.resolve("duplicate.jfr"), 2, true, false, false, false, 1, 2);
        result = OfflineCorrelator.correlatePartial(source, duplicate, DEFAULTS);
        assertThat(result.provisionalPairs())
                .as("AP duplicate joined in prefix")
                .isZero();
        assertThat(result.report().getInvalidSource())
                .as("AP duplicate joined in prefix")
                .isEqualTo(1);
        assertThat(result.report().getInvalidJfr())
                .as("AP duplicate joined in prefix")
                .isEqualTo(2);
        assertThat(result.report().getSourceSelectedObservedDurationNanos())
                .as("AP ambiguity erased source duration")
                .isEqualTo(3000);
        Path statsFirst = recording(dir.resolve("stats-first.jfr"), 1, true, true, false, false, 1, 1);
        result = OfflineCorrelator.correlatePartial(source, statsFirst, DEFAULTS);
        assertThat(result.provisionalPairs())
                .as("Physical stats-before-sample order rejected")
                .isEqualTo(1);
        Path missingStats = recording(dir.resolve("missing-stats.jfr"), 1, false, false, false, false, 1, 0);
        rejects(() -> SignalJfrExporter.visit(missingStats, ignored -> {}), "Missing terminal");
        result = OfflineCorrelator.correlatePartial(source, missingStats, DEFAULTS);
        assertThat(result.provisionalPairs())
                .as("Unknown final submitted count replaced with zero")
                .isEqualTo(1);
        assertThat(result.report().hasSubmittedButNotParsed())
                .as("Unknown final submitted count replaced with zero")
                .isFalse();
        assertThat(result.report().getJfrParse().getTerminalStatsPresent())
                .as("Missing stats claimed present")
                .isFalse();
        assertThat(result.report().getIncompleteReasonsList())
                .contains(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_MISSING_AP_STATS);
    }

    /**
     * A two-chunk JFR cut at chunk-header, mid-chunk and chunk-boundary offsets; the offsets depend on the
     * size of the recorded first chunk, so each cut is a test computed at runtime.
     */
    @TestFactory
    Stream<DynamicTest> jfrPrefixFixtures(@TempDir Path dir) throws Exception {
        CaptureProto.Observation observation = fixture(dir).observation();
        Path first = recording(dir.resolve("chunk-first.jfr"), 1, false, false, false, false, 1, 0);
        Path last = recording(dir.resolve("chunk-last.jfr"), 1, true, false, false, false, 2, 2);
        byte[] head = Files.readAllBytes(first);
        byte[] tail = Files.readAllBytes(last);
        byte[] both = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, both, head.length, tail.length);
        Path combined = dir.resolve("multichunk.jfr");
        Files.write(combined, both);
        CaptureProto.Observation second = observation.toBuilder()
                .setCorrelationId(CorrelationFixture.COOKIE + 1)
                .build();
        Path source = CorrelationFixture.source(dir, combined, List.of(observation, second));
        assertThat(OfflineCorrelator.correlate(source, combined, DEFAULTS).matched())
                .as("Multi-chunk fixture invalid")
                .isEqualTo(2);
        int[] cuts = {0, 4, 64, head.length, head.length + 7, head.length + 64, both.length - 1};
        return IntStream.of(cuts)
                .mapToObj(cut -> dynamicTest("cut at byte " + cut, () -> {
                    Files.write(combined, Arrays.copyOf(both, cut));
                    rejects(() -> OfflineCorrelator.correlate(source, combined, DEFAULTS), "JFR byte count mismatch");
                    var result = OfflineCorrelator.correlatePartial(source, combined, DEFAULTS);
                    assertThat(result.provisionalPairs())
                            .as("Truncated JFR invented a pair (cut at %d)", cut)
                            .isLessThanOrEqualTo(result.report().getJfrSamples())
                            .isLessThanOrEqualTo(2);
                    assertThat(result.report().getArtifactVerification())
                            .as("Truncated JFR declared hash verified")
                            .isEqualTo(ReportProto.ArtifactVerification.ARTIFACT_VERIFICATION_UNVERIFIED_INCOMPLETE);
                    assertThat(result.report().getIncompleteReasonsList())
                            .contains(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_JFR_SHORTER_THAN_FINALIZED_ARTIFACT);
                    assertThat(result.report().getClockVerification())
                            .as("Observed consistent footer clock proof was erased")
                            .isEqualTo(ReportProto.FooterVerification.FOOTER_VERIFICATION_VERIFIED_FOOTER);
                    if (cut == head.length) {
                        assertThat(result.provisionalPairs())
                                .as("Complete first JFR chunk not recovered")
                                .isEqualTo(1);
                    }
                }));
    }

    /**
     * A whole record appended to a valid prefix that is not a record the stream defines is a hard error even in
     * partial mode: only a record cut short is a recoverable tail. The bytes are the length prefix and the message.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                // An empty message, and one holding only a field the schema does not define.
                "00       | Unknown source record",
                "02780a   | Unknown source record",
                // A capture_start whose embedded length runs past the record.
                "030a0501 | protocol message"
            })
    void malformedRecord(String hex, String reason, @TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Files.write(
                source,
                concat(
                        prefix(fixture.complete(), RecordCase.OBSERVATION),
                        HexFormat.of().parseHex(hex.strip())));
        rejects(() -> OfflineCorrelator.correlatePartial(source, fixture.jfr(), DEFAULTS), reason);
    }

    @ParameterizedTest(name = "{4}")
    @CsvSource({
        "1,   1048576, 1048576, 4096, row limit",
        "100, 10,      1048576, 4096, record byte limit",
        "100, 1048576, 256,     4096, budget",
        "100, 1048576, 1048576, 1,    frame count"
    })
    void partialInputLimits(
            int maxRows, int maxLineBytes, long maxRetainedBytes, int maxFrames, String reason, @TempDir Path dir)
            throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Files.write(source, prefix(fixture.complete(), RecordCase.OBSERVATION));
        var limit = new OfflineCorrelator.Limits(maxRows, maxLineBytes, maxRetainedBytes, maxFrames, null, null, null);
        rejects(() -> OfflineCorrelator.correlatePartial(source, fixture.jfr(), limit), reason);
    }

    @Test
    void hardFailures(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Path jfr = fixture.jfr();
        List<CaptureProto.Record> complete = fixture.complete();
        // Only the one stream format version is read.
        byte[] oldHeader = CaptureFormat.header();
        oldHeader[oldHeader.length - 2] = 1;
        Files.write(source, concat(oldHeader, record(row(complete, RecordCase.CAPTURE_START))));
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS), "Unsupported capture format version");
        CaptureProto.Record.Builder start = row(complete, RecordCase.CAPTURE_START).toBuilder();
        start.getCaptureStartBuilder()
                .getSamplingBuilder()
                .setMinOffCpuMicros(10)
                .setMaxOffCpuMicros(10);
        Files.write(source, CaptureRecordFixture.encode(List.of(start.build())));
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS), "Invalid duration policy bounds");
        start = row(complete, RecordCase.CAPTURE_START).toBuilder();
        start.getCaptureStartBuilder().getTimeSplitBuilder().clearSource();
        Files.write(source, CaptureRecordFixture.encode(List.of(start.build())));
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS), "Unknown timeSplit source");
        Files.write(source, prefix(complete, RecordCase.OBSERVATION));
        Path conflict = recording(dir.resolve("conflict.jfr"), 1, false, false, false, true, 1, 0);
        rejects(
                () -> OfflineCorrelator.correlatePartial(source, conflict, DEFAULTS),
                "Conflicting signal capture context");
        Path unknown = recording(dir.resolve("unknown.jfr"), 1, false, false, true, false, 1, 0);
        rejects(() -> OfflineCorrelator.correlatePartial(source, unknown, DEFAULTS), "Unsupported signal event type");
        Path badStats = recording(dir.resolve("bad-stats.jfr"), 1, true, false, false, false, 1, 0);
        rejects(() -> OfflineCorrelator.correlatePartial(source, badStats, DEFAULTS), "samples exceed submitted");
        // Consumer failure must not be mistaken for recoverable RecordingFile tail corruption.
        rejects(
                () -> SignalJfrExporter.visitPrefix(jfr, ignored -> {
                    throw new IOException("consumer-budget");
                }),
                "consumer-budget");
        Files.write(source, CaptureRecordFixture.encode(complete));
        byte[] original = Files.readAllBytes(jfr);
        byte[] corrupted = original.clone();
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(jfr, corrupted);
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS), "JFR digest mismatch");
        Files.write(jfr, original);
        List<CaptureProto.Record> tampered = complete.stream()
                .map(record -> record.hasObservation()
                        ? CaptureFixtures.record(record.getObservation().toBuilder()
                                .setHostTid(457)
                                .build())
                        : record)
                .toList();
        // Not re-sealed: the footer's digest covers the original bytes.
        Files.write(source, CaptureRecordFixture.encode(tampered));
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS), "Source digest mismatch");
        Files.write(
                source,
                concat(
                        CaptureRecordFixture.encode(complete),
                        record(CaptureFixtures.record(CorrelationFixture.stack(99, "late")))));
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS), "Rows follow captureFinalized");
        Files.write(source, prefix(complete, RecordCase.OBSERVATION));
        CaptureInput snapshot = CaptureInput.readPartial(source, jfr, DEFAULTS, new CaptureInput.SourceVisitor() {
            @Override
            public void reading(CaptureInput.Budget budget, LongIntMap announcedStacks) {}

            @Override
            public void start(CaptureProto.CaptureStart captureStart) {}

            @Override
            public void stack(long stackId, CaptureProto.Stack stack) {}

            @Override
            public void observation(int rowNumber, CaptureProto.Observation observation) {}
        });
        Files.write(source, prefix(complete, RecordCase.CAPTURE_START));
        rejects(() -> snapshot.verifyUnchanged(source, jfr), "Inputs changed");
    }

    @Test
    void outputFixtures(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Path jfr = fixture.jfr();
        // A source prefix without its footer: no clock proof, so no handler delay can be derived.
        Files.write(source, prefix(fixture.complete(), RecordCase.OBSERVATION));
        Path output = dir.resolve("partial-output");
        String[] cli = {
            "--source",
            source.toString(),
            "--jfr",
            jfr.toString(),
            "--output",
            output.toString(),
            "--partial",
            "true",
            "--format",
            "collapsed"
        };
        assertThat(OffCpuCorrelator.run(cli))
                .as("Partial API/CLI status must be 2")
                .isEqualTo(2);
        assertThat(output.resolve(OutputFiles.COMPLETE))
                .as("Partial publication resembles complete output")
                .doesNotExist();
        assertThat(output.resolve(OutputFiles.SYNTHETIC_JFR))
                .as("Partial publication resembles complete output")
                .doesNotExist();
        assertThat(output.resolve(OutputFiles.COLLAPSED))
                .as("Partial publication resembles complete output")
                .doesNotExist();
        ReportProto.Marker marker = CorrelationFixture.marker(output.resolve(OutputFiles.PARTIAL));
        assertThat(marker.getState())
                .as("Partial marker promoted completion")
                .isEqualTo(ReportProto.MarkerState.MARKER_STATE_INCOMPLETE);
        assertThat(marker.getCoverageComplete())
                .as("Partial marker promoted completion")
                .isFalse();
        // Parsed strictly: a partial report has no field for a population estimate or a synthetic JFR at all.
        ReportProto.PartialReport report = CorrelationFixture.parse(
                        output.resolve(OutputFiles.INCOMPLETE_REPORT), ReportProto.PartialReport.newBuilder())
                .build();
        assertThat(report.getCoverageComplete())
                .as("Partial report claims coverage")
                .isFalse();
        assertThat(report.getPairFinality()).isEqualTo("provisional-within-recovered-records");
        assertThat(report.getIncompleteReasonsList())
                .as("The marker repeats the report's reasons")
                .containsExactlyElementsOf(marker.getIncompleteReasonsList())
                .contains(ReportProto.IncompleteCause.INCOMPLETE_CAUSE_MISSING_CAPTURE_END);
        assertThat(report.getProvisionalPairs()).isEqualTo(1);
        assertThat(Files.readString(output.resolve(OutputFiles.INCOMPLETE_COLLAPSED)))
                .as("Graph lost incomplete root label")
                .startsWith("[INCOMPLETE capture: observed prefix only];");
        List<ReportProto.Pair> pairs = CorrelationFixture.pairs(output.resolve(OutputFiles.INCOMPLETE_PAIRS));
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).getCorrelationId()).isEqualTo(CorrelationFixture.COOKIE);
        assertThat(pairs.get(0).hasHandlerDelayNanos())
                .as("Missing clock proof got a guessed delay")
                .isFalse();
        assertThat(CorrelationFixture.classifiedRecords(output.resolve(OutputFiles.INCOMPLETE_CLASSIFIED_RECORDS)))
                .as("Partial classified records are provisional")
                .extracting(ReportProto.ClassifiedRecord::getClassification)
                .containsOnly(ReportProto.Classification.CLASSIFICATION_PROVISIONAL_MATCH);
        rejects(() -> OffCpuCorrelator.run(cli), "partial-output");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"--format, jfr", "--format, both", "--estimate-population, true", "--quantum-ns, 1"})
    void unsupportedPartialOutput(String option, String value, @TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Files.write(source, prefix(fixture.complete(), RecordCase.OBSERVATION));
        Path rejected = dir.resolve("rejected-output");
        CommandLineFixture.usageError(
                "Partial mode supports",
                "--source",
                source.toString(),
                "--jfr",
                fixture.jfr().toString(),
                "--output",
                rejected.toString(),
                "--partial",
                "true",
                option,
                value);
        assertThat(rejected).as("Unsupported partial output created files").doesNotExist();
    }

    @Test
    void partialCliSubprocess(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Files.write(source, prefix(fixture.complete(), RecordCase.OBSERVATION));
        Path subprocess = dir.resolve("partial-subprocess");
        Path log = dir.resolve("partial-subprocess.log");
        Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        OffCpuCorrelator.class.getName(),
                        "--source",
                        source.toString(),
                        "--jfr",
                        fixture.jfr().toString(),
                        "--output",
                        subprocess.toString(),
                        "--partial",
                        "true")
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly();
        assertThat(exited).as("Partial CLI subprocess timed out").isTrue();
        assertThat(process.exitValue())
                .as("Partial CLI process exit mismatch: %s", Files.readString(log))
                .isEqualTo(2);
        assertThat(subprocess.resolve(OutputFiles.PARTIAL))
                .as("Partial CLI process marker missing: %s", Files.readString(log))
                .exists();
    }

    /** Running the JAR with no arguments is a request for help, not a failed analysis. */
    @Test
    void helpSubprocess(@TempDir Path dir) throws Exception {
        Path helpLog = dir.resolve("help-subprocess.log");
        Process help = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        OffCpuCorrelator.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(helpLog.toFile())
                .start();
        boolean exited = help.waitFor(30, TimeUnit.SECONDS);
        if (!exited) help.destroyForcibly();
        assertThat(exited).as("Help subprocess timed out").isTrue();
        String helpText = Files.readString(helpLog);
        assertThat(help.exitValue())
                .as("No-argument run must exit 0: %s", helpText)
                .isZero();
        assertThat(helpText)
                .as("No-argument run must print usage")
                .startsWith("Usage: java -jar jonoffcpu-correlator.jar")
                .contains(OutputFiles.REPORT)
                .contains(OutputFiles.PARTIAL);
    }
}
