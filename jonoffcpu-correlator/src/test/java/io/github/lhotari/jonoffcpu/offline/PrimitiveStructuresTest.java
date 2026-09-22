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

    public static void main(String[] args) throws Exception {
        unsigned();
        cookieIndex();
        System.out.println("Primitive structure fixtures passed");
    }
}
