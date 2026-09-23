// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
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
        // The pre-subcommand spelling of dump, kept as a deprecated alias.
        if (args.length > 0 && args[0].equals("--dump")) {
            args = args.clone();
            args[0] = "dump";
        }
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
            List.of("--profile", "--output"),
            "merge",
            List.of("--profiles", "--output"),
            "export",
            List.of("--profile", "--output"),
            "dump",
            List.of("--source"));

    private static void requireOptions(CommandLine command) {
        // The top-level command with no arguments at all asks for help instead.
        if (command.getParseResult().originalArgs().isEmpty()) return;
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
                description = "Which derived outputs to write: both, collapsed or jfr; with --partial true,"
                        + " diagnostics or collapsed. Default: both, or diagnostics with --partial true.")
        String format;

        @Option(
                names = "--quantum-ns",
                paramLabel = "N",
                defaultValue = "1000000",
                description = "Off-CPU time one synthetic JFR event represents. Default: ${DEFAULT-VALUE}.")
        long quantumNs;

        @Option(
                names = "--max-synthetic-events",
                paramLabel = "N",
                defaultValue = "10000000",
                description = "Most synthetic JFR events; the quantum is raised to fit. Default: ${DEFAULT-VALUE}.")
        long maxSyntheticEvents;

        @Option(
                names = "--estimate-population",
                arity = "1",
                paramLabel = "true|false",
                defaultValue = "false",
                description = "Report the inverse-probability population estimate. Requires the complete,"
                        + " unselected JFR and an unthinned source. Default: ${DEFAULT-VALUE}.")
        boolean estimatePopulation;

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
                description = "What to do when the retained-bytes budget is reached: degrade (coarsen, drop audit,"
                        + " thin, narrow, reporting each step), fail, or truncate (narrow the window directly)."
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

    /** Which profile a slice is rendered from and how its lines are made; shared by the stack-profile commands. */
    static final class SliceOptions {
        @Option(
                names = "--profile",
                paramLabel = "FILE",
                description = "The stack profile, such as " + OutputFiles.PROFILE + ". Required.")
        Path profile;

        @Option(
                names = "--reason",
                paramLabel = "REASONS",
                defaultValue = "all",
                converter = ReasonsConverter.class,
                description = "all, or a comma-separated list of blocked, runnable, preempted and unspecified."
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
                description = "Read --include patterns from a file, one per line; repeatable.")
        List<String> includeFrom = new ArrayList<>();

        @Option(
                names = "--exclude-from",
                paramLabel = "FILE",
                description = "Read --exclude patterns from a file, one per line; repeatable.")
        List<String> excludeFrom = new ArrayList<>();

        StackProfileRenderer.Filter filter() throws IOException {
            return StackProfileRenderer.Filter.of(
                    patterns(include, "--include-from", includeFrom), patterns(exclude, "--exclude-from", excludeFrom));
        }
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
            subcommands = {Correlate.class, Stacks.class, Merge.class, Export.class, Dump.class, HelpCommand.class},
            footer = {
                "",
                "Writes into the output directory: " + OutputFiles.REPORT + ", " + OutputFiles.COLLAPSED + ", "
                        + OutputFiles.PROFILE + ", " + OutputFiles.SYNTHETIC_JFR + " and, last, "
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
        var limits = new OfflineCorrelator.Limits(
                options.maxRows,
                defaults.maxLineBytes(),
                options.maxRetainedBytes == null ? defaults.maxRetainedBytes() : options.maxRetainedBytes,
                defaults.maxFrames(),
                options.maxHandlerDelayNs,
                options.fromNs,
                options.toNs);
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
        String format = options.format != null ? options.format : options.partial ? "diagnostics" : "both";
        if (options.partial) {
            if (!Set.of("diagnostics", "collapsed").contains(format)
                    || options.estimatePopulation
                    || options.given("--quantum-ns")
                    || options.given("--max-synthetic-events")
                    || options.given("--audit")
                    || options.given("--thinning")
                    || options.given("--collapsed-reason-frame")
                    || options.given("--profile-output")
                    || options.given("--profile-group-by")
                    || options.given("--max-profile-entries")) {
                throw options.usage("Partial mode supports diagnostics or labelled collapsed output;"
                        + " synthetic JFR and population estimates require complete analysis");
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
        if (!Set.of("both", "collapsed", "jfr").contains(format)) {
            throw options.usage("Invalid output format: " + format + " (only with --partial true)");
        }
        ProfileAccumulator.Options profileOptions;
        Thinning thinning;
        try {
            profileOptions = ProfileAccumulator.Options.parse(
                    options.profileGroupBy, Integer.toString(options.maxProfileEntries));
            thinning = options.thinning != null ? Thinning.of(options.thinning, options.thinningSeed) : Thinning.NONE;
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
                format,
                new CompatibilityJfrWriter.Options(options.quantumNs, options.maxSyntheticEvents),
                options.estimatePopulation,
                options.audit,
                thinning,
                options.onLimit,
                options.collapsedReasonFrame,
                options.profileOutput,
                profileOptions);
    }

    @Command(
            name = "stacks",
            mixinStandardHelpOptions = true,
            versionProvider = Version.class,
            sortOptions = false,
            description = "Renders one collapsed slice of a stack profile: by switch-out reason, stack kinds,"
                    + " weights and time part, keeping or dropping whole intervals by their frames.")
    static final class Stacks implements Callable<Integer> {
        @Mixin
        SliceOptions slice;

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
                description = "Also write a JSON summary of the slice: its totals, patterns and what the filters"
                        + " removed.")
        Path summary;

        @Mixin
        FilterOptions filters;

        @Override
        public Integer call() throws Exception {
            StackProfile profile = StackProfile.read(slice.profile);
            StackProfileRenderer.Filter filter = filters.filter();
            StackProfileRenderer.Slice rendered = StackProfileRenderer.render(
                    profile,
                    slice.reasons.selected(),
                    slice.stack,
                    slice.weights,
                    reasonFrame,
                    slice.time,
                    filter,
                    slice.packageNames);
            try (BufferedWriter writer = OffCpuCorrelator.newFile(output)) {
                StackProfileRenderer.writeCollapsed(rendered, writer);
            }
            JsonObject json = StackProfileRenderer.summary(
                    rendered,
                    profile,
                    slice.reasons.selected(),
                    slice.stack,
                    slice.weights,
                    slice.time,
                    filter,
                    slice.packageNames);
            if (summary != null) {
                try (BufferedWriter writer = OffCpuCorrelator.newFile(summary)) {
                    new GsonBuilder()
                            .serializeNulls()
                            .setPrettyPrinting()
                            .create()
                            .toJson(json, writer);
                    writer.newLine();
                }
            }
            System.out.println("Wrote " + rendered.nanos().size() + " collapsed stacks to " + output + ": "
                    + rendered.intervals() + " intervals, " + rendered.totalNanos() + " ns"
                    + (slice.time == StackProfileRenderer.Time.TOTAL ? "" : " of " + slice.time.label() + " time")
                    + (filter.active()
                            ? "; filtered out " + rendered.filteredIntervals() + " intervals, "
                                    + rendered.filteredNanos() + " ns, matching "
                                    + String.join(",", StackProfileRenderer.Filter.scope(profile)) + " stacks"
                            : ""));
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
            description = "Writes one row per stack-profile entry, stacks expanded, for tools such as DuckDB.")
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

        @Override
        public Integer call() throws Exception {
            StackProfile read = StackProfile.read(profile);
            try (BufferedWriter writer = OffCpuCorrelator.newFile(output)) {
                StackProfileRenderer.export(read, format, writer);
            }
            return OK;
        }
    }

    @Command(
            name = "dump",
            mixinStandardHelpOptions = true,
            versionProvider = Version.class,
            description = {
                "Prints the capture stream as NDJSON, each observation's interned stacks expanded.",
                "`--dump --source FILE` is a deprecated alias."
            })
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
        static final String RESOURCE = "/io/github/lhotari/jonoffcpu/offline/version.properties";

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
                OffCpuReason reason = choice(label, OffCpuReason.values(), OffCpuReason::label);
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

    static final class FormatConverter implements ITypeConverter<String> {
        @Override
        public String convert(String text) {
            return choice(text, new String[] {"both", "collapsed", "jfr", "diagnostics"}, Function.identity());
        }
    }

    static final class ExportFormatConverter implements ITypeConverter<String> {
        @Override
        public String convert(String text) {
            return choice(text, new String[] {"csv", "jsonl"}, Function.identity());
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
