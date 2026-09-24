// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

import static io.github.jonoffcpu.jonoffcpu.offline.CaptureInput.require;

import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * The capture's resolved sampling policy as the agent recorded it in {@code capture_start.sampling}: the
 * switch-out reasons the kernel kept, optional strict duration bounds in microseconds, and the admission policy
 * the kernel applied to eligible intervals. The per-row {@code admission_threshold} is recomputed from this policy
 * exactly as the collector computes it.
 */
record SamplingPolicy(
        List<OffCpuReason> reasons,
        BigInteger minOffCpuNanos,
        BigInteger maxOffCpuNanos,
        String policy,
        long parameter) {
    static final BigInteger CERTAIN_ADMISSION = BigInteger.ONE.shiftLeft(32);
    private static final BigInteger THOUSAND = BigInteger.valueOf(1000);

    static SamplingPolicy parse(CaptureProto.Sampling sampling) throws IOException {
        List<OffCpuReason> reasons = reasons(sampling);
        BigInteger minimum = sampling.hasMinOffCpuMicros() ? bound(sampling.getMinOffCpuMicros()) : null;
        BigInteger maximum = sampling.hasMaxOffCpuMicros() ? bound(sampling.getMaxOffCpuMicros()) : null;
        require(minimum == null || maximum == null || minimum.compareTo(maximum) < 0, "Invalid duration policy bounds");
        String policy;
        long parameter;
        switch (sampling.getAdmissionCase()) {
            case UNIFORM -> {
                CaptureProto.UniformAdmission uniform = sampling.getUniform();
                policy = "uniform";
                require(uniform.getProbability().matches("(?:0|1)(?:\\.[0-9]+)?"), "Invalid probability");
                parameter = uniform.getProbabilityThreshold();
                require(
                        parameter >= 1 && parameter <= CERTAIN_ADMISSION.longValueExact(),
                        "Invalid probability threshold");
            }
            case PROPORTIONAL -> {
                policy = "proportional";
                long micros = sampling.getProportional().getRecordAllAboveMicros();
                require(micros >= 1 && micros <= Long.MAX_VALUE / 1000, "Invalid recordAllAboveMicros");
                parameter = micros * 1000;
            }
            case NONE -> throw new IOException("Unsupported admission policy: none");
            default -> throw new IOException("Missing admission policy");
        }
        return new SamplingPolicy(reasons, minimum, maximum, policy, parameter);
    }

    /** A non-empty list of distinct reasons in the canonical order blocked, runnable, preempted. */
    private static List<OffCpuReason> reasons(CaptureProto.Sampling sampling) throws IOException {
        List<OffCpuReason> reasons = new ArrayList<>();
        for (int value : sampling.getReasonsValueList()) {
            OffCpuReason reason = OffCpuReason.fromWire(value);
            require(reason != null && reason != OffCpuReason.UNSPECIFIED, "Invalid sampling reason");
            require(
                    reasons.isEmpty() || reasons.get(reasons.size() - 1).compareTo(reason) < 0,
                    "Sampling reasons must be distinct and canonical");
            reasons.add(reason);
        }
        require(!reasons.isEmpty(), "Sampling reasons must not be empty");
        return List.copyOf(reasons);
    }

    /** A strict bound in microseconds, which must still fit signed nanoseconds. */
    private static BigInteger bound(long micros) throws IOException {
        require(micros >= 0 && micros <= Long.MAX_VALUE / 1000, "Duration policy overflows nanoseconds");
        return BigInteger.valueOf(micros).multiply(THOUSAND);
    }

    /** Whether the kernel's reason filter would have kept an interval of this reason. */
    boolean selects(OffCpuReason reason) {
        return reasons.contains(reason);
    }

    /** Whether the kernel would have found this duration eligible under the strict bounds. */
    boolean withinBounds(BigInteger durationNanos) {
        return (minOffCpuNanos == null || durationNanos.compareTo(minOffCpuNanos) > 0)
                && (maxOffCpuNanos == null || durationNanos.compareTo(maxOffCpuNanos) < 0);
    }

    /**
     * The 32-bit-scaled threshold the kernel drew against for an interval of this length: the fixed threshold
     * under {@code uniform}; under {@code proportional}, {@code 2^32} at and above the reference duration and
     * otherwise {@code duration * 2^32 / reference} with the reference shifted below {@code 2^32}, exactly as the
     * collector computes it in 64-bit arithmetic.
     */
    BigInteger admissionThreshold(BigInteger durationNanos) {
        if (policy.equals("uniform")) return BigInteger.valueOf(parameter);
        BigInteger reference = BigInteger.valueOf(parameter);
        if (durationNanos.compareTo(reference) >= 0) return CERTAIN_ADMISSION;
        int shift = Math.max(0, reference.bitLength() - 32);
        return durationNanos.shiftRight(shift).shiftLeft(32).divide(reference.shiftRight(shift));
    }
}
