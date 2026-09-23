// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Projects a {@link StackProfile} onto collapsed stacks: one slice by switch-out reason, stack kind, weighting and
 * {@link Time time part}. Rendered with the profile's own defaults — every reason, Java stacks, observed weights, the
 * whole time — a profile reproduces the correlator's {@code jonoffcpu-offcpu-stacks.collapsed} byte for byte. A {@link Filter} keeps or drops whole
 * entries by their frames before they are merged into lines, so it sees stacks the slice does not render, and the
 * time it removes stays accountable.
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

    /**
     * Which part of each interval's time a slice weighs: all of it, only the sleeping or the run-queue part, or all of
     * it with each line ending in a {@code [sleeping]}, {@code [runqueue]} or {@code [unsplit]} leaf frame, so one
     * flame graph shows both parts. Every mode but {@code total} needs a profile with the split.
     */
    enum Time {
        TOTAL(null),
        SLEEPING(TimeSplit.Part.SLEEPING),
        RUNQUEUE(TimeSplit.Part.RUNQUEUE),
        SPLIT(null);

        final TimeSplit.Part part;

        Time(TimeSplit.Part part) {
            this.part = part;
        }

        String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static Time parse(String text) {
            for (Time time : values()) {
                if (time.label().equals(text)) return time;
            }
            throw new IllegalArgumentException("Invalid time part: " + text);
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

    /**
     * Keeps or drops whole profile entries by their frames. An entry is dropped when any frame of any of its stacks
     * matches an {@code exclude} pattern, and otherwise kept when {@code include} is empty or any frame matches one
     * of its patterns. Patterns are searched for ({@link java.util.regex.Matcher#find()}) in each frame's rendered
     * text: a Java frame's name, a native frame's offset-free symbol, a kernel frame's with its {@code _[k]} suffix
     * and without the tracing frames, and an unavailable native stack's placeholder frame. Every stack the profile
     * is grouped by is searched, whichever the slice renders.
     */
    record Filter(List<Pattern> include, List<Pattern> exclude) {
        static final Filter NONE = new Filter(List.of(), List.of());

        private static final int INCLUDED = 1;
        private static final int EXCLUDED = 2;

        Filter {
            include = List.copyOf(include);
            exclude = List.copyOf(exclude);
        }

        static Filter of(List<String> include, List<String> exclude) {
            return new Filter(
                    include.stream().map(Pattern::compile).toList(),
                    exclude.stream().map(Pattern::compile).toList());
        }

        boolean active() {
            return !include.isEmpty() || !exclude.isEmpty();
        }

        /** The stack kinds a filter over this profile searches: Java always, native ones when grouped by. */
        static List<String> scope(StackProfile profile) {
            List<String> scope = new ArrayList<>(List.of("java"));
            if (profile.header().dimensions().contains(ProfileAccumulator.USER)) scope.add(ProfileAccumulator.USER);
            if (profile.header().dimensions().contains(ProfileAccumulator.KERNEL)) {
                scope.add(ProfileAccumulator.KERNEL);
            }
            return scope;
        }

        /** Whether an entry survives; each distinct stack is matched once per render. */
        private Predicate<StackProfile.Entry> predicate(StackProfile profile) {
            if (!active()) return entry -> true;
            List<String> scope = scope(profile);
            boolean user = scope.contains(ProfileAccumulator.USER);
            boolean kernel = scope.contains(ProfileAccumulator.KERNEL);
            Map<List<StackProfile.Frame>, Integer> javaFlags = new IdentityHashMap<>();
            Map<List<StackProfile.Frame>, Integer> userFlags = new IdentityHashMap<>();
            Map<List<StackProfile.Frame>, Integer> kernelFlags = new IdentityHashMap<>();
            return entry -> {
                int flags = javaFlags.computeIfAbsent(entry.javaStack(), stack -> match(javaNames(stack)));
                if (user && (flags & EXCLUDED) == 0) {
                    flags |= userFlags.computeIfAbsent(entry.userStack(), stack -> match(userNames(stack)));
                }
                if (kernel && (flags & EXCLUDED) == 0) {
                    flags |= kernelFlags.computeIfAbsent(entry.kernelStack(), stack -> match(kernelNames(stack)));
                }
                if ((flags & EXCLUDED) != 0) return false;
                return include.isEmpty() || (flags & INCLUDED) != 0;
            };
        }

        private int match(List<String> names) {
            int flags = 0;
            for (String name : names) {
                for (Pattern pattern : exclude) {
                    if (pattern.matcher(name).find()) return EXCLUDED;
                }
                if ((flags & INCLUDED) == 0) {
                    for (Pattern pattern : include) {
                        if (pattern.matcher(name).find()) {
                            flags |= INCLUDED;
                            break;
                        }
                    }
                }
            }
            return flags;
        }
    }

    /**
     * The collapsed lines of one slice and the totals behind them, for a summary a reader can reconcile. The
     * filtered totals are what the slice's filter removed: an unfiltered slice's totals less the kept ones. The
     * unsplit total is the part of the kept entries' time that has no sleeping/run-queue split, which a
     * {@code sleeping} or {@code runqueue} slice leaves out.
     */
    record Slice(
            Map<String, BigInteger> nanos,
            long intervals,
            BigInteger totalNanos,
            long filteredIntervals,
            BigInteger filteredNanos,
            BigInteger unsplitNanos) {}

    private static final Pattern OFFSET = Pattern.compile("\\+0x[0-9a-fA-F]+$");
    /**
     * The first frame of the tracing machinery that took the kernel stack: the tracepoint iterator, its BPF
     * trampoline, and the profiler's own program, whose name carries a per-load tag. Everything from it to the
     * leaf is the profiler, not the wait.
     */
    private static final Pattern TRACING = Pattern.compile("^(__traceiter_|__bpf_trace_|bpf_trace_run|bpf_prog_)");

    private static final String USER_UNAVAILABLE = "[user stack unavailable]";
    private static final String KERNEL_UNAVAILABLE = "[kernel stack unavailable]";

    private StackProfileRenderer() {}

    /** The synthetic root frame naming a switch-out reason. */
    static String reasonFrame(OffCpuReason reason) {
        return "[offcpu: " + reason.label() + "]";
    }

    /**
     * Renders one slice. {@code reasons} null selects every reason. Observed weights are rescaled by the source's
     * correlation-time thinning, as the correlator's own collapsed file is; estimated weights require the
     * profile's estimate to be available. The filter applies to entries before anything else, including the
     * {@link ReasonFrame#AUTO} decision, so a filtered slice reads as if the dropped entries were never recorded.
     */
    static Slice render(
            StackProfile profile,
            Set<OffCpuReason> reasons,
            StackKinds kinds,
            Weights weights,
            ReasonFrame frame,
            Time time,
            Filter filter)
            throws IOException {
        Slice kept = project(profile, reasons, kinds, weights, frame, time, filter.predicate(profile));
        if (!filter.active()) return kept;
        // Rendered unfiltered as well, so the removed time is exact even where thinning rounds line by line.
        Slice all = project(profile, reasons, kinds, weights, frame, time, entry -> true);
        return new Slice(
                kept.nanos(),
                kept.intervals(),
                kept.totalNanos(),
                Math.subtractExact(all.intervals(), kept.intervals()),
                all.totalNanos().subtract(kept.totalNanos()),
                kept.unsplitNanos());
    }

    private static Slice project(
            StackProfile profile,
            Set<OffCpuReason> reasons,
            StackKinds kinds,
            Weights weights,
            ReasonFrame frame,
            Time time,
            Predicate<StackProfile.Entry> keeps)
            throws IOException {
        if (weights == Weights.ESTIMATED) {
            CaptureInput.require(
                    profile.header().estimateAvailable(),
                    "This profile's inverse-probability estimate is unavailable; use observed weights");
        }
        if (time != Time.TOTAL) {
            CaptureInput.require(
                    profile.header().timeSplitAvailable(),
                    "This profile has no sleeping/run-queue split (its capture predates timeSplit or ran with"
                            + " timeSplit.source off); use --time total");
        }
        boolean estimated = weights == Weights.ESTIMATED;
        if (kinds.kernel) requireDimension(profile, ProfileAccumulator.KERNEL);
        if (kinds.user) requireDimension(profile, ProfileAccumulator.USER);
        Set<OffCpuReason> selected = reasons == null ? EnumSet.allOf(OffCpuReason.class) : EnumSet.copyOf(reasons);
        List<StackProfile.Entry> entries = new ArrayList<>();
        Set<OffCpuReason> present = EnumSet.noneOf(OffCpuReason.class);
        for (StackProfile.Entry entry : profile.entries()) {
            if (!selected.contains(entry.reason()) || !keeps.test(entry)) continue;
            entries.add(entry);
            // Only reasons that contribute a line count, as in the correlator's own collapsed file.
            long contributes =
                    time.part == null ? entry.observedNanos() : entry.split().nanos(time.part, false);
            if (contributes > 0) present.add(entry.reason());
        }
        boolean withReason = frame.applies(present.size());
        String label = profile.header().label();
        Map<String, BigInteger> raw = new TreeMap<>();
        long intervals = 0;
        long unsplit = 0;
        for (StackProfile.Entry entry : entries) {
            intervals = Math.addExact(intervals, entry.intervals());
            unsplit = U64.add(unsplit, entry.split().nanos(TimeSplit.Part.UNSPLIT, estimated), "Unsplit duration");
            long nanos = time.part != null
                    ? entry.split().nanos(time.part, estimated)
                    : estimated ? entry.estimatedNanos() : entry.observedNanos();
            if (nanos == 0) continue;
            StringBuilder line = new StringBuilder(label);
            if (withReason) line.append(reasonFrame(entry.reason())).append(';');
            int start = line.length();
            if (kinds.java) appendJava(line, entry.javaStack());
            if (kinds.user) appendNative(line, start, userNames(entry.userStack()));
            if (kinds.kernel) appendNative(line, start, kernelNames(entry.kernelStack()));
            if (time != Time.SPLIT) {
                raw.merge(line.toString(), BigInteger.valueOf(nanos), BigInteger::add);
                continue;
            }
            for (TimeSplit.Part part : TimeSplit.Part.values()) {
                long partNanos = entry.split().nanos(part, estimated);
                if (partNanos == 0) continue;
                raw.merge(line + ";" + partFrame(part), BigInteger.valueOf(partNanos), BigInteger::add);
            }
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
        BigInteger unsplitTotal = thinning.active() ? thinning.scale(unsplit) : BigInteger.valueOf(unsplit);
        return new Slice(scaled, intervals, total, 0, BigInteger.ZERO, unsplitTotal);
    }

    /** The leaf frame naming one part of an interval's time in a {@link Time#SPLIT} slice. */
    static String partFrame(TimeSplit.Part part) {
        return "[" + part.label() + "]";
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

    private static void appendNative(StringBuilder line, int start, List<String> names) {
        if (line.length() > start) line.append(';');
        line.append(String.join(";", names));
    }

    /** Each kind of stack's frames, root first, as they read in a collapsed line; what a {@link Filter} matches. */
    private static List<String> javaNames(List<StackProfile.Frame> stack) {
        if (stack == null) return List.of();
        return stack.stream().map(StackProfile.Frame::name).toList();
    }

    private static List<String> userNames(List<StackProfile.Frame> stack) {
        return nativeNames(stack, stack == null ? 0 : stack.size(), "", USER_UNAVAILABLE);
    }

    private static List<String> kernelNames(List<StackProfile.Frame> stack) {
        return nativeNames(stack, stack == null ? 0 : kernelEnd(stack), "_[k]", KERNEL_UNAVAILABLE);
    }

    private static List<String> nativeNames(
            List<StackProfile.Frame> stack, int end, String suffix, String unavailable) {
        if (stack == null) return List.of(unavailable);
        List<String> names = new ArrayList<>(end);
        for (int index = 0; index < end; index++) {
            names.add(escape(nativeName(stack.get(index))) + suffix);
        }
        return names;
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
            Slice slice,
            StackProfile profile,
            Set<OffCpuReason> reasons,
            StackKinds kinds,
            Weights weights,
            Time time,
            Filter filter) {
        JsonObject summary = new JsonObject();
        summary.addProperty("schemaVersion", 1);
        JsonArray selected = new JsonArray();
        for (OffCpuReason reason : reasons == null ? EnumSet.allOf(OffCpuReason.class) : reasons) {
            selected.add(reason.label());
        }
        summary.add("reasons", selected);
        summary.addProperty("stack", kinds.text);
        summary.addProperty("weights", weights.name().toLowerCase(java.util.Locale.ROOT));
        summary.addProperty("time", time.label());
        summary.addProperty(
                "reasonSemantics",
                "switch-out reason; a blocked interval's time is split into sleeping before its wakeup and runqueue"
                        + " after it, and runnable and preempted intervals are runqueue throughout, when the profile"
                        + " has the split");
        summary.addProperty("intervals", Long.toString(slice.intervals()));
        summary.addProperty("lines", slice.nanos().size());
        summary.addProperty("totalNanos", slice.totalNanos().toString());
        // The kept entries' time without a split: part of a total or split slice, left out of sleeping and runqueue.
        summary.addProperty("unsplitNanos", slice.unsplitNanos().toString());
        summary.add("include", patterns(filter.include()));
        summary.add("exclude", patterns(filter.exclude()));
        JsonArray scope = new JsonArray();
        if (filter.active()) Filter.scope(profile).forEach(scope::add);
        summary.add("filterScope", scope);
        // What the filter removed from the selected reasons: add it back to reconcile with an unfiltered slice.
        JsonObject filtered = new JsonObject();
        filtered.addProperty("intervals", Long.toString(slice.filteredIntervals()));
        filtered.addProperty("totalNanos", slice.filteredNanos().toString());
        summary.add("filtered", filtered);
        summary.addProperty("label", profile.header().label());
        summary.addProperty("sources", profile.header().sources().size());
        return summary;
    }

    private static JsonArray patterns(List<Pattern> patterns) {
        JsonArray array = new JsonArray();
        for (Pattern pattern : patterns) array.add(pattern.pattern());
        return array;
    }

    /** One row per entry with its stacks expanded, for tools such as DuckDB. */
    static void export(StackProfile profile, String format, BufferedWriter writer) throws IOException {
        switch (format) {
            case "csv" -> {
                writer.write("reason,task_state,thread,java_stack,kernel_stack,user_stack,"
                        + "intervals,observed_nanos,estimated_nanos,sleeping_nanos,runqueue_nanos,unsplit_nanos,"
                        + "estimated_sleeping_nanos,estimated_runqueue_nanos,estimated_unsplit_nanos");
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
                            Long.toUnsignedString(entry.estimatedNanos()),
                            Long.toUnsignedString(entry.split().sleeping()),
                            Long.toUnsignedString(entry.split().runqueue()),
                            Long.toUnsignedString(entry.split().unsplit()),
                            Long.toUnsignedString(entry.split().estimatedSleeping()),
                            Long.toUnsignedString(entry.split().estimatedRunqueue()),
                            Long.toUnsignedString(entry.split().estimatedUnsplit())));
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
                    StackProfile.Split split = entry.split();
                    row.addProperty("sleepingNanos", Long.toUnsignedString(split.sleeping()));
                    row.addProperty("runqueueNanos", Long.toUnsignedString(split.runqueue()));
                    row.addProperty("unsplitNanos", Long.toUnsignedString(split.unsplit()));
                    row.addProperty("estimatedSleepingNanos", Long.toUnsignedString(split.estimatedSleeping()));
                    row.addProperty("estimatedRunqueueNanos", Long.toUnsignedString(split.estimatedRunqueue()));
                    row.addProperty("estimatedUnsplitNanos", Long.toUnsignedString(split.estimatedUnsplit()));
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
