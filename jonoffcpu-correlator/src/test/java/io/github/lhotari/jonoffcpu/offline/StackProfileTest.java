// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
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
        rejects(
                IllegalArgumentException.class,
                "--exclude",
                () -> stacks(profile, dir.resolve("missing.collapsed"), "--exclude"));

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
            unclassifiedGolden(Files.createDirectories(dir.resolve("golden")));
            mixedReasons(Files.createDirectories(dir.resolve("mixed")));
            classificationIsVerified(Files.createDirectories(dir.resolve("verified")));
            filters(Files.createDirectories(dir.resolve("filters")));
            System.out.println("Stack profile fixtures passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
