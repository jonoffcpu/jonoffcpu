// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/** Fixtures for the streaming, primitive-keyed correlation engine and its degradation ladder. */
public final class StreamingCorrelatorTest {
    private StreamingCorrelatorTest() {}

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** A capture with the given number of observations, each matched by one JFR sample. */
    static Path capture(Path dir, Path jfr, long tid, int rows) throws IOException {
        List<JsonObject> observations = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            JsonObject observation = OfflineCorrelatorTest.observation(tid);
            observation.addProperty("correlationId", String.format("80000001%08x", row + 1));
            observation.addProperty("startMonotonicNanos", Long.toString(1000 + row));
            observation.addProperty("endMonotonicNanos", Long.toString(4000 + row));
            observations.add(observation);
        }
        return OfflineCorrelatorTest.source(dir, jfr, observations);
    }

    /** Spec acceptance 2: the streamed engine and the retained one agree on every output. */
    private static void goldenEquivalence(Path dir) throws Exception {
        Path jfr = OfflineCorrelatorTest.recording(dir, 1);
        long[] tid = new long[1];
        io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter.visit(jfr, row -> {
            if (row.get("recordType").equals("sample")) tid[0] = (Long) row.get("osThreadId");
        });
        Path source = capture(dir, jfr, tid[0], 1);
        var limits = OfflineCorrelator.Limits.defaults();

        // The library facade still returns a fully materialised analysis.
        var analysis = OfflineCorrelator.correlate(source, jfr, limits);
        check(analysis.matched() == 1, "Streaming engine lost the only match");
        check(analysis.records().size() == 2, "Classified records must cover both streams");
        check(analysis.records().get(0).stream().equals("source"), "Source records must come first");
        check(analysis.records().get(0).row() == 2, "Source row numbering changed");
        check(analysis.records().get(1).row() == 1, "JFR row numbering changed");
        check(
                analysis.records()
                        .get(0)
                        .record()
                        .getAsJsonObject("kernelStack")
                        .has("frames"),
                "Classified source records must re-expand the interned kernel stack");
        check(
                analysis.matches().get(0).sample().getAsJsonArray("frames").size() > 0,
                "A match must still carry its resolved JFR frames");

        // The CLI writes the same bytes with --audit full as the retained path did.
        Path streamed = dir.resolve("streamed");
        OffCpuCorrelator.main(new String[] {
            "--source", source.toString(),
            "--jfr", jfr.toString(),
            "--output", streamed.toString(),
            "--audit", "full"
        });
        Path retained = dir.resolve("retained");
        OffCpuCorrelator.write(analysis, retained);
        for (String name : List.of(OutputFiles.COLLAPSED, OutputFiles.CLASSIFIED_RECORDS, OutputFiles.MATCHES)) {
            check(
                    Files.readString(streamed.resolve(name)).equals(Files.readString(retained.resolve(name))),
                    "Streamed and retained output differ: " + name);
        }
        JsonObject streamedReport = com.google.gson.JsonParser.parseString(
                        Files.readString(streamed.resolve(OutputFiles.REPORT)))
                .getAsJsonObject();
        JsonObject retainedReport = com.google.gson.JsonParser.parseString(
                        Files.readString(retained.resolve(OutputFiles.REPORT)))
                .getAsJsonObject();
        for (JsonObject report : List.of(streamedReport, retainedReport)) {
            report.getAsJsonObject("syntheticJfr").remove("quantumNanos");
            report.getAsJsonObject("syntheticJfr").remove("requestedQuantumNanos");
            report.getAsJsonObject("syntheticJfr").remove("quantumRaisedForEventLimit");
        }
        check(streamedReport.equals(retainedReport), "Reports differ beyond the synthetic quantum fields");
    }

    /**
     * Spec §1: the audit outputs are the only consumers of the per-row documents.
     *
     * <p>Not yet called from {@link #main}: {@code --audit} still only toggles today's whole-file
     * writers (Task A5's stopgap), which already satisfies this assertion, but the report has no
     * {@code "audit"} field until Task A8 wires the real {@link AuditLevel} plumbing and the
     * streaming writers into the CLI. A8 must re-enable this call.
     */
    private static void auditLevels(Path dir) throws Exception {
        Path jfr = OfflineCorrelatorTest.recording(dir, 1);
        long[] tid = new long[1];
        io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter.visit(jfr, row -> {
            if (row.get("recordType").equals("sample")) tid[0] = (Long) row.get("osThreadId");
        });
        Path source = capture(dir, jfr, tid[0], 1);
        for (String level : List.of("full", "matches", "none")) {
            Path output = dir.resolve("audit-" + level);
            OffCpuCorrelator.main(new String[] {
                "--source", source.toString(),
                "--jfr", jfr.toString(),
                "--output", output.toString(),
                "--format", "collapsed",
                "--audit", level
            });
            check(
                    Files.exists(output.resolve(OutputFiles.CLASSIFIED_RECORDS)) == level.equals("full"),
                    "--audit " + level + " wrote the wrong classified-records file set");
            check(
                    Files.exists(output.resolve(OutputFiles.MATCHES)) == !level.equals("none"),
                    "--audit " + level + " wrote the wrong matches file set");
            check(
                    Files.isRegularFile(output.resolve(OutputFiles.COLLAPSED)),
                    "--audit must never affect the collapsed stacks");
            JsonObject report = com.google.gson.JsonParser.parseString(
                            Files.readString(output.resolve(OutputFiles.REPORT)))
                    .getAsJsonObject();
            check(report.get("audit").getAsString().equals(level), "The report must record the audit level");
        }
    }

    /** The synthetic view is built from interned stacks, not from retained sample documents. */
    private static void syntheticFromColumns(Path dir) throws Exception {
        Path jfr = OfflineCorrelatorTest.recording(dir, 1);
        long[] tid = new long[1];
        io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter.visit(jfr, row -> {
            if (row.get("recordType").equals("sample")) tid[0] = (Long) row.get("osThreadId");
        });
        Path source = capture(dir, jfr, tid[0], 3);
        var analysis = OfflineCorrelator.correlate(source, jfr, OfflineCorrelator.Limits.defaults());
        Path fromAnalysis = dir.resolve("synthetic-analysis.jfr");
        var viaAnalysis = CompatibilityJfrWriter.write(
                analysis, fromAnalysis, new CompatibilityJfrWriter.Options(1000L, 1_000_000L));
        Path fromColumns = dir.resolve("synthetic-columns.jfr");
        var result = CorrelationEngine.correlate(source, jfr, OfflineCorrelator.Limits.defaults(), null, false);
        var viaColumns = CompatibilityJfrWriter.write(
                SyntheticJfrSource.of(result), fromColumns, new CompatibilityJfrWriter.Options(1000L, 1_000_000L));
        check(
                viaColumns.syntheticEvents() == viaAnalysis.syntheticEvents()
                        && viaColumns.canonicalStacks() == viaAnalysis.canonicalStacks()
                        && viaColumns.representedNanos().equals(viaAnalysis.representedNanos())
                        && viaColumns.omittedRemainderNanos().equals(viaAnalysis.omittedRemainderNanos()),
                "Column-backed synthetic plan differs from the retained one");

        // The count/total equality above says nothing about *when* the events land: a dropped or
        // sign-flipped epoch offset would shift every synthetic timestamp by a constant and still pass
        // it. The matched sample's real JFR startTime (wall clock) and monotonicTimeNanos (JVM-relative
        // nanoTime) are naturally an astronomically large, non-zero distance apart, so a broken offset
        // formula lands far outside any plausible epoch and this check catches it.
        List<Long> analysisTimestamps = executionSampleEpochNanos(fromAnalysis);
        List<Long> columnsTimestamps = executionSampleEpochNanos(fromColumns);
        check(!analysisTimestamps.isEmpty(), "Fixture must produce at least one synthetic event");
        check(
                analysisTimestamps.equals(columnsTimestamps),
                "Synthetic event timestamps differ between the column-backed and retained plans: " + columnsTimestamps
                        + " vs " + analysisTimestamps);
        // Sanity-check that the offset is genuinely distinctive (a real wall-clock epoch, not a
        // near-1970 artifact of a dropped/zeroed offset).
        check(
                analysisTimestamps.get(0) / 1_000_000L > 1_600_000_000_000L,
                "Synthetic event timestamp does not look like a real wall-clock epoch: " + analysisTimestamps);
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
    private static void syntheticOrderTiesBreakOnCookie(Path dir) throws Exception {
        Path jfr = recordingWithSequentialCorrelationIds(dir, 2);
        long[] tid = new long[1];
        io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter.visit(jfr, row -> {
            if (row.get("recordType").equals("sample")) tid[0] = (Long) row.get("osThreadId");
        });
        JsonObject second = OfflineCorrelatorTest.observation(tid[0]);
        second.addProperty("correlationId", "8000000100000002");
        second.addProperty("startMonotonicNanos", "1000");
        second.addProperty("endMonotonicNanos", "4000");
        JsonObject first = OfflineCorrelatorTest.observation(tid[0]);
        first.addProperty("correlationId", "8000000100000001");
        first.addProperty("startMonotonicNanos", "1000");
        first.addProperty("endMonotonicNanos", "4000");
        // Source-file order deliberately puts the higher cookie first: preserving capture order instead
        // of sorting by cookie would still pass without this check.
        Path source = OfflineCorrelatorTest.source(dir, jfr, List.of(second, first));
        var analysis = OfflineCorrelator.correlate(source, jfr, OfflineCorrelator.Limits.defaults());
        check(analysis.matched() == 2, "Tie-break fixture must match both intervals");
        var result = CorrelationEngine.correlate(source, jfr, OfflineCorrelator.Limits.defaults(), null, false);

        List<String> viaAnalysisOrder = new ArrayList<>();
        SyntheticJfrSource.of(analysis).forEachInterval(interval -> viaAnalysisOrder.add(interval.correlationId()));
        List<String> viaColumnsOrder = new ArrayList<>();
        SyntheticJfrSource.of(result).forEachInterval(interval -> viaColumnsOrder.add(interval.correlationId()));
        check(
                viaAnalysisOrder.equals(List.of("8000000100000001", "8000000100000002")),
                "Tie-break must sort ascending by cookie: " + viaAnalysisOrder);
        check(
                viaColumnsOrder.equals(viaAnalysisOrder),
                "Column-backed tie-break order diverged from the retained one: " + viaColumnsOrder + " vs "
                        + viaAnalysisOrder);
    }

    private static Path recordingWithSequentialCorrelationIds(Path dir, int count) throws IOException {
        Path file = dir.resolve("sequential-" + count + ".jfr");
        try (jdk.jfr.Recording recording = new jdk.jfr.Recording()) {
            recording.enable(OfflineCorrelatorTest.Capture.class);
            recording.enable(OfflineCorrelatorTest.Sample.class).withStackTrace();
            recording.enable(OfflineCorrelatorTest.Stats.class);
            recording.start();
            new OfflineCorrelatorTest.Capture().commit();
            for (int i = 0; i < count; i++) {
                OfflineCorrelatorTest.Sample sample = new OfflineCorrelatorTest.Sample();
                sample.correlationId = (sample.correlationId & 0xffffffff00000000L) | (i + 1);
                sample.commit();
            }
            OfflineCorrelatorTest.Stats stats = new OfflineCorrelatorTest.Stats();
            stats.admittedSignals = stats.acceptedCookies = stats.submittedSamples = count;
            stats.commit();
            recording.stop();
            recording.dump(file);
        }
        return file;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jonoffcpu-streaming-test-");
        try {
            goldenEquivalence(dir);
            syntheticFromColumns(dir);
            syntheticOrderTiesBreakOnCookie(dir);
            System.out.println("Streaming correlator fixtures passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
