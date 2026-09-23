// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lhotari.jonoffcpu.testing.FixtureSteps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Frame-level transforms of {@code stacks}: each transform on hand-built stacks, then through the command line. */
public final class StackTransformsTest {
    private StackTransformsTest() {}

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

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

    private static void expect(StackTransforms transforms, String stack, String expected) {
        String actual = apply(transforms, stack);
        check(actual.equals(expected), stack + " must become " + expected + ", was " + actual);
    }

    /** The spec's unit cases, one transform at a time. */
    private static void transformsInIsolation() throws IOException {
        List<StackTransforms.Sourced> none = List.of();
        StackTransforms trim = transforms(none, inline("^(A|B|C)$"), none, none, none, false);
        expect(trim, "A;B;C;x.App.m;D", "x.App.m;D");
        // The run stops at the first frame that does not match: B here, A in the second case.
        expect(transforms(none, inline("^(A|C)$"), none, none, none, false), "A;B;C;x.App.m;D", "B;C;x.App.m;D");
        expect(transforms(none, inline("^(B|C)$"), none, none, none, false), "A;B;C;x.App.m;D", "A;B;C;x.App.m;D");
        expect(trim, "A;B;C", "C");
        // Only the root-side run is trimmed: an application call into a matching frame keeps it.
        expect(trim, "A;x.App.m;B", "x.App.m;B");

        StackTransforms rootAt = transforms(none, none, inline("^x\\."), none, none, false);
        expect(rootAt, "T;x.App.a;y.Lib.b;x.App.c;L1;L2", "x.App.a;y.Lib.b;x.App.c;L1;L2");
        expect(rootAt, "T;L1", StackTransforms.NO_APPLICATION_FRAME);
        StackTransforms keep = new StackTransforms(
                false, none, none, inline("^x\\."), true, none, none, false, StackTransforms.ThreadFrame.NONE);
        expect(keep, "T;L1", "T;L1");
        StackTransforms leafAt = transforms(none, none, none, inline("^x\\."), none, false);
        expect(leafAt, "T;x.App.a;y.Lib.b;x.App.c;L1;L2", "T;x.App.a;y.Lib.b;x.App.c");
        expect(leafAt, "T;L1", "T;L1");

        StackTransforms hide = transforms(inline("^y\\."), none, none, none, none, false);
        expect(hide, "T;x.App.a;y.Lib.b;x.App.c", "T;x.App.a;x.App.c");
        expect(hide, "y.Lib.a;y.Lib.b", "y.Lib.b");

        String lock = "x.App.m;java.util.concurrent.locks.ReentrantLock.lock;"
                + "java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire;jdk.internal.misc.Unsafe.park;"
                + "libc.so.6.__futex_abstimed_wait_cancelable64";
        List<StackTransforms.Sourced> machinery = preset("jvm-wait-machinery");
        expect(
                transforms(none, none, none, none, machinery, false),
                lock,
                "x.App.m;java.util.concurrent.locks.ReentrantLock.lock");
        expect(transforms(none, none, none, none, machinery, true), lock, "x.App.m;[lock]");
        String monitor = "x.App.m;C2 Runtime complete_monitor_locking;libjvm.so.ObjectMonitor::enter;"
                + "libc.so.6.__GI___pthread_cond_wait";
        expect(transforms(none, none, none, none, machinery, true), monitor, "x.App.m;[monitor]");
        expect(
                transforms(none, none, none, none, machinery, true),
                "x.App.m;jdk.internal.misc.Unsafe.park;libjvm.so.Unsafe_Park",
                "x.App.m;[park]");
        expect(
                transforms(none, none, none, none, machinery, true),
                "x.App.m;java.lang.Thread.sleep;java.lang.Thread.sleepNanos0",
                "x.App.m;[sleep]");
        expect(transforms(none, none, none, none, machinery, true), "x.App.m;java.lang.Object.wait0", "x.App.m;[wait]");
        expect(
                transforms(none, none, none, none, machinery, true),
                "x.App.m;libc.so.6.read;vfs_read_[k]",
                "x.App.m;[native]");
        expect(transforms(none, none, none, none, machinery, true), "x.App.m;__schedule_[k]", "x.App.m;[kernel]");
        // Nothing to collapse leaves the stack alone.
        expect(transforms(none, none, none, none, machinery, true), "x.App.m;x.App.n", "x.App.m;x.App.n");

        check(
                StackTransforms.canonicalName("a.B$$Lambda.0x0000000081a06030.run")
                        .equals(StackTransforms.canonicalName("a.B$$Lambda.0x00000000819ed250.run")),
                "Two lambdas of one site must compare");
        check(
                StackTransforms.canonicalName("a.B$$Lambda.0x0000000081a06030.run")
                        .equals("a.B$$Lambda.run"),
                StackTransforms.canonicalName("a.B$$Lambda.0x0000000081a06030.run"));
        check(
                StackTransforms.canonicalName("java.lang.invoke.LambdaForm$MH/0x0000000800c01000.invoke")
                        .equals("java.lang.invoke.LambdaForm$MH.invoke"),
                "Lambda forms lose their address");
        check(
                StackTransforms.canonicalName("a.B$$Lambda$14/0x0000000800066840.run")
                        .equals("a.B$$Lambda.run"),
                "Pre-JDK 21 lambdas lose their index and address");
        check(StackTransforms.poolName("pulsar-io-3-25").equals("pulsar-io-#-#"), "Pool of a numbered thread");
        check(StackTransforms.poolName("[tid=12345]").equals("[tid=#]"), "Pool of a thread id");
    }

    private static StackProfile.Entry entry(List<StackProfile.Frame> stack, String thread, long intervals, long nanos) {
        return new StackProfile.Entry(stack, null, null, OffCpuReason.BLOCKED, 1, thread, intervals, nanos, 0);
    }

    private static String stacks(Path dir, String name, String... args) throws Exception {
        Path output = dir.resolve(name + ".collapsed");
        List<String> command = new ArrayList<>(List.of("stacks", "--output", output.toString()));
        command.addAll(List.of(args));
        CommandLineTest.Invocation invocation = CommandLineTest.invoke(command.toArray(String[]::new));
        check(invocation.code() == 0, "stacks " + List.of(args) + " failed: " + invocation);
        return Files.readString(output);
    }

    private static JsonObject json(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    /** The transforms through the command line: merging, totals, the filter order, presets and summaries. */
    private static void commandLine(Path dir) throws Exception {
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
                        new StackProfile.Header(List.of(), List.of("reason", "thread"), false, "{}", "", List.of()),
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
        check(trimmed.equals("x.App.m;x.App.park 5\n"), "Prefixes must merge: " + trimmed);
        JsonObject transforms = json(summary).getAsJsonObject("transforms");
        check(
                transforms.get("linesBefore").getAsInt() == 2
                        && transforms.get("linesAfter").getAsInt() == 1
                        && json(summary).get("intervals").getAsString().equals("5")
                        && json(summary).get("totalNanos").getAsString().equals("5000"),
                "The summary must report 2 lines becoming 1 and keep the totals: " + json(summary));
        check(
                transforms
                        .getAsJsonArray("trimRoot")
                        .get(0)
                        .getAsJsonObject()
                        .get("source")
                        .getAsString()
                        .equals("inline"),
                "Each pattern names its source: " + transforms);

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
        check(excluded.equals("x.App.m;x.App.park 3\n"), "--exclude must see removed frames: " + excluded);

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
        check(
                rooted.equals("[no application frame] 7\nx.App.m;x.App.park 5\nx.App.n 3\n"),
                "--root-at must re-root and bucket: " + rooted);
        JsonObject bucket = json(rootSummary).getAsJsonObject("transforms").getAsJsonObject("noApplicationFrame");
        check(
                bucket.get("weight").getAsString().equals("7000")
                        && bucket.get("share").getAsString().equals("0.482759"),
                "The bucket's weight and share: " + bucket);

        // Canonical names merge two runs' lambdas.
        String canonical = stacks(
                dir, "canonical", "--profile", profile.toString(), "--canonical-names", "--include", "x\\.App\\.n");
        check(canonical.equals("x.Other$$Lambda.run;x.App.n 3\n"), "Lambdas must merge: " + canonical);

        // The pool frame groups numbered threads.
        String pools = stacks(
                dir, "pool", "--profile", profile.toString(), "--thread-frame", "pool", "--root-at", "^x\\.App\\.");
        check(
                pools.equals("event-loop-#;[no application frame] 7\npool-#-thread-#;x.App.m;x.App.park 5\n"
                        + "worker-#;x.App.n 3\n"),
                "Pool frames: " + pools);

        // No transform, no change; and every combination keeps the totals.
        check(untransformed.equals(stacks(dir, "plain-again", "--profile", profile.toString())), "Determinism");
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
        check(
                json(all).get("totalNanos").getAsString().equals("14500")
                        && json(all).get("intervals").getAsString().equals("12"),
                "Transforms never change totals: " + json(all));

        // A thread frame needs the thread dimension.
        Path narrow = dir.resolve("narrow.pb");
        new StackProfile(
                        new StackProfile.Header(List.of(), List.of("reason"), false, "{}", "", List.of()),
                        entries.stream()
                                .map(entry -> entry(entry.javaStack(), null, entry.intervals(), entry.observedNanos()))
                                .toList())
                .write(narrow);
        try {
            stacks(dir, "narrow", "--profile", narrow.toString(), "--thread-frame", "name");
            throw new AssertionError("--thread-frame must need the thread dimension");
        } catch (IOException expected) {
            check(expected.getMessage().contains("thread"), "Unexpected failure: " + expected);
        }

        // Presets work in every -from option, including the filters, and are listed.
        CommandLineTest.Invocation listing = CommandLineTest.invoke("stacks", "--list-presets");
        check(
                listing.code() == 0
                        && listing.out().contains("preset:jvm-infra")
                        && listing.out().contains("preset:jvm-wait-machinery")
                        && listing.out().contains("preset:jvm-idle"),
                "--list-presets must list every preset: " + listing);
        String idle = stacks(
                dir, "idle", "--profile", profile.toString(), "--exclude-from", "preset:jvm-idle", "--include", "x\\.");
        check(!idle.isEmpty(), "preset:jvm-idle must be accepted by --exclude-from");
        try {
            stacks(dir, "unknown", "--profile", profile.toString(), "--hide-from", "preset:nope");
            throw new AssertionError("An unknown preset must be refused");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("Unknown preset: nope"), "Unexpected failure: " + expected);
        }
        CommandLineTest.usageError(
                "Give exactly one of --profile and --collapsed-input",
                "stacks",
                "--output",
                dir.resolve("neither").toString());
    }

    /** Any collapsed file: the converter's markers and slashes are normalised, and reason options are refused. */
    private static void collapsedInput(Path dir) throws Exception {
        Path input = Files.writeString(
                dir.resolve("converter-cpu.collapsed"),
                "java/lang/Thread.run_[0];org/apache/X.m_[j];__schedule_[k] 5\n"
                        + "java/lang/Thread.run_[0];org/apache/X.m_[i];I2C/C2I adapters 2.5\n"
                        + "java/lang/Thread.run_[0];io/netty/Y.run_[1] 3\n");
        Path summary = dir.resolve("cpu.json");
        String plain = stacks(dir, "cpu", "--collapsed-input", input.toString(), "--summary", summary.toString());
        check(
                plain.equals("java.lang.Thread.run;io.netty.Y.run 3\n"
                        + "java.lang.Thread.run;org.apache.X.m;I2C/C2I adapters 2.5\n"
                        + "java.lang.Thread.run;org.apache.X.m;__schedule_[k] 5\n"),
                "Collapsed input must be normalised: " + plain);
        check(
                json(summary).get("input").getAsString().equals("collapsed")
                        && json(summary).get("totalWeight").getAsString().equals("10.5"),
                "The summary keeps the input's unit: " + json(summary));
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
        check(
                rooted.equals("X.m;I2C/C2I adapters 2.5\nX.m;__schedule_[k] 5\n[no application frame] 3\n"),
                "Transforms apply to collapsed input: " + rooted);
        String excluded = stacks(dir, "cpu-exclude", "--collapsed-input", input.toString(), "--exclude", "_\\[k\\]$");
        check(!excluded.contains("__schedule"), "Filters apply to collapsed input: " + excluded);
        CommandLineTest.usageError(
                "--reason needs a stack profile",
                "stacks",
                "--collapsed-input",
                input.toString(),
                "--reason",
                "blocked",
                "--output",
                dir.resolve("refused").toString());
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jonoffcpu-transforms-test-");
        try {
            FixtureSteps.step("transformsInIsolation", () -> transformsInIsolation());
            FixtureSteps.step("commandLine", () -> commandLine(dir));
            FixtureSteps.step("collapsedInput", () -> collapsedInput(dir));
            System.out.println("Stack transform fixtures passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
