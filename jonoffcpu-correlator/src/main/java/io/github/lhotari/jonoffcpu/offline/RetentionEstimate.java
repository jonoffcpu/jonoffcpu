// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What a correlation will retain, worked out from the two file sizes before either is decoded.
 *
 * <p>Retention per record is a known constant of the columnar design, and the input sizes are
 * known, so the run does not have to discover at ninety percent of heap that it does not fit — the
 * point at which a limit becomes an OutOfMemoryError that no policy can recover from. The estimate
 * chooses the thinning factor and the audit level at the start; a watermark during the pass catches
 * an estimate that was wrong, and a restart costs one sequential re-read of files that are already
 * digest-verified and in page cache.
 *
 * <p>The per-record constants are measured on a three-minute Apache Pulsar broker capture:
 * 110.9 MB of capture stream for 1,121,421 observations (99 bytes each) and 38.2 MB of JFR for
 * 1,121,418 {@code profiler.SignalSample} events (35 bytes each). A source column slot is 59 bytes
 * (38 before the stack profile kept each row's reason, task state and native stack ids, 51 before the run-queue
 * reading), a sample slot 38, and a cookie index entry 24 at the index's half load factor. Interned stacks
 * are bounded by the number of distinct stacks rather than by input size — that capture had 10,631
 * — so they enter as a flat allowance.
 */
record RetentionEstimate(long sourceBytes, long jfrBytes, long observations, long samples, long retainedBytes) {
    private static final long CAPTURE_BYTES_PER_OBSERVATION = 99;
    private static final long JFR_BYTES_PER_SAMPLE = 35;
    private static final long SOURCE_SLOT_BYTES = 59;
    private static final long SAMPLE_SLOT_BYTES = 38;
    private static final long INDEX_ENTRY_BYTES = 24;
    private static final long INTERNED_STACK_ALLOWANCE_BYTES = 64L << 20;
    private static final long BUDGET_FLOOR_BYTES = 256L << 20;
    private static final int BUDGET_HEAP_PERCENT = 60;

    static RetentionEstimate of(Path source, Path jfr) throws IOException {
        long sourceBytes = Files.size(source);
        long jfrBytes = Files.size(jfr);
        long observations = sourceBytes / CAPTURE_BYTES_PER_OBSERVATION;
        long samples = jfrBytes / JFR_BYTES_PER_SAMPLE;
        long retained = observations * (SOURCE_SLOT_BYTES + INDEX_ENTRY_BYTES)
                + samples * (SAMPLE_SLOT_BYTES + INDEX_ENTRY_BYTES)
                + INTERNED_STACK_ALLOWANCE_BYTES;
        return new RetentionEstimate(sourceBytes, jfrBytes, observations, samples, retained);
    }

    /**
     * The retention budget to guard with: the configured value when one was given, otherwise a
     * share of this JVM's heap, never below the floor. A guard should track the heap it protects.
     */
    static long budget(long configuredLimitBytes) {
        if (configuredLimitBytes > 0) return configuredLimitBytes;
        long derived = Runtime.getRuntime().maxMemory() / 100 * BUDGET_HEAP_PERCENT;
        return Math.max(BUDGET_FLOOR_BYTES, derived);
    }

    /** The largest keep probability whose estimated retention fits the budget, as a decimal string. */
    String thinningFor(long budgetBytes) {
        if (retainedBytes <= budgetBytes) return "1";
        long headroom = Math.max(0, budgetBytes - INTERNED_STACK_ALLOWANCE_BYTES);
        long perRow = SOURCE_SLOT_BYTES + SAMPLE_SLOT_BYTES + 2 * INDEX_ENTRY_BYTES;
        double fraction = (double) headroom / Math.max(1, observations * perRow);
        // Stay on round numbers so the report and the flame graph label read well.
        for (String candidate : new String[] {"0.5", "0.2", "0.1", "0.05", "0.02", "0.01"}) {
            if (Double.parseDouble(candidate) <= fraction) return candidate;
        }
        return "0.01";
    }
}
