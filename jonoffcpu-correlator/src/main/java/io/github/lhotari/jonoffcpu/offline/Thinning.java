// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * A correlation-time second sampling stage: keep each recorded interval with probability {@code q}
 * and scale the duration it contributes by {@code 1/q}.
 *
 * <p>The capture is already a sample of the intervals by design, and every observation carries the
 * exact admission threshold the kernel drew against, so this is the same kind of object with a
 * stated estimator rather than a fudge. The result is an unbiased estimate of the same per-stack
 * totals over the whole requested window, with variance that falls as {@code q} rises: a flame
 * graph whose towers are in the right proportion, built from a tenth of the records.
 *
 * <p>The decision hashes the cookie, so it is deterministic, order-independent and reproducible,
 * and because the cookie is the join key it drops an observation and its JFR sample together —
 * which keeps the matched, unmatched and orphan counts describing one coherent subsample.
 *
 * <p>This changes what the collapsed weights <em>are</em>: observed durations become estimates. The
 * report therefore carries {@link #report()}, and {@code q = 1} stays the default whenever the
 * input fits.
 */
record Thinning(long threshold, long seed, String probability) {
    /** Keeps every interval and scales by one. */
    static final Thinning NONE = new Thinning(0L, 0L, "1");

    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

    static Thinning of(String probability, long seed) {
        BigDecimal value = new BigDecimal(probability);
        if (value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Thinning probability must be in (0, 1]: " + probability);
        }
        if (value.compareTo(BigDecimal.ONE) == 0) return NONE;
        BigInteger scaled = value.multiply(new BigDecimal(TWO_64))
                .setScale(0, RoundingMode.FLOOR)
                .toBigInteger();
        if (scaled.signum() <= 0) {
            throw new IllegalArgumentException("Thinning probability is below one part in 2^64: " + probability);
        }
        return new Thinning(scaled.longValue(), seed, probability);
    }

    boolean active() {
        return threshold != 0;
    }

    /** The exact realised keep probability, which is the one the reweighting inverts. */
    private BigInteger realisedNumerator() {
        return U64.big(threshold);
    }

    boolean keeps(long cookie) {
        return !active() || Long.compareUnsigned(mix(cookie ^ seed), threshold) < 0;
    }

    /** {@code observed / q}, rounded half up, in exact integer arithmetic. */
    BigInteger scale(long observedNanos) {
        if (!active()) return BigInteger.valueOf(observedNanos);
        BigInteger denominator = realisedNumerator();
        return BigInteger.valueOf(observedNanos)
                .shiftLeft(64)
                .add(denominator.shiftRight(1))
                .divide(denominator);
    }

    /**
     * The observed nanoseconds one synthetic event should consume so that it still represents
     * {@code requestedQuantumNanos} of estimated time. At least one, so a quantum never vanishes.
     */
    long scaleQuantum(long requestedQuantumNanos) {
        if (!active()) return requestedQuantumNanos;
        return Math.max(
                1L,
                BigInteger.valueOf(requestedQuantumNanos)
                        .multiply(realisedNumerator())
                        .shiftRight(64)
                        .longValueExact());
    }

    ReportProto.SourceThinning report() {
        return ReportProto.SourceThinning.newBuilder()
                .setQ(probability)
                .setRealisedProbabilityNumerator(realisedNumerator().toString())
                .setRealisedProbabilityDenominator(TWO_64.toString())
                .setSeed(seed)
                .setEstimator("inverse-probability")
                .build();
    }

    /** SplitMix64's finalizer: the cookie's low half is a sequence number, so the raw bits cluster. */
    private static long mix(long key) {
        long value = key;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
