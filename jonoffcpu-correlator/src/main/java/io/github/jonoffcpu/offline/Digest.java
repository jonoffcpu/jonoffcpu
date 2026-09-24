// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.offline;

import io.github.jonoffcpu.capture.ProtoJson;
import io.github.jonoffcpu.offline.AnalysisProto.DigestCapture;
import io.github.jonoffcpu.offline.AnalysisProto.DigestTable;
import io.github.jonoffcpu.offline.AnalysisProto.TopResult;
import io.github.jonoffcpu.offline.AnalysisProto.TopRow;
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
 * AI agents, which states the capture's coverage and losses, where the time went, the ranked tables of {@link Top},
 * the heaviest transformed stacks, and for each table the command that reproduces it. The Markdown is rendered from
 * the {@link AnalysisProto.Digest} the JSON prints, so the two cannot disagree.
 */
final class Digest {
    /** What the digest is computed with. {@code app} empty ranks by the collapsed leaf instead of a boundary. */
    record Options(
            List<StackTransforms.Sourced> app,
            List<StackTransforms.Sourced> idle,
            List<StackTransforms.Sourced> machinery,
            int limit) {}

    private Digest() {}

    /** The default options: {@code preset:jvm-idle}, {@code preset:jvm-wait-machinery}, no application pattern. */
    static Options defaults() throws IOException {
        return new Options(
                List.of(),
                Cli.sourced(List.of(), "--idle-from", List.of("preset:jvm-idle")),
                Cli.sourced(List.of(), "--machinery-from", List.of("preset:jvm-wait-machinery")),
                20);
    }

    /**
     * The digest of a profile. {@code profilePath} is how the reproduce commands name the profile; {@code report} is
     * the correlation report, or null when there is none to summarise.
     */
    static AnalysisProto.Digest of(StackProfile profile, String profilePath, ReportProto.Report report, Options options)
            throws IOException {
        boolean withApp = !options.app().isEmpty();
        List<StackTransforms.Sourced> collapse = options.machinery();
        // Canonical names keep generated-class addresses out of the callers, and out of a comparison between runs.
        StackTransforms transforms = new StackTransforms(
                true,
                List.of(),
                List.of(),
                List.of(),
                false,
                List.of(),
                withApp ? List.of() : collapse,
                false,
                StackTransforms.ThreadFrame.NONE);
        Top.Options topOptions = new Top.Options(
                withApp ? Top.By.BOUNDARY : Top.By.SELF,
                options.app(),
                options.idle(),
                options.machinery(),
                options.limit(),
                transforms,
                StackProfileRenderer.PackageNames.FULL,
                StackProfileRenderer.Weights.OBSERVED,
                null,
                StackProfileRenderer.Filter.NONE);
        List<String> topCommand = new ArrayList<>(List.of(Cli.NAME, "top", "--profile", profilePath));
        topCommand.addAll(patternOptions("--app", "--app-from", options.app()));
        topCommand.addAll(patternOptions("--idle", "--idle-from", options.idle()));
        topCommand.add("--canonical-names");
        if (!withApp) {
            topCommand.addAll(List.of("--by", "self"));
            topCommand.addAll(patternOptions("--collapse-leaf", "--collapse-leaf-from", collapse));
        }
        topCommand.addAll(List.of("--limit", Integer.toString(options.limit()), "--format", "md"));
        Top.Input input = Top.fromProfile(Path.of(profilePath), profile, topOptions);
        TopResult tables = Top.tables(input, topOptions, Top.shell(topCommand));
        String by = withApp ? "boundary" : "self";

        AnalysisProto.Digest.Builder digest = AnalysisProto.Digest.newBuilder()
                .setProfile(profilePath)
                .setRun(StackProfileRenderer.defaultRun(profile))
                .setEstimateAvailable(profile.header().estimateAvailable())
                .setTimeSplitAvailable(profile.header().timeSplitAvailable());
        if (report != null) digest.setCapture(capture(report));
        digest.setSelection(tables.getSelection()).setWhereTheTimeWent(tables.getTotals());
        digest.setBusy(DigestTable.newBuilder()
                .setBy(by)
                .setCommand(Top.shell(topCommand))
                .addAllRows(tables.getRowsList()));
        if (withApp) {
            digest.setBusyNoApplicationFrameByPool(DigestTable.newBuilder()
                    .setBy("pool")
                    .setCommand(Top.shell(topCommand))
                    .addAllRows(tables.getNoApplicationFrameList()));
        } else if (input.pools()) {
            List<String> poolCommand = new ArrayList<>(topCommand);
            int byIndex = poolCommand.indexOf("--by");
            poolCommand.set(byIndex + 1, "pool");
            Top.Options poolOptions = new Top.Options(
                    Top.By.POOL,
                    options.app(),
                    options.idle(),
                    options.machinery(),
                    options.limit(),
                    transforms,
                    StackProfileRenderer.PackageNames.FULL,
                    StackProfileRenderer.Weights.OBSERVED,
                    null,
                    StackProfileRenderer.Filter.NONE);
            digest.setBusyNoApplicationFrameByPool(DigestTable.newBuilder()
                    .setBy("pool")
                    .setCommand(Top.shell(poolCommand))
                    .addAllRows(Top.tables(input, poolOptions, Top.shell(poolCommand))
                            .getRowsList()));
        }
        digest.setIdle(DigestTable.newBuilder()
                .setBy(by)
                .setCommand(Top.shell(topCommand))
                .addAllRows(tables.getIdleList()));
        digest.setHeaviestStacks(heaviest(profile, profilePath, options, withApp));
        digest.addAllWarnings(tables.getWarningsList());
        return digest.build();
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

    /** The heaviest busy stacks after the transforms that make them short, ten lines. */
    private static AnalysisProto.HeaviestStacks heaviest(
            StackProfile profile, String profilePath, Options options, boolean withApp) throws IOException {
        StackTransforms transforms = withApp
                ? new StackTransforms(
                        true,
                        List.of(),
                        List.of(),
                        options.app(),
                        false,
                        List.of(),
                        options.machinery(),
                        false,
                        StackTransforms.ThreadFrame.NONE)
                : new StackTransforms(
                        true,
                        List.of(),
                        Cli.sourced(List.of(), "--trim-root-from", List.of("preset:jvm-infra")),
                        List.of(),
                        false,
                        List.of(),
                        options.machinery(),
                        false,
                        StackTransforms.ThreadFrame.NONE);
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
                StackProfileRenderer.PackageNames.DROP,
                transforms);
        List<String> command = new ArrayList<>(List.of(Cli.NAME, "stacks", "--profile", profilePath));
        command.addAll(patternOptions("--exclude", "--exclude-from", options.idle()));
        if (withApp) {
            command.addAll(patternOptions("--root-at", "--root-at-from", options.app()));
        } else {
            command.addAll(List.of("--trim-root-from", "preset:jvm-infra"));
        }
        command.addAll(patternOptions("--collapse-leaf", "--collapse-leaf-from", options.machinery()));
        command.addAll(List.of("--canonical-names", "--package-names", "drop", "--output", "busy.collapsed"));
        AnalysisProto.HeaviestStacks.Builder heaviest = AnalysisProto.HeaviestStacks.newBuilder()
                .setCommand(Top.shell(command))
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
        if (digest.hasCapture()) capture(digest.getCapture(), text);

        text.append("## Where the time went\n\n| Slice | Entries | Intervals | s |\n|---|---:|---:|---:|\n");
        Top.totalsRows(digest.getWhereTheTimeWent(), true, text);
        text.append("\nIdle means a frame of the stack matched one of the idle patterns ")
                .append(patterns(digest.getSelection().getIdleList()))
                .append(". A nonzero over-exclusion check means idle patterns hid waits on a lock or monitor.\n\n");

        DigestTable busy = digest.getBusy();
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
        text.append(boundary ? "## Idle, by boundary\n\n" : "## Idle, by leaf\n\n");
        section(digest.getIdle(), boundary ? "boundary" : "key", text);

        AnalysisProto.HeaviestStacks heaviest = digest.getHeaviestStacks();
        text.append("## Heaviest transformed stacks\n\nThe busy slice renders as ")
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
        text.append("- Busy and idle tables: `").append(busy.getCommand()).append("`\n");
        if (digest.hasBusyNoApplicationFrameByPool()
                && !digest.getBusyNoApplicationFrameByPool().getCommand().equals(busy.getCommand())) {
            text.append("- Pool table: `")
                    .append(digest.getBusyNoApplicationFrameByPool().getCommand())
                    .append("`\n");
        }
        text.append("- Heaviest stacks: `").append(heaviest.getCommand()).append("`\n");
        text.append("- Any other question: `")
                .append(Cli.NAME)
                .append(" export --profile ")
                .append(digest.getProfile())
                .append(" --format jsonl --output entries.jsonl`\n");
        return text.toString();
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

    private static void section(DigestTable section, String keyName, StringBuilder text) {
        List<TopRow> rows = section.getRowsList();
        StringBuilder table = new StringBuilder();
        Top.table(rows, keyName, "s", "Intervals", true, table);
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
