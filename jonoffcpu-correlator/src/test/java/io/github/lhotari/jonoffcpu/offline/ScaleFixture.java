// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
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
    private static final long EPOCH = 0x80000001L;

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
            context.enable(OfflineCorrelatorTest.Capture.class);
            context.start();
            new OfflineCorrelatorTest.Capture().commit();
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
            samples.enable(OfflineCorrelatorTest.Sample.class).withStackTrace();
            samples.enable(OfflineCorrelatorTest.Stats.class);
            samples.start();
            committer
                    .submit(() -> {
                        for (int row = 0; row < rows; row++) {
                            OfflineCorrelatorTest.Sample sample = new OfflineCorrelatorTest.Sample();
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
            OfflineCorrelatorTest.Stats stats = new OfflineCorrelatorTest.Stats();
            stats.admittedSignals = stats.acceptedCookies = stats.submittedSamples = rows;
            stats.commit();
            samples.stop();
            samples.dump(file);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IOException("Committing the fixture's samples failed", e);
        }
    }

    /** Recurses to the requested depth before committing, so the captured stack trace varies. */
    private static void commitAtDepth(int depth, OfflineCorrelatorTest.Sample event) {
        if (depth <= 1) {
            event.commit();
        } else {
            commitAtDepth(depth - 1, event);
        }
    }

    /** The one thread every sample above was committed from, plus its terminal stats row. */
    private record JfrSummary(long threadId, JsonObject stats) {}

    private static JfrSummary jfrSummary(Path jfr) throws IOException {
        Gson gson = new GsonBuilder().serializeNulls().create();
        long[] threadId = new long[1];
        JsonObject[] stats = new JsonObject[1];
        SignalJfrExporter.visit(jfr, raw -> {
            if (threadId[0] == 0 && raw.get("recordType").equals("sample")) {
                threadId[0] = (Long) raw.get("osThreadId");
            }
            if (raw.get("recordType").equals("stats")) {
                stats[0] = gson.toJsonTree(raw).getAsJsonObject();
            }
        });
        if (threadId[0] == 0) throw new IllegalStateException("Scale fixture JFR produced no sample thread id");
        if (stats[0] == null) throw new IllegalStateException("Scale fixture JFR produced no terminal stats row");
        return new JfrSummary(threadId[0], stats[0]);
    }

    private static JsonObject captureStart() {
        JsonObject start = OfflineCorrelatorTest.row("captureStart");
        start.addProperty("sourceId", "jonoffcpu.offcpu.v1");
        start.addProperty("signal", 35);
        start.addProperty("signalDelivery", "queued");
        start.addProperty("hostTgid", 123);
        start.addProperty("targetPid", ProcessHandle.current().pid());
        start.add("sampling", OfflineCorrelatorTest.uniformSampling());
        start.addProperty("processGenerationNs", "100");
        start.addProperty("timeNamespaceInode", "42");
        start.addProperty("pidNamespaceDevice", "4");
        start.addProperty("pidNamespaceInode", "43");
        start.addProperty("registrationToken", "0000000000000001");
        start.addProperty("startedMonotonicNanos", "500");
        return start;
    }

    /**
     * The last observation ends at {@code 4000 + rows - 1}: every capture-lifecycle boundary below
     * must clear that, or rows past some cutoff are silently classified outside the capture / after
     * the profiler stopped instead of matching.
     */
    private static long lastObservationEndNanos(int rows) {
        return 4000L + rows;
    }

    private static JsonObject captureEnd(int rows) {
        long stopped = lastObservationEndNanos(rows) + 1000;
        JsonObject end = OfflineCorrelatorTest.row("captureEnd");
        end.addProperty("state", "complete");
        end.addProperty("drainTimedOut", false);
        end.addProperty("startedMonotonicNanos", "500");
        end.addProperty("stoppedMonotonicNanos", Long.toString(stopped));
        end.addProperty("detachedMonotonicNanos", Long.toString(stopped + 1000));
        end.addProperty("drainCompletedMonotonicNanos", Long.toString(stopped + 2000));
        JsonObject userspace = new JsonObject();
        userspace.addProperty("receivedObservations", Integer.toString(rows));
        userspace.addProperty("writtenObservations", Integer.toString(rows));
        userspace.addProperty("writeFailures", "0");
        userspace.addProperty("pollFailures", "0");
        JsonObject counters = new JsonObject();
        counters.add("userspace", userspace);
        JsonObject kernel = new JsonObject();
        kernel.addProperty("targetNamespaceFailures", "0");
        kernel.addProperty("eligibleIntervals", Integer.toString(rows));
        kernel.addProperty("admissionRejections", "0");
        kernel.addProperty("selectedIntervals", Integer.toString(rows));
        for (String key : List.of(
                "ringReserveFailures",
                "sequenceExhaustions",
                "sequenceContentions",
                "lifetimeRejections",
                "threadStateFailures")) {
            kernel.addProperty(key, "0");
        }
        counters.add("kernel", kernel);
        end.add("counters", counters);
        return end;
    }

    private static JsonObject footer(Path jfr, JfrSummary summary, long rawBytes, String rawSha256, int rows)
            throws IOException {
        JsonObject footer = OfflineCorrelatorTest.row("captureFinalized");
        footer.addProperty("state", "complete");
        JsonObject inputs = captureStart();
        inputs.remove("recordType");
        inputs.addProperty("clockVerified", true);
        inputs.addProperty("monotonicOffsetNanos", "0");
        inputs.add("verifiedIdentity", inputs.deepCopy());
        JsonObject stats = summary.stats().deepCopy();
        for (String key : List.of("schemaVersion", "recordType", "sessionId", "captureEpoch", "startTime")) {
            stats.remove(key);
        }
        inputs.add("apStats", stats);
        JsonObject sourceArtifact = new JsonObject();
        sourceArtifact.addProperty("path", "an-unrelated-old-location");
        sourceArtifact.addProperty("rawBytes", Long.toString(rawBytes));
        sourceArtifact.addProperty("rawSha256", rawSha256);
        inputs.add("sourceArtifact", sourceArtifact);
        JsonObject jfrArtifact = new JsonObject();
        jfrArtifact.addProperty("path", "an-unrelated-old-jfr-location");
        jfrArtifact.addProperty("bytes", Long.toString(Files.size(jfr)));
        jfrArtifact.addProperty("sha256", CaptureInput.digest(jfr));
        inputs.add("jfrArtifact", jfrArtifact);
        String sessionId = inputs.get("sessionId").getAsString();
        long epoch = inputs.get("captureEpoch").getAsLong();
        footer.add("analysisInputs", inputs);
        footer.addProperty(
                "apStopResponse",
                "signal-capture-v1 stopped id="
                        + sessionId
                        + " delivery=queued signal=35 epoch="
                        + epoch
                        + " finalized=true stopped-at="
                        + (lastObservationEndNanos(rows) + 4000)
                        + " reason=explicit"
                        + " admitted="
                        + rows
                        + " invalid-code=0 zero-cookie=0 zero-sequence=0 stale-epoch=0"
                        + " accepted="
                        + rows
                        + " capture-failures=0 submitted="
                        + rows
                        + "\n");
        return footer;
    }

    /**
     * Writes the header, the two announced stacks, and {@code rows} observations straight to disk,
     * digesting the bytes as they are written so the footer never needs the body back in memory.
     */
    static Path capture(Path dir, Path jfr, int rows) throws Exception {
        JfrSummary summary = jfrSummary(jfr);
        Path body = dir.resolve("scale-body-" + stem(jfr) + ".tmp");
        MessageDigest digest = CaptureInput.sha256();
        try (var out = new BufferedOutputStream(new DigestOutputStream(Files.newOutputStream(body), digest))) {
            out.write(CaptureStreamFixture.header());
            CaptureStreamFixture.record(captureStart()).writeDelimitedTo(out);
            CaptureStreamFixture.record(
                            OfflineCorrelatorTest.stack(OfflineCorrelatorTest.KERNEL_STACK_ID, "kernel_wait"))
                    .writeDelimitedTo(out);
            CaptureStreamFixture.record(OfflineCorrelatorTest.stack(OfflineCorrelatorTest.USER_STACK_ID, "user_wait"))
                    .writeDelimitedTo(out);
            for (int row = 0; row < rows; row++) {
                JsonObject observation = OfflineCorrelatorTest.observation(summary.threadId());
                observation.addProperty("correlationId", String.format("80000001%08x", row + 1));
                observation.addProperty("startMonotonicNanos", Long.toString(1000L + row));
                observation.addProperty("endMonotonicNanos", Long.toString(4000L + row));
                CaptureStreamFixture.record(observation).writeDelimitedTo(out);
            }
            CaptureStreamFixture.record(captureEnd(rows)).writeDelimitedTo(out);
        }
        long rawBytes = Files.size(body);
        String rawSha256 = CaptureInput.hex(digest.digest());
        JsonObject footer = footer(jfr, summary, rawBytes, rawSha256, rows);
        Path result = dir.resolve("scale-source-" + stem(jfr) + ".capture");
        try (var out = new BufferedOutputStream(Files.newOutputStream(result));
                var in = new BufferedInputStream(Files.newInputStream(body))) {
            in.transferTo(out);
            CaptureStreamFixture.record(footer).writeDelimitedTo(out);
        }
        Files.delete(body);
        return result;
    }

    /**
     * The same rows as {@link #capture}, emitted in a seeded permutation, with the footer's
     * {@code rawBytes}/{@code rawSha256} recomputed for the reordered body. The stack records stay
     * ahead of every observation, which the format requires regardless of observation order.
     */
    static Path shuffledCapture(Path dir, Path jfr, int rows, long seed) throws Exception {
        JfrSummary summary = jfrSummary(jfr);
        int[] order = new int[rows];
        for (int row = 0; row < rows; row++) order[row] = row;
        java.util.Random random = new java.util.Random(seed);
        for (int index = rows - 1; index > 0; index--) {
            int swapWith = random.nextInt(index + 1);
            int value = order[index];
            order[index] = order[swapWith];
            order[swapWith] = value;
        }
        Path body = dir.resolve("scale-shuffled-body-" + stem(jfr) + "-" + seed + ".tmp");
        MessageDigest digest = CaptureInput.sha256();
        try (var out = new BufferedOutputStream(new DigestOutputStream(Files.newOutputStream(body), digest))) {
            out.write(CaptureStreamFixture.header());
            CaptureStreamFixture.record(captureStart()).writeDelimitedTo(out);
            CaptureStreamFixture.record(
                            OfflineCorrelatorTest.stack(OfflineCorrelatorTest.KERNEL_STACK_ID, "kernel_wait"))
                    .writeDelimitedTo(out);
            CaptureStreamFixture.record(OfflineCorrelatorTest.stack(OfflineCorrelatorTest.USER_STACK_ID, "user_wait"))
                    .writeDelimitedTo(out);
            for (int row : order) {
                JsonObject observation = OfflineCorrelatorTest.observation(summary.threadId());
                observation.addProperty("correlationId", String.format("80000001%08x", row + 1));
                observation.addProperty("startMonotonicNanos", Long.toString(1000L + row));
                observation.addProperty("endMonotonicNanos", Long.toString(4000L + row));
                CaptureStreamFixture.record(observation).writeDelimitedTo(out);
            }
            CaptureStreamFixture.record(captureEnd(rows)).writeDelimitedTo(out);
        }
        long rawBytes = Files.size(body);
        String rawSha256 = CaptureInput.hex(digest.digest());
        JsonObject footer = footer(jfr, summary, rawBytes, rawSha256, rows);
        Path result = dir.resolve("scale-shuffled-source-" + stem(jfr) + "-" + seed + ".capture");
        try (var out = new BufferedOutputStream(Files.newOutputStream(result));
                var in = new BufferedInputStream(Files.newInputStream(body))) {
            in.transferTo(out);
            CaptureStreamFixture.record(footer).writeDelimitedTo(out);
        }
        Files.delete(body);
        return result;
    }
}
