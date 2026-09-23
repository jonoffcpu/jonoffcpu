// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static io.github.lhotari.jonoffcpu.offline.CaptureInput.number;
import static io.github.lhotari.jonoffcpu.offline.CaptureInput.object;
import static io.github.lhotari.jonoffcpu.offline.CaptureInput.require;
import static io.github.lhotari.jonoffcpu.offline.CaptureInput.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * The capture's resolved sampling policy as the agent recorded it in {@code captureStart.sampling}: the
 * switch-out reasons the kernel kept, optional strict duration bounds in microseconds, and the admission policy
 * the kernel applied to eligible intervals. The per-row {@code admissionThreshold} is recomputed from this policy
 * exactly as the collector computes it.
 *
 * <p>{@code reasons} is null for a schemaVersion 2 capture, which predates the classification and kept every
 * interval regardless of why it left the CPU.
 */
record SamplingPolicy(
        List<OffCpuReason> reasons,
        BigInteger minOffCpuNanos,
        BigInteger maxOffCpuNanos,
        String policy,
        long parameter) {
    static final BigInteger CERTAIN_ADMISSION = BigInteger.ONE.shiftLeft(32);
    private static final BigInteger THOUSAND = BigInteger.valueOf(1000);

    static SamplingPolicy parse(JsonObject sampling) throws IOException {
        boolean classified = sampling.has("reasons");
        require(
                sampling.keySet()
                        .equals(
                                classified
                                        ? java.util.Set.of("reasons", "minOffCpuMicros", "maxOffCpuMicros", "admission")
                                        : java.util.Set.of("minOffCpuMicros", "maxOffCpuMicros", "admission")),
                "Unexpected sampling policy shape");
        List<OffCpuReason> reasons = classified ? reasons(sampling.get("reasons")) : null;
        BigInteger minimum = optionalBound(sampling, "minOffCpuMicros");
        BigInteger maximum = optionalBound(sampling, "maxOffCpuMicros");
        require(minimum == null || maximum == null || minimum.compareTo(maximum) < 0, "Invalid duration policy bounds");
        JsonObject admission = object(sampling, "admission");
        String policy = text(admission, "policy");
        long parameter;
        switch (policy) {
            case "uniform" -> {
                require(
                        admission.keySet().equals(java.util.Set.of("policy", "probability", "probabilityThreshold")),
                        "Unexpected uniform admission shape");
                require(text(admission, "probability").matches("(?:0|1)(?:\\.[0-9]+)?"), "Invalid probability");
                parameter = number(admission, "probabilityThreshold");
                require(
                        parameter >= 1 && parameter <= CERTAIN_ADMISSION.longValueExact(),
                        "Invalid probability threshold");
            }
            case "proportional" -> {
                require(
                        admission.keySet().equals(java.util.Set.of("policy", "recordAllAboveMicros")),
                        "Unexpected proportional admission shape");
                long micros = number(admission, "recordAllAboveMicros");
                require(micros >= 1 && micros <= Long.MAX_VALUE / 1000, "Invalid recordAllAboveMicros");
                parameter = micros * 1000;
            }
            default -> throw new IOException("Unsupported admission policy: " + policy);
        }
        return new SamplingPolicy(reasons, minimum, maximum, policy, parameter);
    }

    /** A non-empty list of distinct reasons in the canonical order blocked, runnable, preempted. */
    private static List<OffCpuReason> reasons(JsonElement value) throws IOException {
        require(value != null && value.isJsonArray(), "Invalid sampling reasons");
        List<OffCpuReason> reasons = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            require(item.isJsonPrimitive() && item.getAsJsonPrimitive().isString(), "Invalid sampling reason");
            OffCpuReason reason = OffCpuReason.parse(item.getAsString());
            require(reason != OffCpuReason.UNSPECIFIED, "Invalid sampling reason");
            require(
                    reasons.isEmpty() || reasons.get(reasons.size() - 1).compareTo(reason) < 0,
                    "Sampling reasons must be distinct and canonical");
            reasons.add(reason);
        }
        require(!reasons.isEmpty(), "Sampling reasons must not be empty");
        return List.copyOf(reasons);
    }

    /** Whether the capture classifies its intervals by switch-out reason. */
    boolean classified() {
        return reasons != null;
    }

    /** Whether the kernel's reason filter would have kept an interval of this reason. */
    boolean selects(OffCpuReason reason) {
        return reasons == null ? reason == OffCpuReason.UNSPECIFIED : reasons.contains(reason);
    }

    private static BigInteger optionalBound(JsonObject sampling, String key) throws IOException {
        JsonElement value = sampling.get(key);
        require(value != null, "Missing source bound: " + key);
        if (value.isJsonNull()) return null;
        long micros = number(sampling, key);
        require(micros <= Long.MAX_VALUE / 1000, "Duration policy overflows nanoseconds");
        return BigInteger.valueOf(micros).multiply(THOUSAND);
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
