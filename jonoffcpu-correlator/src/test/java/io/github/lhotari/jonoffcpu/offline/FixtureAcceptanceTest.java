// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The reference numbers the 0.5.0 specs were written against, measured on recordings of an Apache Pulsar broker. The
 * recordings are not part of the repository; run with {@code -Djonoffcpu.fixtures=DIR} (Gradle:
 * {@code -PjonoffcpuFixtures=DIR}) pointing at a directory holding {@code pulsar-broker-2026-09-23-wolfi/} and
 * {@code pulsar-broker-2026-09-23-wolfi-cpu/}, and it is skipped otherwise.
 */
public final class FixtureAcceptanceTest {
    static final String IDLE_BOOKKEEPER =
            "^org\\.apache\\.bookkeeper\\.common\\.collections\\.[\\w$]*BlockingQueue\\.take(All)?$";
    static final String APP = "^org\\.apache\\.";

    private FixtureAcceptanceTest() {}

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** One row of the transforms reference table: the options, then lines and depth for CPU and off-CPU. */
    record Row(String name, List<String> options, int cpuLines, String cpuDepth, int offLines, String offDepth) {}

    static final List<Row> TRANSFORMS = List.of(
            new Row("none", List.of(), 9_839, "28.9", 164, "23.0"),
            new Row("hide", List.of("--hide-from", "preset:jvm-infra"), 9_605, "20.7", 157, "17.6"),
            new Row("trim-root", List.of("--trim-root-from", "preset:jvm-infra"), 9_829, "21.2", 164, "18.4"),
            new Row(
                    "collapse-leaf",
                    List.of("--collapse-leaf-from", "preset:jvm-wait-machinery"),
                    7_392,
                    "24.1",
                    109,
                    "9.6"),
            new Row(
                    "trim-root+collapse-leaf",
                    List.of(
                            "--trim-root-from",
                            "preset:jvm-infra",
                            "--collapse-leaf-from",
                            "preset:jvm-wait-machinery"),
                    7_381,
                    "16.4",
                    109,
                    "5.0"),
            new Row("root-at", List.of("--root-at", APP), 5_231, "9.8", 79, "8.2"),
            new Row(
                    "root-at+collapse-leaf",
                    List.of("--root-at", APP, "--collapse-leaf-from", "preset:jvm-wait-machinery"),
                    4_925,
                    "9.6",
                    78,
                    "4.1"),
            new Row("root-at+leaf-at", List.of("--root-at", APP, "--leaf-at", APP), 1_234, "7.3", 66, "3.6"));

    /** Lines and weight-averaged depth of a collapsed file, as the reference table counts them. */
    static String shape(Path collapsed) throws Exception {
        List<String> lines = Files.readAllLines(collapsed, StandardCharsets.UTF_8);
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal frames = BigDecimal.ZERO;
        for (String line : lines) {
            int space = line.lastIndexOf(' ');
            BigDecimal value = new BigDecimal(line.substring(space + 1));
            weight = weight.add(value);
            frames = frames.add(
                    value.multiply(BigDecimal.valueOf(line.substring(0, space).split(";", -1).length)));
        }
        return lines.size() + " lines at " + frames.divide(weight, 1, java.math.RoundingMode.HALF_EVEN);
    }

    static Path run(Path output, List<String> args) throws Exception {
        List<String> command = new ArrayList<>(args);
        command.addAll(List.of("--output", output.toString()));
        CommandLineTest.Invocation invocation = CommandLineTest.invoke(command.toArray(String[]::new));
        check(invocation.code() == 0, "Failed: " + args + ": " + invocation);
        return output;
    }

    private static void transforms(Path fixtures, Path dir) throws Exception {
        Path cpu = fixtures.resolve("pulsar-broker-2026-09-23-wolfi-cpu/broker-cpu.collapsed");
        Path profile = fixtures.resolve("pulsar-broker-2026-09-23-wolfi/jonoffcpu-offcpu-profile.pb");
        for (int index = 0; index < TRANSFORMS.size(); index++) {
            Row row = TRANSFORMS.get(index);
            List<String> cpuArgs = new ArrayList<>(List.of("stacks", "--collapsed-input", cpu.toString()));
            cpuArgs.addAll(row.options());
            String cpuShape = shape(run(dir.resolve("cpu-" + index + ".collapsed"), cpuArgs));
            List<String> offArgs = new ArrayList<>(List.of(
                    "stacks",
                    "--profile",
                    profile.toString(),
                    "--exclude-from",
                    "preset:jvm-idle",
                    "--exclude",
                    IDLE_BOOKKEEPER));
            offArgs.addAll(row.options());
            String offShape = shape(run(dir.resolve("off-" + index + ".collapsed"), offArgs));
            String expectedCpu = row.cpuLines() + " lines at " + row.cpuDepth();
            String expectedOff = row.offLines() + " lines at " + row.offDepth();
            check(
                    cpuShape.equals(expectedCpu) && offShape.equals(expectedOff),
                    row.name() + ": CPU " + cpuShape + " (expected " + expectedCpu + "), off-CPU " + offShape
                            + " (expected " + expectedOff + ")");
            System.out.println(row.name() + ": CPU " + cpuShape + ", off-CPU " + offShape);
        }
    }

    /** The boundary ranking recipe of the README's "Analyzing with SQL", on the 0.5.0 export. */
    static final String BOUNDARY_SQL = """
            CREATE TEMP TABLE entries AS
            SELECT javaFrames AS frames, observedNanos AS nanos, intervals
            FROM read_json('%s', format = 'newline_delimited');

            SELECT coalesce(list_filter(frames, lambda f: regexp_matches(f, '^org\\.apache\\.'))[-1],
                            '[no application frame]') AS boundary,
                   round(sum(nanos) / 1e9, 3) AS seconds, sum(intervals) AS intervals
            FROM entries
            WHERE NOT list_bool_or(list_transform(frames, lambda f: regexp_matches(f,
                  '^(io\\.netty\\.channel\\.epoll\\.Native\\.epollWait0?|java\\.util\\.concurrent\\.ThreadPoolExecutor\\.getTask|sun\\.nio\\.ch\\.SelectorImpl\\.select|java\\.util\\.concurrent\\.ForkJoinPool\\.awaitWork)$|BlockingQueue\\.take(All)?$|^java\\.lang\\.ref\\.|^libasyncProfiler\\.so\\.')))
            GROUP BY 1 ORDER BY 2 DESC LIMIT 3;
            """;

    /** The export's reference numbers, and the SQL recipe when DuckDB is on the path. */
    private static void export(Path fixtures, Path dir) throws Exception {
        Path profile = fixtures.resolve("pulsar-broker-2026-09-23-wolfi/jonoffcpu-offcpu-profile.pb");
        Path jsonl = dir.resolve("wolfi.jsonl");
        Path metadata = dir.resolve("wolfi-run.json");
        CommandLineTest.Invocation invocation = CommandLineTest.invoke(
                "export",
                "--profile",
                profile.toString(),
                "--format",
                "jsonl",
                "--output",
                jsonl.toString(),
                "--run-metadata",
                metadata.toString());
        check(invocation.code() == 0, "Export failed: " + invocation);
        List<String> rows = Files.readAllLines(jsonl, StandardCharsets.UTF_8);
        check(rows.size() == 2_791, "2,791 rows, got " + rows.size());
        long observed = 0;
        java.util.regex.Pattern address = java.util.regex.Pattern.compile("(Lambda|LambdaForm\\$D?MH)[./]0x");
        for (String line : rows) {
            var row = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            List<String> frames = new ArrayList<>();
            row.getAsJsonArray("javaFrames").forEach(frame -> frames.add(frame.getAsString()));
            check(String.join(";", frames).equals(row.get("javaStack").getAsString()), "javaFrames must join: " + line);
            check(row.getAsJsonArray("javaFrameKinds").size() == frames.size(), "One kind per frame: " + line);
            check(!address.matcher(row.get("canonicalJavaStack").getAsString()).find(), "Canonical: " + line);
            observed += row.get("observedNanos").getAsLong();
        }
        check(observed == 8_519_334_220_784L, "Observed nanoseconds " + observed);
        var run = com.google.gson.JsonParser.parseString(Files.readString(metadata))
                .getAsJsonObject();
        check(run.get("observedNanos").getAsLong() == observed, "Run metadata total: " + run);
        System.out.println("export: 2791 rows, " + observed + " observed ns");

        Path duckdb = ExportTest.duckdb();
        if (duckdb == null) {
            System.out.println("Skipping the DuckDB recipes: duckdb is not on the path");
            return;
        }
        String types = ExportTest.duckdb(
                duckdb,
                "DESCRIBE SELECT javaFrames, observedNanos FROM read_json('" + jsonl
                        + "', format = 'newline_delimited');");
        check(
                types.contains("javaFrames,VARCHAR[]") && types.contains("observedNanos,BIGINT"),
                "DuckDB must infer the frame arrays and numeric counters: " + types);
        String boundaries = ExportTest.duckdb(duckdb, BOUNDARY_SQL.formatted(jsonl));
        check(
                boundaries.contains(
                                "org.apache.pulsar.broker.service.persistent.PersistentDispatcherMultipleConsumers.internalConsumerFlow,11.982,4245")
                        && boundaries.contains(
                                "org.apache.bookkeeper.common.collections.GrowableBatchedArrayBlockingQueue.offer,4.946,1565"),
                "The boundary recipe: " + boundaries);
        System.out.println("DuckDB recipes reproduce the reference rows");
    }

    public static void main(String[] args) throws Exception {
        String fixtures = System.getProperty("jonoffcpu.fixtures", "");
        if (fixtures.isEmpty()) {
            System.out.println("Skipping the fixture acceptance checks: -Djonoffcpu.fixtures is not set");
            return;
        }
        Path dir = Files.createTempDirectory("jonoffcpu-fixture-acceptance-");
        try {
            transforms(Path.of(fixtures), dir);
            export(Path.of(fixtures), dir);
            System.out.println("Fixture acceptance checks passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
