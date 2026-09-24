// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static io.github.lhotari.jonoffcpu.capture.CaptureFixtures.record;

import io.github.lhotari.jonoffcpu.capture.CaptureFixtures;
import io.github.lhotari.jonoffcpu.capture.CaptureFormat;
import io.github.lhotari.jonoffcpu.capture.CaptureProto;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jdk.jfr.Recording;

/**
 * Builds the scale fixture's JFR and capture stream without ever retaining more than one row:
 * the JFR is produced by {@code jdk.jfr.Recording}'s own native buffering, and the capture stream
 * is framed and digested straight to disk with {@code CaptureProto.Record.writeDelimitedTo}.
 */
final class ScaleFixture {
    private static final long EPOCH = Integer.toUnsignedLong(CorrelationFixture.EPOCH);

    /** Inputs already generated, by directory and shape: several tests share one large fixture. */
    private static final Map<String, Input> INPUTS = new HashMap<>();

    private ScaleFixture() {}

    /** A recording and the finalized capture whose observations it matches row for row. */
    record Input(Path jfr, Path source) {}

    /**
     * The recording and capture of {@code rows} rows over {@code distinctStacks} stacks, generated in {@code dir}
     * on first use and reused afterwards. Inputs are only read, so tests may share them.
     */
    static synchronized Input input(Path dir, int rows, int distinctStacks, boolean skewed) throws Exception {
        String key = dir + "/" + rows + "-" + distinctStacks + "-" + skewed;
        Input input = INPUTS.get(key);
        if (input == null) {
            Path jfr = recording(dir, rows, distinctStacks, skewed);
            input = new Input(jfr, capture(dir, jfr, rows));
            INPUTS.put(key, input);
        }
        return input;
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }

    /**
     * {@code rows} {@code profiler.SignalSample} events, cycling through {@code distinctStacks}.
     *
     * <p>The exporter requires the single {@code profiler.SignalCapture} context event to be seen
     * before any sample that depends on it. At high commit volume a single recording does not honor
     * that: {@code jdk.jfr.Event.commit()} routes same-thread events through per-size buffer pools
     * that flush independently, so a later {@code dump()} can read the context event — and the
     * small, equally rare terminal stats event — back well after most samples, regardless of commit
     * order or of streaming straight to a destination file. Recording the context event by itself,
     * in its own tiny session, sidesteps that: a JFR file is a sequence of self-contained chunks, so
     * concatenating that session's dump ahead of the sample-flood session's dump yields one valid
     * recording whose first chunk is guaranteed to be the context event, independent of how either
     * session's own buffers happened to flush.
     */
    static Path recording(Path dir, int rows, int distinctStacks) throws IOException {
        return recording(dir, rows, distinctStacks, false);
    }

    /**
     * Like {@link #recording(Path, int, int)}, but with a skewed (power-of-two) distribution
     * across distinct stacks instead of a uniform round robin, so distinct stacks carry
     * distinctly separated weights. The thinning-accuracy fixture needs this: under a uniform
     * round robin every stack is exactly tied in weight, and thinning's ~2-4% per-stack sampling
     * noise would then decide the top-stack ranking essentially at random, which is an unwinnable
     * comparison against the exact run. {@code scale()} must not use this: it exists specifically
     * to exercise the stack dictionary near its real fan-out (spec acceptance 4), which a skewed
     * distribution — where {@code numberOfTrailingZeros(row + 1)} never reaches large values for
     * a two-million-row recording — would silently collapse to a couple dozen distinct stacks.
     */
    static Path recordingSkewed(Path dir, int rows, int distinctStacks) throws IOException {
        return recording(dir, rows, distinctStacks, true);
    }

    private static Path recording(Path dir, int rows, int distinctStacks, boolean skewed) throws IOException {
        String shape = rows + "-" + distinctStacks + (skewed ? "-skewed" : "");
        Path contextFile = dir.resolve("scale-context-" + shape + ".jfr");
        try (Recording context = new Recording()) {
            context.enable(CorrelationFixture.Capture.class);
            context.start();
            new CorrelationFixture.Capture().commit();
            context.stop();
            context.dump(contextFile);
        }
        Path samplesFile = dir.resolve("scale-samples-" + shape + ".jfr");
        recordSamples(samplesFile, rows, distinctStacks, skewed);
        Path file = dir.resolve("scale-" + shape + ".jfr");
        try (var out = new BufferedOutputStream(Files.newOutputStream(file))) {
            for (Path part : List.of(contextFile, samplesFile)) {
                try (var in = new BufferedInputStream(Files.newInputStream(part))) {
                    in.transferTo(out);
                }
                Files.delete(part);
            }
        }
        return file;
    }

    /** The samples as one recording, with the terminal stats event after the last row. */
    private static void recordSamples(Path file, int rows, int distinctStacks, boolean skewed) throws IOException {
        // Committed from a thread of their own, so each sample's stack is the fixture's recursion alone rather than
        // the test runner's frames below it, which would multiply the cost of recording and reading it.
        try (Recording samples = new Recording();
                var committer = Executors.newSingleThreadExecutor()) {
            samples.enable(CorrelationFixture.Sample.class).withStackTrace();
            samples.enable(CorrelationFixture.Stats.class);
            samples.start();
            committer
                    .submit(() -> {
                        for (int row = 0; row < rows; row++) {
                            CorrelationFixture.Sample sample = new CorrelationFixture.Sample();
                            sample.correlationId = (EPOCH << 32) | (row + 1);
                            // The matching observation's endMonotonicNanos grows with row (see capture()
                            // below); the handler delay is monotonicTimeNanos minus that end, so this must
                            // grow with it too, or every row past the point where the fixed default falls
                            // behind is classified invalid-handler-delay instead of matched.
                            sample.monotonicTimeNanos = 5000L + row;
                            // A skewed (power-of-two) distribution gives distinct stacks distinctly separated
                            // weights, needed only by the thinning-accuracy fixture (see recordingSkewed).
                            int depth = skewed
                                    ? 1 + (Integer.numberOfTrailingZeros(row + 1) % distinctStacks)
                                    : 1 + (row % distinctStacks);
                            commitAtDepth(depth, sample);
                        }
                    })
                    .get(2, TimeUnit.MINUTES);
            CorrelationFixture.Stats stats = new CorrelationFixture.Stats();
            stats.admittedSignals = stats.acceptedCookies = stats.submittedSamples = rows;
            stats.commit();
            samples.stop();
            samples.dump(file);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IOException("Committing the fixture's samples failed", e);
        }
    }

    /** Recurses to the requested depth before committing, so the captured stack trace varies. */
    private static void commitAtDepth(int depth, CorrelationFixture.Sample event) {
        if (depth <= 1) {
            event.commit();
        } else {
            commitAtDepth(depth - 1, event);
        }
    }

    /** The one thread every sample above was committed from, plus its terminal counters. */
    private record JfrSummary(long threadId, CaptureProto.AsyncProfilerStats stats) {}

    private static JfrSummary jfrSummary(Path jfr) throws IOException {
        long[] threadId = new long[1];
        CaptureProto.AsyncProfilerStats[] stats = new CaptureProto.AsyncProfilerStats[1];
        SignalJfrExporter.visit(jfr, row -> {
            if (threadId[0] == 0 && row.hasSample())
                threadId[0] = row.getSample().getOsThreadId();
            if (row.hasStats()) stats[0] = row.getStats().getCounters();
        });
        if (threadId[0] == 0) throw new IllegalStateException("Scale fixture JFR produced no sample thread id");
        if (stats[0] == null) throw new IllegalStateException("Scale fixture JFR produced no terminal stats row");
        return new JfrSummary(threadId[0], stats[0]);
    }

    private static CaptureProto.CaptureStart captureStart() {
        return CorrelationFixture.captureStart(
                        CorrelationFixture.uniformSampling(),
                        CorrelationFixture.timeSplit(CaptureProto.TimeSplitSource.TIME_SPLIT_SOURCE_OFF))
                .build();
    }

    /**
     * The last observation ends at {@code 4000 + rows - 1}: every capture-lifecycle boundary below
     * must clear that, or rows past some cutoff are silently classified outside the capture / after
     * the profiler stopped instead of matching.
     */
    private static long lastObservationEndNanos(int rows) {
        return 4000L + rows;
    }

    private static CaptureProto.CaptureEnd captureEnd(CaptureProto.CaptureStart start, int rows) {
        long stopped = lastObservationEndNanos(rows) + 1000;
        return CaptureFixtures.captureEnd(start, rows)
                .setStoppedMonotonicNanos(stopped)
                .setDetachedMonotonicNanos(stopped + 1000)
                .setDrainCompletedMonotonicNanos(stopped + 2000)
                .build();
    }

    private static CaptureProto.CaptureFinalized footer(
            CaptureProto.CaptureStart start, Path jfr, JfrSummary summary, long rawBytes, String rawSha256, int rows)
            throws IOException {
        CaptureProto.CaptureFinalized.Builder footer =
                CaptureFixtures.captureFinalized(start, new byte[0], jfr, summary.stats());
        footer.getAnalysisInputsBuilder()
                .getSourceArtifactBuilder()
                .setRawBytes(rawBytes)
                .setRawSha256(rawSha256);
        return footer.setApStopResponse(
                        CaptureFixtures.apStopResponse(start, lastObservationEndNanos(rows) + 4000, summary.stats()))
                .build();
    }

    private static CaptureProto.Observation observation(long threadId, int row) {
        return CorrelationFixture.observation(threadId)
                .setCorrelationId((EPOCH << 32) | (row + 1))
                .setStartMonotonicNanos(1000L + row)
                .setEndMonotonicNanos(4000L + row)
                .build();
    }

    /**
     * Writes the header, the two announced stacks, and {@code rows} observations straight to disk,
     * digesting the bytes as they are written so the footer never needs the body back in memory.
     */
    static Path capture(Path dir, Path jfr, int rows) throws Exception {
        int[] order = new int[rows];
        for (int row = 0; row < rows; row++) order[row] = row;
        return write(dir, jfr, order, dir.resolve("scale-source-" + stem(jfr) + ".capture"));
    }

    /**
     * The same rows as {@link #capture}, emitted in a seeded permutation, with the footer's
     * {@code rawBytes}/{@code rawSha256} recomputed for the reordered body. The stack records stay
     * ahead of every observation, which the format requires regardless of observation order.
     */
    static Path shuffledCapture(Path dir, Path jfr, int rows, long seed) throws Exception {
        int[] order = new int[rows];
        for (int row = 0; row < rows; row++) order[row] = row;
        java.util.Random random = new java.util.Random(seed);
        for (int index = rows - 1; index > 0; index--) {
            int swapWith = random.nextInt(index + 1);
            int value = order[index];
            order[index] = order[swapWith];
            order[swapWith] = value;
        }
        return write(dir, jfr, order, dir.resolve("scale-shuffled-source-" + stem(jfr) + "-" + seed + ".capture"));
    }

    private static Path write(Path dir, Path jfr, int[] order, Path result) throws Exception {
        JfrSummary summary = jfrSummary(jfr);
        CaptureProto.CaptureStart start = captureStart();
        Path body = dir.resolve(result.getFileName() + ".tmp");
        MessageDigest digest = CaptureInput.sha256();
        try (var out = new BufferedOutputStream(new DigestOutputStream(Files.newOutputStream(body), digest))) {
            out.write(CaptureFormat.header());
            CaptureFormat.write(out, record(start));
            CaptureFormat.write(
                    out, record(CorrelationFixture.stack(CorrelationFixture.KERNEL_STACK_ID, "kernel_wait")));
            CaptureFormat.write(out, record(CorrelationFixture.stack(CorrelationFixture.USER_STACK_ID, "user_wait")));
            for (int row : order) CaptureFormat.write(out, record(observation(summary.threadId(), row)));
            CaptureFormat.write(out, record(captureEnd(start, order.length)));
        }
        long rawBytes = Files.size(body);
        String rawSha256 = HexFormat.of().formatHex(digest.digest());
        CaptureProto.CaptureFinalized footer = footer(start, jfr, summary, rawBytes, rawSha256, order.length);
        try (var out = new BufferedOutputStream(Files.newOutputStream(result));
                var in = new BufferedInputStream(Files.newInputStream(body))) {
            in.transferTo(out);
            CaptureFormat.write(out, record(footer));
        }
        Files.delete(body);
        return result;
    }
}
