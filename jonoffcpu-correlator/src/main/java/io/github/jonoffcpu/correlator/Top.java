// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import io.github.jonoffcpu.capture.CaptureProto;
import io.github.jonoffcpu.correlator.AnalysisProto.ComparedRow;
import io.github.jonoffcpu.correlator.AnalysisProto.TableSum;
import io.github.jonoffcpu.correlator.AnalysisProto.TopResult;
import io.github.jonoffcpu.correlator.AnalysisProto.TopRow;
import io.github.jonoffcpu.correlator.AnalysisProto.TopTotals;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Ranked tables of where off-CPU time went, for people and agents who should not have to read a flame graph: each
 * selected interval is either idle, when any frame of any of its stacks matches an idle pattern, or busy, and busy
 * time is attributed by {@link By} to a row. With {@link By#BOUNDARY} a row is the deepest application frame of the
 * stack and the blocker below it, with {@link By#ROOT} the root of the transformed stack, and with {@link
 * By#APP_METHOD} a call chain of application methods; busy time without an application frame is broken down by
 * thread pool instead. {@link StackTransforms.UnmatchedRoot#HIDE} leaves busy time without a {@code --root-at} match
 * out of every mode's rows, into a total and the pool table of its own. Idle time is listed in its own table, never
 * silently dropped, and every total adds up: busy and idle make the selection, and the rows and the pool table make
 * the busy time.
 *
 * <p>The result is a {@link TopResult}, printed as JSON; Markdown and CSV are rendered from it, so no format can
 * disagree with another.
 */
final class Top {
    /** What a row is keyed by. */
    enum By {
        BOUNDARY,
        ROOT,
        APP_METHOD,
        SELF,
        METHOD,
        CLASS,
        PACKAGE,
        POOL;

        String label() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }

        /** Whether the rows cover only busy time with an application frame, the rest going to the pool table. */
        boolean application() {
            return this == BOUNDARY || this == APP_METHOD;
        }
    }

    static final String NO_APPLICATION_FRAME = StackTransforms.NO_APPLICATION_FRAME;

    /**
     * An idle entry that still waited on a lock or a monitor: the waits an idle list may hide by mistake, reported as
     * the over-exclusion check.
     */
    static final Pattern LOCK_ACQUIRE =
            Pattern.compile("^java\\.util\\.concurrent\\.locks\\..*\\.(lock|acquire)\\w*$|complete_monitor_locking");

    private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);

    /** Everything that decides the tables, echoed in their {@code selection}. */
    record Options(
            By by,
            List<StackTransforms.Sourced> app,
            List<StackTransforms.Sourced> idle,
            List<StackTransforms.Sourced> machinery,
            int limit,
            StackTransforms transforms,
            StackProfileRenderer.PackageNames packages,
            StackProfileRenderer.Weights weights,
            Set<OffCpuReason> reasons,
            StackProfileRenderer.Filter filter) {}

    /** One selected profile entry or collapsed line, before attribution. */
    record Item(
            List<StackProfile.Frame> java,
            String thread,
            OffCpuReason reason,
            long intervals,
            BigDecimal weight,
            BigDecimal estimated,
            BigDecimal sleeping,
            BigDecimal runqueue,
            boolean idle) {}

    /**
     * The selected items and what the input can say about them: the unit of their weights, whether estimates, the
     * sleeping/run-queue split and thread pools exist, and the sampling that decides whether observed time is biased.
     * {@code source} names the input in the selection.
     */
    record Input(
            List<Item> items,
            boolean seconds,
            boolean estimateColumn,
            boolean splitColumn,
            boolean pools,
            boolean exhaustiveSampling,
            boolean estimateAvailable,
            AnalysisProto.TopSelection source) {}

    private Top() {}

    // ---- input ---------------------------------------------------------------------------------------------------

    static Input fromProfile(Path path, StackProfile profile, Options options) throws IOException {
        boolean estimated = options.weights() == StackProfileRenderer.Weights.ESTIMATED;
        StackProfile.Header header = profile.header();
        if (estimated) {
            CaptureInput.require(
                    header.estimateAvailable(),
                    "This profile's inverse-probability estimate is unavailable; use observed weights");
        }
        if (options.by() == By.POOL) {
            CaptureInput.require(
                    header.dimensions().contains(ProfileAccumulator.THREAD),
                    "This profile is not grouped by its thread stacks; --by pool needs the thread dimension");
        }
        Predicate<StackProfile.Entry> keeps = options.filter().predicate(profile);
        Predicate<StackProfile.Entry> busy = StackProfileRenderer.Filter.of(
                        List.of(),
                        options.idle().stream()
                                .map(StackTransforms.Sourced::pattern)
                                .toList())
                .predicate(profile);
        Set<OffCpuReason> reasons =
                options.reasons() == null ? EnumSet.copyOf(OffCpuReason.CLASSIFIED) : EnumSet.copyOf(options.reasons());
        Thinning thinning = estimated ? Thinning.NONE : header.thinning();
        List<Item> items = new ArrayList<>();
        for (StackProfile.Entry entry : profile.entries()) {
            if (!reasons.contains(entry.reason()) || !keeps.test(entry)) continue;
            long nanos = estimated ? entry.estimatedNanos() : entry.observedNanos();
            BigDecimal weight = new BigDecimal(
                    thinning.active() ? thinning.scale(nanos) : new BigInteger(Long.toUnsignedString(nanos)));
            items.add(new Item(
                    entry.javaStack() == null ? List.of() : entry.javaStack(),
                    entry.thread(),
                    entry.reason(),
                    entry.intervals(),
                    weight,
                    header.estimateAvailable() && !estimated ? unsigned(entry.estimatedNanos()) : null,
                    header.timeSplitAvailable()
                            ? unsigned(entry.split().nanos(TimeSplit.Part.SLEEPING, estimated))
                            : null,
                    header.timeSplitAvailable()
                            ? unsigned(entry.split().nanos(TimeSplit.Part.RUNQUEUE, estimated))
                            : null,
                    !busy.test(entry)));
        }
        AnalysisProto.TopSelection source = AnalysisProto.TopSelection.newBuilder()
                .setProfile(path.toString())
                .setRun(StackProfileRenderer.defaultRun(profile))
                .build();
        return new Input(
                items,
                true,
                header.estimateAvailable() && !estimated,
                header.timeSplitAvailable(),
                header.dimensions().contains(ProfileAccumulator.THREAD),
                exhaustive(header),
                header.estimateAvailable(),
                source);
    }

    static Input fromCollapsed(Path path, Options options) throws IOException {
        StackProfileRenderer.Filter idle = StackProfileRenderer.Filter.of(
                List.of(),
                options.idle().stream().map(StackTransforms.Sourced::pattern).toList());
        List<Item> items = new ArrayList<>();
        for (CollapsedStacks.Line line : CollapsedStacks.read(path)) {
            List<String> names =
                    line.frames().stream().map(StackProfile.Frame::name).toList();
            if (!options.filter().keeps(names)) continue;
            items.add(new Item(line.frames(), null, null, 1, line.weight(), null, null, null, !idle.keeps(names)));
        }
        AnalysisProto.TopSelection source = AnalysisProto.TopSelection.newBuilder()
                .setCollapsedInput(path.toString())
                .build();
        return new Input(items, false, false, false, false, true, false, source);
    }

    /** Whether every eligible interval was kept, so that observed time is an unbiased weight. */
    static boolean exhaustive(StackProfile.Header header) {
        for (var source : header.sources()) {
            CaptureProto.Sampling sampling = source.getSampling();
            if (sampling.hasProportional()) return false;
            if (sampling.hasUniform()
                    && new BigDecimal(sampling.getUniform().getProbability()).compareTo(BigDecimal.ONE) < 0) {
                return false;
            }
        }
        return true;
    }

    private static BigDecimal unsigned(long value) {
        return new BigDecimal(new BigInteger(Long.toUnsignedString(value)));
    }

    // ---- attribution ---------------------------------------------------------------------------------------------

    /** One row's sums, and the reasons and callers behind it. */
    static final class Row {
        final List<String> key;
        BigDecimal weight = BigDecimal.ZERO;
        long intervals;
        BigDecimal estimated = BigDecimal.ZERO;
        BigDecimal sleeping = BigDecimal.ZERO;
        BigDecimal runqueue = BigDecimal.ZERO;
        final Map<OffCpuReason, BigDecimal> reasons = new EnumMap<>(OffCpuReason.class);
        final Map<String, BigDecimal> callers = new HashMap<>();
        /** {@link By#ROOT}: the transformed lines under the root, abbreviated, by weight. */
        final Map<String, BigDecimal> lines = new HashMap<>();
        /** {@link By#APP_METHOD}: the chain's methods, root-most first, its self time and distinct stacks. */
        List<String> methods;

        BigDecimal self = BigDecimal.ZERO;
        long stacks;

        Row(List<String> key) {
            this.key = key;
        }

        void add(Item item, String caller) {
            add(item, caller, null);
        }

        void add(Item item, String caller, String line) {
            weight = weight.add(item.weight());
            intervals += item.intervals();
            if (item.estimated() != null) estimated = estimated.add(item.estimated());
            if (item.sleeping() != null) sleeping = sleeping.add(item.sleeping());
            if (item.runqueue() != null) runqueue = runqueue.add(item.runqueue());
            if (item.reason() != null) reasons.merge(item.reason(), item.weight(), BigDecimal::add);
            if (caller != null) callers.merge(caller, item.weight(), BigDecimal::add);
            if (line != null) lines.merge(line, item.weight(), BigDecimal::add);
        }

        /** Adds another row's sums, reasons included; its callers and lines are not merged. */
        void merge(Row other) {
            weight = weight.add(other.weight);
            intervals += other.intervals;
            estimated = estimated.add(other.estimated);
            sleeping = sleeping.add(other.sleeping);
            runqueue = runqueue.add(other.runqueue);
            other.reasons.forEach((reason, value) -> reasons.merge(reason, value, BigDecimal::add));
        }

        static String heaviest(Map<String, BigDecimal> weights) {
            return weights.entrySet().stream()
                    .max(Map.Entry.<String, BigDecimal>comparingByValue()
                            .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }

        OffCpuReason reason() {
            return reasons.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }
    }

    /** Sums of a set of items, for the totals. */
    record Sum(long entries, long intervals, BigDecimal weight) {
        static Sum of(List<Item> items) {
            long intervals = 0;
            BigDecimal weight = BigDecimal.ZERO;
            for (Item item : items) {
                intervals += item.intervals();
                weight = weight.add(item.weight());
            }
            return new Sum(items.size(), intervals, weight);
        }
    }

    /** The attribution machinery for one set of options: compiled patterns and per-stack memos. */
    static final class Attribution {
        private final Options options;
        private final List<Pattern> app;
        private final List<Pattern> machinery;
        private final StackTransforms.Compiled names;
        private final StackTransforms.Compiled full;
        private final StackTransforms.Compiled callerPath;
        private final Map<List<StackProfile.Frame>, List<StackProfile.Frame>> named = new IdentityHashMap<>();
        private final Map<List<StackProfile.Frame>, List<StackProfile.Frame>> transformed = new IdentityHashMap<>();
        private final Map<StackProfile.Frame, String> abbreviated = new HashMap<>();

        Attribution(Options options) {
            this.options = options;
            app = options.app().stream().map(StackTransforms.Sourced::compiled).toList();
            machinery = options.machinery().stream()
                    .map(StackTransforms.Sourced::compiled)
                    .toList();
            StackTransforms transforms = options.transforms();
            names = new StackTransforms(
                            transforms.canonicalNames(),
                            transforms.hide(),
                            List.of(),
                            List.of(),
                            StackTransforms.UnmatchedRoot.BUCKET,
                            List.of(),
                            List.of(),
                            false,
                            StackTransforms.ThreadFrame.NONE)
                    .compile();
            full = transforms.compile();
            callerPath = new StackTransforms(
                            transforms.canonicalNames(),
                            transforms.hide(),
                            transforms.trimRoot(),
                            transforms.rootAt(),
                            StackTransforms.UnmatchedRoot.KEEP,
                            List.of(),
                            List.of(),
                            false,
                            StackTransforms.ThreadFrame.NONE)
                    .compile();
        }

        /** The Java stack attribution sees: after canonical names and hidden frames. */
        List<StackProfile.Frame> stack(Item item) {
            return named.computeIfAbsent(item.java(), names::apply);
        }

        /** The Java stack after every transform, as a flame graph with the same options draws it. */
        List<StackProfile.Frame> transformed(Item item) {
            return transformed.computeIfAbsent(item.java(), full::apply);
        }

        /** Whether {@link StackTransforms.UnmatchedRoot#HIDE} leaves this item out. */
        boolean hidden(Item item) {
            return StackTransforms.hidden(item.java(), transformed(item));
        }

        /** Whether the item has an application frame, as {@code by} decides: a boundary, or one after the transforms. */
        boolean hasApplication(Item item, By by) {
            if (by == By.APP_METHOD) {
                for (StackProfile.Frame frame : transformed(item)) {
                    if (isApplication(frame)) return true;
                }
                return false;
            }
            return boundary(stack(item)) >= 0;
        }

        /** A transformed stack as one line with abbreviated package names, as a flame graph would label it. */
        String line(List<StackProfile.Frame> stack) {
            StringBuilder line = new StringBuilder();
            StackProfileRenderer.appendJava(line, stack, StackProfileRenderer.PackageNames.ABBREVIATE, abbreviated);
            return line.toString();
        }

        boolean isApplication(StackProfile.Frame frame) {
            for (Pattern pattern : app) {
                if (pattern.matcher(frame.name()).find()) return true;
            }
            return false;
        }

        /** The index of the deepest application frame, or -1. */
        int boundary(List<StackProfile.Frame> stack) {
            for (int index = stack.size() - 1; index >= 0; index--) {
                if (isApplication(stack.get(index))) return index;
            }
            return -1;
        }

        /**
         * The entry frame of the longest leaf-side run of wait machinery below the boundary, or the leaf when there is
         * no such run: the lock, monitor or park the boundary waited on.
         */
        String blocker(List<StackProfile.Frame> stack, int boundary) {
            int start = stack.size();
            while (start > boundary + 1 && matches(machinery, stack.get(start - 1))) start--;
            return stack.get(start < stack.size() ? start : stack.size() - 1).name();
        }

        String caller(Item item) {
            if (app.isEmpty()) return null;
            for (StackProfile.Frame frame : callerPath.apply(item.java())) {
                if (isApplication(frame)) return frame.name();
            }
            return null;
        }

        /** The row keys an item adds to under {@code by}; several for the inclusive modes, each once. */
        List<List<String>> keys(Item item, By by) {
            List<StackProfile.Frame> stack = stack(item);
            return switch (by) {
                case BOUNDARY -> {
                    int boundary = boundary(stack);
                    yield List.of(
                            boundary < 0
                                    ? List.of(NO_APPLICATION_FRAME)
                                    : List.of(stack.get(boundary).name(), blocker(stack, boundary)));
                }
                case ROOT -> {
                    List<StackProfile.Frame> transformed = transformed(item);
                    yield List.of(List.of(
                            transformed.isEmpty()
                                    ? hidden(item) ? NO_APPLICATION_FRAME : "[empty stack]"
                                    : transformed.get(0).name()));
                }
                case APP_METHOD -> throw new IllegalArgumentException("Application methods are grouped by chain");
                case SELF -> {
                    List<StackProfile.Frame> transformed = transformed(item);
                    yield List.of(List.of(
                            transformed.isEmpty()
                                    ? "[empty stack]"
                                    : transformed.get(transformed.size() - 1).name()));
                }
                case METHOD ->
                    distinct(stack.stream().map(StackProfile.Frame::name).toList());
                case CLASS, PACKAGE -> {
                    List<String> names = new ArrayList<>();
                    for (StackProfile.Frame frame : stack) {
                        if (StackTransforms.isNative(frame)) continue;
                        String qualified = className(frame.name());
                        if (qualified == null) continue;
                        if (by == By.PACKAGE) {
                            int dot = qualified.lastIndexOf('.');
                            qualified = dot < 0 ? "[default package]" : qualified.substring(0, dot);
                        }
                        names.add(qualified);
                    }
                    yield distinct(names);
                }
                case POOL ->
                    List.of(List.of(
                            item.thread() == null ? "[unknown thread]" : StackTransforms.poolName(item.thread())));
            };
        }

        private static List<List<String>> distinct(List<String> names) {
            return new LinkedHashSet<>(names).stream().map(List::of).toList();
        }

        private static boolean matches(List<Pattern> patterns, StackProfile.Frame frame) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(frame.name()).find()) return true;
            }
            return false;
        }
    }

    /** A Java frame's class, {@code a.b.C} of {@code a.b.C.m}; null when the name has no method part. */
    static String className(String frame) {
        int dot = frame.lastIndexOf('.');
        return dot <= 0 ? null : frame.substring(0, dot);
    }

    static List<Row> aggregate(List<Item> items, Attribution attribution, By by, boolean withCallers) {
        if (by == By.APP_METHOD) return applicationMethods(items, attribution);
        Map<List<String>, Row> rows = new HashMap<>();
        for (Item item : items) {
            String caller = withCallers ? attribution.caller(item) : null;
            String line = by == By.ROOT && !attribution.transformed(item).isEmpty()
                    ? attribution.line(attribution.transformed(item))
                    : null;
            for (List<String> key : attribution.keys(item, by)) {
                rows.computeIfAbsent(key, Row::new).add(item, caller, line);
            }
        }
        List<Row> sorted = new ArrayList<>(rows.values());
        sorted.sort(Comparator.comparing((Row row) -> row.weight)
                .reversed()
                .thenComparing(row -> String.join("\u0000", row.key)));
        return sorted;
    }

    /**
     * {@link By#APP_METHOD}: every distinct application frame of each transformed stack adds the stack's time once
     * (inclusive), and methods that occur in exactly the same distinct stacks are one call chain, one row. A row's
     * self time is that of the stacks whose deepest application frame is in the chain, so self times add up to the
     * items' total. Rows sort by inclusive time, then self time, then the root-most method.
     */
    static List<Row> applicationMethods(List<Item> items, Attribution attribution) {
        Map<List<StackProfile.Frame>, Integer> ids = new HashMap<>();
        List<Row> stacks = new ArrayList<>();
        List<String> deepest = new ArrayList<>();
        Map<String, java.util.BitSet> members = new java.util.LinkedHashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        for (Item item : items) {
            List<StackProfile.Frame> stack = attribution.transformed(item);
            Integer id = ids.get(stack);
            if (id == null) {
                id = stacks.size();
                ids.put(stack, id);
                stacks.add(new Row(List.of()));
                List<String> application = new ArrayList<>();
                for (StackProfile.Frame frame : stack) {
                    if (attribution.isApplication(frame)) application.add(frame.name());
                }
                deepest.add(application.isEmpty() ? null : application.get(application.size() - 1));
                int index = 0;
                for (String method : new LinkedHashSet<>(application)) {
                    members.computeIfAbsent(method, key -> new java.util.BitSet())
                            .set(id);
                    depth.merge(method, index++, Math::min);
                }
            }
            stacks.get(id).add(item, null);
        }
        Map<java.util.BitSet, List<String>> chains = new java.util.LinkedHashMap<>();
        members.forEach((method, in) ->
                chains.computeIfAbsent(in, key -> new ArrayList<>()).add(method));
        List<Row> rows = new ArrayList<>();
        chains.forEach((in, methods) -> {
            List<String> ordered = new ArrayList<>(methods);
            ordered.sort(Comparator.comparing(depth::get));
            Row row = new Row(List.of(ordered.get(0)));
            row.methods = List.copyOf(ordered);
            Set<String> chain = Set.copyOf(ordered);
            in.stream().forEach(id -> {
                row.merge(stacks.get(id));
                if (chain.contains(deepest.get(id))) row.self = row.self.add(stacks.get(id).weight);
            });
            row.stacks = in.cardinality();
            rows.add(row);
        });
        rows.sort(Comparator.comparing((Row row) -> row.weight)
                .reversed()
                .thenComparing(Comparator.comparing((Row row) -> row.self).reversed())
                .thenComparing(row -> row.key.get(0)));
        return rows;
    }

    /** A chain as its row key reads: the method, both ends, or both ends and how many methods it has. */
    static String chain(List<String> methods) {
        return switch (methods.size()) {
            case 1 -> methods.get(0);
            case 2 -> methods.get(0) + " → " + methods.get(1);
            default -> methods.get(0) + " → … (" + methods.size() + ") → " + methods.get(methods.size() - 1);
        };
    }

    /** An identity set of items, for splitting a list by membership. */
    private static Set<Item> identities(List<Item> items) {
        Set<Item> set = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(items);
        return set;
    }

    // ---- the result ---------------------------------------------------------------------------------------------

    /** The ranked tables. */
    static TopResult tables(Input input, Options options, String command) {
        Attribution attribution = new Attribution(options);
        List<Item> busy = input.items().stream().filter(item -> !item.idle()).toList();
        List<Item> idle = input.items().stream().filter(Item::idle).toList();
        By by = options.by();
        boolean applicationRanking = by.application();
        boolean hides = options.transforms().unmatchedRoot() == StackTransforms.UnmatchedRoot.HIDE;
        List<Item> hidden = hides ? busy.stream().filter(attribution::hidden).toList() : List.of();
        // The rows' items: busy ones not hidden and, for an application ranking, with an application frame. The rest
        // of the busy time is in the pool table.
        List<Item> application = busy.stream()
                .filter(item -> !hides || !attribution.hidden(item))
                .filter(item -> !applicationRanking || attribution.hasApplication(item, by))
                .toList();
        Set<Item> inRows = identities(application);
        List<Item> noApplication =
                busy.stream().filter(item -> !inRows.contains(item)).toList();
        TopResult.Builder result = TopResult.newBuilder()
                .setCommand(command)
                .setBy(by.label())
                .setUnit(input.seconds() ? "seconds" : "weight")
                .setSelection(selection(input, options));

        Sum busySum = Sum.of(busy);
        Sum applicationSum = Sum.of(application);
        TopTotals.Builder totals = TopTotals.newBuilder()
                .setSelected(sum(Sum.of(input.items()), input))
                .setIdle(sum(Sum.of(idle), input))
                .setBusy(sum(busySum, input));
        if (applicationRanking) {
            totals.setBusyApplication(sum(applicationSum, input));
            totals.setBusyNoApplicationFrame(sum(Sum.of(noApplication), input));
        }
        if (hides) totals.setBusyRootAtUnmatchedHidden(sum(Sum.of(hidden), input));
        totals.setOverExclusion(sum(
                Sum.of(idle.stream()
                        .filter(item -> item.java().stream()
                                .anyMatch(frame ->
                                        LOCK_ACQUIRE.matcher(frame.name()).find()))
                        .toList()),
                input));
        result.setTotals(totals);

        // Shares are of the time the rows cover whenever some busy time is left out of them; a boundary ranking
        // without hiding keeps its shares of all busy time.
        BigDecimal whole = by == By.BOUNDARY && !hides ? busySum.weight() : applicationSum.weight();
        List<Row> rows = aggregate(application, attribution, by, by == By.BOUNDARY);
        result.addAllRows(rows(rows, whole, input, options));
        if (applicationRanking || hides) {
            List<Row> pools = input.pools()
                    ? aggregate(noApplication, attribution, By.POOL, false)
                    : aggregate(noApplication, attribution, By.BOUNDARY, false);
            result.addAllNoApplicationFrame(rows(pools, busySum.weight(), input, options));
        }
        List<Row> idleRows = applicationRanking
                ? aggregate(idle, attribution, By.BOUNDARY, false).stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                row -> row.key.get(0),
                                java.util.LinkedHashMap::new,
                                java.util.stream.Collectors.toList()))
                        .entrySet()
                        .stream()
                        .map(group -> {
                            Row merged = new Row(List.of(group.getKey()));
                            for (Row row : group.getValue()) {
                                merged.weight = merged.weight.add(row.weight);
                                merged.intervals += row.intervals;
                                merged.estimated = merged.estimated.add(row.estimated);
                                merged.sleeping = merged.sleeping.add(row.sleeping);
                                merged.runqueue = merged.runqueue.add(row.runqueue);
                                row.reasons.forEach(
                                        (reason, weight) -> merged.reasons.merge(reason, weight, BigDecimal::add));
                            }
                            return merged;
                        })
                        .sorted(Comparator.comparing((Row row) -> row.weight)
                                .reversed()
                                .thenComparing(row -> row.key.get(0)))
                        .toList()
                : aggregate(idle, attribution, by, false);
        result.addAllIdle(rows(idleRows, Sum.of(idle).weight(), input, options));
        if (by == By.ROOT
                && options.transforms().rootAt().isEmpty()
                && options.transforms().trimRoot().isEmpty()) {
            result.addWarnings("No --root-at or --trim-root is given, so each root is a thread's entry point, such as"
                    + " Thread.run; give --root-at with your application's patterns to rank where threads entered"
                    + " your code.");
        }
        if ((applicationRanking || hides) && !noApplication.isEmpty() && input.seconds()) {
            result.addWarnings("Busy time without an application frame depends on native symbolization: on a musl"
                    + " image every native frame is /lib/ld-musl-<arch>.so.1, the HotSpot idle patterns cannot match,"
                    + " and idle GC and compiler threads count as busy.");
        }
        if (!input.exhaustiveSampling() && options.weights() == StackProfileRenderer.Weights.OBSERVED) {
            result.addWarnings("Sampling kept only some intervals (proportional or uniform admission), so observed"
                    + " time under-weights short waits; use --weights estimated when the estimate is available.");
        }
        return result.build();
    }

    static AnalysisProto.TopSelection selection(Input input, Options options) {
        AnalysisProto.TopSelection.Builder selection = input.source().toBuilder();
        if (options.reasons() != null) {
            options.reasons().stream().sorted().forEach(reason -> selection.addReasons(reason.proto()));
        }
        return selection
                .setWeights(options.weights().name().toLowerCase(java.util.Locale.ROOT))
                .addAllApp(StackTransforms.patterns(options.app()))
                .addAllIdle(StackTransforms.patterns(options.idle()))
                .addAllMachinery(StackTransforms.patterns(options.machinery()))
                .addAllInclude(StackProfileRenderer.patterns(options.filter().include()))
                .addAllExclude(StackProfileRenderer.patterns(options.filter().exclude()))
                .setTransforms(options.transforms().report())
                .setPackageNames(options.packages().label())
                .setLimit(options.limit())
                .build();
    }

    private static TableSum sum(Sum sum, Input input) {
        return TableSum.newBuilder()
                .setEntries(sum.entries())
                .setIntervals(sum.intervals())
                .setValue(value(sum.weight(), input).toPlainString())
                .build();
    }

    /** A weight in the table's unit: seconds to three decimals, or the collapsed file's own unit. */
    static BigDecimal value(BigDecimal weight, Input input) {
        return (input.seconds() ? weight.divide(NANOS_PER_SECOND) : weight).setScale(3, RoundingMode.HALF_EVEN);
    }

    static BigDecimal share(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO : part.divide(whole, 6, RoundingMode.HALF_EVEN);
    }

    private static List<TopRow> rows(List<Row> rows, BigDecimal whole, Input input, Options options) {
        List<TopRow> result = new ArrayList<>();
        int rank = 0;
        for (Row row : rows) {
            if (rank == options.limit()) break;
            TopRow.Builder item = TopRow.newBuilder().setRank(++rank);
            if (row.methods != null) {
                List<String> methods =
                        row.methods.stream().map(options.packages()::apply).toList();
                item.setKey(chain(methods))
                        .addAllMethods(methods)
                        .setSelf(value(row.self, input).toPlainString())
                        .setStacks(row.stacks);
            } else {
                item.setKey(options.packages().apply(row.key.get(0)));
            }
            if (row.key.size() > 1) item.setBlocker(options.packages().apply(row.key.get(1)));
            item.setValue(value(row.weight, input).toPlainString())
                    .setShare(share(row.weight, whole).toPlainString())
                    .setIntervals(row.intervals);
            if (input.estimateColumn())
                item.setEstimated(value(row.estimated, input).toPlainString());
            if (input.splitColumn()) {
                item.setSleeping(value(row.sleeping, input).toPlainString());
                item.setRunqueue(value(row.runqueue, input).toPlainString());
            }
            // An application method's row spans stacks of any reason, so it names none.
            if (input.seconds() && row.reason() != null && row.methods == null)
                item.setReason(row.reason().proto());
            if (!row.callers.isEmpty()) item.setCaller(options.packages().apply(Row.heaviest(row.callers)));
            if (!row.lines.isEmpty()) item.setHeaviestStack(Row.heaviest(row.lines));
            result.add(item.build());
        }
        return result;
    }

    // ---- comparing two runs --------------------------------------------------------------------------------------

    /** The application boundary rows of two runs side by side, per unit of work when the units are given. */
    static TopResult compare(
            Input input, Input baseline, Options options, BigDecimal units, BigDecimal baselineUnits, String command) {
        Attribution attribution = new Attribution(options);
        Map<String, BigDecimal[]> rows = new HashMap<>();
        BigDecimal[] application = {BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal[] busy = {BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal[] unresolved = {BigDecimal.ZERO, BigDecimal.ZERO};
        List<Input> runs = List.of(baseline, input);
        for (int run = 0; run < 2; run++) {
            for (Item item : runs.get(run).items()) {
                if (item.idle()) continue;
                busy[run] = busy[run].add(item.weight());
                List<StackProfile.Frame> stack = attribution.stack(item);
                if (stack.stream().anyMatch(frame -> frame.name().startsWith("/"))) {
                    unresolved[run] = unresolved[run].add(item.weight());
                }
                int boundary = attribution.boundary(stack);
                if (boundary < 0) continue;
                application[run] = application[run].add(item.weight());
                BigDecimal[] sums = rows.computeIfAbsent(
                        stack.get(boundary).name(), key -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                sums[run] = sums[run].add(item.weight());
            }
        }
        boolean perUnit = units != null && baselineUnits != null;
        record Compared(String boundary, BigDecimal baseline, BigDecimal current, BigDecimal order, BigDecimal delta) {}
        List<Compared> compared = new ArrayList<>();
        for (var row : rows.entrySet()) {
            BigDecimal before = normalise(row.getValue()[0], perUnit ? baselineUnits : null, input);
            BigDecimal after = normalise(row.getValue()[1], perUnit ? units : null, input);
            compared.add(new Compared(
                    row.getKey(),
                    before,
                    after,
                    before.max(after),
                    after.subtract(before).abs()));
        }
        compared.sort(Comparator.comparing(Compared::order)
                .reversed()
                .thenComparing(Comparator.comparing(Compared::delta).reversed())
                .thenComparing(Compared::boundary));
        AnalysisProto.TopSelection.Builder selection = selection(input, options).toBuilder()
                .setBaseline(AnalysisProto.TopInput.newBuilder()
                        .setProfile(baseline.source().getProfile())
                        .setRun(baseline.source().getRun()));
        if (perUnit) {
            selection.setUnits(units.toPlainString()).setBaselineUnits(baselineUnits.toPlainString());
        }
        TopResult.Builder result = TopResult.newBuilder()
                .setCommand(command)
                .setBy("boundary")
                .setUnit(perUnit ? "seconds per unit" : "seconds")
                .setSelection(selection)
                .setComparisonTotals(AnalysisProto.ComparisonTotals.newBuilder()
                        .setBaselineBusy(value(busy[0], input).toPlainString())
                        .setBusy(value(busy[1], input).toPlainString())
                        .setBaselineBusyApplication(value(application[0], input).toPlainString())
                        .setBusyApplication(value(application[1], input).toPlainString()));
        int rank = 0;
        for (Compared row : compared) {
            if (rank == options.limit()) break;
            ComparedRow.Builder item = ComparedRow.newBuilder()
                    .setRank(++rank)
                    .setBoundary(options.packages().apply(row.boundary()))
                    .setDelta(row.current().subtract(row.baseline()).toPlainString());
            BigDecimal[] raw = rows.get(row.boundary());
            if (raw[0].signum() != 0) {
                item.setBaseline(row.baseline().toPlainString())
                        .setBaselineShare(share(raw[0], application[0]).toPlainString());
            }
            if (raw[1].signum() != 0) {
                item.setValue(row.current().toPlainString())
                        .setShare(share(raw[1], application[1]).toPlainString());
            }
            result.addComparison(item);
        }
        BigDecimal unresolvedBaseline = share(unresolved[0], busy[0]);
        BigDecimal unresolvedCurrent = share(unresolved[1], busy[1]);
        if (unresolvedBaseline.subtract(unresolvedCurrent).abs().compareTo(new BigDecimal("0.10")) > 0) {
            result.addWarnings("The runs' unresolved native frames differ by more than 10 points of busy time ("
                    + percent(unresolvedBaseline) + " vs " + percent(unresolvedCurrent) + "): their busy time without"
                    + " an application frame is not comparable; compare the application rows only.");
        }
        if ((!input.exhaustiveSampling() || !baseline.exhaustiveSampling())
                && options.weights() == StackProfileRenderer.Weights.OBSERVED) {
            result.addWarnings("At least one run kept only some intervals and has no population estimate, so observed"
                    + " time and its shares are length-biased toward long waits; correlate with"
                    + " --estimate-population true and compare with --weights estimated.");
        }
        return result.build();
    }

    private static BigDecimal normalise(BigDecimal weight, BigDecimal units, Input input) {
        BigDecimal value = input.seconds() ? weight.divide(NANOS_PER_SECOND) : weight;
        if (units != null) value = value.divide(units, 12, RoundingMode.HALF_EVEN);
        return value.setScale(3, RoundingMode.HALF_EVEN);
    }

    // ---- rendering -----------------------------------------------------------------------------------------------

    static String percent(BigDecimal share) {
        return share.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_EVEN) + " %";
    }

    static String percent(String share) {
        return share.isEmpty() ? "" : percent(new BigDecimal(share));
    }

    /** Markdown code for a cell: frame names carry {@code $}, {@code *} and {@code |}, which must not render. */
    static String code(String text) {
        if (text == null) return "";
        return "`" + text.replace("|", "\\|").replace("`", "'") + "`";
    }

    /** Markdown: one table per section, each with a heading and the command that reproduces it. */
    static String markdown(TopResult result) {
        StringBuilder text = new StringBuilder();
        boolean seconds = result.getUnit().startsWith("seconds");
        String unit = seconds ? "s" : "weight";
        String count = seconds ? "Intervals" : "Lines";
        text.append("Reproduce: `").append(result.getCommand()).append("`\n\n");
        if (result.hasComparisonTotals()) {
            AnalysisProto.ComparisonTotals totals = result.getComparisonTotals();
            boolean perUnit = result.getUnit().equals("seconds per unit");
            String column = perUnit ? "s/unit" : "s";
            text.append("## Busy application time, compared with the baseline\n\n");
            text.append("Busy application time: ")
                    .append(totals.getBaselineBusyApplication())
                    .append(" s (baseline) vs ")
                    .append(totals.getBusyApplication())
                    .append(" s. Busy total including time without an application frame: ")
                    .append(totals.getBaselineBusy())
                    .append(" s vs ")
                    .append(totals.getBusy())
                    .append(" s.\n\n");
            text.append("| # | Boundary | Baseline ")
                    .append(column)
                    .append(" | ")
                    .append(column)
                    .append(" | Delta | Baseline share | Share |\n|---:|---|---:|---:|---:|---:|---:|\n");
            for (ComparedRow row : result.getComparisonList()) {
                text.append("| ")
                        .append(row.getRank())
                        .append(" | ")
                        .append(code(row.getBoundary()))
                        .append(" | ")
                        .append(row.getBaseline())
                        .append(" | ")
                        .append(row.getValue())
                        .append(" | ")
                        .append(row.getDelta())
                        .append(" | ")
                        .append(percent(row.getBaselineShare()))
                        .append(" | ")
                        .append(percent(row.getShare()))
                        .append(" |\n");
            }
            warnings(result.getWarningsList(), text);
            return text.toString();
        }
        TopTotals totals = result.getTotals();
        text.append("| Slice | Entries | ")
                .append(count)
                .append(" | ")
                .append(unit)
                .append(" |\n|---|---:|---:|---:|\n");
        totalsRows(totals, false, text);
        text.append('\n');
        String by = result.getBy();
        boolean boundary = by.equals("boundary") || by.equals("app-method");
        text.append("## Busy, by ").append(heading(by)).append("\n\n");
        rowsTable(result.getRowsList(), by, unit, count, seconds, text);
        if (boundary || totals.hasBusyRootAtUnmatchedHidden()) {
            text.append("\n## Busy without an application frame, by pool\n\n");
            table(result.getNoApplicationFrameList(), "pool", unit, count, seconds, text);
        }
        text.append("\n## Idle, by ").append(boundary ? "boundary" : by).append("\n\n");
        table(result.getIdleList(), boundary ? "boundary" : keyName(by), unit, count, seconds, text);
        warnings(result.getWarningsList(), text);
        return text.toString();
    }

    /** A ranking's name in a section heading. */
    static String heading(String by) {
        return switch (by) {
            case "boundary" -> "application boundary";
            case "root" -> "root";
            case "app-method" -> "application method";
            default -> by;
        };
    }

    /** The key column's heading of a ranking's rows. */
    static String keyName(String by) {
        return switch (by) {
            case "boundary" -> "boundary";
            case "root" -> "root";
            case "app-method" -> "application methods";
            default -> "key";
        };
    }

    /**
     * A ranking's rows as one table, and for application methods each chain's methods in a {@code <details>} block
     * below it. The digest renders its tables with this too, so a reproduced table reads byte for byte the same.
     */
    static void rowsTable(
            List<TopRow> rows, String by, String unit, String count, boolean seconds, StringBuilder text) {
        boolean methods = by.equals("app-method");
        table(rows, keyName(by), unit, count, seconds && !methods, text);
        if (!methods) return;
        for (TopRow row : rows) {
            if (row.getMethodsCount() < 3) continue;
            text.append("\n<details><summary>")
                    .append(row.getRank())
                    .append(": ")
                    .append(code(row.getKey()))
                    .append("</summary>\n\n");
            for (String method : row.getMethodsList())
                text.append("1. ").append(code(method)).append('\n');
            text.append("\n</details>\n");
        }
    }

    /**
     * The totals as table rows: the selection, idle and busy, and with a boundary the busy time with and without an
     * application frame, then the over-exclusion check. {@code digest} uses the digest's longer labels.
     */
    static void totalsRows(TopTotals totals, boolean digest, StringBuilder text) {
        if (digest) {
            // The digest is about busy time, so it leads with it; its tables and stacks leave idle intervals out.
            busyRows(totals, text);
            totalsRow(totals.getIdle(), "Idle, left out below", text);
            totalsRow(totals.getSelected(), "All selected", text);
            totalsRow(totals.getOverExclusion(), "Over-exclusion check: idle entries with a lock-acquire frame", text);
            return;
        }
        totalsRow(totals.getSelected(), "Selected", text);
        totalsRow(totals.getIdle(), "Idle", text);
        busyRows(totals, text);
        totalsRow(totals.getOverExclusion(), "Over-exclusion: idle entries with a lock-acquire frame", text);
    }

    private static void busyRows(TopTotals totals, StringBuilder text) {
        totalsRow(totals.getBusy(), "Busy", text);
        if (totals.hasBusyApplication()) {
            totalsRow(totals.getBusyApplication(), "Busy, with an application frame", text);
        }
        if (totals.hasBusyNoApplicationFrame()) {
            totalsRow(totals.getBusyNoApplicationFrame(), "Busy, no application frame", text);
        }
        if (totals.hasBusyRootAtUnmatchedHidden()) {
            totalsRow(
                    totals.getBusyRootAtUnmatchedHidden(),
                    "Busy without an application frame, hidden by --root-at",
                    text);
        }
    }

    private static void totalsRow(TableSum sum, String label, StringBuilder text) {
        text.append("| ")
                .append(label)
                .append(" | ")
                .append(sum.getEntries())
                .append(" | ")
                .append(sum.getIntervals())
                .append(" | ")
                .append(sum.getValue())
                .append(" |\n");
    }

    private static void warnings(List<String> warnings, StringBuilder text) {
        if (warnings.isEmpty()) return;
        text.append('\n');
        for (String warning : warnings)
            text.append("> **Warning:** ").append(warning).append("\n");
    }

    /**
     * One ranked table. {@code keyName} heads the key column; {@code reasons} adds the dominant-reason column, which
     * only a profile's rows have.
     */
    static void table(
            List<TopRow> rows, String keyName, String unit, String count, boolean reasons, StringBuilder text) {
        if (rows.isEmpty()) {
            text.append("(none)\n");
            return;
        }
        TopRow first = rows.get(0);
        List<String> columns = new ArrayList<>(List.of("#", capital(keyName)));
        if (first.hasBlocker()) columns.add("Blocker");
        columns.addAll(List.of(unit, "Share"));
        if (first.hasSelf()) columns.addAll(List.of("Self " + unit, "Stacks"));
        columns.add(count);
        if (first.hasEstimated()) columns.add("Estimated " + unit);
        if (first.hasSleeping()) columns.addAll(List.of("Sleeping " + unit, "Run queue " + unit));
        if (reasons) columns.add("Reason");
        boolean callers = rows.stream().anyMatch(TopRow::hasCaller);
        if (callers) columns.add("Caller");
        boolean heaviest = rows.stream().anyMatch(TopRow::hasHeaviestStack);
        if (heaviest) columns.add("Heaviest stack");
        text.append("| ").append(String.join(" | ", columns)).append(" |\n|");
        for (String column : columns) {
            text.append(
                    column.equals("#")
                                    || column.startsWith(unit)
                                    || column.equals("Share")
                                    || column.equals(count)
                                    || column.startsWith("Self")
                                    || column.equals("Stacks")
                                    || column.startsWith("Estimated")
                                    || column.startsWith("Sleeping")
                                    || column.startsWith("Run queue")
                            ? "---:|"
                            : "---|");
        }
        text.append('\n');
        for (TopRow row : rows) {
            List<String> cells = new ArrayList<>();
            cells.add(Integer.toString(row.getRank()));
            cells.add(code(row.getKey()));
            if (first.hasBlocker()) cells.add(row.hasBlocker() ? code(row.getBlocker()) : "");
            cells.add(row.getValue());
            cells.add(percent(row.getShare()));
            if (first.hasSelf()) {
                cells.add(row.getSelf());
                cells.add(Long.toString(row.getStacks()));
            }
            cells.add(Long.toString(row.getIntervals()));
            if (first.hasEstimated()) cells.add(row.getEstimated());
            if (first.hasSleeping()) {
                cells.add(row.getSleeping());
                cells.add(row.getRunqueue());
            }
            if (reasons) cells.add(row.hasReason() ? label(row.getReason()) : "");
            if (callers) cells.add(row.hasCaller() ? code(row.getCaller()) : "");
            if (heaviest) cells.add(row.hasHeaviestStack() ? code(row.getHeaviestStack()) : "");
            text.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
    }

    /** A reason as the command line and the collapsed frames spell it. */
    static String label(CaptureProto.OffCpuReason reason) {
        OffCpuReason value = OffCpuReason.fromWire(reason.getNumber());
        return value == null ? "" : value.label();
    }

    private static String capital(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** CSV: one row per table row, with a {@code table} column naming its table. */
    static String csv(TopResult result) {
        StringBuilder text = new StringBuilder();
        if (result.hasComparisonTotals()) {
            text.append("table,rank,boundary,baseline,value,delta,baseline_share,share\n");
            for (ComparedRow row : result.getComparisonList()) {
                text.append(String.join(
                                ",",
                                "comparison",
                                Integer.toString(row.getRank()),
                                csvCell(row.getBoundary()),
                                row.getBaseline(),
                                row.getValue(),
                                row.getDelta(),
                                row.getBaselineShare(),
                                row.getShare()))
                        .append('\n');
            }
            return text.toString();
        }
        boolean seconds = result.getUnit().equals("seconds");
        text.append("table,rank,key,blocker,value,share,")
                .append(seconds ? "intervals" : "lines")
                .append(",estimated,sleeping,runqueue,reason,caller,heaviest_stack,self,stacks,methods\n");
        boolean boundary = result.getBy().equals("boundary")
                || result.getBy().equals("app-method")
                || result.getTotals().hasBusyRootAtUnmatchedHidden();
        List<Map.Entry<String, List<TopRow>>> tables = new ArrayList<>();
        tables.add(Map.entry("rows", result.getRowsList()));
        if (boundary) tables.add(Map.entry("noApplicationFrame", result.getNoApplicationFrameList()));
        tables.add(Map.entry("idle", result.getIdleList()));
        for (Map.Entry<String, List<TopRow>> table : tables) {
            for (TopRow row : table.getValue()) {
                text.append(String.join(
                                ",",
                                table.getKey(),
                                Integer.toString(row.getRank()),
                                csvCell(row.getKey()),
                                csvCell(row.getBlocker()),
                                row.getValue(),
                                row.getShare(),
                                Long.toString(row.getIntervals()),
                                row.getEstimated(),
                                row.getSleeping(),
                                row.getRunqueue(),
                                row.hasReason() ? label(row.getReason()) : "",
                                csvCell(row.getCaller()),
                                csvCell(row.getHeaviestStack()),
                                row.getSelf(),
                                row.hasStacks() ? Long.toString(row.getStacks()) : "",
                                csvCell(String.join(";", row.getMethodsList()))))
                        .append('\n');
            }
        }
        return text.toString();
    }

    private static String csvCell(String value) {
        if (value.chars().noneMatch(c -> c == ',' || c == '"' || c == '\n' || c == '\r')) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** A command line as a shell would need it, single-quoting what is not plainly safe. */
    static String shell(List<String> words) {
        List<String> quoted = new ArrayList<>();
        for (String word : words) {
            boolean plain = word.equals(Cli.NAME) || word.matches("[A-Za-z0-9_./:=,+@%-]+");
            quoted.add(plain ? word : "'" + word.replace("'", "'\\''") + "'");
        }
        return String.join(" ", quoted);
    }
}
