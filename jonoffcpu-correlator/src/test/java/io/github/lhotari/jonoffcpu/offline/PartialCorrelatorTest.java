// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lhotari.jonoffcpu.capture.CaptureProto;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
    private record Fixture(Path jfr, JsonObject observation, Path source, List<JsonObject> complete) {}

    private static Fixture fixture(Path dir) throws IOException {
        Path jfr = OfflineCorrelatorTest.recording(dir, 1);
        long[] tid = new long[1];
        SignalJfrExporter.visit(jfr, row -> {
            if (row.get("recordType").equals("sample")) tid[0] = (Long) row.get("osThreadId");
        });
        JsonObject observation = OfflineCorrelatorTest.observation(tid[0]);
        Path source = OfflineCorrelatorTest.source(dir, jfr, List.of(observation));
        return new Fixture(jfr, observation, source, OfflineCorrelatorTest.readRows(source));
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
            recording.enable(OfflineCorrelatorTest.Capture.class);
            recording.enable(OfflineCorrelatorTest.Sample.class).withStackTrace();
            recording.enable(OfflineCorrelatorTest.Stats.class);
            recording.enable(UnknownSignal.class);
            recording.start();
            new OfflineCorrelatorTest.Capture().commit();
            if (stats && statsFirst) stats(submitted);
            for (int i = 0; i < samples; i++) {
                OfflineCorrelatorTest.Sample sample = new OfflineCorrelatorTest.Sample();
                sample.correlationId = (sample.correlationId & 0xffffffff00000000L) | sequence;
                sample.commit();
            }
            if (unknown) new UnknownSignal().commit();
            if (conflict) {
                OfflineCorrelatorTest.Capture capture = new OfflineCorrelatorTest.Capture();
                capture.sessionId = UUID.randomUUID().toString();
                capture.commit();
            } else {
                // Leave a complete event after the sample, so a bad following chunk cannot hide that
                // sample.
                new OfflineCorrelatorTest.Capture().commit();
            }
            if (stats && !statsFirst) stats(submitted);
            recording.stop();
            recording.dump(path);
        }
        return path;
    }

    private static void stats(int samples) {
        OfflineCorrelatorTest.Stats stats = new OfflineCorrelatorTest.Stats();
        stats.admittedSignals = stats.acceptedCookies = stats.submittedSamples = samples;
        stats.commit();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    /** One row as a length-delimited record, to append to a prefix. */
    private static byte[] record(JsonObject row) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        CaptureStreamFixture.record(row).writeDelimitedTo(bytes);
        return bytes.toByteArray();
    }

    /** The last row of the given type, so fixtures do not depend on record positions. */
    private static JsonObject row(List<JsonObject> rows, String recordType) {
        JsonObject found = null;
        for (JsonObject candidate : rows) {
            if (candidate.get("recordType").getAsString().equals(recordType)) found = candidate;
        }
        assertThat(found).as("No " + recordType + " row").isNotNull();
        return found;
    }

    /** The stream truncated after the last record of the given type. */
    private static byte[] prefix(List<JsonObject> rows, String recordType) throws IOException {
        int count = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).get("recordType").getAsString().equals(recordType)) count = i + 1;
        }
        return CaptureStreamFixture.encode(rows.subList(0, count));
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    @Test
    void completeInputsInPartialMode(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        var completeAsPartial = OfflineCorrelator.correlatePartial(fixture.source(), fixture.jfr(), DEFAULTS);
        assertThat(completeAsPartial.state())
                .as("Explicit partial mode promoted complete inputs")
                .isEqualTo("incomplete");
        assertThat(completeAsPartial.coverageComplete())
                .as("Explicit partial mode promoted complete inputs")
                .isFalse();
        assertThat(completeAsPartial
                        .diagnostics()
                        .getAsJsonArray("incompleteReasons")
                        .toString())
                .as("Explicit partial mode promoted complete inputs")
                .contains("explicit-partial-mode");
    }

    @Test
    void sourcePrefixFixtures(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Path jfr = fixture.jfr();
        JsonObject observation = fixture.observation();
        List<JsonObject> complete = fixture.complete();
        Files.write(source, prefix(complete, "captureEnd"));
        rejects(() -> OfflineCorrelator.correlate(source, jfr, DEFAULTS), "Missing source finalization");
        var result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
        assertThat(result.provisionalPairs())
                .as("Missing-footer pair not retained")
                .isEqualTo(1);
        assertThat(result.identityUnverified())
                .as("Missing-footer pair not retained")
                .isZero();
        assertThat(result.pairs().get(0).handlerDelayNanos())
                .as("Missing footer invented clock proof")
                .isNull();
        assertThat(result.diagnostics().get("clockVerification").getAsString())
                .as("Clock claim invented")
                .isEqualTo("unavailable");
        assertThat(result.records())
                .as("Prefix matches have final classifications")
                .allMatch(row -> row.classification().equals("provisional-match"));
        assertThat(result.submittedButNotParsed())
                .as("Observed terminal stats not retained")
                .isEqualTo("0");
        var delayLimit = new OfflineCorrelator.Limits(
                DEFAULTS.maxRows(),
                DEFAULTS.maxLineBytes(),
                DEFAULTS.maxRetainedBytes(),
                DEFAULTS.maxFrames(),
                BigInteger.ONE,
                null,
                null);
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, delayLimit), "without verified clock");

        Files.write(source, prefix(complete, "observation"));
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
        assertThat(result.pairedSelectedObservedDurationNanos())
                .as("Prefix source-window clipping lost delayed sample")
                .isEqualTo("1000");
        assertThat(result.sourceEnd()).as("Missing captureEnd fabricated").isNull();
        // A record whose length prefix promises more bytes than the file holds is a truncated tail.
        for (byte[] tail : List.of(new byte[] {40, 10, 24}, new byte[] {(byte) 0x9a, 0x02})) {
            byte[] prefix = prefix(complete, "observation");
            byte[] bytes = Arrays.copyOf(prefix, prefix.length + tail.length);
            System.arraycopy(tail, 0, bytes, prefix.length, tail.length);
            Files.write(source, bytes);
            result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
            assertThat(result.sourceRows()).as("Trailing fragment became a row").isEqualTo(1);
            assertThat(result.provisionalPairs())
                    .as("Trailing fragment became a row")
                    .isEqualTo(1);
            assertThat(result.diagnostics()
                            .getAsJsonObject("sourceParse")
                            .get("ignoredTrailingBytes")
                            .getAsInt())
                    .as("Trailing byte count wrong")
                    .isEqualTo(tail.length);
        }
        // A footer record whose last byte never landed is still an incomplete tail.
        byte[] whole = CaptureStreamFixture.encode(complete);
        Files.write(source, Arrays.copyOf(whole, whole.length - 1));
        result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
        assertThat(result.diagnostics().get("apStopVerification").getAsString())
                .as("Unterminated footer certified AP completion")
                .isEqualTo("unavailable");
        JsonObject end = row(complete, "captureEnd").deepCopy();
        end.addProperty("state", "incomplete");
        end.getAsJsonObject("counters").getAsJsonObject("kernel").addProperty("targetNamespaceFailures", "1");
        Files.write(source, concat(prefix(complete, "observation"), record(end)));
        rejects(() -> OfflineCorrelator.correlate(source, jfr, DEFAULTS), "Missing source finalization");
        result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
        assertThat(result.diagnostics().getAsJsonArray("incompleteReasons").toString())
                .as("Incomplete source state missing")
                .contains("source-capture-incomplete");
        assertThat(result.sourceEnd()
                        .getAsJsonObject("counters")
                        .getAsJsonObject("kernel")
                        .get("targetNamespaceFailures")
                        .getAsString())
                .as("Source failure counter erased")
                .isEqualTo("1");

        Files.write(source, concat(prefix(complete, "observation"), record(observation)));
        result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
        assertThat(result.provisionalPairs())
                .as("Late prefix source duplicate joined")
                .isZero();
        assertThat(result.invalidSource())
                .as("Late prefix source duplicate joined")
                .isEqualTo(2);
        assertThat(result.invalidJfr())
                .as("Late prefix source duplicate joined")
                .isEqualTo(1);
        assertThat(result.sourceSelectedObservedDurationNanos())
                .as("Duplicate source duration counted")
                .isEqualTo("0");
        Files.write(source, prefix(complete, "observation"));
        Path duplicate = recording(dir.resolve("duplicate.jfr"), 2, true, false, false, false, 1, 2);
        result = OfflineCorrelator.correlatePartial(source, duplicate, DEFAULTS);
        assertThat(result.provisionalPairs())
                .as("AP duplicate joined in prefix")
                .isZero();
        assertThat(result.invalidSource()).as("AP duplicate joined in prefix").isEqualTo(1);
        assertThat(result.invalidJfr()).as("AP duplicate joined in prefix").isEqualTo(2);
        assertThat(result.sourceSelectedObservedDurationNanos())
                .as("AP ambiguity erased source duration")
                .isEqualTo("3000");
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
        assertThat(result.submittedButNotParsed())
                .as("Unknown final submitted count replaced with zero")
                .isNull();
        assertThat(result.diagnostics()
                        .getAsJsonObject("jfrParse")
                        .get("terminalStatsPresent")
                        .getAsBoolean())
                .as("Missing stats claimed present")
                .isFalse();
    }

    /**
     * A two-chunk JFR cut at chunk-header, mid-chunk and chunk-boundary offsets; the offsets depend on the
     * size of the recorded first chunk, so each cut is a test computed at runtime.
     */
    @TestFactory
    Stream<DynamicTest> jfrPrefixFixtures(@TempDir Path dir) throws Exception {
        JsonObject observation = fixture(dir).observation();
        Path first = recording(dir.resolve("chunk-first.jfr"), 1, false, false, false, false, 1, 0);
        Path last = recording(dir.resolve("chunk-last.jfr"), 1, true, false, false, false, 2, 2);
        byte[] head = Files.readAllBytes(first);
        byte[] tail = Files.readAllBytes(last);
        byte[] both = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, both, head.length, tail.length);
        Path combined = dir.resolve("multichunk.jfr");
        Files.write(combined, both);
        JsonObject second = observation.deepCopy();
        second.addProperty("correlationId", "8000000100000002");
        Path source = OfflineCorrelatorTest.source(dir, combined, List.of(observation, second));
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
                            .isLessThanOrEqualTo(result.jfrSamples())
                            .isLessThanOrEqualTo(2);
                    assertThat(result.diagnostics().get("artifactVerification").getAsString())
                            .as("Truncated JFR declared hash verified")
                            .isEqualTo("unverified-incomplete");
                    assertThat(result.diagnostics().get("clockVerification").getAsString())
                            .as("Observed consistent footer clock proof was erased")
                            .isEqualTo("verified-footer");
                    if (cut == head.length) {
                        assertThat(result.provisionalPairs())
                                .as("Complete first JFR chunk not recovered")
                                .isEqualTo(1);
                    }
                }));
    }

    /** A control record appended to a valid prefix is still read with the strict parser. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "{}                                     | schemaVersion",
                "{invalid}                              | malformed JSON",
                "{\"schemaVersion\":1,\"schemaVersion\":1} | Duplicate JSON field"
            })
    void malformedControlRecord(String bad, String reason, @TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Files.write(source, concat(prefix(fixture.complete(), "observation"), CaptureStreamFixture.controlRecord(bad)));
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
        Files.write(source, prefix(fixture.complete(), "observation"));
        var limit = new OfflineCorrelator.Limits(maxRows, maxLineBytes, maxRetainedBytes, maxFrames, null, null, null);
        rejects(() -> OfflineCorrelator.correlatePartial(source, fixture.jfr(), limit), reason);
    }

    @Test
    void hardFailures(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Path jfr = fixture.jfr();
        List<JsonObject> complete = fixture.complete();
        // Rows are shared objects now, so fixtures mutate copies.
        JsonObject header = row(complete, "captureStart").deepCopy();
        header.addProperty("schemaVersion", 5);
        Files.write(source, CaptureStreamFixture.encode(List.of(header)));
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS), "Unsupported source schema");
        header.addProperty("schemaVersion", 2);
        header.getAsJsonObject("sampling").addProperty("minOffCpuMicros", 10);
        header.getAsJsonObject("sampling").addProperty("maxOffCpuMicros", 10);
        Files.write(source, CaptureStreamFixture.encode(List.of(header)));
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS), "Invalid duration policy bounds");
        Files.write(source, prefix(complete, "observation"));
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
        Files.write(source, CaptureStreamFixture.encode(complete));
        byte[] original = Files.readAllBytes(jfr);
        byte[] corrupted = original.clone();
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(jfr, corrupted);
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS), "JFR digest mismatch");
        Files.write(jfr, original);
        List<JsonObject> tampered = new ArrayList<>();
        for (JsonObject row : complete) tampered.add(row.deepCopy());
        for (JsonObject row : tampered) {
            if (row.get("recordType").getAsString().equals("observation")) row.addProperty("hostTid", 457);
        }
        Files.write(source, CaptureStreamFixture.encode(tampered));
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS), "Source digest mismatch");
        Files.write(source, concat(CaptureStreamFixture.encode(complete), CaptureStreamFixture.controlRecord("{}")));
        rejects(() -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS), "Rows follow captureFinalized");
        Files.write(source, prefix(complete, "observation"));
        CaptureInput snapshot = CaptureInput.readPartial(source, jfr, DEFAULTS, new CaptureInput.SourceVisitor() {
            @Override
            public void reading(CaptureInput.Budget budget, LongIntMap announcedStacks) {}

            @Override
            public void start(JsonObject captureStart) {}

            @Override
            public void stack(long stackId, CaptureProto.Stack stack) {}

            @Override
            public void observation(int rowNumber, CaptureProto.Observation observation) {}
        });
        Files.write(source, prefix(complete, "captureStart"));
        rejects(() -> snapshot.verifyUnchanged(source, jfr), "Inputs changed");
    }

    @Test
    void outputFixtures(@TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Path jfr = fixture.jfr();
        // A source prefix without its footer: no clock proof, so no handler delay can be derived.
        Files.write(source, prefix(fixture.complete(), "observation"));
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
        JsonObject marker = json(output.resolve(OutputFiles.PARTIAL));
        assertThat(marker.get("state").getAsString())
                .as("Partial marker promoted completion")
                .isEqualTo("incomplete");
        assertThat(marker.get("coverageComplete").getAsBoolean())
                .as("Partial marker promoted completion")
                .isFalse();
        JsonObject report = json(output.resolve(OutputFiles.INCOMPLETE_REPORT));
        assertThat(report.has("populationEstimate"))
                .as("Partial report estimates complete population")
                .isFalse();
        assertThat(report.get("analysisMode").getAsString())
                .as("Partial report estimates complete population")
                .isEqualTo("partial");
        assertThat(Files.readString(output.resolve(OutputFiles.INCOMPLETE_COLLAPSED)))
                .as("Graph lost incomplete root label")
                .startsWith("[INCOMPLETE capture: observed prefix only];");
        assertThat(Files.readString(output.resolve(OutputFiles.INCOMPLETE_PAIRS)))
                .as("Missing clock proof got a guessed delay")
                .contains("\"handlerDelayNanos\":null");
        rejects(() -> OffCpuCorrelator.run(cli), "partial-output");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"--format, jfr", "--format, both", "--estimate-population, true", "--quantum-ns, 1"})
    void unsupportedPartialOutput(String option, String value, @TempDir Path dir) throws Exception {
        Fixture fixture = fixture(dir);
        Path source = fixture.source();
        Files.write(source, prefix(fixture.complete(), "observation"));
        Path rejected = dir.resolve("rejected-output");
        CommandLineTest.usageError(
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
        Files.write(source, prefix(fixture.complete(), "observation"));
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
