// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import io.github.jonoffcpu.capture.CaptureProto;
import io.github.jonoffcpu.correlator.AnalysisProto.TopRow;
import io.github.jonoffcpu.correlator.profile.ProfileProto;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The digest on hand-built entries: its two layouts, the metadata block, the terms, command blocks that parse back to
 * their commands and reproduce their tables, and what --process-details shows.
 */
class DigestTest {
    private static final CaptureProto.Sampling NONE = CaptureProto.Sampling.newBuilder()
            .addReasons(CaptureProto.OffCpuReason.OFF_CPU_REASON_BLOCKED)
            .setNone(CaptureProto.NoAdmission.getDefaultInstance())
            .build();

    private static List<StackProfile.Frame> java(String... names) {
        return Arrays.stream(names)
                .map(name -> new StackProfile.Frame(
                        name.contains(" ") || StackTransforms.looksNative(name)
                                ? StackProfile.Kind.JFR_NATIVE
                                : StackProfile.Kind.JAVA,
                        name,
                        ""))
                .toList();
    }

    private static StackProfile.Entry entry(List<StackProfile.Frame> stack, String thread, long intervals, long nanos) {
        return entry(stack, thread, intervals, nanos, OffCpuReason.BLOCKED);
    }

    private static StackProfile.Entry entry(
            List<StackProfile.Frame> stack, String thread, long intervals, long nanos, OffCpuReason reason) {
        return new StackProfile.Entry(stack, null, null, reason, 1, thread, intervals, nanos, nanos);
    }

    /**
     * A service whose work runs through an executor and a lambda bridge, a map read, a web pool waiting for work that
     * the waiting list misses ({@code web} nanoseconds), and a waiting pool worker.
     */
    private static StackProfile profile(long web) {
        return profile(web, NONE, OffCpuReason.BLOCKED);
    }

    /** As {@link #profile(long)}, recorded with {@code sampling}, the map read having switched out for {@code reason}. */
    private static StackProfile profile(long web, CaptureProto.Sampling sampling, OffCpuReason reason) {
        return new StackProfile(
                new StackProfile.Header(
                        List.of(ProfileProto.Provenance.newBuilder()
                                .setSessionId("digest")
                                .setCaptureEpoch(1)
                                .setSampling(sampling)
                                .setThinningProbability("1")
                                .build()),
                        List.of("reason", "thread"),
                        false,
                        null,
                        "",
                        List.of()),
                List.of(
                        entry(
                                java(
                                        "java.lang.Thread.run",
                                        "java.util.concurrent.FutureTask.run",
                                        "x.Svc$$Lambda.0x0000000081a06030.run",
                                        "x.Svc.lambda$go$0",
                                        "x.Svc.work",
                                        "java.util.concurrent.locks.ReentrantLock.lock",
                                        "jdk.internal.misc.Unsafe.park"),
                                "svc-1",
                                2,
                                2_000_000_000L),
                        entry(
                                java(
                                        "java.lang.Thread.run",
                                        "x.Svc.handle",
                                        "x.Map.get",
                                        "java.util.concurrent.locks.StampedLock.readLock",
                                        "jdk.internal.misc.Unsafe.park"),
                                "svc-2",
                                1,
                                1_000_000_000L,
                                reason),
                        entry(
                                java(
                                        "java.lang.Thread.run",
                                        "org.eclipse.jetty.util.thread.ReservedThreadExecutor$ReservedThread.waitForTask",
                                        "jdk.internal.misc.Unsafe.park"),
                                "web-12",
                                3,
                                web),
                        entry(
                                java(
                                        "java.lang.Thread.run",
                                        "java.util.concurrent.ThreadPoolExecutor.getTask",
                                        "jdk.internal.misc.Unsafe.park"),
                                "pool-1-thread-1",
                                7,
                                50_000_000_000L)));
    }

    private static Digest.Options application(boolean processDetails) throws IOException {
        Digest.Options defaults = Digest.defaults();
        return new Digest.Options(
                Cli.sourced(List.of("^x\\."), "--app-from", List.of()),
                defaults.waiting(),
                defaults.machinery(),
                Cli.sourced(List.of(), "--hide-from", List.of("preset:jvm-dispatch")),
                defaults.limit(),
                processDetails);
    }

    private static Digest.Options application() throws IOException {
        return application(false);
    }

    private static Struct struct(Map<String, Object> fields) {
        Struct.Builder struct = Struct.newBuilder();
        fields.forEach((name, value) -> struct.putFields(
                name,
                value instanceof Number number
                        ? Value.newBuilder()
                                .setNumberValue(number.doubleValue())
                                .build()
                        : Value.newBuilder().setStringValue(value.toString()).build()));
        return struct.build();
    }

    private static Timestamp timestamp(String instant) {
        Instant parsed = Instant.parse(instant);
        return Timestamp.newBuilder()
                .setSeconds(parsed.getEpochSecond())
                .setNanos(parsed.getNano())
                .build();
    }

    /** A report as correlation writes it, with a recording as the JFR describes it and a selected window. */
    private static ReportProto.Report report() {
        return ReportProto.Report.newBuilder()
                .setAnalysisInputs(CaptureProto.AnalysisInputs.newBuilder()
                        .setSessionId("72157cd7-7952-4b1b-913c-152c1d31fdd7")
                        .setTargetPid(72)
                        .setHostTgid(2976336)
                        .setSampling(NONE)
                        .setJfrArtifact(CaptureProto.FileArtifact.newBuilder()
                                .setPath("broker.jfr")
                                .setSha256("7bef8e83")))
                .setSourceRows(20)
                .setMatched(13)
                .setJfrSelection(ReportProto.JfrSelection.newBuilder()
                        .setFrom("2026-09-24T23:45:09.903Z")
                        .setTo("2026-09-24T23:45:47.924Z"))
                .setRecording(SignalProto.JfrRecording.newBuilder()
                        .setStart(timestamp("2026-09-24T23:44:33Z"))
                        .setEnd(timestamp("2026-09-24T23:46:02Z"))
                        .setAsyncProfilerVersion("4.5")
                        .putEvents(
                                "jdk.JVMInformation",
                                struct(
                                        Map.of(
                                                "jvmName",
                                                "OpenJDK 64-Bit Server VM",
                                                "jvmVersion",
                                                "OpenJDK 64-Bit Server VM (25.0.4.1+8-LTS) for linux-amd64 JRE (25.0.4.1+8-LTS)",
                                                "pid",
                                                72,
                                                "jvmArguments",
                                                "-Xmx4g -Ddb.password=hunter2",
                                                "javaArguments",
                                                "org.apache.pulsar.PulsarBrokerStarter --broker-conf /pulsar/conf/broker.conf")))
                        .putEvents(
                                "jdk.OSInformation",
                                struct(Map.of(
                                        "osVersion",
                                        "ID=wolfi\nPRETTY_NAME=\"Wolfi\"\nuname: Linux 7.1.5-76070105-generic #2026 SMP"
                                                + " PREEMPT_DYNAMIC x86_64\nlibc: glibc 2.44 NPTL 2.44 \n")))
                        .putEvents(
                                "jdk.CPUInformation",
                                struct(Map.of(
                                        "cpu",
                                        "Intel (null) (HT)",
                                        "description",
                                        "Brand: Intel(R) Core(TM) i9-9980HK CPU @ 2.40GHz, Vendor: GenuineIntel\nFamily: 6",
                                        "cores",
                                        8,
                                        "hwThreads",
                                        16)))
                        .putSystemProperties("java.vm.version", "25.0.4.1+8-LTS")
                        .putEnvironmentVariables("DB_PASSWORD", "hunter2"))
                .build();
    }

    /** A digest with a fixed writer, so that a golden file does not change with the version. */
    private static String markdown(AnalysisProto.Digest digest) {
        return Digest.markdown(
                digest.toBuilder().setWrittenBy("jonoffcpu-correlator test").build());
    }

    private static List<String> keys(AnalysisProto.DigestTable table) {
        return table.getRowsList().stream().map(TopRow::getKey).toList();
    }

    private static BigDecimal sum(AnalysisProto.DigestTable table) {
        return table.getRowsList().stream()
                .map(row -> new BigDecimal(row.getValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The Markdown outside its fenced blocks, where the reading rules apply. */
    private static String prose(String markdown) {
        StringBuilder prose = new StringBuilder();
        boolean fenced = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("```")) {
                fenced = !fenced;
            } else if (!fenced) {
                prose.append(line).append('\n');
            }
        }
        return prose.toString();
    }

    /** The fenced {@code bash} blocks of a Markdown text, each as its lines. */
    private static List<List<String>> bashBlocks(String markdown) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> block = null;
        for (String line : markdown.split("\n", -1)) {
            if (block == null && line.equals("```bash")) {
                block = new ArrayList<>();
            } else if (block != null && line.equals("```")) {
                blocks.add(block);
                block = null;
            } else if (block != null) {
                block.add(line);
            }
        }
        return blocks;
    }

    /** The tables that say where to look come first, then the context, each table summing to the application time. */
    @Test
    void applicationRootedLayout() throws Exception {
        AnalysisProto.Digest digest = Digest.of(profile(10_000_000_000L), "run.pb", report(), application());
        assertThat(keys(digest.getBlocked()))
                .as("The application methods that waited")
                .containsExactly("x.Svc.work", "x.Map.get");
        assertThat(digest.getBlocked().getRows(0).getShare())
                .as("Shares are of the blocked time with an application frame")
                .isEqualTo("0.666667");
        assertThat(keys(digest.getBlockedByRoot()))
                .as("Roots are the work, not the executor or the lambda bridge")
                .containsExactly("x.Svc.lambda$go$0", "x.Svc.handle");
        assertThat(keys(digest.getBlockedByApplicationMethod()))
                .containsExactly("x.Svc.lambda$go$0 → x.Svc.work", "x.Svc.handle → x.Map.get");
        String application =
                digest.getWhereTheTimeWent().getBlockedApplication().getValue();
        assertThat(application).isEqualTo("3.000");
        for (AnalysisProto.DigestTable table : List.of(digest.getBlocked(), digest.getBlockedByRoot())) {
            assertThat(sum(table))
                    .as("The %s rows sum to the blocked time with an application frame", table.getBy())
                    .isEqualByComparingTo(application);
        }
        assertThat(digest.getBlockedWithoutApplicationFrame().getValue()).isEqualTo("10.000");
        assertThat(keys(digest.getBlockedNoApplicationFrameByPool())).containsExactly("web-#");
        AnalysisProto.TopTotals totals = digest.getWhereTheTimeWent();
        assertThat(totals.getBlockedApplication().getShareOfBlocked()).isEqualTo("0.230769231");
        assertThat(totals.getWaiting().getShareOfSelected()).isEqualTo("0.793650794");
        assertThat(totals.getWaiting().hasShareOfBlocked())
                .as("Waiting is not part of the blocked time")
                .isFalse();

        String markdown = markdown(digest);
        assertThat(markdown)
                .as("The metadata block and the headline")
                .contains(
                        "- **Recorded:** 2026-09-24 23:44:33 UTC to 23:46:02 UTC (1 min 29 s)\n",
                        "- **Analysed:** 2026-09-24 23:45:09.903 UTC to 23:45:47.924 UTC (38.0 s), the selected"
                                + " window\n",
                        "- **Process:** PID 72 (host TGID 2976336), OpenJDK 64-Bit Server VM 25.0.4.1+8-LTS,"
                                + " async-profiler 4.5\n",
                        "- **System:** Wolfi, Linux 7.1.5-76070105-generic x86_64, glibc 2.44; Intel(R) Core(TM)"
                                + " i9-9980HK CPU @ 2.40GHz, 8 cores, 16 hardware threads\n",
                        "Blocked with an application frame: 3.000 s, 23.1 % of the 13.000 s blocked; 50.000 s waiting"
                                + " left out. Terms: [About this digest](#about-this-digest).")
                .contains("| Blocked with an application frame | 2 | 3 | 3.000 | 23.1 % | 4.8 % |")
                .contains("| Waiting, left out | 1 | 7 | 50.000 |  | 79.4 % |");
        List<String> headings = List.of(
                "## Blocked, by the application method that waited",
                "## Blocked, by application root",
                "## Blocked, by application method",
                "## Where the time went",
                "## Blocked without an application frame, by pool",
                "## About this digest",
                "## How to reproduce");
        List<Integer> positions = headings.stream().map(markdown::indexOf).toList();
        assertThat(positions)
                .as("Every section, in order: %s", headings)
                .doesNotContain(-1)
                .isSorted();
        assertThat(markdown).doesNotContain("## Heaviest");
        assertThat(markdown.substring(0, markdown.indexOf("## Blocked without an application frame, by pool")))
                .as("The unattributed pool is only in its own table")
                .doesNotContain("web-");
        assertThat(markdown)
                .as("Without --process-details, no command line, system properties or environment variables")
                .doesNotContain("hunter2", "Command line", "System properties", "Environment variables");
        String about = markdown.substring(markdown.indexOf("## About this digest"));
        assertThat(about)
                .as("The terms of the application layout")
                .contains(
                        "- **Blocked**:",
                        "- **Waiting**:",
                        "- **Application frame**:",
                        "- **Application root**:",
                        "- **Application method that waited**:",
                        "- **Self time**",
                        "- **Observed seconds**:")
                .doesNotContain("**In the run queue**");
        assertThat(about)
                .as("The default waiting patterns: the reproduce commands keep preset:*, the notes name its presets")
                .contains("  --waiting-from 'preset:*' \\\n", "  - `preset:jvm-waiting`\n");
    }

    /** The reading rules: no busy or idle, one sentence under each table, commands only in wrapped bash blocks. */
    @Test
    void readingRules() throws Exception {
        for (Digest.Options options : List.of(Digest.defaults(), application())) {
            AnalysisProto.Digest digest = Digest.of(profile(10_000_000_000L), "run.pb", report(), options);
            String markdown = markdown(digest);
            String prose = prose(markdown);
            assertThat(prose.toLowerCase(java.util.Locale.ROOT))
                    .as("Blocked and waiting, never busy or idle")
                    .doesNotContain("busy", "idle");
            assertThat(prose).as("No command inline").doesNotContain("`java -jar", "Reproduce: `");
            String tables = prose.substring(prose.indexOf("\n## "), prose.indexOf("## About this digest"));
            for (String paragraph : tables.split("\n\n")) {
                if (paragraph.startsWith("|") || paragraph.startsWith("#") || paragraph.startsWith("- ")) continue;
                assertThat(paragraph.split("(?<=\\.) ").length)
                        .as("At most one sentence between the tables: %s", paragraph)
                        .isLessThanOrEqualTo(1);
            }
            List<String> commands =
                    new ArrayList<>(List.of(digest.getBlocked().getCommand(), digest.getFlameGraphCommand()));
            List<List<String>> blocks = bashBlocks(markdown);
            assertThat(blocks).as("One block per command").hasSizeGreaterThanOrEqualTo(4);
            List<String> joined = new ArrayList<>();
            for (List<String> block : blocks) {
                for (int line = 0; line < block.size(); line++) {
                    String text = block.get(line);
                    assertThat(text.length() <= Top.COMMAND_WIDTH
                                    || !text.trim().contains(" "))
                            .as("A block line is at most %s characters: %s", Top.COMMAND_WIDTH, text)
                            .isTrue();
                    if (line < block.size() - 1) assertThat(text).endsWith(" \\");
                }
                String command = String.join(
                        " ",
                        block.stream()
                                .map(text -> text.endsWith(" \\") ? text.substring(0, text.length() - 2) : text)
                                .map(String::trim)
                                .toList());
                joined.add(command);
                assertThat(Top.words(command))
                        .as("The block parses to its command's words")
                        .isEqualTo(Top.words(command.replaceAll("\\s+", " ")));
            }
            for (String command : commands) {
                assertThat(joined.stream().map(Top::words).toList())
                        .as("The block of %s splits into its words", command)
                        .contains(Top.words(command));
            }
        }
    }

    /** More than half of the blocked time without an application frame says the waiting patterns probably miss waits. */
    @ParameterizedTest(name = "{0} ns without an application frame")
    @CsvSource({"3001000000, true", "3000000000, false", "1000000000, false"})
    void missingWaitingNote(long web, boolean note) throws Exception {
        String markdown = markdown(Digest.of(profile(web), "run.pb", null, application()));
        assertThat(markdown.contains("> Most of the blocked time has no application frame: see [Blocked without an"
                        + " application frame, by pool](#blocked-without-an-application-frame-by-pool)."))
                .as("The opening note with %s ns of 3 s application time", web)
                .isEqualTo(note);
    }

    /** With runnable or preempted intervals recorded and selected, the first slice is blocked or in the run queue. */
    @Test
    void runQueue() throws Exception {
        CaptureProto.Sampling reasons = NONE.toBuilder()
                .addReasons(CaptureProto.OffCpuReason.OFF_CPU_REASON_RUNNABLE)
                .build();
        for (Digest.Options options : List.of(Digest.defaults(), application())) {
            AnalysisProto.Digest digest =
                    Digest.of(profile(10_000_000_000L, reasons, OffCpuReason.RUNNABLE), "run.pb", null, options);
            assertThat(digest.getRunQueue()).isTrue();
            String markdown = markdown(digest);
            assertThat(markdown)
                    .contains("## Blocked or in the run queue, by ", "- **In the run queue**:")
                    .doesNotContain("## Blocked, by");
        }
        assertThat(Digest.of(profile(10_000_000_000L), "run.pb", null, Digest.defaults())
                        .getRunQueue())
                .as("Blocked intervals only")
                .isFalse();
    }

    /** --process-details shows the command line, the system properties and the environment variables. */
    @Test
    void processDetails() throws Exception {
        AnalysisProto.Digest digest = Digest.of(profile(10_000_000_000L), "run.pb", report(), application(true));
        String markdown = markdown(digest);
        assertThat(markdown)
                .contains(
                        "**Command line** (jdk.JVMInformation):\n\n```text\njava \\\n  -Xmx4g \\\n"
                                + "  -Ddb.password=hunter2 \\\n"
                                + "  org.apache.pulsar.PulsarBrokerStarter --broker-conf /pulsar/conf/broker.conf\n```",
                        "<details><summary>System properties (1, jdk.InitialSystemProperty)</summary>",
                        "| `java.vm.version` | `25.0.4.1+8-LTS` |",
                        "<details><summary>Environment variables (1, jdk.InitialEnvironmentVariable)</summary>");
        AnalysisProto.Digest hidden = Digest.of(profile(10_000_000_000L), "run.pb", report(), application(false));
        assertThat(hidden.getRecording()
                        .getEventsMap()
                        .get("jdk.JVMInformation")
                        .getFieldsMap())
                .as("The command line is removed from the digest's JSON too")
                .doesNotContainKeys("jvmArguments", "javaArguments")
                .containsKey("jvmName");
        assertThat(hidden.getRecording().getSystemPropertiesMap()).isEmpty();
        assertThat(hidden.getRecording().getEnvironmentVariablesMap()).isEmpty();
    }

    /** Without a selection the analysed window is the whole recording. */
    @Test
    void wholeRecording() throws Exception {
        ReportProto.Report report = report().toBuilder().clearJfrSelection().build();
        AnalysisProto.Digest digest = Digest.of(profile(10_000_000_000L), "run.pb", report, Digest.defaults());
        assertThat(digest.getAnalysed().getWholeRecording()).isTrue();
        assertThat(digest.getAnalysed().getSeconds()).isEqualTo("89.000");
        assertThat(markdown(digest)).contains("- **Analysed:** the whole recording\n");
    }

    /**
     * As the Apache Pulsar launcher renders it, with commonmark-java and its tables and heading-anchor extensions:
     * one bash code block per command, no long inline code, and every in-page link resolving to a heading.
     */
    @Test
    void rendersToHtml() throws Exception {
        List<org.commonmark.Extension> extensions = List.of(
                org.commonmark.ext.gfm.tables.TablesExtension.create(),
                org.commonmark.ext.heading.anchor.HeadingAnchorExtension.create());
        for (Digest.Options options : List.of(Digest.defaults(), application(true))) {
            AnalysisProto.Digest digest = Digest.of(profile(10_000_000_000L), "run.pb", report(), options);
            String markdown = markdown(digest);
            String html = org.commonmark.renderer.html.HtmlRenderer.builder()
                    .extensions(extensions)
                    .build()
                    .render(org.commonmark.parser.Parser.builder()
                            .extensions(extensions)
                            .build()
                            .parse(markdown));
            assertThat(countOf(html, "<pre><code class=\"language-bash\">"))
                    .as("One bash block per command")
                    .isEqualTo(bashBlocks(markdown).size());
            String outsidePre = html.replaceAll("(?s)<pre>.*?</pre>", "");
            java.util.regex.Matcher code =
                    java.util.regex.Pattern.compile("(?s)<code>(.*?)</code>").matcher(outsidePre);
            while (code.find()) {
                assertThat(code.group(1).length())
                        .as("Inline code is short: %s", code.group(1))
                        .isLessThanOrEqualTo(120);
            }
            java.util.regex.Matcher link =
                    java.util.regex.Pattern.compile("href=\"#([^\"]+)\"").matcher(html);
            int links = 0;
            while (link.find()) {
                links++;
                assertThat(html).as("The anchor #%s exists", link.group(1)).contains("id=\"" + link.group(1) + "\"");
            }
            assertThat(links).as("The headline links to the terms").isPositive();
        }
    }

    private static int countOf(String text, String part) {
        int count = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + 1)) count++;
        return count;
    }

    /** Both layouts, byte for byte: {@code -Djonoffcpu.updateDigest=DIR} rewrites the golden files into DIR. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"application, true", "plain, false"})
    void golden(String name, boolean withApp) throws Exception {
        String markdown = markdown(
                Digest.of(profile(10_000_000_000L), "run.pb", report(), withApp ? application() : Digest.defaults()));
        String update = System.getProperty("jonoffcpu.updateDigest");
        if (update != null) {
            Files.writeString(Files.createDirectories(Path.of(update)).resolve(name + ".md"), markdown);
            return;
        }
        try (InputStream golden = DigestTest.class.getResourceAsStream("/digest/" + name + ".md")) {
            assertThat(golden).as("Missing golden file digest/%s.md", name).isNotNull();
            assertThat(markdown).isEqualTo(new String(golden.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Every reproduce command, run as written, prints its table as the digest has it. */
    @Test
    void reproduceCommands(@TempDir Path dir) throws Exception {
        Path profile = dir.resolve("run.pb");
        profile(10_000_000_000L).write(profile);
        Path out = dir.resolve("digest");
        CommandLineFixture.Invocation summarize = CommandLineFixture.invoke(
                "summarize", "--profile", profile.toString(), "--app", "^x\\.", "--output-dir", out.toString());
        assertThat(summarize.code()).as("summarize failed: %s", summarize).isZero();
        AnalysisProto.Digest digest = CorrelationFixture.parse(
                        out.resolve(OutputFiles.SUMMARY_JSON), AnalysisProto.Digest.newBuilder())
                .build();
        String markdown = Files.readString(out.resolve(OutputFiles.SUMMARY_MD));
        for (AnalysisProto.DigestTable table :
                List.of(digest.getBlocked(), digest.getBlockedByRoot(), digest.getBlockedByApplicationMethod())) {
            CommandLineFixture.Invocation top = CommandLineFixture.invoke(CommandLineFixture.words(table.getCommand()));
            assertThat(top.code()).as("%s failed: %s", table.getCommand(), top).isZero();
            StringBuilder rendered = new StringBuilder();
            Top.rowsTable(table.getRowsList(), table.getBy(), "s", "Intervals", true, rendered);
            assertThat(markdown).contains(rendered);
            assertThat(top.out())
                    .as("%s reproduces its table", table.getCommand())
                    .contains(rendered);
        }
        StringBuilder pools = new StringBuilder();
        Top.table(digest.getBlockedNoApplicationFrameByPool().getRowsList(), "pool", "s", "Intervals", true, pools);
        assertThat(CommandLineFixture.invoke(
                                CommandLineFixture.words(digest.getBlocked().getCommand()))
                        .out())
                .as("The waiting-method command also prints the pool table")
                .contains(pools);

        List<String> stacks = new ArrayList<>(List.of(CommandLineFixture.words(digest.getFlameGraphCommand())));
        Path collapsed = dir.resolve("blocked-app.collapsed");
        stacks.set(stacks.indexOf("blocked-app.collapsed"), collapsed.toString());
        assertThat(CommandLineFixture.invoke(stacks.toArray(String[]::new)).code())
                .isZero();
        assertThat(Files.readAllLines(collapsed, StandardCharsets.UTF_8))
                .as("The flame graph command renders the application-rooted lines")
                .containsExactlyInAnyOrder(
                        "x.Svc.lambda$go$0;x.Svc.work;java.util.concurrent.locks.ReentrantLock.lock 2000000",
                        "x.Svc.handle;x.Map.get;java.util.concurrent.locks.StampedLock.readLock 1000000");
    }
}
