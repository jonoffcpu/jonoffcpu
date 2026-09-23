// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jdk.jfr.Recording;
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
        // Only the streamed path has the columns a stack profile is built from.
        check(streamedReport.remove("stackProfile") != null, "The streamed report must describe its stack profile");
        // The digest is made from the stack profile, so only the streamed run has one.
        check(streamedReport.remove("digest") != null, "The streamed report must name its digest");
        check(
                Files.isRegularFile(streamed.resolve(OutputFiles.PROFILE)),
                "The streamed run must write a stack profile");
        for (JsonObject report : List.of(streamedReport, retainedReport)) {
            report.getAsJsonObject("syntheticJfr").remove("quantumNanos");
            report.getAsJsonObject("syntheticJfr").remove("requestedQuantumNanos");
            report.getAsJsonObject("syntheticJfr").remove("observedQuantumNanos");
            report.getAsJsonObject("syntheticJfr").remove("quantumRaisedForEventLimit");
            // The streamed path builds a real ladder from the CLI's own limits and pre-decode estimate
            // (one settings() call even when nothing was needed); the retained/library path's
            // OutputOptions.defaults() ladder is inert (Degradation.none()), with no limit or estimate
            // of its own and no measured peak. Both report an empty stepsApplied either way, which is
            // what this equivalence check cares about.
            JsonObject degradation = report.getAsJsonObject("degradation");
            degradation.remove("attempts");
            degradation.remove("peakRetainedBytes");
            degradation.remove("retainedBytesLimit");
            degradation.remove("estimatedRetainedBytes");
        }
        check(
                streamedReport.equals(retainedReport),
                "Reports differ beyond the synthetic quantum and degradation-measurement fields");
    }

    /** Spec §1: the audit outputs are the only consumers of the per-row documents. */
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

    /**
     * The audit re-read walks the rows correlation kept, not every row in the files.
     *
     * <p>Thinning and window narrowing make the columns hold only the kept rows, so an audit pass that
     * still assumed one column slot per file row indexed past the end of the columns and died with a
     * raw {@code ArrayIndexOutOfBoundsException} — on exactly the headline case this work exists for,
     * a full audit of a large capture under a tight budget. Both degradation mechanisms are covered:
     * an explicit {@code --thinning}, and the watermark-driven narrowing a {@code truncate} run takes.
     */
    private static void auditUnderDegradation(Path dir) throws Exception {
        int rows = 200_000;
        Path jfr = ScaleFixture.recording(dir, rows, 8);
        Path source = ScaleFixture.capture(dir, jfr, rows);

        Path thinned = dir.resolve("audit-thinned");
        int status = OffCpuCorrelator.run(new String[] {
            "--source",
            source.toString(),
            "--jfr",
            jfr.toString(),
            "--output",
            thinned.toString(),
            "--format",
            "collapsed",
            "--audit",
            "full",
            "--thinning",
            "0.5"
        });
        check(status == 0, "A full audit of a thinned run must succeed, but exited " + status);
        checkAuditMatchesColumns(thinned, OutputFiles.REPORT, OutputFiles.CLASSIFIED_RECORDS, OutputFiles.MATCHES);

        // The same budget and policy ladder() proves narrows: under truncate the ladder skips the
        // audit-drop and thin-source rungs, so --audit full survives all the way into a narrowed run.
        Path narrowed = dir.resolve("audit-narrowed");
        status = OffCpuCorrelator.run(new String[] {
            "--source",
            source.toString(),
            "--jfr",
            jfr.toString(),
            "--output",
            narrowed.toString(),
            "--format",
            "collapsed",
            "--audit",
            "full",
            "--on-limit",
            "truncate",
            "--max-retained-bytes",
            "26500000"
        });
        check(status == 2, "A narrowed full audit must still report an incomplete window, got " + status);
        JsonObject narrowedReport = com.google.gson.JsonParser.parseString(
                        Files.readString(narrowed.resolve(OutputFiles.INCOMPLETE_REPORT)))
                .getAsJsonObject();
        check(
                !narrowedReport
                        .getAsJsonObject("degradation")
                        .get("narrowedToNanos")
                        .isJsonNull(),
                "The truncate sub-case must actually narrow, or it proves nothing about the audit pass");
        check(
                narrowedReport.get("audit").getAsString().equals("full"),
                "Truncate must not quietly drop the requested audit level: " + narrowedReport.get("audit"));
        check(
                narrowedReport.get("sourceRows").getAsInt() < rows,
                "A narrowed run must have dropped source rows, or the audit mapping is untested");
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
        JsonObject report = com.google.gson.JsonParser.parseString(Files.readString(output.resolve(reportName)))
                .getAsJsonObject();
        int sourceRows = report.get("sourceRows").getAsInt();
        int jfrSamples = report.get("jfrSamples").getAsInt();
        int matched = report.get("matched").getAsInt();
        int unmatchedSource = report.get("unmatchedSource").getAsInt();
        int invalidSource = report.get("invalidSource").getAsInt();
        check(sourceRows > 0 && jfrSamples > 0 && matched > 0, "Nothing was kept, so nothing is being checked");

        int sourceLines = 0;
        int jfrLines = 0;
        int matchedSourceLines = 0;
        java.util.Set<String> auditMatchedCookies = new java.util.HashSet<>();
        java.util.Set<String> auditSourceCookies = new java.util.HashSet<>();
        for (String line : Files.readAllLines(output.resolve(recordsName))) {
            JsonObject entry = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            String cookie = entry.getAsJsonObject("record").get("correlationId").getAsString();
            if (entry.get("stream").getAsString().equals("source")) {
                sourceLines++;
                check(auditSourceCookies.add(cookie), "The audit pass emitted the same source row twice: " + cookie);
                if (entry.get("classification").getAsString().equals("matched")) {
                    matchedSourceLines++;
                    auditMatchedCookies.add(cookie);
                }
            } else {
                jfrLines++;
            }
        }
        check(sourceLines == sourceRows, "Audit source lines " + sourceLines + " != kept source rows " + sourceRows);
        check(jfrLines == jfrSamples, "Audit JFR lines " + jfrLines + " != kept JFR samples " + jfrSamples);
        check(
                sourceLines == matched + unmatchedSource + invalidSource,
                "Audit source lines do not add up to the report's own source classification counts");
        check(
                matchedSourceLines == matched,
                "Audit matched source lines " + matchedSourceLines + " != report matched " + matched);

        java.util.Set<String> matchesCookies = new java.util.HashSet<>();
        for (String line : Files.readAllLines(output.resolve(matchesName))) {
            matchesCookies.add(com.google.gson.JsonParser.parseString(line)
                    .getAsJsonObject()
                    .get("correlationId")
                    .getAsString());
        }
        check(
                auditMatchedCookies.equals(matchesCookies),
                "The audit pass classified a different set of cookies as matched than the matches file names");
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

    /** Spec acceptance 4: retention tracks distinct stacks, not recorded intervals. */
    private static void scale(Path dir) throws Exception {
        int rows = Integer.getInteger("jonoffcpu.scaleRows", 2_000_000);
        Path jfr = ScaleFixture.recording(dir, rows, 2000);
        Path source = ScaleFixture.capture(dir, jfr, rows);
        var limits =
                new OfflineCorrelator.Limits(rows * 2 + 16, 1024 * 1024, 400L * 1024 * 1024, 4096, null, null, null);
        var result = CorrelationEngine.correlate(source, jfr, limits, null, false);
        check(result.matched() == rows, "Scale fixture lost matches: " + result.matched());
        // The uniform round robin asks for 2000 distinct depths, but the JVM's default JFR
        // stack-capture depth (FlightRecorderOptions=stackdepth, 64 by default) truncates any deeper
        // recursion to the same leaf-first prefix, so depths past that cap collapse together; the
        // fixture has never actually reached 2000 distinct stacks. What matters here is a floor that
        // catches a real fan-out regression (a prior fixture bug collapsed this fixture to ~21
        // distinct stacks) without pinning an exact count that depends on JIT/inlining behavior this
        // fixture does not control.
        int minimumDistinctStacks = Math.min(100, rows);
        check(
                result.dictionaries().stackCount() >= minimumDistinctStacks,
                "Scale fixture produced " + result.dictionaries().stackCount() + " distinct stacks, expected at least "
                        + minimumDistinctStacks + "; the fixture's fan-out coverage regressed");
        check(
                result.dictionaries().stackCount() <= 4000,
                "Scale fixture produced more distinct stacks than intended: "
                        + result.dictionaries().stackCount());
        // 2 M observations plus 2 M samples at the documented 38 bytes a row, the two cookie indices,
        // and a few thousand interned stacks: well under a quarter of the budget above.
        check(
                result.peakRetainedBytes() < 400L * 1024 * 1024,
                "Peak retention exceeded the bound: " + result.peakRetainedBytes());
        check(result.peakRetainedBytes() > 0, "Peak retention was not measured");
        System.out.println("Scale fixture peak retained bytes: " + result.peakRetainedBytes());
    }

    /** Spec acceptance 7: thinning is usable only if the towers keep their proportions. */
    private static void thinningAccuracy(Path dir) throws Exception {
        int rows = 200_000;
        Path jfr = ScaleFixture.recordingSkewed(dir, rows, 8);
        Path source = ScaleFixture.capture(dir, jfr, rows);
        var limits = new OfflineCorrelator.Limits(rows * 2 + 16, 1024 * 1024, 400L << 20, 4096, null, null, null);
        var exact = CorrelationEngine.correlate(source, jfr, limits, null, false, Thinning.NONE);
        var thinned = CorrelationEngine.correlate(source, jfr, limits, null, false, Thinning.of("0.1", 0));
        List<String> exactTop = topStacks(exact, 5);
        List<String> thinnedTop = topStacks(thinned, 5);
        check(exactTop.equals(thinnedTop), "Thinning reordered the top stacks: " + thinnedTop + " vs " + exactTop);
        for (String key : exactTop) {
            java.math.BigInteger full = weight(exact, key);
            java.math.BigInteger estimate = weight(thinned, key);
            java.math.BigInteger difference = estimate.subtract(full).abs().multiply(java.math.BigInteger.valueOf(100));
            check(
                    difference.compareTo(full.multiply(java.math.BigInteger.TEN)) <= 0,
                    "Reweighted total for " + key + " is more than 10% from the exact one: " + estimate + " vs "
                            + full);
        }
    }

    /** Spec acceptance 8: the same capture, the same q, byte-identical output, order-independent. */
    private static void thinningDeterminism(Path dir) throws Exception {
        int rows = 20_000;
        Path jfr = ScaleFixture.recording(dir, rows, 8);
        Path source = ScaleFixture.capture(dir, jfr, rows);
        Path first = dir.resolve("thinned-1");
        Path second = dir.resolve("thinned-2");
        for (Path output : List.of(first, second)) {
            OffCpuCorrelator.main(new String[] {
                "--source",
                source.toString(),
                "--jfr",
                jfr.toString(),
                "--output",
                output.toString(),
                "--format",
                "collapsed",
                "--audit",
                "none",
                "--thinning",
                "0.1"
            });
        }
        check(
                Files.readString(first.resolve(OutputFiles.COLLAPSED))
                        .equals(Files.readString(second.resolve(OutputFiles.COLLAPSED))),
                "The same capture and q produced different collapsed stacks");
        Path shuffled = ScaleFixture.shuffledCapture(dir, jfr, rows, 20260922L);
        Path third = dir.resolve("thinned-3");
        OffCpuCorrelator.main(new String[] {
            "--source",
            shuffled.toString(),
            "--jfr",
            jfr.toString(),
            "--output",
            third.toString(),
            "--format",
            "collapsed",
            "--audit",
            "none",
            "--thinning",
            "0.1"
        });
        check(
                Files.readString(first.resolve(OutputFiles.COLLAPSED))
                        .equals(Files.readString(third.resolve(OutputFiles.COLLAPSED))),
                "Shuffling the capture changed the thinned result");
    }

    /** Spec acceptance 6: a small budget still produces a flame graph, and says what it cost. */
    private static void ladder(Path dir) throws Exception {
        int rows = 200_000;
        Path jfr = ScaleFixture.recording(dir, rows, 8);
        Path source = ScaleFixture.capture(dir, jfr, rows);

        // Measured against this fixture: the cookie indices and the source/sample columns are all sized
        // off the input files up front, so they dominate retention and barely shrink under thinning.
        // At the heaviest thinning rung (q=0.01, which this budget's headroom always selects) peak
        // retention floors around 25 MB (21.5 MB before a source slot grew from 38 to 51 bytes for the
        // stack profile). An unthinned, unnarrowed run exceeds 26,500,000 bytes only
        // partway through the JFR pass; narrowing to the cut the watermark hands back there (the
        // triggering sample's own source row) converges, through a few further watermark-driven cuts, to
        // a window whose full source-and-sample retention fits the same budget.
        long budgetBytes = 26_500_000L;
        Path thinnedOnly = dir.resolve("ladder-thinned");
        int status = OffCpuCorrelator.run(new String[] {
            "--source",
            source.toString(),
            "--jfr",
            jfr.toString(),
            "--output",
            thinnedOnly.toString(),
            "--format",
            "collapsed",
            "--max-retained-bytes",
            Long.toString(budgetBytes)
        });
        check(status == 0, "Thinning alone must still be a complete analysis, but exited " + status);
        check(
                Files.isRegularFile(thinnedOnly.resolve(OutputFiles.COMPLETE)),
                "Thinning keeps the ordinary names and the completion marker");
        check(
                Files.size(thinnedOnly.resolve(OutputFiles.COLLAPSED)) > 0,
                "A degraded run must still produce a flame graph");
        JsonObject report = com.google.gson.JsonParser.parseString(
                        Files.readString(thinnedOnly.resolve(OutputFiles.REPORT)))
                .getAsJsonObject();
        List<String> steps = new ArrayList<>();
        report.getAsJsonObject("degradation")
                .getAsJsonArray("stepsApplied")
                .forEach(step -> steps.add(step.getAsJsonObject().get("step").getAsString()));
        check(steps.contains("thin-source"), "The report must name every ladder step applied: " + steps);
        check(report.has("sourceThinning"), "A thinned analysis must state its estimator");
        check(
                report.getAsJsonObject("degradation").get("narrowedToNanos").isJsonNull(),
                "A thinned-only run must not report a narrowed window");

        // The case above never leaves Degradation's constructor: the pre-decode estimate already picks
        // a rung that fits, so the retry loop's own thin-source rung (advance(), triggered by a
        // watermark exceeded mid-decode) is never exercised. Force that path by requesting a thinning
        // ratio explicit enough to bypass the constructor's proactive pick (Degradation only picks for
        // itself when the caller did not) but loose enough to still exceed the same budget once decoded.
        Path reactiveThinned = dir.resolve("ladder-reactive-thinned");
        status = OffCpuCorrelator.run(new String[] {
            "--source",
            source.toString(),
            "--jfr",
            jfr.toString(),
            "--output",
            reactiveThinned.toString(),
            "--format",
            "collapsed",
            "--thinning",
            "0.9",
            "--max-retained-bytes",
            Long.toString(budgetBytes)
        });
        check(
                status == 0,
                "A watermark-driven thin-source retry must still be a complete analysis, but exited " + status);
        JsonObject reactiveReport = com.google.gson.JsonParser.parseString(
                        Files.readString(reactiveThinned.resolve(OutputFiles.REPORT)))
                .getAsJsonObject();
        JsonObject reactiveDegradation = reactiveReport.getAsJsonObject("degradation");
        check(
                reactiveDegradation.get("attempts").getAsInt() > 1,
                "A too-loose --thinning must force a watermark-driven retry, not just the constructor's"
                        + " pre-decode pick: " + reactiveDegradation);
        List<String> reactiveThinSourceReasons = new ArrayList<>();
        reactiveDegradation.getAsJsonArray("stepsApplied").forEach(step -> {
            JsonObject entry = step.getAsJsonObject();
            if (entry.get("step").getAsString().equals("thin-source")) {
                reactiveThinSourceReasons.add(entry.get("reason").getAsString());
            }
        });
        check(
                reactiveThinSourceReasons.contains("retained bytes reached the budget during the pass"),
                "The watermark-driven rung must record the watermark reason, not the pre-decode one: "
                        + reactiveDegradation);

        Path narrowed = dir.resolve("ladder-narrowed");
        status = OffCpuCorrelator.run(new String[] {
            "--source",
            source.toString(),
            "--jfr",
            jfr.toString(),
            "--output",
            narrowed.toString(),
            "--format",
            "collapsed",
            "--on-limit",
            "truncate",
            "--max-retained-bytes",
            Long.toString(budgetBytes)
        });
        check(status == 2, "A narrowed window is not the question that was asked; expected 2, got " + status);
        check(!Files.exists(narrowed.resolve(OutputFiles.COMPLETE)), "A narrowed window must not look complete");
        check(
                Files.isRegularFile(narrowed.resolve(OutputFiles.INCOMPLETE_COLLAPSED))
                        && Files.isRegularFile(narrowed.resolve(OutputFiles.INCOMPLETE_REPORT))
                        && Files.isRegularFile(narrowed.resolve(OutputFiles.NARROWED)),
                "A narrowed window takes the INCOMPLETE names and its own marker");

        Path refused = dir.resolve("ladder-fail");
        try {
            OffCpuCorrelator.run(new String[] {
                "--source",
                source.toString(),
                "--jfr",
                jfr.toString(),
                "--output",
                refused.toString(),
                "--format",
                "collapsed",
                "--on-limit",
                "fail",
                "--max-retained-bytes",
                Long.toString(budgetBytes)
            });
            throw new AssertionError("--on-limit fail must still refuse");
        } catch (IOException expected) {
            check(expected.getMessage().contains("budget"), "Unexpected refusal: " + expected);
        }
    }

    /** The top {@code limit} collapsed stacks by thinning-reweighted duration, descending, ties broken on key. */
    private static List<String> topStacks(CorrelationResult result, int limit) {
        record Entry(String key, java.math.BigInteger weight) {}
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
        List<String> top = new ArrayList<>();
        for (Entry entry : entries) {
            if (top.size() >= limit) break;
            top.add(entry.key());
        }
        return top;
    }

    /** The thinning-reweighted duration of the given collapsed stack key. */
    private static java.math.BigInteger weight(CorrelationResult result, String key) {
        for (int id = 0; id < result.collapsedNanos().length; id++) {
            if (result.dictionaries().collapsedKey(id).equals(key)) {
                return result.thinning().scale(result.collapsedNanos()[id]);
            }
        }
        throw new AssertionError("Stack not found: " + key);
    }

    /**
     * Builds the scale fixture's JFR and capture stream without ever retaining more than one row:
     * the JFR is produced by {@code jdk.jfr.Recording}'s own native buffering, and the capture stream
     * is framed and digested straight to disk with {@code CaptureProto.Record.writeDelimitedTo}.
     */
    private static final class ScaleFixture {
        private static final long EPOCH = 0x80000001L;

        private ScaleFixture() {}

        /**
         * {@code rows} {@code profiler.SignalSample} events, cycling through {@code distinctStacks}.
         *
         * <p>The exporter requires the single {@code profiler.SignalCapture} context event to be seen
         * before any sample that depends on it. At high commit volume a single recording does not honor
         * that: {@code jdk.jfr.Event.commit()} routes same-thread events through per-size buffer pools
         * that flush independently, so a later {@code dump()} can read the context event — and the
         * small, equally rare terminal stats event — back well after most samples, regardless of commit
         * order or of streaming straight to a destination file. Recording the context event by itself,
         * in its own tiny session, sidesteps that: a JFR file is a sequence of self-contained chunks, so
         * concatenating that session's dump ahead of the sample-flood session's dump yields one valid
         * recording whose first chunk is guaranteed to be the context event, independent of how either
         * session's own buffers happened to flush.
         */
        static Path recording(Path dir, int rows, int distinctStacks) throws IOException {
            return recording(dir, rows, distinctStacks, false);
        }

        /**
         * Like {@link #recording(Path, int, int)}, but with a skewed (power-of-two) distribution
         * across distinct stacks instead of a uniform round robin, so distinct stacks carry
         * distinctly separated weights. The thinning-accuracy fixture needs this: under a uniform
         * round robin every stack is exactly tied in weight, and thinning's ~2-4% per-stack sampling
         * noise would then decide the top-stack ranking essentially at random, which is an unwinnable
         * comparison against the exact run. {@code scale()} must not use this: it exists specifically
         * to exercise the stack dictionary near its real fan-out (spec acceptance 4), which a skewed
         * distribution — where {@code numberOfTrailingZeros(row + 1)} never reaches large values for
         * a two-million-row recording — would silently collapse to a couple dozen distinct stacks.
         */
        static Path recordingSkewed(Path dir, int rows, int distinctStacks) throws IOException {
            return recording(dir, rows, distinctStacks, true);
        }

        private static Path recording(Path dir, int rows, int distinctStacks, boolean skewed) throws IOException {
            Path contextFile = dir.resolve("scale-context-" + rows + ".jfr");
            try (Recording context = new Recording()) {
                context.enable(OfflineCorrelatorTest.Capture.class);
                context.start();
                new OfflineCorrelatorTest.Capture().commit();
                context.stop();
                context.dump(contextFile);
            }
            Path samplesFile = dir.resolve("scale-samples-" + rows + ".jfr");
            try (Recording samples = new Recording()) {
                samples.enable(OfflineCorrelatorTest.Sample.class).withStackTrace();
                samples.enable(OfflineCorrelatorTest.Stats.class);
                samples.start();
                for (int row = 0; row < rows; row++) {
                    OfflineCorrelatorTest.Sample sample = new OfflineCorrelatorTest.Sample();
                    sample.correlationId = (EPOCH << 32) | (row + 1);
                    // The matching observation's endMonotonicNanos grows with row (see capture() below);
                    // the handler delay is monotonicTimeNanos minus that end, so this must grow with it
                    // too, or every row past the point where the fixed default falls behind is classified
                    // invalid-handler-delay instead of matched.
                    sample.monotonicTimeNanos = 5000L + row;
                    // A skewed (power-of-two) distribution gives distinct stacks distinctly separated
                    // weights, needed only by the thinning-accuracy fixture (see recordingSkewed).
                    int depth = skewed
                            ? 1 + (Integer.numberOfTrailingZeros(row + 1) % distinctStacks)
                            : 1 + (row % distinctStacks);
                    commitAtDepth(depth, sample);
                }
                OfflineCorrelatorTest.Stats stats = new OfflineCorrelatorTest.Stats();
                stats.admittedSignals = stats.acceptedCookies = stats.submittedSamples = rows;
                stats.commit();
                samples.stop();
                samples.dump(samplesFile);
            }
            Path file = dir.resolve("scale-" + rows + ".jfr");
            try (var out = new BufferedOutputStream(Files.newOutputStream(file))) {
                try (var in = new BufferedInputStream(Files.newInputStream(contextFile))) {
                    in.transferTo(out);
                }
                try (var in = new BufferedInputStream(Files.newInputStream(samplesFile))) {
                    in.transferTo(out);
                }
            }
            Files.delete(contextFile);
            Files.delete(samplesFile);
            return file;
        }

        /** Recurses to the requested depth before committing, so the captured stack trace varies. */
        private static void commitAtDepth(int depth, OfflineCorrelatorTest.Sample event) {
            if (depth <= 1) {
                event.commit();
            } else {
                commitAtDepth(depth - 1, event);
            }
        }

        /** The one thread every sample above was committed from, plus its terminal stats row. */
        private record JfrSummary(long threadId, JsonObject stats) {}

        private static JfrSummary jfrSummary(Path jfr) throws IOException {
            Gson gson = new GsonBuilder().serializeNulls().create();
            long[] threadId = new long[1];
            JsonObject[] stats = new JsonObject[1];
            SignalJfrExporter.visit(jfr, raw -> {
                if (threadId[0] == 0 && raw.get("recordType").equals("sample")) {
                    threadId[0] = (Long) raw.get("osThreadId");
                }
                if (raw.get("recordType").equals("stats")) {
                    stats[0] = gson.toJsonTree(raw).getAsJsonObject();
                }
            });
            check(threadId[0] != 0, "Scale fixture JFR produced no sample thread id");
            check(stats[0] != null, "Scale fixture JFR produced no terminal stats row");
            return new JfrSummary(threadId[0], stats[0]);
        }

        private static JsonObject captureStart() {
            JsonObject start = OfflineCorrelatorTest.row("captureStart");
            start.addProperty("sourceId", "jonoffcpu.offcpu.v1");
            start.addProperty("signal", 35);
            start.addProperty("signalDelivery", "queued");
            start.addProperty("hostTgid", 123);
            start.addProperty("targetPid", ProcessHandle.current().pid());
            start.add("sampling", OfflineCorrelatorTest.uniformSampling());
            start.addProperty("processGenerationNs", "100");
            start.addProperty("timeNamespaceInode", "42");
            start.addProperty("pidNamespaceDevice", "4");
            start.addProperty("pidNamespaceInode", "43");
            start.addProperty("registrationToken", "0000000000000001");
            start.addProperty("startedMonotonicNanos", "500");
            return start;
        }

        /**
         * The last observation ends at {@code 4000 + rows - 1}: every capture-lifecycle boundary below
         * must clear that, or rows past some cutoff are silently classified outside the capture / after
         * the profiler stopped instead of matching.
         */
        private static long lastObservationEndNanos(int rows) {
            return 4000L + rows;
        }

        private static JsonObject captureEnd(int rows) {
            long stopped = lastObservationEndNanos(rows) + 1000;
            JsonObject end = OfflineCorrelatorTest.row("captureEnd");
            end.addProperty("state", "complete");
            end.addProperty("drainTimedOut", false);
            end.addProperty("startedMonotonicNanos", "500");
            end.addProperty("stoppedMonotonicNanos", Long.toString(stopped));
            end.addProperty("detachedMonotonicNanos", Long.toString(stopped + 1000));
            end.addProperty("drainCompletedMonotonicNanos", Long.toString(stopped + 2000));
            JsonObject userspace = new JsonObject();
            userspace.addProperty("receivedObservations", Integer.toString(rows));
            userspace.addProperty("writtenObservations", Integer.toString(rows));
            userspace.addProperty("writeFailures", "0");
            userspace.addProperty("pollFailures", "0");
            JsonObject counters = new JsonObject();
            counters.add("userspace", userspace);
            JsonObject kernel = new JsonObject();
            kernel.addProperty("targetNamespaceFailures", "0");
            kernel.addProperty("eligibleIntervals", Integer.toString(rows));
            kernel.addProperty("admissionRejections", "0");
            kernel.addProperty("selectedIntervals", Integer.toString(rows));
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
            return end;
        }

        private static JsonObject footer(Path jfr, JfrSummary summary, long rawBytes, String rawSha256, int rows)
                throws IOException {
            JsonObject footer = OfflineCorrelatorTest.row("captureFinalized");
            footer.addProperty("state", "complete");
            JsonObject inputs = captureStart();
            inputs.remove("recordType");
            inputs.addProperty("clockVerified", true);
            inputs.addProperty("monotonicOffsetNanos", "0");
            inputs.add("verifiedIdentity", inputs.deepCopy());
            JsonObject stats = summary.stats().deepCopy();
            for (String key : List.of("schemaVersion", "recordType", "sessionId", "captureEpoch", "startTime")) {
                stats.remove(key);
            }
            inputs.add("apStats", stats);
            JsonObject sourceArtifact = new JsonObject();
            sourceArtifact.addProperty("path", "an-unrelated-old-location");
            sourceArtifact.addProperty("rawBytes", Long.toString(rawBytes));
            sourceArtifact.addProperty("rawSha256", rawSha256);
            inputs.add("sourceArtifact", sourceArtifact);
            JsonObject jfrArtifact = new JsonObject();
            jfrArtifact.addProperty("path", "an-unrelated-old-jfr-location");
            jfrArtifact.addProperty("bytes", Long.toString(Files.size(jfr)));
            jfrArtifact.addProperty("sha256", CaptureInput.digest(jfr));
            inputs.add("jfrArtifact", jfrArtifact);
            String sessionId = inputs.get("sessionId").getAsString();
            long epoch = inputs.get("captureEpoch").getAsLong();
            footer.add("analysisInputs", inputs);
            footer.addProperty(
                    "apStopResponse",
                    "signal-capture-v1 stopped id="
                            + sessionId
                            + " delivery=queued signal=35 epoch="
                            + epoch
                            + " finalized=true stopped-at="
                            + (lastObservationEndNanos(rows) + 4000)
                            + " reason=explicit"
                            + " admitted="
                            + rows
                            + " invalid-code=0 zero-cookie=0 zero-sequence=0 stale-epoch=0"
                            + " accepted="
                            + rows
                            + " capture-failures=0 submitted="
                            + rows
                            + "\n");
            return footer;
        }

        /**
         * Writes the header, the two announced stacks, and {@code rows} observations straight to disk,
         * digesting the bytes as they are written so the footer never needs the body back in memory.
         */
        static Path capture(Path dir, Path jfr, int rows) throws Exception {
            JfrSummary summary = jfrSummary(jfr);
            Path body = dir.resolve("scale-body-" + rows + ".tmp");
            MessageDigest digest = CaptureInput.sha256();
            try (var out = new BufferedOutputStream(new DigestOutputStream(Files.newOutputStream(body), digest))) {
                out.write(CaptureStreamFixture.header());
                CaptureStreamFixture.record(captureStart()).writeDelimitedTo(out);
                CaptureStreamFixture.record(
                                OfflineCorrelatorTest.stack(OfflineCorrelatorTest.KERNEL_STACK_ID, "kernel_wait"))
                        .writeDelimitedTo(out);
                CaptureStreamFixture.record(
                                OfflineCorrelatorTest.stack(OfflineCorrelatorTest.USER_STACK_ID, "user_wait"))
                        .writeDelimitedTo(out);
                for (int row = 0; row < rows; row++) {
                    JsonObject observation = OfflineCorrelatorTest.observation(summary.threadId());
                    observation.addProperty("correlationId", String.format("80000001%08x", row + 1));
                    observation.addProperty("startMonotonicNanos", Long.toString(1000L + row));
                    observation.addProperty("endMonotonicNanos", Long.toString(4000L + row));
                    CaptureStreamFixture.record(observation).writeDelimitedTo(out);
                }
                CaptureStreamFixture.record(captureEnd(rows)).writeDelimitedTo(out);
            }
            long rawBytes = Files.size(body);
            String rawSha256 = CaptureInput.hex(digest.digest());
            JsonObject footer = footer(jfr, summary, rawBytes, rawSha256, rows);
            Path result = dir.resolve("scale-source-" + rows + ".capture");
            try (var out = new BufferedOutputStream(Files.newOutputStream(result));
                    var in = new BufferedInputStream(Files.newInputStream(body))) {
                in.transferTo(out);
                CaptureStreamFixture.record(footer).writeDelimitedTo(out);
            }
            Files.delete(body);
            return result;
        }

        /**
         * The same rows as {@link #capture}, emitted in a seeded permutation, with the footer's
         * {@code rawBytes}/{@code rawSha256} recomputed for the reordered body. The stack records stay
         * ahead of every observation, which the format requires regardless of observation order.
         */
        static Path shuffledCapture(Path dir, Path jfr, int rows, long seed) throws Exception {
            JfrSummary summary = jfrSummary(jfr);
            int[] order = new int[rows];
            for (int row = 0; row < rows; row++) order[row] = row;
            java.util.Random random = new java.util.Random(seed);
            for (int index = rows - 1; index > 0; index--) {
                int swapWith = random.nextInt(index + 1);
                int value = order[index];
                order[index] = order[swapWith];
                order[swapWith] = value;
            }
            Path body = dir.resolve("scale-shuffled-body-" + rows + "-" + seed + ".tmp");
            MessageDigest digest = CaptureInput.sha256();
            try (var out = new BufferedOutputStream(new DigestOutputStream(Files.newOutputStream(body), digest))) {
                out.write(CaptureStreamFixture.header());
                CaptureStreamFixture.record(captureStart()).writeDelimitedTo(out);
                CaptureStreamFixture.record(
                                OfflineCorrelatorTest.stack(OfflineCorrelatorTest.KERNEL_STACK_ID, "kernel_wait"))
                        .writeDelimitedTo(out);
                CaptureStreamFixture.record(
                                OfflineCorrelatorTest.stack(OfflineCorrelatorTest.USER_STACK_ID, "user_wait"))
                        .writeDelimitedTo(out);
                for (int row : order) {
                    JsonObject observation = OfflineCorrelatorTest.observation(summary.threadId());
                    observation.addProperty("correlationId", String.format("80000001%08x", row + 1));
                    observation.addProperty("startMonotonicNanos", Long.toString(1000L + row));
                    observation.addProperty("endMonotonicNanos", Long.toString(4000L + row));
                    CaptureStreamFixture.record(observation).writeDelimitedTo(out);
                }
                CaptureStreamFixture.record(captureEnd(rows)).writeDelimitedTo(out);
            }
            long rawBytes = Files.size(body);
            String rawSha256 = CaptureInput.hex(digest.digest());
            JsonObject footer = footer(jfr, summary, rawBytes, rawSha256, rows);
            Path result = dir.resolve("scale-shuffled-source-" + rows + "-" + seed + ".capture");
            try (var out = new BufferedOutputStream(Files.newOutputStream(result));
                    var in = new BufferedInputStream(Files.newInputStream(body))) {
                in.transferTo(out);
                CaptureStreamFixture.record(footer).writeDelimitedTo(out);
            }
            Files.delete(body);
            return result;
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jonoffcpu-streaming-test-");
        try {
            goldenEquivalence(dir);
            auditLevels(dir);
            syntheticFromColumns(dir);
            syntheticOrderTiesBreakOnCookie(dir);
            scale(dir);
            thinningAccuracy(dir);
            thinningDeterminism(dir);
            ladder(dir);
            // Runs after scale(), not next to auditLevels() where it belongs by subject. scale()'s
            // distinct-stack floor depends on how far the JVM has inlined ScaleFixture's recursive
            // commitAtDepth by the time it runs: another 200,000-event fixture ahead of it warms that
            // recursion enough to collapse the captured stacks below the floor (66 observed against a
            // floor of 100). Every other large fixture is already downstream of scale() for the same
            // reason; this one joins them.
            auditUnderDegradation(dir);
            System.out.println("Streaming correlator fixtures passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
