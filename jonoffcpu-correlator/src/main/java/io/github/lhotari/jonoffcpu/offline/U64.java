// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import java.io.IOException;
import java.math.BigInteger;

/**
 * Unsigned 64-bit helpers for the columnar engine. Capture timestamps, cookies and durations are
 * u64 by construction, so the columns hold their raw bits and every ordering question goes through
 * here rather than through the JVM's signed comparison.
 *
 * <p>Arithmetic on those bits is plain signed {@code long}, which is exact as long as the operands
 * stay below {@code 2^63}. The engine enforces that up front with {@link #requireSigned}: {@code
 * 2^63} nanoseconds is 292 years, so the only stream that can violate it is a corrupt one, and it
 * is rejected by name instead of wrapping silently.
 */
final class U64 {
    static final String MONOTONIC_RANGE = "Monotonic timestamp exceeds signed 64-bit nanoseconds";
    private static final BigInteger SIGNED_LIMIT = BigInteger.ONE.shiftLeft(63);

    private U64() {}

    static int compare(long left, long right) {
        return Long.compareUnsigned(left, right);
    }

    static boolean gt(long left, long right) {
        return Long.compareUnsigned(left, right) > 0;
    }

    static boolean lt(long left, long right) {
        return Long.compareUnsigned(left, right) < 0;
    }

    static boolean ge(long left, long right) {
        return Long.compareUnsigned(left, right) >= 0;
    }

    static boolean le(long left, long right) {
        return Long.compareUnsigned(left, right) <= 0;
    }

    static long max(long left, long right) {
        return gt(left, right) ? left : right;
    }

    static long min(long left, long right) {
        return lt(left, right) ? left : right;
    }

    /** The unsigned value of raw column bits, for report fields that must stay exact decimal text. */
    static BigInteger big(long bits) {
        return bits >= 0 ? BigInteger.valueOf(bits) : BigInteger.valueOf(bits).add(BigInteger.ONE.shiftLeft(64));
    }

    /** A clock value that must fit a nonnegative signed long, so ordinary arithmetic is exact. */
    static long requireSigned(BigInteger value, String label) throws IOException {
        CaptureInput.require(value.signum() >= 0 && value.compareTo(SIGNED_LIMIT) < 0, MONOTONIC_RANGE + ": " + label);
        return value.longValueExact();
    }

    /** The same rule applied to raw u64 bits that came from protobuf rather than decimal text. */
    static long requireSigned(long bits, String label) throws IOException {
        CaptureInput.require(bits >= 0, MONOTONIC_RANGE + ": " + label);
        return bits;
    }

    /** A signed clock offset that must fit a signed long in either direction. */
    static long requireSignedOffset(BigInteger value, String label) throws IOException {
        CaptureInput.require(value.abs().compareTo(SIGNED_LIMIT) < 0, MONOTONIC_RANGE + ": " + label);
        return value.longValueExact();
    }

    /** {@code left - right} for operands already in signed range, rejected when the result is negative. */
    static long difference(long left, long right, String label) throws IOException {
        long value = left - right;
        CaptureInput.require(left >= 0 && right >= 0 && value >= 0, label + " exceeds signed 64-bit nanoseconds");
        return value;
    }

    /** Checked addition; the caller names the accumulator so a corrupt stream fails closed by name. */
    static long add(long left, long right, String label) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException error) {
            throw new IOException(label + " exceeds signed 64-bit nanoseconds", error);
        }
    }
}
