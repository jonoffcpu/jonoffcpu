// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.jonoffcpu.jonoffcpu.capture.ProtoJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * The reference numbers the 0.5.0 specs were written against, measured on recordings of an Apache Pulsar broker. The
 * recordings are not part of the repository; run with {@code -Djonoffcpu.fixtures=DIR} (Gradle:
 * {@code -PjonoffcpuFixtures=DIR}) pointing at a directory holding {@code pulsar-broker-2026-09-23-wolfi/} and
 * {@code pulsar-broker-2026-09-23-wolfi-cpu/}, and it is skipped otherwise.
 */
class FixtureAcceptanceTest {
    static final String IDLE_BOOKKEEPER =
            "^org\\.apache\\.bookkeeper\\.common\\.collections\\.[\\w$]*BlockingQueue\\.take(All)?$";
    static final String APP = "^org\\.apache\\.";

    /** The configured fixture directory; the calling test is skipped when none is configured. */
    static Path fixtures() {
        String fixtures = System.getProperty("jonoffcpu.fixtures", "");
        assumeFalse(
                fixtures.isBlank(),
                "Skipping the fixture acceptance checks: -Djonoffcpu.fixtures (Gradle: -PjonoffcpuFixtures) is not set");
        return Path.of(fixtures);
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
        return lines.size() + " lines at " + frames.divide(weight, 1, RoundingMode.HALF_EVEN);
    }

    static Path run(Path output, List<String> args) throws Exception {
        List<String> command = new ArrayList<>(args);
        command.addAll(List.of("--output", output.toString()));
        CommandLineFixture.Invocation invocation = CommandLineFixture.invoke(command.toArray(String[]::new));
        assertThat(invocation.code()).as("Failed: %s: %s", args, invocation).isZero();
        return output;
    }

    /** One test per row of the transforms reference table. */
    @ParameterizedTest(name = "{0}")
    @FieldSource("TRANSFORMS")
    void transforms(Row row, @TempDir Path dir) throws Exception {
        Path fixtures = fixtures();
        Path cpu = fixtures.resolve("pulsar-broker-2026-09-23-wolfi-cpu/broker-cpu.collapsed");
        Path profile = fixtures.resolve("pulsar-broker-2026-09-23-wolfi/jonoffcpu-offcpu-profile.pb");
        int index = TRANSFORMS.indexOf(row);
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
        assertThat(cpuShape).as("%s: CPU", row.name()).isEqualTo(row.cpuLines() + " lines at " + row.cpuDepth());
        assertThat(offShape).as("%s: off-CPU", row.name()).isEqualTo(row.offLines() + " lines at " + row.offDepth());
    }

    /** The boundary ranking recipe of the README's "Analyzing with SQL", on the 0.5.0 export. */
    static final String BOUNDARY_SQL = """
            CREATE TEMP TABLE entries AS
            SELECT javaFrames AS frames, observedNanos::UBIGINT AS nanos, intervals::UBIGINT AS intervals
            FROM read_json('%s', format = 'newline_delimited');

            SELECT coalesce(list_filter(frames, lambda f: regexp_matches(f, '^org\\.apache\\.'))[-1],
                            '[no application frame]') AS boundary,
                   round(sum(nanos) / 1e9, 3) AS seconds, sum(intervals) AS intervals
            FROM entries
            WHERE NOT list_bool_or(list_transform(frames, lambda f: regexp_matches(f,
                  '^(io\\.netty\\.channel\\.epoll\\.Native\\.epollWait0?|java\\.util\\.concurrent\\.ThreadPoolExecutor\\.getTask|sun\\.nio\\.ch\\.SelectorImpl\\.select|java\\.util\\.concurrent\\.ForkJoinPool\\.awaitWork)$|BlockingQueue\\.take(All)?$|^java\\.lang\\.ref\\.|^libasyncProfiler\\.so\\.')))
            GROUP BY 1 ORDER BY 2 DESC LIMIT 3;
            """;

    /** Exports the Wolfi profile as JSON Lines, with its run metadata beside it. */
    private static Path exportWolfi(Path fixtures, Path dir) throws Exception {
        Path profile = fixtures.resolve("pulsar-broker-2026-09-23-wolfi/jonoffcpu-offcpu-profile.pb");
        Path jsonl = dir.resolve("wolfi.jsonl");
        CommandLineFixture.Invocation invocation = CommandLineFixture.invoke(
                "export",
                "--profile",
                profile.toString(),
                "--format",
                "jsonl",
                "--output",
                jsonl.toString(),
                "--run-metadata",
                dir.resolve("wolfi-run.json").toString());
        assertThat(invocation.code()).as("Export failed: %s", invocation).isZero();
        return jsonl;
    }

    /** The export's reference numbers. */
    @Test
    void export(@TempDir Path dir) throws Exception {
        Path jsonl = exportWolfi(fixtures(), dir);
        List<String> rows = Files.readAllLines(jsonl, StandardCharsets.UTF_8);
        assertThat(rows).hasSize(2_791);
        long observed = 0;
        Pattern address = Pattern.compile("(Lambda|LambdaForm\\$D?MH)[./]0x");
        for (String line : rows) {
            AnalysisProto.ExportRow row =
                    ProtoJson.parse(line, AnalysisProto.ExportRow.newBuilder()).build();
            assertThat(String.join(";", row.getJavaFramesList()))
                    .as("javaFrames must join: %s", line)
                    .isEqualTo(row.getJavaStack());
            assertThat(row.getJavaFrameKindsCount())
                    .as("One kind per frame: %s", line)
                    .isEqualTo(row.getJavaFramesCount());
            assertThat(row.getCanonicalJavaStack()).as("Canonical: %s", line).doesNotContainPattern(address);
            observed += row.getObservedNanos();
        }
        assertThat(observed).as("Observed nanoseconds").isEqualTo(8_519_334_220_784L);
        AnalysisProto.RunMetadata run = ProtoJson.parse(
                        Files.readString(dir.resolve("wolfi-run.json")), AnalysisProto.RunMetadata.newBuilder())
                .build();
        assertThat(run.getObservedNanos()).as("Run metadata total: %s", run).isEqualTo(Long.toString(observed));
    }

    /** The README's SQL recipes on the export reproduce the reference rows; skipped without DuckDB. */
    @Test
    void exportDuckDbRecipes(@TempDir Path dir) throws Exception {
        Path fixtures = fixtures();
        Path duckdb = ExportDuckDbTest.duckdb();
        Path jsonl = exportWolfi(fixtures, dir);
        String types = ExportDuckDbTest.duckdb(
                duckdb,
                "DESCRIBE SELECT javaFrames, observedNanos FROM read_json('" + jsonl
                        + "', format = 'newline_delimited');");
        assertThat(types)
                .as("DuckDB must infer the frame arrays, and read the 64-bit counters as decimal strings")
                .contains("javaFrames,VARCHAR[]", "observedNanos,VARCHAR");
        String boundaries = ExportDuckDbTest.duckdb(duckdb, BOUNDARY_SQL.formatted(jsonl));
        assertThat(boundaries)
                .as("The boundary recipe")
                .contains(
                        "org.apache.pulsar.broker.service.persistent.PersistentDispatcherMultipleConsumers.internalConsumerFlow,11.982,4245",
                        "org.apache.bookkeeper.common.collections.GrowableBatchedArrayBlockingQueue.offer,4.946,1565");
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

    private static AnalysisProto.TopResult topJson(List<String> args) throws Exception {
        List<String> command = new ArrayList<>(List.of("top", "--format", "json"));
        command.addAll(args);
        CommandLineFixture.Invocation invocation = CommandLineFixture.invoke(command.toArray(String[]::new));
        assertThat(invocation.code()).as("top failed: %s", invocation).isZero();
        return ProtoJson.parse(invocation.out(), AnalysisProto.TopResult.newBuilder())
                .build();
    }

    private static String totals(AnalysisProto.TableSum sum) {
        return sum.getEntries() + "/" + sum.getIntervals() + "/" + sum.getValue();
    }

    /** The top-and-digest worked example, its comparison, the digest bound, and top agreeing with stacks. */
    @Test
    void top(@TempDir Path dir) throws Exception {
        Path fixtures = fixtures();
        Path wolfi = fixtures.resolve("pulsar-broker-2026-09-23-wolfi/jonoffcpu-offcpu-profile.pb");
        Path alpine = fixtures.resolve("pulsar-broker-2026-09-23-alpine/jonoffcpu-offcpu-profile.pb");
        List<String> options =
                List.of("--app", APP, "--idle-from", "preset:jvm-idle", "--idle", IDLE_BOOKKEEPER, "--limit", "10");
        List<String> args = new ArrayList<>(List.of("--profile", wolfi.toString()));
        args.addAll(options);
        AnalysisProto.TopResult result = topJson(args);
        AnalysisProto.TopTotals totals = result.getTotals();
        assertThat(totals(totals.getSelected())).as("All: %s", totals).isEqualTo("2791/308777/8519.334");
        assertThat(totals(totals.getIdle())).as("Idle: %s", totals).isEqualTo("660/301389/8470.366");
        assertThat(totals(totals.getBusy())).as("Busy: %s", totals).isEqualTo("2131/7388/48.968");
        assertThat(totals(totals.getBusyNoApplicationFrame()))
                .as("No application frame: %s", totals)
                .isEqualTo("132/237/28.374");
        assertThat(totals(totals.getOverExclusion()))
                .as("Over-exclusion: %s", totals)
                .isEqualTo("18/18/0.047");
        List<String> rows = new ArrayList<>();
        for (AnalysisProto.TopRow row : result.getRowsList()) {
            rows.add(row.getKey() + "|" + row.getBlocker() + "|" + row.getValue() + "|" + row.getIntervals());
        }
        assertThat(rows).as("Busy by boundary").isEqualTo(BOUNDARY_ROWS);
        List<AnalysisProto.TopRow> pools = result.getNoApplicationFrameList();
        List<String> firstPools = new ArrayList<>();
        for (AnalysisProto.TopRow pool : pools.subList(0, 2)) {
            firstPools.add(pool.getKey() + "|" + pool.getValue() + "|" + pool.getIntervals());
        }
        assertThat(firstPools)
                .as("Pools: %s", pools)
                .containsExactly("ZDriverMinor|22.517|88", "ZDriverMajor|5.527|27");
        List<String> idle = new ArrayList<>();
        for (AnalysisProto.TopRow row : result.getIdleList()) {
            idle.add(row.getKey() + " " + new BigDecimal(row.getValue()).setScale(1, RoundingMode.HALF_EVEN));
        }
        assertThat(idle.subList(0, 5))
                .as("Idle by boundary: %s", idle)
                .containsExactly(
                        "[no application frame] 7687.8",
                        "org.apache.bookkeeper.common.collections.GrowableBatchedArrayBlockingQueue.internalTakeAll 231.2",
                        "org.apache.pulsar.common.util.collections.GrowableArrayBlockingQueue.take 225.0",
                        "org.apache.zookeeper.ClientCnxnSocketNIO.doTransport 218.2",
                        "org.apache.zookeeper.ClientCnxn$EventThread.run 108.2");

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
        AnalysisProto.TopResult comparison = topJson(compare);
        List<String> compared = new ArrayList<>();
        for (AnalysisProto.ComparedRow row : comparison.getComparisonList()) {
            compared.add(row.getBoundary() + "|" + row.getBaseline() + "|" + row.getValue() + "|"
                    + Top.percent(row.getBaselineShare()) + "|" + Top.percent(row.getShare()));
        }
        assertThat(compared).as("Comparison").isEqualTo(COMPARISON_ROWS);
        AnalysisProto.ComparisonTotals compareTotals = comparison.getComparisonTotals();
        assertThat(List.of(
                        compareTotals.getBaselineBusyApplication(),
                        compareTotals.getBusyApplication(),
                        compareTotals.getBaselineBusy(),
                        compareTotals.getBusy()))
                .as("Comparison totals: %s", compareTotals)
                .containsExactly("16.574", "20.593", "2019.121", "48.968");
        assertThat(comparison.getWarningsList()).as("Both warnings").hasSize(2);

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
        Map<String, BigDecimal> leaves = new HashMap<>();
        for (String line : Files.readAllLines(leafAt)) {
            int space = line.lastIndexOf(' ');
            String stack = line.substring(0, space);
            leaves.merge(
                    stack.substring(stack.lastIndexOf(';') + 1),
                    new BigDecimal(line.substring(space + 1)).movePointLeft(6),
                    BigDecimal::add);
        }
        Map<String, BigDecimal> boundaries = new HashMap<>();
        List<String> all = new ArrayList<>(List.of("--profile", wolfi.toString()));
        all.addAll(options.subList(0, 6));
        all.addAll(List.of("--limit", "100000"));
        for (AnalysisProto.TopRow row : topJson(all).getRowsList()) {
            boundaries.merge(row.getKey(), new BigDecimal(row.getValue()), BigDecimal::add);
        }
        for (var boundary : boundaries.entrySet()) {
            assertThat(boundary.getValue())
                    .as(
                            "top and stacks disagree on %s: %s vs %s",
                            boundary.getKey(), boundary.getValue(), leaves.get(boundary.getKey()))
                    .isCloseTo(
                            leaves.getOrDefault(boundary.getKey(), BigDecimal.ZERO), within(new BigDecimal("0.002")));
        }

        Path digest = dir.resolve("digest");
        CommandLineFixture.Invocation summarize = CommandLineFixture.invoke(
                "summarize",
                "--profile",
                wolfi.toString(),
                "--app",
                APP,
                "--idle-from",
                "preset:jvm-idle",
                "--idle",
                IDLE_BOOKKEEPER,
                "--output-dir",
                digest.toString());
        assertThat(summarize.code()).as("summarize failed: %s", summarize).isZero();
        assertThat(Files.size(digest.resolve(OutputFiles.SUMMARY_MD)))
                .as("The digest's Markdown must stay under 16 KB")
                .isLessThan(16 * 1024);
        AnalysisProto.HeaviestStacks heaviest = ProtoJson.parse(
                        Files.readString(digest.resolve(OutputFiles.SUMMARY_JSON)), AnalysisProto.Digest.newBuilder())
                .getHeaviestStacks();
        assertThat(heaviest.getLines())
                .as("Heaviest transformed stacks: %s", heaviest)
                .isEqualTo(78);
        assertThat(heaviest.getMeanDepth())
                .as("Heaviest transformed stacks: %s", heaviest)
                .isEqualTo("4.1");
    }
}
