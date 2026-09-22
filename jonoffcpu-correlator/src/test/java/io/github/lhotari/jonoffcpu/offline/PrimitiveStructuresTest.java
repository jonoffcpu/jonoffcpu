// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import java.io.IOException;
import java.math.BigInteger;

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
        check(U64.gt(-1L, 1L), "u64 comparison used signed order");
        check(U64.lt(1L, -1L), "u64 comparison used signed order");
        check(U64.max(-1L, 1L) == -1L, "u64 max used signed order");
        check(U64.min(-1L, 1L) == 1L, "u64 min used signed order");
        check(U64.big(-1L).equals(CaptureInput.U64_MAX), "u64 widening lost the top bit");
        check(U64.difference(3000L, 1000L, "interval") == 2000L, "u64 difference is wrong");
        rejects(() -> U64.difference(-1L, 0L, "interval"), "signed 64-bit nanoseconds");
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
        java.util.Map<String, Object> frame = new java.util.LinkedHashMap<>();
        frame.put("type", "Interpreted");
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

    public static void main(String[] args) throws Exception {
        unsigned();
        cookieIndex();
        dictionaries();
        columns();
        System.out.println("Primitive structure fixtures passed");
    }
}
