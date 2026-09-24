// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.agent;

import io.github.jonoffcpu.capture.CaptureProto.NoAdmission;
import io.github.jonoffcpu.capture.CaptureProto.OffCpuReason;
import io.github.jonoffcpu.capture.CaptureProto.ProportionalAdmission;
import io.github.jonoffcpu.capture.CaptureProto.Sampling;
import io.github.jonoffcpu.capture.CaptureProto.UniformAdmission;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the configuration's {@code sampling} block into the {@link Sampling} message: the switch-out reasons whose
 * intervals are eligible, optional strict duration bounds, and the admission policy applied to the intervals inside
 * them. The resolved message is the single representation sent to the native collector, echoed by it, written into
 * the capture stream and manifest, and compared as a message by every consumer.
 *
 * <p>The configuration keeps its own friendly spellings ({@code reasons: [blocked, runnable]}, {@code policy:
 * uniform}); only this class maps them to the schema's enums.
 */
final class SamplingConfig {
    static final long CERTAIN_ADMISSION = 1L << 32;
    private static final Set<String> KEYS = Set.of("reasons", "minOffCpuMicros", "maxOffCpuMicros", "admission");
    private static final long MAX_MICROS = Long.MAX_VALUE / 1000;
    /** Blocked intervals only: preemptions are far more frequent, and recording one costs a signal and a stack walk. */
    static final List<OffCpuReason> DEFAULT_REASONS = List.of(OffCpuReason.OFF_CPU_REASON_BLOCKED);

    private SamplingConfig() {}

    /** The configuration's name of a reason: {@code blocked}, {@code runnable} or {@code preempted}. */
    static String reasonName(OffCpuReason reason) {
        return switch (reason) {
            case OFF_CPU_REASON_BLOCKED -> "blocked";
            case OFF_CPU_REASON_RUNNABLE -> "runnable";
            case OFF_CPU_REASON_PREEMPTED -> "preempted";
            default -> throw new IllegalArgumentException("Not an off-CPU reason: " + reason);
        };
    }

    static OffCpuReason parseReason(String value) {
        for (OffCpuReason reason :
                EnumSet.range(OffCpuReason.OFF_CPU_REASON_BLOCKED, OffCpuReason.OFF_CPU_REASON_PREEMPTED)) {
            if (reasonName(reason).equals(value)) return reason;
        }
        throw new IllegalArgumentException("Unknown off-CPU reason: " + value);
    }

    /**
     * The kernel's classification of a switch-out from the raw {@code sched_switch} arguments: preemption wins, then
     * a zero task state is TASK_RUNNING.
     */
    static OffCpuReason classify(boolean preempted, int prevTaskState) {
        if (preempted) return OffCpuReason.OFF_CPU_REASON_PREEMPTED;
        return prevTaskState == 0 ? OffCpuReason.OFF_CPU_REASON_RUNNABLE : OffCpuReason.OFF_CPU_REASON_BLOCKED;
    }

    /** Resolves the {@code sampling} mapping of the configuration. */
    static Sampling parse(Map<?, ?> value) {
        ConfigValues.requireKeys(value, KEYS, "sampling");
        Long minimum = optionalMicros(value, "minOffCpuMicros");
        Long maximum = optionalMicros(value, "maxOffCpuMicros");
        if (minimum != null && maximum != null && minimum >= maximum) {
            throw new IllegalArgumentException("minOffCpuMicros must be less than maxOffCpuMicros");
        }
        Sampling.Builder sampling = Sampling.newBuilder();
        parseAdmission(ConfigValues.requireMap(value, "admission"), sampling);
        if (sampling.hasNone()) {
            if (minimum != null || maximum != null) {
                throw new IllegalArgumentException("Duration bounds have no effect with admission policy none");
            }
            if (value.get("reasons") != null) {
                throw new IllegalArgumentException("Switch-out reasons have no effect with admission policy none");
            }
            return sampling.build();
        }
        if (minimum != null) sampling.setMinOffCpuMicros(minimum);
        if (maximum != null) sampling.setMaxOffCpuMicros(maximum);
        return sampling.addAllReasons(parseReasons(value.get("reasons"))).build();
    }

    /** Absent means the default; otherwise a non-empty list without duplicates, in any order, kept canonical. */
    private static List<OffCpuReason> parseReasons(Object element) {
        if (element == null) return DEFAULT_REASONS;
        if (!(element instanceof List<?> items)) throw new IllegalArgumentException("sampling.reasons must be a list");
        EnumSet<OffCpuReason> reasons = EnumSet.noneOf(OffCpuReason.class);
        for (Object item : items) {
            if (!(item instanceof String name)) {
                throw new IllegalArgumentException("sampling.reasons must list reason names");
            }
            if (!reasons.add(parseReason(name))) {
                throw new IllegalArgumentException("Duplicate off-CPU reason: " + name);
            }
        }
        if (reasons.isEmpty()) throw new IllegalArgumentException("sampling.reasons must not be empty");
        // EnumSet iterates in declaration order, which is the schema's canonical order.
        return List.copyOf(reasons);
    }

    /** The flat {@code sampling-reasons=blocked+runnable} option spelling, as the list the mapping form holds. */
    static List<String> reasonsOption(String key, String value) {
        List<String> reasons = List.of(value.split("\\+", -1));
        if (reasons.contains("")) throw new IllegalArgumentException("Empty off-CPU reason in option: " + key);
        return reasons;
    }

    private static void parseAdmission(Map<?, ?> value, Sampling.Builder sampling) {
        String policy = ConfigValues.requireString(value, "policy");
        Set<String> allowed =
                switch (policy) {
                    case "none" -> Set.of("policy");
                    case "uniform" -> Set.of("policy", "probability");
                    case "proportional" -> Set.of("policy", "recordAllAboveMicros");
                    default -> throw new IllegalArgumentException("Unknown admission policy: " + policy);
                };
        for (Object key : value.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Admission policy " + policy + " does not accept key: " + key);
            }
        }
        switch (policy) {
            case "none" -> sampling.setNone(NoAdmission.getDefaultInstance());
            case "uniform" -> uniform(parseProbability(value), sampling);
            default ->
                sampling.setProportional(ProportionalAdmission.newBuilder()
                        .setRecordAllAboveMicros(
                                ConfigValues.requireInteger(value, "recordAllAboveMicros", 1, MAX_MICROS)));
        }
    }

    /** A zero probability is the explicit off switch; a positive one must keep at least one draw in 2^32. */
    static void uniform(BigDecimal probability, Sampling.Builder sampling) {
        if (probability.signum() == 0) {
            sampling.setNone(NoAdmission.getDefaultInstance());
            return;
        }
        long threshold = probability
                .multiply(new BigDecimal(CERTAIN_ADMISSION))
                .toBigInteger()
                .longValueExact();
        if (threshold == 0) {
            throw new IllegalArgumentException(
                    "probability is too small to admit any interval; use admission policy none to disable the source");
        }
        sampling.setUniform(UniformAdmission.newBuilder()
                .setProbability(probability.toPlainString())
                .setProbabilityThreshold(threshold));
    }

    private static BigDecimal parseProbability(Map<?, ?> value) {
        Object element = value.get("probability");
        // YAML reads an unquoted probability as a number; its decimal text is what is checked and kept.
        if (!(element instanceof String) && !(element instanceof Number)) {
            throw new IllegalArgumentException("Missing/decimal field probability");
        }
        return parseProbability("probability", element.toString());
    }

    static BigDecimal parseProbability(String key, String value) {
        if (value == null || !value.matches("(?:0|1)(?:\\.[0-9]+)?")) {
            throw new IllegalArgumentException("Invalid decimal probability option: " + key);
        }
        BigDecimal probability = new BigDecimal(value);
        if (probability.compareTo(BigDecimal.ZERO) < 0 || probability.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Probability option must be in 0.0..1.0: " + key);
        }
        return probability;
    }

    private static Long optionalMicros(Map<?, ?> value, String name) {
        return value.get(name) == null ? null : ConfigValues.requireInteger(value, name, 0, MAX_MICROS);
    }

    /** Admission policy {@code none}: no eBPF source is loaded, and async-profiler runs alone. */
    static boolean profilerOnly(Sampling sampling) {
        return sampling.hasNone();
    }

    /** The 32-bit-scaled threshold the kernel draws against for an interval of the given length. */
    static long admissionThreshold(Sampling sampling, long durationNanos) {
        return switch (sampling.getAdmissionCase()) {
            case UNIFORM -> sampling.getUniform().getProbabilityThreshold();
            case PROPORTIONAL ->
                admissionThreshold(durationNanos, sampling.getProportional().getRecordAllAboveMicros() * 1000);
            default -> throw new IllegalStateException("Admission policy none records no intervals");
        };
    }

    /**
     * The 32-bit-scaled threshold the kernel applies to an interval under the proportional policy:
     * {@code 2^32} at and above the reference duration, otherwise {@code duration * 2^32 / reference} computed
     * as the collector does, with the reference shifted below {@code 2^32} so the product fits 64 bits.
     * Both operands are treated as unsigned 64-bit values.
     */
    static long admissionThreshold(long durationNanos, long recordAllAboveNanos) {
        if (Long.compareUnsigned(durationNanos, recordAllAboveNanos) >= 0) return CERTAIN_ADMISSION;
        int shift = Math.max(0, 64 - Long.numberOfLeadingZeros(recordAllAboveNanos) - 32);
        long scaled = recordAllAboveNanos >>> shift;
        return Long.divideUnsigned((durationNanos >>> shift) << 32, scaled);
    }
}
