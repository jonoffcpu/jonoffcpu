// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.offline.StackProfile.Header;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
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
            AuditLevel audit,
            CompatibilityJfrWriter.Options jfrOptions,
            String prefix,
            Degradation ladder,
            boolean stackProfile,
            StackProfileRenderer.ReasonFrame reasonFrame) {
        public OutputOptions {
            if (reasonFrame == null) throw new IllegalArgumentException("Missing reason frame mode");
            if (!collapsed && !compatibilityJfr)
                throw new IllegalArgumentException("Select at least one output format");
            if (audit == null) throw new IllegalArgumentException("Missing audit level");
            if (jfrOptions == null) throw new IllegalArgumentException("Missing compatibility JFR options");
            if (prefix == null) throw new IllegalArgumentException("Missing output name prefix");
            if (ladder == null) throw new IllegalArgumentException("Missing degradation ladder");
        }

        /** True when {@code --format jfr} was asked for explicitly: an unfittable synthetic JFR must abort. */
        boolean jfrOnly() {
            return compatibilityJfr && !collapsed;
        }

        public static OutputOptions defaults() {
            return new OutputOptions(
                    true,
                    true,
                    false,
                    AuditLevel.FULL,
                    CompatibilityJfrWriter.Options.defaults(),
                    OutputFiles.PREFIX,
                    Degradation.none(),
                    true,
                    StackProfileRenderer.ReasonFrame.AUTO);
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
        CorrelationResult result =
                CorrelationEngine.correlate(source, jfr, OfflineCorrelator.Limits.defaults(), null, false);
        write(
                AnalysisOutput.of(result, source, jfr, null),
                outputDirectory,
                OutputOptions.defaults(),
                () -> result.capture().verifyUnchanged(source, jfr));
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
                result.selectedObservedDuration());
    }

    public static void main(String[] args) throws Exception {
        int status = run(args);
        if (status != 0) System.exit(status);
    }

    /**
     * Prints the capture stream as one JSON object per line, with each observation's interned stacks
     * expanded, so humans and tools can read a binary capture without the correlator's analysis.
     */
    private static void dump(Path source) throws IOException {
        Gson gson = new GsonBuilder().serializeNulls().create();
        Map<Long, JsonArray> stacks = new java.util.HashMap<>();
        try (java.io.InputStream input = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(source));
                BufferedWriter writer =
                        new BufferedWriter(new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            CaptureStream.readHeader(input);
            CaptureStream.Framed framed;
            while ((framed = CaptureStream.next(
                            input, OfflineCorrelator.Limits.defaults().maxLineBytes()))
                    != null) {
                if (framed.truncated()) {
                    writer.write(gson.toJson(java.util.Map.of("truncatedTailBytes", framed.bytes().length)));
                    writer.newLine();
                    break;
                }
                JsonObject row =
                        switch (framed.record().getRecordCase()) {
                            case STACK -> {
                                JsonObject stack =
                                        CaptureStream.stackRow(framed.record().getStack());
                                stacks.put(stack.get("stackId").getAsLong(), stack.getAsJsonArray("frames"));
                                yield stack;
                            }
                            case OBSERVATION -> {
                                JsonObject observation = CaptureStream.observationRow(
                                        framed.record().getObservation());
                                for (String stack : List.of("kernelStack", "userStack")) {
                                    JsonArray frames = stacks.get(
                                            observation.get(stack + "Id").getAsLong());
                                    observation.add(stack + "Frames", frames == null ? new JsonArray() : frames);
                                }
                                yield observation;
                            }
                            default ->
                                com.google.gson.JsonParser.parseString(CaptureStream.controlJson(framed.record()))
                                        .getAsJsonObject();
                        };
                writer.write(gson.toJson(row));
                writer.newLine();
            }
        }
    }

    /** Returns 0 for complete analysis or 2 for explicitly incomplete diagnostics; failures throw. */
    public static int run(String[] args) throws Exception {
        // No arguments is a request for help, not a failed analysis.
        if (args.length == 0 || args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("Usage: java -jar jonoffcpu-correlator.jar"
                    + " --source jonoffcpu-capture.ndjson --jfr jonoffcpu-capture.jfr --output new-directory"
                    + " [--from TIME] [--to TIME] [--partial-jfr true|false]"
                    + " [--from-ns N] [--to-ns N] [--max-handler-delay-ns N]"
                    + " [--max-rows N] [--max-retained-bytes N] [--format both|collapsed|jfr]"
                    + " [--quantum-ns N] [--max-synthetic-events N] [--estimate-population true|false]"
                    + " [--audit none|matches|full] [--thinning Q (0 < Q <= 1, keep probability)]"
                    + " [--thinning-seed N] [--on-limit degrade|fail|truncate]"
                    + " [--partial true|false (partial format: diagnostics|collapsed)]"
                    + " [--collapsed-reason-frame auto|always|never] [--profile-output true|false]"
                    + " [--profile-group-by kernel,user,thread|none] [--max-profile-entries N]");
            System.out.println("       java -jar jonoffcpu-correlator.jar stacks --profile " + OutputFiles.PROFILE
                    + " --output out.collapsed [--reason all|blocked,runnable,preempted,unspecified]"
                    + " [--stack java|kernel|user|java+kernel|java+user+kernel] [--weights observed|estimated]"
                    + " [--reason-frame auto|always|never] [--time total|sleeping|runqueue|split]"
                    + " [--package-names full|abbreviate|drop] [--summary summary.json]"
                    + " [--include REGEX]... [--exclude REGEX]..."
                    + " [--include-from FILE]... [--exclude-from FILE]...");
            System.out.println(
                    "       java -jar jonoffcpu-correlator.jar merge --profiles a.pb,b.pb --output merged.pb");
            System.out.println("       java -jar jonoffcpu-correlator.jar export --profile " + OutputFiles.PROFILE
                    + " --format csv|jsonl --output entries.csv");
            System.out.println("       java -jar jonoffcpu-correlator.jar --dump --source jonoffcpu-capture.pb"
                    + "   (prints the capture stream as NDJSON, stacks expanded)");
            System.out.println("Writes into the output directory: " + OutputFiles.REPORT + ", "
                    + OutputFiles.COLLAPSED + ", " + OutputFiles.SYNTHETIC_JFR + " and, last, " + OutputFiles.COMPLETE
                    + "; " + OutputFiles.CLASSIFIED_RECORDS + " is written only for --audit full and "
                    + OutputFiles.MATCHES + " for --audit full or matches; --partial true writes "
                    + OutputFiles.INCOMPLETE_PREFIX + "* files and " + OutputFiles.PARTIAL + " instead.");
            return 0;
        }
        if (args.length == 3 && args[0].equals("--dump") && args[1].equals("--source")) {
            dump(Path.of(args[2]));
            return 0;
        }
        // Every correlate option starts with "--", so a bare first word is free for a subcommand.
        switch (args[0]) {
            case "stacks" -> {
                return stacks(Arrays.copyOfRange(args, 1, args.length));
            }
            case "merge" -> {
                return merge(Arrays.copyOfRange(args, 1, args.length));
            }
            case "export" -> {
                return export(Arrays.copyOfRange(args, 1, args.length));
            }
            default -> {
                // correlate
            }
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
                "--partial-jfr",
                "--audit",
                "--thinning",
                "--thinning-seed",
                "--on-limit",
                "--collapsed-reason-frame",
                "--profile-output",
                "--profile-group-by",
                "--max-profile-entries");
        parseOptions(args, allowed, options);
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
        // Spec §4's defaults table: the CLI defaults to "matches", unlike the library's OutputOptions.defaults(),
        // which keeps "full" so OfflineCorrelatorTest's exact-file-set assertion still holds.
        AuditLevel audit = AuditLevel.parse(options.getOrDefault("--audit", "matches"));
        boolean hasJfrRange = options.containsKey("--from") || options.containsKey("--to");
        if (partial && (partialJfr || hasJfrRange)) {
            throw new IllegalArgumentException("Incomplete-capture mode cannot be combined with JFR selection");
        }
        if (estimatePopulation && (partialJfr || hasJfrRange)) {
            throw new IllegalArgumentException("Population estimates require the complete unselected JFR");
        }
        if (estimatePopulation && options.containsKey("--thinning")) {
            throw new IllegalArgumentException("Population estimates require the unthinned source");
        }
        String format = options.getOrDefault("--format", partial ? "diagnostics" : "both");
        if (partial) {
            if (!Set.of("diagnostics", "collapsed").contains(format)
                    || estimatePopulation
                    || options.containsKey("--quantum-ns")
                    || options.containsKey("--max-synthetic-events")
                    || options.containsKey("--audit")
                    || options.containsKey("--thinning")
                    || options.containsKey("--collapsed-reason-frame")
                    || options.containsKey("--profile-output")
                    || options.containsKey("--profile-group-by")
                    || options.containsKey("--max-profile-entries")) {
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
        boolean stackProfile = booleanOption(options, "--profile-output", true);
        ProfileAccumulator.Options profileOptions = ProfileAccumulator.Options.parse(
                options.getOrDefault("--profile-group-by", "kernel,user,thread"),
                options.getOrDefault(
                        "--max-profile-entries", Integer.toString(ProfileAccumulator.DEFAULT_MAX_ENTRIES)));
        StackProfileRenderer.ReasonFrame reasonFrame =
                StackProfileRenderer.ReasonFrame.parse(options.getOrDefault("--collapsed-reason-frame", "auto"));
        Path sourcePath = Path.of(options.get("--source"));
        Thinning requestedThinning = options.containsKey("--thinning")
                ? Thinning.of(options.get("--thinning"), Long.parseLong(options.getOrDefault("--thinning-seed", "0")))
                : Thinning.NONE;
        Degradation ladder = new Degradation(
                Degradation.Policy.parse(options.getOrDefault("--on-limit", "degrade")),
                audit,
                requestedThinning,
                limits.maxRetainedBytes(),
                RetentionEstimate.of(sourcePath, jfr));
        // A restart is one sequential re-read of files that are already digest-verified.
        Degradation.Settings settings = null;
        CorrelationResult result = null;
        while (result == null) {
            settings = ladder.settings();
            try {
                result = CorrelationEngine.correlate(
                        sourcePath, jfr, limits, selection, false, settings, profileOptions);
            } catch (RetentionLimitExceeded limit) {
                if (!ladder.advance(limit)) throw new IOException(ladder.refusal(limit), limit);
            }
        }
        boolean narrowed = ladder.narrowed();
        String prefix = narrowed ? OutputFiles.INCOMPLETE_PREFIX : OutputFiles.PREFIX;
        CorrelationResult publishedResult = result;
        write(
                AnalysisOutput.of(result, sourcePath, jfr, selection, settings.narrowedToNanos()),
                Path.of(options.get("--output")),
                new OutputOptions(
                        !format.equals("jfr"),
                        !format.equals("collapsed"),
                        estimatePopulation,
                        ladder.audit(),
                        jfrOptions,
                        prefix,
                        ladder,
                        stackProfile,
                        reasonFrame),
                () -> publishedResult.capture().verifyUnchanged(sourcePath, jfr));
        System.out.println((narrowed ? "Wrote INCOMPLETE narrowed analysis to " : "Wrote validated analysis to ")
                + options.get("--output")
                + ": "
                + result.matched()
                + " matches; "
                + result.selectedObservedDurationNanos()
                + " selected observed ns");
        return narrowed ? 2 : 0;
    }

    /**
     * Writes to a new directory, never replacing existing inputs or reports. Consumers must require
     * {@code jonoffcpu-complete.json}: it is created last. An interrupted output has no completion marker and
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
        write(AnalysisOutput.of(result), directory, options);
    }

    /**
     * Writes the output directory from a streamed or retained {@link AnalysisOutput}. The digest
     * recheck a caller performs after this returns (see the two overloads with a {@code Runnable})
     * happens with no completion marker published yet, so a file changed while writing is still
     * caught before the directory is promoted to complete.
     */
    static void write(AnalysisOutput output, Path directory, OutputOptions options) throws IOException {
        write(output, directory, options, () -> {});
    }

    /**
     * The full writer, taking a callback invoked after every other artifact — including the audit
     * re-read, for the streamed path — but before the completion marker, so a digest recheck can
     * still fail the run closed.
     */
    private static void write(AnalysisOutput output, Path directory, OutputOptions options, Verification verify)
            throws IOException {
        Files.createDirectory(directory);
        Gson gson = new GsonBuilder().serializeNulls().create();
        String prefix = options.prefix();
        boolean narrowed = options.ladder().narrowed();
        // The combined file keeps every recorded interval. When it mixes switch-out reasons, each line starts with
        // its reason's frame, because an unlabelled graph would merge waiting with being denied the CPU; a
        // single-reason capture, which includes every capture without classification, is written as before.
        List<OffCpuReason> reasonsPresent = output.reasonsPresent();
        boolean reasonFrames =
                !reasonsPresent.isEmpty() && options.reasonFrame().applies(reasonsPresent.size());
        if (options.collapsed()) {
            writeCollapsed(
                    directory.resolve(OutputFiles.name(prefix, OutputFiles.COLLAPSED_SUFFIX)),
                    reasonFrames ? output.collapsedNanos(null) : output.collapsedNanos());
            if (reasonsPresent.size() > 1) {
                for (OffCpuReason reason : reasonsPresent) {
                    writeCollapsed(
                            directory.resolve(OutputFiles.collapsedForReason(prefix, reason)),
                            output.collapsedNanos(reason));
                }
            }
        }
        // The synthetic JFR keeps its observed scale: thinning shrinks the quantum an event consumes so
        // that, once reweighted by the caller, one event still represents one requested quantum of
        // estimated time.
        CompatibilityJfrWriter.Options requestedJfrOptions = options.jfrOptions();
        CompatibilityJfrWriter.Options effectiveJfrOptions = output.thinning().active()
                ? new CompatibilityJfrWriter.Options(
                        output.thinning().scaleQuantum(requestedJfrOptions.quantumNanos()),
                        requestedJfrOptions.maxSyntheticEvents(),
                        requestedJfrOptions.onEventLimit())
                : requestedJfrOptions;
        CompatibilityJfrWriter.Result compatibility = null;
        if (options.compatibilityJfr()) {
            try {
                compatibility = CompatibilityJfrWriter.write(
                        output.synthetic(),
                        directory.resolve(OutputFiles.name(prefix, OutputFiles.SYNTHETIC_SUFFIX)),
                        effectiveJfrOptions);
            } catch (IOException unfittable) {
                // Spec §3: a quantum that cannot be coarsened to fit degrades to collapsed-only output
                // instead of discarding an otherwise-complete analysis; only an explicit --format jfr
                // still aborts, since then there is nothing else to publish.
                if (options.jfrOnly()) throw unfittable;
                options.ladder().syntheticOmitted(unfittable.getMessage());
            }
            if (compatibility != null && compatibility.quantumRaised()) {
                options.ladder().quantumRaised(compatibility.requestedQuantumNanos(), compatibility.quantumNanos());
            }
        }
        if (options.audit() == AuditLevel.FULL) {
            try (BufferedWriter writer =
                    newFile(directory.resolve(OutputFiles.name(prefix, OutputFiles.CLASSIFIED_RECORDS_SUFFIX)))) {
                output.writeClassifiedRecords(writer);
            }
        }
        if (options.audit() != AuditLevel.NONE) {
            try (BufferedWriter writer =
                    newFile(directory.resolve(OutputFiles.name(prefix, OutputFiles.MATCHES_SUFFIX)))) {
                output.writeMatches(writer);
            }
        }
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty(
                "stackSemantics", "signal-delivery stack; not guaranteed to match the eBPF scheduler-exit stack");
        report.addProperty("audit", options.audit().text());
        report.addProperty(
                "weightSemantics",
                "collapsed stacks use rounded integer microseconds; exact selected duration remains in nanoseconds");
        if (output.thinning().active()) {
            report.add("sourceThinning", output.thinning().report());
            report.addProperty("keptSourceRows", Integer.toString(output.sourceRows()));
            report.addProperty("observedKeptDurationNanos", Long.toString(output.observedKeptDurationNanos()));
            report.addProperty(
                    "weightSemantics",
                    "collapsed stacks are inverse-probability estimates from a thinned subsample;"
                            + " observed kept nanoseconds are reported separately");
        }
        report.add("analysisInputs", output.analysisInputs());
        report.add("sourceCounters", output.sourceCounters());
        report.addProperty("apStoppedAtNanos", output.apStoppedAtNanos());
        report.addProperty("sourceRows", output.sourceRows());
        report.addProperty("jfrSamples", output.jfrSamples());
        report.addProperty("matched", output.matched());
        report.addProperty("unmatchedSource", output.unmatchedSource());
        report.addProperty("sourceRowsWithoutSelectedJfrSample", output.sourceRowsWithoutSelectedJfrSample());
        report.addProperty("orphanJfr", output.orphanJfr());
        report.addProperty("invalidSource", output.invalidSource());
        report.addProperty("invalidJfr", output.invalidJfr());
        report.addProperty("identityUnverified", output.identityUnverified());
        report.addProperty("selectedObservedDurationNanos", output.selectedObservedDurationNanos());
        report.addProperty("submittedButNotParsed", output.submittedButNotParsed());
        if (output.jfrSelection() != null) {
            report.add("jfrSelection", output.jfrSelection());
        }
        if (options.populationEstimate()) {
            report.add("populationEstimate", gson.toJsonTree(output.populationEstimate()));
        }
        JsonObject delays = new JsonObject();
        long[] sortedDelays = output.sortedHandlerDelays();
        delays.addProperty("count", sortedDelays.length);
        delays.addProperty("scope", "source interval end to AP handler timestamp, after clock translation");
        for (int percentile : new int[] {50, 90, 99, 100}) {
            String name = percentile == 100 ? "max" : "p" + percentile;
            if (sortedDelays.length == 0) {
                delays.add(name, JsonNull.INSTANCE);
            } else {
                int rank = (int) ((sortedDelays.length * (long) percentile + 99) / 100);
                delays.addProperty(name, Long.toString(sortedDelays[rank - 1]));
            }
        }
        report.add("handlerDelayNanos", delays);
        if (compatibility != null) {
            JsonObject view = new JsonObject();
            view.addProperty("path", compatibility.output().getFileName().toString());
            // quantumNanos is the requested quantum at estimated scale (what one reweighted event
            // represents); observedQuantumNanos is the actual, possibly thinning-shrunk and
            // event-limit-raised, quantum of observed time an event was built from.
            view.addProperty("quantumNanos", Long.toString(requestedJfrOptions.quantumNanos()));
            view.addProperty("requestedQuantumNanos", Long.toString(compatibility.requestedQuantumNanos()));
            view.addProperty("observedQuantumNanos", Long.toString(compatibility.quantumNanos()));
            view.addProperty("quantumRaisedForEventLimit", compatibility.quantumRaised());
            view.addProperty("syntheticEvents", Long.toString(compatibility.syntheticEvents()));
            view.addProperty("representedNanos", compatibility.representedNanos());
            view.addProperty("quantizationErrorNanos", compatibility.quantizationErrorNanos());
            view.addProperty("omittedRemainderNanos", compatibility.omittedRemainderNanos());
            report.add("syntheticJfr", view);
        }
        JsonObject offCpuReasons = output.offCpuReasons();
        if (offCpuReasons != null) report.add("offCpuReasons", offCpuReasons);
        // Always present, with an empty stepsApplied when nothing was needed, so a consumer can see that
        // degradation was considered and declined.
        report.add("degradation", options.ladder().report(output.peakRetainedBytes()));
        StackProfile profile = options.stackProfile() ? output.stackProfile("") : null;
        if (profile != null) {
            JsonObject view = new JsonObject();
            view.addProperty("path", OutputFiles.name(prefix, OutputFiles.PROFILE_SUFFIX));
            view.addProperty("entries", profile.entries().size());
            view.add("dimensions", gson.toJsonTree(profile.header().dimensions()));
            view.add("dimensionsDropped", gson.toJsonTree(profile.header().dimensionsDropped()));
            view.addProperty("estimateAvailable", profile.header().estimateAvailable());
            view.addProperty("timeSplitAvailable", profile.header().timeSplitAvailable());
            report.add("stackProfile", view);
        }
        // Explicit nulls keep the echoed sampling bounds and an unavailable estimate visible as such.
        String reportJson =
                new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report);
        if (profile != null) {
            // The profile carries the report it was produced with, so it stays interpretable on its own.
            Header header = profile.header();
            new StackProfile(
                            new Header(
                                    header.sources(),
                                    header.dimensions(),
                                    header.estimateAvailable(),
                                    reportJson,
                                    header.label(),
                                    header.dimensionsDropped(),
                                    header.timeSplitAvailable()),
                            profile.entries())
                    .write(directory.resolve(OutputFiles.name(prefix, OutputFiles.PROFILE_SUFFIX)));
        }
        try (BufferedWriter writer = newFile(directory.resolve(OutputFiles.name(prefix, OutputFiles.REPORT_SUFFIX)))) {
            writer.write(reportJson);
            writer.newLine();
        }
        verify.verify();
        if (narrowed) {
            // A narrowed window is not the question that was asked: it never promotes the directory to
            // complete, and the same no-replace hard-link publication writePartial uses keeps a
            // half-written marker from ever being observed.
            JsonObject marker = new JsonObject();
            marker.addProperty("schemaVersion", 1);
            marker.addProperty("state", "narrowed");
            marker.addProperty("coverageComplete", false);
            marker.addProperty(
                    "effectiveToNanos", Long.toString(options.ladder().narrowedToNanos()));
            Path temporary = Files.createTempFile(directory, ".narrowed-marker-", ".tmp");
            try {
                Files.writeString(temporary, gson.toJson(marker) + "\n", StandardCharsets.UTF_8);
                Files.createLink(directory.resolve(OutputFiles.NARROWED), temporary);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } else {
            try (BufferedWriter writer = newFile(directory.resolve(OutputFiles.COMPLETE))) {
                writer.write("{\"schemaVersion\":1,\"state\":\"complete\"}\n");
            }
        }
    }

    /** A digest recheck run after every other artifact is written but before the completion marker. */
    @FunctionalInterface
    private interface Verification {
        void verify() throws IOException;
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
            try (BufferedWriter writer = newFile(directory.resolve(OutputFiles.INCOMPLETE_COLLAPSED))) {
                for (var entry : new TreeMap<>(result.collapsedNanos()).entrySet()) {
                    writer.write("[INCOMPLETE capture: observed prefix only];");
                    writer.write(entry.getKey());
                    writer.write(' ');
                    writer.write(collapsedMicros(entry.getValue()));
                    writer.newLine();
                }
            }
        }
        try (BufferedWriter writer = newFile(directory.resolve(OutputFiles.INCOMPLETE_CLASSIFIED_RECORDS))) {
            for (var record : result.records()) {
                JsonObject row = gson.toJsonTree(record).getAsJsonObject();
                row.addProperty("state", "incomplete");
                row.addProperty("analysisMode", "partial");
                gson.toJson(row, writer);
                writer.newLine();
            }
        }
        try (BufferedWriter writer = newFile(directory.resolve(OutputFiles.INCOMPLETE_PAIRS))) {
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
        try (BufferedWriter writer = newFile(directory.resolve(OutputFiles.INCOMPLETE_REPORT))) {
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
            Files.createLink(directory.resolve(OutputFiles.PARTIAL), temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeCollapsed(Path file, Map<String, String> weights) throws IOException {
        try (BufferedWriter writer = newFile(file)) {
            for (var entry : new TreeMap<>(weights).entrySet()) {
                writer.write(entry.getKey());
                writer.write(' ');
                writer.write(collapsedMicros(entry.getValue()));
                writer.newLine();
            }
        }
    }

    // ---- stack profile subcommands --------------------------------------------------------------

    /** Renders one collapsed slice of a stack profile. */
    private static int stacks(String[] args) throws IOException {
        Map<String, String> options = new HashMap<>();
        Map<String, List<String>> repeated = new HashMap<>();
        args = takeRepeated(args, Set.of("--include", "--exclude", "--include-from", "--exclude-from"), repeated);
        parseOptions(
                args,
                Set.of(
                        "--profile",
                        "--output",
                        "--reason",
                        "--stack",
                        "--weights",
                        "--reason-frame",
                        "--time",
                        "--package-names",
                        "--summary"),
                options);
        requireOptions(options, "--profile", "--output");
        StackProfile profile = StackProfile.read(Path.of(options.get("--profile")));
        Set<OffCpuReason> reasons = reasons(options.getOrDefault("--reason", "all"));
        StackProfileRenderer.StackKinds kinds =
                StackProfileRenderer.StackKinds.parse(options.getOrDefault("--stack", "java"));
        StackProfileRenderer.Weights weights =
                StackProfileRenderer.Weights.parse(options.getOrDefault("--weights", "observed"));
        StackProfileRenderer.Time time = StackProfileRenderer.Time.parse(options.getOrDefault("--time", "total"));
        StackProfileRenderer.PackageNames packages =
                StackProfileRenderer.PackageNames.parse(options.getOrDefault("--package-names", "full"));
        StackProfileRenderer.Filter filter = StackProfileRenderer.Filter.of(
                patterns(repeated, "--include", "--include-from"), patterns(repeated, "--exclude", "--exclude-from"));
        StackProfileRenderer.Slice slice = StackProfileRenderer.render(
                profile,
                reasons,
                kinds,
                weights,
                StackProfileRenderer.ReasonFrame.parse(options.getOrDefault("--reason-frame", "auto")),
                time,
                filter,
                packages);
        try (BufferedWriter writer = newFile(Path.of(options.get("--output")))) {
            StackProfileRenderer.writeCollapsed(slice, writer);
        }
        JsonObject summary =
                StackProfileRenderer.summary(slice, profile, reasons, kinds, weights, time, filter, packages);
        if (options.containsKey("--summary")) {
            try (BufferedWriter writer = newFile(Path.of(options.get("--summary")))) {
                new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(summary, writer);
                writer.newLine();
            }
        }
        System.out.println("Wrote " + slice.nanos().size() + " collapsed stacks to " + options.get("--output") + ": "
                + slice.intervals() + " intervals, " + slice.totalNanos() + " ns"
                + (time == StackProfileRenderer.Time.TOTAL ? "" : " of " + time.label() + " time")
                + (filter.active()
                        ? "; filtered out " + slice.filteredIntervals() + " intervals, " + slice.filteredNanos()
                                + " ns, matching " + String.join(",", StackProfileRenderer.Filter.scope(profile))
                                + " stacks"
                        : ""));
        return 0;
    }

    /** A filter option's patterns: those given inline, then those of each pattern file in order. */
    private static List<String> patterns(Map<String, List<String>> repeated, String inline, String fromFile)
            throws IOException {
        List<String> patterns = new java.util.ArrayList<>(repeated.getOrDefault(inline, List.of()));
        for (String file : repeated.getOrDefault(fromFile, List.of())) patterns.addAll(patternFile(fromFile, file));
        return patterns;
    }

    /**
     * Reads a pattern file: one regular expression per line, as {@code --include}/{@code --exclude} would take it.
     * Blank lines and lines starting with {@code #} are skipped; a pattern starting with {@code #} is written
     * {@code \#}. A line is otherwise taken verbatim, spaces included, since frame names can contain them. A file
     * without any pattern is refused rather than read as no filter, which for an include would keep everything.
     */
    static List<String> patternFile(String option, String file) throws IOException {
        List<String> patterns = new java.util.ArrayList<>();
        List<String> lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank() || line.startsWith("#")) continue;
            try {
                java.util.regex.Pattern.compile(line);
            } catch (java.util.regex.PatternSyntaxException invalid) {
                throw new IllegalArgumentException(
                        "Invalid pattern in " + option + " " + file + " line " + (index + 1) + ": "
                                + invalid.getMessage(),
                        invalid);
            }
            patterns.add(line);
        }
        if (patterns.isEmpty()) throw new IllegalArgumentException(option + " " + file + " contains no patterns");
        return patterns;
    }

    /** Moves each occurrence of a repeatable option's value into {@code repeated}; returns the other arguments. */
    private static String[] takeRepeated(String[] args, Set<String> names, Map<String, List<String>> repeated) {
        List<String> rest = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (names.contains(args[i])) {
                if (i + 1 == args.length) throw new IllegalArgumentException("Missing value for option: " + args[i]);
                repeated.computeIfAbsent(args[i], name -> new java.util.ArrayList<>())
                        .add(args[++i]);
            } else {
                rest.add(args[i]);
                // An ordinary option's value is not an option name, even when it looks like one.
                if (i + 1 < args.length) rest.add(args[++i]);
            }
        }
        return rest.toArray(String[]::new);
    }

    /** Sums the counters of several stack profiles into one. */
    private static int merge(String[] args) throws IOException {
        Map<String, String> options = new HashMap<>();
        parseOptions(args, Set.of("--profiles", "--output"), options);
        requireOptions(options, "--profiles", "--output");
        List<StackProfile> profiles = new java.util.ArrayList<>();
        for (String path : options.get("--profiles").split(",", -1)) {
            if (path.isEmpty()) throw new IllegalArgumentException("Empty profile path in --profiles");
            profiles.add(StackProfile.read(Path.of(path)));
        }
        StackProfile merged = StackProfile.merge(profiles);
        merged.write(Path.of(options.get("--output")));
        System.out.println("Merged " + profiles.size() + " profiles into " + options.get("--output") + ": "
                + merged.entries().size() + " entries");
        return 0;
    }

    /** Writes one row per profile entry, stacks expanded, for ad-hoc tools such as DuckDB. */
    private static int export(String[] args) throws IOException {
        Map<String, String> options = new HashMap<>();
        parseOptions(args, Set.of("--profile", "--format", "--output"), options);
        requireOptions(options, "--profile", "--output");
        StackProfile profile = StackProfile.read(Path.of(options.get("--profile")));
        try (BufferedWriter writer = newFile(Path.of(options.get("--output")))) {
            StackProfileRenderer.export(profile, options.getOrDefault("--format", "csv"), writer);
        }
        return 0;
    }

    private static Set<OffCpuReason> reasons(String text) throws IOException {
        if (text.equals("all")) return null;
        Set<OffCpuReason> reasons = java.util.EnumSet.noneOf(OffCpuReason.class);
        for (String label : text.split(",", -1)) {
            if (!reasons.add(OffCpuReason.parse(label))) {
                throw new IllegalArgumentException("Duplicate reason: " + label);
            }
        }
        return reasons;
    }

    private static void parseOptions(String[] args, Set<String> allowed, Map<String, String> options) {
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length
                    || !allowed.contains(args[i])
                    || options.putIfAbsent(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Unknown, duplicate or missing option: " + args[i]);
            }
        }
    }

    private static void requireOptions(Map<String, String> options, String... required) {
        for (String key : required) {
            if (!options.containsKey(key)) throw new IllegalArgumentException("Missing " + key);
        }
    }

    static String collapsedMicros(String nanoseconds) {
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
