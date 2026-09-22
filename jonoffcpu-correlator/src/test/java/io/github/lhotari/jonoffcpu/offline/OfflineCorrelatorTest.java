// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

/** Real JFR reader plus synthetic source fixtures, including ambiguity, corruption and clipping. */
public final class OfflineCorrelatorTest {
    private static final String SESSION = UUID.randomUUID().toString();
    private static final long EPOCH = 0x80000001L;
    private static final long COOKIE = EPOCH << 32 | 1;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    @Name("profiler.SignalCapture")
    @StackTrace(false)
    public static class Capture extends Event {
        public int schemaVersion = 1;
        public String sessionId = SESSION;
        public long captureEpoch = EPOCH;
        public String signalDelivery = "queued";
        public int signal = 35;
        public long processId = ProcessHandle.current().pid();
        public long processStartTimeMillis = 1000;
    }

    @Name("profiler.SignalSample")
    @StackTrace(true)
    public static class Sample extends Event {
        public long correlationId = COOKIE;
        public long monotonicTimeNanos = 5000;
    }

    @Name("profiler.SignalCaptureStats")
    @StackTrace(false)
    public static class Stats extends Event {
        public int schemaVersion = 1;
        public String sessionId = SESSION;
        public long captureEpoch = EPOCH;
        public long admittedSignals = 1;
        public long invalidSignalCode;
        public long zeroCookie;
        public long zeroSequence;
        public long staleEpoch;
        public long acceptedCookies = 1;
        public long captureFailures;
        public long submittedSamples = 1;
    }

    static JsonObject row(String type) {
        JsonObject row = new JsonObject();
        row.addProperty("schemaVersion", 1);
        row.addProperty("recordType", type);
        row.addProperty("sessionId", SESSION);
        row.addProperty("captureEpoch", EPOCH);
        return row;
    }

    static Path recording(Path dir, int count) throws IOException {
        Path file = dir.resolve("original-" + count + ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(Capture.class);
            recording.enable(Sample.class).withStackTrace();
            recording.enable(Stats.class);
            recording.start();
            new Capture().commit();
            for (int i = 0; i < count; i++) new Sample().commit();
            Stats stats = new Stats();
            stats.admittedSignals = stats.acceptedCookies = stats.submittedSamples = count;
            stats.commit();
            recording.stop();
            recording.dump(file);
        }
        return file;
    }

    static Path partialRecording(Path dir, int count) throws IOException {
        Path file = dir.resolve("partial-" + count + ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(Sample.class).withStackTrace();
            recording.start();
            for (int i = 0; i < count; i++) new Sample().commit();
            recording.stop();
            recording.dump(file);
        }
        return file;
    }

    static JsonObject observation(long tid) {
        JsonObject row = row("observation");
        row.addProperty("sourceId", "jonoffcpu.offcpu.v1");
        row.addProperty("correlationId", "8000000100000001");
        row.addProperty("hostTgid", 123);
        row.addProperty("hostTid", 456);
        row.addProperty("targetTid", tid);
        row.addProperty("targetTgid", ProcessHandle.current().pid());
        row.addProperty("admissionThreshold", 42949673);
        row.addProperty("processGenerationNs", "100");
        row.addProperty("registrationToken", "0000000000000001");
        row.addProperty("threadGenerationNs", "200");
        row.addProperty("startMonotonicNanos", "1000");
        row.addProperty("endMonotonicNanos", "4000");
        row.addProperty("signalResult", 0);
        for (String field : List.of("kernelStack", "userStack")) {
            JsonObject stack = new JsonObject();
            stack.addProperty("status", "ok");
            stack.add("frames", new JsonArray());
            row.add(field, stack);
        }
        return row;
    }

    static JsonObject uniformSampling() {
        JsonObject admission = new JsonObject();
        admission.addProperty("policy", "uniform");
        admission.addProperty("probability", "0.01");
        admission.addProperty("probabilityThreshold", 42949673);
        return sampling(admission);
    }

    static JsonObject sampling(JsonObject admission) {
        JsonObject sampling = new JsonObject();
        sampling.add("minOffCpuMicros", JsonNull.INSTANCE);
        sampling.add("maxOffCpuMicros", JsonNull.INSTANCE);
        sampling.add("admission", admission);
        return sampling;
    }

    static Path source(Path dir, Path jfr, List<JsonObject> observations) throws IOException {
        return source(dir, jfr, observations, uniformSampling());
    }

    static Path source(Path dir, Path jfr, List<JsonObject> observations, JsonObject sampling) throws IOException {
        List<JsonObject> samples = new ArrayList<>();
        List<JsonObject> statsRows = new ArrayList<>();
        SignalJfrExporter.visit(jfr, raw -> {
            JsonObject row = GSON.toJsonTree(raw).getAsJsonObject();
            if (raw.get("recordType").equals("sample")) samples.add(row);
            if (raw.get("recordType").equals("stats")) statsRows.add(row);
        });
        JsonObject start = row("captureStart");
        start.addProperty("sourceId", "jonoffcpu.offcpu.v1");
        start.addProperty("signal", 35);
        start.addProperty("signalDelivery", "queued");
        start.addProperty("hostTgid", 123);
        start.addProperty("targetPid", ProcessHandle.current().pid());
        start.add("sampling", sampling);
        start.addProperty("processGenerationNs", "100");
        start.addProperty("timeNamespaceInode", "42");
        start.addProperty("pidNamespaceDevice", "4");
        start.addProperty("pidNamespaceInode", "43");
        start.addProperty("registrationToken", "0000000000000001");
        start.addProperty("startedMonotonicNanos", "500");
        JsonObject end = row("captureEnd");
        end.addProperty("state", "complete");
        end.addProperty("drainTimedOut", false);
        end.addProperty("startedMonotonicNanos", "500");
        end.addProperty("stoppedMonotonicNanos", "6000");
        end.addProperty("detachedMonotonicNanos", "7000");
        end.addProperty("drainCompletedMonotonicNanos", "8000");
        JsonObject userspace = new JsonObject();
        userspace.addProperty("receivedObservations", Integer.toString(observations.size()));
        userspace.addProperty("writtenObservations", Integer.toString(observations.size()));
        userspace.addProperty("writeFailures", "0");
        userspace.addProperty("pollFailures", "0");
        JsonObject counters = new JsonObject();
        counters.add("userspace", userspace);
        JsonObject kernel = new JsonObject();
        kernel.addProperty("targetNamespaceFailures", "0");
        kernel.addProperty("eligibleIntervals", Integer.toString(observations.size()));
        kernel.addProperty("admissionRejections", "0");
        kernel.addProperty("selectedIntervals", Integer.toString(observations.size()));
        for (String key : List.of(
                "ringReserveFailures",
                "sequenceExhaustions",
                "sequenceContentions",
                "lifetimeRejections",
                "threadStateFailures")) {
            kernel.addProperty(key, "0");
        }
        counters.add("kernel", kernel);
        end.add("counters", counters);
        StringBuilder raw = new StringBuilder(start + "\n");
        for (JsonObject observation : observations) raw.append(observation).append('\n');
        raw.append(end).append('\n');
        byte[] bytes = raw.toString().getBytes(StandardCharsets.UTF_8);
        JsonObject footer = row("captureFinalized");
        footer.addProperty("state", "complete");
        JsonObject inputs = start.deepCopy();
        inputs.remove("recordType");
        inputs.addProperty("clockVerified", true);
        inputs.addProperty("monotonicOffsetNanos", "0");
        inputs.add("verifiedIdentity", inputs.deepCopy());
        JsonObject stats = statsRows.get(0).deepCopy();
        for (String key : List.of("schemaVersion", "recordType", "sessionId", "captureEpoch", "startTime"))
            stats.remove(key);
        inputs.add("apStats", stats);
        JsonObject sourceArtifact = new JsonObject();
        sourceArtifact.addProperty("path", "an-unrelated-old-location");
        sourceArtifact.addProperty("rawBytes", Integer.toString(bytes.length));
        sourceArtifact.addProperty(
                "rawSha256", CaptureInput.hex(CaptureInput.sha256().digest(bytes)));
        inputs.add("sourceArtifact", sourceArtifact);
        JsonObject jfrArtifact = new JsonObject();
        jfrArtifact.addProperty("path", "an-unrelated-old-jfr-location");
        jfrArtifact.addProperty("bytes", Long.toString(Files.size(jfr)));
        jfrArtifact.addProperty("sha256", CaptureInput.digest(jfr));
        inputs.add("jfrArtifact", jfrArtifact);
        footer.add("analysisInputs", inputs);
        footer.addProperty(
                "apStopResponse",
                "signal-capture-v1 stopped id="
                        + SESSION
                        + " delivery=queued signal=35 epoch="
                        + EPOCH
                        + " finalized=true stopped-at=9000 reason=explicit"
                        + " admitted="
                        + samples.size()
                        + " invalid-code=0 zero-cookie=0 zero-sequence=0 stale-epoch=0"
                        + " accepted="
                        + samples.size()
                        + " capture-failures=0 submitted="
                        + samples.size()
                        + "\n");
        Path result = dir.resolve("source.jsonl");
        Files.writeString(result, raw + footer.toString() + "\n");
        return result;
    }

    /**
     * Under the proportional policy each row carries the threshold the kernel drew against: 2^32 at and above the
     * reference duration, duration * 2^32 / reference below it. The estimate weights each row by its own threshold.
     */
    private static void proportionalEstimate(Path dir, Path jfr, JsonObject matched, long tid) throws Exception {
        JsonObject admission = new JsonObject();
        admission.addProperty("policy", "proportional");
        admission.addProperty("recordAllAboveMicros", 2);
        JsonObject certain = matched.deepCopy();
        certain.addProperty("admissionThreshold", 1L << 32);
        JsonObject half = observation(tid);
        half.addProperty("correlationId", "8000000100000002");
        half.addProperty("startMonotonicNanos", "4000");
        half.addProperty("endMonotonicNanos", "5000");
        half.addProperty("admissionThreshold", 1L << 31);
        Path source = source(dir, jfr, List.of(certain, half), sampling(admission));
        var result = OfflineCorrelator.correlate(source, jfr, OfflineCorrelator.Limits.defaults());
        check(result.invalidSource() == 0 && result.matched() == 1, "Proportional rows were not accepted");
        check(
                result.populationEstimate().status().equals("available")
                        && result.populationEstimate().admissionPolicy().equals("proportional")
                        && result.populationEstimate()
                                .sourceSelectedObservedDurationNanos()
                                .equals("4000")
                        && result.populationEstimate().estimatedDurationNanos().equals("5000"),
                "Proportional estimate must weight the half-probability row twice: " + result.populationEstimate());
        JsonObject wrong = half.deepCopy();
        wrong.addProperty("admissionThreshold", 1L << 32);
        source = source(dir, jfr, List.of(certain, wrong), sampling(admission));
        result = OfflineCorrelator.correlate(source, jfr, OfflineCorrelator.Limits.defaults());
        check(result.invalidSource() == 1, "Row threshold that disagrees with the policy was accepted");
        check(
                result.populationEstimate().status().equals("unavailable")
                        && result.populationEstimate()
                                .unavailableReasons()
                                .contains("intrinsically-invalid-or-duplicate-source-rows"),
                "Invalid row must disable the estimate");
        source = source(dir, jfr, List.of(certain, half), sampling(admission));
        mutateSource(
                source,
                0,
                row -> row.getAsJsonObject("sampling")
                        .getAsJsonObject("admission")
                        .addProperty("recordAllAboveMicros", 3));
        rejects(source, jfr, OfflineCorrelator.Limits.defaults(), "Source/footer mismatch: sampling");
        JsonObject bounded = sampling(admission);
        bounded.addProperty("minOffCpuMicros", 1);
        source = source(dir, jfr, List.of(certain, half), bounded);
        result = OfflineCorrelator.correlate(source, jfr, OfflineCorrelator.Limits.defaults());
        check(result.invalidSource() == 1, "Strict lower bound did not reject the 1000 ns row");
    }

    /**
     * Rewrite a source prefix and its digest, so validation cannot pass merely by rejecting a stale
     * hash.
     */
    private static void mutateSource(Path source, int rowIndex, java.util.function.Consumer<JsonObject> mutation)
            throws IOException {
        List<String> lines = Files.readAllLines(source);
        JsonObject row =
                com.google.gson.JsonParser.parseString(lines.get(rowIndex)).getAsJsonObject();
        mutation.accept(row);
        lines.set(rowIndex, row.toString());
        int last = lines.size() - 1;
        byte[] prefix = (String.join("\n", lines.subList(0, last)) + "\n").getBytes(StandardCharsets.UTF_8);
        JsonObject footer =
                com.google.gson.JsonParser.parseString(lines.get(last)).getAsJsonObject();
        JsonObject artifact = footer.getAsJsonObject("analysisInputs").getAsJsonObject("sourceArtifact");
        artifact.addProperty("rawBytes", Integer.toString(prefix.length));
        artifact.addProperty("rawSha256", CaptureInput.hex(CaptureInput.sha256().digest(prefix)));
        Files.writeString(source, new String(prefix, StandardCharsets.UTF_8) + footer + "\n");
    }

    private static void check(boolean test, String message) {
        if (!test) throw new AssertionError(message);
    }

    private static void rejects(Path source, Path jfr, OfflineCorrelator.Limits limits, String reason)
            throws IOException {
        try {
            OfflineCorrelator.correlate(source, jfr, limits);
            throw new AssertionError("Expected rejection: " + reason);
        } catch (IOException expected) {
            check(expected.getMessage().contains(reason), "Unexpected rejection: " + expected);
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jonoffcpu-offline-test-");
        try {
            Instant parserBase = Instant.parse("2026-09-21T10:15:30Z");
            check(
                    JfrTimeRange.parse("5s", parserBase).equals(parserBase.plusSeconds(5)),
                    "Relative JFR time was not measured from recording start");
            check(
                    JfrTimeRange.parse("PT0.5S", parserBase).equals(parserBase.plusMillis(500)),
                    "ISO-8601 JFR duration was not accepted");
            check(
                    JfrTimeRange.parse(Long.toString(parserBase.toEpochMilli()), Instant.EPOCH)
                            .equals(parserBase),
                    "Epoch-millisecond JFR boundary was not accepted");
            Path jfr = recording(dir, 1);
            long[] tid = new long[1];
            SignalJfrExporter.visit(jfr, row -> {
                if (row.get("recordType").equals("sample")) tid[0] = (Long) row.get("osThreadId");
            });
            JsonObject observation = observation(tid[0]);
            Path source = source(dir, jfr, List.of(observation));
            var defaults = OfflineCorrelator.Limits.defaults();
            var result = OfflineCorrelator.correlate(source, jfr, defaults);
            check(result.matched() == 1 && result.identityUnverified() == 0, "High-bit cookie match failed");
            check(result.selectedObservedDurationNanos().equals("3000"), "Duration weight mismatch");
            check(
                    result.matches().get(0).handlerDelayNanos().equals(BigInteger.valueOf(1000)),
                    "Delivery delay mismatch");
            check(result.collapsedNanos().containsValue("3000"), "Collapsed duration missing");
            Path output = dir.resolve("analysis");
            OffCpuCorrelator.write(result, output);
            check(Files.isRegularFile(output.resolve(OutputFiles.COMPLETE)), "Missing output completion marker");
            check(
                    Files.readString(output.resolve(OutputFiles.COLLAPSED))
                            .strip()
                            .endsWith(" 3"),
                    "Collapsed output is not weighted in integer microseconds");
            check(
                    Files.readString(output.resolve(OutputFiles.REPORT)).contains("signal-delivery stack"),
                    "Missing stack caveat");
            JsonObject report = com.google.gson.JsonParser.parseString(
                            Files.readString(output.resolve(OutputFiles.REPORT)))
                    .getAsJsonObject();
            check(
                    report.getAsJsonObject("handlerDelayNanos")
                            .get("p99")
                            .getAsString()
                            .equals("1000"),
                    "Delivery delay percentile missing");
            check(!report.has("populationEstimate"), "Population estimate must be opt-in");

            Instant sampleTime = null;
            List<Instant> sampleTimes = new ArrayList<>();
            SignalJfrExporter.visit(jfr, row -> {
                if (row.get("recordType").equals("sample")) {
                    sampleTimes.add(Instant.parse((String) row.get("startTime")));
                }
            });
            sampleTime = sampleTimes.get(0);
            JfrTimeRange.Range beforeSample = JfrTimeRange.resolve(jfr, null, sampleTime.toString());
            result = OfflineCorrelator.correlate(
                    source,
                    jfr,
                    defaults,
                    new OfflineCorrelator.JfrSelection(beforeSample.from(), beforeSample.to(), false));
            check(
                    result.matched() == 0 && result.unmatchedSource() == 1,
                    "JFR event-time selection retained an event at the exclusive boundary");
            check(
                    result.records().stream()
                            .anyMatch(row -> "sample-not-present-in-selected-jfr".equals(row.reason())),
                    "Selected-range source omission was not explained");
            Path selectedOutput = dir.resolve("analysis-selected-range");
            OffCpuCorrelator.main(new String[] {
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
            });
            JsonObject rangeReport = com.google.gson.JsonParser.parseString(
                            Files.readString(selectedOutput.resolve(OutputFiles.REPORT)))
                    .getAsJsonObject();
            check(
                    rangeReport
                            .getAsJsonObject("jfrSelection")
                            .get("missingSourceMatchesExpected")
                            .getAsBoolean(),
                    "JFR selection metadata did not explain expected missing matches");
            check(
                    rangeReport.get("sourceRowsWithoutSelectedJfrSample").getAsLong() == 1,
                    "Expected selected-JFR omission count was not reported");

            Path partialJfr = partialRecording(dir, 1);
            rejects(source, partialJfr, defaults, "JFR byte count mismatch");
            result = OfflineCorrelator.correlate(
                    source, partialJfr, defaults, new OfflineCorrelator.JfrSelection(null, null, true));
            check(
                    result.matched() == 1 && result.submittedButNotParsed() == null,
                    "A valid sample-only partial JFR did not correlate");
            check(
                    !result.jfrSelection().get("captureContextPresent").getAsBoolean()
                            && !result.jfrSelection()
                                    .get("terminalStatsPresent")
                                    .getAsBoolean(),
                    "Partial JFR unexpectedly claimed omitted metadata: " + result.jfrSelection());
            Path partialOutput = dir.resolve("analysis-partial-jfr");
            OffCpuCorrelator.main(new String[] {
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
            });
            check(
                    Files.isRegularFile(partialOutput.resolve(OutputFiles.COMPLETE)),
                    "Partial JFR analysis did not complete");

            Path estimated = dir.resolve("analysis-estimate");
            OffCpuCorrelator.main(new String[] {
                "--source",
                source.toString(),
                "--jfr",
                jfr.toString(),
                "--output",
                estimated.toString(),
                "--format",
                "collapsed",
                "--estimate-population",
                "true"
            });
            JsonObject estimate = com.google.gson.JsonParser.parseString(
                            Files.readString(estimated.resolve(OutputFiles.REPORT)))
                    .getAsJsonObject()
                    .getAsJsonObject("populationEstimate");
            check(
                    estimate.get("method").getAsString().equals("inverse-probability-source-duration"),
                    "Population estimate method missing");
            check(estimate.get("status").getAsString().equals("available"), "Source estimate unavailable");
            check(
                    estimate.get("sourceSelectedObservedDurationNanos")
                            .getAsString()
                            .equals("3000"),
                    "Source duration estimate basis mismatch");
            check(
                    estimate.get("matchedSelectedObservedDurationNanos")
                            .getAsString()
                            .equals("3000"),
                    "Matched duration estimate basis mismatch");
            check(estimate.get("admissionPolicy").getAsString().equals("uniform"), "Population estimate lost policy");
            check(
                    estimate.get("estimatedDurationNanos")
                            .getAsString()
                            .equals(BigInteger.valueOf(3000)
                                    .shiftLeft(32)
                                    .divide(BigInteger.valueOf(42949673))
                                    .toString()),
                    "Population estimate is not the truncated exact inverse-probability sum");
            // Writes its own source.jsonl variants, so it runs before the two-row source below is created.
            proportionalEstimate(dir, jfr, observation, tid[0]);
            JsonObject unmatched = observation(tid[0]);
            unmatched.addProperty("correlationId", "8000000100000002");
            Path twoSource = source(dir, jfr, List.of(observation, unmatched));
            var two = OfflineCorrelator.correlate(twoSource, jfr, defaults);
            check(
                    two.populationEstimate()
                            .sourceSelectedObservedDurationNanos()
                            .equals("6000"),
                    "Unmatched durable source duration omitted from estimate");
            check(
                    two.populationEstimate()
                            .matchedSelectedObservedDurationNanos()
                            .equals("3000"),
                    "Stack-matched duration was incorrectly scaled");
            mutateSource(
                    twoSource,
                    3,
                    row -> row.getAsJsonObject("counters")
                            .getAsJsonObject("kernel")
                            .addProperty("selectedIntervals", "3"));
            two = OfflineCorrelator.correlate(twoSource, jfr, defaults);
            check(
                    two.populationEstimate().status().equals("unavailable")
                            && two.populationEstimate()
                                    .unavailableReasons()
                                    .contains("selected-source-row-count-mismatch"),
                    "Missing selected source row did not disable population estimate");
            source = source(dir, jfr, List.of(observation));
            mutateSource(
                    source,
                    2,
                    row -> row.getAsJsonObject("counters")
                            .getAsJsonObject("kernel")
                            .addProperty("eligibleIntervals", CaptureInput.U64_MAX.toString()));
            result = OfflineCorrelator.correlate(source, jfr, defaults);
            check(
                    result.populationEstimate().status().equals("unavailable")
                            && result.populationEstimate()
                                    .unavailableReasons()
                                    .contains("saturated-counter-eligibleIntervals"),
                    "Saturated source counter was treated as exact coverage");
            source = source(dir, jfr, List.of(observation));
            mutateSource(
                    source,
                    2,
                    row -> row.getAsJsonObject("counters")
                            .getAsJsonObject("kernel")
                            .addProperty("eligibleIntervals", 1));
            result = OfflineCorrelator.correlate(source, jfr, defaults);
            check(
                    result.populationEstimate().status().equals("unavailable")
                            && result.populationEstimate()
                                    .unavailableReasons()
                                    .contains("invalid-counter-eligibleIntervals"),
                    "Non-string estimator counter was accepted");
            source = source(dir, jfr, List.of(observation));
            mutateSource(
                    source,
                    2,
                    row -> row.getAsJsonObject("counters")
                            .getAsJsonObject("kernel")
                            .addProperty("targetNamespaceFailures", "+0"));
            rejects(source, jfr, defaults, "Invalid unsigned decimal");
            source = source(dir, jfr, List.of(observation));
            check(Files.isRegularFile(output.resolve(OutputFiles.SYNTHETIC_JFR)), "Missing default JFR output");
            try (var listing = Files.list(output)) {
                java.util.Set<String> names =
                        listing.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
                check(
                        names.equals(java.util.Set.of(
                                OutputFiles.REPORT,
                                OutputFiles.COLLAPSED,
                                OutputFiles.SYNTHETIC_JFR,
                                OutputFiles.CLASSIFIED_RECORDS,
                                OutputFiles.MATCHES,
                                OutputFiles.COMPLETE)),
                        "Complete analysis wrote an unexpected file set: " + names);
                check(
                        names.stream().allMatch(name -> name.startsWith(OutputFiles.PREFIX)),
                        "Every output must carry the jonoffcpu- prefix: " + names);
            }
            check(Files.isRegularFile(output.resolve(OutputFiles.COLLAPSED)), "Missing default collapsed output");
            check(report.has("syntheticJfr"), "Missing JFR quantization metadata");
            for (String format : List.of("collapsed", "jfr")) {
                Path selected = dir.resolve("analysis-" + format);
                OffCpuCorrelator.main(new String[] {
                    "--source",
                    source.toString(),
                    "--jfr",
                    jfr.toString(),
                    "--output",
                    selected.toString(),
                    "--format",
                    format,
                    "--quantum-ns",
                    "1000"
                });
                check(
                        Files.exists(selected.resolve(OutputFiles.SYNTHETIC_JFR)) == format.equals("jfr"),
                        "JFR output format selection ignored");
                check(
                        Files.exists(selected.resolve(OutputFiles.COLLAPSED)) == format.equals("collapsed"),
                        "Collapsed output format selection ignored");
                JsonObject selectedReport = com.google.gson.JsonParser.parseString(
                                Files.readString(selected.resolve(OutputFiles.REPORT)))
                        .getAsJsonObject();
                check(selectedReport.has("syntheticJfr") == format.equals("jfr"), "Incorrect JFR metadata selection");
                if (format.equals("jfr")) {
                    check(
                            selectedReport
                                            .getAsJsonObject("syntheticJfr")
                                            .get("syntheticEvents")
                                            .getAsLong()
                                    == 3,
                            "CLI quantum was not applied");
                }
            }
            try {
                OffCpuCorrelator.write(result, output);
                throw new AssertionError("Existing analysis overwritten");
            } catch (java.nio.file.FileAlreadyExistsException expected) {
                // Existing output must be preserved, even if its directory is empty.
            }
            var clipped = new OfflineCorrelator.Limits(
                    defaults.maxRows(),
                    defaults.maxLineBytes(),
                    defaults.maxRetainedBytes(),
                    defaults.maxFrames(),
                    null,
                    BigInteger.valueOf(2000),
                    BigInteger.valueOf(3000));
            result = OfflineCorrelator.correlate(source, jfr, clipped);
            check(
                    result.matched() == 1
                            && result.selectedObservedDurationNanos().equals("1000"),
                    "Clipped before join");
            var delayed = new OfflineCorrelator.Limits(
                    defaults.maxRows(),
                    defaults.maxLineBytes(),
                    defaults.maxRetainedBytes(),
                    defaults.maxFrames(),
                    BigInteger.valueOf(999),
                    null,
                    null);
            result = OfflineCorrelator.correlate(source, jfr, delayed);
            check(result.invalidSource() == 1 && result.invalidJfr() == 1, "Delivery delay not rejected");
            source = source(dir, jfr, List.of(observation, observation.deepCopy()));
            result = OfflineCorrelator.correlate(source, jfr, defaults);
            check(result.invalidSource() == 2 && result.invalidJfr() == 1 && result.matched() == 0, "Ambiguity joined");
            JsonObject wrongEpoch = observation.deepCopy();
            wrongEpoch.addProperty("captureEpoch", 1);
            source = source(dir, jfr, List.of(observation, wrongEpoch));
            result = OfflineCorrelator.correlate(source, jfr, defaults);
            check(result.invalidSource() == 2 && result.invalidJfr() == 1, "Invalid duplicate escaped ambiguity check");
            JsonObject wrongBinding = observation.deepCopy();
            wrongBinding.addProperty("registrationToken", "0000000000000002");
            source = source(dir, jfr, List.of(wrongBinding));
            result = OfflineCorrelator.correlate(source, jfr, defaults);
            check(result.invalidSource() == 1 && result.invalidJfr() == 1, "Wrong process registration joined");
            JsonObject outside = observation.deepCopy();
            outside.addProperty("startMonotonicNanos", "1");
            source = source(dir, jfr, List.of(outside));
            result = OfflineCorrelator.correlate(source, jfr, defaults);
            check(result.invalidSource() == 1 && result.invalidJfr() == 1, "Source interval outside capture joined");
            Path duplicateJfr = recording(dir, 2);
            source = source(dir, duplicateJfr, List.of(observation));
            result = OfflineCorrelator.correlate(source, duplicateJfr, defaults);
            check(result.invalidSource() == 1 && result.invalidJfr() == 2, "Duplicate JFR sample joined");
            check(
                    result.populationEstimate().status().equals("available")
                            && result.populationEstimate()
                                    .sourceSelectedObservedDurationNanos()
                                    .equals("3000")
                            && result.populationEstimate()
                                    .matchedSelectedObservedDurationNanos()
                                    .equals("0"),
                    "AP-only duplicate erased independently valid source duration");
            observation.addProperty("targetTid", tid[0] + 1);
            source = source(dir, jfr, List.of(observation));
            result = OfflineCorrelator.correlate(source, jfr, defaults);
            check(result.invalidSource() == 1 && result.invalidJfr() == 1, "Thread mismatch joined");
            observation.add("targetTid", JsonNull.INSTANCE);
            source = source(dir, jfr, List.of(observation));
            rejects(source, jfr, defaults, "Missing target namespace TID");
            observation.addProperty("targetTid", tid[0]);
            observation.addProperty("targetTgid", ProcessHandle.current().pid() + 1);
            source = source(dir, jfr, List.of(observation));
            result = OfflineCorrelator.correlate(source, jfr, defaults);
            check(result.invalidSource() == 1 && result.invalidJfr() == 1, "Wrong namespace TGID joined");
            observation.addProperty("targetTgid", ProcessHandle.current().pid());
            source = source(dir, jfr, List.of(observation));
            mutateSource(source, 0, row -> row.addProperty("pidNamespaceInode", "1"));
            rejects(source, jfr, defaults, "Verified namespace mismatch");
            source = source(dir, jfr, List.of(observation));
            mutateSource(
                    source,
                    2,
                    row -> row.getAsJsonObject("counters")
                            .getAsJsonObject("kernel")
                            .addProperty("targetNamespaceFailures", "1"));
            rejects(source, jfr, defaults, "Source target namespace mapping failed");
            source = source(dir, jfr, List.of(observation));
            mutateSource(
                    source,
                    2,
                    row -> row.getAsJsonObject("counters")
                            .getAsJsonObject("kernel")
                            .remove("targetNamespaceFailures"));
            rejects(source, jfr, defaults, "targetNamespaceFailures");
            source = source(dir, jfr, List.of(observation));
            String valid = Files.readString(source);
            Files.writeString(source, valid.replace("stopped-at=9000", "stopped-at=3000"));
            result = OfflineCorrelator.correlate(source, jfr, defaults);
            check(
                    result.matched() == 0 && result.invalidSource() == 1 && result.invalidJfr() == 1,
                    "Source interval after AP stop still joined");
            Files.writeString(source, valid.replace("\"hostTid\":456", "\"hostTid\":457"));
            rejects(source, jfr, defaults, "Source digest mismatch");
            Files.writeString(source, valid.substring(0, valid.length() - 1));
            rejects(source, jfr, defaults, "missing newline");
            Files.writeString(source, valid + "{}\n");
            rejects(source, jfr, defaults, "Rows follow");
            Files.writeString(
                    source, valid.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
            rejects(source, jfr, defaults, "Duplicate JSON field");
            Files.writeString(source, valid);
            rejects(
                    source,
                    jfr,
                    new OfflineCorrelator.Limits(1, 1024 * 1024, 1024 * 1024, 4096, null, null, null),
                    "row limit");
            rejects(
                    source,
                    jfr,
                    new OfflineCorrelator.Limits(100, 10, 1024 * 1024, 4096, null, null, null),
                    "line byte limit");
            rejects(source, jfr, new OfflineCorrelator.Limits(100, 1024 * 1024, 256, 4096, null, null, null), "budget");
            byte[] original = Files.readAllBytes(jfr);
            original[original.length - 1] ^= 1;
            Files.write(jfr, original);
            rejects(source, jfr, defaults, "JFR digest mismatch");
            System.out.println("OfflineCorrelator fixtures passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
