// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import static io.github.jonoffcpu.correlator.CommandLineFixture.invoke;
import static io.github.jonoffcpu.correlator.CommandLineFixture.usageError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.jonoffcpu.correlator.CommandLineFixture.Invocation;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The command line's contract: help per command matches its checked-in snapshot, usage errors return 64 with the
 * message and usage and no stack trace, and {@code run} never exits. The documentation's options are checked by
 * {@link DocumentationTest}. Run with {@code -Djonoffcpu.updateHelp=DIR} to rewrite the snapshots into {@code DIR}.
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
            List.of(
                    "expected one of blocked, runnable, preempted but was 'unspecified'",
                    "stacks",
                    "--reason",
                    "unspecified"),
            List.of("Empty profile path", "merge", "--profiles", "a.pb,,b.pb", "--output", "m.pb"),
            // The busy/idle spellings are gone: --idle is an unknown option, jvm-idle an unknown preset.
            List.of("Unknown options: '--idle'", "summarize", "--profile", "p.pb", "--idle", "x"),
            List.of("Unknown options: '--idle-from'", "top", "--profile", "p.pb", "--idle-from", "preset:jvm-waiting"),
            List.of(
                    "Unknown options: '--idle-from'",
                    "--source",
                    "a",
                    "--jfr",
                    "b",
                    "--output",
                    "o",
                    "--idle-from",
                    "x"),
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

    /** dump writes the stream without closing stdout. */
    @Test
    void dumpAlias(@TempDir Path dir) throws Exception {
        Path jfr = CorrelationFixture.recording(dir, 1);
        var observation = CorrelationFixture.observation(CorrelationFixture.sampleThread(jfr));
        Path source = CorrelationFixture.source(dir, jfr, List.of(observation));
        assertThat(stdout("dump", "--source", source.toString()))
                .as("dump must write something")
                .isNotEmpty();

        // The pre-picocli spellings used by the README and the Pulsar launcher keep their exit codes, and the default
        // command and its explicit name write the same analysis.
        Path first = dir.resolve("default");
        Path second = dir.resolve("explicit");
        List<String> common = List.of(
                "--source",
                source.toString(),
                "--jfr",
                jfr.toString(),
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
        // --waiting-from shapes the digest alone: a waiting pattern matching every frame leaves no blocked time there,
        // and
        // every other output is the default one.
        Path everythingWaiting = Files.writeString(dir.resolve("everything-waiting.txt"), "# Every frame\n.\n");
        Path waitingDigest = dir.resolve("waiting-digest");
        List<String> withWaiting = new ArrayList<>(common);
        withWaiting.addAll(
                List.of("--output", waitingDigest.toString(), "--waiting-from", everythingWaiting.toString()));
        assertThat(OffCpuCorrelator.run(withWaiting.toArray(String[]::new)))
                .as("Correlation with waiting patterns")
                .isZero();
        for (String file : List.of(OutputFiles.COLLAPSED, OutputFiles.PROFILE)) {
            assertThat(Files.readAllBytes(waitingDigest.resolve(file)))
                    .as("--waiting-from must not change %s", file)
                    .isEqualTo(Files.readAllBytes(first.resolve(file)));
        }
        AnalysisProto.Digest waiting = CorrelationFixture.parse(
                        waitingDigest.resolve(OutputFiles.SUMMARY_JSON), AnalysisProto.Digest.newBuilder())
                .build();
        assertThat(waiting.getWhereTheTimeWent().getBlocked().getIntervals())
                .as("Every interval matched the waiting pattern: %s", waiting.getWhereTheTimeWent())
                .isZero();
        assertThat(waiting.getWhereTheTimeWent().getWaiting().getIntervals())
                .as("Every interval matched the waiting pattern: %s", waiting.getWhereTheTimeWent())
                .isEqualTo(waiting.getWhereTheTimeWent().getSelected().getIntervals());
        assertThat(Files.readString(waitingDigest.resolve(OutputFiles.SUMMARY_MD)))
                .as("The digest names the waiting patterns it left out")
                .contains(everythingWaiting.toString());
        List<String> waitingWithoutDigest = new ArrayList<>(common);
        waitingWithoutDigest.addAll(List.of(
                "--output",
                dir.resolve("waiting-without-digest").toString(),
                "--summary-output",
                "false",
                "--waiting",
                "."));
        CommandLineFixture.usageError("--summary-output false leaves out", waitingWithoutDigest.toArray(String[]::new));
        // The report describes the process from the JFR, and keeps the command line only with --process-details true.
        SignalProto.JfrRecording recording =
                CorrelationFixture.report(first.resolve(OutputFiles.REPORT)).getRecording();
        assertThat(recording.getAsyncProfilerVersion())
                .as("async-profiler's recording name")
                .isEqualTo("0.0-test");
        assertThat(recording.getEventsMap())
                .as("The JDK's own events, whole")
                .containsKeys("jdk.JVMInformation", "jdk.OSInformation", "jdk.CPUInformation");
        assertThat(recording.getEventsMap().get("jdk.JVMInformation").getFieldsMap())
                .containsKeys("jvmName", "jvmVersion", "pid")
                .doesNotContainKeys("jvmArguments", "javaArguments");
        assertThat(recording.getSystemPropertiesMap())
                .as("No system properties by default")
                .isEmpty();
        assertThat(recording.getEnvironmentVariablesMap())
                .as("No environment by default")
                .isEmpty();
        assertThat(recording.hasStart() && recording.hasEnd())
                .as("The chunk bounds")
                .isTrue();
        Path detailed = dir.resolve("detailed");
        List<String> withDetails = new ArrayList<>(common);
        withDetails.addAll(List.of("--output", detailed.toString(), "--process-details", "true"));
        assertThat(OffCpuCorrelator.run(withDetails.toArray(String[]::new))).isZero();
        SignalProto.JfrRecording details =
                CorrelationFixture.report(detailed.resolve(OutputFiles.REPORT)).getRecording();
        assertThat(details.getEventsMap().get("jdk.JVMInformation").getFieldsMap())
                .containsKeys("jvmArguments", "javaArguments");
        assertThat(details.getSystemPropertiesMap()).containsKey("java.vm.version");
        assertThat(Files.readString(detailed.resolve(OutputFiles.SUMMARY_MD)))
                .as("The digest shows them")
                .contains("**Command line** (jdk.JVMInformation):", "System properties (");
        assertThat(Files.readString(first.resolve(OutputFiles.SUMMARY_MD)))
                .as("The digest describes the recording, without the command line")
                .contains("- **Recorded:** ", "- **Analysed:** ", "- **Process:** PID ", "async-profiler 0.0-test")
                .doesNotContain("Command line", "System properties");

        // --app roots the digest at the application and changes no other output.
        Path appDigest = dir.resolve("app-digest");
        List<String> withApp = new ArrayList<>(common);
        withApp.addAll(List.of("--output", appDigest.toString(), "--app", "."));
        assertThat(OffCpuCorrelator.run(withApp.toArray(String[]::new)))
                .as("Correlation with an application pattern")
                .isZero();
        for (String file : List.of(OutputFiles.COLLAPSED, OutputFiles.PROFILE)) {
            assertThat(Files.readAllBytes(appDigest.resolve(file)))
                    .as("--app must not change %s", file)
                    .isEqualTo(Files.readAllBytes(first.resolve(file)));
        }
        AnalysisProto.Digest app = CorrelationFixture.parse(
                        appDigest.resolve(OutputFiles.SUMMARY_JSON), AnalysisProto.Digest.newBuilder())
                .build();
        assertThat(app.getSelection().getTransforms().getHide(0).getSource())
                .as("--app hides preset:jvm-dispatch by default: %s", app.getSelection())
                .isEqualTo("preset:jvm-dispatch");
        assertThat(app.hasBlockedByRoot())
                .as("The digest is rooted at the application")
                .isTrue();
        for (List<String> refused : List.of(
                List.of("--summary-output", "false", "--app", "."),
                List.of("--summary-output", "false", "--hide", "."),
                List.of("--hide-from", "preset:jvm-dispatch"))) {
            List<String> args = new ArrayList<>(common);
            args.addAll(List.of("--output", dir.resolve("refused").toString()));
            args.addAll(refused);
            CommandLineFixture.usageError("--hide", args.toArray(String[]::new));
        }
        Path slice = dir.resolve("slice.collapsed");
        Path patterns = Files.writeString(dir.resolve("waiting.txt"), "epollWait\n");
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
}
