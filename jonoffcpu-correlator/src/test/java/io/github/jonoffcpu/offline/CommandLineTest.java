// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.offline;

import static io.github.jonoffcpu.offline.CommandLineFixture.invoke;
import static io.github.jonoffcpu.offline.CommandLineFixture.usageError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.jonoffcpu.offline.CommandLineFixture.Invocation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * The command line's contract: help per command matches its checked-in snapshot, usage errors return 64 with the
 * message and usage and no stack trace, {@code run} never exits, and the README's option names and defaults are the
 * parser's. Run with {@code -Djonoffcpu.updateHelp=DIR} to rewrite the snapshots into {@code DIR}.
 */
class CommandLineTest {
    /** Every command with its own help, the top-level one as the empty name. */
    static final List<String> COMMANDS =
            List.of("", "correlate", "stacks", "top", "summarize", "merge", "export", "dump");

    private static String[] words(String command, String... rest) {
        List<String> words = new ArrayList<>();
        if (!command.isEmpty()) words.add(command);
        words.addAll(List.of(rest));
        return words.toArray(String[]::new);
    }

    /** One test per command: its help matches the checked-in snapshot, from {@code --help} and {@code help}. */
    @ParameterizedTest(name = "help ''{0}''")
    @FieldSource("COMMANDS")
    void helpSnapshots(String command) throws Exception {
        String update = System.getProperty("jonoffcpu.updateHelp");
        Invocation flag = invoke(words(command, "--help"));
        Invocation helpCommand = invoke(command.isEmpty() ? new String[] {"help"} : new String[] {"help", command});
        assertThat(flag.code()).as("--help must return 0 for '%s'", command).isZero();
        assertThat(helpCommand.code())
                .as("help must return 0 for '%s'", command)
                .isZero();
        assertThat(flag.out()).as("help %s and --help must agree", command).isEqualTo(helpCommand.out());
        assertThat(flag.err()).as("Help goes to stdout").isEmpty();
        String name = (command.isEmpty() ? "jonoffcpu-correlator" : command) + ".txt";
        if (update != null) {
            Files.writeString(Files.createDirectories(Path.of(update)).resolve(name), flag.out());
            return;
        }
        String expected;
        try (InputStream input = CommandLineTest.class.getResourceAsStream("/help/" + name)) {
            assertThat(input).as("Missing help snapshot %s", name).isNotNull();
            expected = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(flag.out())
                .as("Help for '%s' differs from src/test/resources/help/%s", command, name)
                .isEqualTo(expected);
    }

    @Test
    void noArgumentsAndVersion() throws Exception {
        Invocation none = invoke();
        assertThat(none.code()).as("No arguments must print the help").isZero();
        assertThat(none.out())
                .as("No arguments must print the help")
                .isEqualTo(invoke("--help").out());
        Invocation version = invoke("--version");
        assertThat(version.code()).isZero();
        assertThat(version.out())
                .as("--version must name the build and the async-profiler fork")
                .startsWith("jonoffcpu-correlator ")
                .contains("async-profiler fork ");
        assertThat(invoke("stacks", "-V").out())
                .as("Every command has --version")
                .isEqualTo(version.out());
    }

    /** A typo is a usage error that suggests the option and shows the subcommand's usage. */
    @Test
    void usageErrorSuggestsOption() throws Exception {
        Invocation typo = usageError("Unknown options: '--stakc'", "stacks", "--stakc", "java");
        assertThat(typo.err())
                .as("A typo must suggest the option")
                .contains("--stack")
                .as("The stacks usage")
                .contains("Usage: java -jar jonoffcpu-correlator.jar stacks");
    }

    /** Each case: the message the usage error must carry, then the command line. */
    private static final List<List<String>> USAGE_ERRORS = List.of(
            List.of(
                    "expected one of full, abbreviate, drop but was 'short'",
                    "stacks",
                    "--profile",
                    "p.pb",
                    "--output",
                    "o.collapsed",
                    "--package-names",
                    "short"),
            List.of("Give exactly one of --profile and --collapsed-input", "stacks", "--output", "o.collapsed"),
            List.of("Missing required option: '--source=FILE'", "--jfr", "x.jfr", "--output", "out"),
            List.of("Missing required option: '--jfr=FILE'", "correlate", "--source", "x.pb", "--output", "out"),
            List.of("should be specified only once", "--source", "a", "--source", "b"),
            List.of("'maybe' is not a boolean", "--partial", "maybe"),
            List.of("expected nanoseconds", "--from-ns", "-1"),
            List.of("duplicate reason 'blocked'", "stacks", "--reason", "blocked,blocked"),
            List.of("Empty profile path", "merge", "--profiles", "a.pb,,b.pb", "--output", "m.pb"),
            List.of(
                    "Population estimates require the unthinned source",
                    "--source",
                    "a",
                    "--jfr",
                    "b",
                    "--output",
                    "c",
                    "--estimate-population",
                    "true",
                    "--thinning",
                    "0.5"),
            List.of(
                    "Thinning probability must be in (0, 1]",
                    "--source",
                    "a",
                    "--jfr",
                    "b",
                    "--output",
                    "c",
                    "--thinning",
                    "2"));

    static Stream<Arguments> usageErrorCases() {
        return USAGE_ERRORS.stream()
                .map(testCase -> Arguments.of(testCase.get(0), testCase.subList(1, testCase.size())));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("usageErrorCases")
    void usageErrors(String message, List<String> args) throws Exception {
        usageError(message, args.toArray(String[]::new));
    }

    /** {@code --dump --source} and {@code dump --source} write the same bytes, and neither closes stdout. */
    @Test
    void dumpAlias(@TempDir Path dir) throws Exception {
        Path jfr = CorrelationFixture.recording(dir, 1);
        var observation = CorrelationFixture.observation(CorrelationFixture.sampleThread(jfr));
        Path source = CorrelationFixture.source(dir, jfr, List.of(observation));
        byte[] alias = stdout("--dump", "--source", source.toString());
        byte[] command = stdout("dump", "--source", source.toString());
        assertThat(alias).as("dump must write something").isNotEmpty();
        assertThat(alias).as("dump and --dump must agree").isEqualTo(command);

        // The pre-picocli spellings used by the README and the Pulsar launcher keep their exit codes, and the default
        // command and its explicit name write the same analysis.
        Path first = dir.resolve("default");
        Path second = dir.resolve("explicit");
        List<String> common = List.of(
                "--source",
                source.toString(),
                "--jfr",
                jfr.toString(),
                "--quantum-ns",
                "10000000",
                "--audit",
                "none",
                "--from",
                "0s",
                "--to",
                "1h");
        List<String> implicit = new ArrayList<>(common);
        implicit.addAll(List.of("--output", first.toString()));
        List<String> explicit = new ArrayList<>(List.of("correlate"));
        explicit.addAll(common);
        explicit.addAll(List.of("--output", second.toString()));
        assertThat(OffCpuCorrelator.run(implicit.toArray(String[]::new)))
                .as("The default command must correlate")
                .isZero();
        assertThat(OffCpuCorrelator.run(explicit.toArray(String[]::new)))
                .as("correlate must correlate")
                .isZero();
        assertThat(first.resolve(OutputFiles.SUMMARY_MD))
                .as("No digest: %s", Files.readString(first.resolve(OutputFiles.REPORT)))
                .exists();
        for (String file : List.of(
                OutputFiles.COLLAPSED,
                OutputFiles.PROFILE,
                OutputFiles.SUMMARY_JSON,
                OutputFiles.SUMMARY_MD,
                OutputFiles.COMPLETE)) {
            assertThat(Files.readAllBytes(first.resolve(file)))
                    .as("%s must not depend on how correlate is named", file)
                    .isEqualTo(Files.readAllBytes(second.resolve(file)));
        }
        // Correlation writes the digest by default and names it in the report; --summary-output false does not.
        ReportProto.DigestFiles digest =
                CorrelationFixture.report(first.resolve(OutputFiles.REPORT)).getDigest();
        assertThat(digest.getPath())
                .as("The report must name the digest: %s", digest)
                .isEqualTo(OutputFiles.SUMMARY_MD);
        assertThat(digest.getJson())
                .as("The report must name the digest: %s", digest)
                .isEqualTo(OutputFiles.SUMMARY_JSON);
        assertThat(Files.readString(first.resolve(OutputFiles.SUMMARY_MD))).startsWith("# jonoffcpu analysis digest");
        Path withoutDigest = dir.resolve("without-digest");
        List<String> noDigest = new ArrayList<>(common);
        noDigest.addAll(List.of("--output", withoutDigest.toString(), "--summary-output", "false"));
        assertThat(OffCpuCorrelator.run(noDigest.toArray(String[]::new)))
                .as("Correlation without a digest")
                .isZero();
        assertThat(withoutDigest.resolve(OutputFiles.SUMMARY_MD))
                .as("--summary-output false must write no digest")
                .doesNotExist();
        assertThat(withoutDigest.resolve(OutputFiles.SUMMARY_JSON))
                .as("--summary-output false must write no digest")
                .doesNotExist();
        assertThat(Files.readString(withoutDigest.resolve(OutputFiles.REPORT)))
                .as("--summary-output false must write no digest")
                .doesNotContain("\"digest\"");
        Path slice = dir.resolve("slice.collapsed");
        Path patterns = Files.writeString(dir.resolve("idle.txt"), "epollWait\n");
        assertThat(OffCpuCorrelator.run(new String[] {
                    "stacks",
                    "--profile",
                    first.resolve(OutputFiles.PROFILE).toString(),
                    "--package-names",
                    "drop",
                    "--summary",
                    dir.resolve("slice.json").toString(),
                    "--output",
                    slice.toString(),
                    "--exclude-from",
                    patterns.toString()
                }))
                .as("The launcher's stacks invocation must succeed")
                .isZero();

        // A failing analysis still throws out of run instead of exiting: an existing output directory must fail.
        assertThatExceptionOfType(FileAlreadyExistsException.class)
                .isThrownBy(() -> OffCpuCorrelator.run(new String[] {
                    "--source", source.toString(), "--jfr", jfr.toString(), "--output", first.toString()
                }));
    }

    private static byte[] stdout(String... args) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            int code = OffCpuCorrelator.run(args);
            boolean closed = capture.checkError();
            // Restored before asserting, so that a failure is reported on the real stdout.
            System.setOut(original);
            assertThat(code).as("dump must succeed").isZero();
            assertThat(closed).as("dump must not close stdout").isFalse();
        } finally {
            System.setOut(original);
        }
        return bytes.toByteArray();
    }

    // ---- README against the parser ---------------------------------------------------------------

    private static final Pattern OPTION = Pattern.compile("(?<![\\w-])--[a-z][a-z0-9-]*");
    private static final Pattern DEFAULT = Pattern.compile("Default `([^`]+)`");

    /** The lines of the README section starting at {@code heading}, up to the next heading of its level or above. */
    static List<String> section(List<String> readme, String heading) {
        int start = readme.indexOf(heading);
        assertThat(start).as("README has no section %s", heading).isNotNegative();
        int level = heading.indexOf(' ');
        List<String> lines = new ArrayList<>();
        boolean fenced = false;
        for (String line : readme.subList(start + 1, readme.size())) {
            if (line.startsWith("```")) fenced = !fenced;
            if (!fenced && line.startsWith("#") && line.indexOf(' ') <= level) break;
            lines.add(line);
        }
        return lines;
    }

    private static Set<String> options(String text) {
        Set<String> options = new LinkedHashSet<>();
        Matcher matcher = OPTION.matcher(text);
        while (matcher.find()) options.add(matcher.group());
        return options;
    }

    private static CommandSpec spec(String command) {
        CommandLine root = Cli.commandLine();
        return command.isEmpty()
                ? root.getCommandSpec()
                : root.getSubcommands().get(command).getCommandSpec();
    }

    @Test
    void readmeMatchesParser() throws IOException {
        String path = System.getProperty("jonoffcpu.readme");
        assumeTrue(path != null, "Skipping the README check: -Djonoffcpu.readme is not set");
        List<String> readme = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
        CommandSpec correlate = spec("");
        // Each table row names its options in the first column; a stated default must be the parser's.
        for (String line : section(readme, "### Correlator options")) {
            if (!line.startsWith("| `--")) continue;
            String[] columns = line.split("(?<!\\\\)\\|");
            Set<String> names = options(columns[1]);
            for (String name : names) {
                assertThat(correlate.findOption(name))
                        .as("README option %s is not a correlate option", name)
                        .isNotNull();
            }
            Matcher stated = DEFAULT.matcher(columns[2]);
            if (names.size() == 1 && stated.find()) {
                OptionSpec option = correlate.findOption(names.iterator().next());
                assertThat(stated.group(1))
                        .as("README default of %s must be the parser's", option.longestName())
                        .isEqualTo(option.defaultValue());
            }
        }
        for (String name : options(String.join("\n", section(readme, "### Correlator options")))) {
            assertThat(correlate.findOption(name))
                    .as("README mentions unknown correlate option %s", name)
                    .isNotNull();
        }
        // The slicing section's commands: every option it names belongs to one of them.
        List<CommandSpec> profileCommands =
                List.of(spec("stacks"), spec("top"), spec("summarize"), spec("merge"), spec("export"));
        for (String heading : List.of(
                "### 5. Slice and filter with the stack profile",
                "### 6. Find what to optimize",
                "## Analyzing with AI agents",
                "## Analyzing with SQL")) {
            for (String name : options(String.join("\n", section(readme, heading)))) {
                assertThat(profileCommands)
                        .as("README section %s mentions unknown option %s", heading, name)
                        .anyMatch(command -> command.findOption(name) != null);
            }
        }
        for (String name : options(String.join("\n", section(readme, "### 3. Correlate")))) {
            assertThat(correlate.findOption(name))
                    .as("README correlate step mentions unknown option %s", name)
                    .isNotNull();
        }
    }
}
