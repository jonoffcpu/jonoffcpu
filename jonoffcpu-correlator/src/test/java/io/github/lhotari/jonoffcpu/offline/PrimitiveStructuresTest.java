// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import io.github.lhotari.jonoffcpu.testing.FixtureSteps;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Fixtures for the primitive structures the columnar correlation engine is built from. */
public final class PrimitiveStructuresTest {
    private PrimitiveStructuresTest() {}

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void rejects(ThrowingRunnable operation, String fragment) {
        try {
            operation.run();
            throw new AssertionError("Expected rejection containing: " + fragment);
        } catch (IOException expected) {
            check(expected.getMessage().contains(fragment), "Unexpected rejection: " + expected);
        }
    }

    interface ThrowingRunnable {
        void run() throws IOException;
    }

    private static void unsigned() throws IOException {
        check(U64.big(-1L).equals(CaptureInput.U64_MAX), "u64 widening lost the top bit");
        check(U64.requireSigned(BigInteger.valueOf(5), "boundary") == 5L, "in-range value rejected");
        rejects(
                () -> U64.requireSigned(BigInteger.ONE.shiftLeft(63), "boundary"),
                "Monotonic timestamp exceeds signed 64-bit nanoseconds");
        rejects(() -> U64.add(Long.MAX_VALUE, 1L, "delay"), "signed 64-bit nanoseconds");
    }

    private static void cookieIndex() {
        LongIntMap index = new LongIntMap(4);
        check(index.get(0x8000000100000001L) == LongIntMap.ABSENT, "Empty index returned a slot");
        index.observe(0x8000000100000001L, 7);
        check(index.get(0x8000000100000001L) == 7, "Index lost the only slot for a cookie");
        index.observe(0x8000000100000001L, 9);
        check(index.get(0x8000000100000001L) == LongIntMap.DUPLICATE, "Second observation was not marked duplicate");
        index.observe(0x8000000100000001L, 11);
        check(index.get(0x8000000100000001L) == LongIntMap.DUPLICATE, "Duplicate marker was overwritten");
        // Zero is a legal key: an observation whose cookie decodes to zero must still land in a bucket.
        index.observe(0L, 3);
        check(index.get(0L) == 3, "Zero key was treated as an empty slot");
        index.observe(0L, 4);
        check(index.get(0L) == LongIntMap.DUPLICATE, "Zero key missed duplicate detection");
        // Growth must preserve every mapping, including the duplicate markers.
        LongIntMap grown = new LongIntMap(2);
        for (int slot = 0; slot < 10_000; slot++) grown.observe(0x8000000000000000L | slot, slot);
        for (int slot = 0; slot < 10_000; slot++) {
            check(grown.get(0x8000000000000000L | slot) == slot, "Growth lost slot " + slot);
        }
        check(grown.size() == 10_000, "Growth lost entries");
        check(grown.retainedBytes() >= 10_000L * 12, "Retention accounting is below the stored bytes");
    }

    private static java.util.Map<String, Object> frame(String className, String methodName, int line) {
        return frame("Interpreted", className, methodName, line);
    }

    private static java.util.Map<String, Object> frame(String type, String className, String methodName, int line) {
        java.util.Map<String, Object> frame = new java.util.LinkedHashMap<>();
        frame.put("type", type);
        frame.put("className", className);
        frame.put("methodName", methodName);
        frame.put("descriptor", "()V");
        frame.put("lineNumber", line);
        frame.put("bytecodeIndex", 0);
        return frame;
    }

    private static void dictionaries() throws IOException {
        JfrDictionaries dictionaries = new JfrDictionaries();
        java.util.List<java.util.Map<String, Object>> leafFirst =
                java.util.List.of(frame("a.Leaf", "run", 3), frame("a.Root", "main", 1));
        int first = dictionaries.internStack(leafFirst, false, 4096);
        int again = dictionaries.internStack(
                java.util.List.of(frame("a.Leaf", "run", 3), frame("a.Root", "main", 1)), false, 4096);
        check(first == again, "Equal stacks were not interned to one id");
        check(dictionaries.stackCount() == 1, "Interner retained a second copy of an equal stack");
        int truncated = dictionaries.internStack(leafFirst, true, 4096);
        check(truncated != first, "The truncation flag must separate canonical stacks");
        int differentLine = dictionaries.internStack(
                java.util.List.of(frame("a.Leaf", "run", 4), frame("a.Root", "main", 1)), false, 4096);
        check(differentLine != first, "A different line number must separate canonical stacks");
        // Root first, ';'-joined, class and method dotted: the collapsed key the flame graph reads.
        check(
                dictionaries.collapsedKey(dictionaries.collapsedOf(first)).equals("a.Root.main;a.Leaf.run"),
                "Collapsed key is not the root-first dotted form: "
                        + dictionaries.collapsedKey(dictionaries.collapsedOf(first)));
        // Line numbers do not belong in a collapsed key, so those two stacks share one collapsed id.
        check(
                dictionaries.collapsedOf(differentLine) == dictionaries.collapsedOf(first),
                "Collapsed ids must merge stacks that differ only below the collapsed key");
        check(dictionaries.collapsedCount() == 1, "Collapsed id space grew with canonical stacks");
        int empty = dictionaries.internStack(java.util.List.of(), false, 4096);
        check(
                dictionaries.collapsedKey(dictionaries.collapsedOf(empty)).equals("[stack unavailable]"),
                "An empty frame list must collapse to the unavailable marker");
        java.util.Map<String, Object> unresolved = frame(null, null, -1);
        int missing = dictionaries.internStack(java.util.List.of(unresolved), false, 4096);
        check(
                dictionaries.collapsedKey(dictionaries.collapsedOf(missing)).equals("[unresolved]"),
                "A frame with no class or method must collapse to [unresolved]");
        java.util.Map<String, Object> awkward = frame("a;b\nc", "run", 1);
        int escaped = dictionaries.internStack(java.util.List.of(awkward), false, 4096);
        check(
                dictionaries.collapsedKey(dictionaries.collapsedOf(escaped)).equals("a:b c.run"),
                "Separator and newline escaping changed");
        rejects(() -> dictionaries.internStack(leafFirst, false, 1), "Stack frame count limit exceeded");
        check(dictionaries.frames(first).length == 2, "Interner lost the retained frames");
        check(dictionaries.frames(first)[0].className().equals("a.Leaf"), "Frames must stay leaf-first");

        // Which frames of a collapsed key are Java comes from async-profiler's frame type, root first like the key.
        int typed = dictionaries.internStack(
                java.util.List.of(
                        frame("Kernel", null, "futex_wait", 0),
                        frame("C++", "libjvm.so", "Unsafe_Park", 0),
                        frame("Native", "libc.so.6", "__futex_abstimed_wait_cancelable64", 0),
                        frame(null, "b.Untyped", "run", 0),
                        frame("Inlined", "b.Inlined", "run", 0),
                        frame("C1 compiled", "b.C1", "run", 0),
                        frame("JIT compiled", "b.Jit", "run", 0),
                        frame("Interpreted", "b.Root", "main", 0)),
                false,
                4096);
        check(
                java.util.Arrays.equals(
                        dictionaries.collapsedJava(dictionaries.collapsedOf(typed)),
                        new boolean[] {true, true, true, true, false, false, false, false}),
                "Java frames must follow the frame type");
        check(
                java.util.Arrays.equals(
                        dictionaries.collapsedJava(dictionaries.collapsedOf(first)), new boolean[] {true, true}),
                "Interpreted frames are Java");
        // A stack that collapses to the same key with a native type at one frame makes that frame native.
        int sometimesNative = dictionaries.internStack(
                java.util.List.of(frame("Native", "a.Leaf", "run", 3), frame("a.Root", "main", 1)), false, 4096);
        check(
                dictionaries.collapsedOf(sometimesNative) == dictionaries.collapsedOf(first),
                "A frame type does not belong in a collapsed key");
        check(
                java.util.Arrays.equals(
                        dictionaries.collapsedJava(dictionaries.collapsedOf(first)), new boolean[] {true, false}),
                "A frame that is sometimes native must be native");
        check(
                java.util.Arrays.equals(
                        dictionaries.collapsedJava(dictionaries.collapsedOf(empty)), new boolean[] {false}),
                "The unavailable marker is not a Java frame");

        int thread = dictionaries.internThread(41L, 401L, "worker");
        check(dictionaries.internThread(41L, 401L, "worker") == thread, "Equal threads were not interned");
        check(dictionaries.internThread(42L, 401L, "worker") != thread, "Different OS thread ids were merged");
        check(dictionaries.thread(thread).name().equals("worker"), "Thread dictionary lost the name");
        check(dictionaries.internThread(null, null, null) >= 0, "A sample without a thread must still intern");
        check(dictionaries.retainedBytes() > 0, "Dictionary retention accounting is missing");
    }

    private static void columns() {
        SourceColumns sources = new SourceColumns(2);
        for (int slot = 0; slot < 1000; slot++) {
            sources.add(0x8000000100000000L | slot, 1000 + slot, 4000 + slot, 42949673L, 456, false, Reason.NONE);
        }
        check(sources.size() == 1000, "Source columns lost rows across growth");
        check(sources.cookie(999) == (0x8000000100000000L | 999), "Source columns lost a cookie across growth");
        check(sources.end(999) == 4999, "Source columns lost an end timestamp across growth");
        check(sources.reason(999) == Reason.NONE, "Default reason is not NONE");
        sources.reason(999, Reason.DUPLICATE_COOKIE);
        check(sources.reason(999) == Reason.DUPLICATE_COOKIE, "Reason column did not round-trip");
        check(sources.outcome(999) == Outcome.UNRESOLVED, "Default outcome is not UNRESOLVED");
        sources.outcome(999, Outcome.MATCHED);
        sources.verified(999, true);
        check(sources.outcome(999) == Outcome.MATCHED && sources.verified(999), "Outcome or verified bit lost");
        check(!sources.verified(998), "Verified bits leaked between rows");
        check(!sources.signalFailed(999), "A zero signal result must not read as a failed request");
        sources.add(0x8000000100000999L, 1, 2, 42949673L, 456, true, Reason.NONE);
        check(sources.signalFailed(1000) && !sources.signalFailed(999), "Failed-signal bits leaked between rows");
        check(sources.retainedBytes() >= 1000L * 38, "Source retention accounting is below the stored bytes");

        SampleColumns samples = new SampleColumns(2);
        for (int slot = 0; slot < 1000; slot++) samples.add(0x8000000100000000L | slot, 5000 + slot, 7L, 456, 1, 2);
        check(samples.size() == 1000 && samples.monotonicNanos(999) == 5999, "Sample columns lost rows");
        check(samples.stackId(999) == 1 && samples.threadId(999) == 2, "Sample dictionary ids lost");

        check(Reason.DUPLICATE_COOKIE.text().equals("duplicate-cookie"), "Reason text changed");
        check(Reason.NONE.text() == null, "A valid row must carry no reason text");
        check(
                Outcome.NOT_IN_SELECTED_JFR.text().equals("sample-not-present-in-selected-jfr"),
                "Unmatched reason text changed");
        check(Reason.of((byte) Reason.INVALID_PAIR.ordinal()) == Reason.INVALID_PAIR, "Reason byte decoding is wrong");
    }

    private static void budget() throws IOException {
        var tight = new OfflineCorrelator.Limits(2, 1024 * 1024, 1024 * 1024, 4096, null, null, null);
        CaptureInput.Budget rows = new CaptureInput.Budget(tight);
        rows.countRow();
        rows.countRow();
        rejects(rows::countRow, "Input row limit exceeded");

        var small = new OfflineCorrelator.Limits(100, 1024 * 1024, 4096, 4096, null, null, null);
        CaptureInput.Budget retained = new CaptureInput.Budget(small);
        // Structure retention replaces, rather than accumulates: it is the live size of the columns.
        retained.structures(2048);
        retained.structures(3072);
        check(retained.retained() == 3072, "Structure retention accumulated instead of replacing");
        check(retained.peak() == 3072, "Peak retention was not tracked");
        rejects(() -> retained.structures(8192), "Decoded input budget exceeded");
    }

    private static void quantum() throws IOException {
        long[] weights = {10_000, 3_000, 1};
        var exact = QuantumPlanner.plan(weights, 1000, 1_000_000);
        check(exact.quantumNanos() == 1000 && !exact.raised(), "A fitting quantum must not be raised");
        check(exact.syntheticEvents() == 13, "Event count is not the sum of per-stack floors");
        var raised = QuantumPlanner.plan(weights, 1000, 6);
        check(raised.raised() && raised.requestedQuantumNanos() == 1000, "The request must be reported as raised");
        check(raised.syntheticEvents() <= 6, "Raising the quantum did not bring the event count under the cap");
        check(raised.quantumNanos() > 1000, "The quantum was not raised");
        // A coarser quantum is a rendering choice: the totals it represents never grow.
        check(
                raised.syntheticEvents() * raised.quantumNanos() <= 13_001,
                "A coarser quantum represented more time than was measured");
        var empty = QuantumPlanner.plan(new long[0], 1000, 10);
        check(empty.syntheticEvents() == 0 && !empty.raised(), "An empty correlation needs no coarsening");
    }

    private static void thinning() {
        check(!Thinning.NONE.active() && Thinning.NONE.keeps(1234L), "q = 1 must keep every cookie");
        check(Thinning.NONE.scale(3000).equals(java.math.BigInteger.valueOf(3000)), "q = 1 must not reweight");
        Thinning tenth = Thinning.of("0.1", 0);
        check(tenth.active(), "q = 0.1 must be active");
        int kept = 0;
        for (long cookie = 1; cookie <= 200_000; cookie++) {
            if (tenth.keeps(0x8000000100000000L | cookie)) kept++;
        }
        // 200k draws at q = 0.1: five sigma is about 670, so this band cannot fail by chance.
        check(kept > 19_000 && kept < 21_000, "Thinning kept " + kept + " of 200000, which is not about a tenth");
        for (long cookie = 1; cookie <= 1000; cookie++) {
            check(
                    tenth.keeps(0x8000000100000000L | cookie)
                            == Thinning.of("0.1", 0).keeps(0x8000000100000000L | cookie),
                    "The keep decision must depend only on the cookie, the probability and the seed");
        }
        check(
                Thinning.of("0.1", 1).keeps(0x8000000100000001L) != tenth.keeps(0x8000000100000001L)
                        || Thinning.of("0.1", 1).threshold() == tenth.threshold(),
                "A different seed must be able to change a decision");
        // Reweighting is the exact reciprocal of the realised keep probability, not of the requested one.
        java.math.BigInteger realised = U64.big(tenth.threshold());
        java.math.BigInteger expected = java.math.BigInteger.valueOf(1000)
                .shiftLeft(64)
                .add(realised.shiftRight(1))
                .divide(realised);
        check(
                tenth.scale(1000).equals(expected),
                "Reweighting is not the exact reciprocal of the realised probability");
        check(
                tenth.scale(1000).compareTo(java.math.BigInteger.valueOf(9990)) > 0
                        && tenth.scale(1000).compareTo(java.math.BigInteger.valueOf(10010)) < 0,
                "q = 0.1 must scale a thousand observed nanoseconds to about ten thousand");
        check(tenth.scaleQuantum(1_000_000) < 1_000_000, "A thinned run must spend fewer observed nanos per event");
    }

    private static void estimate(Path dir) throws IOException {
        Path source = dir.resolve("estimate-source.bin");
        Path jfr = dir.resolve("estimate.jfr");
        Files.write(source, new byte[110_900_000]);
        Files.write(jfr, new byte[38_200_000]);
        RetentionEstimate estimate = RetentionEstimate.of(source, jfr);
        check(
                estimate.observations() > 1_000_000 && estimate.observations() < 1_300_000,
                "Observation estimate is not near the measured 99 bytes a record: " + estimate.observations());
        check(
                estimate.retainedBytes() > 100L << 20 && estimate.retainedBytes() < 400L << 20,
                "Retention estimate is outside the documented per-row constants: " + estimate.retainedBytes());
        // The guard should track the heap it protects, with a floor for a tiny one.
        check(RetentionEstimate.budget(0) >= 256L << 20, "Derived budget must not fall below the floor");
    }

    public static void main(String[] args) throws Exception {
        FixtureSteps.step("unsigned", () -> unsigned());
        FixtureSteps.step("cookieIndex", () -> cookieIndex());
        FixtureSteps.step("dictionaries", () -> dictionaries());
        FixtureSteps.step("columns", () -> columns());
        FixtureSteps.step("budget", () -> budget());
        FixtureSteps.step("quantum", () -> quantum());
        FixtureSteps.step("thinning", () -> thinning());
        Path dir = Files.createTempDirectory("jonoffcpu-primitive-structures-test-");
        try {
            FixtureSteps.step("estimate", () -> estimate(dir));
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Primitive structure fixtures passed");
    }
}
