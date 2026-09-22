// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

/** Exercises exact quantization, reconstructed constants, bounded publication and real readers. */
public final class CompatibilityJfrWriterTest {
    private static final long QUANTUM = 1_000L;
    private static final long BASE_MONOTONIC = 1_000_000_000L;
    private static final long BASE_EPOCH = Instant.parse("2026-09-21T10:00:00Z").getEpochSecond() * 1_000_000_000L;
    private static final String SESSION = UUID.randomUUID().toString();

    private CompatibilityJfrWriterTest() {}

    private static JsonObject frame(String className, String methodName, String type, int line, int bci) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", type);
        frame.addProperty("className", className);
        frame.addProperty("methodName", methodName);
        frame.addProperty("descriptor", "()V");
        frame.addProperty("lineNumber", line);
        frame.addProperty("bytecodeIndex", bci);
        return frame;
    }

    private static OfflineCorrelator.Match match(
            long sequence, long from, long duration, String thread, boolean truncated, JsonObject... frames) {
        JsonObject observation = new JsonObject();
        observation.addProperty("correlationId", String.format("80000001%08x", sequence));
        JsonObject sample = new JsonObject();
        long sampleMonotonic = from + duration + 100;
        sample.addProperty("monotonicTimeNanos", Long.toString(sampleMonotonic));
        sample.addProperty(
                "startTime",
                Instant.ofEpochSecond(0, BASE_EPOCH + sampleMonotonic - BASE_MONOTONIC)
                        .toString());
        sample.addProperty("osThreadId", 40 + sequence);
        sample.addProperty("javaThreadId", 400 + sequence);
        sample.addProperty("threadName", thread);
        sample.addProperty("stackTruncated", truncated);
        JsonArray stack = new JsonArray();
        for (JsonObject frame : frames) stack.add(frame);
        sample.add("frames", stack);
        return new OfflineCorrelator.Match(
                observation,
                sample,
                BigInteger.valueOf(from),
                BigInteger.valueOf(from + duration),
                BigInteger.valueOf(duration),
                BigInteger.valueOf(100),
                true);
    }

    private static OfflineCorrelator.Analysis analysis(List<OfflineCorrelator.Match> matches) {
        BigInteger exact =
                matches.stream().map(OfflineCorrelator.Match::durationNanos).reduce(BigInteger.ZERO, BigInteger::add);
        JsonObject inputs = new JsonObject();
        inputs.addProperty("sessionId", SESSION);
        inputs.addProperty("captureEpoch", 0x80000001L);
        inputs.addProperty("monotonicOffsetNanos", "0");
        JsonObject source = new JsonObject();
        source.addProperty("rawSha256", "11".repeat(32));
        inputs.add("sourceArtifact", source);
        JsonObject jfr = new JsonObject();
        jfr.addProperty("sha256", "22".repeat(32));
        inputs.add("jfrArtifact", jfr);
        return new OfflineCorrelator.Analysis(
                1,
                inputs,
                new JsonObject(),
                "0",
                matches.size(),
                matches.size(),
                matches.size(),
                0,
                0,
                0,
                0,
                0,
                exact.toString(),
                "0",
                Map.of(),
                List.of(),
                List.copyOf(matches),
                null,
                null);
    }

    private static List<RecordedEvent> events(Path file) throws IOException {
        List<RecordedEvent> events = new ArrayList<>();
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) events.add(recording.readEvent());
        }
        return events;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectExists(Callable<?> operation, String message) throws Exception {
        try {
            operation.call();
            throw new AssertionError(message);
        } catch (FileAlreadyExistsException expected) {
            // Expected no-replace result.
        }
    }

    private static void verifyConverter(Path converter, Path input, Path directory) throws Exception {
        Path collapsed = directory.resolve("ap-converter.collapsed");
        Process process = new ProcessBuilder(converter.toString(), "--cpu", input.toString(), collapsed.toString())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        check(
                process.waitFor() == 0,
                "AP jfrconv rejected compatibility JFR: " + new String(output, StandardCharsets.UTF_8));
        String text = Files.readString(collapsed);
        check(text.contains("Alpha.alpha") && text.contains("Beta.beta"), "AP converter lost reconstructed stacks");
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("jonoffcpu-compatibility-jfr-test-");
        try {
            JsonObject alpha = frame("test.Alpha", "alpha", "JIT compiled", 11, 3);
            JsonObject beta = frame("test.Beta", "beta", "Interpreted", 22, 7);
            // Native frames commonly lack a descriptor. AP requires even this empty Symbol
            // constant to use the ordinary inline UTF encoding.
            beta.addProperty("descriptor", "");
            List<OfflineCorrelator.Match> matches = List.of(
                    match(1, BASE_MONOTONIC, 2 * QUANTUM, "alpha-thread", true, alpha),
                    match(2, BASE_MONOTONIC + 10 * QUANTUM, 4 * QUANTUM, "beta-thread", false, beta),
                    match(3, BASE_MONOTONIC + 20 * QUANTUM, QUANTUM, "missing-stack", false));
            OfflineCorrelator.Analysis analysis = analysis(matches);
            Path output = directory.resolve("compatibility.jfr");
            CompatibilityJfrWriter.Result result =
                    CompatibilityJfrWriter.write(analysis, output, new CompatibilityJfrWriter.Options(QUANTUM, 100));
            check(
                    result.syntheticEvents() == 7
                            && result.exactSelectedNanos().equals("7000")
                            && result.representedNanos().equals("7000")
                            && result.quantizationErrorNanos().equals("0"),
                    "Exact two-duration quantization mismatch");

            List<RecordedEvent> decoded = events(output);
            RecordedEvent metadata = decoded.stream()
                    .filter(event -> event.getEventType().getName().equals("jonoffcpu.SyntheticOffCpuMetadata"))
                    .findFirst()
                    .orElseThrow();
            check(
                    metadata.getBoolean("synthetic")
                            && metadata.getLong("quantumNanos") == QUANTUM
                            && metadata.getString("exactSelectedNanos").equals("7000")
                            && metadata.getString("quantizationErrorNanos").equals("0")
                            && metadata.getString("sessionId").equals(SESSION),
                    "Synthetic metadata incomplete");
            List<RecordedEvent> samples = decoded.stream()
                    .filter(event -> event.getEventType().getName().equals("jdk.ExecutionSample"))
                    .toList();
            check(samples.size() == 7, "Wrong ExecutionSample count");
            long alphaCount = 0;
            long betaCount = 0;
            long emptyCount = 0;
            for (RecordedEvent event : samples) {
                check(event.getThread("sampledThread") != null, "Missing reconstructed thread");
                String thread = event.getThread("sampledThread").getJavaName();
                OfflineCorrelator.Match source = thread.equals("alpha-thread")
                        ? matches.get(0)
                        : thread.equals("beta-thread") ? matches.get(1) : matches.get(2);
                long epochNanos = event.getStartTime().getEpochSecond() * 1_000_000_000L
                        + event.getStartTime().getNano();
                long lower = BASE_EPOCH + source.fromNanos().longValueExact() - BASE_MONOTONIC;
                long upper = BASE_EPOCH + source.toNanos().longValueExact() - BASE_MONOTONIC;
                check(epochNanos >= lower && epochNanos < upper, "Synthetic timestamp escaped source interval");
                if (thread.equals("alpha-thread")) {
                    List<RecordedFrame> frames = event.getStackTrace().getFrames();
                    alphaCount++;
                    check(event.getStackTrace().isTruncated(), "Truncation flag lost");
                    check(
                            frames.get(0).getMethod().getType().getName().equals("test.Alpha")
                                    && frames.get(0).getMethod().getName().equals("alpha"),
                            "Alpha frame changed");
                } else if (thread.equals("beta-thread")) {
                    betaCount++;
                    check(!event.getStackTrace().isTruncated(), "False truncation flag");
                } else {
                    emptyCount++;
                    check(
                            event.getStackTrace().getFrames().size() == 1
                                    && event.getStackTrace()
                                            .getFrames()
                                            .get(0)
                                            .getMethod()
                                            .getName()
                                            .equals("[stack unavailable]"),
                            "Missing stack placeholder changed");
                }
            }
            check(alphaCount == 2 && betaCount == 4 && emptyCount == 1, "Duration ratio was not retained");

            // Per-stack carry makes 1.5q + 0.5q exactly two events, with the second timestamp in the
            // later interval.
            JsonObject carryFrame = frame("test.Carry", "carry", "Interpreted", 33, 1);
            List<OfflineCorrelator.Match> carryMatches = List.of(
                    match(4, BASE_MONOTONIC + 30 * QUANTUM, 1_500, "carry-one", false, carryFrame),
                    match(5, BASE_MONOTONIC + 40 * QUANTUM, 500, "carry-two", false, carryFrame.deepCopy()));
            Path carryFile = directory.resolve("carry.jfr");
            var carry = CompatibilityJfrWriter.write(
                    analysis(carryMatches), carryFile, new CompatibilityJfrWriter.Options(QUANTUM, 10));
            check(carry.syntheticEvents() == 2 && carry.omittedRemainderNanos().equals("0"), "Remainder carry failed");
            long secondIntervalEvents = events(carryFile).stream()
                    .filter(event -> event.getEventType().getName().equals("jdk.ExecutionSample"))
                    .filter(event ->
                            event.getThread("sampledThread").getJavaName().equals("carry-two"))
                    .count();
            check(secondIntervalEvents == 1, "Carried weight timestamp convention changed");

            Path capped = directory.resolve("capped.jfr");
            var coarsened =
                    CompatibilityJfrWriter.write(analysis, capped, new CompatibilityJfrWriter.Options(QUANTUM, 6));
            check(coarsened.quantumRaised(), "Expansion cap did not coarsen the quantum");
            check(coarsened.syntheticEvents() <= 6, "Coarsened plan still exceeded the cap");
            check(coarsened.requestedQuantumNanos() == QUANTUM, "Requested quantum was not reported");
            check(Files.isRegularFile(capped), "Coarsened plan published no output");
            Path failing = directory.resolve("failing.jfr");
            try {
                CompatibilityJfrWriter.write(
                        analysis,
                        failing,
                        new CompatibilityJfrWriter.Options(QUANTUM, 6, CompatibilityJfrWriter.EventLimitPolicy.FAIL));
                throw new AssertionError("Expansion cap was ignored under the fail policy");
            } catch (IOException expected) {
                check(expected.getMessage().contains("limit"), "Unexpected cap failure: " + expected);
            }
            check(!Files.exists(failing), "Cap failure published partial output");

            Path occupied = directory.resolve("occupied.jfr");
            Files.writeString(occupied, "keep");
            expectExists(
                    () -> CompatibilityJfrWriter.write(
                            analysis, occupied, new CompatibilityJfrWriter.Options(QUANTUM, 100)),
                    "Existing destination was replaced");
            check(Files.readString(occupied).equals("keep"), "Existing destination contents changed");
            Path empty = directory.resolve("empty.jfr");
            Files.createFile(empty);
            expectExists(
                    () -> CompatibilityJfrWriter.write(
                            analysis, empty, new CompatibilityJfrWriter.Options(QUANTUM, 100)),
                    "Existing empty destination was replaced");
            check(Files.size(empty) == 0, "Existing empty destination contents changed");

            Path raced = directory.resolve("raced.jfr");
            var pool = Executors.newFixedThreadPool(2);
            try {
                var futures = pool.invokeAll(List.of(
                        () -> CompatibilityJfrWriter.write(
                                analysis, raced, new CompatibilityJfrWriter.Options(QUANTUM, 100)),
                        () -> CompatibilityJfrWriter.write(
                                analysis, raced, new CompatibilityJfrWriter.Options(QUANTUM, 100))));
                int successes = 0;
                int collisions = 0;
                for (var future : futures) {
                    try {
                        future.get();
                        successes++;
                    } catch (java.util.concurrent.ExecutionException e) {
                        if (e.getCause() instanceof FileAlreadyExistsException) collisions++;
                        else throw e;
                    }
                }
                check(successes == 1 && collisions == 1, "Concurrent no-replace publication was not exclusive");
            } finally {
                pool.shutdownNow();
            }
            check(
                    events(raced).stream()
                                    .filter(event ->
                                            event.getEventType().getName().equals(EXECUTION_SAMPLE))
                                    .count()
                            == 7,
                    "Concurrent winner produced invalid recording");

            if (args.length == 1 && Files.isExecutable(Path.of(args[0]))) {
                verifyConverter(Path.of(args[0]), output, directory);
            }
            System.out.println("Compatibility JFR writer fixtures passed on " + Runtime.version());
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static final String EXECUTION_SAMPLE = "jdk.ExecutionSample";
}
