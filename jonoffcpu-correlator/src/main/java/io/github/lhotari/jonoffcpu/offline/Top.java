// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * stack and the blocker below it, and busy time without an application frame is broken down by thread pool instead.
 * Idle time is listed in its own table, never silently dropped, and every total adds up: busy and idle make the
 * selection, and application rows and the pool table make the busy time.
 *
 * <p>The result is a JSON object; Markdown and CSV are rendered from it, so no format can disagree with another.
 */
final class Top {
    /** What a row is keyed by. */
    enum By {
        BOUNDARY,
        SELF,
        METHOD,
        CLASS,
        PACKAGE,
        POOL;

        String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
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
     */
    record Input(
            List<Item> items,
            boolean seconds,
            boolean estimateColumn,
            boolean splitColumn,
            boolean pools,
            boolean exhaustiveSampling,
            boolean estimateAvailable,
            JsonObject source) {}

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
                options.reasons() == null ? EnumSet.allOf(OffCpuReason.class) : EnumSet.copyOf(options.reasons());
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
        JsonObject source = new JsonObject();
        source.addProperty("profile", path.toString());
        source.addProperty("run", StackProfileRenderer.defaultRun(profile));
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
        JsonObject source = new JsonObject();
        source.addProperty("collapsedInput", path.toString());
        return new Input(items, false, false, false, false, true, false, source);
    }

    /** Whether every eligible interval was kept, so that observed time is an unbiased weight. */
    static boolean exhaustive(StackProfile.Header header) {
        for (StackProfile.Provenance source : header.sources()) {
            if (source.samplingJson() == null || source.samplingJson().isEmpty()) continue;
            JsonObject admission = JsonParser.parseString(source.samplingJson())
                    .getAsJsonObject()
                    .getAsJsonObject("admission");
            if (admission == null) continue;
            String policy = admission.get("policy").getAsString();
            if (policy.equals("proportional")) return false;
            if (policy.equals("uniform")
                    && new BigDecimal(admission.get("probability").getAsString()).compareTo(BigDecimal.ONE) < 0) {
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

        Row(List<String> key) {
            this.key = key;
        }

        void add(Item item, String caller) {
            weight = weight.add(item.weight());
            intervals += item.intervals();
            if (item.estimated() != null) estimated = estimated.add(item.estimated());
            if (item.sleeping() != null) sleeping = sleeping.add(item.sleeping());
            if (item.runqueue() != null) runqueue = runqueue.add(item.runqueue());
            if (item.reason() != null) reasons.merge(item.reason(), item.weight(), BigDecimal::add);
            if (caller != null) callers.merge(caller, item.weight(), BigDecimal::add);
        }

        static String heaviest(Map<String, BigDecimal> weights) {
            return weights.entrySet().stream()
                    .max(Map.Entry.<String, BigDecimal>comparingByValue()
                            .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }

        String reason() {
            return reasons.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(entry -> entry.getKey().label())
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
                            false,
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
                            true,
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
                case SELF -> {
                    List<StackProfile.Frame> transformed = full.apply(item.java());
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
        Map<List<String>, Row> rows = new HashMap<>();
        for (Item item : items) {
            String caller = withCallers ? attribution.caller(item) : null;
            for (List<String> key : attribution.keys(item, by)) {
                rows.computeIfAbsent(key, Row::new).add(item, caller);
            }
        }
        List<Row> sorted = new ArrayList<>(rows.values());
        sorted.sort(Comparator.comparing((Row row) -> row.weight)
                .reversed()
                .thenComparing(row -> String.join("\u0000", row.key)));
        return sorted;
    }

    // ---- the result as JSON --------------------------------------------------------------------------------------

    /** The ranked tables as one versioned object. */
    static JsonObject tables(Input input, Options options, String command) {
        Attribution attribution = new Attribution(options);
        List<Item> busy = input.items().stream().filter(item -> !item.idle()).toList();
        List<Item> idle = input.items().stream().filter(Item::idle).toList();
        boolean boundary = options.by() == By.BOUNDARY;
        List<Item> application = busy;
        List<Item> noApplication = List.of();
        if (boundary) {
            application = busy.stream()
                    .filter(item -> attribution.boundary(attribution.stack(item)) >= 0)
                    .toList();
            noApplication = busy.stream()
                    .filter(item -> attribution.boundary(attribution.stack(item)) < 0)
                    .toList();
        }
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("command", command);
        result.addProperty("by", options.by().label());
        result.addProperty("unit", input.seconds() ? "seconds" : "weight");
        result.add("selection", selection(input, options));

        Sum busySum = Sum.of(busy);
        JsonObject totals = new JsonObject();
        totals.add("selected", sum(Sum.of(input.items()), input));
        totals.add("idle", sum(Sum.of(idle), input));
        totals.add("busy", sum(busySum, input));
        if (boundary) {
            totals.add("busyApplication", sum(Sum.of(application), input));
            totals.add("busyNoApplicationFrame", sum(Sum.of(noApplication), input));
        }
        totals.add(
                "overExclusion",
                sum(
                        Sum.of(idle.stream()
                                .filter(item -> item.java().stream()
                                        .anyMatch(frame -> LOCK_ACQUIRE
                                                .matcher(frame.name())
                                                .find()))
                                .toList()),
                        input));
        result.add("totals", totals);

        List<Row> rows = aggregate(boundary ? application : busy, attribution, options.by(), boundary);
        result.add("rows", rows(rows, busySum.weight(), input, options, boundary ? "boundary" : "key"));
        if (boundary) {
            List<Row> pools = input.pools()
                    ? aggregate(noApplication, attribution, By.POOL, false)
                    : aggregate(noApplication, attribution, By.BOUNDARY, false);
            result.add("noApplicationFrame", rows(pools, busySum.weight(), input, options, "pool"));
        }
        List<Row> idleRows = boundary
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
                : aggregate(idle, attribution, options.by(), false);
        result.add("idle", rows(idleRows, Sum.of(idle).weight(), input, options, boundary ? "boundary" : "key"));
        JsonArray warnings = new JsonArray();
        if (boundary && !noApplication.isEmpty() && input.seconds()) {
            warnings.add("Busy time without an application frame depends on native symbolization: on a musl image"
                    + " every native frame is /lib/ld-musl-<arch>.so.1, the HotSpot idle patterns cannot match, and"
                    + " idle GC and compiler threads count as busy.");
        }
        if (!input.exhaustiveSampling() && options.weights() == StackProfileRenderer.Weights.OBSERVED) {
            warnings.add("Sampling kept only some intervals (proportional or uniform admission), so observed time"
                    + " under-weights short waits; use --weights estimated when the estimate is available.");
        }
        result.add("warnings", warnings);
        return result;
    }

    static JsonObject selection(Input input, Options options) {
        JsonObject selection = input.source().deepCopy();
        JsonArray reasons = new JsonArray();
        if (options.reasons() == null) {
            reasons.add("all");
        } else {
            options.reasons().forEach(reason -> reasons.add(reason.label()));
        }
        selection.add("reasons", reasons);
        selection.addProperty("weights", options.weights().name().toLowerCase(java.util.Locale.ROOT));
        selection.add("app", StackTransforms.patterns(options.app()));
        selection.add("idle", StackTransforms.patterns(options.idle()));
        selection.add("machinery", StackTransforms.patterns(options.machinery()));
        selection.add("include", StackProfileRenderer.patterns(options.filter().include()));
        selection.add("exclude", StackProfileRenderer.patterns(options.filter().exclude()));
        selection.add("transforms", options.transforms().report());
        selection.addProperty("packageNames", options.packages().label());
        selection.addProperty("limit", options.limit());
        return selection;
    }

    private static JsonObject sum(Sum sum, Input input) {
        JsonObject object = new JsonObject();
        object.addProperty("entries", sum.entries());
        object.addProperty(input.seconds() ? "intervals" : "lines", sum.intervals());
        object.addProperty("value", value(sum.weight(), input));
        return object;
    }

    /** A weight in the table's unit: seconds to three decimals, or the collapsed file's own unit. */
    static BigDecimal value(BigDecimal weight, Input input) {
        return (input.seconds() ? weight.divide(NANOS_PER_SECOND) : weight).setScale(3, RoundingMode.HALF_EVEN);
    }

    static BigDecimal share(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO : part.divide(whole, 6, RoundingMode.HALF_EVEN);
    }

    private static JsonArray rows(List<Row> rows, BigDecimal whole, Input input, Options options, String keyName) {
        JsonArray array = new JsonArray();
        int rank = 0;
        for (Row row : rows) {
            if (rank == options.limit()) break;
            JsonObject item = new JsonObject();
            item.addProperty("rank", ++rank);
            item.addProperty(keyName, options.packages().apply(row.key.get(0)));
            if (row.key.size() > 1)
                item.addProperty("blocker", options.packages().apply(row.key.get(1)));
            item.addProperty("value", value(row.weight, input));
            item.addProperty("share", share(row.weight, whole));
            item.addProperty(input.seconds() ? "intervals" : "lines", row.intervals);
            if (input.estimateColumn()) item.addProperty("estimated", value(row.estimated, input));
            if (input.splitColumn()) {
                item.addProperty("sleeping", value(row.sleeping, input));
                item.addProperty("runqueue", value(row.runqueue, input));
            }
            if (input.seconds()) item.addProperty("reason", row.reason());
            if (keyName.equals("boundary") && !row.callers.isEmpty()) {
                item.addProperty("caller", options.packages().apply(Row.heaviest(row.callers)));
            }
            array.add(item);
        }
        return array;
    }

    // ---- comparing two runs --------------------------------------------------------------------------------------

    /** The application boundary rows of two runs side by side, per unit of work when the units are given. */
    static JsonObject compare(
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
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("command", command);
        result.addProperty("by", "boundary");
        result.addProperty("unit", perUnit ? "seconds per unit" : "seconds");
        JsonObject selection = selection(input, options);
        selection.add("baseline", baseline.source());
        if (perUnit) {
            selection.addProperty("units", units);
            selection.addProperty("baselineUnits", baselineUnits);
        }
        result.add("selection", selection);
        JsonObject totals = new JsonObject();
        totals.addProperty("baselineBusy", value(busy[0], input));
        totals.addProperty("busy", value(busy[1], input));
        totals.addProperty("baselineBusyApplication", value(application[0], input));
        totals.addProperty("busyApplication", value(application[1], input));
        result.add("totals", totals);
        JsonArray array = new JsonArray();
        int rank = 0;
        for (Compared row : compared) {
            if (rank == options.limit()) break;
            JsonObject item = new JsonObject();
            item.addProperty("rank", ++rank);
            item.addProperty("boundary", options.packages().apply(row.boundary()));
            BigDecimal[] raw = rows.get(row.boundary());
            item.add("baseline", raw[0].signum() == 0 ? JsonNull.INSTANCE : json(row.baseline()));
            item.add("value", raw[1].signum() == 0 ? JsonNull.INSTANCE : json(row.current()));
            item.addProperty("delta", row.current().subtract(row.baseline()));
            item.add("baselineShare", raw[0].signum() == 0 ? JsonNull.INSTANCE : json(share(raw[0], application[0])));
            item.add("share", raw[1].signum() == 0 ? JsonNull.INSTANCE : json(share(raw[1], application[1])));
            array.add(item);
        }
        result.add("comparison", array);
        JsonArray warnings = new JsonArray();
        BigDecimal unresolvedBaseline = share(unresolved[0], busy[0]);
        BigDecimal unresolvedCurrent = share(unresolved[1], busy[1]);
        if (unresolvedBaseline.subtract(unresolvedCurrent).abs().compareTo(new BigDecimal("0.10")) > 0) {
            warnings.add("The runs' unresolved native frames differ by more than 10 points of busy time ("
                    + percent(unresolvedBaseline) + " vs " + percent(unresolvedCurrent) + "): their busy time without"
                    + " an application frame is not comparable; compare the application rows only.");
        }
        if ((!input.exhaustiveSampling() || !baseline.exhaustiveSampling())
                && options.weights() == StackProfileRenderer.Weights.OBSERVED) {
            warnings.add("At least one run kept only some intervals and has no population estimate, so observed"
                    + " time and its shares are length-biased toward long waits; correlate with"
                    + " --estimate-population true and compare with --weights estimated.");
        }
        result.add("warnings", warnings);
        return result;
    }

    private static BigDecimal normalise(BigDecimal weight, BigDecimal units, Input input) {
        BigDecimal value = input.seconds() ? weight.divide(NANOS_PER_SECOND) : weight;
        if (units != null) value = value.divide(units, 12, RoundingMode.HALF_EVEN);
        return value.setScale(3, RoundingMode.HALF_EVEN);
    }

    private static JsonElement json(BigDecimal value) {
        return new com.google.gson.JsonPrimitive(value);
    }

    // ---- rendering -----------------------------------------------------------------------------------------------

    static String percent(BigDecimal share) {
        return share.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_EVEN) + " %";
    }

    static String number(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        return value.getAsBigDecimal().toPlainString();
    }

    /** Markdown code for a cell: frame names carry {@code $}, {@code *} and {@code |}, which must not render. */
    static String code(String text) {
        if (text == null) return "";
        return "`" + text.replace("|", "\\|").replace("`", "'") + "`";
    }

    /** Markdown: one table per section, each with a heading and the command that reproduces it. */
    static String markdown(JsonObject result) {
        StringBuilder text = new StringBuilder();
        boolean seconds = result.get("unit").getAsString().startsWith("seconds");
        String unit = seconds ? "s" : "weight";
        String count = seconds ? "Intervals" : "Lines";
        String countKey = seconds ? "intervals" : "lines";
        text.append("Reproduce: `").append(result.get("command").getAsString()).append("`\n\n");
        if (result.has("comparison")) {
            JsonObject totals = result.getAsJsonObject("totals");
            boolean perUnit = result.get("unit").getAsString().equals("seconds per unit");
            String column = perUnit ? "s/unit" : "s";
            text.append("## Busy application time, compared with the baseline\n\n");
            text.append("Busy application time: ")
                    .append(number(totals.get("baselineBusyApplication")))
                    .append(" s (baseline) vs ")
                    .append(number(totals.get("busyApplication")))
                    .append(" s. Busy total including time without an application frame: ")
                    .append(number(totals.get("baselineBusy")))
                    .append(" s vs ")
                    .append(number(totals.get("busy")))
                    .append(" s.\n\n");
            text.append("| # | Boundary | Baseline ")
                    .append(column)
                    .append(" | ")
                    .append(column)
                    .append(" | Delta | Baseline share | Share |\n|---:|---|---:|---:|---:|---:|---:|\n");
            for (JsonElement element : result.getAsJsonArray("comparison")) {
                JsonObject row = element.getAsJsonObject();
                text.append("| ")
                        .append(row.get("rank").getAsInt())
                        .append(" | ")
                        .append(code(row.get("boundary").getAsString()))
                        .append(" | ")
                        .append(number(row.get("baseline")))
                        .append(" | ")
                        .append(number(row.get("value")))
                        .append(" | ")
                        .append(number(row.get("delta")))
                        .append(" | ")
                        .append(shareCell(row.get("baselineShare")))
                        .append(" | ")
                        .append(shareCell(row.get("share")))
                        .append(" |\n");
            }
            warnings(result, text);
            return text.toString();
        }
        JsonObject totals = result.getAsJsonObject("totals");
        text.append("| Slice | Entries | ")
                .append(count)
                .append(" | ")
                .append(unit)
                .append(" |\n|---|---:|---:|---:|\n");
        for (String[] slice : new String[][] {
            {"selected", "Selected"},
            {"idle", "Idle"},
            {"busy", "Busy"},
            {"busyApplication", "Busy, with an application frame"},
            {"busyNoApplicationFrame", "Busy, no application frame"},
            {"overExclusion", "Over-exclusion: idle entries with a lock-acquire frame"}
        }) {
            if (!totals.has(slice[0])) continue;
            JsonObject sum = totals.getAsJsonObject(slice[0]);
            text.append("| ")
                    .append(slice[1])
                    .append(" | ")
                    .append(sum.get("entries").getAsLong())
                    .append(" | ")
                    .append(sum.get(countKey).getAsLong())
                    .append(" | ")
                    .append(number(sum.get("value")))
                    .append(" |\n");
        }
        text.append('\n');
        boolean boundary = result.get("by").getAsString().equals("boundary");
        text.append(
                boundary
                        ? "## Busy, by application boundary\n\n"
                        : "## Busy, by " + result.get("by").getAsString() + "\n\n");
        table(result.getAsJsonArray("rows"), boundary ? "boundary" : "key", unit, count, countKey, text);
        if (result.has("noApplicationFrame")) {
            text.append("\n## Busy without an application frame, by pool\n\n");
            table(result.getAsJsonArray("noApplicationFrame"), "pool", unit, count, countKey, text);
        }
        text.append("\n## Idle, by ")
                .append(boundary ? "boundary" : result.get("by").getAsString())
                .append("\n\n");
        table(result.getAsJsonArray("idle"), boundary ? "boundary" : "key", unit, count, countKey, text);
        warnings(result, text);
        return text.toString();
    }

    private static String shareCell(JsonElement share) {
        return share == null || share.isJsonNull() ? "" : percent(share.getAsBigDecimal());
    }

    private static void warnings(JsonObject result, StringBuilder text) {
        JsonArray warnings = result.getAsJsonArray("warnings");
        if (warnings.isEmpty()) return;
        text.append('\n');
        for (JsonElement warning : warnings)
            text.append("> **Warning:** ").append(warning.getAsString()).append("\n");
    }

    static void table(JsonArray rows, String keyName, String unit, String count, String countKey, StringBuilder text) {
        if (rows.isEmpty()) {
            text.append("(none)\n");
            return;
        }
        JsonObject first = rows.get(0).getAsJsonObject();
        List<String> columns = new ArrayList<>(List.of("#", capital(keyName)));
        if (first.has("blocker")) columns.add("Blocker");
        columns.addAll(List.of(unit, "Share", count));
        if (first.has("estimated")) columns.add("Estimated " + unit);
        if (first.has("sleeping")) columns.addAll(List.of("Sleeping " + unit, "Run queue " + unit));
        if (first.has("reason")) columns.add("Reason");
        boolean callers = false;
        for (JsonElement row : rows) callers |= row.getAsJsonObject().has("caller");
        if (callers) columns.add("Caller");
        text.append("| ").append(String.join(" | ", columns)).append(" |\n|");
        for (String column : columns) {
            text.append(
                    column.equals("#")
                                    || column.startsWith(unit)
                                    || column.equals("Share")
                                    || column.equals(count)
                                    || column.startsWith("Estimated")
                                    || column.startsWith("Sleeping")
                                    || column.startsWith("Run queue")
                            ? "---:|"
                            : "---|");
        }
        text.append('\n');
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            List<String> cells = new ArrayList<>();
            cells.add(Integer.toString(row.get("rank").getAsInt()));
            cells.add(code(row.get(keyName).getAsString()));
            if (first.has("blocker"))
                cells.add(row.has("blocker") ? code(row.get("blocker").getAsString()) : "");
            cells.add(number(row.get("value")));
            cells.add(percent(row.get("share").getAsBigDecimal()));
            cells.add(Long.toString(row.get(countKey).getAsLong()));
            if (first.has("estimated")) cells.add(number(row.get("estimated")));
            if (first.has("sleeping")) {
                cells.add(number(row.get("sleeping")));
                cells.add(number(row.get("runqueue")));
            }
            if (first.has("reason"))
                cells.add(
                        row.get("reason").isJsonNull() ? "" : row.get("reason").getAsString());
            if (callers) cells.add(row.has("caller") ? code(row.get("caller").getAsString()) : "");
            text.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
    }

    private static String capital(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** CSV: one row per table row, with a {@code table} column naming its table. */
    static String csv(JsonObject result) {
        StringBuilder text = new StringBuilder();
        if (result.has("comparison")) {
            text.append("table,rank,boundary,baseline,value,delta,baseline_share,share\n");
            for (JsonElement element : result.getAsJsonArray("comparison")) {
                JsonObject row = element.getAsJsonObject();
                text.append(String.join(
                                ",",
                                "comparison",
                                row.get("rank").getAsString(),
                                csvCell(row.get("boundary").getAsString()),
                                number(row.get("baseline")),
                                number(row.get("value")),
                                number(row.get("delta")),
                                number(row.get("baselineShare")),
                                number(row.get("share"))))
                        .append('\n');
            }
            return text.toString();
        }
        String countKey = result.get("unit").getAsString().equals("seconds") ? "intervals" : "lines";
        text.append("table,rank,key,blocker,value,share,")
                .append(countKey)
                .append(",estimated,sleeping,runqueue,reason,caller\n");
        for (String table : List.of("rows", "noApplicationFrame", "idle")) {
            if (!result.has(table)) continue;
            for (JsonElement element : result.getAsJsonArray(table)) {
                JsonObject row = element.getAsJsonObject();
                String key = row.has("boundary")
                        ? row.get("boundary").getAsString()
                        : row.has("pool")
                                ? row.get("pool").getAsString()
                                : row.get("key").getAsString();
                text.append(String.join(
                                ",",
                                table,
                                row.get("rank").getAsString(),
                                csvCell(key),
                                csvCell(row.has("blocker") ? row.get("blocker").getAsString() : ""),
                                number(row.get("value")),
                                number(row.get("share")),
                                row.get(countKey).getAsString(),
                                number(row.get("estimated")),
                                number(row.get("sleeping")),
                                number(row.get("runqueue")),
                                row.has("reason") && !row.get("reason").isJsonNull()
                                        ? row.get("reason").getAsString()
                                        : "",
                                csvCell(row.has("caller") ? row.get("caller").getAsString() : "")))
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
