// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * The command line's contract: help per command matches its checked-in snapshot, usage errors return 64 with the
 * message and usage and no stack trace, {@code run} never exits, and the README's option names and defaults are the
 * parser's. Run with {@code -Djonoffcpu.updateHelp=DIR} to rewrite the snapshots into {@code DIR}.
 */
public final class CommandLineTest {
    /** Every command with its own help, the top-level one as the empty name. */
    static final List<String> COMMANDS = List.of("", "correlate", "stacks", "merge", "export", "dump");

    private CommandLineTest() {}

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    record Invocation(int code, String out, String err) {}

    /** Runs one command line in-process, capturing what picocli prints. */
    static Invocation invoke(String... args) throws Exception {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Cli.run(args, new PrintWriter(out), new PrintWriter(err));
        return new Invocation(code, out.toString(), err.toString());
    }

    /** Checks that a command line is refused as a usage error, with its usage and without a stack trace. */
    static Invocation usageError(String message, String... args) throws Exception {
        Invocation invocation = invoke(args);
        check(invocation.code() == Cli.USAGE, "A usage error must return 64, got " + invocation);
        check(invocation.err().contains(message), "The usage error must say '" + message + "': " + invocation.err());
        check(invocation.err().contains("Usage: "), "A usage error must show the usage: " + invocation.err());
        check(
                !invocation.err().contains("Exception") && !invocation.err().contains("\tat "),
                "A usage error must not print a stack trace: " + invocation.err());
        return invocation;
    }

    private static String[] words(String command, String... rest) {
        List<String> words = new ArrayList<>();
        if (!command.isEmpty()) words.add(command);
        words.addAll(List.of(rest));
        return words.toArray(String[]::new);
    }

    private static void helpSnapshots() throws Exception {
        String update = System.getProperty("jonoffcpu.updateHelp");
        for (String command : COMMANDS) {
            Invocation flag = invoke(words(command, "--help"));
            Invocation helpCommand = invoke(command.isEmpty() ? new String[] {"help"} : new String[] {"help", command});
            check(flag.code() == 0 && helpCommand.code() == 0, "Help must return 0 for '" + command + "'");
            check(flag.out().equals(helpCommand.out()), "help " + command + " and --help must agree");
            check(flag.err().isEmpty(), "Help goes to stdout: " + flag.err());
            String name = (command.isEmpty() ? "jonoffcpu-correlator" : command) + ".txt";
            if (update != null) {
                Files.writeString(Files.createDirectories(Path.of(update)).resolve(name), flag.out());
                continue;
            }
            String expected;
            try (InputStream input = CommandLineTest.class.getResourceAsStream("/help/" + name)) {
                check(input != null, "Missing help snapshot " + name);
                expected = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            check(
                    flag.out().equals(expected),
                    "Help for '" + command + "' differs from src/test/resources/help/" + name + ":\n" + flag.out());
        }
        Invocation none = invoke();
        check(none.code() == 0 && none.out().equals(invoke("--help").out()), "No arguments must print the help");
        Invocation version = invoke("--version");
        check(
                version.code() == 0
                        && version.out().startsWith("jonoffcpu-correlator ")
                        && version.out().contains("async-profiler fork "),
                "--version must name the build and the async-profiler fork: " + version.out());
        check(invoke("stacks", "-V").out().equals(version.out()), "Every command has --version");
    }

    private static void usageErrors() throws Exception {
        Invocation typo = usageError("Unknown options: '--stakc'", "stacks", "--stakc", "java");
        check(typo.err().contains("--stack"), "A typo must suggest the option: " + typo.err());
        check(typo.err().contains("Usage: java -jar jonoffcpu-correlator.jar stacks"), "The stacks usage: " + typo);
        usageError(
                "expected one of full, abbreviate, drop but was 'short'",
                "stacks",
                "--profile",
                "p.pb",
                "--output",
                "o.collapsed",
                "--package-names",
                "short");
        usageError("Missing required option: '--profile=FILE'", "stacks", "--output", "o.collapsed");
        usageError("Missing required option: '--source=FILE'", "--jfr", "x.jfr", "--output", "out");
        usageError("Missing required option: '--jfr=FILE'", "correlate", "--source", "x.pb", "--output", "out");
        usageError("should be specified only once", "--source", "a", "--source", "b");
        usageError("'maybe' is not a boolean", "--partial", "maybe");
        usageError("expected nanoseconds", "--from-ns", "-1");
        usageError("duplicate reason 'blocked'", "stacks", "--reason", "blocked,blocked");
        usageError("Empty profile path", "merge", "--profiles", "a.pb,,b.pb", "--output", "m.pb");
        usageError(
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
                "0.5");
        usageError(
                "Thinning probability must be in (0, 1]",
                "--source",
                "a",
                "--jfr",
                "b",
                "--output",
                "c",
                "--thinning",
                "2");
    }

    /** {@code --dump --source} and {@code dump --source} write the same bytes, and neither closes stdout. */
    private static void dumpAlias(Path dir) throws Exception {
        Path jfr = OfflineCorrelatorTest.recording(dir, 1);
        long[] tid = new long[1];
        SignalJfrExporter.visit(jfr, row -> {
            if (row.get("recordType").equals("sample")) tid[0] = (Long) row.get("osThreadId");
        });
        JsonObject observation = OfflineCorrelatorTest.observation(tid[0]);
        Path source = OfflineCorrelatorTest.source(dir, jfr, List.of(observation));
        byte[] alias = stdout("--dump", "--source", source.toString());
        byte[] command = stdout("dump", "--source", source.toString());
        check(alias.length > 0 && java.util.Arrays.equals(alias, command), "dump and --dump must agree");

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
        check(OffCpuCorrelator.run(implicit.toArray(String[]::new)) == 0, "The default command must correlate");
        check(OffCpuCorrelator.run(explicit.toArray(String[]::new)) == 0, "correlate must correlate");
        for (String file : List.of(OutputFiles.COLLAPSED, OutputFiles.PROFILE, OutputFiles.COMPLETE)) {
            check(
                    java.util.Arrays.equals(
                            Files.readAllBytes(first.resolve(file)), Files.readAllBytes(second.resolve(file))),
                    file + " must not depend on how correlate is named");
        }
        Path slice = dir.resolve("slice.collapsed");
        Path patterns = Files.writeString(dir.resolve("idle.txt"), "epollWait\n");
        check(
                OffCpuCorrelator.run(new String[] {
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
                        })
                        == 0,
                "The launcher's stacks invocation must succeed");

        // A failing analysis still throws out of run instead of exiting.
        try {
            OffCpuCorrelator.run(
                    new String[] {"--source", source.toString(), "--jfr", jfr.toString(), "--output", first.toString()
                    });
            throw new AssertionError("An existing output directory must fail");
        } catch (java.nio.file.FileAlreadyExistsException expected) {
            // run returned control to the caller.
        }
    }

    private static byte[] stdout(String... args) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            check(OffCpuCorrelator.run(args) == 0, "dump must succeed");
            check(!capture.checkError(), "dump must not close stdout");
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
        check(start >= 0, "README has no section " + heading);
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

    private static void readmeMatchesParser() throws IOException {
        String path = System.getProperty("jonoffcpu.readme");
        if (path == null) {
            System.out.println("Skipping the README check: -Djonoffcpu.readme is not set");
            return;
        }
        List<String> readme = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
        CommandSpec correlate = spec("");
        // Each table row names its options in the first column; a stated default must be the parser's.
        for (String line : section(readme, "### Correlator options")) {
            if (!line.startsWith("| `--")) continue;
            String[] columns = line.split("(?<!\\\\)\\|");
            Set<String> names = options(columns[1]);
            for (String name : names) {
                check(correlate.findOption(name) != null, "README option " + name + " is not a correlate option");
            }
            Matcher stated = DEFAULT.matcher(columns[2]);
            if (names.size() == 1 && stated.find()) {
                OptionSpec option = correlate.findOption(names.iterator().next());
                check(
                        stated.group(1).equals(option.defaultValue()),
                        "README default of " + option.longestName() + " is " + stated.group(1) + ", the parser's is "
                                + option.defaultValue());
            }
        }
        for (String name : options(String.join("\n", section(readme, "### Correlator options")))) {
            check(correlate.findOption(name) != null, "README mentions unknown correlate option " + name);
        }
        // The slicing section's commands: every option it names belongs to one of them.
        List<CommandSpec> profileCommands = List.of(spec("stacks"), spec("merge"), spec("export"));
        for (String name :
                options(String.join("\n", section(readme, "### 5. Slice and filter with the stack profile")))) {
            check(
                    profileCommands.stream().anyMatch(command -> command.findOption(name) != null),
                    "README slicing section mentions unknown option " + name);
        }
        for (String name : options(String.join("\n", section(readme, "### 3. Correlate")))) {
            check(correlate.findOption(name) != null, "README correlate step mentions unknown option " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jonoffcpu-cli-test-");
        try {
            helpSnapshots();
            usageErrors();
            dumpAlias(dir);
            readmeMatchesParser();
            System.out.println("Command line fixtures passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
