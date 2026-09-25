// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import io.github.jonoffcpu.codec.ProtoJson;
import io.github.jonoffcpu.correlator.AnalysisProto.DigestCapture;
import io.github.jonoffcpu.correlator.AnalysisProto.DigestTable;
import io.github.jonoffcpu.correlator.AnalysisProto.TopResult;
import io.github.jonoffcpu.correlator.AnalysisProto.TopRow;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The analysis digest, {@code jonoffcpu-summary.json} and {@code .md}: a bounded summary of one capture for people and
 * AI agents, which states the capture's coverage and losses, where the time went, the ranked busy tables of {@link
 * Top}, the heaviest transformed busy stacks, and for each table the command that reproduces it. Idle intervals, waits
 * for work, are counted in where the time went and left out of everything else, since they would otherwise dominate
 * every table. The Markdown is rendered from the {@link AnalysisProto.Digest} the JSON prints, so the two cannot
 * disagree.
 *
 * <p>With an application pattern every table and stack is computed from one set of transforms, those of the
 * application-rooted flame graph: canonical names, the {@code hide} frames removed, each stack rooted at its first
 * application frame, busy time without one hidden and counted apart, and the wait machinery collapsed. Without one
 * the tables rank the collapsed leaf, as before schema 2.
 */
final class Digest {
    /** The {@link AnalysisProto.Digest#getSchemaVersion() schema} this class writes. */
    static final int SCHEMA_VERSION = 2;

    /**
     * What the digest is computed with. {@code app} empty ranks by the collapsed leaf instead of a boundary, and
     * {@code hide} only applies with an application pattern.
     */
    record Options(
            List<StackTransforms.Sourced> app,
            List<StackTransforms.Sourced> idle,
            List<StackTransforms.Sourced> machinery,
            List<StackTransforms.Sourced> hide,
            int limit) {}

    private Digest() {}

    /** The default options: {@code preset:jvm-idle}, {@code preset:jvm-wait-machinery}, no application pattern. */
    static Options defaults() throws IOException {
        return defaults(Cli.sourced(List.of(), "--idle-from", List.of("preset:jvm-idle")));
    }

    /** The default options with the given idle patterns, as correlation writes the digest. */
    static Options defaults(List<StackTransforms.Sourced> idle) throws IOException {
        return new Options(
                List.of(),
                idle,
                Cli.sourced(List.of(), "--machinery-from", List.of("preset:jvm-wait-machinery")),
                List.of(),
                20);
    }

    /**
     * The digest of a profile. {@code profilePath} is how the reproduce commands name the profile; {@code report} is
     * the correlation report, or null when there is none to summarise.
     */
    static AnalysisProto.Digest of(StackProfile profile, String profilePath, ReportProto.Report report, Options options)
            throws IOException {
        if (!options.app().isEmpty()) return application(profile, profilePath, report, options);
        List<StackTransforms.Sourced> collapse = options.machinery();
        // Canonical names keep generated-class addresses out of the callers, and out of a comparison between runs.
        StackTransforms transforms = new StackTransforms(
                true,
                List.of(),
                List.of(),
                List.of(),
                StackTransforms.UnmatchedRoot.BUCKET,
                List.of(),
                collapse,
                false,
                StackTransforms.ThreadFrame.NONE);
        Top.Options topOptions = topOptions(Top.By.SELF, options, transforms);
        List<String> topCommand = new ArrayList<>(List.of(Cli.NAME, "top", "--profile", profilePath));
        topCommand.addAll(patternOptions("--idle", "--idle-from", options.idle()));
        topCommand.add("--canonical-names");
        topCommand.addAll(List.of("--by", "self"));
        topCommand.addAll(patternOptions("--collapse-leaf", "--collapse-leaf-from", collapse));
        topCommand.addAll(List.of("--limit", Integer.toString(options.limit()), "--format", "md"));
        Top.Input input = Top.fromProfile(Path.of(profilePath), profile, topOptions);
        TopResult tables = Top.tables(input, topOptions, Top.shell(topCommand));

        AnalysisProto.Digest.Builder digest = header(profile, profilePath, report);
        digest.setSelection(tables.getSelection()).setWhereTheTimeWent(tables.getTotals());
        digest.setBusy(DigestTable.newBuilder()
                .setBy("self")
                .setCommand(Top.shell(topCommand))
                .addAllRows(tables.getRowsList()));
        if (input.pools()) {
            List<String> poolCommand = new ArrayList<>(topCommand);
            int byIndex = poolCommand.indexOf("--by");
            poolCommand.set(byIndex + 1, "pool");
            Top.Options poolOptions = topOptions(Top.By.POOL, options, transforms);
            digest.setBusyNoApplicationFrameByPool(DigestTable.newBuilder()
                    .setBy("pool")
                    .setCommand(Top.shell(poolCommand))
                    .addAllRows(Top.tables(input, poolOptions, Top.shell(poolCommand))
                            .getRowsList()));
        }
        StackTransforms trimmed = new StackTransforms(
                true,
                List.of(),
                Cli.sourced(List.of(), "--trim-root-from", List.of("preset:jvm-infra")),
                List.of(),
                StackTransforms.UnmatchedRoot.BUCKET,
                List.of(),
                options.machinery(),
                false,
                StackTransforms.ThreadFrame.NONE);
        List<String> command = new ArrayList<>(List.of(Cli.NAME, "stacks", "--profile", profilePath));
        command.addAll(patternOptions("--exclude", "--exclude-from", options.idle()));
        command.addAll(List.of("--trim-root-from", "preset:jvm-infra"));
        command.addAll(patternOptions("--collapse-leaf", "--collapse-leaf-from", options.machinery()));
        command.addAll(List.of("--canonical-names", "--package-names", "drop", "--output", "busy.collapsed"));
        digest.setHeaviestStacks(
                heaviest(profile, options, trimmed, StackProfileRenderer.PackageNames.DROP, Top.shell(command)));
        digest.addAllWarnings(tables.getWarningsList());
        return digest.build();
    }

    /**
     * The application-rooted digest: every table from the transforms of the application-rooted flame graph, the busy
     * time without an application frame hidden from them and counted by pool.
     */
    private static AnalysisProto.Digest application(
            StackProfile profile, String profilePath, ReportProto.Report report, Options options) throws IOException {
        StackTransforms transforms = new StackTransforms(
                true,
                options.hide(),
                List.of(),
                options.app(),
                StackTransforms.UnmatchedRoot.HIDE,
                List.of(),
                options.machinery(),
                false,
                StackTransforms.ThreadFrame.NONE);
        // The transforms as the command line spells them, shared by every reproduce command.
        List<String> transformWords = new ArrayList<>(List.of("--canonical-names"));
        transformWords.addAll(patternOptions("--hide", "--hide-from", options.hide()));
        transformWords.addAll(patternOptions("--root-at", "--root-at-from", options.app()));
        transformWords.addAll(List.of("--root-at-unmatched", "hide"));
        transformWords.addAll(patternOptions("--collapse-leaf", "--collapse-leaf-from", options.machinery()));

        Top.Options boundaryOptions = topOptions(Top.By.BOUNDARY, options, transforms);
        Top.Input input = Top.fromProfile(Path.of(profilePath), profile, boundaryOptions);
        AnalysisProto.Digest.Builder digest = header(profile, profilePath, report);
        java.util.Set<String> warnings = new java.util.LinkedHashSet<>();
        TopResult boundary = null;
        for (Top.By by : List.of(Top.By.BOUNDARY, Top.By.ROOT, Top.By.APP_METHOD)) {
            List<String> command = new ArrayList<>(List.of(Cli.NAME, "top", "--profile", profilePath));
            command.addAll(patternOptions("--app", "--app-from", options.app()));
            command.addAll(patternOptions("--idle", "--idle-from", options.idle()));
            if (!isDefaultMachinery(options.machinery())) {
                command.addAll(patternOptions("--machinery", "--machinery-from", options.machinery()));
            }
            command.addAll(transformWords);
            command.addAll(List.of("--by", by.label(), "--limit", Integer.toString(options.limit()), "--format", "md"));
            TopResult tables = Top.tables(input, topOptions(by, options, transforms), Top.shell(command));
            warnings.addAll(tables.getWarningsList());
            DigestTable table = DigestTable.newBuilder()
                    .setBy(by.label())
                    .setCommand(tables.getCommand())
                    .addAllRows(tables.getRowsList())
                    .build();
            switch (by) {
                case BOUNDARY -> {
                    boundary = tables;
                    digest.setBusy(table);
                    digest.setBusyNoApplicationFrameByPool(DigestTable.newBuilder()
                            .setBy("pool")
                            .setCommand(tables.getCommand())
                            .addAllRows(tables.getNoApplicationFrameList()));
                }
                case ROOT -> digest.setBusyByRoot(table);
                default -> digest.setBusyByApplicationMethod(table);
            }
        }
        digest.setSelection(boundary.getSelection())
                .setWhereTheTimeWent(boundary.getTotals())
                .setBusyWithoutApplicationFrame(boundary.getTotals().getBusyNoApplicationFrame());
        List<String> command = new ArrayList<>(List.of(Cli.NAME, "stacks", "--profile", profilePath));
        command.addAll(patternOptions("--exclude", "--exclude-from", options.idle()));
        command.addAll(transformWords);
        command.addAll(List.of("--package-names", "abbreviate", "--output", "busy-app.collapsed"));
        digest.setHeaviestApplicationStacks(heaviest(
                profile, options, transforms, StackProfileRenderer.PackageNames.ABBREVIATE, Top.shell(command)));
        digest.addAllWarnings(warnings);
        return digest.build();
    }

    private static boolean isDefaultMachinery(List<StackTransforms.Sourced> machinery) {
        return machinery.stream().allMatch(pattern -> pattern.source().equals("preset:jvm-wait-machinery"));
    }

    private static Top.Options topOptions(Top.By by, Options options, StackTransforms transforms) {
        return new Top.Options(
                by,
                options.app(),
                options.idle(),
                options.machinery(),
                options.limit(),
                transforms,
                StackProfileRenderer.PackageNames.FULL,
                StackProfileRenderer.Weights.OBSERVED,
                null,
                StackProfileRenderer.Filter.NONE);
    }

    private static AnalysisProto.Digest.Builder header(
            StackProfile profile, String profilePath, ReportProto.Report report) {
        AnalysisProto.Digest.Builder digest = AnalysisProto.Digest.newBuilder()
                .setSchemaVersion(SCHEMA_VERSION)
                .setProfile(profilePath)
                .setRun(StackProfileRenderer.defaultRun(profile))
                .setEstimateAvailable(profile.header().estimateAvailable())
                .setTimeSplitAvailable(profile.header().timeSplitAvailable());
        if (report != null) digest.setCapture(capture(report));
        return digest;
    }

    private static List<String> patternOptions(String inline, String fromFile, List<StackTransforms.Sourced> patterns) {
        List<String> words = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (StackTransforms.Sourced pattern : patterns) {
            if (pattern.source().equals("inline")) {
                words.addAll(List.of(inline, pattern.pattern()));
            } else if (!sources.contains(pattern.source())) {
                sources.add(pattern.source());
                words.addAll(List.of(fromFile, pattern.source()));
            }
        }
        return words;
    }

    /** The capture's coverage and losses, from the correlation report. */
    static DigestCapture capture(ReportProto.Report report) {
        DigestCapture.Builder capture = DigestCapture.newBuilder()
                .setSessionId(report.getAnalysisInputs().getSessionId())
                .setSampling(report.getAnalysisInputs().getSampling())
                .setSourceRows(report.getSourceRows())
                .setMatched(report.getMatched())
                .setSourceRowsWithoutSelectedJfrSample(report.getSourceRowsWithoutSelectedJfrSample())
                .setOrphanJfr(report.getOrphanJfr())
                .setInvalidSource(report.getInvalidSource())
                .setInvalidJfr(report.getInvalidJfr())
                .setIdentityUnverified(report.getIdentityUnverified())
                .setSelectedObservedDurationNanos(report.getSelectedObservedDurationNanos());
        if (report.hasSourceCounters()) {
            var kernel = report.getSourceCounters().getKernel();
            var userspace = report.getSourceCounters().getUserspace();
            capture.setLoss(AnalysisProto.CollectorLoss.newBuilder()
                    .setSelectedIntervals(kernel.getSelectedIntervals())
                    .setSequenceContentions(kernel.getSequenceContentions())
                    .setSequenceExhaustions(kernel.getSequenceExhaustions())
                    .setRingReserveFailures(kernel.getRingReserveFailures())
                    .setReceivedObservations(userspace.getReceivedObservations())
                    .setWriteFailures(userspace.getWriteFailures())
                    .setPollFailures(userspace.getPollFailures()));
        }
        if (report.hasHandlerDelayNanos()) capture.setHandlerDelayNanos(report.getHandlerDelayNanos());
        if (report.hasOffCpuReasons()) {
            capture.addAllReasons(report.getOffCpuReasons().getSelectedList());
            capture.setKernelSwitchOuts(report.getOffCpuReasons().getKernelSwitchOuts());
        }
        if (report.hasPopulationEstimate()) capture.setPopulationEstimate(report.getPopulationEstimate());
        if (report.hasDegradation())
            capture.addAllDegradationSteps(report.getDegradation().getStepsAppliedList());
        return capture.build();
    }

    /** The ten heaviest busy lines after {@code transforms}, rendered as {@code command} renders them. */
    private static AnalysisProto.HeaviestStacks heaviest(
            StackProfile profile,
            Options options,
            StackTransforms transforms,
            StackProfileRenderer.PackageNames packages,
            String command)
            throws IOException {
        StackProfileRenderer.Filter filter = StackProfileRenderer.Filter.of(
                List.of(),
                options.idle().stream().map(StackTransforms.Sourced::pattern).toList());
        StackProfileRenderer.Slice slice = StackProfileRenderer.render(
                profile,
                null,
                StackProfileRenderer.StackKinds.JAVA,
                StackProfileRenderer.Weights.OBSERVED,
                StackProfileRenderer.ReasonFrame.AUTO,
                StackProfileRenderer.Time.TOTAL,
                filter,
                packages,
                transforms);
        AnalysisProto.HeaviestStacks.Builder heaviest = AnalysisProto.HeaviestStacks.newBuilder()
                .setCommand(command)
                .setLines(slice.nanos().size())
                .setMeanDepth(
                        Cli.meanDepth(slice.nanos(), profile.header().label().length())
                                .toPlainString());
        List<Map.Entry<String, BigInteger>> lines =
                new ArrayList<>(slice.nanos().entrySet());
        lines.sort(
                Map.Entry.<String, BigInteger>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        for (Map.Entry<String, BigInteger> line : lines.subList(0, Math.min(10, lines.size()))) {
            heaviest.addTop(AnalysisProto.HeavyStack.newBuilder()
                    .setStack(line.getKey().substring(profile.header().label().length()))
                    .setSeconds(new BigDecimal(line.getValue())
                            .divide(BigDecimal.valueOf(1_000_000_000L))
                            .setScale(3, java.math.RoundingMode.HALF_EVEN)
                            .toPlainString()));
        }
        return heaviest.build();
    }

    // ---- Markdown ------------------------------------------------------------------------------------------------

    /** The digest as Markdown, rendered from its message. */
    static String markdown(AnalysisProto.Digest digest) {
        StringBuilder text = new StringBuilder();
        intro(digest, text);
        if (digest.hasBusyByRoot()) return application(digest, text);
        if (digest.hasCapture()) capture(digest.getCapture(), text);

        DigestTable busy = digest.getBusy();
        text.append("## Where the time went\n\n| Slice | Entries | Intervals | s |\n|---|---:|---:|---:|\n");
        Top.totalsRows(digest.getWhereTheTimeWent(), true, text);
        text.append("\nThe tables and stacks below cover busy time only. Idle intervals, waits for work, are left out:")
                .append(" a frame of their stack matched one of the idle patterns ")
                .append(patterns(digest.getSelection().getIdleList()))
                .append(". `")
                .append(busy.getCommand())
                .append("` lists them. A nonzero over-exclusion check means idle patterns hid waits on a lock or")
                .append(" monitor.\n\n");

        boolean boundary = busy.getBy().equals("boundary");
        text.append(
                boundary
                        ? "## Busy, by application boundary\n\nEach row is the deepest application frame (its boundary) and"
                                + " the blocker below it.\n\n"
                        : "## Busy, by leaf\n\nNo application pattern was given, so each row is the stack's leaf after"
                                + " collapsing the wait machinery; pass --app to rank by application boundary.\n\n");
        section(busy, boundary ? "boundary" : "key", text);
        if (digest.hasBusyNoApplicationFrameByPool()) {
            text.append(
                    boundary
                            ? "## Busy without an application frame, by pool\n\nNative symbolization matters here: on musl"
                                    + " every native frame is /lib/ld-musl-<arch>.so.1, and idle JVM threads count as busy.\n\n"
                            : "## Busy, by pool\n\n");
            section(digest.getBusyNoApplicationFrameByPool(), "pool", text);
        }

        AnalysisProto.HeaviestStacks heaviest = digest.getHeaviestStacks();
        text.append("## Heaviest busy stacks\n\nThe busy slice, transformed, renders as ")
                .append(heaviest.getLines())
                .append(" lines at a mean depth of ")
                .append(heaviest.getMeanDepth())
                .append(" frames. Reproduce: `")
                .append(heaviest.getCommand())
                .append("`\n\n| s | Stack |\n|---:|---|\n");
        for (AnalysisProto.HeavyStack line : heaviest.getTopList()) {
            text.append("| ")
                    .append(line.getSeconds())
                    .append(" | ")
                    .append(Top.code(line.getStack()))
                    .append(" |\n");
        }
        if (digest.getWarningsCount() > 0) {
            text.append('\n');
            for (String warning : digest.getWarningsList())
                text.append("> **Warning:** ").append(warning).append("\n");
        }
        text.append("\n## How to reproduce\n\n");
        text.append("- Busy tables, and the idle waits left out: `")
                .append(busy.getCommand())
                .append("`\n");
        if (digest.hasBusyNoApplicationFrameByPool()
                && !digest.getBusyNoApplicationFrameByPool().getCommand().equals(busy.getCommand())) {
            text.append("- Pool table: `")
                    .append(digest.getBusyNoApplicationFrameByPool().getCommand())
                    .append("`\n");
        }
        text.append("- Heaviest stacks: `").append(heaviest.getCommand()).append("`\n");
        exportLine(digest, text);
        return text.toString();
    }

    private static void intro(AnalysisProto.Digest digest, StringBuilder text) {
        text.append("# jonoffcpu analysis digest\n\n");
        text.append("Profile `").append(digest.getProfile()).append("`");
        if (!digest.getRun().isEmpty()) {
            text.append(", run `").append(digest.getRun()).append('`');
        }
        text.append(". Times are observed seconds of off-CPU time")
                .append(
                        digest.getEstimateAvailable()
                                ? "; the population estimate is available (top --weights estimated)."
                                : "; the population estimate is unavailable, so under proportional or uniform sampling short"
                                        + " waits are under-weighted.")
                .append("\n\n");
    }

    private static void exportLine(AnalysisProto.Digest digest, StringBuilder text) {
        text.append("- Any other question: `")
                .append(Cli.NAME)
                .append(" export --profile ")
                .append(digest.getProfile())
                .append(" --format jsonl --output entries.jsonl`\n");
    }

    /**
     * Whether most of the busy time has no application frame, which usually means the idle patterns miss some waits
     * for work.
     */
    static boolean mostlyWithoutApplication(AnalysisProto.TopTotals totals) {
        BigDecimal busy = new BigDecimal(totals.getBusy().getValue());
        BigDecimal without = new BigDecimal(totals.getBusyNoApplicationFrame().getValue());
        return without.multiply(BigDecimal.valueOf(2)).compareTo(busy) > 0;
    }

    /**
     * The application-rooted digest: the tables that say where to look first, then where the time went, the busy
     * time the tables leave out, the capture and how to reproduce each table.
     */
    private static String application(AnalysisProto.Digest digest, StringBuilder text) {
        AnalysisProto.TopTotals totals = digest.getWhereTheTimeWent();
        BigDecimal busy = new BigDecimal(totals.getBusy().getValue());
        BigDecimal application = new BigDecimal(totals.getBusyApplication().getValue());
        boolean mostlyWithout = mostlyWithoutApplication(totals);
        text.append("Busy with an application frame: ")
                .append(totals.getBusyApplication().getValue())
                .append(" s, ")
                .append(Top.percent(Top.share(application, busy)))
                .append(" of the ")
                .append(totals.getBusy().getValue())
                .append(" s busy. Left out of the tables: ")
                .append(totals.getBusyNoApplicationFrame().getValue())
                .append(" s busy without an application frame and ")
                .append(totals.getIdle().getValue())
                .append(" s idle (see [Where the time went](#where-the-time-went)).\n\n");
        if (mostlyWithout) {
            text.append("More than half of the busy time has no application frame: the idle patterns probably miss")
                    .append(" some waits for work, or the JVM itself waited. See [Busy without an application frame,")
                    .append(" by pool](#busy-without-an-application-frame-by-pool) and extend the idle patterns.\n\n");
        }

        DigestTable waited = digest.getBusy();
        text.append("## Busy, by the application method that waited\n\nEach row is the last application method")
                .append(" before the blocking call (its boundary) and what it blocked on: the leaf side of each tower")
                .append(" of the application-rooted flame graph. The rows sum to the busy time with an application")
                .append(" frame, and shares are of it.\n\n");
        section(waited, "boundary", text);

        text.append("## Busy, by application root\n\nEach row is the first application frame once the dispatch")
                .append(" frames are hidden, where a thread entered the application: the root side of the same")
                .append(" towers, with the heaviest line under it. The rows sum to the busy time with an application")
                .append(" frame.\n\n");
        section(digest.getBusyByRoot(), "root", text);

        AnalysisProto.HeaviestStacks heaviest = digest.getHeaviestApplicationStacks();
        text.append("## Heaviest application stacks\n\nThe busy time with an application frame, rooted at the")
                .append(" application, renders as ")
                .append(heaviest.getLines())
                .append(" lines at a mean depth of ")
                .append(heaviest.getMeanDepth())
                .append(" frames; the heaviest ten, each a path from a root to what it blocked on. Reproduce, the")
                .append(" application-rooted flame graph's input: `")
                .append(heaviest.getCommand())
                .append("`\n\n| s | Stack |\n|---:|---|\n");
        for (AnalysisProto.HeavyStack line : heaviest.getTopList()) {
            text.append("| ")
                    .append(line.getSeconds())
                    .append(" | ")
                    .append(Top.code(line.getStack()))
                    .append(" |\n");
        }
        text.append('\n');

        text.append("## Busy, by application method\n\nApplication methods ranked across all stacks: each has the")
                .append(" time of every stack it is in, so shares add up to more than 100 %. Methods that are always")
                .append(" in the same stacks are one call chain, root-most first. Self time is that of the stacks")
                .append(" whose deepest application method is in the row.\n\n");
        section(digest.getBusyByApplicationMethod(), "app-method", text);

        text.append("## Where the time went\n\n| Slice | Entries | Intervals | s |\n|---|---:|---:|---:|\n");
        tableSum(totals.getBusyApplication(), "Busy with an application frame", text);
        tableSum(
                totals.getBusyNoApplicationFrame(),
                "Busy without an application frame, hidden from the tables above",
                text);
        tableSum(totals.getIdle(), "Idle, left out", text);
        tableSum(totals.getSelected(), "All selected", text);
        tableSum(totals.getOverExclusion(), "Over-exclusion check: idle entries with a lock-acquire frame", text);
        text.append("\nIdle intervals, waits for work, are left out of every table: a frame of their stack matched")
                .append(" one of the idle patterns ")
                .append(patterns(digest.getSelection().getIdleList()))
                .append(". `")
                .append(waited.getCommand())
                .append("` lists them. A nonzero over-exclusion check means idle patterns hid waits on a lock or")
                .append(" monitor.\n\n");

        text.append("## Busy without an application frame, by pool\n\nBusy time without a frame of the application,")
                .append(" left out of the tables above: waits for work the idle patterns miss, or the JVM itself")
                .append(" waiting. Native symbolization matters here: on musl every native frame is")
                .append(" /lib/ld-musl-<arch>.so.1, and idle JVM threads count as busy.");
        if (mostlyWithout) {
            text.append(" It is more than half of the busy time: add the waits for work of its heaviest pools to the")
                    .append(" idle patterns.");
        }
        text.append("\n\n");
        section(digest.getBusyNoApplicationFrameByPool(), "pool", text);

        if (digest.hasCapture()) capture(digest.getCapture(), text);
        if (digest.getWarningsCount() > 0) {
            for (String warning : digest.getWarningsList())
                text.append("> **Warning:** ").append(warning).append("\n");
            text.append('\n');
        }
        text.append("## How to reproduce\n\n");
        text.append("- By the application method that waited, the busy time without an application frame by pool, and")
                .append(" the idle waits left out: `")
                .append(waited.getCommand())
                .append("`\n");
        text.append("- By application root: `")
                .append(digest.getBusyByRoot().getCommand())
                .append("`\n");
        text.append("- By application method: `")
                .append(digest.getBusyByApplicationMethod().getCommand())
                .append("`\n");
        text.append("- Heaviest application stacks: `")
                .append(heaviest.getCommand())
                .append("`\n");
        exportLine(digest, text);
        return text.toString();
    }

    private static void tableSum(AnalysisProto.TableSum sum, String label, StringBuilder text) {
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

    private static String patterns(List<AnalysisProto.SourcedPattern> patterns) {
        List<String> sources = new ArrayList<>();
        for (AnalysisProto.SourcedPattern pattern : patterns) {
            String shown =
                    pattern.getSource().equals("inline") ? "`" + pattern.getPattern() + "`" : pattern.getSource();
            if (!sources.contains(shown)) sources.add(shown);
        }
        return sources.isEmpty() ? "(none)" : String.join(", ", sources);
    }

    /**
     * One table, as {@code top --format md} renders it: {@code by} names a ranking, whose rows render as {@link
     * Top#rowsTable}, or else the key column's heading.
     */
    private static void section(DigestTable section, String by, StringBuilder text) {
        List<TopRow> rows = section.getRowsList();
        StringBuilder table = new StringBuilder();
        if (List.of("boundary", "root", "app-method").contains(by)) {
            Top.rowsTable(rows, by, "s", "Intervals", true, table);
        } else {
            Top.table(rows, by, "s", "Intervals", true, table);
        }
        text.append(table).append('\n');
    }

    private static void capture(DigestCapture capture, StringBuilder text) {
        text.append("## Capture\n\n");
        if (capture.getReasonsCount() > 0) {
            text.append("- Reasons recorded: ")
                    .append(String.join(
                            ", ",
                            capture.getReasonsList().stream().map(Top::label).toList()))
                    .append('\n');
        }
        if (capture.hasSampling()) {
            text.append("- Sampling: `")
                    .append(ProtoJson.line(capture.getSampling()))
                    .append("`\n");
        }
        text.append("- Source rows ")
                .append(capture.getSourceRows())
                .append(", matched ")
                .append(capture.getMatched())
                .append(", outside the selected JFR window ")
                .append(capture.getSourceRowsWithoutSelectedJfrSample())
                .append(", orphan JFR ")
                .append(capture.getOrphanJfr())
                .append(", invalid ")
                .append(capture.getInvalidSource())
                .append('/')
                .append(capture.getInvalidJfr())
                .append('\n');
        if (capture.hasLoss()) {
            AnalysisProto.CollectorLoss loss = capture.getLoss();
            text.append("- Collector: selectedIntervals ")
                    .append(loss.getSelectedIntervals())
                    .append(", sequenceContentions ")
                    .append(loss.getSequenceContentions())
                    .append(", sequenceExhaustions ")
                    .append(loss.getSequenceExhaustions())
                    .append(", ringReserveFailures ")
                    .append(loss.getRingReserveFailures())
                    .append(", receivedObservations ")
                    .append(loss.getReceivedObservations())
                    .append(", writeFailures ")
                    .append(loss.getWriteFailures())
                    .append(", pollFailures ")
                    .append(loss.getPollFailures())
                    .append('\n');
        }
        if (capture.hasHandlerDelayNanos()) {
            ReportProto.HandlerDelays delays = capture.getHandlerDelayNanos();
            text.append("- Signal handler delay: p50 ")
                    .append(delays.hasP50() ? micros(delays.getP50()) : "n/a")
                    .append(", p99 ")
                    .append(delays.hasP99() ? micros(delays.getP99()) : "n/a")
                    .append('\n');
        }
        if (capture.hasKernelSwitchOuts()) {
            ReportProto.KernelSwitchOuts switchOuts = capture.getKernelSwitchOuts();
            text.append("- Kernel switch-outs by reason: blocked ")
                    .append(switchOuts.getBlocked())
                    .append(", runnable ")
                    .append(switchOuts.getRunnable())
                    .append(", preempted ")
                    .append(switchOuts.getPreempted())
                    .append('\n');
        }
        if (capture.hasPopulationEstimate()) {
            ReportProto.PopulationEstimate estimate = capture.getPopulationEstimate();
            text.append("- Population estimate: ")
                    .append(
                            estimate.getStatus() == ReportProto.EstimateStatus.ESTIMATE_STATUS_AVAILABLE
                                    ? "available"
                                    : "unavailable");
            if (estimate.hasAccountedLoss()) {
                ReportProto.AccountedLoss loss = estimate.getAccountedLoss();
                text.append(", accounted loss ")
                        .append(loss.getIntervals())
                        .append(" intervals (")
                        .append(Top.percent(loss.getFraction()))
                        .append(", ")
                        .append(loss.getReason())
                        .append(")");
            }
            text.append('\n');
        }
        if (capture.getDegradationStepsCount() > 0) {
            text.append("- Degradation steps applied: ")
                    .append(String.join(
                            ", ",
                            capture.getDegradationStepsList().stream()
                                    .map(ProtoJson::line)
                                    .toList()))
                    .append('\n');
        }
        text.append('\n');
    }

    private static String micros(long nanos) {
        return new BigDecimal(nanos).divide(BigDecimal.valueOf(1000)).setScale(1, java.math.RoundingMode.HALF_EVEN)
                + " µs";
    }

    /** Writes both files; they must not exist. */
    static void write(AnalysisProto.Digest digest, Path json, Path markdown) throws IOException {
        try (BufferedWriter writer = OffCpuCorrelator.newFile(json)) {
            writer.write(ProtoJson.pretty(digest));
            writer.newLine();
        }
        try (BufferedWriter writer = OffCpuCorrelator.newFile(markdown)) {
            writer.write(markdown(digest));
        }
    }
}
