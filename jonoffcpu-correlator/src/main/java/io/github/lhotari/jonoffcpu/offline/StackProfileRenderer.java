// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Projects a {@link StackProfile} onto collapsed stacks: one slice by switch-out reason, stack kind and weighting.
 * Rendered with the profile's own defaults — every reason, Java stacks, observed weights — a profile reproduces
 * the correlator's {@code jonoffcpu-offcpu-stacks.collapsed} byte for byte.
 */
final class StackProfileRenderer {
    /** Which stacks a collapsed line is made of, root first, in this order. */
    enum StackKinds {
        JAVA("java", true, false, false),
        KERNEL("kernel", false, false, true),
        USER("user", false, true, false),
        JAVA_KERNEL("java+kernel", true, false, true),
        JAVA_USER_KERNEL("java+user+kernel", true, true, true);

        final String text;
        final boolean java;
        final boolean user;
        final boolean kernel;

        StackKinds(String text, boolean java, boolean user, boolean kernel) {
            this.text = text;
            this.java = java;
            this.user = user;
            this.kernel = kernel;
        }

        static StackKinds parse(String text) {
            for (StackKinds kinds : values()) {
                if (kinds.text.equals(text)) return kinds;
            }
            throw new IllegalArgumentException("Invalid stack selection: " + text);
        }
    }

    enum Weights {
        OBSERVED,
        ESTIMATED;

        static Weights parse(String text) {
            return switch (text) {
                case "observed" -> OBSERVED;
                case "estimated" -> ESTIMATED;
                default -> throw new IllegalArgumentException("Invalid weights: " + text);
            };
        }
    }

    /** Whether each line starts with an {@code [offcpu: reason]} frame. */
    enum ReasonFrame {
        /** Only when the slice mixes more than one reason, which is when an unlabelled graph misleads. */
        AUTO,
        ALWAYS,
        NEVER;

        static ReasonFrame parse(String text) {
            return switch (text) {
                case "auto" -> AUTO;
                case "always" -> ALWAYS;
                case "never" -> NEVER;
                default -> throw new IllegalArgumentException("Invalid reason frame mode: " + text);
            };
        }

        boolean applies(int selectedReasons) {
            return this == ALWAYS || this == AUTO && selectedReasons > 1;
        }
    }

    /** The collapsed lines of one slice and the totals behind them, for a summary a reader can reconcile. */
    record Slice(Map<String, BigInteger> nanos, long intervals, BigInteger totalNanos) {}

    private static final Pattern OFFSET = Pattern.compile("\\+0x[0-9a-fA-F]+$");
    /**
     * The first frame of the tracing machinery that took the kernel stack: the tracepoint iterator, its BPF
     * trampoline, and the profiler's own program, whose name carries a per-load tag. Everything from it to the
     * leaf is the profiler, not the wait.
     */
    private static final Pattern TRACING = Pattern.compile("^(__traceiter_|__bpf_trace_|bpf_trace_run|bpf_prog_)");

    private StackProfileRenderer() {}

    /** The synthetic root frame naming a switch-out reason. */
    static String reasonFrame(OffCpuReason reason) {
        return "[offcpu: " + reason.label() + "]";
    }

    /**
     * Renders one slice. {@code reasons} null selects every reason. Observed weights are rescaled by the source's
     * correlation-time thinning, as the correlator's own collapsed file is; estimated weights require the
     * profile's estimate to be available.
     */
    static Slice render(
            StackProfile profile, Set<OffCpuReason> reasons, StackKinds kinds, Weights weights, ReasonFrame frame)
            throws IOException {
        if (weights == Weights.ESTIMATED) {
            CaptureInput.require(
                    profile.header().estimateAvailable(),
                    "This profile's inverse-probability estimate is unavailable; use observed weights");
        }
        if (kinds.kernel) requireDimension(profile, ProfileAccumulator.KERNEL);
        if (kinds.user) requireDimension(profile, ProfileAccumulator.USER);
        Set<OffCpuReason> selected = reasons == null ? EnumSet.allOf(OffCpuReason.class) : EnumSet.copyOf(reasons);
        Set<OffCpuReason> present = EnumSet.noneOf(OffCpuReason.class);
        for (StackProfile.Entry entry : profile.entries()) {
            // Only reasons that contribute a line count, as in the correlator's own collapsed file.
            if (selected.contains(entry.reason()) && entry.observedNanos() > 0) present.add(entry.reason());
        }
        boolean withReason = frame.applies(present.size());
        String label = profile.header().label();
        Map<String, BigInteger> raw = new TreeMap<>();
        long intervals = 0;
        for (StackProfile.Entry entry : profile.entries()) {
            if (!selected.contains(entry.reason())) continue;
            intervals = Math.addExact(intervals, entry.intervals());
            long nanos = weights == Weights.OBSERVED ? entry.observedNanos() : entry.estimatedNanos();
            if (nanos == 0) continue;
            StringBuilder line = new StringBuilder(label);
            if (withReason) line.append(reasonFrame(entry.reason())).append(';');
            int start = line.length();
            if (kinds.java) appendJava(line, entry.javaStack());
            if (kinds.user) appendNative(line, start, entry.userStack(), "", "[user stack unavailable]");
            if (kinds.kernel) appendNative(line, start, entry.kernelStack(), "_[k]", "[kernel stack unavailable]");
            raw.merge(line.toString(), BigInteger.valueOf(nanos), BigInteger::add);
        }
        Thinning thinning = weights == Weights.OBSERVED ? profile.header().thinning() : Thinning.NONE;
        Map<String, BigInteger> scaled = new TreeMap<>();
        BigInteger total = BigInteger.ZERO;
        for (var line : raw.entrySet()) {
            BigInteger value =
                    thinning.active() ? thinning.scale(line.getValue().longValueExact()) : line.getValue();
            scaled.put(line.getKey(), value);
            total = total.add(value);
        }
        return new Slice(scaled, intervals, total);
    }

    private static void requireDimension(StackProfile profile, String dimension) throws IOException {
        CaptureInput.require(
                profile.header().dimensions().contains(dimension),
                "This profile is not grouped by its " + dimension + " stacks");
    }

    private static void appendJava(StringBuilder line, List<StackProfile.Frame> stack) {
        for (int index = 0; index < stack.size(); index++) {
            if (index > 0) line.append(';');
            line.append(stack.get(index).name());
        }
    }

    private static void appendNative(
            StringBuilder line, int start, List<StackProfile.Frame> stack, String suffix, String unavailable) {
        if (line.length() > start) line.append(';');
        if (stack == null) {
            line.append(unavailable);
            return;
        }
        int end = suffix.isEmpty() ? stack.size() : kernelEnd(stack);
        for (int index = 0; index < end; index++) {
            if (index > 0) line.append(';');
            line.append(escape(nativeName(stack.get(index)))).append(suffix);
        }
    }

    /** Where a root-first kernel stack stops being the thread's own: at the first tracing frame, if any. */
    static int kernelEnd(List<StackProfile.Frame> stack) {
        for (int index = 1; index < stack.size(); index++) {
            if (TRACING.matcher(stack.get(index).name()).find()) return index;
        }
        return stack.size();
    }

    /** A flame graph frame for a native frame: the symbol without its offset, so one function is one frame. */
    static String nativeName(StackProfile.Frame frame) {
        String symbol = OFFSET.matcher(frame.name()).replaceFirst("");
        if (!symbol.isEmpty()) return symbol;
        if (!frame.module().isEmpty()) return "[" + frame.module() + "]";
        return "[unknown]";
    }

    private static String escape(String name) {
        return name.replace(';', ':').replace('\n', ' ').replace('\r', ' ');
    }

    /** Writes collapsed lines with the correlator's rounding: nearest microsecond, and at least one. */
    static void writeCollapsed(Slice slice, BufferedWriter writer) throws IOException {
        for (var line : slice.nanos().entrySet()) {
            writer.write(line.getKey());
            writer.write(' ');
            writer.write(OffCpuCorrelator.collapsedMicros(line.getValue().toString()));
            writer.newLine();
        }
    }

    static JsonObject summary(
            Slice slice, StackProfile profile, Set<OffCpuReason> reasons, StackKinds kinds, Weights weights) {
        JsonObject summary = new JsonObject();
        summary.addProperty("schemaVersion", 1);
        JsonArray selected = new JsonArray();
        for (OffCpuReason reason : reasons == null ? EnumSet.allOf(OffCpuReason.class) : reasons) {
            selected.add(reason.label());
        }
        summary.add("reasons", selected);
        summary.addProperty("stack", kinds.text);
        summary.addProperty("weights", weights.name().toLowerCase(java.util.Locale.ROOT));
        summary.addProperty(
                "reasonSemantics",
                "switch-out reason; a blocked interval's duration includes its run-queue delay after wakeup");
        summary.addProperty("intervals", Long.toString(slice.intervals()));
        summary.addProperty("lines", slice.nanos().size());
        summary.addProperty("totalNanos", slice.totalNanos().toString());
        summary.addProperty("label", profile.header().label());
        summary.addProperty("sources", profile.header().sources().size());
        return summary;
    }

    /** One row per entry with its stacks expanded, for tools such as DuckDB. */
    static void export(StackProfile profile, String format, BufferedWriter writer) throws IOException {
        switch (format) {
            case "csv" -> {
                writer.write("reason,task_state,thread,java_stack,kernel_stack,user_stack,"
                        + "intervals,observed_nanos,estimated_nanos");
                writer.newLine();
                for (StackProfile.Entry entry : profile.entries()) {
                    writer.write(String.join(
                            ",",
                            entry.reason().label(),
                            Integer.toUnsignedString(entry.taskState()),
                            csv(entry.thread()),
                            csv(joined(entry.javaStack(), false)),
                            csv(joined(entry.kernelStack(), true)),
                            csv(joined(entry.userStack(), true)),
                            Long.toString(entry.intervals()),
                            Long.toUnsignedString(entry.observedNanos()),
                            Long.toUnsignedString(entry.estimatedNanos())));
                    writer.newLine();
                }
            }
            case "jsonl" -> {
                var gson = new GsonBuilder().serializeNulls().create();
                for (StackProfile.Entry entry : profile.entries()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("reason", entry.reason().label());
                    row.addProperty("taskState", Integer.toUnsignedLong(entry.taskState()));
                    row.addProperty("thread", entry.thread());
                    row.addProperty("javaStack", joined(entry.javaStack(), false));
                    row.addProperty("kernelStack", joined(entry.kernelStack(), true));
                    row.addProperty("userStack", joined(entry.userStack(), true));
                    row.addProperty("intervals", entry.intervals());
                    row.addProperty("observedNanos", Long.toUnsignedString(entry.observedNanos()));
                    row.addProperty("estimatedNanos", Long.toUnsignedString(entry.estimatedNanos()));
                    gson.toJson(row, writer);
                    writer.newLine();
                }
            }
            default -> throw new IllegalArgumentException("Invalid export format: " + format);
        }
    }

    private static String joined(List<StackProfile.Frame> stack, boolean nativeFrames) {
        if (stack == null) return null;
        StringBuilder text = new StringBuilder();
        int end = nativeFrames && stack.get(0).kind() == StackProfile.Kind.KERNEL ? kernelEnd(stack) : stack.size();
        for (int index = 0; index < end; index++) {
            StackProfile.Frame frame = stack.get(index);
            if (text.length() > 0) text.append(';');
            text.append(nativeFrames ? escape(nativeName(frame)) : frame.name());
        }
        return text.toString();
    }

    private static String csv(String value) {
        if (value == null) return "";
        if (value.isEmpty() || value.chars().noneMatch(c -> c == ',' || c == '"' || c == '\n' || c == '\r')) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
