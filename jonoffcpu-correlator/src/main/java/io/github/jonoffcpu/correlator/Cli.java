// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import com.google.protobuf.Message;
import io.github.jonoffcpu.codec.ProtoJson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;
import picocli.CommandLine.TypeConversionException;
import picocli.CommandLine.UnmatchedArgumentException;

/**
 * The correlator's command line: the default command correlates, and the subcommands work on its outputs. Every
 * option is declared here, so the generated help is the reference for names, values and defaults.
 *
 * <p>Exit codes: {@link #OK} for a complete result, help or version; {@link #NARROWED} for narrowed or partial
 * output; {@link #USAGE} for an invalid command line, reported with the command's usage and never as a stack trace.
 * A failure of the analysis itself (I/O, integrity) is thrown, as it always was.
 */
final class Cli {
    static final int OK = 0;
    static final int NARROWED = 2;
    /** {@code EX_USAGE} from {@code sysexits.h}; picocli's default of 2 would read as a narrowed result. */
    static final int USAGE = 64;

    static final String NAME = "java -jar jonoffcpu-correlator.jar";

    private Cli() {}

    /** Parses and runs one command line; see the class comment for the exit codes. */
    static int run(String[] args, PrintWriter out, PrintWriter err) throws Exception {
        CommandLine commandLine = commandLine();
        commandLine.setOut(out);
        commandLine.setErr(err);
        try {
            ParseResult parsed = commandLine.parseArgs(args);
            if (CommandLine.printHelpIfRequested(parsed)) return OK;
            List<CommandLine> chain = parsed.asCommandLineList();
            CommandLine last = chain.get(chain.size() - 1);
            requireOptions(last);
            Object command = last.getCommand();
            @SuppressWarnings("unchecked")
            Callable<Integer> callable = (Callable<Integer>) command;
            return callable.call();
        } catch (Presets.Unusable unusable) {
            // A preset the option cannot use is a mistake on the command line, not a failed analysis.
            err.println(unusable.getMessage());
            List<CommandLine> chain = commandLine.getParseResult() == null
                    ? List.of(commandLine)
                    : commandLine.getParseResult().asCommandLineList();
            chain.get(chain.size() - 1).usage(err);
            return USAGE;
        } catch (ParameterException usage) {
            err.println(usage.getMessage());
            if (usage instanceof UnmatchedArgumentException unmatched) {
                UnmatchedArgumentException.printSuggestions(unmatched, err);
            }
            usage.getCommandLine().usage(err);
            return USAGE;
        } finally {
            out.flush();
            err.flush();
        }
    }

    /**
     * Each command's required options. They are checked here rather than by picocli's {@code required}, which
     * reports them ahead of an unknown option, so a typo would read as a missing option instead of being named.
     */
    private static final java.util.Map<String, List<String>> REQUIRED = java.util.Map.of(
            NAME,
            List.of("--source", "--jfr", "--output"),
            "correlate",
            List.of("--source", "--jfr", "--output"),
            "stacks",
            List.of("--output"),
            "merge",
            List.of("--profiles", "--output"),
            "export",
            List.of("--profile", "--output"),
            "summarize",
            List.of("--profile"),
            "dump",
            List.of("--source"));

    private static void requireOptions(CommandLine command) {
        // The top-level command with no arguments at all asks for help instead, and a listing needs no input.
        if (command.getParseResult().originalArgs().isEmpty()) return;
        if (command.getParseResult().hasMatchedOption("--list-presets")) return;
        List<String> missing = new ArrayList<>();
        for (String name : REQUIRED.getOrDefault(command.getCommandName(), List.of())) {
            if (!command.getParseResult().hasMatchedOption(name)) {
                missing.add("'" + name + "="
                        + command.getCommandSpec().findOption(name).paramLabel() + "'");
            }
        }
        if (!missing.isEmpty()) {
            throw new ParameterException(
                    command,
                    (missing.size() == 1 ? "Missing required option: " : "Missing required options: ")
                            + String.join(", ", missing));
        }
    }

    static CommandLine commandLine() {
        CommandLine commandLine = new CommandLine(new Root());
        commandLine.setUsageHelpWidth(100);
        commandLine.setUsageHelpAutoWidth(false);
        return commandLine;
    }

    // ---- shared option groups ----------------------------------------------------------------------

    /** The correlation options, which are both the top-level command's and {@code correlate}'s. */
    static final class CorrelateOptions {
        @Spec(Spec.Target.MIXEE)
        CommandSpec mixee;

        @Option(
                names = "--source",
                paramLabel = "FILE",
                description = "The finalized capture stream the agent wrote (jonoffcpu-capture.pb). Required.")
        Path source;

        @Option(
                names = "--jfr",
                paramLabel = "FILE",
                description = "The combined JFR recorded with the capture. Required.")
        Path jfr;

        @Option(
                names = "--output",
                paramLabel = "DIR",
                description = "The output directory, which must not exist yet. Required.")
        Path output;

        @Option(
                names = "--from",
                paramLabel = "TIME",
                description = "Select samples from this JFR event time: an ISO-8601 timestamp, epoch milliseconds,"
                        + " a duration, or an offset from the recording start such as 30s or 2m.")
        String from;

        @Option(names = "--to", paramLabel = "TIME", description = "Select samples up to this JFR event time.")
        String to;

        @Option(
                names = "--partial-jfr",
                arity = "1",
                paramLabel = "true|false",
                defaultValue = "false",
                description = "Accept a JFR that another tool has cut; source rows without a sample in it are"
                        + " expected omissions, not loss. Default: ${DEFAULT-VALUE}.")
        boolean partialJfr;

        @Option(
                names = "--from-ns",
                paramLabel = "N",
                converter = NanosConverter.class,
                description = "Clip matched intervals to start at this source monotonic time.")
        BigInteger fromNs;

        @Option(
                names = "--to-ns",
                paramLabel = "N",
                converter = NanosConverter.class,
                description = "Clip matched intervals to end at this source monotonic time.")
        BigInteger toNs;

        @Option(
                names = "--max-handler-delay-ns",
                paramLabel = "N",
                converter = NanosConverter.class,
                description = "Reject matches whose Java stack was captured more than this long after the interval"
                        + " ended.")
        BigInteger maxHandlerDelayNs;

        @Option(
                names = "--max-rows",
                paramLabel = "N",
                defaultValue = "100000000",
                description = "Most source rows admitted. Default: ${DEFAULT-VALUE}.")
        int maxRows;

        @Option(
                names = "--max-retained-bytes",
                paramLabel = "N",
                description = "Retained-bytes budget. Default: sixty percent of the heap, never below 256 MiB.")
        Long maxRetainedBytes;

        @Option(
                names = "--format",
                paramLabel = "FORMAT",
                converter = FormatConverter.class,
                description = "Which derived outputs to write: collapsed, the only choice for a complete analysis;"
                        + " with --partial true, diagnostics or collapsed. Default: collapsed, or diagnostics"
                        + " with --partial true.")
        String format;

        @Option(
                names = "--estimate-population",
                arity = "1",
                paramLabel = "true|false",
                defaultValue = "false",
                description = "Report the inverse-probability population estimate. Requires the complete,"
                        + " unselected JFR and an unthinned source. Default: ${DEFAULT-VALUE}.")
        boolean estimatePopulation;

        @Option(
                names = "--max-accounted-loss",
                paramLabel = "F",
                defaultValue = "0.01",
                converter = FractionConverter.class,
                description = "The largest fraction of selected intervals the collector may have dropped under counted"
                        + " sequence contention for the population estimate to stay available, scaled up by the"
                        + " loss (0 <= F < 1). Default: ${DEFAULT-VALUE}.")
        BigDecimal maxAccountedLoss;

        /** Not a supported option: it lets tests drive the degradation ladder with a small input. */
        @Option(names = "--watermark-rows", paramLabel = "N", hidden = true)
        Integer watermarkRows;

        @Option(
                names = "--audit",
                paramLabel = "LEVEL",
                defaultValue = "matches",
                converter = AuditConverter.class,
                description = "Per-row audit output: full, matches or none. Default: ${DEFAULT-VALUE}, which writes "
                        + OutputFiles.MATCHES + " but not " + OutputFiles.CLASSIFIED_RECORDS + ".")
        AuditLevel audit;

        @Option(
                names = "--thinning",
                paramLabel = "Q",
                description = "Keep each recorded interval with probability Q (0 < Q <= 1) and reweight by 1/Q,"
                        + " deterministically in the cookie. Default: chosen automatically, and 1 whenever the"
                        + " input fits.")
        String thinning;

        @Option(
                names = "--thinning-seed",
                paramLabel = "N",
                defaultValue = "0",
                description = "Changes the deterministic draw --thinning uses. Default: ${DEFAULT-VALUE}.")
        long thinningSeed;

        @Option(
                names = "--on-limit",
                paramLabel = "POLICY",
                defaultValue = "degrade",
                converter = LimitPolicyConverter.class,
                description = "What to do when the retained-bytes budget is reached: degrade (drop audit, thin,"
                        + " narrow, reporting each step), fail, or truncate (narrow the window directly)."
                        + " Default: ${DEFAULT-VALUE}.")
        Degradation.Policy onLimit;

        @Option(
                names = "--partial",
                arity = "1",
                paramLabel = "true|false",
                defaultValue = "false",
                description = "Inspect an interrupted capture: writes " + OutputFiles.INCOMPLETE_PREFIX
                        + "* files and " + OutputFiles.PARTIAL + ", exits with status 2, and never writes "
                        + OutputFiles.COMPLETE + ". Default: ${DEFAULT-VALUE}.")
        boolean partial;

        @Option(
                names = "--collapsed-reason-frame",
                paramLabel = "MODE",
                defaultValue = "auto",
                converter = ReasonFrameConverter.class,
                description = "Whether each line of " + OutputFiles.COLLAPSED + " starts with its"
                        + " [offcpu: <reason>] frame: auto (when the capture mixes reasons), always or never."
                        + " Default: ${DEFAULT-VALUE}.")
        StackProfileRenderer.ReasonFrame collapsedReasonFrame;

        @Option(
                names = "--profile-output",
                arity = "1",
                paramLabel = "true|false",
                defaultValue = "true",
                description = "Whether to write " + OutputFiles.PROFILE + ". Default: ${DEFAULT-VALUE}.")
        boolean profileOutput;

        @Option(
                names = "--profile-group-by",
                paramLabel = "LIST",
                defaultValue = "kernel,user,thread",
                description = "The profile's optional dimensions besides the Java stack and the reason: any of"
                        + " kernel, user, thread, or none. Default: ${DEFAULT-VALUE}.")
        String profileGroupBy;

        @Option(
                names = "--summary-output",
                arity = "1",
                paramLabel = "true|false",
                defaultValue = "true",
                description = "Whether to write the analysis digest, " + OutputFiles.SUMMARY_MD + " and "
                        + OutputFiles.SUMMARY_JSON + ", from the profile and the report. A failure to write it is"
                        + " reported in the report and never fails the correlation. Default: ${DEFAULT-VALUE}.")
        boolean summaryOutput;

        @Option(
                names = "--waiting",
                paramLabel = "REGEX",
                description = "For the digest: an interval with a frame matching this is waiting, a wait for work, and"
                        + " the digest's tables and stacks leave it out. The other outputs keep every interval."
                        + " Repeatable.")
        List<String> waiting = new ArrayList<>();

        @Option(
                names = "--waiting-from",
                paramLabel = "FILE",
                description = "Read --waiting patterns from a file or preset:NAME; repeatable. Default, when no waiting"
                        + " pattern is given: preset:*, the bundled waiting presets (preset:jvm-waiting).")
        List<String> waitingFrom = new ArrayList<>();

        @Option(
                names = "--app",
                paramLabel = "REGEX",
                description = "For the digest: a frame of your application. Its tables then start each stack at the"
                        + " first one and name the last one before each wait, and count blocked time without one apart,"
                        + " by pool. Repeatable.")
        List<String> app = new ArrayList<>();

        @Option(
                names = "--app-from",
                paramLabel = "FILE",
                description = "Read --app patterns from a file or preset:NAME; repeatable.")
        List<String> appFrom = new ArrayList<>();

        @Mixin
        DigestHideOptions hide;

        @Option(
                names = "--process-details",
                arity = "1",
                paramLabel = "true|false",
                defaultValue = "false",
                description = "Keep the target's command line, system properties and environment variables from the"
                        + " JFR in the report and show them in the digest. They can hold secrets, such as passwords"
                        + " passed as -D options. Default: ${DEFAULT-VALUE}.")
        boolean processDetails;

        @Option(
                names = "--max-profile-entries",
                paramLabel = "N",
                defaultValue = "2000000",
                description = "Entry limit for the profile; past it the thread, then the user and the kernel stack"
                        + " are dropped from the grouping. Default: ${DEFAULT-VALUE}.")
        int maxProfileEntries;

        boolean given(String option) {
            return mixee.commandLine().getParseResult().hasMatchedOption(option);
        }

        ParameterException usage(String message) {
            return new ParameterException(mixee.commandLine(), message);
        }
    }

    /**
     * The digest's hidden frames, which {@code correlate} and {@code summarize} share: with {@code --app} the digest
     * is rooted at the application, and these frames are removed first so that the root is the work itself.
     */
    static final class DigestHideOptions {
        @Option(
                names = "--hide",
                paramLabel = "REGEX",
                description = "For the digest with --app: remove matching frames, such as executors that only run"
                        + " a task, before each stack is rooted at the application. Repeatable.")
        List<String> hide = new ArrayList<>();

        @Option(
                names = "--hide-from",
                paramLabel = "FILE",
                description = "Read --hide patterns from a file or preset:NAME; repeatable. Default, with --app and"
                        + " no hide pattern: preset:*, the bundled dispatch presets (preset:jvm-dispatch).")
        List<String> hideFrom = new ArrayList<>();

        boolean given() {
            return !hide.isEmpty() || !hideFrom.isEmpty();
        }

        /** The patterns, or with an application pattern and none given, the bundled hide presets. */
        List<StackTransforms.Sourced> patterns(boolean app) throws IOException {
            if (app && !given()) return sourced(List.of(), "--hide-from", List.of(Presets.ALL));
            return sourced(hide, "--hide-from", hideFrom);
        }
    }

    /** Which profile a slice is rendered from and how its lines are made; shared by the stack-profile commands. */
    static final class SliceOptions {
        @Option(
                names = "--profile",
                paramLabel = "FILE",
                description = "The stack profile, such as " + OutputFiles.PROFILE + "."
                        + " Required unless --collapsed-input is given.")
        Path profile;

        @Option(
                names = "--reason",
                paramLabel = "REASONS",
                defaultValue = "all",
                converter = ReasonsConverter.class,
                description = "all, or a comma-separated list of blocked, runnable and preempted."
                        + " Default: ${DEFAULT-VALUE}.")
        Reasons reasons;

        @Option(
                names = "--stack",
                paramLabel = "KINDS",
                defaultValue = "java",
                converter = StackKindsConverter.class,
                description = "Which stacks a line is made of: java, kernel, user, java+kernel or java+user+kernel."
                        + " Default: ${DEFAULT-VALUE}.")
        StackProfileRenderer.StackKinds stack;

        @Option(
                names = "--weights",
                paramLabel = "WEIGHTS",
                defaultValue = "observed",
                converter = WeightsConverter.class,
                description = "observed durations, or the estimated inverse-probability population when the"
                        + " profile's estimate is available. Default: ${DEFAULT-VALUE}.")
        StackProfileRenderer.Weights weights;

        @Option(
                names = "--time",
                paramLabel = "PART",
                defaultValue = "total",
                converter = TimeConverter.class,
                description = "Which part of each interval's time is weighed: total, sleeping, runqueue, or split"
                        + " (the whole time, each line ending in its part's frame). Default: ${DEFAULT-VALUE}.")
        StackProfileRenderer.Time time;

        @Option(
                names = "--package-names",
                paramLabel = "MODE",
                defaultValue = "full",
                converter = PackageNamesConverter.class,
                description = "How a Java frame's package is shown: full, abbreviate (initials) or drop."
                        + " Default: ${DEFAULT-VALUE}.")
        StackProfileRenderer.PackageNames packageNames;
    }

    /** The interval filters: which whole entries a slice keeps. */
    static final class FilterOptions {
        @Option(
                names = "--include",
                paramLabel = "REGEX",
                description = "Keep only intervals with a frame matching the pattern; repeatable, any matches.")
        List<String> include = new ArrayList<>();

        @Option(
                names = "--exclude",
                paramLabel = "REGEX",
                description = "Drop every interval with a frame matching the pattern; repeatable, and an"
                        + " exclusion wins.")
        List<String> exclude = new ArrayList<>();

        @Option(
                names = "--include-from",
                paramLabel = "FILE",
                description =
                        "Read --include patterns from a file, one per line, or a bundled preset:NAME;" + " repeatable.")
        List<String> includeFrom = new ArrayList<>();

        @Option(
                names = "--exclude-from",
                paramLabel = "FILE",
                description = "Read --exclude patterns from a file, one per line, or a bundled preset:NAME such"
                        + " as preset:jvm-waiting; repeatable.")
        List<String> excludeFrom = new ArrayList<>();

        StackProfileRenderer.Filter filter() throws IOException {
            return StackProfileRenderer.Filter.of(
                    patterns(include, "--include-from", includeFrom), patterns(exclude, "--exclude-from", excludeFrom));
        }
    }

    /**
     * The frame-level transforms of a line's Java stack. They change what a kept interval's stack looks like, never
     * which intervals are kept; the filters see the untransformed stack.
     */
    static final class TransformOptions {
        @Spec(Spec.Target.MIXEE)
        CommandSpec mixee;

        @Option(
                names = "--canonical-names",
                description = "Remove generated-class addresses ($$Lambda.0x..., LambdaForm$MH/0x...), so two runs'"
                        + " stacks compare.")
        boolean canonicalNames;

        @Option(
                names = "--hide",
                paramLabel = "REGEX",
                description = "Remove every frame that matches, anywhere in the stack; a stack of nothing else keeps"
                        + " its leaf. Repeatable.")
        List<String> hide = new ArrayList<>();

        @Option(
                names = "--hide-from",
                paramLabel = "FILE",
                description = "Read --hide patterns from a file or preset:NAME; repeatable.")
        List<String> hideFrom = new ArrayList<>();

        @Option(
                names = "--trim-root",
                paramLabel = "REGEX",
                description = "Remove the longest root-side run of matching frames, such as thread and event-loop"
                        + " entry points; deeper frames stay even if they match. Repeatable.")
        List<String> trimRoot = new ArrayList<>();

        @Option(
                names = "--trim-root-from",
                paramLabel = "FILE",
                description = "Read --trim-root patterns from a file or preset:NAME, such as preset:jvm-infra;"
                        + " repeatable.")
        List<String> trimRootFrom = new ArrayList<>();

        @Option(
                names = "--root-at",
                paramLabel = "REGEX",
                description = "Start the stack at the root-most matching frame, such as your application's first"
                        + " frame. Repeatable.")
        List<String> rootAt = new ArrayList<>();

        @Option(
                names = "--root-at-from",
                paramLabel = "FILE",
                description = "Read --root-at patterns from a file or preset:NAME; repeatable.")
        List<String> rootAtFrom = new ArrayList<>();

        @Option(
                names = "--root-at-unmatched",
                paramLabel = "MODE",
                defaultValue = "bucket",
                converter = UnmatchedConverter.class,
                description = "A stack without a --root-at match: bucket (becomes the single frame"
                        + " " + StackTransforms.NO_APPLICATION_FRAME + "), keep (unchanged) or hide (left out of"
                        + " the lines and the rows, and reported as a total of its own). Default: ${DEFAULT-VALUE}.")
        StackTransforms.UnmatchedRoot rootAtUnmatched;

        @Option(
                names = "--leaf-at",
                paramLabel = "REGEX",
                description = "Cut the callees of the leaf-most matching frame, keeping the match. Repeatable.")
        List<String> leafAt = new ArrayList<>();

        @Option(
                names = "--leaf-at-from",
                paramLabel = "FILE",
                description = "Read --leaf-at patterns from a file or preset:NAME; repeatable.")
        List<String> leafAtFrom = new ArrayList<>();

        @Option(
                names = "--collapse-leaf",
                paramLabel = "REGEX",
                description = "Replace the longest leaf-side run of matching frames, the wait machinery, by its"
                        + " root-most frame, such as ReentrantLock.lock. Repeatable.")
        List<String> collapseLeaf = new ArrayList<>();

        @Option(
                names = "--collapse-leaf-from",
                paramLabel = "FILE",
                description = "Read --collapse-leaf patterns from a file or preset:NAME, such as"
                        + " preset:jvm-wait-machinery; repeatable.")
        List<String> collapseLeafFrom = new ArrayList<>();

        @Option(
                names = "--collapse-leaf-label",
                paramLabel = "LABEL",
                defaultValue = "frame",
                converter = LeafLabelConverter.class,
                description = "What replaces a collapsed run: frame (its root-most frame) or category ([monitor],"
                        + " [lock], [park], [wait], [sleep], [native] or [kernel]). Default: ${DEFAULT-VALUE}.")
        String collapseLeafLabel;

        @Option(
                names = "--thread-frame",
                paramLabel = "MODE",
                defaultValue = "none",
                converter = ThreadFrameConverter.class,
                description = "Start each line with the thread's name, or its pool (digit runs replaced by #:"
                        + " pulsar-io-3-25 is pulsar-io-#-#); needs the profile's thread dimension. none, name or"
                        + " pool. Default: ${DEFAULT-VALUE}.")
        StackTransforms.ThreadFrame threadFrame;

        StackTransforms transforms() throws IOException {
            if (rootAtUnmatched == StackTransforms.UnmatchedRoot.HIDE && rootAt.isEmpty() && rootAtFrom.isEmpty()) {
                throw new ParameterException(
                        mixee.commandLine(),
                        "--root-at-unmatched hide needs --root-at or --root-at-from: without them nothing is"
                                + " unmatched");
            }
            return new StackTransforms(
                    canonicalNames,
                    sourced(hide, "--hide-from", hideFrom),
                    sourced(trimRoot, "--trim-root-from", trimRootFrom),
                    sourced(rootAt, "--root-at-from", rootAtFrom),
                    rootAtUnmatched,
                    sourced(leafAt, "--leaf-at-from", leafAtFrom),
                    sourced(collapseLeaf, "--collapse-leaf-from", collapseLeafFrom),
                    collapseLeafLabel.equals("category"),
                    threadFrame);
        }
    }

    /** A transform option's patterns with their sources: inline ones first, then each file or preset in order. */
    static List<StackTransforms.Sourced> sourced(List<String> inline, String fromOption, List<String> files)
            throws IOException {
        List<StackTransforms.Sourced> patterns = new ArrayList<>();
        for (String pattern : inline) {
            try {
                java.util.regex.Pattern.compile(pattern);
            } catch (java.util.regex.PatternSyntaxException invalid) {
                throw new IllegalArgumentException(
                        "Invalid pattern for " + fromOption.replace("-from", "") + ": " + invalid.getMessage(),
                        invalid);
            }
            patterns.add(new StackTransforms.Sourced(pattern, "inline"));
        }
        for (String file : files) {
            for (String source : Presets.expand(fromOption, file)) {
                for (String pattern : OffCpuCorrelator.patternFile(fromOption, source)) {
                    patterns.add(new StackTransforms.Sourced(pattern, source, file));
                }
            }
        }
        return patterns;
    }

    /** A filter option's patterns: those given inline, then those of each pattern file in order. */
    static List<String> patterns(List<String> inline, String fromOption, List<String> files) throws IOException {
        List<String> patterns = new ArrayList<>(inline);
        for (String file : files) patterns.addAll(OffCpuCorrelator.patternFile(fromOption, file));
        return patterns;
    }

    // ---- commands ----------------------------------------------------------------------------------

    @Command(
            name = NAME,
            mixinStandardHelpOptions = true,
            versionProvider = Version.class,
            sortOptions = false,
            showDefaultValues = false,
            description = "Correlates a jonoffcpu capture with its JFR, and renders, merges and exports the stack"
                    + " profile it produces. Without a subcommand it correlates, with the options below.",
            subcommands = {
                Correlate.class,
                Stacks.class,
                TopCommand.class,
                Summarize.class,
                Merge.class,
                Export.class,
                Dump.class,
                HelpCommand.class
            },
            footer = {
                "",
                "Writes into the output directory: " + OutputFiles.REPORT + ", " + OutputFiles.COLLAPSED + ", "
                        + OutputFiles.PROFILE + ", the digest " + OutputFiles.SUMMARY_MD + " and "
                        + OutputFiles.SUMMARY_JSON + " and, last, "
                        + OutputFiles.COMPLETE + "; " + OutputFiles.CLASSIFIED_RECORDS + " is written only for"
                        + " --audit full and " + OutputFiles.MATCHES + " for --audit full or matches; --partial"
                        + " true writes " + OutputFiles.INCOMPLETE_PREFIX + "* files and " + OutputFiles.PARTIAL
                        + " instead.",
                "",
                "Exit status: 0 complete, 2 narrowed or partial output, 64 invalid command line."
            })
    static final class Root implements Callable<Integer> {
        @Spec
        CommandSpec spec;

        @Mixin
        CorrelateOptions options;

        @Override
        public Integer call() throws Exception {
            // No arguments is a request for help, not a failed analysis.
            if (spec.commandLine().getParseResult().originalArgs().isEmpty()) {
                spec.commandLine().usage(spec.commandLine().getOut());
                return OK;
            }
            return correlate(options);
        }
    }

    @Command(
            name = "correlate",
            mixinStandardHelpOptions = true,
            versionProvider = Version.class,
            sortOptions = false,
            description = "Correlates a capture with its JFR into a new output directory (the default command).")
    static final class Correlate implements Callable<Integer> {
        @Mixin
        CorrelateOptions options;

        @Override
        public Integer call() throws Exception {
            return correlate(options);
        }
    }

    static int correlate(CorrelateOptions options) throws Exception {
        var defaults = OfflineCorrelator.Limits.defaults();
        OfflineCorrelator.Limits limits;
        try {
            limits = new OfflineCorrelator.Limits(
                    options.maxRows,
                    defaults.maxLineBytes(),
                    options.maxRetainedBytes == null ? defaults.maxRetainedBytes() : options.maxRetainedBytes,
                    defaults.maxFrames(),
                    options.maxHandlerDelayNs,
                    options.fromNs,
                    options.toNs,
                    options.maxAccountedLoss,
                    options.watermarkRows == null
                            ? OfflineCorrelator.Limits.DEFAULT_WATERMARK_ROWS
                            : options.watermarkRows);
        } catch (IllegalArgumentException invalid) {
            throw options.usage(invalid.getMessage());
        }
        boolean hasJfrRange = options.from != null || options.to != null;
        if (options.partial && (options.partialJfr || hasJfrRange)) {
            throw options.usage("Incomplete-capture mode cannot be combined with JFR selection");
        }
        if (options.estimatePopulation && (options.partialJfr || hasJfrRange)) {
            throw options.usage("Population estimates require the complete unselected JFR");
        }
        if (options.estimatePopulation && options.thinning != null) {
            throw options.usage("Population estimates require the unthinned source");
        }
        String format = options.format != null ? options.format : options.partial ? "diagnostics" : "collapsed";
        if (options.partial) {
            if (!Set.of("diagnostics", "collapsed").contains(format)
                    || options.estimatePopulation
                    || options.given("--audit")
                    || options.given("--thinning")
                    || options.given("--collapsed-reason-frame")
                    || options.given("--profile-output")
                    || options.given("--profile-group-by")
                    || options.given("--max-profile-entries")
                    || options.given("--summary-output")
                    || options.given("--process-details")
                    || options.given("--waiting")
                    || options.given("--waiting-from")
                    || options.given("--app")
                    || options.given("--app-from")
                    || options.given("--hide")
                    || options.given("--hide-from")
                    || options.given("--max-accounted-loss")) {
                throw options.usage("Partial mode supports diagnostics or labelled collapsed output;"
                        + " the other outputs and population estimates require complete analysis");
            }
            var result = OfflineCorrelator.correlatePartial(options.source, options.jfr, limits);
            OffCpuCorrelator.writePartial(result, options.output, format.equals("collapsed"));
            System.out.println("Wrote INCOMPLETE diagnostics to "
                    + options.output
                    + ": "
                    + result.provisionalPairs()
                    + " provisional prefix pairs; coverage is incomplete");
            return NARROWED;
        }
        if (!format.equals("collapsed")) {
            throw options.usage("Invalid output format: " + format + " (only with --partial true)");
        }
        boolean waitingGiven = !options.waiting.isEmpty() || !options.waitingFrom.isEmpty();
        if (waitingGiven && !options.summaryOutput) {
            throw options.usage(
                    "--waiting and --waiting-from shape the digest, which --summary-output false leaves out");
        }
        boolean appGiven = !options.app.isEmpty() || !options.appFrom.isEmpty();
        if ((appGiven || options.hide.given()) && !options.summaryOutput) {
            throw options.usage("--app, --app-from, --hide and --hide-from shape the digest, which --summary-output"
                    + " false leaves out");
        }
        if (options.hide.given() && !appGiven) {
            throw options.usage("--hide and --hide-from shape the digest's application tables; give --app too");
        }
        ProfileAccumulator.Options profileOptions;
        Thinning thinning;
        Digest.Options digest = null;
        try {
            profileOptions = ProfileAccumulator.Options.parse(
                    options.profileGroupBy, Integer.toString(options.maxProfileEntries));
            thinning = options.thinning != null ? Thinning.of(options.thinning, options.thinningSeed) : Thinning.NONE;
            // Resolved before correlating, so that a bad pattern fails at once rather than after the analysis.
            if (options.summaryOutput) {
                Digest.Options digestDefaults = Digest.defaults(
                        waitingGiven
                                ? sourced(options.waiting, "--waiting-from", options.waitingFrom)
                                : sourced(List.of(), "--waiting-from", List.of(Presets.ALL)));
                digest = new Digest.Options(
                        sourced(options.app, "--app-from", options.appFrom),
                        digestDefaults.waiting(),
                        digestDefaults.machinery(),
                        options.hide.patterns(appGiven),
                        digestDefaults.limit(),
                        options.processDetails);
            }
        } catch (IllegalArgumentException invalid) {
            throw options.usage(invalid.getMessage());
        }
        return OffCpuCorrelator.correlateCommand(
                options.source,
                options.jfr,
                options.output,
                limits,
                options.from,
                options.to,
                options.partialJfr,
                options.estimatePopulation,
                options.audit,
                thinning,
                options.onLimit,
                options.collapsedReasonFrame,
                options.profileOutput,
                profileOptions,
                digest,
                options.processDetails);
    }

    @Command(
            name = "stacks",
            mixinStandardHelpOptions = true,
            versionProvider = Version.class,
            sortOptions = false,
            description = {
                "Renders one collapsed slice of a stack profile: by switch-out reason, stack kinds, weights and time"
                        + " part, keeping or dropping whole intervals by their frames and transforming the frames"
                        + " of the ones it keeps.",
                "With --collapsed-input, filters and transforms any collapsed file instead, such as a CPU view the"
                        + " converter wrote."
            },
            footer = {
                "",
                "Per kept entry, in this order: --canonical-names, --hide, --trim-root, --root-at, --leaf-at,"
                        + " --collapse-leaf, then --package-names and --thread-frame as display. Filters see the"
                        + " untransformed stacks, and lines that transform alike merge, so totals never change,"
                        + " except that --root-at-unmatched hide moves unmatched entries to a total of their own."
                        + " Every -from option also takes preset:NAME, or preset:* for every bundled preset meant"
                        + " for it (quote it for the shell); --list-presets prints them with their options."
            })
    static final class Stacks implements Callable<Integer> {
        @Spec
        CommandSpec spec;

        @Mixin
        SliceOptions slice;

        @Option(
                names = "--collapsed-input",
                paramLabel = "FILE",
                description = "Read any collapsed file instead of --profile. Weights keep the file's unit; the"
                        + " converter's Java frames lose their _[j]-style markers and '/' in class names.")
        Path collapsedInput;

        @Option(
                names = "--output",
                paramLabel = "FILE",
                description = "The collapsed file to write; must not exist. Required.")
        Path output;

        @Option(
                names = "--reason-frame",
                paramLabel = "MODE",
                defaultValue = "auto",
                converter = ReasonFrameConverter.class,
                description = "Whether each line starts with its [offcpu: <reason>] frame: auto (when the slice"
                        + " mixes reasons), always or never. Default: ${DEFAULT-VALUE}.")
        StackProfileRenderer.ReasonFrame reasonFrame;

        @Option(
                names = "--summary",
                paramLabel = "FILE",
                description = "Also write a JSON summary of the slice: its totals, patterns, transforms and what"
                        + " the filters removed.")
        Path summary;

        @Option(names = "--list-presets", description = "Print the bundled pattern presets and exit.")
        boolean listPresets;

        @Mixin
        FilterOptions filters;

        @Mixin
        TransformOptions transformOptions;

        @Override
        public Integer call() throws Exception {
            if (listPresets) {
                spec.commandLine().getOut().print(Presets.listing());
                return OK;
            }
            ParseResult parsed = spec.commandLine().getParseResult();
            if ((slice.profile == null) == (collapsedInput == null)) {
                throw new ParameterException(spec.commandLine(), "Give exactly one of --profile and --collapsed-input");
            }
            StackProfileRenderer.Filter filter = filters.filter();
            StackTransforms transforms = transformOptions.transforms();
            if (collapsedInput != null) {
                for (String option :
                        List.of("--reason", "--stack", "--weights", "--time", "--reason-frame", "--thread-frame")) {
                    if (parsed.hasMatchedOption(option)) {
                        throw new ParameterException(
                                spec.commandLine(), option + " needs a stack profile, not --collapsed-input");
                    }
                }
                return collapsed(filter, transforms);
            }
            StackProfile profile = StackProfile.read(slice.profile);
            StackProfileRenderer.Slice rendered = render(profile, filter, transforms);
            try (BufferedWriter writer = OffCpuCorrelator.newFile(output)) {
                StackProfileRenderer.writeCollapsed(rendered, writer);
            }
            AnalysisProto.SliceSummary.Builder json = StackProfileRenderer.summary(
                    rendered,
                    profile,
                    slice.reasons.selected(),
                    slice.stack,
                    slice.weights,
                    slice.time,
                    filter,
                    slice.packageNames);
            boolean hides = transforms.unmatchedRoot() == StackTransforms.UnmatchedRoot.HIDE;
            if (hides) json.setRootAtUnmatchedHidden(StackProfileRenderer.hidden(rendered));
            if (transforms.active() || transforms.threadFrame() != StackTransforms.ThreadFrame.NONE) {
                StackProfileRenderer.Slice before = render(profile, filter, StackTransforms.NONE);
                json.setTransforms(transformsReport(
                        transforms,
                        profile.header().label().length(),
                        before.nanos(),
                        rendered.nanos(),
                        rendered.totalNanos(),
                        rendered.hiddenNanos()));
            }
            writeSummary(json.build());
            System.out.println("Wrote " + rendered.nanos().size() + " collapsed stacks to " + output + ": "
                    + rendered.intervals() + " intervals, " + rendered.totalNanos() + " ns"
                    + (slice.time == StackProfileRenderer.Time.TOTAL ? "" : " of " + slice.time.label() + " time")
                    + (filter.active()
                            ? "; filtered out " + rendered.filteredIntervals() + " intervals, "
                                    + rendered.filteredNanos() + " ns, matching "
                                    + String.join(",", StackProfileRenderer.Filter.scope(profile)) + " stacks"
                            : "")
                    + (hides
                            ? "; hid " + rendered.hiddenIntervals() + " intervals, " + rendered.hiddenNanos()
                                    + " ns without a --root-at match"
                            : ""));
            return OK;
        }

        private StackProfileRenderer.Slice render(
                StackProfile profile, StackProfileRenderer.Filter filter, StackTransforms transforms)
                throws IOException {
            return StackProfileRenderer.render(
                    profile,
                    slice.reasons.selected(),
                    slice.stack,
                    slice.weights,
                    reasonFrame,
                    slice.time,
                    filter,
                    slice.packageNames,
                    transforms);
        }

        private int collapsed(StackProfileRenderer.Filter filter, StackTransforms transforms) throws IOException {
            List<CollapsedStacks.Line> lines = CollapsedStacks.read(collapsedInput);
            CollapsedStacks.Slice rendered = CollapsedStacks.render(lines, filter, transforms, slice.packageNames);
            try (BufferedWriter writer = OffCpuCorrelator.newFile(output)) {
                CollapsedStacks.write(rendered, writer);
            }
            AnalysisProto.CollapsedSliceSummary.Builder json = AnalysisProto.CollapsedSliceSummary.newBuilder()
                    .setPackageNames(slice.packageNames.label())
                    .setInputLines(lines.size())
                    .setLines(rendered.weights().size())
                    .setTotalWeight(rendered.total().toPlainString())
                    .addAllInclude(StackProfileRenderer.patterns(filter.include()))
                    .addAllExclude(StackProfileRenderer.patterns(filter.exclude()))
                    .setFiltered(AnalysisProto.FilteredLines.newBuilder()
                            .setInputLines(rendered.filteredLines())
                            .setTotalWeight(rendered.filteredWeight().toPlainString()));
            boolean hides = transforms.unmatchedRoot() == StackTransforms.UnmatchedRoot.HIDE;
            if (hides) {
                json.setRootAtUnmatchedHidden(AnalysisProto.FilteredLines.newBuilder()
                        .setInputLines(rendered.hiddenLines())
                        .setTotalWeight(rendered.hiddenWeight().toPlainString()));
            }
            if (transforms.active()) {
                CollapsedStacks.Slice before =
                        CollapsedStacks.render(lines, filter, StackTransforms.NONE, slice.packageNames);
                json.setTransforms(transformsReport(
                        transforms,
                        0,
                        before.weights(),
                        rendered.weights(),
                        rendered.total(),
                        rendered.hiddenWeight()));
            }
            writeSummary(json.build());
            System.out.println("Wrote " + rendered.weights().size() + " collapsed stacks to " + output + ": weight "
                    + rendered.total().toPlainString()
                    + (filter.active()
                            ? "; filtered out " + rendered.filteredLines() + " input lines, weight "
                                    + rendered.filteredWeight().toPlainString()
                            : "")
                    + (hides
                            ? "; hid " + rendered.hiddenLines() + " input lines, weight "
                                    + rendered.hiddenWeight().toPlainString() + ", without a --root-at match"
                            : ""));
            return OK;
        }

        private void writeSummary(Message json) throws IOException {
            if (summary == null) return;
            OffCpuCorrelator.writePretty(summary, json);
        }
    }

    /**
     * The transforms in effect with what they did: lines and weight-averaged depth before and after, and for
     * {@code --root-at} the weight and share of {@link StackTransforms#NO_APPLICATION_FRAME}, or with {@code
     * --root-at-unmatched hide} of the {@code hidden} weight, a share of the total before hiding. {@code prefix} is
     * the length of the label every line starts with, which is not a frame.
     */
    static AnalysisProto.Transforms transformsReport(
            StackTransforms transforms,
            int prefix,
            Map<String, ? extends Number> before,
            Map<String, ? extends Number> after,
            Number total,
            Number hidden) {
        AnalysisProto.Transforms.Builder report = transforms
                .report()
                .setLinesBefore(before.size())
                .setLinesAfter(after.size())
                .setFramesBefore(meanDepth(before, prefix).toPlainString())
                .setFramesAfter(meanDepth(after, prefix).toPlainString());
        if (!transforms.rootAt().isEmpty()) {
            java.math.BigDecimal hiddenWeight = new java.math.BigDecimal(hidden.toString());
            java.math.BigDecimal bucket = hiddenWeight;
            for (var line : after.entrySet()) {
                String frames = line.getKey().substring(prefix);
                if (frames.equals(StackTransforms.NO_APPLICATION_FRAME)
                        || frames.endsWith(";" + StackTransforms.NO_APPLICATION_FRAME)) {
                    bucket = bucket.add(new java.math.BigDecimal(line.getValue().toString()));
                }
            }
            java.math.BigDecimal all = new java.math.BigDecimal(total.toString()).add(hiddenWeight);
            report.setNoApplicationFrame(AnalysisProto.NoApplicationFrame.newBuilder()
                    .setWeight(bucket.toPlainString())
                    .setShare((all.signum() == 0
                                    ? java.math.BigDecimal.ZERO
                                    : bucket.divide(all, 6, java.math.RoundingMode.HALF_EVEN))
                            .toPlainString()));
        }
        return report.build();
    }

    /** The weight-averaged number of frames per line, to one decimal place. */
    static java.math.BigDecimal meanDepth(Map<String, ? extends Number> lines, int prefix) {
        java.math.BigDecimal weight = java.math.BigDecimal.ZERO;
        java.math.BigDecimal frames = java.math.BigDecimal.ZERO;
        for (var line : lines.entrySet()) {
            java.math.BigDecimal value =
                    new java.math.BigDecimal(line.getValue().toString());
            String text = line.getKey().substring(prefix);
            long depth = text.isEmpty() ? 0 : text.chars().filter(c -> c == ';').count() + 1;
            weight = weight.add(value);
            frames = frames.add(value.multiply(java.math.BigDecimal.valueOf(depth)));
        }
        return weight.signum() == 0
                ? java.math.BigDecimal.ZERO
                : frames.divide(weight, 1, java.math.RoundingMode.HALF_EVEN);
    }

    /** The options {@code top} and {@code summarize} share: what is application code, waiting and wait machinery. */
    static final class RankingOptions {
        @Option(
                names = "--app",
                paramLabel = "REGEX",
                description = "A frame of your application; the deepest one is a stack's boundary. Repeatable.")
        List<String> app = new ArrayList<>();

        @Option(
                names = "--app-from",
                paramLabel = "FILE",
                description = "Read --app patterns from a file or preset:NAME; repeatable.")
        List<String> appFrom = new ArrayList<>();

        @Option(
                names = "--waiting",
                paramLabel = "REGEX",
                description = "An interval with a frame matching this is waiting, a wait for work: top lists it in its"
                        + " own table, and the digest leaves it out of its tables. Repeatable.")
        List<String> waiting = new ArrayList<>();

        @Option(
                names = "--waiting-from",
                paramLabel = "FILE",
                description =
                        "Read --waiting patterns from a file or preset:NAME, such as preset:jvm-waiting; repeatable."
                                + " Default for summarize, when no waiting pattern is given: preset:*, the bundled"
                                + " waiting presets.")
        List<String> waitingFrom = new ArrayList<>();

        @Option(
                names = "--machinery-from",
                paramLabel = "FILE",
                description = "The wait machinery below a boundary whose entry frame is the blocker, from a file or"
                        + " preset:NAME; repeatable. Default: preset:*, the bundled wait machinery presets"
                        + " (preset:jvm-wait-machinery).")
        List<String> machineryFrom = new ArrayList<>();

        @Option(
                names = "--limit",
                paramLabel = "N",
                defaultValue = "20",
                description = "Rows per table. Default: ${DEFAULT-VALUE}.")
        int limit;

        List<StackTransforms.Sourced> app() throws IOException {
            return sourced(app, "--app-from", appFrom);
        }

        List<StackTransforms.Sourced> waiting(boolean defaultPreset) throws IOException {
            if (defaultPreset && waiting.isEmpty() && waitingFrom.isEmpty()) {
                return sourced(List.of(), "--waiting-from", List.of(Presets.ALL));
            }
            return sourced(waiting, "--waiting-from", waitingFrom);
        }

        List<StackTransforms.Sourced> machinery() throws IOException {
            return sourced(
                    List.of(), "--machinery-from", machineryFrom.isEmpty() ? List.of(Presets.ALL) : machineryFrom);
        }
    }

    @Command(
            name = "top",
            mixinStandardHelpOptions = true,
            versionProvider = Version.class,
            sortOptions = false,
            description = {
                "Ranks where off-CPU time went. Each selected interval is waiting when a frame of any of its stacks"
                        + " matches --waiting, and blocked otherwise; blocked time is attributed by --by to a row, and waiting"
                        + " time is listed in its own table.",
                "--by boundary (the default) keys a row by the deepest --app frame and the blocker below it, and"
                        + " breaks blocked time without an application frame down by thread pool."
            },
            footer = {
                "",
                "With --baseline, compares the application boundaries of two profiles, per unit of work when"
                        + " --units and --baseline-units are given. Selection, filter and transform options are"
                        + " those of stacks."
            })
    static final class TopCommand implements Callable<Integer> {
        @Spec
        CommandSpec spec;

        @Mixin
        SliceOptions slice;

        @Option(
                names = "--collapsed-input",
                paramLabel = "FILE",
                description = "Rank any collapsed file instead of --profile; values keep the file's unit.")
        Path collapsedInput;

        @Option(
                names = "--by",
                paramLabel = "KEY",
                defaultValue = "boundary",
                converter = ByConverter.class,
                description = "What a row is: boundary (deepest --app frame and its blocker), root (the first frame"
                        + " after the transforms), app-method (every distinct --app"
                        + " frame after the transforms, inclusive, call chains grouped, with self time), self (the"
                        + " leaf, after --collapse-leaf), method, class or package (every distinct one in the stack,"
                        + " inclusive), or pool (the thread name with digit runs as #). Default: ${DEFAULT-VALUE}.")
        Top.By by;

        @Mixin
        RankingOptions ranking;

        @Option(
                names = "--format",
                paramLabel = "FORMAT",
                defaultValue = "md",
                converter = TopFormatConverter.class,
                description = "md, json or csv. Default: ${DEFAULT-VALUE}.")
        String format;

        @Option(names = "--output", paramLabel = "FILE", description = "Write to this file instead of stdout.")
        Path output;

        @Option(
                names = "--baseline",
                paramLabel = "FILE",
                description = "Compare with this profile, keyed by application boundary.")
        Path baseline;

        @Option(
                names = "--units",
                paramLabel = "N",
                description = "This run's units of work, such as millions of messages, for time per unit.")
        BigDecimal units;

        @Option(names = "--baseline-units", paramLabel = "N", description = "The baseline's units of work.")
        BigDecimal baselineUnits;

        @Mixin
        FilterOptions filters;

        @Mixin
        TransformOptions transformOptions;

        @Override
        public Integer call() throws Exception {
            ParseResult parsed = spec.commandLine().getParseResult();
            if ((slice.profile == null) == (collapsedInput == null)) {
                throw new ParameterException(spec.commandLine(), "Give exactly one of --profile and --collapsed-input");
            }
            for (String option : List.of("--stack", "--time", "--thread-frame")) {
                if (parsed.hasMatchedOption(option)) {
                    throw new ParameterException(spec.commandLine(), option + " does not apply to top");
                }
            }
            if (collapsedInput != null) {
                for (String option : List.of("--reason", "--weights", "--baseline", "--by")) {
                    boolean pool = option.equals("--by") && by == Top.By.POOL;
                    if (option.equals("--by") ? pool : parsed.hasMatchedOption(option)) {
                        throw new ParameterException(
                                spec.commandLine(),
                                (pool ? "--by pool" : option) + " needs a stack profile, not --collapsed-input");
                    }
                }
            }
            if ((units == null) != (baselineUnits == null) || units != null && baseline == null) {
                throw new ParameterException(
                        spec.commandLine(), "--units and --baseline-units go together, with --baseline");
            }
            if (baseline != null && by != Top.By.BOUNDARY) {
                throw new ParameterException(spec.commandLine(), "--baseline compares boundaries; use --by boundary");
            }
            if (by.application() && ranking.app.isEmpty() && ranking.appFrom.isEmpty()) {
                throw new ParameterException(
                        spec.commandLine(),
                        "--by " + by.label() + " needs --app or --app-from; or rank with --by self");
            }
            Top.Options options = new Top.Options(
                    by,
                    ranking.app(),
                    ranking.waiting(false),
                    ranking.machinery(),
                    ranking.limit,
                    transformOptions.transforms(),
                    slice.packageNames,
                    slice.weights,
                    slice.reasons.selected(),
                    filters.filter());
            String command = Top.shell(reproduce(parsed));
            AnalysisProto.TopResult result;
            if (collapsedInput != null) {
                result = Top.tables(Top.fromCollapsed(collapsedInput, options), options, command);
            } else {
                Top.Input input = Top.fromProfile(slice.profile, StackProfile.read(slice.profile), options);
                if (baseline == null) {
                    result = Top.tables(input, options, command);
                } else {
                    Top.Input before = Top.fromProfile(baseline, StackProfile.read(baseline), options);
                    boolean exhaustive = input.exhaustiveSampling() && before.exhaustiveSampling();
                    if (!exhaustive
                            && slice.weights == StackProfileRenderer.Weights.OBSERVED
                            && input.estimateAvailable()
                            && before.estimateAvailable()) {
                        throw new ParameterException(
                                spec.commandLine(),
                                "Observed time is length-biased under proportional or uniform sampling, and both"
                                        + " runs have population estimates: compare with --weights estimated");
                    }
                    result = Top.compare(input, before, options, units, baselineUnits, command);
                }
            }
            String text =
                    switch (format) {
                        case "json" -> ProtoJson.pretty(result) + "\n";
                        case "csv" -> Top.csv(result);
                        default -> Top.markdown(result);
                    };
            if (output == null) {
                spec.commandLine().getOut().print(text);
            } else {
                try (BufferedWriter writer = OffCpuCorrelator.newFile(output)) {
                    writer.write(text);
                }
            }
            for (String warning : result.getWarningsList()) {
                spec.commandLine().getErr().println("Warning: " + warning);
            }
            return OK;
        }

        /** The command as given, minus where it wrote, so a reader can run it again. */
        private static List<String> reproduce(ParseResult parsed) {
            List<String> words = new ArrayList<>(List.of(NAME, "top"));
            List<String> args = parsed.originalArgs();
            for (int index = args.indexOf("top") + 1; index < args.size(); index++) {
                String word = args.get(index);
                if (word.equals("--output")) {
                    index++;
                } else if (!word.startsWith("--output=")) {
                    words.add(word);
                }
            }
            return words;
        }
    }

    @Command(
            name = "summarize",
            mixinStandardHelpOptions = true,
            versionProvider = Version.class,
            sortOptions = false,
            description = {
                "Writes the analysis digest, " + OutputFiles.SUMMARY_MD + " and " + OutputFiles.SUMMARY_JSON
                        + ": when and on what the capture was recorded, the blocked time ranked with the waits for"
                        + " work left out, where the time went, the capture's coverage and losses, and the command"
                        + " that reproduces each table.",
                "With --app every table is rooted at the application, as its flame graph with --hide-from and"
                        + " --root-at-unmatched hide is: the application method that waited, where threads entered"
                        + " the application and application methods across stacks, with blocked time without an"
                        + " application frame counted by pool.",
                "Correlation writes it by default, with its own --waiting and --app patterns; this command rewrites it"
                        + " with other patterns."
            })
    static final class Summarize implements Callable<Integer> {
        @Option(names = "--profile", paramLabel = "FILE", description = "The stack profile. Required.")
        Path profile;

        @Option(
                names = "--report",
                paramLabel = "FILE",
                description = "A correlation report (" + OutputFiles.REPORT + ") for the capture section. Default:"
                        + " the report the profile carries; a merged profile has none.")
        Path report;

        @Option(
                names = "--output-dir",
                paramLabel = "DIR",
                description =
                        "Where to write the digest; its files must not exist. Default: the current" + " directory.")
        Path outputDirectory;

        @Mixin
        RankingOptions ranking;

        @Mixin
        DigestHideOptions hide;

        @Option(
                names = "--process-details",
                arity = "1",
                paramLabel = "true|false",
                defaultValue = "false",
                description = "Show the target's command line, system properties and environment variables, when"
                        + " the report holds them (correlate --process-details true). They can hold secrets."
                        + " Default: ${DEFAULT-VALUE}.")
        boolean processDetails;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            boolean appGiven = !ranking.app.isEmpty() || !ranking.appFrom.isEmpty();
            if (hide.given() && !appGiven) {
                throw new ParameterException(
                        spec.commandLine(),
                        "--hide and --hide-from shape the digest's application tables; give --app too");
            }
            StackProfile read = StackProfile.read(profile);
            // The profile carries the report it was produced with; a report file is parsed strictly, so a file
            // that is not a report is refused rather than summarised as empty.
            ReportProto.Report captured = report == null
                    ? read.header().report()
                    : ProtoJson.parse(java.nio.file.Files.readString(report), ReportProto.Report.newBuilder())
                            .build();
            Digest.Options options = new Digest.Options(
                    ranking.app(),
                    ranking.waiting(true),
                    ranking.machinery(),
                    hide.patterns(appGiven),
                    ranking.limit,
                    processDetails);
            AnalysisProto.Digest digest = Digest.of(read, profile.toString(), captured, options);
            Path directory = outputDirectory == null ? Path.of("") : outputDirectory;
            java.nio.file.Files.createDirectories(directory.toAbsolutePath());
            Path json = directory.resolve(OutputFiles.SUMMARY_JSON);
            Path markdown = directory.resolve(OutputFiles.SUMMARY_MD);
            Digest.write(digest, json, markdown);
            System.out.println("Wrote " + markdown + " and " + json);
            return OK;
        }
    }

    @Command(
            name = "merge",
            mixinStandardHelpOptions = true,
            versionProvider = Version.class,
            sortOptions = false,
            description = "Sums the counters of several stack profiles into one. Thinned profiles cannot be merged.")
    static final class Merge implements Callable<Integer> {
        @Spec
        CommandSpec spec;

        @Option(
                names = "--profiles",
                paramLabel = "FILE",
                split = ",",
                description = "The profiles to merge, comma-separated. Required.")
        List<String> profiles;

        @Option(names = "--output", paramLabel = "FILE", description = "The merged profile to write. Required.")
        Path output;

        @Override
        public Integer call() throws Exception {
            List<StackProfile> read = new ArrayList<>();
            for (String path : profiles) {
                if (path.isEmpty())
                    throw new ParameterException(spec.commandLine(), "Empty profile path in --profiles");
            }
            for (String path : profiles) read.add(StackProfile.read(Path.of(path)));
            StackProfile merged = StackProfile.merge(read);
            merged.write(output);
            System.out.println("Merged " + read.size() + " profiles into " + output + ": "
                    + merged.entries().size() + " entries");
            return OK;
        }
    }

    @Command(
            name = "export",
            mixinStandardHelpOptions = true,
            versionProvider = Version.class,
            sortOptions = false,
            description = {
                "Writes one row per stack-profile entry, stacks expanded, for tools such as DuckDB.",
                "JSON Lines rows carry the stacks as arrays too, 64-bit counters as decimal strings, and every row"
                        + " names its run and whether its estimated columns may be used."
            })
    static final class Export implements Callable<Integer> {
        @Option(names = "--profile", paramLabel = "FILE", description = "The stack profile to export. Required.")
        Path profile;

        @Option(
                names = "--format",
                paramLabel = "FORMAT",
                defaultValue = "csv",
                converter = ExportFormatConverter.class,
                description = "csv or jsonl. Default: ${DEFAULT-VALUE}.")
        String format;

        @Option(names = "--output", paramLabel = "FILE", description = "The file to write. Required.")
        Path output;

        @Option(
                names = "--run-label",
                paramLabel = "TEXT",
                description = "The run column of every row, for loading several runs into one table. Default: the"
                        + " profile's label, else its first source's session id.")
        String runLabel;

        @Option(
                names = "--run-metadata",
                paramLabel = "FILE",
                description = "Also write one JSON object describing the profile: its run, sources, sampling,"
                        + " dimensions, estimate validity and totals.")
        Path runMetadata;

        @Override
        public Integer call() throws Exception {
            StackProfile read = StackProfile.read(profile);
            String run = runLabel != null ? runLabel : StackProfileRenderer.defaultRun(read);
            try (BufferedWriter writer = OffCpuCorrelator.newFile(output)) {
                StackProfileRenderer.export(read, new StackProfileRenderer.Export(format, run), writer);
            }
            if (runMetadata != null) {
                OffCpuCorrelator.writePretty(runMetadata, StackProfileRenderer.runMetadata(read, run));
            }
            return OK;
        }
    }

    @Command(
            name = "dump",
            mixinStandardHelpOptions = true,
            versionProvider = Version.class,
            description =
                    "Prints the capture stream as JSON Lines, one record per line as the capture schema defines it.")
    static final class Dump implements Callable<Integer> {
        @Option(names = "--source", paramLabel = "FILE", description = "The capture stream. Required.")
        Path source;

        @Override
        public Integer call() throws Exception {
            OffCpuCorrelator.dump(source);
            return OK;
        }
    }

    // ---- version -----------------------------------------------------------------------------------

    /** The build's version and the async-profiler fork commit it was built with, from a generated resource. */
    static final class Version implements IVersionProvider {
        static final String RESOURCE = "/io/github/jonoffcpu/correlator/version.properties";

        @Override
        public String[] getVersion() throws IOException {
            Properties properties = new Properties();
            try (InputStream input = Cli.class.getResourceAsStream(RESOURCE)) {
                if (input != null) properties.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            }
            return new String[] {
                "jonoffcpu-correlator " + properties.getProperty("version", "unknown"),
                "async-profiler fork " + properties.getProperty("asyncProfilerCommit", "unknown")
            };
        }
    }

    // ---- value types and converters ----------------------------------------------------------------

    /** A {@code --reason} selection; {@link #selected()} is null for every reason. */
    record Reasons(Set<OffCpuReason> selected) {}

    /** The one value among {@code values} whose label is {@code text}; the error lists the valid labels. */
    static <T> T choice(String text, T[] values, Function<T, String> label) {
        for (T value : values) {
            if (label.apply(value).equals(text)) return value;
        }
        throw new TypeConversionException("expected one of "
                + String.join(", ", Arrays.stream(values).map(label).toList())
                + " but was '" + text + "'");
    }

    static final class ReasonsConverter implements ITypeConverter<Reasons> {
        @Override
        public Reasons convert(String text) {
            if (text.equals("all")) return new Reasons(null);
            Set<OffCpuReason> reasons = EnumSet.noneOf(OffCpuReason.class);
            for (String label : text.split(",", -1)) {
                OffCpuReason reason =
                        choice(label, OffCpuReason.CLASSIFIED.toArray(OffCpuReason[]::new), OffCpuReason::label);
                if (!reasons.add(reason)) throw new TypeConversionException("duplicate reason '" + label + "'");
            }
            return new Reasons(reasons);
        }
    }

    static final class StackKindsConverter implements ITypeConverter<StackProfileRenderer.StackKinds> {
        @Override
        public StackProfileRenderer.StackKinds convert(String text) {
            return choice(text, StackProfileRenderer.StackKinds.values(), kinds -> kinds.text);
        }
    }

    static final class WeightsConverter implements ITypeConverter<StackProfileRenderer.Weights> {
        @Override
        public StackProfileRenderer.Weights convert(String text) {
            return choice(text, StackProfileRenderer.Weights.values(), Cli::lower);
        }
    }

    static final class TimeConverter implements ITypeConverter<StackProfileRenderer.Time> {
        @Override
        public StackProfileRenderer.Time convert(String text) {
            return choice(text, StackProfileRenderer.Time.values(), StackProfileRenderer.Time::label);
        }
    }

    static final class PackageNamesConverter implements ITypeConverter<StackProfileRenderer.PackageNames> {
        @Override
        public StackProfileRenderer.PackageNames convert(String text) {
            return choice(text, StackProfileRenderer.PackageNames.values(), StackProfileRenderer.PackageNames::label);
        }
    }

    static final class ReasonFrameConverter implements ITypeConverter<StackProfileRenderer.ReasonFrame> {
        @Override
        public StackProfileRenderer.ReasonFrame convert(String text) {
            return choice(text, StackProfileRenderer.ReasonFrame.values(), Cli::lower);
        }
    }

    static final class AuditConverter implements ITypeConverter<AuditLevel> {
        @Override
        public AuditLevel convert(String text) {
            return choice(text, AuditLevel.values(), AuditLevel::text);
        }
    }

    static final class LimitPolicyConverter implements ITypeConverter<Degradation.Policy> {
        @Override
        public Degradation.Policy convert(String text) {
            return choice(text, Degradation.Policy.values(), Degradation.Policy::text);
        }
    }

    static final class UnmatchedConverter implements ITypeConverter<StackTransforms.UnmatchedRoot> {
        @Override
        public StackTransforms.UnmatchedRoot convert(String text) {
            return choice(text, StackTransforms.UnmatchedRoot.values(), StackTransforms.UnmatchedRoot::label);
        }
    }

    static final class LeafLabelConverter implements ITypeConverter<String> {
        @Override
        public String convert(String text) {
            return choice(text, new String[] {"frame", "category"}, Function.identity());
        }
    }

    static final class ThreadFrameConverter implements ITypeConverter<StackTransforms.ThreadFrame> {
        @Override
        public StackTransforms.ThreadFrame convert(String text) {
            return choice(text, StackTransforms.ThreadFrame.values(), StackTransforms.ThreadFrame::label);
        }
    }

    static final class ByConverter implements ITypeConverter<Top.By> {
        @Override
        public Top.By convert(String text) {
            return choice(text, Top.By.values(), Top.By::label);
        }
    }

    static final class TopFormatConverter implements ITypeConverter<String> {
        @Override
        public String convert(String text) {
            return choice(text, new String[] {"md", "json", "csv"}, Function.identity());
        }
    }

    static final class FormatConverter implements ITypeConverter<String> {
        @Override
        public String convert(String text) {
            return choice(text, new String[] {"collapsed", "diagnostics"}, Function.identity());
        }
    }

    static final class ExportFormatConverter implements ITypeConverter<String> {
        @Override
        public String convert(String text) {
            return choice(text, new String[] {"csv", "jsonl"}, Function.identity());
        }
    }

    /** A non-negative decimal fraction such as {@code 0.01}; the range is checked by the limits it goes into. */
    static final class FractionConverter implements ITypeConverter<BigDecimal> {
        @Override
        public BigDecimal convert(String text) {
            if (!text.matches("[0-9]{1,9}(\\.[0-9]{1,18})?")) {
                throw new TypeConversionException("expected a decimal fraction such as 0.01 but was '" + text + "'");
            }
            return new BigDecimal(text);
        }
    }

    /** An unsigned decimal of at most 20 digits, without a sign or leading zeros. */
    static final class NanosConverter implements ITypeConverter<BigInteger> {
        @Override
        public BigInteger convert(String text) {
            if (!text.matches("0|[1-9][0-9]{0,19}")) {
                throw new TypeConversionException("expected nanoseconds as an unsigned decimal but was '" + text + "'");
            }
            return new BigInteger(text);
        }
    }

    static String lower(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }
}
