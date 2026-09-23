// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The resolved sampling policy: the switch-out reasons whose intervals are eligible, optional strict duration
 * bounds, and the admission policy applied to the intervals inside them. {@link #json()} is the single
 * representation sent to the native collector, echoed by it, written into the capture stream and manifest, and
 * compared structurally by the correlator.
 */
record SamplingConfig(Set<OffCpuReason> reasons, Long minOffCpuMicros, Long maxOffCpuMicros, Admission admission) {
    static final long CERTAIN_ADMISSION = 1L << 32;
    private static final Set<String> KEYS = Set.of("reasons", "minOffCpuMicros", "maxOffCpuMicros", "admission");
    private static final long MAX_MICROS = Long.MAX_VALUE / 1000;
    /** Blocked intervals only: preemptions are far more frequent, and recording one costs a signal and a stack walk. */
    static final Set<OffCpuReason> DEFAULT_REASONS = EnumSet.of(OffCpuReason.BLOCKED);

    /**
     * Why the scheduler took a thread off the CPU, as the kernel classifies it at switch-out: {@code blocked}
     * when it left in a waiting state, {@code runnable} when it left at an ordinary scheduling point while still
     * running (how a user-space thread is preempted by the scheduler tick, and {@code sched_yield}), and
     * {@code preempted} when the kernel preempted it inside the kernel. Declaration order is the canonical
     * serialization order.
     */
    enum OffCpuReason {
        BLOCKED,
        RUNNABLE,
        PREEMPTED;

        String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        static OffCpuReason parse(String value) {
            for (OffCpuReason reason : values()) {
                if (reason.json().equals(value)) return reason;
            }
            throw new IllegalArgumentException("Unknown off-CPU reason: " + value);
        }
    }

    SamplingConfig {
        reasons = reasons == null ? null : Set.copyOf(EnumSet.copyOf(reasons));
    }

    sealed interface Admission permits None, Uniform, Proportional {
        String policy();

        JsonObject json();

        /** The 32-bit-scaled threshold the kernel draws against for an interval of the given length. */
        long admissionThreshold(long durationNanos);
    }

    /** Async-profiler only: no eBPF source is loaded. Never sent to the native collector. */
    record None() implements Admission {
        @Override
        public String policy() {
            return "none";
        }

        @Override
        public JsonObject json() {
            JsonObject value = new JsonObject();
            value.addProperty("policy", policy());
            return value;
        }

        @Override
        public long admissionThreshold(long durationNanos) {
            throw new IllegalStateException("Admission policy none records no intervals");
        }
    }

    /** Every eligible interval is admitted with the same probability {@code probabilityThreshold / 2^32}. */
    record Uniform(BigDecimal probability, long probabilityThreshold) implements Admission {
        @Override
        public String policy() {
            return "uniform";
        }

        @Override
        public JsonObject json() {
            JsonObject value = new JsonObject();
            value.addProperty("policy", policy());
            value.addProperty("probability", probability.toPlainString());
            value.addProperty("probabilityThreshold", probabilityThreshold);
            return value;
        }

        @Override
        public long admissionThreshold(long durationNanos) {
            return probabilityThreshold;
        }
    }

    /**
     * An interval of at least {@code recordAllAboveMicros} is always admitted; a shorter one with probability
     * {@code duration / recordAllAboveMicros}, so below the reference the expected number of samples follows
     * off-CPU time rather than interval count, and the signal rate is bounded by the total off-CPU time
     * divided by the reference.
     */
    record Proportional(long recordAllAboveMicros) implements Admission {
        @Override
        public String policy() {
            return "proportional";
        }

        @Override
        public JsonObject json() {
            JsonObject value = new JsonObject();
            value.addProperty("policy", policy());
            value.addProperty("recordAllAboveMicros", recordAllAboveMicros);
            return value;
        }

        long recordAllAboveNanos() {
            return recordAllAboveMicros * 1000;
        }

        @Override
        public long admissionThreshold(long durationNanos) {
            return SamplingConfig.admissionThreshold(durationNanos, recordAllAboveNanos());
        }
    }

    static SamplingConfig parse(JsonObject value) {
        for (String key : value.keySet()) {
            if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown sampling key: " + key);
        }
        Long minimum = optionalMicros(value, "minOffCpuMicros");
        Long maximum = optionalMicros(value, "maxOffCpuMicros");
        if (minimum != null && maximum != null && minimum >= maximum) {
            throw new IllegalArgumentException("minOffCpuMicros must be less than maxOffCpuMicros");
        }
        Admission admission = parseAdmission(JsonSupport.requireObject(value, "admission"));
        if (admission instanceof None && (minimum != null || maximum != null)) {
            throw new IllegalArgumentException("Duration bounds have no effect with admission policy none");
        }
        JsonElement reasons = value.get("reasons");
        if (admission instanceof None) {
            if (reasons != null && !reasons.isJsonNull()) {
                throw new IllegalArgumentException("Switch-out reasons have no effect with admission policy none");
            }
            return new SamplingConfig(null, null, null, admission);
        }
        return new SamplingConfig(parseReasons(reasons), minimum, maximum, admission);
    }

    /** Absent means the default; otherwise a non-empty list without duplicates, in any order. */
    private static Set<OffCpuReason> parseReasons(JsonElement element) {
        if (element == null || element.isJsonNull()) return DEFAULT_REASONS;
        if (!element.isJsonArray()) throw new IllegalArgumentException("sampling.reasons must be a list");
        EnumSet<OffCpuReason> reasons = EnumSet.noneOf(OffCpuReason.class);
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("sampling.reasons must list reason names");
            }
            if (!reasons.add(OffCpuReason.parse(item.getAsString()))) {
                throw new IllegalArgumentException("Duplicate off-CPU reason: " + item.getAsString());
            }
        }
        if (reasons.isEmpty()) throw new IllegalArgumentException("sampling.reasons must not be empty");
        return reasons;
    }

    /** The flat {@code sampling-reasons=blocked+runnable} option spelling. */
    static JsonArray reasonsOption(String key, String value) {
        JsonArray reasons = new JsonArray();
        for (String reason : value.split("\\+", -1)) {
            if (reason.isEmpty()) throw new IllegalArgumentException("Empty off-CPU reason in option: " + key);
            reasons.add(reason);
        }
        return reasons;
    }

    /** The selected reasons in canonical order: blocked, runnable, preempted. */
    List<OffCpuReason> orderedReasons() {
        return reasons == null ? List.of() : List.copyOf(EnumSet.copyOf(reasons));
    }

    private static Admission parseAdmission(JsonObject value) {
        String policy = JsonSupport.requireString(value, "policy");
        Set<String> allowed =
                switch (policy) {
                    case "none" -> Set.of("policy");
                    case "uniform" -> Set.of("policy", "probability");
                    case "proportional" -> Set.of("policy", "recordAllAboveMicros");
                    default -> throw new IllegalArgumentException("Unknown admission policy: " + policy);
                };
        for (String key : value.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Admission policy " + policy + " does not accept key: " + key);
            }
        }
        return switch (policy) {
            case "none" -> new None();
            case "uniform" -> uniform(parseProbability(value));
            default -> new Proportional(JsonSupport.requireNumber(value, "recordAllAboveMicros", 1, MAX_MICROS));
        };
    }

    /** A zero probability is the explicit off switch; a positive one must keep at least one draw in 2^32. */
    static Admission uniform(BigDecimal probability) {
        if (probability.signum() == 0) return new None();
        long threshold = probability
                .multiply(new BigDecimal(CERTAIN_ADMISSION))
                .toBigInteger()
                .longValueExact();
        if (threshold == 0) {
            throw new IllegalArgumentException(
                    "probability is too small to admit any interval; use admission policy none to disable the source");
        }
        return new Uniform(probability, threshold);
    }

    private static BigDecimal parseProbability(JsonObject value) {
        JsonElement element = value.get("probability");
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing/decimal field probability");
        }
        return parseProbability("probability", element.getAsString());
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

    private static Long optionalMicros(JsonObject value, String name) {
        JsonElement element = value.get(name);
        if (element == null || element.isJsonNull()) return null;
        return JsonSupport.requireNumber(value, name, 0, MAX_MICROS);
    }

    boolean profilerOnly() {
        return admission instanceof None;
    }

    long admissionThreshold(long durationNanos) {
        return admission.admissionThreshold(durationNanos);
    }

    JsonObject json() {
        JsonObject value = new JsonObject();
        if (reasons == null) {
            value.add("reasons", JsonNull.INSTANCE);
        } else {
            JsonArray names = new JsonArray();
            for (OffCpuReason reason : orderedReasons()) names.add(reason.json());
            value.add("reasons", names);
        }
        value.add("minOffCpuMicros", nullable(minOffCpuMicros));
        value.add("maxOffCpuMicros", nullable(maxOffCpuMicros));
        value.add("admission", admission.json());
        return value;
    }

    private static JsonElement nullable(Long number) {
        return number == null ? JsonNull.INSTANCE : JsonSupport.GSON.toJsonTree(number);
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
