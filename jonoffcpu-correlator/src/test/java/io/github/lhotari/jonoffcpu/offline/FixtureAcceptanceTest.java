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

    /** The worked example of the top-and-digest spec: rows as boundary, blocker, seconds, intervals. */
    static final List<String> BOUNDARY_ROWS = List.of(
            "org.apache.pulsar.broker.service.persistent.PersistentDispatcherMultipleConsumers.internalConsumerFlow|C2 Runtime complete_monitor_locking|11.982|4245",
            "org.apache.bookkeeper.common.collections.GrowableBatchedArrayBlockingQueue.offer|java.util.concurrent.locks.ReentrantLock.lock|4.946|1565",
            "org.apache.pulsar.broker.service.persistent.MessageDeduplication.isDuplicateNormal|C2 Runtime complete_monitor_locking|0.801|187",
            "org.apache.bookkeeper.mledger.impl.ManagedCursorImpl.isMessageDeleted|java.util.concurrent.locks.ReentrantReadWriteLock$ReadLock.lock|0.621|268",
            "org.apache.bookkeeper.mledger.impl.ManagedCursorImpl.asyncDelete|java.util.concurrent.locks.ReentrantReadWriteLock$WriteLock.lock|0.499|153",
            "org.apache.pulsar.broker.service.PendingAcksMap.addPendingAckIfAllowed|java.util.concurrent.locks.ReentrantReadWriteLock$WriteLock.lock|0.277|95",
            "org.apache.bookkeeper.mledger.impl.ManagedCursorImpl.filterReadEntries|java.util.concurrent.locks.ReentrantReadWriteLock$ReadLock.lock|0.222|112",
            "org.apache.pulsar.broker.service.InMemoryRedeliveryTracker.getRedeliveryCount|java.util.concurrent.locks.StampedLock.readLock|0.217|104",
            "org.apache.pulsar.broker.service.PendingAcksMap.getRemainingUnacked|java.util.concurrent.locks.ReentrantReadWriteLock$ReadLock.lock|0.141|68",
            "org.apache.bookkeeper.mledger.impl.ManagedCursorImpl.updateLastMarkDeleteEntryToLatest|C2 Runtime complete_monitor_locking|0.127|28");

    /** The comparison example: boundary, Alpine s/M, Wolfi s/M, Alpine share, Wolfi share. */
    static final List<String> COMPARISON_ROWS = List.of(
            "org.apache.pulsar.broker.service.persistent.PersistentDispatcherMultipleConsumers.internalConsumerFlow|2.034|2.396|61.4 %|58.2 %",
            "org.apache.bookkeeper.common.collections.GrowableBatchedArrayBlockingQueue.offer|0.735|0.989|22.2 %|24.0 %",
            "org.apache.pulsar.broker.service.persistent.MessageDeduplication.isDuplicateNormal|0.091|0.161|2.8 %|3.9 %",
            "org.apache.bookkeeper.mledger.impl.ManagedCursorImpl.isMessageDeleted|0.116|0.124|3.5 %|3.0 %",
            "org.apache.bookkeeper.mledger.impl.ManagedCursorImpl.asyncDelete|0.064|0.101|1.9 %|2.4 %");

    private static com.google.gson.JsonObject top(List<String> args) throws Exception {
        List<String> command = new ArrayList<>(List.of("top", "--format", "json"));
        command.addAll(args);
        CommandLineTest.Invocation invocation = CommandLineTest.invoke(command.toArray(String[]::new));
        check(invocation.code() == 0, "top failed: " + invocation);
        return com.google.gson.JsonParser.parseString(invocation.out()).getAsJsonObject();
    }

    private static String totals(com.google.gson.JsonObject totals, String slice) {
        var sum = totals.getAsJsonObject(slice);
        return sum.get("entries").getAsLong() + "/" + sum.get("intervals").getAsLong() + "/"
                + sum.get("value").getAsBigDecimal().toPlainString();
    }

    /** The top-and-digest worked example, its comparison, the digest bound, and top agreeing with stacks. */
    private static void top(Path fixtures, Path dir) throws Exception {
        Path wolfi = fixtures.resolve("pulsar-broker-2026-09-23-wolfi/jonoffcpu-offcpu-profile.pb");
        Path alpine = fixtures.resolve("pulsar-broker-2026-09-23-alpine/jonoffcpu-offcpu-profile.pb");
        List<String> options =
                List.of("--app", APP, "--idle-from", "preset:jvm-idle", "--idle", IDLE_BOOKKEEPER, "--limit", "10");
        List<String> args = new ArrayList<>(List.of("--profile", wolfi.toString()));
        args.addAll(options);
        var result = top(args);
        var totals = result.getAsJsonObject("totals");
        check(totals(totals, "selected").equals("2791/308777/8519.334"), "All: " + totals);
        check(totals(totals, "idle").equals("660/301389/8470.366"), "Idle: " + totals);
        check(totals(totals, "busy").equals("2131/7388/48.968"), "Busy: " + totals);
        check(totals(totals, "busyNoApplicationFrame").equals("132/237/28.374"), "No application frame: " + totals);
        check(totals(totals, "overExclusion").equals("18/18/0.047"), "Over-exclusion: " + totals);
        List<String> rows = new ArrayList<>();
        for (var element : result.getAsJsonArray("rows")) {
            var row = element.getAsJsonObject();
            rows.add(
                    row.get("boundary").getAsString() + "|" + row.get("blocker").getAsString() + "|"
                            + row.get("value").getAsBigDecimal().toPlainString() + "|"
                            + row.get("intervals").getAsLong());
        }
        check(rows.equals(BOUNDARY_ROWS), "Busy by boundary:\n" + String.join("\n", rows));
        var pools = result.getAsJsonArray("noApplicationFrame");
        check(
                pools.get(0).getAsJsonObject().get("pool").getAsString().equals("ZDriverMinor")
                        && pools.get(0)
                                .getAsJsonObject()
                                .get("value")
                                .getAsBigDecimal()
                                .toPlainString()
                                .equals("22.517")
                        && pools.get(0).getAsJsonObject().get("intervals").getAsLong() == 88
                        && pools.get(1)
                                .getAsJsonObject()
                                .get("pool")
                                .getAsString()
                                .equals("ZDriverMajor")
                        && pools.get(1)
                                .getAsJsonObject()
                                .get("value")
                                .getAsBigDecimal()
                                .toPlainString()
                                .equals("5.527")
                        && pools.get(1).getAsJsonObject().get("intervals").getAsLong() == 27,
                "Pools: " + pools);
        List<String> idle = new ArrayList<>();
        for (var element : result.getAsJsonArray("idle")) {
            var row = element.getAsJsonObject();
            idle.add(row.get("boundary").getAsString() + " "
                    + row.get("value").getAsBigDecimal().setScale(1, java.math.RoundingMode.HALF_EVEN));
        }
        check(
                idle.subList(0, 5)
                        .equals(List.of(
                                "[no application frame] 7687.8",
                                "org.apache.bookkeeper.common.collections.GrowableBatchedArrayBlockingQueue.internalTakeAll 231.2",
                                "org.apache.pulsar.common.util.collections.GrowableArrayBlockingQueue.take 225.0",
                                "org.apache.zookeeper.ClientCnxnSocketNIO.doTransport 218.2",
                                "org.apache.zookeeper.ClientCnxn$EventThread.run 108.2")),
                "Idle by boundary: " + idle);
        System.out.println("top: the worked example's tables");

        List<String> compare = new ArrayList<>(List.of(
                "--profile",
                wolfi.toString(),
                "--baseline",
                alpine.toString(),
                "--units",
                "5",
                "--baseline-units",
                "5"));
        compare.addAll(options.subList(0, 6));
        compare.addAll(List.of("--limit", "5"));
        var comparison = top(compare);
        List<String> compared = new ArrayList<>();
        for (var element : comparison.getAsJsonArray("comparison")) {
            var row = element.getAsJsonObject();
            compared.add(row.get("boundary").getAsString() + "|"
                    + row.get("baseline").getAsBigDecimal().toPlainString() + "|"
                    + row.get("value").getAsBigDecimal().toPlainString() + "|"
                    + Top.percent(row.get("baselineShare").getAsBigDecimal()) + "|"
                    + Top.percent(row.get("share").getAsBigDecimal()));
        }
        check(compared.equals(COMPARISON_ROWS), "Comparison:\n" + String.join("\n", compared));
        var compareTotals = comparison.getAsJsonObject("totals");
        check(
                compareTotals
                                .get("baselineBusyApplication")
                                .getAsBigDecimal()
                                .toPlainString()
                                .equals("16.574")
                        && compareTotals
                                .get("busyApplication")
                                .getAsBigDecimal()
                                .toPlainString()
                                .equals("20.593")
                        && compareTotals
                                .get("baselineBusy")
                                .getAsBigDecimal()
                                .toPlainString()
                                .equals("2019.121")
                        && compareTotals
                                .get("busy")
                                .getAsBigDecimal()
                                .toPlainString()
                                .equals("48.968"),
                "Comparison totals: " + compareTotals);
        check(comparison.getAsJsonArray("warnings").size() == 2, "Both warnings: " + comparison.get("warnings"));
        System.out.println("top --baseline: the comparison example");

        // top and stacks agree: a boundary's rows add up to the stacks lines that end in it after --leaf-at.
        Path leafAt = run(
                dir.resolve("leaf-at.collapsed"),
                List.of(
                        "stacks",
                        "--profile",
                        wolfi.toString(),
                        "--exclude-from",
                        "preset:jvm-idle",
                        "--exclude",
                        IDLE_BOOKKEEPER,
                        "--leaf-at",
                        APP));
        java.util.Map<String, BigDecimal> leaves = new java.util.HashMap<>();
        for (String line : Files.readAllLines(leafAt)) {
            int space = line.lastIndexOf(' ');
            String stack = line.substring(0, space);
            leaves.merge(
                    stack.substring(stack.lastIndexOf(';') + 1),
                    new BigDecimal(line.substring(space + 1)).movePointLeft(6),
                    BigDecimal::add);
        }
        java.util.Map<String, BigDecimal> boundaries = new java.util.HashMap<>();
        List<String> all = new ArrayList<>(List.of("--profile", wolfi.toString()));
        all.addAll(options.subList(0, 6));
        all.addAll(List.of("--limit", "100000"));
        for (var element : top(all).getAsJsonArray("rows")) {
            var row = element.getAsJsonObject();
            boundaries.merge(row.get("boundary").getAsString(), row.get("value").getAsBigDecimal(), BigDecimal::add);
        }
        for (var boundary : boundaries.entrySet()) {
            BigDecimal difference =
                    boundary.getValue().subtract(leaves.getOrDefault(boundary.getKey(), BigDecimal.ZERO));
            check(
                    difference.abs().compareTo(new BigDecimal("0.002")) <= 0,
                    "top and stacks disagree on " + boundary.getKey() + ": " + boundary.getValue() + " vs "
                            + leaves.get(boundary.getKey()));
        }
        System.out.println("top agrees with stacks --leaf-at on " + boundaries.size() + " boundaries");

        Path digest = dir.resolve("digest");
        CommandLineTest.Invocation summarize = CommandLineTest.invoke(
                "summarize",
                "--profile",
                wolfi.toString(),
                "--report",
                wolfi.resolveSibling(OutputFiles.REPORT).toString(),
                "--app",
                APP,
                "--idle-from",
                "preset:jvm-idle",
                "--idle",
                IDLE_BOOKKEEPER,
                "--output-dir",
                digest.toString());
        check(summarize.code() == 0, "summarize failed: " + summarize);
        long size = Files.size(digest.resolve(OutputFiles.SUMMARY_MD));
        check(size < 16 * 1024, "The digest's Markdown must stay under 16 KB, is " + size);
        var heaviest = com.google.gson.JsonParser.parseString(
                        Files.readString(digest.resolve(OutputFiles.SUMMARY_JSON)))
                .getAsJsonObject()
                .getAsJsonObject("heaviestStacks");
        check(
                heaviest.get("lines").getAsInt() == 78
                        && heaviest.get("meanDepth")
                                .getAsBigDecimal()
                                .toPlainString()
                                .equals("4.1"),
                "Heaviest transformed stacks: " + heaviest);
        System.out.println("summarize: " + size + " bytes of Markdown");
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
            top(Path.of(fixtures), dir);
            System.out.println("Fixture acceptance checks passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
