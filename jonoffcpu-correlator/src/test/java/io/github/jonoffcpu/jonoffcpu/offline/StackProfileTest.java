// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jonoffcpu.jonoffcpu.capture.CaptureFixtures;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto;
import io.github.jonoffcpu.jonoffcpu.profile.ProfileProto;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Fixtures for switch-out reason classification and the stack profile the correlator writes. */
class StackProfileTest {
    private static final long EPOCH = Integer.toUnsignedLong(CorrelationFixture.EPOCH);
    private static final int ROWS = 12;
    private static final int STACKS = 3;
    private static final List<OffCpuReason> ALL_REASONS =
            List.of(OffCpuReason.BLOCKED, OffCpuReason.RUNNABLE, OffCpuReason.PREEMPTED);

    // ---- fixtures ------------------------------------------------------------------------------

    /** {@code rows} samples with cookies 1..rows, cycling through {@code stacks} distinct Java stacks. */
    private static Path recording(Path dir) throws IOException {
        Path file = dir.resolve("profile.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(CorrelationFixture.Capture.class);
            recording.enable(CorrelationFixture.Sample.class).withStackTrace();
            recording.enable(CorrelationFixture.Stats.class);
            recording.start();
            new CorrelationFixture.Capture().commit();
            for (int row = 0; row < ROWS; row++) {
                CorrelationFixture.Sample sample = new CorrelationFixture.Sample();
                sample.correlationId = (EPOCH << 32) | (row + 1);
                sample.monotonicTimeNanos = 5000L + 100L * row;
                commitAtDepth(1 + row % STACKS, sample);
            }
            CorrelationFixture.Stats stats = new CorrelationFixture.Stats();
            stats.admittedSignals = stats.acceptedCookies = stats.submittedSamples = ROWS;
            stats.commit();
            recording.stop();
            recording.dump(file);
        }
        return file;
    }

    private static void commitAtDepth(int depth, CorrelationFixture.Sample event) {
        if (depth <= 1) {
            event.commit();
        } else {
            commitAtDepth(depth - 1, event);
        }
    }

    private static long sampleThread(Path jfr) throws IOException {
        return CorrelationFixture.sampleThread(jfr);
    }

    /**
     * Observations for every sample, each of a distinct duration, classified by {@code reason}; the fixture's
     * default, blocked in TASK_INTERRUPTIBLE, when it is null.
     */
    private static List<CaptureProto.Observation.Builder> observations(long tid, IntFunction<OffCpuReason> reason) {
        List<CaptureProto.Observation.Builder> observations = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            CaptureProto.Observation.Builder observation = CorrelationFixture.observation(tid)
                    .setCorrelationId((EPOCH << 32) | (row + 1))
                    .setStartMonotonicNanos(1000 + 100L * row)
                    .setEndMonotonicNanos(4000 + 100L * row + 7L * row * row);
            OffCpuReason label = reason == null ? null : reason.apply(row);
            if (label != null) classify(observation, label);
            observations.add(observation);
        }
        return observations;
    }

    /** Sets a reason and the raw sched_switch arguments the kernel derives it from. */
    private static void classify(CaptureProto.Observation.Builder observation, OffCpuReason reason) {
        observation
                .setReason(reason.proto())
                .setPreempted(reason == OffCpuReason.PREEMPTED)
                .setPrevTaskState(reason == OffCpuReason.BLOCKED ? 0x2001 : 0);
    }

    private static CaptureProto.Sampling sampling(List<OffCpuReason> reasons) {
        return CaptureFixtures.uniformSampling(
                "0.01",
                CorrelationFixture.THRESHOLD,
                reasons.stream().map(OffCpuReason::proto).toArray(CaptureProto.OffCpuReason[]::new));
    }

    private static final Consumer<CaptureProto.KernelCounters.Builder> REASON_COUNTERS =
            kernel -> kernel.setSwitchOutsBlocked(40).setSwitchOutsRunnable(5).setSwitchOutsPreempted(300);

    private static final CaptureProto.TimeSplit OFF =
            CorrelationFixture.timeSplit(CaptureProto.TimeSplitSource.TIME_SPLIT_SOURCE_OFF);
    private static final CaptureProto.TimeSplit SCHED_INFO =
            CorrelationFixture.timeSplit(CaptureProto.TimeSplitSource.TIME_SPLIT_SOURCE_SCHED_INFO);

    /** The default capture: blocked intervals only, without the time split. */
    private static Path blockedCapture(Path dir, Path jfr) throws IOException {
        return CorrelationFixture.source(dir, jfr, observations(sampleThread(jfr), null));
    }

    private static Path reasonCapture(Path dir, Path jfr, List<OffCpuReason> reasons, IntFunction<OffCpuReason> reason)
            throws IOException {
        return reasonCapture(dir, jfr, observations(sampleThread(jfr), reason), sampling(reasons));
    }

    private static Path reasonCapture(
            Path dir, Path jfr, List<CaptureProto.Observation.Builder> rows, CaptureProto.Sampling sampling)
            throws IOException {
        return CorrelationFixture.source(Files.createDirectories(dir), jfr, rows, sampling, REASON_COUNTERS, OFF);
    }

    private static Path correlate(Path source, Path jfr, Path output, String... extra) throws Exception {
        List<String> args = new ArrayList<>(
                List.of("--source", source.toString(), "--jfr", jfr.toString(), "--output", output.toString()));
        args.addAll(Arrays.asList(extra));
        assertThat(OffCpuCorrelator.run(args.toArray(String[]::new)))
                .as("Correlation must complete")
                .isZero();
        return output;
    }

    private static String stacks(Path profile, Path output, String... extra) throws Exception {
        List<String> args =
                new ArrayList<>(List.of("stacks", "--profile", profile.toString(), "--output", output.toString()));
        args.addAll(Arrays.asList(extra));
        assertThat(OffCpuCorrelator.run(args.toArray(String[]::new)))
                .as("Rendering must succeed")
                .isZero();
        return Files.readString(output);
    }

    private static ReportProto.Report report(Path analysis) throws IOException {
        return CorrelationFixture.report(analysis.resolve(OutputFiles.REPORT));
    }

    private static ReportProto.ReasonTotals matched(ReportProto.OffCpuReasons reasons, OffCpuReason reason) {
        return reasons.getMatchedList().stream()
                .filter(totals -> totals.getReason() == reason.proto())
                .findFirst()
                .orElseThrow();
    }

    // ---- fixtures under test -------------------------------------------------------------------

    /**
     * A capture of one reason correlates without reason frames, and the profile rendered with its defaults reproduces
     * the collapsed file byte for byte — the property that makes the profile a trustworthy stand-in for it.
     */
    @Test
    void singleReasonGolden(@TempDir Path dir) throws Exception {
        Path jfr = recording(dir);
        Path source = blockedCapture(dir, jfr);
        Path analysis = correlate(source, jfr, dir.resolve("analysis"));
        String collapsed = Files.readString(analysis.resolve(OutputFiles.COLLAPSED));
        assertThat(collapsed)
                .as("A single-reason capture must not gain reason frames")
                .doesNotContain("[offcpu:");
        assertThat(collapsed.lines())
                .as("Expected one collapsed line per distinct stack: %s", collapsed)
                .hasSize(STACKS);
        Path profile = analysis.resolve(OutputFiles.PROFILE);
        assertThat(stacks(profile, dir.resolve("default.collapsed")))
                .as("Profile does not reproduce collapsed")
                .isEqualTo(collapsed);
        ReportProto.Report report = report(analysis);
        assertThat(report.getOffCpuReasons().getSelectedList())
                .as("The reasons the capture kept")
                .containsExactly(CaptureProto.OffCpuReason.OFF_CPU_REASON_BLOCKED);
        assertThat(report.getStackProfile().getEntries())
                .as("Wrong profile entry count")
                .isEqualTo(STACKS);

        // Reading and writing again gives the same bytes: the file order does not depend on match order.
        StackProfile read = StackProfile.read(profile);
        Path rewritten = dir.resolve("rewritten.pb");
        read.write(rewritten);
        assertThat(rewritten).as("Profile is not canonical").hasSameBinaryContentAs(profile);
        assertThat(read.header().report())
                .as("The profile must carry the run's report")
                .isEqualTo(report);
        assertThat(read.header().sources().get(0).getSampling())
                .as("The profile must carry the capture's sampling")
                .isEqualTo(CorrelationFixture.uniformSampling());
        assertThat(read.entries())
                .as("Blocked intervals must read back as blocked")
                .allMatch(entry -> entry.reason() == OffCpuReason.BLOCKED);
        // The recording is made on the test thread, whose name the runner chooses.
        String thread = Thread.currentThread().getName();
        assertThat(read.entries()).as("Thread names are grouped").allMatch(entry -> thread.equals(entry.thread()));
        // A JDK recording's frames are all Java code, so every frame of its Java stacks is JAVA.
        assertThat(read.entries().stream().flatMap(entry -> entry.javaStack().stream()))
                .as("Frames recorded as Java code must be JAVA")
                .allMatch(frame -> frame.kind() == StackProfile.Kind.JAVA);

        // Kernel stacks reach a collapsed line; the offset-free symbol keeps one function one frame.
        String mixed = stacks(profile, dir.resolve("java-kernel.collapsed"), "--stack", "java+kernel");
        assertThat(mixed.lines()).as("Kernel frames missing").allMatch(line -> line.contains(";kernel_wait_[k] "));
        String kernelOnly = stacks(profile, dir.resolve("kernel.collapsed"), "--stack", "kernel");
        assertThat(kernelOnly.lines()).hasSize(1);
        assertThat(kernelOnly).startsWith("kernel_wait_[k] ");
        // The profiler's own tracing frames at the leaf of a kernel stack are trimmed when rendered.
        List<StackProfile.Frame> traced = List.of(
                new StackProfile.Frame(StackProfile.Kind.KERNEL, "schedule+0x2a", "[kernel]"),
                new StackProfile.Frame(StackProfile.Kind.KERNEL, "__traceiter_sched_exit_tp+0x40", "[kernel]"),
                new StackProfile.Frame(StackProfile.Kind.KERNEL, "bpf_prog_ae49480d68384438_capture+0x1", "[kernel]"));
        assertThat(StackProfileRenderer.kernelEnd(traced))
                .as("Tracing frames must be trimmed")
                .isEqualTo(1);

        // The inverse-probability weights: the uniform threshold is 42949673, so each nanosecond stands for 100.
        Path summaryFile = dir.resolve("estimated.json");
        stacks(
                profile,
                dir.resolve("estimated.collapsed"),
                "--weights",
                "estimated",
                "--summary",
                summaryFile.toString());
        BigInteger estimated = new BigInteger(summaryOf(summaryFile).getTotalNanos());
        long observed = read.totalObservedNanos();
        assertThat(estimated)
                .as("Estimate out of range for %s", observed)
                .isStrictlyBetween(BigInteger.valueOf(observed * 99), BigInteger.valueOf(observed * 101));

        // Dropping every optional dimension merges entries but changes no rendered weight.
        Path narrow = correlate(source, jfr, dir.resolve("narrow"), "--max-profile-entries", "1");
        StackProfile narrowed = StackProfile.read(narrow.resolve(OutputFiles.PROFILE));
        assertThat(narrowed.header().dimensionsDropped())
                .as("Dimensions must drop in order")
                .containsExactly("thread", "user", "kernel");
        assertThat(narrowed.header().dimensions())
                .as("Only the reason must remain")
                .containsExactly("reason");
        assertThat(stacks(narrow.resolve(OutputFiles.PROFILE), dir.resolve("narrow.collapsed")))
                .as("Dropping dimensions changed the collapsed weights")
                .isEqualTo(collapsed);
        assertThatThrownBy(() -> stacks(
                        narrow.resolve(OutputFiles.PROFILE),
                        dir.resolve("narrow-kernel.collapsed"),
                        "--stack",
                        "kernel"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not grouped by its kernel");

        // Thinning labels and rescales the collapsed file; the profile renders it identically.
        Path thinned = correlate(source, jfr, dir.resolve("thinned"), "--thinning", "0.5");
        assertThat(stacks(thinned.resolve(OutputFiles.PROFILE), dir.resolve("thinned.collapsed")))
                .as("A thinned profile does not reproduce its collapsed file")
                .isEqualTo(Files.readString(thinned.resolve(OutputFiles.COLLAPSED)));
        assertThatThrownBy(() -> StackProfile.merge(List.of(StackProfile.read(thinned.resolve(OutputFiles.PROFILE)))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cannot be merged");

        // Merging a profile with itself doubles every counter and changes no stack.
        Path merged = dir.resolve("merged.pb");
        assertThat(OffCpuCorrelator.run(
                        new String[] {"merge", "--profiles", profile + "," + profile, "--output", merged.toString()}))
                .as("Merge must succeed")
                .isZero();
        StackProfile doubled = StackProfile.read(merged);
        assertThat(doubled.entries()).as("Merge changed the entry set").hasSameSizeAs(read.entries());
        for (int index = 0; index < read.entries().size(); index++) {
            StackProfile.Entry once = read.entries().get(index);
            StackProfile.Entry twice = doubled.entries().get(index);
            assertThat(twice.key()).as("Merge must keep every stack").isEqualTo(once.key());
            assertThat(twice.intervals()).as("Merge must double every counter").isEqualTo(2 * once.intervals());
            assertThat(twice.observedNanos())
                    .as("Merge must double every counter")
                    .isEqualTo(2 * once.observedNanos());
            assertThat(twice.estimatedNanos())
                    .as("Merge must double every counter")
                    .isEqualTo(2 * once.estimatedNanos());
        }
        assertThat(doubled.header().sources())
                .as("A merged profile keeps every input's provenance")
                .hasSize(2);

        // The export is one row per entry, for tools such as DuckDB.
        List<String> rows = exportCsv(profile, dir.resolve("entries.csv"));
        assertThat(rows.get(0)).as("CSV header changed").startsWith("reason,task_state,thread,java_stack");
        assertThat(rows).as("One CSV row per entry").hasSize(read.entries().size() + 1);
        assertThat(rows.get(1)).as("Unexpected CSV row").startsWith("blocked,1," + thread + ",");
        // A column names each Java-stack frame's kind, parallel to java_stack; the 0.5.0 columns follow it.
        assertThat(rows.get(0))
                .as("CSV header lacks the kinds")
                .endsWith(",java_stack_kinds,canonical_java_stack,thread_pool,run,estimate_available");
        String firstStack = String.join(
                ";",
                read.entries().get(0).javaStack().stream().map(frame -> "java").toList());
        assertThat(rows.get(1)).as("Unexpected CSV kinds").contains("," + firstStack + ",");

        // A damaged file is refused rather than half read.
        byte[] bytes = Files.readAllBytes(profile);
        Path truncated = dir.resolve("truncated.pb");
        Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 3));
        assertThatThrownBy(() -> StackProfile.read(truncated))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("stack profile");
        Path foreign = dir.resolve("foreign.pb");
        Files.write(foreign, Files.readAllBytes(source));
        assertThatThrownBy(() -> StackProfile.read(foreign))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not a jonoffcpu stack profile");
    }

    /** A capture that records every reason labels each collapsed line and splits the reasons into files. */
    @Test
    void mixedReasons(@TempDir Path dir) throws Exception {
        Path jfr = recording(dir);
        Path source = reasonCapture(dir, jfr, ALL_REASONS, row -> ALL_REASONS.get(row % 3));
        Path analysis = correlate(source, jfr, dir.resolve("analysis"));
        String collapsed = Files.readString(analysis.resolve(OutputFiles.COLLAPSED));
        assertThat(collapsed.lines())
                .as("Lines need reason frames: %s", collapsed)
                .allMatch(line -> line.startsWith("[offcpu: "));
        Map<OffCpuReason, String> perReason = new HashMap<>();
        StringBuilder prefixed = new StringBuilder();
        for (OffCpuReason reason : ALL_REASONS) {
            Path file = analysis.resolve(OutputFiles.collapsedForReason(OutputFiles.PREFIX, reason));
            String text = Files.readString(file);
            perReason.put(reason, text);
            assertThat(text).as("A per-reason file needs no reason frame").doesNotContain("[offcpu:");
            text.lines()
                    .forEach(line -> prefixed.append("[offcpu: ")
                            .append(reason.label())
                            .append("];")
                            .append(line)
                            .append('\n'));
        }
        assertThat(collapsed.lines().sorted().toList())
                .as("Per-reason files must sum to the combined one")
                .isEqualTo(prefixed.toString().lines().sorted().toList());
        Path profile = analysis.resolve(OutputFiles.PROFILE);
        assertThat(stacks(profile, dir.resolve("all.collapsed")))
                .as("Profile does not reproduce combined")
                .isEqualTo(collapsed);
        assertThat(stacks(profile, dir.resolve("blocked.collapsed"), "--reason", "blocked"))
                .as("Profile does not reproduce the blocked slice")
                .isEqualTo(perReason.get(OffCpuReason.BLOCKED));
        assertThat(stacks(profile, dir.resolve("never.collapsed"), "--reason-frame", "never")
                        .lines())
                .as("--reason-frame never must drop the frames")
                .noneMatch(line -> line.startsWith("[offcpu:"));
        String waitingForCpu = stacks(profile, dir.resolve("cpu.collapsed"), "--reason", "runnable,preempted");
        assertThat(waitingForCpu.lines())
                .as("A two-reason slice keeps its frames")
                .allMatch(line -> line.startsWith("[offcpu: runnable]") || line.startsWith("[offcpu: preempted]"));

        ReportProto.OffCpuReasons reasons = report(analysis).getOffCpuReasons();
        assertThat(reasons.getSelectedList())
                .as("Selected reasons missing: %s", reasons)
                .hasSize(3);
        for (OffCpuReason reason : ALL_REASONS) {
            assertThat(matched(reasons, reason).getIntervals())
                    .as("Wrong matched count for %s: %s", reason, reasons)
                    .isEqualTo(4);
        }
        assertThat(reasons.getKernelSwitchOuts().getPreempted())
                .as("Kernel switch-outs must be reported: %s", reasons)
                .isEqualTo(300);
        StackProfile read = StackProfile.read(profile);
        assertThat(read.entries().stream().filter(entry -> entry.reason() == OffCpuReason.BLOCKED))
                .as("The blocked task state must be kept")
                .allMatch(entry -> entry.taskState() == 0x2001);

        // Blocked only, the default: one reason, so the file is written exactly as an unclassified one.
        Path blockedOnly =
                reasonCapture(dir.resolve("blocked"), jfr, List.of(OffCpuReason.BLOCKED), row -> OffCpuReason.BLOCKED);
        Path blockedAnalysis = correlate(blockedOnly, jfr, dir.resolve("blocked-analysis"));
        assertThat(Files.readString(blockedAnalysis.resolve(OutputFiles.COLLAPSED)))
                .as("A single-reason capture needs no reason frames")
                .doesNotContain("[offcpu:");
        assertThat(blockedAnalysis.resolve(OutputFiles.collapsedForReason(OutputFiles.PREFIX, OffCpuReason.BLOCKED)))
                .as("A single-reason capture needs no per-reason files")
                .doesNotExist();
        Path always = correlate(blockedOnly, jfr, dir.resolve("always"), "--collapsed-reason-frame", "always");
        assertThat(Files.readString(always.resolve(OutputFiles.COLLAPSED)).lines())
                .as("--collapsed-reason-frame always must label every line")
                .allMatch(line -> line.startsWith("[offcpu: blocked];"));
    }

    /** The correlator recomputes every row's reason and its selection, as it does the admission threshold. */
    @Test
    void classificationIsVerified(@TempDir Path dir) throws Exception {
        Path jfr = recording(dir);
        List<OffCpuReason> blocked = List.of(OffCpuReason.BLOCKED);
        // An unselected reason: the kernel filter would have dropped it.
        Path unselected = reasonCapture(
                dir.resolve("unselected"),
                jfr,
                blocked,
                row -> row == 0 ? OffCpuReason.PREEMPTED : OffCpuReason.BLOCKED);
        assertThat(report(correlate(unselected, jfr, dir.resolve("a1"))).getInvalidSource())
                .as("An unselected reason must invalidate its row")
                .isEqualTo(1);
        // A reason that disagrees with the raw sched_switch arguments.
        List<CaptureProto.Observation.Builder> rows = observations(sampleThread(jfr), row -> OffCpuReason.BLOCKED);
        rows.get(0).setPrevTaskState(0);
        Path disagreeing = reasonCapture(dir.resolve("disagreeing"), jfr, rows, sampling(blocked));
        assertThat(report(correlate(disagreeing, jfr, dir.resolve("a2"))).getInvalidSource())
                .as("A reason that disagrees with its task state must invalidate its row")
                .isEqualTo(1);
        // The kernel always classifies: an unspecified reason is not a valid switch-out.
        List<CaptureProto.Observation.Builder> unspecified = observations(sampleThread(jfr), null);
        unspecified.get(0).setReason(CaptureProto.OffCpuReason.OFF_CPU_REASON_UNSPECIFIED);
        Path unclassified = reasonCapture(dir.resolve("unclassified"), jfr, unspecified, sampling(blocked));
        assertThat(report(correlate(unclassified, jfr, dir.resolve("a3"))).getInvalidSource())
                .as("An unspecified reason must invalidate its row")
                .isEqualTo(1);
        // A capture must name the reasons it kept, distinct and in canonical order.
        Path unnamed = reasonCapture(
                dir.resolve("unnamed"),
                jfr,
                observations(sampleThread(jfr), row -> OffCpuReason.BLOCKED),
                sampling(List.of()));
        assertThatThrownBy(() -> correlate(unnamed, jfr, dir.resolve("a4")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Sampling reasons must not be empty");
        Path uncanonical = reasonCapture(
                dir.resolve("uncanonical"),
                jfr,
                observations(sampleThread(jfr), row -> OffCpuReason.BLOCKED),
                sampling(List.of(OffCpuReason.PREEMPTED, OffCpuReason.BLOCKED)));
        assertThatThrownBy(() -> correlate(uncanonical, jfr, dir.resolve("a5")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("canonical");
    }

    /** The one split rule: a blocked interval's run-queue part is its tail, clipped part by part. */
    static Stream<Arguments> timeSplitRule() {
        var blocked = OffCpuReason.BLOCKED;
        List<Arguments> cases = new ArrayList<>(List.of(
                // Blocked for 100 ns, woken at 70: 70 sleeping, 30 on the run queue.
                Arguments.of("unclipped", blocked, true, 30L, 0L, 100L, new long[] {70, 30, 0}),
                Arguments.of("window across the wakeup", blocked, true, 30L, 50L, 90L, new long[] {20, 20, 0}),
                Arguments.of("window after the wakeup", blocked, true, 30L, 80L, 100L, new long[] {0, 20, 0}),
                Arguments.of("window before the wakeup", blocked, true, 30L, 0L, 60L, new long[] {60, 0, 0}),
                Arguments.of("ran as soon as it woke", blocked, true, 0L, 0L, 100L, new long[] {100, 0, 0}),
                Arguments.of("woken at once", blocked, true, 100L, 0L, 100L, new long[] {0, 100, 0}),
                Arguments.of("empty window", blocked, true, 50L, 100L, 100L, new long[] {0, 0, 0}),
                // Nothing is guessed or clamped: no reading, or one longer than a blocked interval, leaves it unsplit.
                Arguments.of("no reading", blocked, false, 0L, 0L, 100L, new long[] {0, 0, 100}),
                Arguments.of("exceeds", blocked, true, 101L, 0L, 100L, new long[] {0, 0, 100}),
                Arguments.of("a u64 reading compares unsigned", blocked, true, -1L, 0L, 100L, new long[] {0, 0, 100}),
                Arguments.of("unspecified", OffCpuReason.UNSPECIFIED, true, 10L, 0L, 100L, new long[] {0, 0, 100})));
        // Runnable and preempted intervals never left the run queue, whatever the reading.
        for (OffCpuReason reason : List.of(OffCpuReason.RUNNABLE, OffCpuReason.PREEMPTED)) {
            cases.add(Arguments.of(reason + " overshooting", reason, true, 103L, 20L, 100L, new long[] {0, 80, 0}));
            cases.add(Arguments.of(reason.label(), reason, true, 60L, 0L, 100L, new long[] {0, 100, 0}));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void timeSplitRule(
            String what, OffCpuReason reason, boolean hasReading, long reading, long from, long to, long[] expected) {
        long[] parts = new long[3];
        TimeSplit.split(reason, 0, 100, hasReading, reading, from, to, parts);
        assertThat(parts).as(what).containsExactly(expected);
    }

    /** The outcome names why an interval was or was not split. */
    @Test
    void timeSplitOutcome() {
        long[] parts = new long[3];
        assertThat(TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, true, 30, 0, 100, parts))
                .isEqualTo(TimeSplit.Outcome.SPLIT);
        assertThat(TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, false, 0, 0, 100, parts))
                .as("no reading")
                .isEqualTo(TimeSplit.Outcome.NO_READING);
        assertThat(TimeSplit.split(OffCpuReason.BLOCKED, 0, 100, true, 101, 0, 100, parts))
                .as("exceeds")
                .isEqualTo(TimeSplit.Outcome.EXCEEDS_INTERVAL);
    }

    /** Rows of the time-split fixture: blocked, runnable and preempted in turn, each with a run-queue reading. */
    private static List<CaptureProto.Observation.Builder> splitObservations(long tid) {
        List<CaptureProto.Observation.Builder> rows = observations(tid, row -> ALL_REASONS.get(row % 3));
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
            if (reading != null) rows.get(row).setRunqueueNanos(reading);
        }
        return rows;
    }

    private static Path splitCapture(
            Path dir, Path jfr, List<CaptureProto.Observation.Builder> rows, CaptureProto.TimeSplit timeSplit)
            throws IOException {
        return CorrelationFixture.source(
                Files.createDirectories(dir),
                jfr,
                rows,
                sampling(ALL_REASONS),
                REASON_COUNTERS.andThen(kernel -> kernel.setRunqueueInversions(1)),
                timeSplit);
    }

    private static BigInteger totalNanos(Path summary) throws IOException {
        return new BigInteger(summaryOf(summary).getTotalNanos());
    }

    /**
     * A capture with the sched_info source splits each reason's time into sleeping and run-queue parts: the report, the profile and
     * every {@code --time} slice agree, and the parts always add up to the whole.
     */
    @Test
    void sleepingAndRunqueue(@TempDir Path dir) throws Exception {
        Path jfr = recording(dir);
        long tid = sampleThread(jfr);
        Path source = splitCapture(dir.resolve("split"), jfr, splitObservations(tid), SCHED_INFO);
        Path analysis = correlate(source, jfr, dir.resolve("analysis"));

        ReportProto.OffCpuReasons reasons = report(analysis).getOffCpuReasons();
        ReportProto.ReasonTotals blocked = matched(reasons, OffCpuReason.BLOCKED);
        ReportProto.ReasonTotals runnable = matched(reasons, OffCpuReason.RUNNABLE);
        ReportProto.ReasonTotals preempted = matched(reasons, OffCpuReason.PREEMPTED);
        // Durations are 3000 + 7 row^2 ns: blocked rows 0, 3, 6, 9; runnable 1, 4, 7, 10; preempted 2, 5, 8, 11.
        assertThat(blocked.getSleepingNanos())
                .as("Blocked sleeping: %s", blocked)
                .isEqualTo(5063);
        assertThat(blocked.getRunqueueNanos())
                .as("Blocked run queue: %s", blocked)
                .isEqualTo(1000);
        assertThat(blocked.getUnsplitNanos()).as("Blocked unsplit: %s", blocked).isEqualTo(6819);
        assertThat(runnable.getRunqueueNanos())
                .as("Runnable run queue: %s", runnable)
                .isEqualTo(13162);
        assertThat(runnable.getSleepingNanos())
                .as("Runnable never sleeps: %s", runnable)
                .isZero();
        assertThat(preempted.getRunqueueNanos())
                .as("Preempted run queue: %s", preempted)
                .isEqualTo(13498);
        for (ReportProto.ReasonTotals totals : reasons.getMatchedList()) {
            assertThat(totals.getSleepingNanos() + totals.getRunqueueNanos() + totals.getUnsplitNanos())
                    .as("Parts must add up for %s", totals.getReason())
                    .isEqualTo(totals.getObservedNanos());
        }
        ReportProto.TimeSplitReport split = reasons.getTimeSplit();
        assertThat(split.getSource())
                .as("Time split accounting: %s", split)
                .isEqualTo(CaptureProto.TimeSplitSource.TIME_SPLIT_SOURCE_SCHED_INFO);
        assertThat(split.getAvailable()).as("Time split accounting: %s", split).isTrue();
        assertThat(split.getRunqueueInversions())
                .as("Time split accounting: %s", split)
                .isEqualTo(1);
        assertThat(split.getUnsplitIntervals().getWithoutReading())
                .as("Unsplit causes: %s", split)
                .isEqualTo(1);
        assertThat(split.getUnsplitIntervals().getReadingExceedsInterval())
                .as("Unsplit causes: %s", split)
                .isEqualTo(1);

        // The default outputs are unchanged by the split: the profile still reproduces the collapsed file.
        Path profile = analysis.resolve(OutputFiles.PROFILE);
        String collapsed = Files.readString(analysis.resolve(OutputFiles.COLLAPSED));
        assertThat(stacks(profile, dir.resolve("total.collapsed")))
                .as("Profile does not reproduce collapsed")
                .isEqualTo(collapsed);
        StackProfile read = StackProfile.read(profile);
        assertThat(read.header().timeSplitAvailable())
                .as("The profile must announce its split")
                .isTrue();
        assertThat(read.header().sources().get(0).getTimeSplit())
                .as("The profile must record its source")
                .isEqualTo(SCHED_INFO);
        Path rewritten = dir.resolve("rewritten.pb");
        read.write(rewritten);
        assertThat(rewritten).as("Profile is not canonical").hasSameBinaryContentAs(profile);

        // Every --time slice is one part of the whole, and the three add up to it.
        Map<String, BigInteger> totals = new HashMap<>();
        for (String time : List.of("total", "sleeping", "runqueue", "split")) {
            Path summary = dir.resolve(time + ".json");
            String text = stacks(
                    profile, dir.resolve(time + "-slice.collapsed"), "--time", time, "--summary", summary.toString());
            totals.put(time, totalNanos(summary));
            assertThat(summaryOf(summary).getTime())
                    .as("Summary must name its time part")
                    .isEqualTo(time);
            assertThat(summaryOf(summary).getUnsplitNanos())
                    .as("Summary must report the unsplit time: %s", summaryOf(summary))
                    .isEqualTo("6819");
            if (time.equals("split")) {
                assertThat(text.lines())
                        .as("Split lines must end in a part frame: %s", text)
                        .allMatch(line -> line.contains(";[sleeping] ")
                                || line.contains(";[runqueue] ")
                                || line.contains(";[unsplit] "));
            }
        }
        assertThat(totals.get("sleeping")).as("Sleeping slice: %s", totals).isEqualTo(BigInteger.valueOf(5063));
        assertThat(totals.get("runqueue"))
                .as("Run-queue slice: %s", totals)
                .isEqualTo(BigInteger.valueOf(1000 + 13162 + 13498));
        assertThat(totals.get("split"))
                .as("The parts must add up to the whole: %s", totals)
                .isEqualTo(totals.get("total"));
        assertThat(totals.get("sleeping").add(totals.get("runqueue")).add(BigInteger.valueOf(6819)))
                .as("The parts must add up to the whole: %s", totals)
                .isEqualTo(totals.get("total"));
        // Estimated parts are floored cumulatively, so they add up exactly too.
        assertThat(read.entries())
                .as("Entry split")
                .allMatch(entry -> entry.split().adds(entry.observedNanos(), entry.estimatedNanos()));
        List<String> csv = exportCsv(profile, dir.resolve("entries.csv"));
        assertThat(csv.get(0))
                .as("CSV header")
                .endsWith(",sleeping_nanos,runqueue_nanos,unsplit_nanos,"
                        + "estimated_sleeping_nanos,estimated_runqueue_nanos,estimated_unsplit_nanos,"
                        + "java_stack_kinds,canonical_java_stack,thread_pool,run,estimate_available");

        // A profile without the split refuses the parts, and merging it with one that has them keeps it unsplit.
        Path v3 = reasonCapture(dir.resolve("v3"), jfr, ALL_REASONS, row -> ALL_REASONS.get(row % 3));
        Path v3Profile = correlate(v3, jfr, dir.resolve("v3-analysis")).resolve(OutputFiles.PROFILE);
        assertThat(StackProfile.read(v3Profile).header().timeSplitAvailable())
                .as("A profile of a capture with the source off has no split")
                .isFalse();
        assertThatThrownBy(() -> stacks(v3Profile, dir.resolve("v3-runqueue.collapsed"), "--time", "runqueue"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no sleeping/run-queue split");
        StackProfile merged = StackProfile.merge(List.of(read, StackProfile.read(v3Profile)));
        assertThat(merged.header().timeSplitAvailable())
                .as("A merge with a split input has the split")
                .isTrue();
        long mergedUnsplit = merged.entries().stream()
                .mapToLong(entry -> entry.split().unsplit())
                .sum();
        assertThat(mergedUnsplit).as("The unsplit input merges as unsplit").isEqualTo(6819 + read.totalObservedNanos());
        Path mergedFile = dir.resolve("merged.pb");
        merged.write(mergedFile);
        assertThat(StackProfile.read(mergedFile).entries())
                .as("A merged split must round-trip")
                .isEqualTo(merged.entries());

        // With the source off, no row may carry a reading.
        Path off = splitCapture(dir.resolve("off"), jfr, splitObservations(tid), OFF);
        ReportProto.Report offReport = report(correlate(off, jfr, dir.resolve("off-analysis")));
        assertThat(offReport.getInvalidSource())
                .as("Rows with a reading under source off must be invalid")
                .isEqualTo(11);
        List<CaptureProto.Observation.Builder> plain = observations(tid, row -> ALL_REASONS.get(row % 3));
        ReportProto.TimeSplitReport offSplit = report(correlate(
                        splitCapture(dir.resolve("off-plain"), jfr, plain, OFF),
                        jfr,
                        dir.resolve("off-plain-analysis")))
                .getOffCpuReasons()
                .getTimeSplit();
        assertThat(offSplit.getSource())
                .as("Source off: %s", offSplit)
                .isEqualTo(CaptureProto.TimeSplitSource.TIME_SPLIT_SOURCE_OFF);
        assertThat(offSplit.getAvailable()).as("Source off: %s", offSplit).isFalse();
        // A capture always names its source; an unspecified one is refused, never read as off.
        assertThatThrownBy(() -> correlate(
                        splitCapture(dir.resolve("unnamed"), jfr, plain, CaptureProto.TimeSplit.getDefaultInstance()),
                        jfr,
                        dir.resolve("unnamed-analysis")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unknown timeSplit source");
    }

    private static List<String> exportCsv(Path profile, Path csv) throws Exception {
        assertThat(OffCpuCorrelator.run(
                        new String[] {"export", "--profile", profile.toString(), "--output", csv.toString()}))
                .as("Export must succeed")
                .isZero();
        return Files.readAllLines(csv);
    }

    /** How each package-names mode shows a frame name. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "io.netty.channel.epoll.Native.epollWait0, i.n.c.e.Native.epollWait0, Native.epollWait0",
        "app.Outer$Inner.<init>, a.Outer$Inner.<init>, Outer$Inner.<init>",
        "java.lang.invoke.LambdaForm$MH/0x0000000800c01000.invoke, j.l.i.LambdaForm$MH/0x0000000800c01000.invoke,"
                + " LambdaForm$MH/0x0000000800c01000.invoke",
        // A JDK 21+ hidden class carries a ".0x" suffix, which is still part of the class name.
        "org.apache.Cursor$$Lambda.0x00000000819d7660.run, o.a.Cursor$$Lambda.0x00000000819d7660.run,"
                + " Cursor$$Lambda.0x00000000819d7660.run",
        // Frames that are not package-qualified Class.method names are left as they are.
        "Worker.run, Worker.run, Worker.run",
        "[stack unavailable], [stack unavailable], [stack unavailable]",
        "epoll_wait, epoll_wait, epoll_wait",
        "libc.so.6, libc.so.6, libc.so.6",
        "'', '', ''"
    })
    void packageNamesOfName(String name, String abbreviated, String dropped) {
        assertThat(StackProfileRenderer.PackageNames.ABBREVIATE.apply(name)).isEqualTo(abbreviated);
        assertThat(StackProfileRenderer.PackageNames.DROP.apply(name)).isEqualTo(dropped);
        assertThat(StackProfileRenderer.PackageNames.FULL.apply(name)).isEqualTo(name);
    }

    /**
     * Java package names can be abbreviated to their initials or dropped. Only the display changes: filters still
     * match the full names, and frames that become equal merge into one line.
     */
    @Test
    void packageNames(@TempDir Path dir) throws Exception {
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
        new StackProfile(new StackProfile.Header(List.of(), List.of("reason"), false, null, "", List.of()), entries)
                .write(profile);

        assertThat(stacks(profile, dir.resolve("full.collapsed"), "--package-names", "full"))
                .as("full must be the default")
                .isEqualTo(stacks(profile, dir.resolve("default.collapsed")));
        String abbreviated = stacks(profile, dir.resolve("abbreviate.collapsed"), "--package-names", "abbreviate");
        assertThat(abbreviated)
                .as("Abbreviated packages")
                .isEqualTo("j.l.Thread.run;a.o.Worker.park 2\n"
                        + "j.l.Thread.run;a.t.Worker.park 1\n"
                        + "j.l.Thread.run;i.n.c.e.Native.epollWait0 3\n");
        // Dropping the packages merges the two Worker.park stacks into one line.
        Path summary = dir.resolve("drop.json");
        String dropped = stacks(
                profile, dir.resolve("drop.collapsed"), "--package-names", "drop", "--summary", summary.toString());
        assertThat(dropped)
                .as("Dropped packages")
                .isEqualTo("Thread.run;Native.epollWait0 3\nThread.run;Worker.park 3\n");
        assertThat(summaryOf(summary).getPackageNames())
                .as("Summary must name the mode")
                .isEqualTo("drop");
        assertThat(summaryOf(summary).getTotalNanos())
                .as("Dropping packages keeps the time")
                .isEqualTo("5500");
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
        assertThat(filtered).as("Filters must see full names").isEqualTo("Thread.run;Worker.park 2\n");
        CommandLineFixture.usageError(
                "expected one of full, abbreviate, drop but was 'short'",
                "stacks",
                "--profile",
                profile.toString(),
                "--output",
                dir.resolve("bad.collapsed").toString(),
                "--package-names",
                "short");
    }

    /** A Java frame's package is shortened by the package-names modes. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "io.netty.channel.epoll.Native.epollWait0, i.n.c.e.Native.epollWait0, Native.epollWait0",
        "a.Outer$Inner.<init>, a.Outer$Inner.<init>, Outer$Inner.<init>"
    })
    void javaFramePackagesAreShortened(String name, String abbreviated, String dropped) {
        StackProfile.Frame frame = new StackProfile.Frame(StackProfile.Kind.JAVA, name, "");
        assertThat(StackProfileRenderer.PackageNames.ABBREVIATE.apply(frame))
                .as("A Java frame's package must still be shortened")
                .isEqualTo(abbreviated);
        assertThat(StackProfileRenderer.PackageNames.DROP.apply(frame))
                .as("A Java frame's package must still be shortened")
                .isEqualTo(dropped);
    }

    /** A native-looking name is never rewritten, whatever its kind and the package-names mode. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "libjvm.so.Unsafe_Park",
                "libjvm.so.ZWorkers::run",
                "libasyncProfiler.so.PerfEvents::signalHandler",
                "libnio.so.Java_sun_nio_ch_EPoll_wait",
                "libnetty_transport_native_epoll_x86_64.so.netty_epoll_native_epollWait0",
                "libc.so.6.__GI___clone3",
                "/lib/ld-musl-x86_64.so.1",
                "C2 Runtime complete_monitor_locking",
                "SafepointBlob"
            })
    void nativeFrameNamesAreKept(String name) {
        for (var kind : List.of(StackProfile.Kind.JAVA, StackProfile.Kind.JFR_NATIVE)) {
            for (var mode : StackProfileRenderer.PackageNames.values()) {
                assertThat(mode.apply(new StackProfile.Frame(kind, name, "")))
                        .as("A native frame was rewritten: %s (%s, %s)", name, kind, mode)
                        .isEqualTo(name);
            }
        }
    }

    /**
     * Native frames that async-profiler records inside the Java stack keep their library whatever the package-names
     * mode: by their JFR_NATIVE kind, and, as a second line of defence, by a native-looking name.
     */
    @Test
    void nativeFrames(@TempDir Path dir) throws Exception {
        var java = StackProfile.Kind.JAVA;
        var jfrNative = StackProfile.Kind.JFR_NATIVE;
        // The kind alone decides: a JFR_NATIVE frame is never read as a package, however Java-like its name.
        assertThat(StackProfileRenderer.PackageNames.DROP.apply(
                        new StackProfile.Frame(jfrNative, "a.b.Class.method", "")))
                .as("A JFR_NATIVE frame must keep its name")
                .isEqualTo("a.b.Class.method");

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
        StackProfile.Header header = new StackProfile.Header(List.of(), List.of("reason"), false, null, "", List.of());
        StackProfile written = new StackProfile(header, entries);
        Path profile = dir.resolve("native.pb");
        written.write(profile);
        StackProfile read = StackProfile.read(profile);
        assertThat(read.entries()).as("Frame kinds must survive a round trip").isEqualTo(written.entries());
        String dropped = stacks(profile, dir.resolve("drop.collapsed"), "--package-names", "drop");
        assertThat(dropped)
                .as("Only JAVA frames may be shortened")
                .isEqualTo("Thread.run;Class.method 1\n"
                        + "Thread.run;a.b.Class.method 2\n"
                        + "Thread.run;libjvm.so.Unsafe_Park 3\n");
        List<String> csv = exportCsv(profile, dir.resolve("native.csv"));
        assertThat(csv.stream().filter(row -> row.contains("libjvm.so")))
                .as("Kinds not exported: %s", csv)
                .allMatch(row -> row.contains(",java;native,"));

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
            assertThat(summaryOf(summary).getTotalNanos())
                    .as("Filtered totals changed with %s: %s", mode, summaryOf(summary))
                    .isEqualTo("3000");
            assertThat(summaryOf(summary).getIntervals())
                    .as("Filtered totals changed with %s: %s", mode, summaryOf(summary))
                    .isEqualTo(3);
        }

        // Frames all tagged JAVA, as another capture's recording could tag them: the name rule protects the native one.
        List<StackProfile.Entry> javaEntries = entries.stream()
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
        Path allJava = dir.resolve("all-java.pb");
        new StackProfile(header, javaEntries).write(allJava);
        String allJavaDropped = stacks(allJava, dir.resolve("all-java.collapsed"), "--package-names", "drop");
        assertThat(allJavaDropped)
                .as("A native-looking name must keep its library even as a JAVA frame")
                .isEqualTo("Thread.run;Class.method 3\nThread.run;libjvm.so.Unsafe_Park 3\n");
        // Merged, a frame either input calls JFR_NATIVE is JFR_NATIVE.
        StackProfile merged = StackProfile.merge(List.of(StackProfile.read(allJava), read));
        assertThat(merged.entries())
                .as("Stacks that differ only in kinds must merge")
                .hasSize(2)
                .as("A sometimes native frame must be JFR_NATIVE")
                .allMatch(entry -> entry.javaStack().get(1).kind() == jfrNative);
        assertThat(merged.totalObservedNanos())
                .as("Merging must keep every nanosecond")
                .isEqualTo(12_000);
        // An older or a newer schema is refused by name rather than read with fields it does not have.
        for (int schema : new int[] {1, StackProfile.SCHEMA_VERSION - 1, StackProfile.SCHEMA_VERSION + 1}) {
            Path other = dir.resolve("schema-" + schema + ".pb");
            schemaVersion(written, schema, other);
            assertThatThrownBy(() -> StackProfile.read(other))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Unsupported stack profile schema " + schema);
        }
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

    private static AnalysisProto.SliceSummary summaryOf(Path file) throws IOException {
        return CorrelationFixture.parse(file, AnalysisProto.SliceSummary.newBuilder())
                .build();
    }

    /**
     * Filters drop whole entries before they merge into lines, match stacks the slice does not render, and account
     * for what they remove — which {@code jfr-converter -X} on a rendered file can do none of.
     */
    @Test
    void filters(@TempDir Path dir) throws Exception {
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
        new StackProfile(new StackProfile.Header(List.of(), grouped, false, null, "", List.of()), entries)
                .write(profile);

        String all = stacks(profile, dir.resolve("all.collapsed"));
        assertThat(all.lines()).as("Unfiltered slice: %s", all).hasSize(3);
        assertThat(all).as("Unfiltered slice").startsWith("[offcpu: ");

        // The literal frame name works as given, and the whole interval goes, not just the frame.
        Path summary = dir.resolve("netty.json");
        String netty = stacks(
                profile,
                dir.resolve("netty.collapsed"),
                "--exclude",
                "io.netty.channel.epoll.Native.epollWait0",
                "--summary",
                summary.toString());
        assertThat(netty).as("Netty wait not dropped").doesNotContain("epollWait0");
        assertThat(netty.lines()).as("Netty wait not dropped: %s", netty).hasSize(2);
        AnalysisProto.SliceSummary counts = summaryOf(summary);
        assertThat(counts.getIntervals()).as("Kept intervals: %s", counts).isEqualTo(3);
        assertThat(counts.getTotalNanos()).as("Kept nanos: %s", counts).isEqualTo("2500");
        AnalysisProto.FilteredSlice filtered = counts.getFiltered();
        assertThat(filtered.getIntervals()).as("Filtered intervals: %s", counts).isEqualTo(3);
        assertThat(filtered.getTotalNanos()).as("Filtered nanos: %s", counts).isEqualTo("3000");
        assertThat(counts.getExcludeList())
                .as("Patterns must be reported: %s", counts)
                .hasSize(1);
        assertThat(counts.getFilterScopeList())
                .as("Every grouped stack is searched: %s", counts)
                .hasSize(3);

        // A kernel frame drops its entry from a Java-only slice, where the frame never reaches a line.
        assertThat(stacks(profile, dir.resolve("ep.collapsed"), "--stack", "java", "--exclude", "^ep_poll_\\[k\\]$"))
                .as("Unrendered kernel frames must be matched")
                .isEqualTo(netty);
        // The profiler's own tracing frames are not the thread's and match nothing.
        assertThat(stacks(profile, dir.resolve("trace.collapsed"), "--exclude", "__traceiter"))
                .as("Tracing frames must not be matched")
                .isEqualTo(all);
        // An unavailable stack matches as its placeholder frame.
        assertThat(stacks(profile, dir.resolve("nouser.collapsed"), "--exclude", "user stack unavailable"))
                .as("Unavailable stacks match their placeholder")
                .doesNotContain("app.Worker.park");

        // Repeated includes are a union, and an exclusion wins over an inclusion.
        String union =
                stacks(profile, dir.resolve("union.collapsed"), "--include", "futex_wait", "--include", "sched_yield");
        assertThat(union.lines()).as("Includes are a union: %s", union).hasSize(2);
        assertThat(union).as("Includes are a union").doesNotContain("epollWait0");
        Path none = dir.resolve("none.json");
        assertThat(stacks(
                        profile,
                        dir.resolve("none.collapsed"),
                        "--include",
                        "Thread.run",
                        "--exclude",
                        "Thread.run",
                        "--summary",
                        none.toString()))
                .as("Exclusion must win")
                .isEmpty();
        assertThat(summaryOf(none).getFiltered().getTotalNanos())
                .as("An empty slice still accounts for the time: %s", summaryOf(none))
                .isEqualTo("5500");

        // Only kept entries decide whether the slice mixes reasons.
        String parked = stacks(profile, dir.resolve("parked.collapsed"), "--include", "Worker\\.(park|spin)$");
        assertThat(parked.lines()).as("Two reasons remain: %s", parked).allMatch(line -> line.startsWith("[offcpu: "));
        String blockedOnly = stacks(profile, dir.resolve("park.collapsed"), "--include", "Worker\\.park$");
        assertThat(blockedOnly.lines())
                .as("A filter leaving one reason needs no reason frame: %s", blockedOnly)
                .hasSize(1);
        assertThat(blockedOnly)
                .as("A filter leaving one reason needs no reason frame")
                .startsWith("java.lang.Thread.run;app.Worker.park ");

        assertThatThrownBy(() -> stacks(profile, dir.resolve("bad.collapsed"), "--include", "("))
                .isInstanceOf(java.util.regex.PatternSyntaxException.class)
                .hasMessageContaining("Unclosed group");
        CommandLineFixture.usageError(
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
        assertThat(stacks(profile, dir.resolve("include-from.collapsed"), "--include-from", includes.toString()))
                .as("A pattern file must read like repeated --include options")
                .isEqualTo(union);
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
        assertThat(mixed).as("Inline and file patterns combine").isEmpty();
        assertThat(summaryOf(fromSummary).getIncludeList())
                .as("The summary lists the patterns read from files: %s", summaryOf(fromSummary))
                .containsExactly("futex_wait", "sched_yield");
        assertThat(summaryOf(fromSummary).getExcludeList())
                .as("The summary lists the patterns read from files: %s", summaryOf(fromSummary))
                .containsExactly("futex_wait", "sched_yield");
        // A pattern that starts with '#' is escaped, since such a line is a comment.
        assertThat(OffCpuCorrelator.patternFile(
                        "--exclude-from",
                        Files.writeString(dir.resolve("hash.txt"), "\\#x\n").toString()))
                .as("An escaped '#' pattern must survive")
                .containsExactly("\\#x");
        assertThat("#x").as("An escaped '#' pattern must match '#'").matches("\\#x");
        Path empty = Files.writeString(dir.resolve("empty.txt"), "# nothing\n\n");
        assertThatThrownBy(() -> stacks(profile, dir.resolve("empty.collapsed"), "--include-from", empty.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains no patterns");
        Path invalid = Files.writeString(dir.resolve("invalid.txt"), "futex_wait\n(\n");
        assertThatThrownBy(
                        () -> stacks(profile, dir.resolve("invalid.collapsed"), "--exclude-from", invalid.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 2");
        assertThatThrownBy(() -> stacks(
                        profile,
                        dir.resolve("absent.collapsed"),
                        "--exclude-from",
                        dir.resolve("absent.txt").toString()))
                .isInstanceOf(java.nio.file.NoSuchFileException.class)
                .hasMessageContaining("absent.txt");

        // A profile not grouped by kernel stacks cannot be filtered by them, and the summary says so.
        Path narrow = dir.resolve("narrow.pb");
        new StackProfile(
                        new StackProfile.Header(List.of(), List.of("reason"), false, null, "", List.of()),
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
        AnalysisProto.SliceSummary narrowCounts = summaryOf(narrowSummary);
        assertThat(narrowCounts.getFiltered().getIntervals())
                .as("Nothing to match: %s", narrowCounts)
                .isZero();
        assertThat(narrowCounts.getFilterScopeList())
                .as("Scope must show what was searched: %s", narrowCounts)
                .containsExactly("java");
    }
}
