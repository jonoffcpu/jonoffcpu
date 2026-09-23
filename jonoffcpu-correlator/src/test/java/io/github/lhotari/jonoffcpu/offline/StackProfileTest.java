// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
import io.github.lhotari.jonoffcpu.profile.ProfileProto;
import io.github.lhotari.jonoffcpu.testing.FixtureSteps;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import jdk.jfr.Recording;

/** Fixtures for switch-out reason classification and the stack profile the correlator writes. */
public final class StackProfileTest {
    private static final long EPOCH = 0x80000001L;
    private static final int ROWS = 12;
    private static final int STACKS = 3;
    private static final List<String> ALL_REASONS = List.of("blocked", "runnable", "preempted");

    private StackProfileTest() {}

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /** {@code rows} samples with cookies 1..rows, cycling through {@code stacks} distinct Java stacks. */
    private static Path recording(Path dir) throws IOException {
        Path file = dir.resolve("profile.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(OfflineCorrelatorTest.Capture.class);
            recording.enable(OfflineCorrelatorTest.Sample.class).withStackTrace();
            recording.enable(OfflineCorrelatorTest.Stats.class);
            recording.start();
            new OfflineCorrelatorTest.Capture().commit();
            for (int row = 0; row < ROWS; row++) {
                OfflineCorrelatorTest.Sample sample = new OfflineCorrelatorTest.Sample();
                sample.correlationId = (EPOCH << 32) | (row + 1);
                sample.monotonicTimeNanos = 5000L + 100L * row;
                commitAtDepth(1 + row % STACKS, sample);
            }
            OfflineCorrelatorTest.Stats stats = new OfflineCorrelatorTest.Stats();
            stats.admittedSignals = stats.acceptedCookies = stats.submittedSamples = ROWS;
            stats.commit();
            recording.stop();
            recording.dump(file);
        }
        return file;
    }

    private static void commitAtDepth(int depth, OfflineCorrelatorTest.Sample event) {
        if (depth <= 1) {
            event.commit();
        } else {
            commitAtDepth(depth - 1, event);
        }
    }

    private static long sampleThread(Path jfr) throws IOException {
        long[] tid = new long[1];
        SignalJfrExporter.visit(jfr, row -> {
            if (row.get("recordType").equals("sample")) tid[0] = (Long) row.get("osThreadId");
        });
        return tid[0];
    }

    /** Observations for every sample, each of a distinct duration, classified by {@code reason} when non-null. */
    private static List<JsonObject> observations(long tid, IntFunction<String> reason) {
        List<JsonObject> observations = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            JsonObject observation = OfflineCorrelatorTest.observation(tid);
            observation.addProperty("correlationId", String.format("80000001%08x", row + 1));
            observation.addProperty("startMonotonicNanos", Long.toString(1000 + 100L * row));
            observation.addProperty("endMonotonicNanos", Long.toString(4000 + 100L * row + 7L * row * row));
            String label = reason == null ? null : reason.apply(row);
            if (label != null) {
                observation.addProperty("offCpuReason", label);
                observation.addProperty("preempted", label.equals("preempted"));
                observation.addProperty("prevTaskState", label.equals("blocked") ? 0x2001 : 0);
            }
            observations.add(observation);
        }
        return observations;
    }

    private static JsonObject sampling(List<String> reasons) {
        JsonObject sampling = OfflineCorrelatorTest.uniformSampling();
        JsonArray names = new JsonArray();
        for (String reason : reasons) names.add(reason);
        JsonObject ordered = new JsonObject();
        ordered.add("reasons", names);
        for (var entry : sampling.entrySet()) ordered.add(entry.getKey(), entry.getValue());
        return ordered;
    }

    private static JsonObject reasonCounters() {
        JsonObject counters = new JsonObject();
        counters.addProperty("switchOutsBlocked", "40");
        counters.addProperty("switchOutsRunnable", "5");
        counters.addProperty("switchOutsPreempted", "300");
        counters.addProperty("reasonRejections", "0");
        counters.addProperty("reasonRejectedDurationMicros", "0");
        return counters;
    }

    private static Path v2Capture(Path dir, Path jfr) throws IOException {
        return OfflineCorrelatorTest.source(dir, jfr, observations(sampleThread(jfr), null));
    }

    private static Path v3Capture(Path dir, Path jfr, List<String> reasons, IntFunction<String> reason)
            throws IOException {
        return OfflineCorrelatorTest.source(
                Files.createDirectories(dir),
                jfr,
                observations(sampleThread(jfr), reason),
                sampling(reasons),
                3,
                reasonCounters());
    }

    private static Path correlate(Path source, Path jfr, Path output, String... extra) throws Exception {
        List<String> args = new ArrayList<>(
                List.of("--source", source.toString(), "--jfr", jfr.toString(), "--output", output.toString()));
        args.addAll(Arrays.asList(extra));
        check(OffCpuCorrelator.run(args.toArray(String[]::new)) == 0, "Correlation must complete");
        return output;
    }

    private static String stacks(Path profile, Path output, String... extra) throws Exception {
        List<String> args =
                new ArrayList<>(List.of("stacks", "--profile", profile.toString(), "--output", output.toString()));
        args.addAll(Arrays.asList(extra));
        check(OffCpuCorrelator.run(args.toArray(String[]::new)) == 0, "Rendering must succeed");
        return Files.readString(output);
    }

    private static JsonObject report(Path analysis) throws IOException {
        return JsonParser.parseString(Files.readString(analysis.resolve(OutputFiles.REPORT)))
                .getAsJsonObject();
    }

    private static void rejects(Class<? extends Throwable> type, String message, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable error) {
            check(type.isInstance(error), "Wrong failure type: " + error);
            check(error.getMessage() != null && error.getMessage().contains(message), "Unexpected failure: " + error);
            return;
        }
        throw new AssertionError("Expected failure: " + message);
    }

    interface ThrowingAction {
        void run() throws Exception;
    }

    // ---- fixtures under test -------------------------------------------------------------------

    /**
     * A capture without classification correlates as before, and the profile rendered with its defaults reproduces
     * the collapsed file byte for byte — the property that makes the profile a trustworthy stand-in for it.
     */
    private static void unclassifiedGolden(Path dir) throws Exception {
        Path jfr = recording(dir);
        Path source = v2Capture(dir, jfr);
        Path analysis = correlate(source, jfr, dir.resolve("analysis"));
        String collapsed = Files.readString(analysis.resolve(OutputFiles.COLLAPSED));
        check(!collapsed.contains("[offcpu:"), "An unclassified capture must not gain reason frames");
        check(collapsed.lines().count() == STACKS, "Expected one collapsed line per distinct stack: " + collapsed);
        Path profile = analysis.resolve(OutputFiles.PROFILE);
        check(
                stacks(profile, dir.resolve("default.collapsed")).equals(collapsed),
                "Profile does not reproduce collapsed");
        JsonObject report = report(analysis);
        check(!report.has("offCpuReasons"), "An unclassified capture has no reason accounting");
        check(report.getAsJsonObject("stackProfile").get("entries").getAsInt() == STACKS, "Wrong profile entry count");

        // Reading and writing again gives the same bytes: the file order does not depend on match order.
        StackProfile read = StackProfile.read(profile);
        Path rewritten = dir.resolve("rewritten.pb");
        read.write(rewritten);
        check(Arrays.equals(Files.readAllBytes(profile), Files.readAllBytes(rewritten)), "Profile is not canonical");
        check(
                read.header()
                        .reportJson()
                        .equals(Files.readString(analysis.resolve(OutputFiles.REPORT))
                                .strip()),
                "The profile must carry the run's report");
        check(
                read.entries().stream().allMatch(entry -> entry.reason() == OffCpuReason.UNSPECIFIED),
                "Unclassified intervals must read back as unspecified");
        check(read.entries().stream().allMatch(entry -> "main".equals(entry.thread())), "Thread names are grouped");
        // A JDK recording's frames are all Java code, so every frame of its Java stacks is JAVA.
        check(
                read.entries().stream()
                        .flatMap(entry -> entry.javaStack().stream())
                        .allMatch(frame -> frame.kind() == StackProfile.Kind.JAVA),
                "Frames recorded as Java code must be JAVA");

        // Kernel stacks reach a collapsed line; the offset-free symbol keeps one function one frame.
        String mixed = stacks(profile, dir.resolve("java-kernel.collapsed"), "--stack", "java+kernel");
        check(mixed.lines().allMatch(line -> line.contains(";kernel_wait_[k] ")), "Kernel frames missing: " + mixed);
        String kernelOnly = stacks(profile, dir.resolve("kernel.collapsed"), "--stack", "kernel");
        check(kernelOnly.lines().count() == 1 && kernelOnly.startsWith("kernel_wait_[k] "), kernelOnly);
        // The profiler's own tracing frames at the leaf of a kernel stack are trimmed when rendered.
        List<StackProfile.Frame> traced = List.of(
                new StackProfile.Frame(StackProfile.Kind.KERNEL, "schedule+0x2a", "[kernel]"),
                new StackProfile.Frame(StackProfile.Kind.KERNEL, "__traceiter_sched_exit_tp+0x40", "[kernel]"),
                new StackProfile.Frame(StackProfile.Kind.KERNEL, "bpf_prog_ae49480d68384438_capture+0x1", "[kernel]"));
        check(StackProfileRenderer.kernelEnd(traced) == 1, "Tracing frames must be trimmed");

        // The inverse-probability weights: the uniform threshold is 42949673, so each nanosecond stands for 100.
        Path summaryFile = dir.resolve("estimated.json");
        stacks(
                profile,
                dir.resolve("estimated.collapsed"),
                "--weights",
                "estimated",
                "--summary",
                summaryFile.toString());
        BigInteger estimated = new BigInteger(JsonParser.parseString(Files.readString(summaryFile))
                .getAsJsonObject()
                .get("totalNanos")
                .getAsString());
        long observed = read.totalObservedNanos();
        check(
                estimated.compareTo(BigInteger.valueOf(observed * 99)) > 0
                        && estimated.compareTo(BigInteger.valueOf(observed * 101)) < 0,
                "Estimate out of range: " + estimated + " for " + observed);

        // Dropping every optional dimension merges entries but changes no rendered weight.
        Path narrow = correlate(source, jfr, dir.resolve("narrow"), "--max-profile-entries", "1");
        StackProfile narrowed = StackProfile.read(narrow.resolve(OutputFiles.PROFILE));
        check(
                narrowed.header().dimensionsDropped().equals(List.of("thread", "user", "kernel")),
                "Dimensions must drop in order: " + narrowed.header().dimensionsDropped());
        check(narrowed.header().dimensions().equals(List.of("reason")), "Only the reason must remain");
        check(
                stacks(narrow.resolve(OutputFiles.PROFILE), dir.resolve("narrow.collapsed"))
                        .equals(collapsed),
                "Dropping dimensions changed the collapsed weights");
        rejects(
                IOException.class,
                "not grouped by its kernel",
                () -> stacks(
                        narrow.resolve(OutputFiles.PROFILE),
                        dir.resolve("narrow-kernel.collapsed"),
                        "--stack",
                        "kernel"));

        // Thinning labels and rescales the collapsed file; the profile renders it identically.
        Path thinned = correlate(source, jfr, dir.resolve("thinned"), "--thinning", "0.5");
        check(
                stacks(thinned.resolve(OutputFiles.PROFILE), dir.resolve("thinned.collapsed"))
                        .equals(Files.readString(thinned.resolve(OutputFiles.COLLAPSED))),
                "A thinned profile does not reproduce its collapsed file");
        rejects(
                IOException.class,
                "cannot be merged",
                () -> StackProfile.merge(List.of(StackProfile.read(thinned.resolve(OutputFiles.PROFILE)))));

        // Merging a profile with itself doubles every counter and changes no stack.
        Path merged = dir.resolve("merged.pb");
        check(
                OffCpuCorrelator.run(new String[] {
                            "merge", "--profiles", profile + "," + profile, "--output", merged.toString()
                        })
                        == 0,
                "Merge must succeed");
        StackProfile doubled = StackProfile.read(merged);
        check(doubled.entries().size() == read.entries().size(), "Merge changed the entry set");
        for (int index = 0; index < read.entries().size(); index++) {
            StackProfile.Entry once = read.entries().get(index);
            StackProfile.Entry twice = doubled.entries().get(index);
            check(
                    once.key().equals(twice.key())
                            && twice.intervals() == 2 * once.intervals()
                            && twice.observedNanos() == 2 * once.observedNanos()
                            && twice.estimatedNanos() == 2 * once.estimatedNanos(),
                    "Merge must double every counter");
        }
        check(doubled.header().sources().size() == 2, "A merged profile keeps every input's provenance");

        // The export is one row per entry, for tools such as DuckDB.
        Path csv = dir.resolve("entries.csv");
        check(
                OffCpuCorrelator.run(
                                new String[] {"export", "--profile", profile.toString(), "--output", csv.toString()})
                        == 0,
                "Export must succeed");
        List<String> rows = Files.readAllLines(csv);
        check(rows.get(0).startsWith("reason,task_state,thread,java_stack"), "CSV header changed: " + rows.get(0));
        check(rows.size() == read.entries().size() + 1, "One CSV row per entry");
        check(rows.get(1).startsWith("unspecified,0,main,"), "Unexpected CSV row: " + rows.get(1));
        // A column names each Java-stack frame's kind, parallel to java_stack; the 0.5.0 columns follow it.
        check(
                rows.get(0).endsWith(",java_stack_kinds,canonical_java_stack,thread_pool,run,estimate_available"),
                "CSV header lacks the kinds: " + rows.get(0));
        String firstStack = String.join(
                ";",
                read.entries().get(0).javaStack().stream().map(frame -> "java").toList());
        check(rows.get(1).contains("," + firstStack + ","), "Unexpected CSV kinds: " + rows.get(1));

        // A damaged file is refused rather than half read.
        byte[] bytes = Files.readAllBytes(profile);
        Path truncated = dir.resolve("truncated.pb");
        Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 3));
        rejects(IOException.class, "stack profile", () -> StackProfile.read(truncated));
        Path foreign = dir.resolve("foreign.pb");
        Files.write(foreign, Files.readAllBytes(source));
        rejects(IOException.class, "Not a jonoffcpu stack profile", () -> StackProfile.read(foreign));
    }

    /** A capture that records every reason labels each collapsed line and splits the reasons into files. */
    private static void mixedReasons(Path dir) throws Exception {
        Path jfr = recording(dir);
        Path source = v3Capture(dir, jfr, ALL_REASONS, row -> ALL_REASONS.get(row % 3));
        Path analysis = correlate(source, jfr, dir.resolve("analysis"));
        String collapsed = Files.readString(analysis.resolve(OutputFiles.COLLAPSED));
        check(
                collapsed.lines().allMatch(line -> line.startsWith("[offcpu: ")),
                "Lines need reason frames: " + collapsed);
        Map<String, String> perReason = new HashMap<>();
        StringBuilder prefixed = new StringBuilder();
        for (String reason : ALL_REASONS) {
            Path file =
                    analysis.resolve(OutputFiles.collapsedForReason(OutputFiles.PREFIX, OffCpuReason.parse(reason)));
            String text = Files.readString(file);
            perReason.put(reason, text);
            check(!text.contains("[offcpu:"), "A per-reason file needs no reason frame");
            text.lines()
                    .forEach(line -> prefixed.append("[offcpu: ")
                            .append(reason)
                            .append("];")
                            .append(line)
                            .append('\n'));
        }
        List<String> combined = collapsed.lines().sorted().toList();
        check(
                combined.equals(prefixed.toString().lines().sorted().toList()),
                "Per-reason files must sum to the combined one");
        Path profile = analysis.resolve(OutputFiles.PROFILE);
        check(stacks(profile, dir.resolve("all.collapsed")).equals(collapsed), "Profile does not reproduce combined");
        check(
                stacks(profile, dir.resolve("blocked.collapsed"), "--reason", "blocked")
                        .equals(perReason.get("blocked")),
                "Profile does not reproduce the blocked slice");
        check(
                stacks(profile, dir.resolve("never.collapsed"), "--reason-frame", "never")
                        .lines()
                        .noneMatch(line -> line.startsWith("[offcpu:")),
                "--reason-frame never must drop the frames");
        String waitingForCpu = stacks(profile, dir.resolve("cpu.collapsed"), "--reason", "runnable,preempted");
        check(
                waitingForCpu
                        .lines()
                        .allMatch(line ->
                                line.startsWith("[offcpu: runnable]") || line.startsWith("[offcpu: preempted]")),
                "A two-reason slice keeps its frames");

        JsonObject reasons = report(analysis).getAsJsonObject("offCpuReasons");
        check(reasons.getAsJsonArray("selected").size() == 3, "Selected reasons missing: " + reasons);
        for (String reason : ALL_REASONS) {
            check(
                    reasons.getAsJsonObject("matched")
                            .getAsJsonObject(reason)
                            .get("intervals")
                            .getAsString()
                            .equals("4"),
                    "Wrong matched count for " + reason + ": " + reasons);
        }
        check(
                reasons.getAsJsonObject("kernelSwitchOuts")
                        .get("preempted")
                        .getAsString()
                        .equals("300"),
                "Kernel switch-outs must be reported: " + reasons);
        StackProfile read = StackProfile.read(profile);
        check(
                read.entries().stream()
                        .filter(entry -> entry.reason() == OffCpuReason.BLOCKED)
                        .allMatch(entry -> entry.taskState() == 0x2001),
                "The blocked task state must be kept");

        // Blocked only, the default: one reason, so the file is written exactly as an unclassified one.
        Path blockedOnly = v3Capture(dir.resolve("blocked"), jfr, List.of("blocked"), row -> "blocked");
        Path blockedAnalysis = correlate(blockedOnly, jfr, dir.resolve("blocked-analysis"));
        check(
                !Files.readString(blockedAnalysis.resolve(OutputFiles.COLLAPSED))
                        .contains("[offcpu:"),
                "A single-reason capture needs no reason frames");
        check(
                !Files.exists(blockedAnalysis.resolve(
                        OutputFiles.collapsedForReason(OutputFiles.PREFIX, OffCpuReason.BLOCKED))),
                "A single-reason capture needs no per-reason files");
        Path always = correlate(blockedOnly, jfr, dir.resolve("always"), "--collapsed-reason-frame", "always");
        check(
                Files.readString(always.resolve(OutputFiles.COLLAPSED))
                        .lines()
                        .allMatch(line -> line.startsWith("[offcpu: blocked];")),
                "--collapsed-reason-frame always must label every line");
    }

    /** The correlator recomputes every row's reason and its selection, as it does the admission threshold. */
    private static void classificationIsVerified(Path dir) throws Exception {
        Path jfr = recording(dir);
        // An unselected reason: the kernel filter would have dropped it.
        Path unselected = v3Capture(
                dir.resolve("unselected"), jfr, List.of("blocked"), row -> row == 0 ? "preempted" : "blocked");
        check(
                report(correlate(unselected, jfr, dir.resolve("a1")))
                                .get("invalidSource")
                                .getAsInt()
                        == 1,
                "An unselected reason must invalidate its row");
        // A reason that disagrees with the raw sched_switch arguments.
        List<JsonObject> rows = observations(sampleThread(jfr), row -> "blocked");
        rows.get(0).addProperty("prevTaskState", 0);
        Path disagreeing = OfflineCorrelatorTest.source(
                Files.createDirectories(dir.resolve("disagreeing")),
                jfr,
                rows,
                sampling(List.of("blocked")),
                3,
                reasonCounters());
        check(
                report(correlate(disagreeing, jfr, dir.resolve("a2")))
                                .get("invalidSource")
                                .getAsInt()
                        == 1,
                "A reason that disagrees with its task state must invalidate its row");
        // A classified row in an unclassified capture.
        List<JsonObject> legacy = observations(sampleThread(jfr), null);
        legacy.get(0).addProperty("offCpuReason", "blocked");
        Path mixedLegacy = OfflineCorrelatorTest.source(Files.createDirectories(dir.resolve("legacy")), jfr, legacy);
        check(
                report(correlate(mixedLegacy, jfr, dir.resolve("a3")))
                                .get("invalidSource")
                                .getAsInt()
                        == 1,
                "A version 2 capture cannot carry a classification");
        // A version 3 capture must name its reasons, and a version 2 one must not.
        Path unnamed = OfflineCorrelatorTest.source(
                Files.createDirectories(dir.resolve("unnamed")),
                jfr,
                observations(sampleThread(jfr), row -> "blocked"),
                OfflineCorrelatorTest.uniformSampling(),
                3,
                reasonCounters());
        rejects(
                IOException.class,
                "Source schema/sampling reasons mismatch",
                () -> correlate(unnamed, jfr, dir.resolve("a4")));
        Path uncanonical = OfflineCorrelatorTest.source(
                Files.createDirectories(dir.resolve("uncanonical")),
                jfr,
                observations(sampleThread(jfr), row -> "blocked"),
                sampling(List.of("preempted", "blocked")),
                3,
                reasonCounters());
        rejects(IOException.class, "canonical", () -> correlate(uncanonical, jfr, dir.resolve("a5")));
    }

    /** The one split rule: a blocked interval's run-queue part is its tail, clipped part by part. */
    private static void timeSplitRule() {
        long[] parts = new long[3];
        java.util.function.BiConsumer<long[], String> expect =
                (expected, what) -> check(Arrays.equals(parts, expected), what + ": " + Arrays.toString(parts));
        // Blocked for 100 ns, woken at 70: 70 sleeping, 30 on the run queue.
        check(TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, true, 30, 0, 100, parts) == TimeSplit.Outcome.SPLIT, "");
        expect.accept(new long[] {70, 30, 0}, "unclipped");
        TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, true, 30, 50, 90, parts);
        expect.accept(new long[] {20, 20, 0}, "window across the wakeup");
        TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, true, 30, 80, 100, parts);
        expect.accept(new long[] {0, 20, 0}, "window after the wakeup");
        TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, true, 30, 0, 60, parts);
        expect.accept(new long[] {60, 0, 0}, "window before the wakeup");
        TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, true, 0, 0, 100, parts);
        expect.accept(new long[] {100, 0, 0}, "ran as soon as it woke");
        TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, true, 100, 0, 100, parts);
        expect.accept(new long[] {0, 100, 0}, "woken at once");
        TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, true, 50, 100, 100, parts);
        expect.accept(new long[] {0, 0, 0}, "empty window");
        // Nothing is guessed or clamped: no reading, or one longer than a blocked interval, leaves it unsplit.
        check(
                TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, false, 0, 0, 100, parts) == TimeSplit.Outcome.NO_READING,
                "no reading");
        expect.accept(new long[] {0, 0, 100}, "no reading");
        check(
                TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, true, 101, 0, 100, parts)
                        == TimeSplit.Outcome.EXCEEDS_INTERVAL,
                "exceeds");
        expect.accept(new long[] {0, 0, 100}, "exceeds");
        TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, true, -1L, 0, 100, parts);
        expect.accept(new long[] {0, 0, 100}, "a u64 reading compares unsigned");
        TimeSplit.split(OffCpuReason.UNSPECIFIED, 0, 100, true, 10, 0, 100, parts);
        expect.accept(new long[] {0, 0, 100}, "unclassified");
        // Runnable and preempted intervals never left the run queue, whatever the reading.
        for (OffCpuReason reason : List.of(OffCpuReason.RUNNABLE, OffCpuReason.PREEMPTED)) {
            TimeSplit.split(reason, 0, 100, true, 103, 20, 100, parts);
            expect.accept(new long[] {0, 80, 0}, reason + " overshooting");
            TimeSplit.split(reason, 0, 100, true, 60, 0, 100, parts);
            expect.accept(new long[] {0, 100, 0}, reason.label());
        }
    }

    /** Rows of the time-split fixture: blocked, runnable and preempted in turn, each with a run-queue reading. */
    private static List<JsonObject> splitObservations(long tid) {
        List<JsonObject> rows = observations(tid, row -> ALL_REASONS.get(row % 3));
        for (int row = 0; row < rows.size(); row++) {
            long duration = 3000 + 7L * row * row;
            Long reading =
                    switch (row) {
                        case 0 -> 1000L; // blocked: 2000 sleeping, 1000 on the run queue
                        case 3 -> 0L; // blocked, ran as soon as it woke
                        case 6 -> null; // blocked, the kernel dropped the reading
                        case 9 -> duration + 1; // blocked, a reading longer than the interval
                        case 1 -> duration + 5; // runnable, the scheduler clock ran a little ahead
                        default -> row % 3 == 1 ? duration - 3 : duration;
                    };
            if (reading != null) rows.get(row).addProperty("runqueueNanos", Long.toString(reading));
        }
        return rows;
    }

    private static JsonObject timeSplit(String source) {
        JsonObject value = new JsonObject();
        value.addProperty("source", source);
        return value;
    }

    private static Path v4Capture(Path dir, Path jfr, List<JsonObject> rows, JsonObject timeSplit) throws IOException {
        JsonObject counters = reasonCounters();
        counters.addProperty("runqueueInversions", "1");
        return OfflineCorrelatorTest.source(
                Files.createDirectories(dir), jfr, rows, sampling(ALL_REASONS), 4, counters, timeSplit);
    }

    private static BigInteger totalNanos(Path summary) throws IOException {
        return new BigInteger(summaryOf(summary).get("totalNanos").getAsString());
    }

    /**
     * A version 4 capture splits each reason's time into sleeping and run-queue parts: the report, the profile and
     * every {@code --time} slice agree, and the parts always add up to the whole.
     */
    private static void sleepingAndRunqueue(Path dir) throws Exception {
        Path jfr = recording(dir);
        long tid = sampleThread(jfr);
        Path source = v4Capture(dir.resolve("split"), jfr, splitObservations(tid), timeSplit("schedInfo"));
        Path analysis = correlate(source, jfr, dir.resolve("analysis"));

        JsonObject reasons = report(analysis).getAsJsonObject("offCpuReasons");
        JsonObject matched = reasons.getAsJsonObject("matched");
        java.util.function.BiFunction<String, String, String> part =
                (reason, key) -> matched.getAsJsonObject(reason).get(key).getAsString();
        // Durations are 3000 + 7 row^2 ns: blocked rows 0, 3, 6, 9; runnable 1, 4, 7, 10; preempted 2, 5, 8, 11.
        check(part.apply("blocked", "sleepingNanos").equals("5063"), "Blocked sleeping: " + matched);
        check(part.apply("blocked", "runqueueNanos").equals("1000"), "Blocked run queue: " + matched);
        check(part.apply("blocked", "unsplitNanos").equals("6819"), "Blocked unsplit: " + matched);
        check(part.apply("runnable", "runqueueNanos").equals("13162"), "Runnable run queue: " + matched);
        check(part.apply("runnable", "sleepingNanos").equals("0"), "Runnable never sleeps: " + matched);
        check(part.apply("preempted", "runqueueNanos").equals("13498"), "Preempted run queue: " + matched);
        for (String reason : ALL_REASONS) {
            long sum = 0;
            for (String key : List.of("sleepingNanos", "runqueueNanos", "unsplitNanos")) {
                sum += Long.parseLong(part.apply(reason, key));
            }
            check(sum == Long.parseLong(part.apply(reason, "observedNanos")), "Parts must add up for " + reason);
        }
        JsonObject split = reasons.getAsJsonObject("timeSplit");
        check(
                split.get("source").getAsString().equals("schedInfo")
                        && split.get("available").getAsBoolean()
                        && split.get("runqueueInversions").getAsString().equals("1"),
                "Time split accounting: " + split);
        JsonObject unsplit = split.getAsJsonObject("unsplitIntervals");
        check(
                unsplit.get("withoutReading").getAsString().equals("1")
                        && unsplit.get("readingExceedsInterval").getAsString().equals("1"),
                "Unsplit causes: " + unsplit);

        // The default outputs are unchanged by the split: the profile still reproduces the collapsed file.
        Path profile = analysis.resolve(OutputFiles.PROFILE);
        String collapsed = Files.readString(analysis.resolve(OutputFiles.COLLAPSED));
        check(
                stacks(profile, dir.resolve("total.collapsed")).equals(collapsed),
                "Profile does not reproduce collapsed");
        StackProfile read = StackProfile.read(profile);
        check(read.header().timeSplitAvailable(), "The profile must announce its split");
        check(
                read.header().sources().get(0).timeSplitJson().equals("{\"source\":\"schedInfo\"}"),
                "The profile must record its source: " + read.header().sources().get(0));
        Path rewritten = dir.resolve("rewritten.pb");
        read.write(rewritten);
        check(Arrays.equals(Files.readAllBytes(profile), Files.readAllBytes(rewritten)), "Profile is not canonical");

        // Every --time slice is one part of the whole, and the three add up to it.
        Map<String, BigInteger> totals = new HashMap<>();
        for (String time : List.of("total", "sleeping", "runqueue", "split")) {
            Path summary = dir.resolve(time + ".json");
            String text = stacks(
                    profile, dir.resolve(time + "-slice.collapsed"), "--time", time, "--summary", summary.toString());
            totals.put(time, totalNanos(summary));
            check(summaryOf(summary).get("time").getAsString().equals(time), "Summary must name its time part");
            check(
                    summaryOf(summary).get("unsplitNanos").getAsString().equals("6819"),
                    "Summary must report the unsplit time: " + summaryOf(summary));
            if (time.equals("split")) {
                check(
                        text.lines()
                                .allMatch(line -> line.contains(";[sleeping] ")
                                        || line.contains(";[runqueue] ")
                                        || line.contains(";[unsplit] ")),
                        "Split lines must end in a part frame: " + text);
            }
        }
        check(totals.get("sleeping").equals(BigInteger.valueOf(5063)), "Sleeping slice: " + totals);
        check(totals.get("runqueue").equals(BigInteger.valueOf(1000 + 13162 + 13498)), "Run-queue slice: " + totals);
        check(
                totals.get("split").equals(totals.get("total"))
                        && totals.get("sleeping")
                                .add(totals.get("runqueue"))
                                .add(BigInteger.valueOf(6819))
                                .equals(totals.get("total")),
                "The parts must add up to the whole: " + totals);
        // Estimated parts are floored cumulatively, so they add up exactly too.
        for (StackProfile.Entry entry : read.entries()) {
            check(entry.split().adds(entry.observedNanos(), entry.estimatedNanos()), "Entry split: " + entry);
        }
        List<String> csv = exportCsv(profile, dir.resolve("entries.csv"));
        check(
                csv.get(0)
                        .endsWith(",sleeping_nanos,runqueue_nanos,unsplit_nanos,"
                                + "estimated_sleeping_nanos,estimated_runqueue_nanos,estimated_unsplit_nanos,"
                                + "java_stack_kinds,canonical_java_stack,thread_pool,run,estimate_available"),
                "CSV header: " + csv.get(0));

        // A profile without the split refuses the parts, and merging it with one that has them keeps it unsplit.
        Path v3 = v3Capture(dir.resolve("v3"), jfr, ALL_REASONS, row -> ALL_REASONS.get(row % 3));
        Path v3Profile = correlate(v3, jfr, dir.resolve("v3-analysis")).resolve(OutputFiles.PROFILE);
        check(!StackProfile.read(v3Profile).header().timeSplitAvailable(), "A version 3 profile has no split");
        rejects(
                IOException.class,
                "no sleeping/run-queue split",
                () -> stacks(v3Profile, dir.resolve("v3-runqueue.collapsed"), "--time", "runqueue"));
        StackProfile merged = StackProfile.merge(List.of(read, StackProfile.read(v3Profile)));
        check(merged.header().timeSplitAvailable(), "A merge with a split input has the split");
        long mergedUnsplit = merged.entries().stream()
                .mapToLong(entry -> entry.split().unsplit())
                .sum();
        check(mergedUnsplit == 6819 + read.totalObservedNanos(), "The version 3 input merges as unsplit");
        Path mergedFile = dir.resolve("merged.pb");
        merged.write(mergedFile);
        check(StackProfile.read(mergedFile).entries().equals(merged.entries()), "A merged split must round-trip");

        // With the source off, no row may carry a reading.
        Path off = v4Capture(dir.resolve("off"), jfr, splitObservations(tid), timeSplit("off"));
        JsonObject offReport = report(correlate(off, jfr, dir.resolve("off-analysis")));
        check(
                offReport.get("invalidSource").getAsInt() == 11,
                "Rows with a reading under source off must be invalid: " + offReport.get("invalidSource"));
        List<JsonObject> plain = observations(tid, row -> ALL_REASONS.get(row % 3));
        JsonObject offSplit = report(correlate(
                        v4Capture(dir.resolve("off-plain"), jfr, plain, timeSplit("off")),
                        jfr,
                        dir.resolve("off-plain-analysis")))
                .getAsJsonObject("offCpuReasons")
                .getAsJsonObject("timeSplit");
        check(
                offSplit.get("source").getAsString().equals("off")
                        && !offSplit.get("available").getAsBoolean(),
                "Source off: " + offSplit);
        // Version 4 always names its source, and an older capture never does.
        rejects(
                IOException.class,
                "Source schema/timeSplit mismatch",
                () -> correlate(
                        v4Capture(dir.resolve("unnamed"), jfr, plain, null), jfr, dir.resolve("unnamed-analysis")));
        rejects(
                IOException.class,
                "Unknown timeSplit source",
                () -> correlate(
                        v4Capture(dir.resolve("wakeup"), jfr, plain, timeSplit("wakeup")),
                        jfr,
                        dir.resolve("wakeup-analysis")));
    }

    private static List<String> exportCsv(Path profile, Path csv) throws Exception {
        check(
                OffCpuCorrelator.run(
                                new String[] {"export", "--profile", profile.toString(), "--output", csv.toString()})
                        == 0,
                "Export must succeed");
        return Files.readAllLines(csv);
    }

    /**
     * Java package names can be abbreviated to their initials or dropped. Only the display changes: filters still
     * match the full names, and frames that become equal merge into one line.
     */
    private static void packageNames(Path dir) throws Exception {
        var abbreviate = StackProfileRenderer.PackageNames.ABBREVIATE;
        var drop = StackProfileRenderer.PackageNames.DROP;
        Map<String, List<String>> expected = new java.util.LinkedHashMap<>();
        expected.put(
                "io.netty.channel.epoll.Native.epollWait0", List.of("i.n.c.e.Native.epollWait0", "Native.epollWait0"));
        expected.put("app.Outer$Inner.<init>", List.of("a.Outer$Inner.<init>", "Outer$Inner.<init>"));
        expected.put(
                "java.lang.invoke.LambdaForm$MH/0x0000000800c01000.invoke",
                List.of("j.l.i.LambdaForm$MH/0x0000000800c01000.invoke", "LambdaForm$MH/0x0000000800c01000.invoke"));
        // Frames that are not package-qualified Class.method names are left as they are.
        // A JDK 21+ hidden class carries a ".0x" suffix, which is still part of the class name.
        expected.put(
                "org.apache.Cursor$$Lambda.0x00000000819d7660.run",
                List.of("o.a.Cursor$$Lambda.0x00000000819d7660.run", "Cursor$$Lambda.0x00000000819d7660.run"));
        for (String unchanged : List.of("Worker.run", "[stack unavailable]", "epoll_wait", "libc.so.6", "")) {
            expected.put(unchanged, List.of(unchanged, unchanged));
        }
        for (var entry : expected.entrySet()) {
            check(
                    abbreviate.apply(entry.getKey()).equals(entry.getValue().get(0))
                            && drop.apply(entry.getKey())
                                    .equals(entry.getValue().get(1))
                            && StackProfileRenderer.PackageNames.FULL
                                    .apply(entry.getKey())
                                    .equals(entry.getKey()),
                    "Package names of " + entry.getKey() + ": " + abbreviate.apply(entry.getKey()) + ", "
                            + drop.apply(entry.getKey()));
        }

        var java = StackProfile.Kind.JAVA;
        List<StackProfile.Entry> entries = List.of(
                new StackProfile.Entry(
                        frames(java, "java.lang.Thread.run", "io.netty.channel.epoll.Native.epollWait0"),
                        null,
                        null,
                        OffCpuReason.BLOCKED,
                        1,
                        null,
                        3,
                        3000,
                        0),
                new StackProfile.Entry(
                        frames(java, "java.lang.Thread.run", "app.one.Worker.park"),
                        null,
                        null,
                        OffCpuReason.BLOCKED,
                        1,
                        null,
                        2,
                        2000,
                        0),
                new StackProfile.Entry(
                        frames(java, "java.lang.Thread.run", "app.two.Worker.park"),
                        null,
                        null,
                        OffCpuReason.BLOCKED,
                        1,
                        null,
                        1,
                        500,
                        0));
        Path profile = dir.resolve("packages.pb");
        new StackProfile(new StackProfile.Header(List.of(), List.of("reason"), false, "{}", "", List.of()), entries)
                .write(profile);

        check(
                stacks(profile, dir.resolve("full.collapsed"), "--package-names", "full")
                        .equals(stacks(profile, dir.resolve("default.collapsed"))),
                "full must be the default");
        String abbreviated = stacks(profile, dir.resolve("abbreviate.collapsed"), "--package-names", "abbreviate");
        check(
                abbreviated.equals("j.l.Thread.run;a.o.Worker.park 2\n"
                        + "j.l.Thread.run;a.t.Worker.park 1\n"
                        + "j.l.Thread.run;i.n.c.e.Native.epollWait0 3\n"),
                "Abbreviated packages: " + abbreviated);
        // Dropping the packages merges the two Worker.park stacks into one line.
        Path summary = dir.resolve("drop.json");
        String dropped = stacks(
                profile, dir.resolve("drop.collapsed"), "--package-names", "drop", "--summary", summary.toString());
        check(
                dropped.equals("Thread.run;Native.epollWait0 3\nThread.run;Worker.park 3\n"),
                "Dropped packages: " + dropped);
        check(summaryOf(summary).get("packageNames").getAsString().equals("drop"), "Summary must name the mode");
        check(summaryOf(summary).get("totalNanos").getAsString().equals("5500"), "Dropping packages keeps the time");
        // Filters match the full names, whatever is shown.
        String filtered = stacks(
                profile,
                dir.resolve("drop-filtered.collapsed"),
                "--package-names",
                "drop",
                "--exclude",
                "^io\\.netty\\.",
                "--include",
                "app\\.one\\.");
        check(filtered.equals("Thread.run;Worker.park 2\n"), "Filters must see full names: " + filtered);
        CommandLineTest.usageError(
                "expected one of full, abbreviate, drop but was 'short'",
                "stacks",
                "--profile",
                profile.toString(),
                "--output",
                dir.resolve("bad.collapsed").toString(),
                "--package-names",
                "short");
    }

    /**
     * Native frames that async-profiler records inside the Java stack keep their library whatever the package-names
     * mode: by their JFR_NATIVE kind, and, in a profile written before frames had kinds, by a native-looking name.
     */
    private static void nativeFrames(Path dir) throws Exception {
        var java = StackProfile.Kind.JAVA;
        var jfrNative = StackProfile.Kind.JFR_NATIVE;
        Map<String, List<String>> javaCases = new java.util.LinkedHashMap<>();
        javaCases.put(
                "io.netty.channel.epoll.Native.epollWait0", List.of("i.n.c.e.Native.epollWait0", "Native.epollWait0"));
        javaCases.put("a.Outer$Inner.<init>", List.of("a.Outer$Inner.<init>", "Outer$Inner.<init>"));
        for (var entry : javaCases.entrySet()) {
            StackProfile.Frame frame = new StackProfile.Frame(java, entry.getKey(), "");
            check(
                    StackProfileRenderer.PackageNames.ABBREVIATE
                                    .apply(frame)
                                    .equals(entry.getValue().get(0))
                            && StackProfileRenderer.PackageNames.DROP
                                    .apply(frame)
                                    .equals(entry.getValue().get(1)),
                    "A Java frame's package must still be shortened: " + entry.getKey());
        }
        List<String> nativeNames = List.of(
                "libjvm.so.Unsafe_Park",
                "libjvm.so.ZWorkers::run",
                "libasyncProfiler.so.PerfEvents::signalHandler",
                "libnio.so.Java_sun_nio_ch_EPoll_wait",
                "libnetty_transport_native_epoll_x86_64.so.netty_epoll_native_epollWait0",
                "libc.so.6.__GI___clone3",
                "/lib/ld-musl-x86_64.so.1",
                "C2 Runtime complete_monitor_locking",
                "SafepointBlob");
        for (String name : nativeNames) {
            for (var kind : List.of(java, jfrNative)) {
                for (var mode : StackProfileRenderer.PackageNames.values()) {
                    String shown = mode.apply(new StackProfile.Frame(kind, name, ""));
                    check(
                            shown.equals(name),
                            "A native frame was rewritten: " + name + " (" + kind + ", " + mode + ") -> " + shown);
                }
            }
        }
        // The kind alone decides: a JFR_NATIVE frame is never read as a package, however Java-like its name.
        check(
                StackProfileRenderer.PackageNames.DROP
                        .apply(new StackProfile.Frame(jfrNative, "a.b.Class.method", ""))
                        .equals("a.b.Class.method"),
                "A JFR_NATIVE frame must keep its name");

        // A rendered profile: the same name as JAVA in one stack and JFR_NATIVE in another is shortened only as JAVA.
        List<StackProfile.Entry> entries = List.of(
                new StackProfile.Entry(
                        List.of(
                                new StackProfile.Frame(java, "java.lang.Thread.run", ""),
                                new StackProfile.Frame(jfrNative, "libjvm.so.Unsafe_Park", "")),
                        null,
                        null,
                        OffCpuReason.BLOCKED,
                        1,
                        null,
                        3,
                        3000,
                        0),
                new StackProfile.Entry(
                        List.of(
                                new StackProfile.Frame(java, "java.lang.Thread.run", ""),
                                new StackProfile.Frame(jfrNative, "a.b.Class.method", "")),
                        null,
                        null,
                        OffCpuReason.BLOCKED,
                        1,
                        null,
                        2,
                        2000,
                        0),
                new StackProfile.Entry(
                        List.of(
                                new StackProfile.Frame(java, "java.lang.Thread.run", ""),
                                new StackProfile.Frame(java, "a.b.Class.method", "")),
                        null,
                        null,
                        OffCpuReason.BLOCKED,
                        1,
                        null,
                        1,
                        1000,
                        0));
        StackProfile.Header header = new StackProfile.Header(List.of(), List.of("reason"), false, "{}", "", List.of());
        StackProfile written = new StackProfile(header, entries);
        Path profile = dir.resolve("native.pb");
        written.write(profile);
        StackProfile read = StackProfile.read(profile);
        check(read.entries().equals(written.entries()), "Frame kinds must survive a round trip");
        String dropped = stacks(profile, dir.resolve("drop.collapsed"), "--package-names", "drop");
        check(
                dropped.equals("Thread.run;Class.method 1\n"
                        + "Thread.run;a.b.Class.method 2\n"
                        + "Thread.run;libjvm.so.Unsafe_Park 3\n"),
                "Only JAVA frames may be shortened: " + dropped);
        List<String> csv = exportCsv(profile, dir.resolve("native.csv"));
        check(
                csv.stream().filter(row -> row.contains("libjvm.so")).allMatch(row -> row.contains(",java;native,")),
                "Kinds not exported: " + csv);

        // Filters match the full names in every mode, so they select the same entries with the same totals.
        for (String mode : List.of("full", "abbreviate", "drop")) {
            Path summary = dir.resolve(mode + "-filtered.json");
            stacks(
                    profile,
                    dir.resolve(mode + "-filtered.collapsed"),
                    "--package-names",
                    mode,
                    "--exclude",
                    "^libjvm\\.so\\.",
                    "--summary",
                    summary.toString());
            check(
                    summaryOf(summary).get("totalNanos").getAsString().equals("3000")
                            && summaryOf(summary).get("intervals").getAsString().equals("3"),
                    "Filtered totals changed with " + mode + ": " + summaryOf(summary));
        }

        // A schema 1 profile tags every frame JAVA; it still reads, and the name rule protects its native frames.
        List<StackProfile.Entry> legacyEntries = entries.stream()
                .map(entry -> new StackProfile.Entry(
                        entry.javaStack().stream()
                                .map(frame -> new StackProfile.Frame(java, frame.name(), ""))
                                .toList(),
                        null,
                        null,
                        entry.reason(),
                        entry.taskState(),
                        null,
                        entry.intervals(),
                        entry.observedNanos(),
                        entry.estimatedNanos()))
                .toList();
        Path legacy = dir.resolve("legacy.pb");
        schemaVersion(new StackProfile(header, legacyEntries), 1, legacy);
        String legacyDropped = stacks(legacy, dir.resolve("legacy.collapsed"), "--package-names", "drop");
        check(
                legacyDropped.equals("Thread.run;Class.method 3\nThread.run;libjvm.so.Unsafe_Park 3\n"),
                "A schema 1 profile's native frames must keep their names: " + legacyDropped);
        // Merged with a newer profile, a frame either input calls JFR_NATIVE is JFR_NATIVE.
        StackProfile merged = StackProfile.merge(List.of(StackProfile.read(legacy), read));
        check(merged.entries().size() == 2, "Stacks that differ only in kinds must merge: " + merged.entries());
        for (StackProfile.Entry entry : merged.entries()) {
            check(entry.javaStack().get(1).kind() == jfrNative, "A sometimes native frame must be JFR_NATIVE");
        }
        check(merged.totalObservedNanos() == 12_000, "Merging must keep every nanosecond");
        // A schema 1 profile cannot carry the kind schema 2 introduced, and an unknown schema is refused by name.
        Path forged = dir.resolve("forged.pb");
        schemaVersion(written, 1, forged);
        rejects(IOException.class, "Invalid frame kind", () -> StackProfile.read(forged));
        Path future = dir.resolve("future.pb");
        schemaVersion(written, StackProfile.SCHEMA_VERSION + 1, future);
        rejects(
                IOException.class,
                "Unsupported stack profile schema " + (StackProfile.SCHEMA_VERSION + 1),
                () -> StackProfile.read(future));
    }

    /** Writes a profile with its profile_start's schema version replaced, as an older or newer writer would. */
    private static void schemaVersion(StackProfile profile, int version, Path file) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        profile.write(bytes);
        var input = new java.io.ByteArrayInputStream(bytes.toByteArray());
        try (var output = Files.newOutputStream(file)) {
            output.write(input.readNBytes(StackProfile.HEADER_BYTES));
            ProfileProto.Record record;
            while ((record = ProfileProto.Record.parseDelimitedFrom(input)) != null) {
                if (record.hasProfileStart()) {
                    record = record.toBuilder()
                            .setProfileStart(
                                    record.getProfileStart().toBuilder().setSchemaVersion(version))
                            .build();
                }
                record.writeDelimitedTo(output);
            }
        }
    }

    private static List<StackProfile.Frame> frames(StackProfile.Kind kind, String... names) {
        return Arrays.stream(names)
                .map(name -> new StackProfile.Frame(kind, name, kind == StackProfile.Kind.USER ? "libc.so.6" : ""))
                .toList();
    }

    private static JsonObject summaryOf(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    /**
     * Filters drop whole entries before they merge into lines, match stacks the slice does not render, and account
     * for what they remove — which {@code jfr-converter -X} on a rendered file can do none of.
     */
    private static void filters(Path dir) throws Exception {
        var java = StackProfile.Kind.JAVA;
        var kernel = StackProfile.Kind.KERNEL;
        var user = StackProfile.Kind.USER;
        List<StackProfile.Entry> entries = List.of(
                new StackProfile.Entry(
                        frames(java, "java.lang.Thread.run", "io.netty.channel.epoll.Native.epollWait0"),
                        frames(
                                kernel,
                                "do_syscall_64+0x10",
                                "ep_poll+0x20",
                                "schedule+0x2a",
                                "__traceiter_sched_exit_tp+0x40"),
                        frames(user, "epoll_wait+0x5"),
                        OffCpuReason.BLOCKED,
                        1,
                        "loop-1",
                        3,
                        3000,
                        0),
                new StackProfile.Entry(
                        frames(java, "java.lang.Thread.run", "app.Worker.park"),
                        frames(kernel, "do_syscall_64+0x10", "futex_wait+0x8"),
                        null,
                        OffCpuReason.BLOCKED,
                        1,
                        "worker",
                        2,
                        2000,
                        0),
                new StackProfile.Entry(
                        frames(java, "java.lang.Thread.run", "app.Worker.spin"),
                        frames(kernel, "schedule+0x2a"),
                        frames(user, "sched_yield+0x3"),
                        OffCpuReason.RUNNABLE,
                        0,
                        "worker",
                        1,
                        500,
                        0));
        List<String> grouped = List.of("reason", ProfileAccumulator.KERNEL, ProfileAccumulator.USER, "thread");
        Path profile = dir.resolve("filters.pb");
        new StackProfile(new StackProfile.Header(List.of(), grouped, false, "{}", "", List.of()), entries)
                .write(profile);

        String all = stacks(profile, dir.resolve("all.collapsed"));
        check(all.lines().count() == 3 && all.startsWith("[offcpu: "), "Unfiltered slice: " + all);

        // The literal frame name works as given, and the whole interval goes, not just the frame.
        Path summary = dir.resolve("netty.json");
        String netty = stacks(
                profile,
                dir.resolve("netty.collapsed"),
                "--exclude",
                "io.netty.channel.epoll.Native.epollWait0",
                "--summary",
                summary.toString());
        check(!netty.contains("epollWait0") && netty.lines().count() == 2, "Netty wait not dropped: " + netty);
        JsonObject counts = summaryOf(summary);
        check(counts.get("intervals").getAsString().equals("3"), "Kept intervals: " + counts);
        check(counts.get("totalNanos").getAsString().equals("2500"), "Kept nanos: " + counts);
        JsonObject filtered = counts.getAsJsonObject("filtered");
        check(filtered.get("intervals").getAsString().equals("3"), "Filtered intervals: " + counts);
        check(filtered.get("totalNanos").getAsString().equals("3000"), "Filtered nanos: " + counts);
        check(counts.getAsJsonArray("exclude").size() == 1, "Patterns must be reported: " + counts);
        check(counts.getAsJsonArray("filterScope").size() == 3, "Every grouped stack is searched: " + counts);

        // A kernel frame drops its entry from a Java-only slice, where the frame never reaches a line.
        check(
                stacks(profile, dir.resolve("ep.collapsed"), "--stack", "java", "--exclude", "^ep_poll_\\[k\\]$")
                        .equals(netty),
                "Unrendered kernel frames must be matched");
        // The profiler's own tracing frames are not the thread's and match nothing.
        check(
                stacks(profile, dir.resolve("trace.collapsed"), "--exclude", "__traceiter")
                        .equals(all),
                "Tracing frames must not be matched");
        // An unavailable stack matches as its placeholder frame.
        check(
                !stacks(profile, dir.resolve("nouser.collapsed"), "--exclude", "user stack unavailable")
                        .contains("app.Worker.park"),
                "Unavailable stacks match their placeholder");

        // Repeated includes are a union, and an exclusion wins over an inclusion.
        String union =
                stacks(profile, dir.resolve("union.collapsed"), "--include", "futex_wait", "--include", "sched_yield");
        check(union.lines().count() == 2 && !union.contains("epollWait0"), "Includes are a union: " + union);
        Path none = dir.resolve("none.json");
        check(
                stacks(
                                profile,
                                dir.resolve("none.collapsed"),
                                "--include",
                                "Thread.run",
                                "--exclude",
                                "Thread.run",
                                "--summary",
                                none.toString())
                        .isEmpty(),
                "Exclusion must win");
        check(
                summaryOf(none)
                        .getAsJsonObject("filtered")
                        .get("totalNanos")
                        .getAsString()
                        .equals("5500"),
                "An empty slice still accounts for the time: " + summaryOf(none));

        // Only kept entries decide whether the slice mixes reasons.
        String parked = stacks(profile, dir.resolve("parked.collapsed"), "--include", "Worker\\.(park|spin)$");
        check(parked.lines().allMatch(line -> line.startsWith("[offcpu: ")), "Two reasons remain: " + parked);
        String blockedOnly = stacks(profile, dir.resolve("park.collapsed"), "--include", "Worker\\.park$");
        check(
                blockedOnly.lines().count() == 1 && blockedOnly.startsWith("java.lang.Thread.run;app.Worker.park "),
                "A filter leaving one reason needs no reason frame: " + blockedOnly);

        rejects(
                java.util.regex.PatternSyntaxException.class,
                "Unclosed group",
                () -> stacks(profile, dir.resolve("bad.collapsed"), "--include", "("));
        CommandLineTest.usageError(
                "Missing required parameter for option '--exclude'",
                "stacks",
                "--profile",
                profile.toString(),
                "--output",
                dir.resolve("missing.collapsed").toString(),
                "--exclude");

        // Pattern files hold one pattern per line, skipping blank and '#' lines, and add to the inline patterns.
        Path includes = dir.resolve("includes.txt");
        Files.writeString(includes, "# idle-free waits\nfutex_wait\n\n   \nsched_yield\r\n");
        check(
                stacks(profile, dir.resolve("include-from.collapsed"), "--include-from", includes.toString())
                        .equals(union),
                "A pattern file must read like repeated --include options");
        Path excludes = dir.resolve("excludes.txt");
        Files.writeString(excludes, "sched_yield\n");
        Path fromSummary = dir.resolve("from.json");
        String mixed = stacks(
                profile,
                dir.resolve("mixed-from.collapsed"),
                "--include-from",
                includes.toString(),
                "--exclude",
                "futex_wait",
                "--exclude-from",
                excludes.toString(),
                "--summary",
                fromSummary.toString());
        check(mixed.isEmpty(), "Inline and file patterns combine: " + mixed);
        check(
                summaryOf(fromSummary).getAsJsonArray("include").toString().equals("[\"futex_wait\",\"sched_yield\"]")
                        && summaryOf(fromSummary)
                                .getAsJsonArray("exclude")
                                .toString()
                                .equals("[\"futex_wait\",\"sched_yield\"]"),
                "The summary lists the patterns read from files: " + summaryOf(fromSummary));
        // A pattern that starts with '#' is escaped, since such a line is a comment.
        check(
                OffCpuCorrelator.patternFile(
                                        "--exclude-from",
                                        Files.writeString(dir.resolve("hash.txt"), "\\#x\n")
                                                .toString())
                                .equals(List.of("\\#x"))
                        && "#x".matches("\\#x"),
                "An escaped '#' pattern must survive");
        Path empty = Files.writeString(dir.resolve("empty.txt"), "# nothing\n\n");
        rejects(
                IllegalArgumentException.class,
                "contains no patterns",
                () -> stacks(profile, dir.resolve("empty.collapsed"), "--include-from", empty.toString()));
        Path invalid = Files.writeString(dir.resolve("invalid.txt"), "futex_wait\n(\n");
        rejects(
                IllegalArgumentException.class,
                "line 2",
                () -> stacks(profile, dir.resolve("invalid.collapsed"), "--exclude-from", invalid.toString()));
        rejects(
                java.nio.file.NoSuchFileException.class,
                "absent.txt",
                () -> stacks(
                        profile,
                        dir.resolve("absent.collapsed"),
                        "--exclude-from",
                        dir.resolve("absent.txt").toString()));

        // A profile not grouped by kernel stacks cannot be filtered by them, and the summary says so.
        Path narrow = dir.resolve("narrow.pb");
        new StackProfile(
                        new StackProfile.Header(List.of(), List.of("reason"), false, "{}", "", List.of()),
                        entries.stream()
                                .map(entry -> new StackProfile.Entry(
                                        entry.javaStack(),
                                        null,
                                        null,
                                        entry.reason(),
                                        entry.taskState(),
                                        null,
                                        entry.intervals(),
                                        entry.observedNanos(),
                                        entry.estimatedNanos()))
                                .toList())
                .write(narrow);
        Path narrowSummary = dir.resolve("narrow.json");
        stacks(narrow, dir.resolve("narrow.collapsed"), "--exclude", "ep_poll", "--summary", narrowSummary.toString());
        JsonObject narrowCounts = summaryOf(narrowSummary);
        check(
                narrowCounts
                        .getAsJsonObject("filtered")
                        .get("intervals")
                        .getAsString()
                        .equals("0"),
                "Nothing to match: " + narrowCounts);
        check(
                narrowCounts.getAsJsonArray("filterScope").toString().equals("[\"java\"]"),
                "Scope must show what was searched: " + narrowCounts);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jonoffcpu-profile-test-");
        try {
            FixtureSteps.step(
                    "unclassifiedGolden", () -> unclassifiedGolden(Files.createDirectories(dir.resolve("golden"))));
            FixtureSteps.step("mixedReasons", () -> mixedReasons(Files.createDirectories(dir.resolve("mixed"))));
            FixtureSteps.step(
                    "classificationIsVerified",
                    () -> classificationIsVerified(Files.createDirectories(dir.resolve("verified"))));
            FixtureSteps.step("filters", () -> filters(Files.createDirectories(dir.resolve("filters"))));
            FixtureSteps.step("packageNames", () -> packageNames(Files.createDirectories(dir.resolve("packages"))));
            FixtureSteps.step("nativeFrames", () -> nativeFrames(Files.createDirectories(dir.resolve("native"))));
            FixtureSteps.step("timeSplitRule", () -> timeSplitRule());
            FixtureSteps.step(
                    "sleepingAndRunqueue", () -> sleepingAndRunqueue(Files.createDirectories(dir.resolve("split"))));
            System.out.println("Stack profile fixtures passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
