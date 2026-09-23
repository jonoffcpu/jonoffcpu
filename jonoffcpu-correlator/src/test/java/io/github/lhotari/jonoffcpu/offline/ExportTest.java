// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The SQL-friendly export: frame arrays, canonical stacks, pools, the run column, numeric counters, run metadata. */
public final class ExportTest {
    private ExportTest() {}

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static StackProfile.Frame frame(StackProfile.Kind kind, String name) {
        return new StackProfile.Frame(kind, name, kind == StackProfile.Kind.USER ? "libc.so.6" : "");
    }

    private static List<JsonObject> jsonl(Path file) throws Exception {
        List<JsonObject> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file))
            rows.add(JsonParser.parseString(line).getAsJsonObject());
        return rows;
    }

    private static Path export(Path dir, String name, Path profile, String... extra) throws Exception {
        Path output = dir.resolve(name);
        List<String> args =
                new ArrayList<>(List.of("export", "--profile", profile.toString(), "--output", output.toString()));
        args.addAll(List.of(extra));
        CommandLineTest.Invocation invocation = CommandLineTest.invoke(args.toArray(String[]::new));
        check(invocation.code() == 0, "Export failed: " + invocation);
        return output;
    }

    /**
     * The DuckDB executable on the path, or null. With {@code JONOFFCPU_REQUIRE_DUCKDB=true}, as CI sets it, a
     * missing DuckDB fails instead of skipping the SQL checks.
     */
    static Path duckdb() {
        for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(directory, "duckdb");
            if (Files.isExecutable(candidate)) return candidate;
        }
        check(
                !"true".equals(System.getenv("JONOFFCPU_REQUIRE_DUCKDB")),
                "JONOFFCPU_REQUIRE_DUCKDB is set but duckdb is not on the path");
        return null;
    }

    /** Runs SQL in a fresh in-memory DuckDB and returns its CSV output. */
    static String duckdb(Path duckdb, String sql) throws Exception {
        Process process = new ProcessBuilder(duckdb.toString(), "-csv", "-c", sql)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(process.waitFor() == 0, "duckdb failed: " + output);
        return output;
    }

    /** DuckDB reads the JSON Lines export without a schema: frames as VARCHAR[], counters as BIGINT. */
    private static void readByDuckDb(Path dir) throws Exception {
        Path duckdb = duckdb();
        if (duckdb == null) {
            System.out.println("Skipping the DuckDB checks: duckdb is not on the path");
            return;
        }
        List<StackProfile.Entry> entries = List.of(
                new StackProfile.Entry(
                        List.of(
                                frame(StackProfile.Kind.JAVA, "x.App.run"),
                                frame(StackProfile.Kind.JAVA, "x.App.wait")),
                        null,
                        null,
                        OffCpuReason.BLOCKED,
                        1,
                        "worker-1",
                        3,
                        3_000_000_000L,
                        0),
                new StackProfile.Entry(
                        List.of(
                                frame(StackProfile.Kind.JAVA, "y.Idle.run"),
                                frame(StackProfile.Kind.JAVA, "x.App.wait")),
                        null,
                        null,
                        OffCpuReason.BLOCKED,
                        1,
                        "worker-2",
                        2,
                        1_500_000_000L,
                        0));
        Path profile = dir.resolve("sql.pb");
        new StackProfile(
                        new StackProfile.Header(List.of(), List.of("reason", "thread"), false, "{}", "", List.of()),
                        entries)
                .write(profile);
        Path rows = export(dir, "sql.jsonl", profile, "--format", "jsonl", "--run-label", "a");
        String types = duckdb(
                duckdb,
                "DESCRIBE SELECT javaFrames, javaFrameKinds, observedNanos, run, estimateAvailable FROM read_json('"
                        + rows + "', format = 'newline_delimited');");
        for (String expected : List.of(
                "javaFrames,VARCHAR[]",
                "javaFrameKinds,VARCHAR[]",
                "observedNanos,BIGINT",
                "run,VARCHAR",
                "estimateAvailable,BOOLEAN")) {
            check(types.contains(expected), "DuckDB must infer " + expected + ": " + types);
        }
        String boundaries = duckdb(
                duckdb,
                "SELECT list_filter(javaFrames, lambda f: regexp_matches(f, '^x\\.'))[1] AS boundary,"
                        + " sum(observedNanos) / 1e9 AS seconds, sum(intervals) AS intervals FROM read_json('" + rows
                        + "', format = 'newline_delimited') GROUP BY 1 ORDER BY 1;");
        check(boundaries.contains("x.App.run,3.0,3") && boundaries.contains("x.App.wait,1.5,2"), boundaries);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jonoffcpu-export-test-");
        try {
            var java = StackProfile.Kind.JAVA;
            var jfrNative = StackProfile.Kind.JFR_NATIVE;
            List<StackProfile.Entry> entries = List.of(
                    new StackProfile.Entry(
                            List.of(
                                    frame(java, "a.B$$Lambda.0x0000000081a06030.run"),
                                    frame(jfrNative, "libjvm.so.Unsafe_Park")),
                            List.of(
                                    frame(StackProfile.Kind.KERNEL, "__schedule+0x1f"),
                                    frame(StackProfile.Kind.KERNEL, "__traceiter_sched_switch+0x3")),
                            List.of(frame(StackProfile.Kind.USER, "futex_wait+0x10")),
                            OffCpuReason.BLOCKED,
                            1,
                            "pulsar-io-3-25",
                            2,
                            2000,
                            4000),
                    new StackProfile.Entry(
                            List.of(frame(java, "x.App.main")),
                            null,
                            null,
                            OffCpuReason.BLOCKED,
                            1,
                            "[tid=12345]",
                            1,
                            // Past 2^53 - 1, a JSON number would lose precision, so it stays a string.
                            (1L << 53) + 1,
                            0));
            Path profile = dir.resolve("profile.pb");
            List<StackProfile.Provenance> sources = List.of(new StackProfile.Provenance(
                    "session-1", 7, "", "", "{\"reasons\":[\"blocked\"]}", "1", 0, 10, 20, ""));
            new StackProfile(
                            new StackProfile.Header(
                                    sources, List.of("reason", "kernel", "user", "thread"), false, "{}", "", List.of()),
                            entries)
                    .write(profile);

            List<JsonObject> rows = jsonl(export(dir, "rows.jsonl", profile, "--format", "jsonl"));
            JsonObject first = rows.get(0);
            check(
                    first.getAsJsonArray("javaFrames")
                            .toString()
                            .equals("[\"a.B$$Lambda.0x0000000081a06030.run\",\"libjvm.so.Unsafe_Park\"]"),
                    "Java frames as an array: " + first);
            check(first.getAsJsonArray("javaFrameKinds").toString().equals("[\"java\",\"native\"]"), "Kinds: " + first);
            check(
                    first.getAsJsonArray("kernelFrames").toString().equals("[\"__schedule\"]")
                            && first.getAsJsonArray("userFrames").toString().equals("[\"futex_wait\"]"),
                    "Native frames as the joined columns render them: " + first);
            check(
                    first.get("canonicalJavaStack").getAsString().equals("a.B$$Lambda.run;libjvm.so.Unsafe_Park"),
                    "Canonical stack: " + first);
            check(first.get("threadPool").getAsString().equals("pulsar-io-#-#"), "Pool: " + first);
            check(rows.get(1).get("threadPool").getAsString().equals("[tid=#]"), "Pool of a thread id");
            check(
                    first.get("observedNanos").getAsJsonPrimitive().isNumber()
                            && first.get("observedNanos").getAsLong() == 2000,
                    "Counters are numbers: " + first);
            check(
                    rows.get(1).get("observedNanos").getAsJsonPrimitive().isString()
                            && rows.get(1).get("observedNanos").getAsString().equals(Long.toString((1L << 53) + 1)),
                    "Past 2^53 - 1 a counter stays a string: " + rows.get(1));
            check(
                    first.get("run").getAsString().equals("session-1")
                            && !first.get("estimateAvailable").getAsBoolean(),
                    "Run defaults to the first session; the estimate's validity is on every row: " + first);
            check(rows.get(1).get("kernelFrames").isJsonNull(), "An absent stack is null");
            // Existing fields keep their names and order; the new ones follow them.
            List<String> names = new ArrayList<>(first.keySet());
            check(
                    names.subList(0, 3).equals(List.of("reason", "taskState", "thread"))
                            && names.indexOf("javaStackKinds") == 15
                            && names.get(names.size() - 1).equals("estimateAvailable"),
                    "Field order: " + names);

            List<JsonObject> strings =
                    jsonl(export(dir, "strings.jsonl", profile, "--format", "jsonl", "--numbers", "string"));
            check(strings.get(0).get("observedNanos").getAsJsonPrimitive().isString(), "--numbers string");

            Path metadata = dir.resolve("run.json");
            List<String> csv = Files.readAllLines(
                    export(dir, "rows.csv", profile, "--run-label", "baseline", "--run-metadata", metadata.toString()));
            check(csv.get(0).endsWith(",canonical_java_stack,thread_pool,run,estimate_available"), csv.get(0));
            check(
                    csv.get(1).endsWith(",a.B$$Lambda.run;libjvm.so.Unsafe_Park,pulsar-io-#-#,baseline,false"),
                    "CSV row: " + csv.get(1));
            JsonObject run = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
            JsonArray runSources = run.getAsJsonArray("sources");
            check(
                    run.get("run").getAsString().equals("baseline")
                            && run.get("entries").getAsInt() == 2
                            && run.get("intervals").getAsInt() == 3
                            && run.get("observedNanos").getAsString().equals(Long.toString((1L << 53) + 2001))
                            && runSources
                                            .get(0)
                                            .getAsJsonObject()
                                            .get("captureEpoch")
                                            .getAsInt()
                                    == 7
                            && runSources
                                            .get(0)
                                            .getAsJsonObject()
                                            .get("windowToNanos")
                                            .getAsLong()
                                    == 20
                            && !run.get("estimateAvailable").getAsBoolean(),
                    "Run metadata: " + run);
            CommandLineTest.usageError(
                    "expected one of number, string",
                    "export",
                    "--profile",
                    profile.toString(),
                    "--output",
                    dir.resolve("refused").toString(),
                    "--numbers",
                    "text");
            readByDuckDb(dir);
            System.out.println("Export fixtures passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
