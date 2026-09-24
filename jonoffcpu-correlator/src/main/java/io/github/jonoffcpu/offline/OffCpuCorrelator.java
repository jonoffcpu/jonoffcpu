// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.offline;

import com.google.protobuf.Message;
import io.github.jonoffcpu.capture.CaptureFormat;
import io.github.jonoffcpu.capture.ProtoJson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Command-line entry point for the two-input Java correlator. */
public final class OffCpuCorrelator {
    static final String STACK_SEMANTICS =
            "signal-delivery stack; not guaranteed to match the eBPF scheduler-exit stack";

    record OutputOptions(
            boolean collapsed,
            boolean compatibilityJfr,
            boolean populationEstimate,
            AuditLevel audit,
            CompatibilityJfrWriter.Options jfrOptions,
            String prefix,
            Degradation ladder,
            boolean stackProfile,
            StackProfileRenderer.ReasonFrame reasonFrame,
            boolean digest) {
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
                    StackProfileRenderer.ReasonFrame.AUTO,
                    true);
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
     * Prints the capture stream as JSON Lines, one record per line as {@code jonoffcpu-capture.proto} defines it, so
     * humans and tools can read a binary capture without the correlator's analysis. A record cut short ends the
     * output with a {@code truncatedTailBytes} line.
     */
    static void dump(Path source) throws IOException {
        // Flushed, never closed: an in-process caller keeps its standard output.
        BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        try (java.io.InputStream input = new java.io.BufferedInputStream(Files.newInputStream(source))) {
            CaptureFormat.readHeader(input);
            CaptureFormat.Framed framed;
            while ((framed = CaptureFormat.next(
                            input, OfflineCorrelator.Limits.defaults().maxLineBytes()))
                    != null) {
                Message line = framed.truncated()
                        ? ReportProto.TruncatedTail.newBuilder()
                                .setTruncatedTailBytes(framed.bytes().length)
                                .build()
                        : framed.record();
                writer.write(ProtoJson.line(line));
                writer.newLine();
                if (framed.truncated()) break;
            }
        } finally {
            writer.flush();
        }
    }

    /**
     * Runs one command line in-process and returns its exit code: 0 for a complete analysis, help or version, 2 for
     * explicitly incomplete (narrowed or partial) output, and 64 for an invalid command line, whose message and usage
     * go to standard error. It never calls {@link System#exit}. A failure of the analysis itself (I/O, integrity) is
     * thrown; library callers that want every problem as an exception use the typed APIs such as
     * {@link #correlate(Path, Path, Path)}.
     */
    public static int run(String[] args) throws Exception {
        return Cli.run(
                args,
                new java.io.PrintWriter(new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8), true),
                new java.io.PrintWriter(new java.io.OutputStreamWriter(System.err, StandardCharsets.UTF_8), true));
    }

    /** The correlate command once its options are parsed and validated; returns 0, or 2 when narrowed. */
    static int correlateCommand(
            Path sourcePath,
            Path jfr,
            Path output,
            OfflineCorrelator.Limits limits,
            String from,
            String to,
            boolean partialJfr,
            String format,
            CompatibilityJfrWriter.Options jfrOptions,
            boolean estimatePopulation,
            AuditLevel audit,
            Thinning requestedThinning,
            Degradation.Policy onLimit,
            StackProfileRenderer.ReasonFrame reasonFrame,
            boolean stackProfile,
            ProfileAccumulator.Options profileOptions,
            boolean digest)
            throws IOException {
        boolean hasJfrRange = from != null || to != null;
        OfflineCorrelator.JfrSelection selection = null;
        if (partialJfr || hasJfrRange) {
            JfrTimeRange.Range range = hasJfrRange ? JfrTimeRange.resolve(jfr, from, to) : null;
            selection = new OfflineCorrelator.JfrSelection(
                    range == null ? null : range.from(), range == null ? null : range.to(), partialJfr);
        }
        Degradation ladder = new Degradation(
                onLimit, audit, requestedThinning, limits.maxRetainedBytes(), RetentionEstimate.of(sourcePath, jfr));
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
                output,
                new OutputOptions(
                        !format.equals("jfr"),
                        !format.equals("collapsed"),
                        estimatePopulation,
                        ladder.audit(),
                        jfrOptions,
                        prefix,
                        ladder,
                        stackProfile,
                        reasonFrame,
                        digest),
                () -> publishedResult.capture().verifyUnchanged(sourcePath, jfr));
        System.out.println((narrowed ? "Wrote INCOMPLETE narrowed analysis to " : "Wrote validated analysis to ")
                + output
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
        ReportProto.Report.Builder report = ReportProto.Report.newBuilder()
                .setStackSemantics(STACK_SEMANTICS)
                .setAudit(options.audit().text())
                .setWeightSemantics("collapsed stacks use rounded integer microseconds; exact selected duration remains"
                        + " in nanoseconds");
        if (output.thinning().active()) {
            report.setSourceThinning(output.thinning().report())
                    .setKeptSourceRows(output.sourceRows())
                    .setObservedKeptDurationNanos(output.observedKeptDurationNanos())
                    .setWeightSemantics("collapsed stacks are inverse-probability estimates from a thinned subsample;"
                            + " observed kept nanoseconds are reported separately");
        }
        if (output.analysisInputs() != null) report.setAnalysisInputs(output.analysisInputs());
        if (output.sourceCounters() != null) report.setSourceCounters(output.sourceCounters());
        if (output.apStoppedAtNanos() != null) report.setApStoppedAtNanos(output.apStoppedAtNanos());
        report.setSourceRows(output.sourceRows())
                .setJfrSamples(output.jfrSamples())
                .setMatched(output.matched())
                .setUnmatchedSource(output.unmatchedSource())
                .setSourceRowsWithoutSelectedJfrSample(output.sourceRowsWithoutSelectedJfrSample())
                .setOrphanJfr(output.orphanJfr())
                .setInvalidSource(output.invalidSource())
                .setInvalidJfr(output.invalidJfr())
                .setIdentityUnverified(output.identityUnverified())
                .setSelectedObservedDurationNanos(output.selectedObservedDurationNanos());
        if (output.submittedButNotParsed() != null) report.setSubmittedButNotParsed(output.submittedButNotParsed());
        if (output.jfrSelection() != null) report.setJfrSelection(output.jfrSelection());
        if (options.populationEstimate() && output.populationEstimate() != null) {
            report.setPopulationEstimate(output.populationEstimate());
        }
        report.setHandlerDelayNanos(handlerDelays(output.sortedHandlerDelays()));
        if (compatibility != null) {
            // quantumNanos is the requested quantum at estimated scale (what one reweighted event
            // represents); observedQuantumNanos is the actual, possibly thinning-shrunk and
            // event-limit-raised, quantum of observed time an event was built from.
            report.setSyntheticJfr(ReportProto.SyntheticJfr.newBuilder()
                    .setPath(compatibility.output().getFileName().toString())
                    .setQuantumNanos(requestedJfrOptions.quantumNanos())
                    .setRequestedQuantumNanos(compatibility.requestedQuantumNanos())
                    .setObservedQuantumNanos(compatibility.quantumNanos())
                    .setQuantumRaisedForEventLimit(compatibility.quantumRaised())
                    .setSyntheticEvents(compatibility.syntheticEvents())
                    .setRepresentedNanos(Long.parseLong(compatibility.representedNanos()))
                    .setQuantizationErrorNanos(Long.parseLong(compatibility.quantizationErrorNanos()))
                    .setOmittedRemainderNanos(Long.parseLong(compatibility.omittedRemainderNanos())));
        }
        ReportProto.OffCpuReasons offCpuReasons = output.offCpuReasons();
        if (offCpuReasons != null) report.setOffCpuReasons(offCpuReasons);
        // Always present, with an empty stepsApplied when nothing was needed, so a consumer can see that
        // degradation was considered and declined.
        report.setDegradation(options.ladder().report(output.peakRetainedBytes()));
        StackProfile profile = options.stackProfile() ? output.stackProfile() : null;
        if (profile != null) {
            report.setStackProfile(ReportProto.StackProfileSummary.newBuilder()
                    .setPath(OutputFiles.name(prefix, OutputFiles.PROFILE_SUFFIX))
                    .setEntries(profile.entries().size())
                    .addAllDimensions(profile.header().dimensions())
                    .addAllDimensionsDropped(profile.header().dimensionsDropped())
                    .setEstimateAvailable(profile.header().estimateAvailable())
                    .setTimeSplitAvailable(profile.header().timeSplitAvailable()));
            if (options.digest()) report.setDigest(digest(directory, prefix, profile, report.build()));
        }
        ReportProto.Report built = report.build();
        if (profile != null) {
            // The profile carries the report it was produced with, so it stays interpretable on its own.
            new StackProfile(profile.header().withReport(built), profile.entries())
                    .write(directory.resolve(OutputFiles.name(prefix, OutputFiles.PROFILE_SUFFIX)));
        }
        writePretty(directory.resolve(OutputFiles.name(prefix, OutputFiles.REPORT_SUFFIX)), built);
        verify.verify();
        if (narrowed) {
            // A narrowed window is not the question that was asked: it never promotes the directory to
            // complete, and the same no-replace hard-link publication writePartial uses keeps a
            // half-written marker from ever being observed.
            publishMarker(
                    directory,
                    OutputFiles.NARROWED,
                    ReportProto.Marker.newBuilder()
                            .setState(ReportProto.MarkerState.MARKER_STATE_NARROWED)
                            .setCoverageComplete(false)
                            .setEffectiveToNanos(options.ladder().narrowedToNanos())
                            .build());
        } else {
            try (BufferedWriter writer = newFile(directory.resolve(OutputFiles.COMPLETE))) {
                writer.write(ProtoJson.line(ReportProto.Marker.newBuilder()
                        .setState(ReportProto.MarkerState.MARKER_STATE_COMPLETE)
                        .setCoverageComplete(true)
                        .build()));
                writer.newLine();
            }
        }
    }

    /** The delivery-delay percentiles by nearest rank; unset when nothing matched. */
    static ReportProto.HandlerDelays handlerDelays(long[] sortedDelays) {
        ReportProto.HandlerDelays.Builder delays = ReportProto.HandlerDelays.newBuilder()
                .setCount(sortedDelays.length)
                .setScope("source interval end to AP handler timestamp, after clock translation");
        if (sortedDelays.length > 0) {
            delays.setP50(percentile(sortedDelays, 50))
                    .setP90(percentile(sortedDelays, 90))
                    .setP99(percentile(sortedDelays, 99))
                    .setMax(percentile(sortedDelays, 100));
        }
        return delays.build();
    }

    private static long percentile(long[] sorted, int percentile) {
        int rank = (int) ((sorted.length * (long) percentile + 99) / 100);
        return sorted[rank - 1];
    }

    /**
     * Writes the analysis digest beside the report and returns the report's {@code digest} object. The digest is a
     * convenience: a failure to produce it is reported there, its files are removed, and the correlation goes on.
     */
    private static ReportProto.DigestFiles digest(
            Path directory, String prefix, StackProfile profile, ReportProto.Report report) {
        ReportProto.DigestFiles.Builder view = ReportProto.DigestFiles.newBuilder();
        Path json = directory.resolve(OutputFiles.name(prefix, OutputFiles.SUMMARY_JSON_SUFFIX));
        Path markdown = directory.resolve(OutputFiles.name(prefix, OutputFiles.SUMMARY_MD_SUFFIX));
        try {
            Digest.write(
                    Digest.of(profile, OutputFiles.name(prefix, OutputFiles.PROFILE_SUFFIX), report, Digest.defaults()),
                    json,
                    markdown);
            view.setPath(markdown.getFileName().toString());
            view.setJson(json.getFileName().toString());
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(json);
                Files.deleteIfExists(markdown);
            } catch (IOException ignored) {
                // The error below is what the reader needs; a leftover file is named by it too.
            }
            view.setError(String.valueOf(failure));
        }
        return view.build();
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
            for (ReportProto.ClassifiedRecord record : result.records()) {
                writer.write(ProtoJson.line(record));
                writer.newLine();
            }
        }
        try (BufferedWriter writer = newFile(directory.resolve(OutputFiles.INCOMPLETE_PAIRS))) {
            for (OfflineCorrelator.Match pair : result.pairs()) {
                writer.write(ProtoJson.line(pair.pair()));
                writer.newLine();
            }
        }
        writePretty(directory.resolve(OutputFiles.INCOMPLETE_REPORT), result.report());
        publishMarker(
                directory,
                OutputFiles.PARTIAL,
                ReportProto.Marker.newBuilder()
                        .setState(ReportProto.MarkerState.MARKER_STATE_INCOMPLETE)
                        .setCoverageComplete(false)
                        .addAllIncompleteReasons(result.report().getIncompleteReasonsList())
                        .build());
    }

    /** A no-replace hard link publishes only the fully written marker, never a partial write. */
    private static void publishMarker(Path directory, String name, ReportProto.Marker marker) throws IOException {
        Path temporary = Files.createTempFile(directory, "." + name + "-", ".tmp");
        try {
            Files.writeString(temporary, ProtoJson.line(marker) + "\n", StandardCharsets.UTF_8);
            Files.createLink(directory.resolve(name), temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Writes a message as indented JSON into a new file. */
    static void writePretty(Path file, Message message) throws IOException {
        try (BufferedWriter writer = newFile(file)) {
            writer.write(ProtoJson.pretty(message));
            writer.newLine();
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

    /**
     * Reads a pattern file: one regular expression per line, as {@code --include}/{@code --exclude} would take it.
     * Blank lines and lines starting with {@code #} are skipped; a pattern starting with {@code #} is written
     * {@code \#}. A line is otherwise taken verbatim, spaces included, since frame names can contain them. A file
     * without any pattern is refused rather than read as no filter, which for an include would keep everything.
     * {@code preset:NAME} names a pattern list bundled with the correlator instead of a file; see {@link Presets}.
     */
    static List<String> patternFile(String option, String file) throws IOException {
        List<String> patterns = new java.util.ArrayList<>();
        List<String> lines = file.startsWith(Presets.PREFIX)
                ? Presets.lines(file.substring(Presets.PREFIX.length()))
                : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
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

    static String collapsedMicros(String nanoseconds) {
        BigInteger nanos = new BigInteger(nanoseconds);
        if (nanos.signum() <= 0) return "0";
        return nanos.add(BigInteger.valueOf(500))
                .divide(BigInteger.valueOf(1000))
                .max(BigInteger.ONE)
                .toString();
    }

    static BufferedWriter newFile(Path file) throws IOException {
        return Files.newBufferedWriter(
                file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
