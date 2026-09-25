// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jonoffcpu.capture.CaptureProto;
import io.github.jonoffcpu.correlator.AnalysisProto.TopRow;
import io.github.jonoffcpu.correlator.profile.ProfileProto;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The application-rooted digest on hand-built entries: layout, sums, the missing-idle note and reproduce commands. */
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
        return new StackProfile.Entry(stack, null, null, OffCpuReason.BLOCKED, 1, thread, intervals, nanos, nanos);
    }

    /**
     * A service whose work runs through an executor and a lambda bridge, a map read, a web pool waiting for work that
     * the idle list misses ({@code web} nanoseconds), and an idle pool worker.
     */
    private static StackProfile profile(long web) {
        return new StackProfile(
                new StackProfile.Header(
                        List.of(ProfileProto.Provenance.newBuilder()
                                .setSessionId("digest")
                                .setCaptureEpoch(1)
                                .setSampling(NONE)
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
                                1_000_000_000L),
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

    private static Digest.Options application() throws IOException {
        Digest.Options defaults = Digest.defaults();
        return new Digest.Options(
                Cli.sourced(List.of("^x\\."), "--app-from", List.of()),
                defaults.idle(),
                defaults.machinery(),
                Cli.sourced(List.of(), "--hide-from", List.of("preset:jvm-dispatch")),
                defaults.limit());
    }

    private static List<String> keys(AnalysisProto.DigestTable table) {
        return table.getRowsList().stream().map(TopRow::getKey).toList();
    }

    private static BigDecimal sum(AnalysisProto.DigestTable table) {
        return table.getRowsList().stream()
                .map(row -> new BigDecimal(row.getValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The tables that say where to look come first, then the context, each table summing to the application time. */
    @Test
    void applicationRootedLayout() throws Exception {
        AnalysisProto.Digest digest = Digest.of(profile(10_000_000_000L), "run.pb", null, application());
        assertThat(keys(digest.getBusy()))
                .as("The application methods that waited")
                .containsExactly("x.Svc.work", "x.Map.get");
        assertThat(digest.getBusy().getRows(0).getShare())
                .as("Shares are of the busy time with an application frame")
                .isEqualTo("0.666667");
        assertThat(keys(digest.getBusyByRoot()))
                .as("Roots are the work, not the executor or the lambda bridge")
                .containsExactly("x.Svc.lambda$go$0", "x.Svc.handle");
        assertThat(keys(digest.getBusyByApplicationMethod()))
                .containsExactly("x.Svc.lambda$go$0 → x.Svc.work", "x.Svc.handle → x.Map.get");
        String application = digest.getWhereTheTimeWent().getBusyApplication().getValue();
        assertThat(application).isEqualTo("3.000");
        for (AnalysisProto.DigestTable table : List.of(digest.getBusy(), digest.getBusyByRoot())) {
            assertThat(sum(table))
                    .as("The %s rows sum to the busy time with an application frame", table.getBy())
                    .isEqualByComparingTo(application);
        }
        assertThat(digest.getBusyWithoutApplicationFrame().getValue()).isEqualTo("10.000");
        assertThat(keys(digest.getBusyNoApplicationFrameByPool())).containsExactly("web-#");
        assertThat(digest.getHeaviestApplicationStacks().getTopList())
                .as("The heaviest application stacks leave the unattributed time out")
                .extracting(AnalysisProto.HeavyStack::getStack)
                .containsExactly(
                        "x.Svc.lambda$go$0;x.Svc.work;j.u.c.l.ReentrantLock.lock",
                        "x.Svc.handle;x.Map.get;j.u.c.l.StampedLock.readLock");

        String markdown = Digest.markdown(digest);
        assertThat(markdown)
                .as("The opening line")
                .contains("Busy with an application frame: 3.000 s, 23.1 % of the 13.000 s busy. Left out of the"
                        + " tables: 10.000 s busy without an application frame and 50.000 s idle");
        List<String> headings = List.of(
                "## Busy, by the application method that waited",
                "## Busy, by application root",
                "## Heaviest application stacks",
                "## Busy, by application method",
                "## Where the time went",
                "## Busy without an application frame, by pool",
                "## How to reproduce");
        List<Integer> positions = headings.stream().map(markdown::indexOf).toList();
        assertThat(positions)
                .as("Every section, in order: %s", headings)
                .doesNotContain(-1)
                .isSorted();
        assertThat(markdown.substring(0, markdown.indexOf("## Busy without an application frame, by pool")))
                .as("The unattributed pool is only in its own table")
                .doesNotContain("web-");
    }

    /** More than half of the busy time without an application frame says the idle patterns probably miss waits. */
    @ParameterizedTest(name = "{0} ns without an application frame")
    @CsvSource({"3001000000, true", "3000000000, false", "1000000000, false"})
    void missingIdleNote(long web, boolean note) throws Exception {
        String markdown = Digest.markdown(Digest.of(profile(web), "run.pb", null, application()));
        assertThat(markdown.contains("More than half of the busy time has no application frame"))
                .as("The opening note with %s ns of 3 s application time", web)
                .isEqualTo(note);
        assertThat(markdown.contains("add the waits for work of its heaviest pools"))
                .as("The pool section's note")
                .isEqualTo(note);
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
                List.of(digest.getBusy(), digest.getBusyByRoot(), digest.getBusyByApplicationMethod())) {
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
        Top.table(digest.getBusyNoApplicationFrameByPool().getRowsList(), "pool", "s", "Intervals", true, pools);
        assertThat(CommandLineFixture.invoke(
                                CommandLineFixture.words(digest.getBusy().getCommand()))
                        .out())
                .as("The waiting-method command also prints the pool table")
                .contains(pools);

        List<String> stacks = new ArrayList<>(List.of(
                CommandLineFixture.words(digest.getHeaviestApplicationStacks().getCommand())));
        Path collapsed = dir.resolve("busy-app.collapsed");
        stacks.set(stacks.indexOf("busy-app.collapsed"), collapsed.toString());
        assertThat(CommandLineFixture.invoke(stacks.toArray(String[]::new)).code())
                .isZero();
        assertThat(Files.readAllLines(collapsed, StandardCharsets.UTF_8))
                .as("The heaviest stacks' command renders the application-rooted flame graph's lines")
                .containsExactlyInAnyOrder(
                        "x.Svc.lambda$go$0;x.Svc.work;j.u.c.l.ReentrantLock.lock 2000000",
                        "x.Svc.handle;x.Map.get;j.u.c.l.StampedLock.readLock 1000000");
    }
}
