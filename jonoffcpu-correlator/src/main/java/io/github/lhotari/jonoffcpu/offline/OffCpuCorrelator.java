// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Command-line entry point for the two-input Java correlator. */
public final class OffCpuCorrelator {
    record OutputOptions(
            boolean collapsed,
            boolean compatibilityJfr,
            boolean populationEstimate,
            CompatibilityJfrWriter.Options jfrOptions) {
        public OutputOptions {
            if (!collapsed && !compatibilityJfr)
                throw new IllegalArgumentException("Select at least one output format");
            if (jfrOptions == null) throw new IllegalArgumentException("Missing compatibility JFR options");
        }

        public static OutputOptions defaults() {
            return new OutputOptions(true, true, false, CompatibilityJfrWriter.Options.defaults());
        }
    }

    /** Summary returned by the dependency-free Java API after publishing a complete analysis. */
    public record Summary(
            Path outputDirectory,
            int sourceRows,
            int jfrSamples,
            int matched,
            int unmatchedSource,
            int orphanJfr,
            int invalidSource,
            int invalidJfr,
            int identityUnverified,
            BigInteger selectedObservedDurationNanos) {}

    private OffCpuCorrelator() {}

    /**
     * Correlates one finalized capture with default limits and writes all derived outputs. The output
     * directory must not exist.
     */
    public static Summary correlate(Path source, Path jfr, Path outputDirectory) throws IOException {
        OfflineCorrelator.Analysis result =
                OfflineCorrelator.correlate(source, jfr, OfflineCorrelator.Limits.defaults());
        write(result, outputDirectory);
        return new Summary(
                outputDirectory,
                result.sourceRows(),
                result.jfrSamples(),
                result.matched(),
                result.unmatchedSource(),
                result.orphanJfr(),
                result.invalidSource(),
                result.invalidJfr(),
                result.identityUnverified(),
                new BigInteger(result.selectedObservedDurationNanos()));
    }

    public static void main(String[] args) throws Exception {
        int status = run(args);
        if (status != 0) System.exit(status);
    }

    /** Returns 0 for complete analysis or 2 for explicitly incomplete diagnostics; failures throw. */
    public static int run(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Usage: java -jar jonoffcpu-correlator.jar"
                    + " --source capture.jsonl --jfr original.jfr --output new-directory"
                    + " [--from TIME] [--to TIME] [--partial-jfr true|false]"
                    + " [--from-ns N] [--to-ns N] [--max-handler-delay-ns N]"
                    + " [--max-rows N] [--max-retained-bytes N] [--format both|collapsed|jfr]"
                    + " [--quantum-ns N] [--max-synthetic-events N] [--estimate-population true|false]"
                    + " [--partial true|false (partial format: diagnostics|collapsed)]");
            return 0;
        }
        Map<String, String> options = new HashMap<>();
        Set<String> allowed = Set.of(
                "--source",
                "--jfr",
                "--output",
                "--from-ns",
                "--to-ns",
                "--max-handler-delay-ns",
                "--max-rows",
                "--max-retained-bytes",
                "--format",
                "--quantum-ns",
                "--max-synthetic-events",
                "--estimate-population",
                "--partial",
                "--from",
                "--to",
                "--partial-jfr");
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length
                    || !allowed.contains(args[i])
                    || options.putIfAbsent(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Unknown, duplicate or missing option: " + args[i]);
            }
        }
        for (String required : Set.of("--source", "--jfr", "--output")) {
            if (!options.containsKey(required)) throw new IllegalArgumentException("Missing " + required);
        }
        var defaults = OfflineCorrelator.Limits.defaults();
        var limits = new OfflineCorrelator.Limits(
                Integer.parseInt(options.getOrDefault("--max-rows", Integer.toString(defaults.maxRows()))),
                defaults.maxLineBytes(),
                Long.parseLong(
                        options.getOrDefault("--max-retained-bytes", Long.toString(defaults.maxRetainedBytes()))),
                defaults.maxFrames(),
                decimal(options, "--max-handler-delay-ns"),
                decimal(options, "--from-ns"),
                decimal(options, "--to-ns"));
        boolean partial = booleanOption(options, "--partial", false);
        boolean partialJfr = booleanOption(options, "--partial-jfr", false);
        boolean estimatePopulation = booleanOption(options, "--estimate-population", false);
        boolean hasJfrRange = options.containsKey("--from") || options.containsKey("--to");
        if (partial && (partialJfr || hasJfrRange)) {
            throw new IllegalArgumentException("Incomplete-capture mode cannot be combined with JFR selection");
        }
        if (estimatePopulation && (partialJfr || hasJfrRange)) {
            throw new IllegalArgumentException("Population estimates require the complete unselected JFR");
        }
        String format = options.getOrDefault("--format", partial ? "diagnostics" : "both");
        if (partial) {
            if (!Set.of("diagnostics", "collapsed").contains(format)
                    || estimatePopulation
                    || options.containsKey("--quantum-ns")
                    || options.containsKey("--max-synthetic-events")) {
                throw new IllegalArgumentException("Partial mode supports diagnostics or labelled collapsed output;"
                        + " synthetic JFR and population estimates require complete analysis");
            }
            var result = OfflineCorrelator.correlatePartial(
                    Path.of(options.get("--source")), Path.of(options.get("--jfr")), limits);
            writePartial(result, Path.of(options.get("--output")), format.equals("collapsed"));
            System.out.println("Wrote INCOMPLETE diagnostics to "
                    + options.get("--output")
                    + ": "
                    + result.provisionalPairs()
                    + " provisional prefix pairs; coverage is incomplete");
            return 2;
        }
        if (!Set.of("both", "collapsed", "jfr").contains(format))
            throw new IllegalArgumentException("Invalid output format");
        var jfrDefaults = CompatibilityJfrWriter.Options.defaults();
        var jfrOptions = new CompatibilityJfrWriter.Options(
                Long.parseLong(options.getOrDefault("--quantum-ns", Long.toString(jfrDefaults.quantumNanos()))),
                Long.parseLong(options.getOrDefault(
                        "--max-synthetic-events", Long.toString(jfrDefaults.maxSyntheticEvents()))));
        Path jfr = Path.of(options.get("--jfr"));
        OfflineCorrelator.JfrSelection selection = null;
        if (partialJfr || hasJfrRange) {
            JfrTimeRange.Range range =
                    hasJfrRange ? JfrTimeRange.resolve(jfr, options.get("--from"), options.get("--to")) : null;
            selection = new OfflineCorrelator.JfrSelection(
                    range == null ? null : range.from(), range == null ? null : range.to(), partialJfr);
        }
        var result = selection == null
                ? OfflineCorrelator.correlate(Path.of(options.get("--source")), jfr, limits)
                : OfflineCorrelator.correlate(Path.of(options.get("--source")), jfr, limits, selection);
        write(
                result,
                Path.of(options.get("--output")),
                new OutputOptions(!format.equals("jfr"), !format.equals("collapsed"), estimatePopulation, jfrOptions));
        System.out.println("Wrote validated analysis to "
                + options.get("--output")
                + ": "
                + result.matched()
                + " matches; "
                + result.selectedObservedDurationNanos()
                + " selected observed ns");
        return 0;
    }

    /**
     * Writes to a new directory, never replacing existing inputs or reports. Consumers must require
     * {@code complete.json}: it is created last. An interrupted output has no completion marker and
     * is not a complete analysis, even if a partial collapsed file is present.
     */
    static void write(OfflineCorrelator.Analysis result, Path directory) throws IOException {
        write(result, directory, OutputOptions.defaults());
    }

    /**
     * Writes selected derived views and always retains classified observations and interpretation
     * metadata.
     */
    static void write(OfflineCorrelator.Analysis result, Path directory, OutputOptions options) throws IOException {
        Files.createDirectory(directory);
        Gson gson = new GsonBuilder().serializeNulls().create();
        if (options.collapsed()) {
            try (BufferedWriter writer = newFile(directory.resolve("offcpu-signal-delivery-stacks.collapsed"))) {
                for (var entry : new TreeMap<>(result.collapsedNanos()).entrySet()) {
                    writer.write(entry.getKey());
                    writer.write(' ');
                    writer.write(collapsedMicros(entry.getValue()));
                    writer.newLine();
                }
            }
        }
        CompatibilityJfrWriter.Result compatibility = options.compatibilityJfr()
                ? CompatibilityJfrWriter.write(result, directory.resolve("offcpu-synthetic.jfr"), options.jfrOptions())
                : null;
        try (BufferedWriter writer = newFile(directory.resolve("classified-records.jsonl"))) {
            for (var row : result.records()) {
                gson.toJson(row, writer);
                writer.newLine();
            }
        }
        try (BufferedWriter writer = newFile(directory.resolve("matches.jsonl"))) {
            for (var match : result.matches()) {
                JsonObject row = new JsonObject();
                row.addProperty(
                        "correlationId",
                        match.observation().get("correlationId").getAsString());
                row.addProperty("fromNanos", match.fromNanos().toString());
                row.addProperty("toNanos", match.toNanos().toString());
                row.addProperty("durationNanos", match.durationNanos().toString());
                row.addProperty("handlerDelayNanos", match.handlerDelayNanos().toString());
                row.addProperty("threadIdentityVerified", match.threadIdentityVerified());
                gson.toJson(row, writer);
                writer.newLine();
            }
        }
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty(
                "stackSemantics", "signal-delivery stack; not guaranteed to match the eBPF scheduler-exit stack");
        report.addProperty(
                "weightSemantics",
                "collapsed stacks use rounded integer microseconds; exact selected duration remains in nanoseconds");
        report.add("analysisInputs", result.analysisInputs());
        report.add("sourceCounters", result.sourceCounters());
        report.addProperty("apStoppedAtNanos", result.apStoppedAtNanos());
        report.addProperty("sourceRows", result.sourceRows());
        report.addProperty("jfrSamples", result.jfrSamples());
        report.addProperty("matched", result.matched());
        report.addProperty("unmatchedSource", result.unmatchedSource());
        report.addProperty(
                "sourceRowsWithoutSelectedJfrSample",
                result.records().stream()
                        .filter(row -> "source".equals(row.stream())
                                && "sample-not-present-in-selected-jfr".equals(row.reason()))
                        .count());
        report.addProperty("orphanJfr", result.orphanJfr());
        report.addProperty("invalidSource", result.invalidSource());
        report.addProperty("invalidJfr", result.invalidJfr());
        report.addProperty("identityUnverified", result.identityUnverified());
        report.addProperty("selectedObservedDurationNanos", result.selectedObservedDurationNanos());
        report.addProperty("submittedButNotParsed", result.submittedButNotParsed());
        if (result.jfrSelection() != null) {
            report.add("jfrSelection", result.jfrSelection());
        }
        if (options.populationEstimate()) {
            report.add("populationEstimate", gson.toJsonTree(result.populationEstimate()));
        }
        JsonObject delays = new JsonObject();
        List<BigInteger> sortedDelays = result.matches().stream()
                .map(OfflineCorrelator.Match::handlerDelayNanos)
                .sorted()
                .toList();
        delays.addProperty("count", sortedDelays.size());
        delays.addProperty("scope", "source interval end to AP handler timestamp, after clock translation");
        for (int percentile : new int[] {50, 90, 99, 100}) {
            String name = percentile == 100 ? "max" : "p" + percentile;
            if (sortedDelays.isEmpty()) {
                delays.add(name, JsonNull.INSTANCE);
            } else {
                int rank = (int) ((sortedDelays.size() * (long) percentile + 99) / 100);
                delays.addProperty(name, sortedDelays.get(rank - 1).toString());
            }
        }
        report.add("handlerDelayNanos", delays);
        if (compatibility != null) {
            JsonObject view = new JsonObject();
            view.addProperty("path", compatibility.output().getFileName().toString());
            view.addProperty("quantumNanos", Long.toString(compatibility.quantumNanos()));
            view.addProperty("syntheticEvents", Long.toString(compatibility.syntheticEvents()));
            view.addProperty("representedNanos", compatibility.representedNanos());
            view.addProperty("quantizationErrorNanos", compatibility.quantizationErrorNanos());
            view.addProperty("omittedRemainderNanos", compatibility.omittedRemainderNanos());
            report.add("syntheticJfr", view);
        }
        try (BufferedWriter writer = newFile(directory.resolve("report.json"))) {
            // Explicit nulls keep the echoed sampling bounds and an unavailable estimate visible as such.
            new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report, writer);
            writer.newLine();
        }
        try (BufferedWriter writer = newFile(directory.resolve("complete.json"))) {
            writer.write("{\"schemaVersion\":1,\"state\":\"complete\"}\n");
        }
    }

    /**
     * Writes visibly incomplete diagnostic artifacts; this overload cannot publish a complete
     * analysis.
     */
    static void writePartial(OfflineCorrelator.PartialAnalysis result, Path directory, boolean collapsed)
            throws IOException {
        Files.createDirectory(directory);
        Gson gson = new GsonBuilder().serializeNulls().create();
        if (collapsed) {
            try (BufferedWriter writer =
                    newFile(directory.resolve("INCOMPLETE-offcpu-signal-delivery-stacks.collapsed"))) {
                for (var entry : new TreeMap<>(result.collapsedNanos()).entrySet()) {
                    writer.write("[INCOMPLETE capture: observed prefix only];");
                    writer.write(entry.getKey());
                    writer.write(' ');
                    writer.write(collapsedMicros(entry.getValue()));
                    writer.newLine();
                }
            }
        }
        try (BufferedWriter writer = newFile(directory.resolve("INCOMPLETE-classified-records.jsonl"))) {
            for (var record : result.records()) {
                JsonObject row = gson.toJsonTree(record).getAsJsonObject();
                row.addProperty("state", "incomplete");
                row.addProperty("analysisMode", "partial");
                gson.toJson(row, writer);
                writer.newLine();
            }
        }
        try (BufferedWriter writer = newFile(directory.resolve("INCOMPLETE-pairs.jsonl"))) {
            for (var pair : result.pairs()) {
                JsonObject row = new JsonObject();
                row.addProperty("state", "incomplete");
                row.addProperty("pairFinality", "provisional-within-recovered-records");
                row.addProperty(
                        "correlationId", pair.observation().get("correlationId").getAsString());
                row.addProperty("fromNanos", pair.fromNanos().toString());
                row.addProperty("toNanos", pair.toNanos().toString());
                row.addProperty("durationNanos", pair.durationNanos().toString());
                row.addProperty(
                        "handlerDelayNanos",
                        pair.handlerDelayNanos() == null
                                ? null
                                : pair.handlerDelayNanos().toString());
                row.addProperty("threadIdentityVerified", pair.threadIdentityVerified());
                gson.toJson(row, writer);
                writer.newLine();
            }
        }
        JsonObject report = result.diagnostics().deepCopy();
        report.addProperty("schemaVersion", 1);
        report.addProperty("analysisMode", "partial");
        report.addProperty("state", result.state());
        report.addProperty("coverageComplete", result.coverageComplete());
        report.addProperty("pairFinality", "provisional-within-recovered-records");
        report.addProperty(
                "stackSemantics", "signal-delivery stack; not guaranteed to match the eBPF scheduler-exit stack");
        report.addProperty(
                "weightSemantics",
                "collapsed stacks use rounded integer microseconds; observed prefix only; no loss correction");
        report.add("observedSourceCapture", result.sourceCapture());
        report.add("observedSourceEnd", result.sourceEnd());
        report.addProperty("sourceRows", Integer.toString(result.sourceRows()));
        report.addProperty("jfrSamples", Integer.toString(result.jfrSamples()));
        report.addProperty("provisionalPairs", Integer.toString(result.provisionalPairs()));
        report.addProperty("unmatchedSource", Integer.toString(result.unmatchedSource()));
        report.addProperty("orphanJfr", Integer.toString(result.orphanJfr()));
        report.addProperty("invalidSource", Integer.toString(result.invalidSource()));
        report.addProperty("invalidJfr", Integer.toString(result.invalidJfr()));
        report.addProperty("identityUnverified", Integer.toString(result.identityUnverified()));
        report.addProperty("sourceSelectedObservedDurationNanos", result.sourceSelectedObservedDurationNanos());
        report.addProperty("pairedSelectedObservedDurationNanos", result.pairedSelectedObservedDurationNanos());
        report.addProperty("submittedButNotParsed", result.submittedButNotParsed());
        try (BufferedWriter writer = newFile(directory.resolve("INCOMPLETE-report.json"))) {
            new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report, writer);
            writer.newLine();
        }
        JsonObject marker = new JsonObject();
        marker.addProperty("schemaVersion", 1);
        marker.addProperty("analysisMode", "partial");
        marker.addProperty("state", "incomplete");
        marker.addProperty("coverageComplete", false);
        marker.add("incompleteReasons", report.get("incompleteReasons"));
        // A no-replace hard link publishes only the fully written marker, never a partial JSON write.
        Path temporary = Files.createTempFile(directory, ".partial-marker-", ".tmp");
        try {
            Files.writeString(temporary, gson.toJson(marker) + "\n", StandardCharsets.UTF_8);
            Files.createLink(directory.resolve("partial.json"), temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String collapsedMicros(String nanoseconds) {
        BigInteger nanos = new BigInteger(nanoseconds);
        if (nanos.signum() <= 0) return "0";
        return nanos.add(BigInteger.valueOf(500))
                .divide(BigInteger.valueOf(1000))
                .max(BigInteger.ONE)
                .toString();
    }

    private static BufferedWriter newFile(Path file) throws IOException {
        return Files.newBufferedWriter(
                file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static BigInteger decimal(Map<String, String> options, String key) {
        String text = options.get(key);
        if (text == null) return null;
        if (!text.matches("0|[1-9][0-9]{0,19}")) throw new IllegalArgumentException("Invalid nanoseconds: " + key);
        return new BigInteger(text);
    }

    private static boolean booleanOption(Map<String, String> options, String key, boolean defaultValue) {
        String value = options.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }
}
