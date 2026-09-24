// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec acceptance 4: retention tracks distinct stacks, not recorded intervals. The build runs this test in a JVM of
 * its own whose heap is the bound it proves (the `scaleTest` task); `-PscaleRows` and `-PscaleHeap` scale it up to
 * the specification's two million rows under 1 GiB.
 */
@Tag("scale")
class StreamingCorrelatorScaleTest {
    /** The retention budget per row: 400 MB for two million rows, as the specification states it. */
    private static final long BUDGET_BYTES_PER_ROW = 200;

    /** The retention budget of one interned stack, whose frames the fixture recurses up to 200 deep. */
    private static final long BUDGET_BYTES_PER_STACK = 24L << 10;

    /** The engine's structures that grow with neither: dictionaries' initial capacity and the like. */
    private static final long FIXED_BUDGET_BYTES = 1L << 20;

    /** The fixture's fan-out: below the JFR stack depth the build gives this test's JVM. */
    private static final int DISTINCT_STACKS = 200;

    @Test
    void retentionTracksDistinctStacksNotIntervals(@TempDir Path dir) throws Exception {
        String configured = System.getProperty("jonoffcpu.scaleRows", "");
        int rows = configured.isBlank() ? 60_000 : Integer.parseInt(configured);
        long budgetBytes = rows * BUDGET_BYTES_PER_ROW + DISTINCT_STACKS * BUDGET_BYTES_PER_STACK + FIXED_BUDGET_BYTES;
        ScaleFixture.Input input = ScaleFixture.input(dir, rows, DISTINCT_STACKS, false);
        var limits = new OfflineCorrelator.Limits(rows * 2 + 16, 1024 * 1024, budgetBytes, 4096, null, null, null);
        var result = CorrelationEngine.correlate(input.source(), input.jfr(), limits, null, false);
        assertThat(result.matched()).as("matches at scale").isEqualTo(rows);
        // One stack per recursion depth: the fixture's frames always run interpreted, and the build raises JFR's
        // stack depth above the deepest recursion, so neither JIT variants nor truncation change the count.
        assertThat(result.dictionaries().stackCount())
                .as("distinct stacks, one per recursion depth")
                .isEqualTo(Math.min(DISTINCT_STACKS, rows));
        // The observations and samples at the documented bytes a row and the two cookie indices sized off the
        // inputs grow with the rows; the interned stacks grow with the distinct stacks alone.
        assertThat(result.peakRetainedBytes())
                .as("peak retention within the bound")
                .isPositive()
                .isLessThan(budgetBytes);
        System.out.println("Scale fixture peak retained bytes: " + result.peakRetainedBytes() + " for " + rows
                + " rows and " + result.dictionaries().stackCount() + " distinct stacks");
    }
}
