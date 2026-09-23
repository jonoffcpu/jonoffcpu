// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Ranked tables and the digest: attribution rules on hand-built entries, formats, totals and the digest files. */
public final class TopTest {
    private TopTest() {}

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

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
        CommandLineTest.Invocation invocation = CommandLineTest.invoke(command.toArray(String[]::new));
        check(invocation.code() == 0, "top failed: " + invocation);
        return JsonParser.parseString(invocation.out()).getAsJsonObject();
    }

    private static JsonObject row(JsonObject result, String table, int index) {
        return result.getAsJsonArray(table).get(index).getAsJsonObject();
    }

    private static String value(JsonObject object) {
        return object.get("value").getAsBigDecimal().toPlainString();
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jonoffcpu-top-test-");
        try {
            Path profile = profile(dir, "run", NONE, false);
            List<String> common = List.of("--profile", profile.toString(), "--app", "^x\\.", "--idle", "getTask$");
            JsonObject boundary = json(common.toArray(String[]::new));
            JsonObject first = row(boundary, "rows", 0);
            check(
                    first.get("boundary").getAsString().equals("x.B.o")
                            && first.get("blocker")
                                    .getAsString()
                                    .equals("java.util.concurrent.locks.ReentrantLock.lock")
                            && value(first).equals("3.000")
                            && first.get("intervals").getAsInt() == 3
                            && first.get("caller").getAsString().equals("x.A.m"),
                    "Boundary past a library frame, with its blocker: " + first);
            check(
                    row(boundary, "rows", 1).get("blocker").getAsString().equals("C2 Runtime complete_monitor_locking"),
                    "A monitor wait's blocker: " + row(boundary, "rows", 1));
            check(
                    row(boundary, "noApplicationFrame", 0)
                            .get("pool")
                            .getAsString()
                            .equals("ZDriverMinor"),
                    "No application frame goes to the pool table: " + boundary);
            check(
                    boundary.getAsJsonArray("idle").size() == 1
                            && row(boundary, "idle", 0)
                                    .get("boundary")
                                    .getAsString()
                                    .equals("x.A.loop"),
                    "The idle entry is listed as idle: " + boundary.getAsJsonArray("idle"));
            JsonObject totals = boundary.getAsJsonObject("totals");
            check(
                    value(totals.getAsJsonObject("selected")).equals("11.750")
                            && value(totals.getAsJsonObject("idle")).equals("7.000")
                            && value(totals.getAsJsonObject("busy")).equals("4.750")
                            && value(totals.getAsJsonObject("busyApplication")).equals("4.250")
                            && value(totals.getAsJsonObject("busyNoApplicationFrame"))
                                    .equals("0.500")
                            && totals.getAsJsonObject("overExclusion")
                                            .get("entries")
                                            .getAsInt()
                                    == 1,
                    "Totals add up: busy and idle make the selection, rows and pools make the busy: " + totals);
            check(
                    boundary.getAsJsonArray("rows").asList().stream()
                            .noneMatch(row -> row.getAsJsonObject()
                                    .get("boundary")
                                    .getAsString()
                                    .equals("x.A.loop")),
                    "An idle entry is never busy");
            check(row(boundary, "rows", 0).has("estimated") == false, "No estimate column without an estimate");

            JsonObject method = json("--profile", profile.toString(), "--by", "method", "--idle", "getTask$");
            for (var element : method.getAsJsonArray("rows")) {
                JsonObject row = element.getAsJsonObject();
                if (row.get("key").getAsString().equals("x.R.r")) {
                    check(
                            row.get("intervals").getAsInt() == 4 && value(row).equals("0.250"),
                            "Recursion counts once: " + row);
                }
            }
            JsonObject classes = json("--profile", profile.toString(), "--by", "class", "--limit", "50");
            check(
                    classes.getAsJsonArray("rows").asList().stream()
                            .noneMatch(row -> row.getAsJsonObject()
                                    .get("key")
                                    .getAsString()
                                    .startsWith("libjvm")),
                    "--by class counts Java frames only: " + classes);
            JsonObject self = json(
                    "--profile",
                    profile.toString(),
                    "--by",
                    "self",
                    "--collapse-leaf-from",
                    "preset:jvm-wait-machinery");
            check(
                    row(self, "rows", 0).get("key").getAsString().equals("x.A.loop")
                            || self.getAsJsonArray("rows").asList().stream()
                                    .anyMatch(row -> row.getAsJsonObject()
                                            .get("key")
                                            .getAsString()
                                            .equals("java.util.concurrent.locks.ReentrantLock.lock")),
                    "--by self uses the collapsed leaf: " + self);
            JsonObject pools = json("--profile", profile.toString(), "--by", "pool", "--idle", "getTask$");
            check(row(pools, "rows", 0).get("key").getAsString().equals("pool-#-thread-#"), "Pools: " + pools);

            // Every format carries the same rows.
            List<String> mdArgs = new ArrayList<>(List.of("top", "--format", "md"));
            mdArgs.addAll(common);
            String markdown =
                    CommandLineTest.invoke(mdArgs.toArray(String[]::new)).out();
            check(
                    markdown.contains("| 1 | `x.B.o` | `java.util.concurrent.locks.ReentrantLock.lock` | 3.000 |"),
                    markdown);
            check(
                    markdown.contains("Reproduce: `java -jar jonoffcpu-correlator.jar top --format md --profile "),
                    markdown);
            List<String> csvArgs = new ArrayList<>(List.of("top", "--format", "csv"));
            csvArgs.addAll(common);
            List<String> csv = CommandLineTest.invoke(csvArgs.toArray(String[]::new))
                    .out()
                    .lines()
                    .toList();
            check(
                    csv.size()
                                    == 1
                                            + boundary.getAsJsonArray("rows").size()
                                            + boundary.getAsJsonArray("noApplicationFrame")
                                                    .size()
                                            + boundary.getAsJsonArray("idle").size()
                            && csv.get(1)
                                    .startsWith("rows,1,x.B.o,java.util.concurrent.locks.ReentrantLock.lock,3.000,"),
                    "CSV: " + csv);

            // Estimated weights need the estimate; a comparison of sampled runs without estimates warns.
            CommandLineTest.Invocation refused = null;
            try {
                refused = CommandLineTest.invoke(
                        "top", "--profile", profile.toString(), "--app", "^x\\.", "--weights", "estimated");
            } catch (IOException expected) {
                check(expected.getMessage().contains("estimate is unavailable"), "Unexpected: " + expected);
            }
            check(refused == null, "--weights estimated must be refused without an estimate");
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
            check(
                    top.get("boundary").getAsString().equals("x.B.o")
                            && top.get("value")
                                    .getAsBigDecimal()
                                    .toPlainString()
                                    .equals("2.000")
                            && top.get("baseline")
                                    .getAsBigDecimal()
                                    .toPlainString()
                                    .equals("1.000"),
                    "Seconds per unit: " + top);
            check(
                    compared.getAsJsonArray("warnings").toString().contains("length-biased"),
                    "Sampled runs without estimates warn: " + compared.getAsJsonArray("warnings"));
            Path estimated = profile(dir, "estimated", PROPORTIONAL, true);
            Path estimatedBaseline = profile(dir, "estimated-baseline", PROPORTIONAL, true);
            CommandLineTest.usageError(
                    "compare with --weights estimated",
                    "top",
                    "--profile",
                    estimated.toString(),
                    "--baseline",
                    estimatedBaseline.toString(),
                    "--app",
                    "^x\\.");
            CommandLineTest.usageError("--by boundary needs --app", "top", "--profile", profile.toString());

            // The digest: written from its JSON, byte-stable, and with the reproduce commands.
            Path firstDigest = dir.resolve("digest-1");
            Path secondDigest = dir.resolve("digest-2");
            for (Path output : List.of(firstDigest, secondDigest)) {
                CommandLineTest.Invocation invocation = CommandLineTest.invoke(
                        "summarize",
                        "--profile",
                        profile.toString(),
                        "--app",
                        "^x\\.",
                        "--output-dir",
                        output.toString());
                check(invocation.code() == 0, "summarize failed: " + invocation);
            }
            for (String name : List.of(OutputFiles.SUMMARY_JSON, OutputFiles.SUMMARY_MD)) {
                check(
                        Arrays.equals(
                                Files.readAllBytes(firstDigest.resolve(name)),
                                Files.readAllBytes(secondDigest.resolve(name))),
                        name + " must be byte-stable");
            }
            JsonObject digest = JsonParser.parseString(Files.readString(firstDigest.resolve(OutputFiles.SUMMARY_JSON)))
                    .getAsJsonObject();
            check(
                    Digest.markdown(digest).equals(Files.readString(firstDigest.resolve(OutputFiles.SUMMARY_MD))),
                    "The Markdown must be rendered from the JSON");
            check(digest.get("schemaVersion").getAsInt() == 1, "Digest schema version");
            JsonArray idleRows = digest.getAsJsonObject("idle").getAsJsonArray("rows");
            check(idleRows.size() == 1, "The default idle preset recognises getTask: " + idleRows);
            check(
                    Files.readString(firstDigest.resolve(OutputFiles.SUMMARY_MD))
                            .contains("## How to reproduce"),
                    "The digest says how to reproduce each table");
            System.out.println("Top and digest fixtures passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
