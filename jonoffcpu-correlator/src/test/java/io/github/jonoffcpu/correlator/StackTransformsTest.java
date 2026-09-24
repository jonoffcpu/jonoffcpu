// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Frame-level transforms of {@code stacks}: each transform on hand-built stacks, then through the command line. */
class StackTransformsTest {
    private static List<StackProfile.Frame> java(String... names) {
        return Arrays.stream(names)
                .map(name -> new StackProfile.Frame(
                        StackTransforms.looksNative(name) || name.contains(" ")
                                ? StackProfile.Kind.JFR_NATIVE
                                : StackProfile.Kind.JAVA,
                        name,
                        ""))
                .toList();
    }

    private static List<StackTransforms.Sourced> inline(String... patterns) {
        return Arrays.stream(patterns)
                .map(pattern -> new StackTransforms.Sourced(pattern, "inline"))
                .toList();
    }

    private static List<StackTransforms.Sourced> preset(String name) throws IOException {
        return Cli.sourced(List.of(), "--collapse-leaf-from", List.of("preset:" + name));
    }

    private static StackTransforms transforms(
            List<StackTransforms.Sourced> hide,
            List<StackTransforms.Sourced> trimRoot,
            List<StackTransforms.Sourced> rootAt,
            List<StackTransforms.Sourced> leafAt,
            List<StackTransforms.Sourced> collapseLeaf,
            boolean category) {
        return new StackTransforms(
                false, hide, trimRoot, rootAt, false, leafAt, collapseLeaf, category, StackTransforms.ThreadFrame.NONE);
    }

    private static String apply(StackTransforms transforms, String stack) {
        return String.join(
                ";",
                transforms.compile().apply(java(stack.split(";"))).stream()
                        .map(StackProfile.Frame::name)
                        .toList());
    }

    /** The spec's unit cases, one transform at a time. */
    static Stream<Arguments> transformsInIsolation() throws IOException {
        List<StackTransforms.Sourced> none = List.of();
        StackTransforms trim = transforms(none, inline("^(A|B|C)$"), none, none, none, false);
        StackTransforms rootAt = transforms(none, none, inline("^x\\."), none, none, false);
        StackTransforms keep = new StackTransforms(
                false, none, none, inline("^x\\."), true, none, none, false, StackTransforms.ThreadFrame.NONE);
        StackTransforms leafAt = transforms(none, none, none, inline("^x\\."), none, false);
        StackTransforms hide = transforms(inline("^y\\."), none, none, none, none, false);
        List<StackTransforms.Sourced> machinery = preset("jvm-wait-machinery");
        StackTransforms collapse = transforms(none, none, none, none, machinery, false);
        StackTransforms category = transforms(none, none, none, none, machinery, true);
        String lock = "x.App.m;java.util.concurrent.locks.ReentrantLock.lock;"
                + "java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire;jdk.internal.misc.Unsafe.park;"
                + "libc.so.6.__futex_abstimed_wait_cancelable64";
        String monitor = "x.App.m;C2 Runtime complete_monitor_locking;libjvm.so.ObjectMonitor::enter;"
                + "libc.so.6.__GI___pthread_cond_wait";
        return Stream.of(
                Arguments.of("trim-root", trim, "A;B;C;x.App.m;D", "x.App.m;D"),
                // The run stops at the first frame that does not match: B here, A in the second case.
                Arguments.of(
                        "trim-root stops at B",
                        transforms(none, inline("^(A|C)$"), none, none, none, false),
                        "A;B;C;x.App.m;D",
                        "B;C;x.App.m;D"),
                Arguments.of(
                        "trim-root stops at A",
                        transforms(none, inline("^(B|C)$"), none, none, none, false),
                        "A;B;C;x.App.m;D",
                        "A;B;C;x.App.m;D"),
                Arguments.of("trim-root keeps the leaf", trim, "A;B;C", "C"),
                // Only the root-side run is trimmed: an application call into a matching frame keeps it.
                Arguments.of("trim-root only at the root", trim, "A;x.App.m;B", "x.App.m;B"),
                Arguments.of("root-at", rootAt, "T;x.App.a;y.Lib.b;x.App.c;L1;L2", "x.App.a;y.Lib.b;x.App.c;L1;L2"),
                Arguments.of(
                        "root-at without an application frame", rootAt, "T;L1", StackTransforms.NO_APPLICATION_FRAME),
                Arguments.of("root-at keeping unmatched", keep, "T;L1", "T;L1"),
                Arguments.of("leaf-at", leafAt, "T;x.App.a;y.Lib.b;x.App.c;L1;L2", "T;x.App.a;y.Lib.b;x.App.c"),
                Arguments.of("leaf-at without an application frame", leafAt, "T;L1", "T;L1"),
                Arguments.of("hide", hide, "T;x.App.a;y.Lib.b;x.App.c", "T;x.App.a;x.App.c"),
                Arguments.of("hide keeps the leaf", hide, "y.Lib.a;y.Lib.b", "y.Lib.b"),
                Arguments.of(
                        "collapse-leaf lock", collapse, lock, "x.App.m;java.util.concurrent.locks.ReentrantLock.lock"),
                Arguments.of("collapse-leaf category lock", category, lock, "x.App.m;[lock]"),
                Arguments.of("collapse-leaf category monitor", category, monitor, "x.App.m;[monitor]"),
                Arguments.of(
                        "collapse-leaf category park",
                        category,
                        "x.App.m;jdk.internal.misc.Unsafe.park;libjvm.so.Unsafe_Park",
                        "x.App.m;[park]"),
                Arguments.of(
                        "collapse-leaf category sleep",
                        category,
                        "x.App.m;java.lang.Thread.sleep;java.lang.Thread.sleepNanos0",
                        "x.App.m;[sleep]"),
                Arguments.of(
                        "collapse-leaf category wait", category, "x.App.m;java.lang.Object.wait0", "x.App.m;[wait]"),
                Arguments.of(
                        "collapse-leaf category native",
                        category,
                        "x.App.m;libc.so.6.read;vfs_read_[k]",
                        "x.App.m;[native]"),
                Arguments.of("collapse-leaf category kernel", category, "x.App.m;__schedule_[k]", "x.App.m;[kernel]"),
                // Nothing to collapse leaves the stack alone.
                Arguments.of("collapse-leaf with nothing to collapse", category, "x.App.m;x.App.n", "x.App.m;x.App.n"));
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource
    void transformsInIsolation(String transform, StackTransforms transforms, String stack, String expected) {
        assertThat(apply(transforms, stack))
                .as("%s must become %s", stack, expected)
                .isEqualTo(expected);
    }

    @Test
    void lambdasOfOneSiteCompare() {
        assertThat(StackTransforms.canonicalName("a.B$$Lambda.0x0000000081a06030.run"))
                .as("Two lambdas of one site must compare")
                .isEqualTo(StackTransforms.canonicalName("a.B$$Lambda.0x00000000819ed250.run"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "a.B$$Lambda.0x0000000081a06030.run, a.B$$Lambda.run",
        // Lambda forms lose their address.
        "java.lang.invoke.LambdaForm$MH/0x0000000800c01000.invoke, java.lang.invoke.LambdaForm$MH.invoke",
        // Pre-JDK 21 lambdas lose their index and address.
        "a.B$$Lambda$14/0x0000000800066840.run, a.B$$Lambda.run"
    })
    void canonicalName(String name, String canonical) {
        assertThat(StackTransforms.canonicalName(name)).isEqualTo(canonical);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        // A numbered thread.
        "pulsar-io-3-25, pulsar-io-#-#",
        // A thread id.
        "[tid=12345], [tid=#]"
    })
    void poolName(String thread, String pool) {
        assertThat(StackTransforms.poolName(thread)).isEqualTo(pool);
    }

    private static StackProfile.Entry entry(List<StackProfile.Frame> stack, String thread, long intervals, long nanos) {
        return new StackProfile.Entry(stack, null, null, OffCpuReason.BLOCKED, 1, thread, intervals, nanos, 0);
    }

    private static String stacks(Path dir, String name, String... args) throws Exception {
        Path output = dir.resolve(name + ".collapsed");
        List<String> command = new ArrayList<>(List.of("stacks", "--output", output.toString()));
        command.addAll(List.of(args));
        CommandLineFixture.Invocation invocation = CommandLineFixture.invoke(command.toArray(String[]::new));
        assertThat(invocation.code())
                .as("stacks %s failed: %s", List.of(args), invocation)
                .isZero();
        return Files.readString(output);
    }

    private static AnalysisProto.SliceSummary json(Path file) throws IOException {
        return CorrelationFixture.parse(file, AnalysisProto.SliceSummary.newBuilder())
                .build();
    }

    private static AnalysisProto.CollapsedSliceSummary collapsedJson(Path file) throws IOException {
        return CorrelationFixture.parse(file, AnalysisProto.CollapsedSliceSummary.newBuilder())
                .build();
    }

    /** The transforms through the command line: merging, totals, the filter order, presets and summaries. */
    @Test
    void commandLine(@TempDir Path dir) throws Exception {
        List<StackProfile.Entry> entries = List.of(
                entry(
                        java("java.lang.Thread.run", "io.netty.A.run", "x.App.m", "x.App.park"),
                        "pool-1-thread-1",
                        2,
                        2000),
                entry(
                        java("java.lang.Thread.run", "java.util.concurrent.B.run", "x.App.m", "x.App.park"),
                        "pool-1-thread-2",
                        3,
                        3000),
                entry(java("java.lang.Thread.run", "io.netty.Idle.wait"), "event-loop-7", 5, 7000),
                entry(java("x.Other$$Lambda.0x0000000081a06030.run", "x.App.n"), "worker-1", 1, 1000),
                entry(java("x.Other$$Lambda.0x00000000819ed250.run", "x.App.n"), "worker-2", 1, 1500));
        Path profile = dir.resolve("profile.pb");
        new StackProfile(
                        new StackProfile.Header(List.of(), List.of("reason", "thread"), false, null, "", List.of()),
                        entries)
                .write(profile);
        String untransformed = stacks(dir, "plain", "--profile", profile.toString());

        // Two entries that differ only in their infrastructure prefix merge; their intervals and time add.
        Path summary = dir.resolve("trim.json");
        String trimmed = stacks(
                dir,
                "trim",
                "--profile",
                profile.toString(),
                "--trim-root",
                "^java\\.lang\\.Thread\\.run$",
                "--trim-root",
                "^(io\\.netty|java\\.util\\.concurrent)\\.[A-Z]\\w*\\.run$",
                "--summary",
                summary.toString(),
                "--include",
                "x\\.App\\.park");
        assertThat(trimmed).as("Prefixes must merge").isEqualTo("x.App.m;x.App.park 5\n");
        AnalysisProto.Transforms transforms = json(summary).getTransforms();
        String summaryMessage = "The summary must report 2 lines becoming 1 and keep the totals: " + json(summary);
        assertThat(transforms.getLinesBefore()).as(summaryMessage).isEqualTo(2);
        assertThat(transforms.getLinesAfter()).as(summaryMessage).isEqualTo(1);
        assertThat(json(summary).getIntervals()).as(summaryMessage).isEqualTo(5);
        assertThat(json(summary).getTotalNanos()).as(summaryMessage).isEqualTo("5000");
        assertThat(transforms.getTrimRoot(0).getSource())
                .as("Each pattern names its source: %s", transforms)
                .isEqualTo("inline");

        // Filters see the untransformed stack: io.netty.A.run is trimmed away and still excludes its entry.
        String excluded = stacks(
                dir,
                "exclude",
                "--profile",
                profile.toString(),
                "--trim-root",
                "^(java\\.lang\\.Thread|io\\.netty\\.A|java\\.util\\.concurrent\\.B)\\.run$",
                "--exclude",
                "^io\\.netty\\.A\\.run$",
                "--include",
                "x\\.App\\.park");
        assertThat(excluded).as("--exclude must see removed frames").isEqualTo("x.App.m;x.App.park 3\n");

        // --root-at buckets entries without an application frame, and the summary accounts for them.
        Path rootSummary = dir.resolve("root.json");
        String rooted = stacks(
                dir,
                "root",
                "--profile",
                profile.toString(),
                "--root-at",
                "^x\\.App\\.",
                "--summary",
                rootSummary.toString());
        assertThat(rooted)
                .as("--root-at must re-root and bucket")
                .isEqualTo("[no application frame] 7\nx.App.m;x.App.park 5\nx.App.n 3\n");
        AnalysisProto.NoApplicationFrame bucket =
                json(rootSummary).getTransforms().getNoApplicationFrame();
        assertThat(bucket.getWeight())
                .as("The bucket's weight and share: %s", bucket)
                .isEqualTo("7000");
        assertThat(bucket.getShare())
                .as("The bucket's weight and share: %s", bucket)
                .isEqualTo("0.482759");

        // Canonical names merge two runs' lambdas.
        String canonical = stacks(
                dir, "canonical", "--profile", profile.toString(), "--canonical-names", "--include", "x\\.App\\.n");
        assertThat(canonical).as("Lambdas must merge").isEqualTo("x.Other$$Lambda.run;x.App.n 3\n");

        // The pool frame groups numbered threads.
        String pools = stacks(
                dir, "pool", "--profile", profile.toString(), "--thread-frame", "pool", "--root-at", "^x\\.App\\.");
        assertThat(pools)
                .as("Pool frames")
                .isEqualTo("event-loop-#;[no application frame] 7\npool-#-thread-#;x.App.m;x.App.park 5\n"
                        + "worker-#;x.App.n 3\n");

        // No transform, no change; and every combination keeps the totals.
        assertThat(stacks(dir, "plain-again", "--profile", profile.toString()))
                .as("Determinism")
                .isEqualTo(untransformed);
        Path all = dir.resolve("all.json");
        stacks(
                dir,
                "all",
                "--profile",
                profile.toString(),
                "--canonical-names",
                "--hide",
                "Idle",
                "--trim-root-from",
                "preset:jvm-infra",
                "--root-at",
                "^x\\.",
                "--leaf-at",
                "^x\\.App\\.m$",
                "--collapse-leaf-from",
                "preset:jvm-wait-machinery",
                "--collapse-leaf-label",
                "category",
                "--summary",
                all.toString());
        assertThat(json(all).getTotalNanos())
                .as("Transforms never change totals: %s", json(all))
                .isEqualTo("14500");
        assertThat(json(all).getIntervals())
                .as("Transforms never change totals: %s", json(all))
                .isEqualTo(12);

        // A thread frame needs the thread dimension.
        Path narrow = dir.resolve("narrow.pb");
        new StackProfile(
                        new StackProfile.Header(List.of(), List.of("reason"), false, null, "", List.of()),
                        entries.stream()
                                .map(entry -> entry(entry.javaStack(), null, entry.intervals(), entry.observedNanos()))
                                .toList())
                .write(narrow);
        assertThatThrownBy(() -> stacks(dir, "narrow", "--profile", narrow.toString(), "--thread-frame", "name"))
                .as("--thread-frame must need the thread dimension")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("thread");

        // Presets work in every -from option, including the filters, and are listed.
        CommandLineFixture.Invocation listing = CommandLineFixture.invoke("stacks", "--list-presets");
        assertThat(listing.code()).as("--list-presets failed: %s", listing).isZero();
        assertThat(listing.out())
                .as("--list-presets must list every preset")
                .contains("preset:jvm-infra", "preset:jvm-wait-machinery", "preset:jvm-idle");
        String idle = stacks(
                dir, "idle", "--profile", profile.toString(), "--exclude-from", "preset:jvm-idle", "--include", "x\\.");
        assertThat(idle)
                .as("preset:jvm-idle must be accepted by --exclude-from")
                .isNotEmpty();
        assertThatThrownBy(() -> stacks(dir, "unknown", "--profile", profile.toString(), "--hide-from", "preset:nope"))
                .as("An unknown preset must be refused")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown preset: nope");
        CommandLineFixture.usageError(
                "Give exactly one of --profile and --collapsed-input",
                "stacks",
                "--output",
                dir.resolve("neither").toString());
    }

    /** Any collapsed file: the converter's markers and slashes are normalised, and reason options are refused. */
    @Test
    void collapsedInput(@TempDir Path dir) throws Exception {
        Path input = Files.writeString(
                dir.resolve("converter-cpu.collapsed"),
                "java/lang/Thread.run_[0];org/apache/X.m_[j];__schedule_[k] 5\n"
                        + "java/lang/Thread.run_[0];org/apache/X.m_[i];I2C/C2I adapters 2.5\n"
                        + "java/lang/Thread.run_[0];io/netty/Y.run_[1] 3\n");
        Path summary = dir.resolve("cpu.json");
        String plain = stacks(dir, "cpu", "--collapsed-input", input.toString(), "--summary", summary.toString());
        assertThat(plain)
                .as("Collapsed input must be normalised")
                .isEqualTo("java.lang.Thread.run;io.netty.Y.run 3\n"
                        + "java.lang.Thread.run;org.apache.X.m;I2C/C2I adapters 2.5\n"
                        + "java.lang.Thread.run;org.apache.X.m;__schedule_[k] 5\n");
        // A collapsed input's summary is its own message, which the strict parse proves.
        assertThat(collapsedJson(summary).getTotalWeight())
                .as("The summary keeps the input's unit: %s", collapsedJson(summary))
                .isEqualTo("10.5");
        String rooted = stacks(
                dir,
                "cpu-root",
                "--collapsed-input",
                input.toString(),
                "--root-at",
                "^org\\.apache\\.",
                "--collapse-leaf-from",
                "preset:jvm-wait-machinery",
                "--package-names",
                "drop");
        assertThat(rooted)
                .as("Transforms apply to collapsed input")
                .isEqualTo("X.m;I2C/C2I adapters 2.5\nX.m;__schedule_[k] 5\n[no application frame] 3\n");
        String excluded = stacks(dir, "cpu-exclude", "--collapsed-input", input.toString(), "--exclude", "_\\[k\\]$");
        assertThat(excluded).as("Filters apply to collapsed input").doesNotContain("__schedule");
        CommandLineFixture.usageError(
                "--reason needs a stack profile",
                "stacks",
                "--collapsed-input",
                input.toString(),
                "--reason",
                "blocked",
                "--output",
                dir.resolve("refused").toString());
    }
}
