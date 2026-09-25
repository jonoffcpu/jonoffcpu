// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import com.google.protobuf.Timestamp;
import io.github.jonoffcpu.capture.CaptureProto;
import io.github.jonoffcpu.codec.ProtoJson;
import io.github.jonoffcpu.correlator.AnalysisProto.DigestCapture;
import io.github.jonoffcpu.correlator.AnalysisProto.DigestTable;
import io.github.jonoffcpu.correlator.AnalysisProto.TableSum;
import io.github.jonoffcpu.correlator.AnalysisProto.TopResult;
import io.github.jonoffcpu.correlator.AnalysisProto.TopRow;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The analysis digest, {@code jonoffcpu-summary.json} and {@code .md}: a bounded summary of one capture for people and
 * AI agents. It says when and on what the capture was recorded, ranks the blocked time in the tables of {@link Top},
 * says where the time went and what the capture lost, and gives the command that reproduces each table. Waiting
 * intervals, threads waiting for work, are counted in where the time went and left out of every table, since they
 * would otherwise dominate them. The Markdown is rendered from the {@link AnalysisProto.Digest} the JSON prints, so the
 * two cannot disagree: tables first, one sentence under each heading, and every definition and caveat in a closing
 * section.
 *
 * <p>With an application pattern every table is computed from one set of transforms, those of the application-rooted
 * flame graph: canonical names, the {@code hide} frames removed, each stack rooted at its first application frame,
 * blocked time without one hidden and counted apart, and the wait machinery collapsed. Without one the tables rank the
 * collapsed leaf.
 */
final class Digest {
    /**
     * What the digest is computed with. {@code app} empty ranks by the collapsed leaf instead of a boundary, and
     * {@code hide} only applies with an application pattern. {@code processDetails} shows the target's command line,
     * system properties and environment variables when the report holds them.
     */
    record Options(
            List<StackTransforms.Sourced> app,
            List<StackTransforms.Sourced> waiting,
            List<StackTransforms.Sourced> machinery,
            List<StackTransforms.Sourced> hide,
            int limit,
            boolean processDetails) {}

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_TIME_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME_MILLIS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final String JVM_INFORMATION = "jdk.JVMInformation";
    private static final String CPU_INFORMATION = "jdk.CPUInformation";
    private static final String CONTAINER_CONFIGURATION = "jdk.ContainerConfiguration";
    /** The version in parentheses of a jvmVersion such as {@code OpenJDK 64-Bit Server VM (25.0.4.1+8-LTS) for …}. */
    private static final java.util.regex.Pattern JVM_VERSION = java.util.regex.Pattern.compile("\\(([^()\\s]+)\\)");

    private Digest() {}

    /** The default options: the waiting and wait machinery presets ({@code preset:*}), no application pattern. */
    static Options defaults() throws IOException {
        return defaults(Cli.sourced(List.of(), "--waiting-from", List.of(Presets.ALL)));
    }

    /** The default options with the given waiting patterns, as correlation writes the digest. */
    static Options defaults(List<StackTransforms.Sourced> waiting) throws IOException {
        return new Options(
                List.of(),
                waiting,
                Cli.sourced(List.of(), "--machinery-from", List.of(Presets.ALL)),
                List.of(),
                20,
                false);
    }

    /**
     * The digest of a profile. {@code profilePath} is how the reproduce commands name the profile; {@code report} is
     * the correlation report, or null when there is none to summarise.
     */
    static AnalysisProto.Digest of(StackProfile profile, String profilePath, ReportProto.Report report, Options options)
            throws IOException {
        AnalysisProto.Digest.Builder digest = header(profile, profilePath, report, options);
        if (!options.app().isEmpty()) {
            application(digest, profile, profilePath, options);
        } else {
            plain(digest, profile, profilePath, options);
        }
        return digest.build();
    }

    /** Without an application pattern: blocked time by the collapsed leaf and by pool. */
    private static void plain(
            AnalysisProto.Digest.Builder digest, StackProfile profile, String profilePath, Options options)
            throws IOException {
        List<StackTransforms.Sourced> collapse = options.machinery();
        // Canonical names keep generated-class addresses out of the keys, and out of a comparison between runs.
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
        topCommand.addAll(patternOptions("--waiting", "--waiting-from", options.waiting()));
        topCommand.add("--canonical-names");
        topCommand.addAll(List.of("--by", "self"));
        topCommand.addAll(patternOptions("--collapse-leaf", "--collapse-leaf-from", collapse));
        topCommand.addAll(List.of("--limit", Integer.toString(options.limit()), "--format", "md"));
        Top.Input input = Top.fromProfile(Path.of(profilePath), profile, topOptions);
        TopResult tables = Top.tables(input, topOptions, Top.shell(topCommand));

        digest.setSelection(tables.getSelection())
                .setWhereTheTimeWent(tables.getTotals())
                .setRunQueue(tables.getRunQueue())
                .setWaitingCommand(waitingCommand(profilePath, options, input.pools(), tables.getCommand()));
        digest.setBlocked(DigestTable.newBuilder()
                .setBy("self")
                .setCommand(tables.getCommand())
                .addAllRows(tables.getRowsList()));
        if (input.pools()) {
            List<String> poolCommand = new ArrayList<>(topCommand);
            poolCommand.set(poolCommand.indexOf("--by") + 1, "pool");
            Top.Options poolOptions = topOptions(Top.By.POOL, options, transforms);
            digest.setBlockedNoApplicationFrameByPool(DigestTable.newBuilder()
                    .setBy("pool")
                    .setCommand(Top.shell(poolCommand))
                    .addAllRows(Top.tables(input, poolOptions, Top.shell(poolCommand))
                            .getRowsList()));
        }
        List<String> command = new ArrayList<>(List.of(Cli.NAME, "stacks", "--profile", profilePath));
        command.addAll(patternOptions("--exclude", "--exclude-from", options.waiting()));
        command.addAll(List.of("--trim-root-from", Presets.ALL));
        command.addAll(patternOptions("--collapse-leaf", "--collapse-leaf-from", options.machinery()));
        command.addAll(List.of("--canonical-names", "--package-names", "drop", "--output", "blocked.collapsed"));
        digest.setFlameGraphCommand(Top.shell(command));
        digest.addAllWarnings(warnings(tables.getWarningsList()));
    }

    /**
     * The application-rooted digest: every table from the transforms of the application-rooted flame graph, the
     * blocked time without an application frame hidden from them and counted by pool.
     */
    private static void application(
            AnalysisProto.Digest.Builder digest, StackProfile profile, String profilePath, Options options)
            throws IOException {
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
        Set<String> warnings = new LinkedHashSet<>();
        TopResult boundary = null;
        for (Top.By by : List.of(Top.By.BOUNDARY, Top.By.ROOT, Top.By.APP_METHOD)) {
            List<String> command = new ArrayList<>(List.of(Cli.NAME, "top", "--profile", profilePath));
            command.addAll(patternOptions("--app", "--app-from", options.app()));
            command.addAll(patternOptions("--waiting", "--waiting-from", options.waiting()));
            if (!isDefaultMachinery(options.machinery())) {
                command.addAll(patternOptions("--machinery", "--machinery-from", options.machinery()));
            }
            command.addAll(transformWords);
            command.addAll(List.of("--by", by.label(), "--limit", Integer.toString(options.limit()), "--format", "md"));
            TopResult tables = Top.tables(input, topOptions(by, options, transforms), Top.shell(command));
            warnings.addAll(warnings(tables.getWarningsList()));
            DigestTable table = DigestTable.newBuilder()
                    .setBy(by.label())
                    .setCommand(tables.getCommand())
                    .addAllRows(tables.getRowsList())
                    .build();
            switch (by) {
                case BOUNDARY -> {
                    boundary = tables;
                    digest.setBlocked(table);
                    digest.setBlockedNoApplicationFrameByPool(DigestTable.newBuilder()
                            .setBy("pool")
                            .setCommand(tables.getCommand())
                            .addAllRows(tables.getNoApplicationFrameList()));
                }
                case ROOT -> digest.setBlockedByRoot(table);
                default -> digest.setBlockedByApplicationMethod(table);
            }
        }
        digest.setSelection(boundary.getSelection())
                .setWhereTheTimeWent(boundary.getTotals())
                .setRunQueue(boundary.getRunQueue())
                .setBlockedWithoutApplicationFrame(boundary.getTotals().getBlockedNoApplicationFrame())
                .setWaitingCommand(waitingCommand(profilePath, options, input.pools(), boundary.getCommand()));
        List<String> command = new ArrayList<>(List.of(Cli.NAME, "stacks", "--profile", profilePath));
        command.addAll(patternOptions("--exclude", "--exclude-from", options.waiting()));
        command.addAll(transformWords);
        command.addAll(List.of("--output", "blocked-app.collapsed"));
        digest.setFlameGraphCommand(Top.shell(command));
        digest.addAllWarnings(warnings);
    }

    /**
     * The command whose waiting table lists the waiting intervals the digest leaves out, by pool; {@code fallback}
     * when the profile has no threads.
     */
    private static String waitingCommand(String profilePath, Options options, boolean pools, String fallback) {
        if (!pools) return fallback;
        List<String> command = new ArrayList<>(List.of(Cli.NAME, "top", "--profile", profilePath));
        command.addAll(patternOptions("--waiting", "--waiting-from", options.waiting()));
        command.addAll(List.of(
                "--canonical-names", "--by", "pool", "--limit", Integer.toString(options.limit()), "--format", "md"));
        return Top.shell(command);
    }

    /** {@code top}'s warnings less those the digest states once in its notes. */
    private static List<String> warnings(List<String> warnings) {
        return warnings.stream()
                .filter(warning -> !warning.equals(Top.SYMBOLIZATION_WARNING) && !warning.equals(Top.SAMPLING_WARNING))
                .toList();
    }

    private static boolean isDefaultMachinery(List<StackTransforms.Sourced> machinery) {
        return machinery.stream().allMatch(pattern -> pattern.given().equals(Presets.ALL));
    }

    private static Top.Options topOptions(Top.By by, Options options, StackTransforms transforms) {
        return new Top.Options(
                by,
                options.app(),
                options.waiting(),
                options.machinery(),
                options.limit(),
                transforms,
                StackProfileRenderer.PackageNames.FULL,
                StackProfileRenderer.Weights.OBSERVED,
                null,
                StackProfileRenderer.Filter.NONE);
    }

    /** What every digest carries besides its tables: the profile, the capture, the recording and the window. */
    private static AnalysisProto.Digest.Builder header(
            StackProfile profile, String profilePath, ReportProto.Report report, Options options) {
        AnalysisProto.Digest.Builder digest = AnalysisProto.Digest.newBuilder()
                .setProfile(profilePath)
                .setRun(StackProfileRenderer.defaultRun(profile))
                .setEstimateAvailable(profile.header().estimateAvailable())
                .setTimeSplitAvailable(profile.header().timeSplitAvailable())
                .setSampled(!Top.exhaustive(profile.header()))
                .setWrittenBy(writtenBy());
        if (report == null) return digest;
        digest.setCapture(capture(report));
        SignalProto.JfrRecording recording = null;
        if (report.hasRecording()) {
            recording = options.processDetails()
                    ? report.getRecording()
                    : JfrSnapshot.withoutProcessDetails(report.getRecording());
            digest.setRecording(recording);
        }
        AnalysisProto.DigestWindow.Builder window = AnalysisProto.DigestWindow.newBuilder();
        ReportProto.JfrSelection selection = report.hasJfrSelection() ? report.getJfrSelection() : null;
        Instant from = null;
        Instant to = null;
        if (selection != null && selection.hasFrom() && selection.hasTo()) {
            from = Instant.parse(selection.getFrom());
            to = Instant.parse(selection.getTo());
        } else {
            window.setWholeRecording(true);
            if (recording != null && recording.hasStart() && recording.hasEnd()) {
                from = instant(recording.getStart());
                to = instant(recording.getEnd());
            }
        }
        if (from != null) {
            window.setFrom(from.toString())
                    .setTo(to.toString())
                    .setSeconds(seconds(Duration.between(from, to)).toPlainString());
        }
        return digest.setAnalysed(window);
    }

    private static String writtenBy() {
        try {
            return new Cli.Version().getVersion()[0];
        } catch (IOException unavailable) {
            return "jonoffcpu-correlator";
        }
    }

    static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds())
                .add(BigDecimal.valueOf(duration.getNano(), 9))
                .setScale(3, RoundingMode.HALF_EVEN);
    }

    private static List<String> patternOptions(String inline, String fromFile, List<StackTransforms.Sourced> patterns) {
        List<String> words = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (StackTransforms.Sourced pattern : patterns) {
            if (pattern.source().equals("inline")) {
                words.addAll(List.of(inline, pattern.pattern()));
            } else if (!sources.contains(pattern.given())) {
                // As given on the command line, so preset:* stays preset:* in the reproduce commands.
                sources.add(pattern.given());
                words.addAll(List.of(fromFile, pattern.given()));
            }
        }
        return words;
    }

    /** The capture's coverage and losses, from the correlation report. */
    static DigestCapture capture(ReportProto.Report report) {
        CaptureProto.AnalysisInputs inputs = report.getAnalysisInputs();
        DigestCapture.Builder capture = DigestCapture.newBuilder()
                .setSessionId(inputs.getSessionId())
                .setSampling(inputs.getSampling())
                .setTargetPid(inputs.getTargetPid())
                .setHostTgid(inputs.getHostTgid())
                .setJfrPath(inputs.getJfrArtifact().getPath())
                .setJfrSha256(inputs.getJfrArtifact().getSha256())
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

    // ---- Markdown ------------------------------------------------------------------------------------------------

    /** The digest as Markdown, rendered from its message. */
    static String markdown(AnalysisProto.Digest digest) {
        boolean application = digest.hasBlockedByRoot();
        String blocked = Top.blockedLabel(digest.getRunQueue());
        String lower = lower(blocked);
        AnalysisProto.TopTotals totals = digest.getWhereTheTimeWent();
        StringBuilder text = new StringBuilder("# jonoffcpu analysis digest\n\n");
        metadata(digest, text);
        String poolHeading = application ? blocked + " without an application frame, by pool" : blocked + ", by pool";
        if (application) {
            BigDecimal all = new BigDecimal(totals.getBlocked().getValue());
            BigDecimal with = new BigDecimal(totals.getBlockedApplication().getValue());
            text.append(blocked)
                    .append(" with an application frame: ")
                    .append(totals.getBlockedApplication().getValue())
                    .append(" s, ")
                    .append(Top.percent(Top.share(with, all)))
                    .append(" of the ")
                    .append(totals.getBlocked().getValue())
                    .append(" s ")
                    .append(lower)
                    .append("; ")
                    .append(totals.getWaiting().getValue())
                    .append(" s waiting left out. Terms: [About this digest](#about-this-digest).\n\n");
            if (mostlyWithoutApplication(totals)) {
                text.append("> Most of the ")
                        .append(lower)
                        .append(" time has no application frame: see [")
                        .append(poolHeading)
                        .append("](#")
                        .append(anchor(poolHeading))
                        .append(").\n\n");
            }
        } else {
            BigDecimal all = new BigDecimal(totals.getSelected().getValue());
            BigDecimal part = new BigDecimal(totals.getBlocked().getValue());
            text.append(blocked)
                    .append(": ")
                    .append(totals.getBlocked().getValue())
                    .append(" s, ")
                    .append(Top.percent(Top.share(part, all)))
                    .append(" of the ")
                    .append(totals.getSelected().getValue())
                    .append(" s selected; ")
                    .append(totals.getWaiting().getValue())
                    .append(" s waiting left out. Terms: [About this digest](#about-this-digest).\n\n");
        }

        if (application) {
            heading(blocked + ", by the application method that waited", text);
            text.append("Each row is the last application method before the blocking call and what it blocked on.\n\n");
            section(digest.getBlocked(), "boundary", text);
            heading(blocked + ", by application root", text);
            text.append(
                    "Each row is the first application frame of a stack, where a thread entered the application.\n\n");
            section(digest.getBlockedByRoot(), "root", text);
            heading(blocked + ", by application method", text);
            text.append("Each row is a chain of application methods always found in the same stacks, with the time of")
                    .append(" every stack it is in.\n\n");
            section(digest.getBlockedByApplicationMethod(), "app-method", text);
        } else {
            heading(blocked + ", by leaf", text);
            text.append("Each row is the stack's leaf after collapsing the wait machinery.\n\n");
            section(digest.getBlocked(), "key", text);
            if (digest.hasBlockedNoApplicationFrameByPool()) {
                heading(poolHeading, text);
                text.append("Each row is a thread pool: the thread name with every run of digits as #.\n\n");
                section(digest.getBlockedNoApplicationFrameByPool(), "pool", text);
            }
        }

        heading("Where the time went", text);
        whereTheTimeWent(totals, application, blocked, text);
        if (application) {
            heading(poolHeading, text);
            text.append("Each row is a thread pool whose ").append(lower).append(" time has no application frame.\n\n");
            section(digest.getBlockedNoApplicationFrameByPool(), "pool", text);
        }
        if (digest.hasCapture()) capture(digest.getCapture(), text);
        for (String warning : digest.getWarningsList()) {
            text.append("> **Warning:** ").append(warning).append("\n\n");
        }
        about(digest, application, text);
        reproduce(digest, application, blocked, text);
        return text.toString();
    }

    private static void heading(String heading, StringBuilder text) {
        text.append("## ").append(heading).append("\n\n");
    }

    private static String lower(String label) {
        return Character.toLowerCase(label.charAt(0)) + label.substring(1);
    }

    /** The anchor GitHub and commonmark-java's heading-anchor extension give a heading. */
    static String anchor(String heading) {
        return heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} -]", "")
                .replace(' ', '-');
    }

    /**
     * Whether most of the blocked time has no application frame, which usually means the waiting patterns miss some
     * waits for work.
     */
    static boolean mostlyWithoutApplication(AnalysisProto.TopTotals totals) {
        BigDecimal all = new BigDecimal(totals.getBlocked().getValue());
        BigDecimal without =
                new BigDecimal(totals.getBlockedNoApplicationFrame().getValue());
        return without.multiply(BigDecimal.valueOf(2)).compareTo(all) > 0;
    }

    /** The lines of the metadata block; a line is left out when the report does not have its values. */
    private static void metadata(AnalysisProto.Digest digest, StringBuilder text) {
        List<String> lines = new ArrayList<>();
        SignalProto.JfrRecording recording = digest.hasRecording() ? digest.getRecording() : null;
        if (recording != null && recording.hasStart() && recording.hasEnd()) {
            lines.add("**Recorded:** " + span(instant(recording.getStart()), instant(recording.getEnd()), false));
        }
        AnalysisProto.DigestWindow window = digest.getAnalysed();
        if (digest.hasAnalysed() && window.getWholeRecording()) {
            lines.add("**Analysed:** the whole recording");
        } else if (digest.hasAnalysed() && window.hasFrom()) {
            lines.add("**Analysed:** " + span(Instant.parse(window.getFrom()), Instant.parse(window.getTo()), true)
                    + ", the selected window");
        }
        List<String> process = new ArrayList<>();
        if (digest.hasCapture() && digest.getCapture().getHostTgid() != 0) {
            process.add("PID " + digest.getCapture().getTargetPid() + " (host TGID "
                    + digest.getCapture().getHostTgid() + ")");
        } else if (recording != null && JfrSnapshot.number(recording, JVM_INFORMATION, "pid") != null) {
            process.add("PID " + JfrSnapshot.number(recording, JVM_INFORMATION, "pid"));
        }
        String jvm = recording == null ? null : jvm(recording);
        if (jvm != null) process.add(jvm);
        if (recording != null && recording.hasAsyncProfilerVersion()) {
            process.add("async-profiler " + recording.getAsyncProfilerVersion());
        }
        if (!process.isEmpty()) lines.add("**Process:** " + String.join(", ", process));
        if (recording != null) {
            String system = system(recording);
            if (!system.isEmpty()) lines.add("**System:** " + system);
        }
        for (String line : lines) text.append("- ").append(line).append('\n');
        if (!lines.isEmpty()) text.append('\n');
    }

    /**
     * The machine in one line: the OS's name, kernel and C library from jdk.OSInformation, the CPU model and its
     * cores, and a container's limits.
     */
    static String system(SignalProto.JfrRecording recording) {
        List<String> parts = new ArrayList<>();
        String os = JfrSnapshot.text(recording, "jdk.OSInformation", "osVersion");
        if (os != null) parts.add(os(os));
        String description = JfrSnapshot.text(recording, CPU_INFORMATION, "description");
        String brand = description == null ? null : brand(description);
        String model = brand != null ? brand : JfrSnapshot.text(recording, CPU_INFORMATION, "cpu");
        if (model != null) {
            StringBuilder cpu = new StringBuilder(model);
            Long cores = JfrSnapshot.number(recording, CPU_INFORMATION, "cores");
            Long threads = JfrSnapshot.number(recording, CPU_INFORMATION, "hwThreads");
            if (cores != null) cpu.append(", ").append(cores).append(" cores");
            if (threads != null) cpu.append(", ").append(threads).append(" hardware threads");
            parts.add(cpu.toString());
        }
        if (recording.containsEvents(CONTAINER_CONFIGURATION)) {
            List<String> limits = new ArrayList<>();
            Long cpus = JfrSnapshot.number(recording, CONTAINER_CONFIGURATION, "effectiveCpuCount");
            Long memory = JfrSnapshot.number(recording, CONTAINER_CONFIGURATION, "memoryLimit");
            if (cpus != null && cpus > 0) limits.add(cpus + " CPUs");
            if (memory != null && memory > 0) limits.add(bytes(memory) + " memory");
            String type = JfrSnapshot.text(recording, CONTAINER_CONFIGURATION, "containerType");
            String container = type == null ? "container" : type + " container";
            parts.add(limits.isEmpty() ? container : container + " limited to " + String.join(" and ", limits));
        }
        return String.join("; ", parts);
    }

    /**
     * jdk.OSInformation's text in one line: the os-release PRETTY_NAME, the kernel and machine of its uname line, and
     * the C library.
     */
    static String os(String os) {
        String name = null;
        String kernel = null;
        String libc = null;
        for (String line : os.split("\n")) {
            if (line.startsWith("PRETTY_NAME=")) {
                name = line.substring("PRETTY_NAME=".length()).replace("\"", "");
            } else if (line.startsWith("uname: ")) {
                String[] words = line.substring("uname: ".length()).trim().split("\\s+");
                kernel = words.length > 2
                        ? words[0] + " " + words[1] + " " + words[words.length - 1]
                        : String.join(" ", words);
            } else if (line.startsWith("libc: ")) {
                String[] words = line.substring("libc: ".length()).trim().split("\\s+");
                libc = words.length >= 2 ? words[0] + " " + words[1] : words[0];
            }
        }
        List<String> described = new ArrayList<>();
        for (String part : new String[] {name, kernel, libc}) {
            if (part != null && !part.isBlank()) described.add(part);
        }
        if (described.isEmpty()) described.add(os.lines().findFirst().orElse("").trim());
        return String.join(", ", described);
    }

    /** The JVM's name and version, such as {@code OpenJDK 64-Bit Server VM 25.0.4.1+8-LTS}; null without one. */
    static String jvm(SignalProto.JfrRecording recording) {
        String name = JfrSnapshot.text(recording, JVM_INFORMATION, "jvmName");
        String version = JfrSnapshot.text(recording, JVM_INFORMATION, "jvmVersion");
        if (version != null) {
            java.util.regex.Matcher matcher = JVM_VERSION.matcher(version);
            if (matcher.find()) version = matcher.group(1);
        }
        if (name == null) return version;
        return version == null ? name : name + " " + version;
    }

    /** The brand of a CPU description such as {@code Brand: Intel(R) Core(TM) i9-9980HK CPU @ 2.40GHz, Vendor: …}. */
    static String brand(String description) {
        int start = description.indexOf("Brand: ");
        if (start < 0) return null;
        start += "Brand: ".length();
        int end = description.indexOf(", Vendor:", start);
        int line = description.indexOf('\n', start);
        if (end < 0 || line >= 0 && line < end) end = line < 0 ? description.length() : line;
        String brand = description.substring(start, end).trim();
        return brand.isEmpty() ? null : brand;
    }

    private static String bytes(long bytes) {
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        BigDecimal value = BigDecimal.valueOf(bytes);
        int unit = 0;
        while (unit < units.length - 1 && value.compareTo(BigDecimal.valueOf(1024)) >= 0) {
            value = value.divide(BigDecimal.valueOf(1024));
            unit++;
        }
        return value.setScale(unit == 0 ? 0 : 1, RoundingMode.HALF_EVEN)
                        .stripTrailingZeros()
                        .toPlainString() + " " + units[unit];
    }

    /** A time span in UTC: the end without its date when it is the same day, and the duration. */
    static String span(Instant from, Instant to, boolean millis) {
        DateTimeFormatter full = millis ? DATE_TIME_MILLIS : DATE_TIME;
        DateTimeFormatter time = millis ? TIME_MILLIS : TIME;
        boolean sameDay = from.atZone(ZoneOffset.UTC)
                .toLocalDate()
                .equals(to.atZone(ZoneOffset.UTC).toLocalDate());
        return full.format(from) + " UTC to " + (sameDay ? time : full).format(to) + " UTC (" + duration(from, to)
                + ")";
    }

    /** A duration as a person reads it: 38.0 s, 1 min 29 s, 2 h 5 min. */
    static String duration(Instant from, Instant to) {
        Duration duration = Duration.between(from, to);
        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return seconds(duration).setScale(1, RoundingMode.HALF_EVEN).toPlainString() + " s";
        }
        if (seconds < 3600) return (seconds / 60) + " min " + (seconds % 60) + " s";
        return (seconds / 3600) + " h " + (seconds % 3600 / 60) + " min";
    }

    private static void whereTheTimeWent(
            AnalysisProto.TopTotals totals, boolean application, String blocked, StringBuilder text) {
        text.append("| Slice | Entries | Intervals | s | Share of ")
                .append(lower(blocked))
                .append(" | Share of all selected |\n|---|---:|---:|---:|---:|---:|\n");
        totalsRow(totals.getBlocked(), blocked, text);
        if (application) {
            totalsRow(totals.getBlockedApplication(), blocked + " with an application frame", text);
            totalsRow(totals.getBlockedNoApplicationFrame(), blocked + " without an application frame", text);
        }
        totalsRow(totals.getWaiting(), "Waiting, left out", text);
        totalsRow(totals.getSelected(), "All selected", text);
        totalsRow(totals.getOverExclusion(), "Over-exclusion check: waiting entries with a lock-acquire frame", text);
        text.append('\n');
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
                .append(" | ")
                .append(sum.hasShareOfBlocked() ? Top.percent(sum.getShareOfBlocked()) : "")
                .append(" | ")
                .append(sum.hasShareOfSelected() ? Top.percent(sum.getShareOfSelected()) : "")
                .append(" |\n");
    }

    /** Definitions, caveats and the recording's details, one bullet per topic. */
    private static void about(AnalysisProto.Digest digest, boolean application, StringBuilder text) {
        heading("About this digest", text);
        text.append("**Terms**\n\n");
        text.append("- **Blocked**: a thread off the CPU while it had work to do: on a lock, a monitor, I/O, a")
                .append(" safepoint.\n");
        text.append(
                        "- **Waiting**: a thread off the CPU until work arrives, such as an event loop or an executor worker")
                .append(" waiting for a task; recognized by the waiting patterns and left out of the tables.\n");
        if (digest.getRunQueue()) {
            text.append("- **In the run queue**: a thread ready to run but waiting for a CPU. Runnable or preempted")
                    .append(" intervals are selected, so the first slice is \"Blocked or in the run queue\".\n");
        }
        if (application) {
            text.append("- **Application frame**: a frame matching the application patterns:\n");
            sources(digest.getSelection().getAppList(), text);
            text.append("- **Application root**: the first application frame of a stack once dispatch frames are")
                    .append(" hidden, where a thread entered the application.\n");
            text.append("- **Application method that waited**: the last application frame before the blocking call.\n");
            text.append("- **Self time** (application methods table): the time of the stacks whose application method")
                    .append(" that waited is in the row.\n");
        }
        text.append("- **Observed seconds**: the tables weigh each interval by its observed off-CPU time");
        if (digest.getSampled()) {
            text.append("; sampling kept only some intervals, so observed time under-weights short ones");
        }
        text.append(
                digest.getEstimateAvailable()
                        ? "; the population estimate is available (top --weights estimated).\n\n"
                        : "; the population estimate is unavailable.\n\n");

        text.append("**Notes**\n\n");
        text.append("- Waiting intervals, threads waiting for work, are left out of every table: a frame of their")
                .append(" stack matched one of the waiting patterns:\n");
        sources(digest.getSelection().getWaitingList(), text);
        text.append("- A nonzero over-exclusion check means waiting patterns hid waits on a lock or monitor.\n");
        if (application) {
            text.append("- The application method table is inclusive: a method has the time of every stack it is in,")
                    .append(" so its shares add up to more than 100 %.\n");
        }
        text.append("- Native symbolization: on musl every native frame is /lib/ld-musl-<arch>.so.1, so the JVM's")
                .append(" own threads waiting for work cannot be recognized and count as blocked.\n");
        if (digest.hasCapture()) {
            DigestCapture capture = digest.getCapture();
            text.append("- Session ").append(capture.getSessionId());
            if (!capture.getJfrPath().isEmpty()) {
                text.append("; JFR ").append(capture.getJfrPath());
                if (!capture.getJfrSha256().isEmpty()) text.append(", SHA-256 ").append(capture.getJfrSha256());
            }
            text.append(".\n");
        }
        text.append("- Written by ").append(digest.getWrittenBy()).append(".\n\n");
        processDetails(digest, text);
    }

    /** Pattern sources as a nested list, one file, preset or inline pattern per line, as given. */
    private static void sources(List<AnalysisProto.SourcedPattern> patterns, StringBuilder text) {
        List<String> sources = new ArrayList<>();
        for (AnalysisProto.SourcedPattern pattern : patterns) {
            String shown = pattern.getSource().equals("inline")
                    ? Top.code(pattern.getPattern())
                    : Top.code(pattern.getSource());
            if (!sources.contains(shown)) sources.add(shown);
        }
        if (sources.isEmpty()) sources.add("(none)");
        for (String source : sources) text.append("  - ").append(source).append('\n');
    }

    /** The command line, system properties and environment variables, present only with --process-details true. */
    private static void processDetails(AnalysisProto.Digest digest, StringBuilder text) {
        if (!digest.hasRecording()) return;
        SignalProto.JfrRecording recording = digest.getRecording();
        String jvmArguments = JfrSnapshot.text(recording, JVM_INFORMATION, "jvmArguments");
        String javaArguments = JfrSnapshot.text(recording, JVM_INFORMATION, "javaArguments");
        if (jvmArguments != null || javaArguments != null) {
            text.append("**Command line** (jdk.JVMInformation):\n\n```text\njava");
            if (jvmArguments != null) {
                for (String word : jvmArguments.trim().split("\\s+")) {
                    if (!word.isEmpty()) text.append(" \\\n  ").append(word);
                }
            }
            if (javaArguments != null && !javaArguments.isBlank()) {
                text.append(" \\\n  ").append(javaArguments.trim());
            }
            text.append("\n```\n\n");
        }
        keyValues("System properties", "jdk.InitialSystemProperty", recording.getSystemPropertiesMap(), text);
        keyValues(
                "Environment variables",
                "jdk.InitialEnvironmentVariable",
                recording.getEnvironmentVariablesMap(),
                text);
    }

    private static void keyValues(String title, String event, Map<String, String> values, StringBuilder text) {
        if (values.isEmpty()) return;
        text.append("<details><summary>")
                .append(title)
                .append(" (")
                .append(values.size())
                .append(", ")
                .append(event)
                .append(")</summary>\n\n| Name | Value |\n|---|---|\n");
        for (var entry : new java.util.TreeMap<>(values).entrySet()) {
            text.append("| ")
                    .append(Top.code(entry.getKey()))
                    .append(" | ")
                    .append(Top.code(entry.getValue().replace('\n', ' ')))
                    .append(" |\n");
        }
        text.append("\n</details>\n\n");
    }

    /** One labelled command block per table, in the order of the tables, then the flame graph, waiting and export. */
    private static void reproduce(
            AnalysisProto.Digest digest, boolean application, String blocked, StringBuilder text) {
        heading("How to reproduce", text);
        if (application) {
            command(
                    "By the application method that waited** (also the " + lower(blocked)
                            + " time without an application frame, by pool):",
                    digest.getBlocked().getCommand(),
                    text);
            command("By application root:**", digest.getBlockedByRoot().getCommand(), text);
            command(
                    "By application method:**",
                    digest.getBlockedByApplicationMethod().getCommand(),
                    text);
            command("Application-rooted flame graph input:**", digest.getFlameGraphCommand(), text);
        } else {
            command("By leaf:**", digest.getBlocked().getCommand(), text);
            if (digest.hasBlockedNoApplicationFrameByPool()) {
                command(
                        "By pool:**",
                        digest.getBlockedNoApplicationFrameByPool().getCommand(),
                        text);
            }
            command("Flame graph input:**", digest.getFlameGraphCommand(), text);
        }
        command("Waiting left out** (its waiting table, by pool):", digest.getWaitingCommand(), text);
        command(
                "Any other question:**",
                Top.shell(List.of(
                        Cli.NAME,
                        "export",
                        "--profile",
                        digest.getProfile(),
                        "--format",
                        "jsonl",
                        "--output",
                        "entries.jsonl")),
                text);
    }

    /** {@code label} is the text after the opening {@code **}, closing the bold part itself. */
    private static void command(String label, String command, StringBuilder text) {
        text.append("**")
                .append(label)
                .append("\n\n")
                .append(Top.commandBlock(command))
                .append('\n');
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
