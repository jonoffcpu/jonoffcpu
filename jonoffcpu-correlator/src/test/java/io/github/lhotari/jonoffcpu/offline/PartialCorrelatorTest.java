// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

/**
 * Real JFR/source truncation and semantic failure fixtures for the explicit incomplete-run path.
 */
public final class PartialCorrelatorTest {
    private static final OfflineCorrelator.Limits DEFAULTS = OfflineCorrelator.Limits.defaults();

    @Name("profiler.SignalSampleV2")
    @StackTrace(false)
    public static class UnknownSignal extends Event {}

    private PartialCorrelatorTest() {}

    @FunctionalInterface
    interface CheckedAction {
        void run() throws Exception;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(CheckedAction action, String reason) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected failure: " + reason);
        } catch (IOException | IllegalArgumentException expected) {
            check(expected.getMessage().contains(reason), "Unexpected failure: " + expected);
        }
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
        check(found != null, "No " + recordType + " row");
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

    private static void sourcePrefixFixtures(
            Path dir, Path source, Path jfr, JsonObject observation, List<JsonObject> complete) throws Exception {
        Files.write(source, prefix(complete, "captureEnd"));
        rejects(() -> OfflineCorrelator.correlate(source, jfr, DEFAULTS), "Missing source finalization");
        var result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
        check(result.provisionalPairs() == 1 && result.identityUnverified() == 0, "Missing-footer pair not retained");
        check(result.pairs().get(0).handlerDelayNanos() == null, "Missing footer invented clock proof");
        check(
                result.diagnostics().get("clockVerification").getAsString().equals("unavailable"),
                "Clock claim invented");
        check(
                result.records().stream().allMatch(row -> row.classification().equals("provisional-match")),
                "Prefix matches have final classifications");
        check(result.submittedButNotParsed().equals("0"), "Observed terminal stats not retained");
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
        check(
                result.provisionalPairs() == 1
                        && result.pairedSelectedObservedDurationNanos().equals("1000"),
                "Prefix source-window clipping lost delayed sample");
        check(result.sourceEnd() == null, "Missing captureEnd fabricated");
        // A record whose length prefix promises more bytes than the file holds is a truncated tail.
        for (byte[] tail : List.of(new byte[] {40, 10, 24}, new byte[] {(byte) 0x9a, 0x02})) {
            byte[] prefix = prefix(complete, "observation");
            byte[] bytes = Arrays.copyOf(prefix, prefix.length + tail.length);
            System.arraycopy(tail, 0, bytes, prefix.length, tail.length);
            Files.write(source, bytes);
            result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
            check(result.sourceRows() == 1 && result.provisionalPairs() == 1, "Trailing fragment became a row");
            check(
                    result.diagnostics()
                                    .getAsJsonObject("sourceParse")
                                    .get("ignoredTrailingBytes")
                                    .getAsInt()
                            == tail.length,
                    "Trailing byte count wrong");
        }
        // A footer record whose last byte never landed is still an incomplete tail.
        byte[] whole = CaptureStreamFixture.encode(complete);
        Files.write(source, Arrays.copyOf(whole, whole.length - 1));
        result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
        check(
                result.diagnostics().get("apStopVerification").getAsString().equals("unavailable"),
                "Unterminated footer certified AP completion");
        JsonObject end = row(complete, "captureEnd").deepCopy();
        end.addProperty("state", "incomplete");
        end.getAsJsonObject("counters").getAsJsonObject("kernel").addProperty("targetNamespaceFailures", "1");
        Files.write(source, concat(prefix(complete, "observation"), record(end)));
        rejects(() -> OfflineCorrelator.correlate(source, jfr, DEFAULTS), "Missing source finalization");
        result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
        check(
                result.diagnostics()
                        .getAsJsonArray("incompleteReasons")
                        .toString()
                        .contains("source-capture-incomplete"),
                "Incomplete source state missing");
        check(
                result.sourceEnd()
                        .getAsJsonObject("counters")
                        .getAsJsonObject("kernel")
                        .get("targetNamespaceFailures")
                        .getAsString()
                        .equals("1"),
                "Source failure counter erased");

        Files.write(source, concat(prefix(complete, "observation"), record(observation)));
        result = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
        check(
                result.provisionalPairs() == 0 && result.invalidSource() == 2 && result.invalidJfr() == 1,
                "Late prefix source duplicate joined");
        check(result.sourceSelectedObservedDurationNanos().equals("0"), "Duplicate source duration counted");
        Files.write(source, prefix(complete, "observation"));
        Path duplicate = recording(dir.resolve("duplicate.jfr"), 2, true, false, false, false, 1, 2);
        result = OfflineCorrelator.correlatePartial(source, duplicate, DEFAULTS);
        check(
                result.provisionalPairs() == 0 && result.invalidSource() == 1 && result.invalidJfr() == 2,
                "AP duplicate joined in prefix");
        check(result.sourceSelectedObservedDurationNanos().equals("3000"), "AP ambiguity erased source duration");
        Path statsFirst = recording(dir.resolve("stats-first.jfr"), 1, true, true, false, false, 1, 1);
        result = OfflineCorrelator.correlatePartial(source, statsFirst, DEFAULTS);
        check(result.provisionalPairs() == 1, "Physical stats-before-sample order rejected");
        Path missingStats = recording(dir.resolve("missing-stats.jfr"), 1, false, false, false, false, 1, 0);
        rejects(() -> SignalJfrExporter.visit(missingStats, ignored -> {}), "Missing terminal");
        result = OfflineCorrelator.correlatePartial(source, missingStats, DEFAULTS);
        check(
                result.provisionalPairs() == 1 && result.submittedButNotParsed() == null,
                "Unknown final submitted count replaced with zero");
        check(
                !result.diagnostics()
                        .getAsJsonObject("jfrParse")
                        .get("terminalStatsPresent")
                        .getAsBoolean(),
                "Missing stats claimed present");
    }

    private static void jfrPrefixFixtures(Path dir, Path source, JsonObject observation) throws Exception {
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
        OfflineCorrelatorTest.source(dir, combined, List.of(observation, second));
        check(OfflineCorrelator.correlate(source, combined, DEFAULTS).matched() == 2, "Multi-chunk fixture invalid");
        byte[] completeSource = Files.readAllBytes(source);
        for (int cut : new int[] {0, 4, 64, head.length, head.length + 7, head.length + 64, both.length - 1}) {
            Files.write(combined, Arrays.copyOf(both, cut));
            rejects(() -> OfflineCorrelator.correlate(source, combined, DEFAULTS), "JFR byte count mismatch");
            var result = OfflineCorrelator.correlatePartial(source, combined, DEFAULTS);
            check(
                    result.provisionalPairs() <= result.jfrSamples() && result.provisionalPairs() <= 2,
                    "Truncated JFR invented a pair");
            check(
                    result.diagnostics()
                            .get("artifactVerification")
                            .getAsString()
                            .equals("unverified-incomplete"),
                    "Truncated JFR declared hash verified");
            check(
                    result.diagnostics().get("clockVerification").getAsString().equals("verified-footer"),
                    "Observed consistent footer clock proof was erased");
            if (cut == head.length) check(result.provisionalPairs() == 1, "Complete first JFR chunk not recovered");
        }
        Files.write(combined, both);
        Files.write(source, completeSource);
    }

    private static void hardFailures(Path dir, Path source, Path jfr, List<JsonObject> complete) throws Exception {
        Files.write(source, prefix(complete, "observation"));
        for (String bad : List.of("{}", "{invalid}", "{\"schemaVersion\":1,\"schemaVersion\":1}")) {
            Files.write(source, concat(prefix(complete, "observation"), CaptureStreamFixture.controlRecord(bad)));
            rejects(
                    () -> OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS),
                    bad.equals("{}")
                            ? "schemaVersion"
                            : bad.contains("invalid") ? "malformed JSON" : "Duplicate JSON field");
        }
        // Rows are shared objects now, so fixtures mutate copies.
        JsonObject header = row(complete, "captureStart").deepCopy();
        header.addProperty("schemaVersion", 3);
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
        for (var limit : List.of(
                new OfflineCorrelator.Limits(1, 1024 * 1024, 1024 * 1024, 4096, null, null, null),
                new OfflineCorrelator.Limits(100, 10, 1024 * 1024, 4096, null, null, null),
                new OfflineCorrelator.Limits(100, 1024 * 1024, 256, 4096, null, null, null),
                new OfflineCorrelator.Limits(100, 1024 * 1024, 1024 * 1024, 1, null, null, null))) {
            rejects(
                    () -> OfflineCorrelator.correlatePartial(source, jfr, limit),
                    limit.maxRows() == 1
                            ? "row limit"
                            : limit.maxLineBytes() == 10
                                    ? "record byte limit"
                                    : limit.maxRetainedBytes() == 256 ? "budget" : "frame count");
        }
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
        List<JsonObject> tampered = new java.util.ArrayList<>();
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
            public void stack(long stackId, int frameCount) {}

            @Override
            public void observation(int rowNumber, CaptureProto.Observation observation) {}
        });
        Files.write(source, prefix(complete, "captureStart"));
        rejects(() -> snapshot.verifyUnchanged(source, jfr), "Inputs changed");
        Files.write(source, prefix(complete, "observation"));
    }

    private static void outputFixtures(Path dir, Path source, Path jfr) throws Exception {
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
        check(OffCpuCorrelator.run(cli) == 2, "Partial API/CLI status must be 2");
        check(
                !Files.exists(output.resolve(OutputFiles.COMPLETE))
                        && !Files.exists(output.resolve(OutputFiles.SYNTHETIC_JFR))
                        && !Files.exists(output.resolve(OutputFiles.COLLAPSED)),
                "Partial publication resembles complete output");
        JsonObject marker = json(output.resolve(OutputFiles.PARTIAL));
        check(
                marker.get("state").getAsString().equals("incomplete")
                        && !marker.get("coverageComplete").getAsBoolean(),
                "Partial marker promoted completion");
        JsonObject report = json(output.resolve(OutputFiles.INCOMPLETE_REPORT));
        check(
                !report.has("populationEstimate")
                        && report.get("analysisMode").getAsString().equals("partial"),
                "Partial report estimates complete population");
        check(
                Files.readString(output.resolve(OutputFiles.INCOMPLETE_COLLAPSED))
                        .startsWith("[INCOMPLETE capture: observed prefix only];"),
                "Graph lost incomplete root label");
        check(
                Files.readString(output.resolve(OutputFiles.INCOMPLETE_PAIRS)).contains("\"handlerDelayNanos\":null"),
                "Missing clock proof got a guessed delay");
        rejects(() -> OffCpuCorrelator.run(cli), "partial-output");
        for (List<String> extra : List.of(
                List.of("--format", "jfr"),
                List.of("--format", "both"),
                List.of("--estimate-population", "true"),
                List.of("--quantum-ns", "1"))) {
            Path rejected = dir.resolve("rejected-output-" + UUID.randomUUID());
            List<String> args = new ArrayList<>(List.of(
                    "--source",
                    source.toString(),
                    "--jfr",
                    jfr.toString(),
                    "--output",
                    rejected.toString(),
                    "--partial",
                    "true"));
            args.addAll(extra);
            rejects(() -> OffCpuCorrelator.run(args.toArray(String[]::new)), "Partial mode supports");
            check(!Files.exists(rejected), "Unsupported partial output created files");
        }
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
                        jfr.toString(),
                        "--output",
                        subprocess.toString(),
                        "--partial",
                        "true")
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Partial CLI subprocess timed out");
        }
        check(
                process.exitValue() == 2 && Files.exists(subprocess.resolve(OutputFiles.PARTIAL)),
                "Partial CLI process exit/marker mismatch: " + Files.readString(log));
        // Running the JAR with no arguments is a request for help, not a failed analysis.
        Path helpLog = dir.resolve("help-subprocess.log");
        Process help = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        OffCpuCorrelator.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(helpLog.toFile())
                .start();
        if (!help.waitFor(30, TimeUnit.SECONDS)) {
            help.destroyForcibly();
            throw new AssertionError("Help subprocess timed out");
        }
        String helpText = Files.readString(helpLog);
        check(
                help.exitValue() == 0
                        && helpText.startsWith("Usage: java -jar jonoffcpu-correlator.jar")
                        && helpText.contains(OutputFiles.REPORT)
                        && helpText.contains(OutputFiles.PARTIAL),
                "No-argument run must print usage and exit 0: " + helpText);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jonoffcpu-partial-test-");
        try {
            Path jfr = OfflineCorrelatorTest.recording(dir, 1);
            long[] tid = new long[1];
            SignalJfrExporter.visit(jfr, row -> {
                if (row.get("recordType").equals("sample")) tid[0] = (Long) row.get("osThreadId");
            });
            JsonObject observation = OfflineCorrelatorTest.observation(tid[0]);
            Path source = OfflineCorrelatorTest.source(dir, jfr, List.of(observation));
            List<JsonObject> complete = OfflineCorrelatorTest.readRows(source);
            var completeAsPartial = OfflineCorrelator.correlatePartial(source, jfr, DEFAULTS);
            check(
                    completeAsPartial.state().equals("incomplete")
                            && !completeAsPartial.coverageComplete()
                            && completeAsPartial
                                    .diagnostics()
                                    .getAsJsonArray("incompleteReasons")
                                    .toString()
                                    .contains("explicit-partial-mode"),
                    "Explicit partial mode promoted complete inputs");
            sourcePrefixFixtures(dir, source, jfr, observation, complete);
            jfrPrefixFixtures(dir, source, observation);
            hardFailures(dir, source, jfr, complete);
            outputFixtures(dir, source, jfr);
            System.out.println("Partial correlator fixtures passed on " + Runtime.version());
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
