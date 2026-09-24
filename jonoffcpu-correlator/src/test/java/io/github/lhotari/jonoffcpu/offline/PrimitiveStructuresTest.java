// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fixtures for the primitive structures the columnar correlation engine is built from. */
class PrimitiveStructuresTest {

    @Test
    void unsigned() throws IOException {
        assertThat(U64.big(-1L)).as("u64 widening lost the top bit").isEqualTo(CaptureInput.U64_MAX);
        assertThat(U64.requireSigned(BigInteger.valueOf(5), "boundary"))
                .as("in-range value rejected")
                .isEqualTo(5L);
        assertThatIOException()
                .isThrownBy(() -> U64.requireSigned(BigInteger.ONE.shiftLeft(63), "boundary"))
                .withMessageContaining("Monotonic timestamp exceeds signed 64-bit nanoseconds");
        assertThatIOException()
                .isThrownBy(() -> U64.add(Long.MAX_VALUE, 1L, "delay"))
                .withMessageContaining("signed 64-bit nanoseconds");
    }

    @Test
    void cookieIndex() {
        LongIntMap index = new LongIntMap(4);
        assertThat(index.get(0x8000000100000001L))
                .as("Empty index returned a slot")
                .isEqualTo(LongIntMap.ABSENT);
        index.observe(0x8000000100000001L, 7);
        assertThat(index.get(0x8000000100000001L))
                .as("Index lost the only slot for a cookie")
                .isEqualTo(7);
        index.observe(0x8000000100000001L, 9);
        assertThat(index.get(0x8000000100000001L))
                .as("Second observation was not marked duplicate")
                .isEqualTo(LongIntMap.DUPLICATE);
        index.observe(0x8000000100000001L, 11);
        assertThat(index.get(0x8000000100000001L))
                .as("Duplicate marker was overwritten")
                .isEqualTo(LongIntMap.DUPLICATE);
        // Zero is a legal key: an observation whose cookie decodes to zero must still land in a bucket.
        index.observe(0L, 3);
        assertThat(index.get(0L)).as("Zero key was treated as an empty slot").isEqualTo(3);
        index.observe(0L, 4);
        assertThat(index.get(0L)).as("Zero key missed duplicate detection").isEqualTo(LongIntMap.DUPLICATE);
        // Growth must preserve every mapping, including the duplicate markers.
        LongIntMap grown = new LongIntMap(2);
        for (int slot = 0; slot < 10_000; slot++) grown.observe(0x8000000000000000L | slot, slot);
        for (int slot = 0; slot < 10_000; slot++) {
            assertThat(grown.get(0x8000000000000000L | slot))
                    .as("Growth lost slot " + slot)
                    .isEqualTo(slot);
        }
        assertThat(grown.size()).as("Growth lost entries").isEqualTo(10_000);
        assertThat(grown.retainedBytes())
                .as("Retention accounting is below the stored bytes")
                .isGreaterThanOrEqualTo(10_000L * 12);
    }

    private static SignalProto.JfrFrame frame(String className, String methodName, int line) {
        return frame("Interpreted", className, methodName, line);
    }

    /** A frame as the exporter reads it; a null value is a field the JFR did not report. */
    private static SignalProto.JfrFrame frame(String type, String className, String methodName, int line) {
        SignalProto.JfrFrame.Builder frame =
                SignalProto.JfrFrame.newBuilder().setLineNumber(line).setBytecodeIndex(0);
        if (type != null) frame.setType(type);
        if (className != null) frame.setClassName(className);
        if (methodName != null) frame.setMethodName(methodName);
        return frame.setMethodDescriptor("()V").build();
    }

    @Test
    void dictionaries() throws IOException {
        JfrDictionaries dictionaries = new JfrDictionaries();
        List<SignalProto.JfrFrame> leafFirst = List.of(frame("a.Leaf", "run", 3), frame("a.Root", "main", 1));
        int first = dictionaries.internStack(leafFirst, false, 4096);
        int again =
                dictionaries.internStack(List.of(frame("a.Leaf", "run", 3), frame("a.Root", "main", 1)), false, 4096);
        assertThat(again).as("Equal stacks were not interned to one id").isEqualTo(first);
        assertThat(dictionaries.stackCount())
                .as("Interner retained a second copy of an equal stack")
                .isEqualTo(1);
        int truncated = dictionaries.internStack(leafFirst, true, 4096);
        assertThat(truncated)
                .as("The truncation flag must separate canonical stacks")
                .isNotEqualTo(first);
        int differentLine =
                dictionaries.internStack(List.of(frame("a.Leaf", "run", 4), frame("a.Root", "main", 1)), false, 4096);
        assertThat(differentLine)
                .as("A different line number must separate canonical stacks")
                .isNotEqualTo(first);
        // Root first, ';'-joined, class and method dotted: the collapsed key the flame graph reads.
        assertThat(dictionaries.collapsedKey(dictionaries.collapsedOf(first)))
                .as("Collapsed key is not the root-first dotted form")
                .isEqualTo("a.Root.main;a.Leaf.run");
        // Line numbers do not belong in a collapsed key, so those two stacks share one collapsed id.
        assertThat(dictionaries.collapsedOf(differentLine))
                .as("Collapsed ids must merge stacks that differ only below the collapsed key")
                .isEqualTo(dictionaries.collapsedOf(first));
        assertThat(dictionaries.collapsedCount())
                .as("Collapsed id space grew with canonical stacks")
                .isEqualTo(1);
        int empty = dictionaries.internStack(List.of(), false, 4096);
        assertThat(dictionaries.collapsedKey(dictionaries.collapsedOf(empty)))
                .as("An empty frame list must collapse to the unavailable marker")
                .isEqualTo("[stack unavailable]");
        SignalProto.JfrFrame unresolved = frame(null, null, -1);
        int missing = dictionaries.internStack(List.of(unresolved), false, 4096);
        assertThat(dictionaries.collapsedKey(dictionaries.collapsedOf(missing)))
                .as("A frame with no class or method must collapse to [unresolved]")
                .isEqualTo("[unresolved]");
        SignalProto.JfrFrame awkward = frame("a;b\nc", "run", 1);
        int escaped = dictionaries.internStack(List.of(awkward), false, 4096);
        assertThat(dictionaries.collapsedKey(dictionaries.collapsedOf(escaped)))
                .as("Separator and newline escaping changed")
                .isEqualTo("a:b c.run");
        assertThatIOException()
                .isThrownBy(() -> dictionaries.internStack(leafFirst, false, 1))
                .withMessageContaining("Stack frame count limit exceeded");
        assertThat(dictionaries.frames(first))
                .as("Interner lost the retained frames")
                .hasSize(2);
        assertThat(dictionaries.frames(first)[0].className())
                .as("Frames must stay leaf-first")
                .isEqualTo("a.Leaf");

        // Which frames of a collapsed key are Java comes from async-profiler's frame type, root first like the key.
        int typed = dictionaries.internStack(
                List.of(
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
        assertThat(dictionaries.collapsedJava(dictionaries.collapsedOf(typed)))
                .as("Java frames must follow the frame type")
                .containsExactly(true, true, true, true, false, false, false, false);
        assertThat(dictionaries.collapsedJava(dictionaries.collapsedOf(first)))
                .as("Interpreted frames are Java")
                .containsExactly(true, true);
        // A stack that collapses to the same key with a native type at one frame makes that frame native.
        int sometimesNative = dictionaries.internStack(
                List.of(frame("Native", "a.Leaf", "run", 3), frame("a.Root", "main", 1)), false, 4096);
        assertThat(dictionaries.collapsedOf(sometimesNative))
                .as("A frame type does not belong in a collapsed key")
                .isEqualTo(dictionaries.collapsedOf(first));
        assertThat(dictionaries.collapsedJava(dictionaries.collapsedOf(first)))
                .as("A frame that is sometimes native must be native")
                .containsExactly(true, false);
        assertThat(dictionaries.collapsedJava(dictionaries.collapsedOf(empty)))
                .as("The unavailable marker is not a Java frame")
                .containsExactly(false);

        int thread = dictionaries.internThread(41L, 401L, "worker");
        assertThat(dictionaries.internThread(41L, 401L, "worker"))
                .as("Equal threads were not interned")
                .isEqualTo(thread);
        assertThat(dictionaries.internThread(42L, 401L, "worker"))
                .as("Different OS thread ids were merged")
                .isNotEqualTo(thread);
        assertThat(dictionaries.thread(thread).name())
                .as("Thread dictionary lost the name")
                .isEqualTo("worker");
        assertThat(dictionaries.internThread(null, null, null))
                .as("A sample without a thread must still intern")
                .isNotNegative();
        assertThat(dictionaries.retainedBytes())
                .as("Dictionary retention accounting is missing")
                .isPositive();
    }

    @Test
    void columns() {
        SourceColumns sources = new SourceColumns(2);
        for (int slot = 0; slot < 1000; slot++) {
            sources.add(0x8000000100000000L | slot, 1000 + slot, 4000 + slot, 42949673L, 456, false, Reason.NONE);
        }
        assertThat(sources.size()).as("Source columns lost rows across growth").isEqualTo(1000);
        assertThat(sources.cookie(999))
                .as("Source columns lost a cookie across growth")
                .isEqualTo(0x8000000100000000L | 999);
        assertThat(sources.end(999))
                .as("Source columns lost an end timestamp across growth")
                .isEqualTo(4999);
        assertThat(sources.reason(999)).as("Default reason is not NONE").isEqualTo(Reason.NONE);
        sources.reason(999, Reason.DUPLICATE_COOKIE);
        assertThat(sources.reason(999)).as("Reason column did not round-trip").isEqualTo(Reason.DUPLICATE_COOKIE);
        assertThat(sources.outcome(999)).as("Default outcome is not UNRESOLVED").isEqualTo(Outcome.UNRESOLVED);
        sources.outcome(999, Outcome.MATCHED);
        sources.verified(999, true);
        assertThat(sources.outcome(999)).as("Outcome lost").isEqualTo(Outcome.MATCHED);
        assertThat(sources.verified(999)).as("Verified bit lost").isTrue();
        assertThat(sources.verified(998))
                .as("Verified bits leaked between rows")
                .isFalse();
        assertThat(sources.signalFailed(999))
                .as("A zero signal result must not read as a failed request")
                .isFalse();
        sources.add(0x8000000100000999L, 1, 2, 42949673L, 456, true, Reason.NONE);
        assertThat(sources.signalFailed(1000)).as("Failed-signal bit lost").isTrue();
        assertThat(sources.signalFailed(999))
                .as("Failed-signal bits leaked between rows")
                .isFalse();
        assertThat(sources.retainedBytes())
                .as("Source retention accounting is below the stored bytes")
                .isGreaterThanOrEqualTo(1000L * 38);

        SampleColumns samples = new SampleColumns(2);
        for (int slot = 0; slot < 1000; slot++) samples.add(0x8000000100000000L | slot, 5000 + slot, 7L, 456, 1, 2);
        assertThat(samples.size()).as("Sample columns lost rows").isEqualTo(1000);
        assertThat(samples.monotonicNanos(999)).as("Sample columns lost rows").isEqualTo(5999);
        assertThat(samples.stackId(999)).as("Sample dictionary ids lost").isEqualTo(1);
        assertThat(samples.threadId(999)).as("Sample dictionary ids lost").isEqualTo(2);

        assertThat(Reason.DUPLICATE_COOKIE.proto())
                .as("Reason vocabulary changed")
                .isEqualTo(ReportProto.RowReason.ROW_REASON_DUPLICATE_COOKIE);
        assertThat(Reason.NONE.proto()).as("A valid row must carry no reason").isNull();
        assertThat(Outcome.NOT_IN_SELECTED_JFR.proto())
                .as("Unmatched reason changed")
                .isEqualTo(ReportProto.RowReason.ROW_REASON_SAMPLE_NOT_PRESENT_IN_SELECTED_JFR);
        assertThat(Outcome.MATCHED.proto()).as("A match carries no reason").isNull();
        for (Reason reason : Reason.values()) {
            if (reason != Reason.NONE) {
                assertThat(reason.proto())
                        .as("Every invalid reason has a wire value: %s", reason)
                        .isNotNull();
            }
        }
        assertThat(Reason.of((byte) Reason.INVALID_PAIR.ordinal()))
                .as("Reason byte decoding is wrong")
                .isEqualTo(Reason.INVALID_PAIR);
    }

    @Test
    void budget() throws IOException {
        var tight = new OfflineCorrelator.Limits(2, 1024 * 1024, 1024 * 1024, 4096, null, null, null);
        CaptureInput.Budget rows = new CaptureInput.Budget(tight);
        rows.countRow();
        rows.countRow();
        assertThatIOException().isThrownBy(rows::countRow).withMessageContaining("Input row limit exceeded");

        var small = new OfflineCorrelator.Limits(100, 1024 * 1024, 4096, 4096, null, null, null);
        CaptureInput.Budget retained = new CaptureInput.Budget(small);
        // Structure retention replaces, rather than accumulates: it is the live size of the columns.
        retained.structures(2048);
        retained.structures(3072);
        assertThat(retained.retained())
                .as("Structure retention accumulated instead of replacing")
                .isEqualTo(3072);
        assertThat(retained.peak()).as("Peak retention was not tracked").isEqualTo(3072);
        assertThatIOException()
                .isThrownBy(() -> retained.structures(8192))
                .withMessageContaining("Decoded input budget exceeded");
    }

    @Test
    void quantum() throws IOException {
        long[] weights = {10_000, 3_000, 1};
        var exact = QuantumPlanner.plan(weights, 1000, 1_000_000);
        assertThat(exact.quantumNanos()).as("A fitting quantum must not change").isEqualTo(1000);
        assertThat(exact.raised()).as("A fitting quantum must not be raised").isFalse();
        assertThat(exact.syntheticEvents())
                .as("Event count is not the sum of per-stack floors")
                .isEqualTo(13);
        var raised = QuantumPlanner.plan(weights, 1000, 6);
        assertThat(raised.raised()).as("The request must be reported as raised").isTrue();
        assertThat(raised.requestedQuantumNanos())
                .as("The request must be reported as raised")
                .isEqualTo(1000);
        assertThat(raised.syntheticEvents())
                .as("Raising the quantum did not bring the event count under the cap")
                .isLessThanOrEqualTo(6);
        assertThat(raised.quantumNanos()).as("The quantum was not raised").isGreaterThan(1000);
        // A coarser quantum is a rendering choice: the totals it represents never grow.
        assertThat(raised.syntheticEvents() * raised.quantumNanos())
                .as("A coarser quantum represented more time than was measured")
                .isLessThanOrEqualTo(13_001);
        var empty = QuantumPlanner.plan(new long[0], 1000, 10);
        assertThat(empty.syntheticEvents())
                .as("An empty correlation needs no events")
                .isZero();
        assertThat(empty.raised())
                .as("An empty correlation needs no coarsening")
                .isFalse();
    }

    @Test
    void thinning() {
        assertThat(Thinning.NONE.active()).as("q = 1 must not be active").isFalse();
        assertThat(Thinning.NONE.keeps(1234L))
                .as("q = 1 must keep every cookie")
                .isTrue();
        assertThat(Thinning.NONE.scale(3000)).as("q = 1 must not reweight").isEqualTo(BigInteger.valueOf(3000));
        Thinning tenth = Thinning.of("0.1", 0);
        assertThat(tenth.active()).as("q = 0.1 must be active").isTrue();
        int kept = 0;
        for (long cookie = 1; cookie <= 200_000; cookie++) {
            if (tenth.keeps(0x8000000100000000L | cookie)) kept++;
        }
        // 200k draws at q = 0.1: five sigma is about 670, so this band cannot fail by chance.
        assertThat(kept)
                .as("Thinning kept %d of 200000, which is not about a tenth", kept)
                .isStrictlyBetween(19_000, 21_000);
        for (long cookie = 1; cookie <= 1000; cookie++) {
            assertThat(tenth.keeps(0x8000000100000000L | cookie))
                    .as("The keep decision must depend only on the cookie, the probability and the seed")
                    .isEqualTo(Thinning.of("0.1", 0).keeps(0x8000000100000000L | cookie));
        }
        assertThat(Thinning.of("0.1", 1).keeps(0x8000000100000001L) != tenth.keeps(0x8000000100000001L)
                        || Thinning.of("0.1", 1).threshold() == tenth.threshold())
                .as("A different seed must be able to change a decision")
                .isTrue();
        // Reweighting is the exact reciprocal of the realised keep probability, not of the requested one.
        BigInteger realised = U64.big(tenth.threshold());
        BigInteger expected = BigInteger.valueOf(1000)
                .shiftLeft(64)
                .add(realised.shiftRight(1))
                .divide(realised);
        assertThat(tenth.scale(1000))
                .as("Reweighting is not the exact reciprocal of the realised probability")
                .isEqualTo(expected);
        assertThat(tenth.scale(1000))
                .as("q = 0.1 must scale a thousand observed nanoseconds to about ten thousand")
                .isStrictlyBetween(BigInteger.valueOf(9990), BigInteger.valueOf(10010));
        assertThat(tenth.scaleQuantum(1_000_000))
                .as("A thinned run must spend fewer observed nanos per event")
                .isLessThan(1_000_000);
    }

    @Test
    void estimate(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("estimate-source.bin");
        Path jfr = dir.resolve("estimate.jfr");
        Files.write(source, new byte[110_900_000]);
        Files.write(jfr, new byte[38_200_000]);
        RetentionEstimate estimate = RetentionEstimate.of(source, jfr);
        assertThat(estimate.observations())
                .as("Observation estimate is not near the measured 99 bytes a record")
                .isStrictlyBetween(1_000_000L, 1_300_000L);
        assertThat(estimate.retainedBytes())
                .as("Retention estimate is outside the documented per-row constants")
                .isStrictlyBetween(100L << 20, 400L << 20);
        // The guard should track the heap it protects, with a floor for a tiny one.
        assertThat(RetentionEstimate.budget(0))
                .as("Derived budget must not fall below the floor")
                .isGreaterThanOrEqualTo(256L << 20);
    }
}
