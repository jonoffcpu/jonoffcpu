// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static io.github.lhotari.jonoffcpu.offline.ExportFixture.frame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DuckDB reads the SQL-friendly export without a schema, as the README's "Analyzing with SQL" recipes expect. */
class ExportDuckDbTest {
    /**
     * The DuckDB executable on the path. With {@code JONOFFCPU_REQUIRE_DUCKDB=true}, as CI sets it, a missing DuckDB
     * fails; otherwise the calling test is skipped.
     */
    static Path duckdb() {
        for (String directory : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path candidate = Path.of(directory, "duckdb");
            if (Files.isExecutable(candidate)) return candidate;
        }
        if ("true".equals(System.getenv("JONOFFCPU_REQUIRE_DUCKDB"))) {
            fail("JONOFFCPU_REQUIRE_DUCKDB is set but duckdb is not on the path");
        }
        assumeTrue(false, "duckdb is not on the path");
        throw new AssertionError("unreachable");
    }

    /** Runs SQL in a fresh in-memory DuckDB and returns its CSV output. */
    static String duckdb(Path duckdb, String sql) throws Exception {
        Process process = new ProcessBuilder(duckdb.toString(), "-csv", "-c", sql)
                .redirectErrorStream(true)
                .start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(1, TimeUnit.MINUTES))
                    .as("duckdb did not exit: %s", output)
                    .isTrue();
            assertThat(process.exitValue()).as("duckdb failed: %s", output).isZero();
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * DuckDB reads the JSON Lines export without a schema: frames as VARCHAR[], and the 64-bit counters, which the
     * proto3 JSON mapping prints as decimal strings, as VARCHAR that one view casts to UBIGINT.
     */
    @Test
    void readByDuckDb(@TempDir Path dir) throws Exception {
        Path duckdb = duckdb();
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
                        new StackProfile.Header(List.of(), List.of("reason", "thread"), false, null, "", List.of()),
                        entries)
                .write(profile);
        Path rows = ExportFixture.export(dir, "sql.jsonl", profile, "--format", "jsonl", "--run-label", "a");
        String types = duckdb(
                duckdb,
                "DESCRIBE SELECT javaFrames, javaFrameKinds, observedNanos, taskState, reason, run, estimateAvailable"
                        + " FROM read_json('" + rows + "', format = 'newline_delimited');");
        // The proto3 JSON mapping prints 64-bit counters as decimal strings, which DuckDB reads as VARCHAR; 32-bit
        // values stay numbers.
        assertThat(types)
                .as("DuckDB must infer the frame arrays, the string counters and the run columns")
                .contains(
                        "javaFrames,VARCHAR[]",
                        "javaFrameKinds,VARCHAR[]",
                        "observedNanos,VARCHAR",
                        "reason,VARCHAR",
                        "run,VARCHAR",
                        "estimateAvailable,BOOLEAN");
        // The documented recipe: a view that casts the counters to UBIGINT once, so every query sums exact integers.
        String view = "CREATE VIEW entries AS SELECT * REPLACE (intervals::UBIGINT AS intervals,"
                + " observedNanos::UBIGINT AS observedNanos, estimatedNanos::UBIGINT AS estimatedNanos)"
                + " FROM read_json('" + rows + "', format = 'newline_delimited');";
        String cast = duckdb(duckdb, view + " SELECT typeof(observedNanos), typeof(intervals) FROM entries LIMIT 1;");
        assertThat(cast).as("The view must cast the counters").contains("UBIGINT,UBIGINT");
        String boundaries = duckdb(
                duckdb,
                view + " SELECT list_filter(javaFrames, lambda f: regexp_matches(f, '^x\\.'))[1] AS boundary,"
                        + " sum(observedNanos) / 1e9 AS seconds, sum(intervals) AS intervals, any_value(reason)"
                        + " AS reason FROM entries GROUP BY 1 ORDER BY 1;");
        assertThat(boundaries)
                .contains("x.App.run,3.0,3,OFF_CPU_REASON_BLOCKED", "x.App.wait,1.5,2,OFF_CPU_REASON_BLOCKED");
    }
}
