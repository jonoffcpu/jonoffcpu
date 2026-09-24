// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ranked tables and the digest: attribution rules on hand-built entries, formats, totals and the digest files. */
class TopTest {
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
        return new StackProfile.Entry(stack, null, null, OffCpuReason.BLOCKED, 1, thread, intervals, nanos, nanos * 2);
    }

    private static Path profile(Path dir, String name, String sampling, boolean estimate) throws IOException {
        List<StackProfile.Entry> entries = List.of(
                // The boundary is the deepest application frame, past interleaved library frames.
                entry(
                        java(
                                "x.A.m",
                                "y.Lib.n",
                                "x.B.o",
                                "java.util.concurrent.locks.ReentrantLock.lock",
                                "jdk.internal.misc.Unsafe.park"),
                        "pool-1-thread-1",
                        3,
                        3_000_000_000L),
                entry(
                        java("x.A.m", "y.Lib.n", "x.B.o", "C2 Runtime complete_monitor_locking"),
                        "pool-1-thread-2",
                        1,
                        1_000_000_000L),
                // No application frame: listed by pool.
                entry(java("java.lang.Thread.run", "libjvm.so.ZDriver::run"), "ZDriverMinor", 2, 500_000_000L),
                // Idle: listed apart, not under busy; it also waited on a lock, which the over-exclusion check counts.
                entry(
                        java(
                                "x.A.loop",
                                "java.util.concurrent.locks.ReentrantLock.lock",
                                "java.util.concurrent.ThreadPoolExecutor.getTask"),
                        "pool-1-thread-3",
                        5,
                        7_000_000_000L),
                // Recursion: x.R.r appears twice and is counted once by --by method.
                entry(java("x.R.r", "x.R.r", "jdk.internal.misc.Unsafe.park"), "worker-7", 4, 250_000_000L));
        Path path = dir.resolve(name + ".pb");
        new StackProfile(
                        new StackProfile.Header(
                                List.of(new StackProfile.Provenance(name, 1, "", "", sampling, "1", 0, 0, 0, "")),
                                List.of("reason", "thread"),
                                estimate,
                                "{}",
                                "",
                                List.of()),
                        entries)
                .write(path);
        return path;
    }

    static final String PROPORTIONAL =
            "{\"reasons\":[\"blocked\"],\"admission\":{\"policy\":\"proportional\",\"recordAllAboveMicros\":10000}}";
    static final String NONE = "{\"reasons\":[\"blocked\"],\"admission\":{\"policy\":\"none\"}}";

    private static JsonObject json(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("top", "--format", "json"));
        command.addAll(List.of(args));
        CommandLineFixture.Invocation invocation = CommandLineFixture.invoke(command.toArray(String[]::new));
        assertThat(invocation.code()).as("top failed: %s", invocation).isZero();
        return JsonParser.parseString(invocation.out()).getAsJsonObject();
    }

    private static JsonObject row(JsonObject result, String table, int index) {
        return result.getAsJsonArray(table).get(index).getAsJsonObject();
    }

    private static List<String> column(JsonObject result, String table, String field) {
        return result.getAsJsonArray(table).asList().stream()
                .map(row -> row.getAsJsonObject().get(field).getAsString())
                .toList();
    }

    private static String value(JsonObject object) {
        return object.get("value").getAsBigDecimal().toPlainString();
    }

    private static List<String> common(Path profile) {
        return List.of("--profile", profile.toString(), "--app", "^x\\.", "--idle", "getTask$");
    }

    @Test
    void boundaryTable(@TempDir Path dir) throws Exception {
        Path profile = profile(dir, "run", NONE, false);
        JsonObject boundary = json(common(profile).toArray(String[]::new));
        JsonObject first = row(boundary, "rows", 0);
        String boundaryMessage = "Boundary past a library frame, with its blocker: " + first;
        assertThat(first.get("boundary").getAsString()).as(boundaryMessage).isEqualTo("x.B.o");
        assertThat(first.get("blocker").getAsString())
                .as(boundaryMessage)
                .isEqualTo("java.util.concurrent.locks.ReentrantLock.lock");
        assertThat(value(first)).as(boundaryMessage).isEqualTo("3.000");
        assertThat(first.get("intervals").getAsInt()).as(boundaryMessage).isEqualTo(3);
        assertThat(first.get("caller").getAsString()).as(boundaryMessage).isEqualTo("x.A.m");
        assertThat(row(boundary, "rows", 1).get("blocker").getAsString())
                .as("A monitor wait's blocker")
                .isEqualTo("C2 Runtime complete_monitor_locking");
        assertThat(row(boundary, "noApplicationFrame", 0).get("pool").getAsString())
                .as("No application frame goes to the pool table: %s", boundary)
                .isEqualTo("ZDriverMinor");
        assertThat(column(boundary, "idle", "boundary"))
                .as("The idle entry is listed as idle")
                .containsExactly("x.A.loop");
        JsonObject totals = boundary.getAsJsonObject("totals");
        String totalsMessage =
                "Totals add up: busy and idle make the selection, rows and pools make the busy: " + totals;
        assertThat(value(totals.getAsJsonObject("selected"))).as(totalsMessage).isEqualTo("11.750");
        assertThat(value(totals.getAsJsonObject("idle"))).as(totalsMessage).isEqualTo("7.000");
        assertThat(value(totals.getAsJsonObject("busy"))).as(totalsMessage).isEqualTo("4.750");
        assertThat(value(totals.getAsJsonObject("busyApplication")))
                .as(totalsMessage)
                .isEqualTo("4.250");
        assertThat(value(totals.getAsJsonObject("busyNoApplicationFrame")))
                .as(totalsMessage)
                .isEqualTo("0.500");
        assertThat(totals.getAsJsonObject("overExclusion").get("entries").getAsInt())
                .as(totalsMessage)
                .isEqualTo(1);
        assertThat(column(boundary, "rows", "boundary"))
                .as("An idle entry is never busy")
                .doesNotContain("x.A.loop");
        assertThat(row(boundary, "rows", 0).has("estimated"))
                .as("No estimate column without an estimate")
                .isFalse();
        CommandLineFixture.usageError("--by boundary needs --app", "top", "--profile", profile.toString());
    }

    @Test
    void otherGroupings(@TempDir Path dir) throws Exception {
        Path profile = profile(dir, "run", NONE, false);
        JsonObject method = json("--profile", profile.toString(), "--by", "method", "--idle", "getTask$");
        for (var element : method.getAsJsonArray("rows")) {
            JsonObject row = element.getAsJsonObject();
            if (row.get("key").getAsString().equals("x.R.r")) {
                assertThat(row.get("intervals").getAsInt())
                        .as("Recursion counts once: %s", row)
                        .isEqualTo(4);
                assertThat(value(row)).as("Recursion counts once: %s", row).isEqualTo("0.250");
            }
        }
        JsonObject classes = json("--profile", profile.toString(), "--by", "class", "--limit", "50");
        assertThat(column(classes, "rows", "key"))
                .as("--by class counts Java frames only")
                .noneMatch(key -> key.startsWith("libjvm"));
        JsonObject self = json(
                "--profile", profile.toString(), "--by", "self", "--collapse-leaf-from", "preset:jvm-wait-machinery");
        assertThat(column(self, "rows", "key"))
                .as("--by self uses the collapsed leaf")
                .satisfiesAnyOf(
                        keys -> assertThat(keys.get(0)).isEqualTo("x.A.loop"),
                        keys -> assertThat(List.<String>copyOf(keys))
                                .contains("java.util.concurrent.locks.ReentrantLock.lock"));
        JsonObject pools = json("--profile", profile.toString(), "--by", "pool", "--idle", "getTask$");
        assertThat(row(pools, "rows", 0).get("key").getAsString())
                .as("Pools: %s", pools)
                .isEqualTo("pool-#-thread-#");
    }

    /** Every format carries the same rows. */
    @Test
    void formats(@TempDir Path dir) throws Exception {
        Path profile = profile(dir, "run", NONE, false);
        List<String> common = common(profile);
        JsonObject boundary = json(common.toArray(String[]::new));
        List<String> mdArgs = new ArrayList<>(List.of("top", "--format", "md"));
        mdArgs.addAll(common);
        String markdown =
                CommandLineFixture.invoke(mdArgs.toArray(String[]::new)).out();
        assertThat(markdown)
                .contains("| 1 | `x.B.o` | `java.util.concurrent.locks.ReentrantLock.lock` | 3.000 |")
                .contains("Reproduce: `java -jar jonoffcpu-correlator.jar top --format md --profile ");
        List<String> csvArgs = new ArrayList<>(List.of("top", "--format", "csv"));
        csvArgs.addAll(common);
        List<String> csv = CommandLineFixture.invoke(csvArgs.toArray(String[]::new))
                .out()
                .lines()
                .toList();
        assertThat(csv)
                .as("CSV")
                .hasSize(1
                        + boundary.getAsJsonArray("rows").size()
                        + boundary.getAsJsonArray("noApplicationFrame").size()
                        + boundary.getAsJsonArray("idle").size());
        assertThat(csv.get(1)).startsWith("rows,1,x.B.o,java.util.concurrent.locks.ReentrantLock.lock,3.000,");
    }

    /** Estimated weights need the estimate; a comparison of sampled runs without estimates warns. */
    @Test
    void estimatedWeightsAndComparison(@TempDir Path dir) throws Exception {
        Path profile = profile(dir, "run", NONE, false);
        assertThatThrownBy(() -> CommandLineFixture.invoke(
                        "top", "--profile", profile.toString(), "--app", "^x\\.", "--weights", "estimated"))
                .as("--weights estimated must be refused without an estimate")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("estimate is unavailable");
        Path sampled = profile(dir, "sampled", PROPORTIONAL, false);
        Path baseline = profile(dir, "baseline", PROPORTIONAL, false);
        JsonObject compared = json(
                "--profile",
                sampled.toString(),
                "--baseline",
                baseline.toString(),
                "--units",
                "2",
                "--baseline-units",
                "4",
                "--app",
                "^x\\.",
                "--idle",
                "getTask$");
        JsonObject top = row(compared, "comparison", 0);
        assertThat(top.get("boundary").getAsString())
                .as("Seconds per unit: %s", top)
                .isEqualTo("x.B.o");
        assertThat(value(top)).as("Seconds per unit: %s", top).isEqualTo("2.000");
        assertThat(top.get("baseline").getAsBigDecimal().toPlainString())
                .as("Seconds per unit: %s", top)
                .isEqualTo("1.000");
        assertThat(compared.getAsJsonArray("warnings").toString())
                .as("Sampled runs without estimates warn")
                .contains("length-biased");
        Path estimated = profile(dir, "estimated", PROPORTIONAL, true);
        Path estimatedBaseline = profile(dir, "estimated-baseline", PROPORTIONAL, true);
        CommandLineFixture.usageError(
                "compare with --weights estimated",
                "top",
                "--profile",
                estimated.toString(),
                "--baseline",
                estimatedBaseline.toString(),
                "--app",
                "^x\\.");
    }

    /** The digest: written from its JSON, byte-stable, and with the reproduce commands. */
    @Test
    void digest(@TempDir Path dir) throws Exception {
        Path profile = profile(dir, "run", NONE, false);
        Path firstDigest = dir.resolve("digest-1");
        Path secondDigest = dir.resolve("digest-2");
        for (Path output : List.of(firstDigest, secondDigest)) {
            CommandLineFixture.Invocation invocation = CommandLineFixture.invoke(
                    "summarize", "--profile", profile.toString(), "--app", "^x\\.", "--output-dir", output.toString());
            assertThat(invocation.code()).as("summarize failed: %s", invocation).isZero();
        }
        for (String name : List.of(OutputFiles.SUMMARY_JSON, OutputFiles.SUMMARY_MD)) {
            assertThat(firstDigest.resolve(name))
                    .as("%s must be byte-stable", name)
                    .hasSameBinaryContentAs(secondDigest.resolve(name));
        }
        JsonObject digest = JsonParser.parseString(Files.readString(firstDigest.resolve(OutputFiles.SUMMARY_JSON)))
                .getAsJsonObject();
        String markdown = Files.readString(firstDigest.resolve(OutputFiles.SUMMARY_MD));
        assertThat(Digest.markdown(digest))
                .as("The Markdown must be rendered from the JSON")
                .isEqualTo(markdown);
        assertThat(digest.get("schemaVersion").getAsInt())
                .as("Digest schema version")
                .isEqualTo(1);
        JsonArray idleRows = digest.getAsJsonObject("idle").getAsJsonArray("rows");
        assertThat(idleRows.asList())
                .as("The default idle preset recognises getTask")
                .hasSize(1);
        assertThat(markdown).as("The digest says how to reproduce each table").contains("## How to reproduce");
    }
}
