// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jonoffcpu-streaming-test-");
        try {
            goldenEquivalence(dir);
            System.out.println("Streaming correlator fixtures passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
