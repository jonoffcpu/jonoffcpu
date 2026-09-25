// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import io.github.jonoffcpu.codec.ProtoJson;
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

    /**
     * How a Java frame's package is shown: in full, abbreviated to the first letter of each package segment
     * ({@code i.n.c.e.Native.epollWait0}), or dropped ({@code Native.epollWait0}). Only the display changes: filters
     * still match the full names, and frames that become equal merge into one line. Native frames of the Java stack
     * ({@code libjvm.so.Unsafe_Park}) are left as they are: by their {@link StackProfile.Kind#JFR_NATIVE} kind, and,
     * as a second line of defence, by a name that is recognisably native.
     */
    enum PackageNames {
        FULL,
        ABBREVIATE,
        DROP;

        /**
         * A qualified Java frame: package segments, then a class and a method, all without further dots except a
         * hidden class's {@code .0x} suffix ({@code Foo$$Lambda.0x0000000801234567}).
         */
        private static final Pattern QUALIFIED = Pattern.compile(
                "^((?:[\\p{L}_$][\\p{L}\\p{N}_$]*\\.)+)([\\p{L}_$][^.]*(?:\\.0x\\p{XDigit}+)?\\.[\\p{L}_$<][^.]*)$");
        /** A shared-library segment such as {@code libjvm.so.} or {@code libc.so.6.}: a native frame's library. */
        private static final Pattern SHARED_LIBRARY = Pattern.compile("\\.so(\\.\\d+)*\\.");

        String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        static PackageNames parse(String text) {
            for (PackageNames mode : values()) {
                if (mode.label().equals(text)) return mode;
            }
            throw new IllegalArgumentException("Invalid package names mode: " + text);
        }

        /** The frame as shown; only a {@link StackProfile.Kind#JAVA JAVA} frame's package is ever changed. */
        String apply(StackProfile.Frame frame) {
            return frame.kind() == StackProfile.Kind.JAVA ? apply(frame.name()) : frame.name();
        }

        /**
         * A Java frame's name as shown. A name that is not a package-qualified {@code Class.method}, or that reads
         * as native — a C++ {@code ::}, a shared library, a path, a bracketed placeholder, or a space — is left
         * unchanged. With kinds the name rule can only ever prevent a rewrite.
         */
        String apply(String frame) {
            if (this == FULL || looksNative(frame)) return frame;
            java.util.regex.Matcher matcher = QUALIFIED.matcher(frame);
            if (!matcher.matches()) return frame;
            if (this == DROP) return matcher.group(2);
            StringBuilder shown = new StringBuilder(frame.length());
            for (String segment : matcher.group(1).split("\\.")) {
                shown.appendCodePoint(segment.codePointAt(0)).append('.');
            }
            return shown.append(matcher.group(2)).toString();
        }

        private static boolean looksNative(String frame) {
            return frame.contains("::")
                    || frame.indexOf(' ') >= 0
                    || frame.startsWith("/")
                    || frame.startsWith("[")
                    || SHARED_LIBRARY.matcher(frame).find();
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
        Predicate<StackProfile.Entry> predicate(StackProfile profile) {
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

        /** Whether a line of plain frame names survives, by the same rules as a profile entry. */
        boolean keeps(List<String> names) {
            if (!active()) return true;
            int flags = match(names);
            if ((flags & EXCLUDED) != 0) return false;
            return include.isEmpty() || (flags & INCLUDED) != 0;
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
     * {@code sleeping} or {@code runqueue} slice leaves out. The hidden totals are the kept entries that
     * {@link StackTransforms.UnmatchedRoot#HIDE} left out of the lines, and out of every other total.
     */
    record Slice(
            Map<String, BigInteger> nanos,
            long intervals,
            BigInteger totalNanos,
            long filteredIntervals,
            BigInteger filteredNanos,
            BigInteger unsplitNanos,
            long hiddenIntervals,
            BigInteger hiddenNanos) {}

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
            Filter filter,
            PackageNames packages)
            throws IOException {
        return render(profile, reasons, kinds, weights, frame, time, filter, packages, StackTransforms.NONE);
    }

    /**
     * As above, with each kept entry's Java stack {@link StackTransforms transformed} before it becomes a line. The
     * filter still sees the untransformed stacks, and transformed lines that read the same merge.
     */
    static Slice render(
            StackProfile profile,
            Set<OffCpuReason> reasons,
            StackKinds kinds,
            Weights weights,
            ReasonFrame frame,
            Time time,
            Filter filter,
            PackageNames packages,
            StackTransforms transforms)
            throws IOException {
        Slice kept =
                project(profile, reasons, kinds, weights, frame, time, packages, transforms, filter.predicate(profile));
        if (!filter.active()) return kept;
        // Rendered unfiltered as well, so the removed time is exact even where thinning rounds line by line.
        Slice all = project(profile, reasons, kinds, weights, frame, time, packages, transforms, entry -> true);
        return new Slice(
                kept.nanos(),
                kept.intervals(),
                kept.totalNanos(),
                Math.subtractExact(all.intervals(), kept.intervals()),
                all.totalNanos().subtract(kept.totalNanos()),
                kept.unsplitNanos(),
                kept.hiddenIntervals(),
                kept.hiddenNanos());
    }

    private static Slice project(
            StackProfile profile,
            Set<OffCpuReason> reasons,
            StackKinds kinds,
            Weights weights,
            ReasonFrame frame,
            Time time,
            PackageNames packages,
            StackTransforms transforms,
            Predicate<StackProfile.Entry> keeps)
            throws IOException {
        // Each distinct Java frame is shortened once per render, and each distinct Java stack transformed once.
        Map<StackProfile.Frame, String> shown = new java.util.HashMap<>();
        StackTransforms.Compiled transform = transforms.compile();
        Map<List<StackProfile.Frame>, List<StackProfile.Frame>> transformed = new IdentityHashMap<>();
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
        if (transforms.threadFrame() != StackTransforms.ThreadFrame.NONE) {
            requireDimension(profile, ProfileAccumulator.THREAD);
        }
        Set<OffCpuReason> selected =
                reasons == null ? EnumSet.copyOf(OffCpuReason.CLASSIFIED) : EnumSet.copyOf(reasons);
        List<StackProfile.Entry> entries = new ArrayList<>();
        Set<OffCpuReason> present = EnumSet.noneOf(OffCpuReason.class);
        long hiddenIntervals = 0;
        BigInteger hidden = BigInteger.ZERO;
        boolean hides = transforms.unmatchedRoot() == StackTransforms.UnmatchedRoot.HIDE;
        for (StackProfile.Entry entry : profile.entries()) {
            if (!selected.contains(entry.reason()) || !keeps.test(entry)) continue;
            // A hidden entry is left out before anything else, so the slice reads as if it had none.
            if (hides
                    && StackTransforms.hidden(
                            entry.javaStack(), transformed.computeIfAbsent(entry.javaStack(), transform::apply))) {
                hiddenIntervals = Math.addExact(hiddenIntervals, entry.intervals());
                hidden = hidden.add(U64.big(
                        time.part != null
                                ? entry.split().nanos(time.part, estimated)
                                : estimated ? entry.estimatedNanos() : entry.observedNanos()));
                continue;
            }
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
            if (transforms.threadFrame() != StackTransforms.ThreadFrame.NONE) {
                String thread = entry.thread() == null ? "[unknown thread]" : escape(entry.thread());
                line.append(
                                transforms.threadFrame() == StackTransforms.ThreadFrame.POOL
                                        ? StackTransforms.poolName(thread)
                                        : thread)
                        .append(';');
            }
            int start = line.length();
            if (kinds.java) {
                List<StackProfile.Frame> stack = transforms.active()
                        ? transformed.computeIfAbsent(entry.javaStack(), transform::apply)
                        : entry.javaStack();
                appendJava(line, stack, packages, shown);
            }
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
        BigInteger hiddenTotal = thinning.active() ? thinning.scale(hidden.longValueExact()) : hidden;
        return new Slice(scaled, intervals, total, 0, BigInteger.ZERO, unsplitTotal, hiddenIntervals, hiddenTotal);
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

    static void appendJava(
            StringBuilder line,
            List<StackProfile.Frame> stack,
            PackageNames packages,
            Map<StackProfile.Frame, String> shown) {
        for (int index = 0; index < stack.size(); index++) {
            if (index > 0) line.append(';');
            StackProfile.Frame frame = stack.get(index);
            line.append(packages == PackageNames.FULL ? frame.name() : shown.computeIfAbsent(frame, packages::apply));
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

    static String escape(String name) {
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

    static AnalysisProto.SliceSummary.Builder summary(
            Slice slice,
            StackProfile profile,
            Set<OffCpuReason> reasons,
            StackKinds kinds,
            Weights weights,
            Time time,
            Filter filter,
            PackageNames packages) {
        AnalysisProto.SliceSummary.Builder summary = AnalysisProto.SliceSummary.newBuilder();
        for (OffCpuReason reason : reasons == null ? OffCpuReason.CLASSIFIED : reasons) {
            summary.addReasons(reason.proto());
        }
        summary.setStack(kinds.text)
                .setWeights(weights.name().toLowerCase(java.util.Locale.ROOT))
                .setTime(time.label())
                .setPackageNames(packages.label())
                .setReasonSemantics("switch-out reason; a blocked interval's time is split into sleeping before its"
                        + " wakeup and runqueue after it, and runnable and preempted intervals are runqueue"
                        + " throughout, when the profile has the split")
                .setIntervals(slice.intervals())
                .setLines(slice.nanos().size())
                .setTotalNanos(slice.totalNanos().toString())
                // The kept entries' time without a split: part of a total or split slice, left out of sleeping and
                // runqueue.
                .setUnsplitNanos(slice.unsplitNanos().toString())
                .addAllInclude(patterns(filter.include()))
                .addAllExclude(patterns(filter.exclude()));
        if (filter.active()) summary.addAllFilterScope(Filter.scope(profile));
        // What the filter removed from the selected reasons: add it back to reconcile with an unfiltered slice.
        summary.setFiltered(AnalysisProto.FilteredSlice.newBuilder()
                .setIntervals(slice.filteredIntervals())
                .setTotalNanos(slice.filteredNanos().toString()));
        summary.setLabel(profile.header().label())
                .setSources(profile.header().sources().size());
        return summary;
    }

    /** What {@link StackTransforms.UnmatchedRoot#HIDE} left out of a slice, for its summary. */
    static AnalysisProto.FilteredSlice hidden(Slice slice) {
        return AnalysisProto.FilteredSlice.newBuilder()
                .setIntervals(slice.hiddenIntervals())
                .setTotalNanos(slice.hiddenNanos().toString())
                .build();
    }

    static List<String> patterns(List<Pattern> patterns) {
        return patterns.stream().map(Pattern::pattern).toList();
    }

    /** How {@link #export} writes a profile: the format, and the {@code run} every row is tagged with. */
    record Export(String format, String run) {}

    /** A profile's default {@code run}: its label without the trailing separator, else its first source's session. */
    static String defaultRun(StackProfile profile) {
        String label = profile.header().label();
        while (label.endsWith(";")) label = label.substring(0, label.length() - 1);
        if (!label.isEmpty()) return label;
        var sources = profile.header().sources();
        return sources.isEmpty() ? "" : sources.get(0).getSessionId();
    }

    /**
     * One row per entry with its stacks expanded, for tools such as DuckDB: the run and the entry's identity, its
     * counters, then its stacks, in the order of {@link AnalysisProto.ExportRow}.
     */
    static void export(StackProfile profile, Export options, BufferedWriter writer) throws IOException {
        boolean estimateAvailable = profile.header().estimateAvailable();
        switch (options.format()) {
            case "csv" -> {
                writer.write("run,estimate_available,reason,task_state,thread,thread_pool,"
                        + "intervals,observed_nanos,estimated_nanos,sleeping_nanos,runqueue_nanos,unsplit_nanos,"
                        + "estimated_sleeping_nanos,estimated_runqueue_nanos,estimated_unsplit_nanos,"
                        + "java_stack,java_stack_kinds,canonical_java_stack,kernel_stack,user_stack");
                writer.newLine();
                for (StackProfile.Entry entry : profile.entries()) {
                    String javaStack = joined(entry.javaStack(), false);
                    writer.write(String.join(
                            ",",
                            csv(options.run()),
                            Boolean.toString(estimateAvailable),
                            entry.reason().label(),
                            Integer.toUnsignedString(entry.taskState()),
                            csv(entry.thread()),
                            csv(entry.thread() == null ? null : StackTransforms.poolName(entry.thread())),
                            Long.toString(entry.intervals()),
                            Long.toUnsignedString(entry.observedNanos()),
                            Long.toUnsignedString(entry.estimatedNanos()),
                            Long.toUnsignedString(entry.split().sleeping()),
                            Long.toUnsignedString(entry.split().runqueue()),
                            Long.toUnsignedString(entry.split().unsplit()),
                            Long.toUnsignedString(entry.split().estimatedSleeping()),
                            Long.toUnsignedString(entry.split().estimatedRunqueue()),
                            Long.toUnsignedString(entry.split().estimatedUnsplit()),
                            csv(javaStack),
                            csv(javaKinds(entry.javaStack())),
                            csv(javaStack == null ? null : StackTransforms.canonicalName(javaStack)),
                            csv(joined(entry.kernelStack(), true)),
                            csv(joined(entry.userStack(), true))));
                    writer.newLine();
                }
            }
            case "jsonl" -> {
                for (StackProfile.Entry entry : profile.entries()) {
                    AnalysisProto.ExportRow.Builder row = AnalysisProto.ExportRow.newBuilder();
                    String javaStack = joined(entry.javaStack(), false);
                    row.setReason(entry.reason().proto()).setTaskState(entry.taskState());
                    if (entry.thread() != null) {
                        row.setThread(entry.thread()).setThreadPool(StackTransforms.poolName(entry.thread()));
                    }
                    if (javaStack != null) {
                        row.setJavaStack(javaStack)
                                .setJavaStackKinds(javaKinds(entry.javaStack()))
                                .setCanonicalJavaStack(StackTransforms.canonicalName(javaStack));
                    }
                    if (entry.kernelStack() != null) row.setKernelStack(joined(entry.kernelStack(), true));
                    if (entry.userStack() != null) row.setUserStack(joined(entry.userStack(), true));
                    StackProfile.Split split = entry.split();
                    row.setIntervals(entry.intervals())
                            .setObservedNanos(entry.observedNanos())
                            .setEstimatedNanos(entry.estimatedNanos())
                            .setSleepingNanos(split.sleeping())
                            .setRunqueueNanos(split.runqueue())
                            .setUnsplitNanos(split.unsplit())
                            .setEstimatedSleepingNanos(split.estimatedSleeping())
                            .setEstimatedRunqueueNanos(split.estimatedRunqueue())
                            .setEstimatedUnsplitNanos(split.estimatedUnsplit())
                            .addAllJavaFrames(names(entry.javaStack(), false))
                            .addAllJavaFrameKinds(kinds(entry.javaStack()))
                            .addAllKernelFrames(names(entry.kernelStack(), true))
                            .addAllUserFrames(names(entry.userStack(), true))
                            .setRun(options.run())
                            .setEstimateAvailable(estimateAvailable);
                    writer.write(ProtoJson.line(row.build()));
                    writer.newLine();
                }
            }
            default -> throw new IllegalArgumentException("Invalid export format: " + options.format());
        }
    }

    /**
     * What {@code export --run-metadata} writes: one message describing the profile, joined to its rows on {@code
     * run}, so the provenance, estimate validity and totals no longer need the header or the report beside them.
     */
    static AnalysisProto.RunMetadata runMetadata(StackProfile profile, String run) {
        BigInteger observed = BigInteger.ZERO;
        long intervals = 0;
        for (StackProfile.Entry entry : profile.entries()) {
            observed = observed.add(U64.big(entry.observedNanos()));
            intervals = Math.addExact(intervals, entry.intervals());
        }
        return AnalysisProto.RunMetadata.newBuilder()
                .setRun(run)
                .setLabel(profile.header().label())
                .addAllSources(profile.header().sources())
                .addAllDimensions(profile.header().dimensions())
                .setEstimateAvailable(profile.header().estimateAvailable())
                .setTimeSplitAvailable(profile.header().timeSplitAvailable())
                .setEntries(profile.entries().size())
                .setIntervals(intervals)
                .setObservedNanos(observed.toString())
                .build();
    }

    /** A stack's frames as a list, root first, as {@link #joined} renders them; empty for an absent stack. */
    private static List<String> names(List<StackProfile.Frame> stack, boolean nativeFrames) {
        if (stack == null) return List.of();
        List<String> names = new ArrayList<>(stack.size());
        int end = nativeFrames && !stack.isEmpty() && stack.get(0).kind() == StackProfile.Kind.KERNEL
                ? kernelEnd(stack)
                : stack.size();
        for (int index = 0; index < end; index++) {
            StackProfile.Frame frame = stack.get(index);
            names.add(nativeFrames ? escape(nativeName(frame)) : frame.name());
        }
        return names;
    }

    private static List<String> kinds(List<StackProfile.Frame> stack) {
        if (stack == null) return List.of();
        return stack.stream()
                .map(frame -> frame.kind() == StackProfile.Kind.JAVA ? "java" : "native")
                .toList();
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

    /** Each Java-stack frame's kind, {@code java} or {@code native}, joined like the stack's names. */
    private static String javaKinds(List<StackProfile.Frame> stack) {
        if (stack == null) return null;
        StringBuilder text = new StringBuilder();
        for (StackProfile.Frame frame : stack) {
            if (text.length() > 0) text.append(';');
            text.append(frame.kind() == StackProfile.Kind.JAVA ? "java" : "native");
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
