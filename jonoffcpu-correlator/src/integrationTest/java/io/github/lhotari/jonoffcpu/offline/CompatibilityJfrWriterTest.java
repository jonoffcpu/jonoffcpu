// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises exact quantization, reconstructed constants, bounded publication and real readers. */
class CompatibilityJfrWriterTest {
    private static final long QUANTUM = 1_000L;
    private static final long BASE_MONOTONIC = 1_000_000_000L;
    private static final long BASE_EPOCH = Instant.parse("2026-09-21T10:00:00Z").getEpochSecond() * 1_000_000_000L;
    private static final String SESSION = UUID.randomUUID().toString();
    private static final String EXECUTION_SAMPLE = "jdk.ExecutionSample";

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

    /** Three matches of 2, 4 and 1 quanta: a truncated JIT frame, an interpreted native frame, and no stack. */
    private static List<OfflineCorrelator.Match> matches() {
        JsonObject alpha = frame("test.Alpha", "alpha", "JIT compiled", 11, 3);
        JsonObject beta = frame("test.Beta", "beta", "Interpreted", 22, 7);
        // Native frames commonly lack a descriptor. AP requires even this empty Symbol
        // constant to use the ordinary inline UTF encoding.
        beta.addProperty("descriptor", "");
        return List.of(
                match(1, BASE_MONOTONIC, 2 * QUANTUM, "alpha-thread", true, alpha),
                match(2, BASE_MONOTONIC + 10 * QUANTUM, 4 * QUANTUM, "beta-thread", false, beta),
                match(3, BASE_MONOTONIC + 20 * QUANTUM, QUANTUM, "missing-stack", false));
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

    private static List<RecordedEvent> executionSamples(Path file) throws IOException {
        return events(file).stream()
                .filter(event -> event.getEventType().getName().equals(EXECUTION_SAMPLE))
                .toList();
    }

    @Test
    void quantizesExactly(@TempDir Path directory) throws Exception {
        List<OfflineCorrelator.Match> matches = matches();
        Path output = directory.resolve("compatibility.jfr");
        CompatibilityJfrWriter.Result result = CompatibilityJfrWriter.write(
                analysis(matches), output, new CompatibilityJfrWriter.Options(QUANTUM, 100));
        assertThat(result.syntheticEvents())
                .as("Exact two-duration quantization")
                .isEqualTo(7);
        assertThat(result.exactSelectedNanos())
                .as("Exact two-duration quantization")
                .isEqualTo("7000");
        assertThat(result.representedNanos())
                .as("Exact two-duration quantization")
                .isEqualTo("7000");
        assertThat(result.quantizationErrorNanos())
                .as("Exact two-duration quantization")
                .isEqualTo("0");

        List<RecordedEvent> decoded = events(output);
        RecordedEvent metadata = decoded.stream()
                .filter(event -> event.getEventType().getName().equals("jonoffcpu.SyntheticOffCpuMetadata"))
                .findFirst()
                .orElseThrow();
        assertThat(metadata.getBoolean("synthetic")).as("Synthetic metadata").isTrue();
        assertThat(metadata.getLong("quantumNanos")).as("Synthetic metadata").isEqualTo(QUANTUM);
        assertThat(metadata.getString("exactSelectedNanos"))
                .as("Synthetic metadata")
                .isEqualTo("7000");
        assertThat(metadata.getString("quantizationErrorNanos"))
                .as("Synthetic metadata")
                .isEqualTo("0");
        assertThat(metadata.getString("sessionId")).as("Synthetic metadata").isEqualTo(SESSION);
        List<RecordedEvent> samples = decoded.stream()
                .filter(event -> event.getEventType().getName().equals(EXECUTION_SAMPLE))
                .toList();
        assertThat(samples).as("ExecutionSample count").hasSize(7);
        long alphaCount = 0;
        long betaCount = 0;
        long emptyCount = 0;
        for (RecordedEvent event : samples) {
            assertThat(event.getThread("sampledThread"))
                    .as("Missing reconstructed thread")
                    .isNotNull();
            String thread = event.getThread("sampledThread").getJavaName();
            OfflineCorrelator.Match source = thread.equals("alpha-thread")
                    ? matches.get(0)
                    : thread.equals("beta-thread") ? matches.get(1) : matches.get(2);
            long epochNanos = event.getStartTime().getEpochSecond() * 1_000_000_000L
                    + event.getStartTime().getNano();
            long lower = BASE_EPOCH + source.fromNanos().longValueExact() - BASE_MONOTONIC;
            long upper = BASE_EPOCH + source.toNanos().longValueExact() - BASE_MONOTONIC;
            assertThat(epochNanos)
                    .as("Synthetic timestamp escaped source interval")
                    .isGreaterThanOrEqualTo(lower)
                    .isLessThan(upper);
            if (thread.equals("alpha-thread")) {
                List<RecordedFrame> frames = event.getStackTrace().getFrames();
                alphaCount++;
                assertThat(event.getStackTrace().isTruncated())
                        .as("Truncation flag lost")
                        .isTrue();
                assertThat(frames.get(0).getMethod().getType().getName())
                        .as("Alpha frame changed")
                        .isEqualTo("test.Alpha");
                assertThat(frames.get(0).getMethod().getName())
                        .as("Alpha frame changed")
                        .isEqualTo("alpha");
            } else if (thread.equals("beta-thread")) {
                betaCount++;
                assertThat(event.getStackTrace().isTruncated())
                        .as("False truncation flag")
                        .isFalse();
            } else {
                emptyCount++;
                assertThat(event.getStackTrace().getFrames())
                        .as("Missing stack placeholder changed")
                        .singleElement()
                        .extracting(frame -> frame.getMethod().getName())
                        .isEqualTo("[stack unavailable]");
            }
        }
        assertThat(List.of(alphaCount, betaCount, emptyCount))
                .as("Duration ratio was not retained")
                .containsExactly(2L, 4L, 1L);
    }

    /** Per-stack carry makes 1.5q + 0.5q exactly two events, with the second timestamp in the later interval. */
    @Test
    void carriesRemainderPerStack(@TempDir Path directory) throws Exception {
        JsonObject carryFrame = frame("test.Carry", "carry", "Interpreted", 33, 1);
        List<OfflineCorrelator.Match> carryMatches = List.of(
                match(4, BASE_MONOTONIC + 30 * QUANTUM, 1_500, "carry-one", false, carryFrame),
                match(5, BASE_MONOTONIC + 40 * QUANTUM, 500, "carry-two", false, carryFrame.deepCopy()));
        Path carryFile = directory.resolve("carry.jfr");
        var carry = CompatibilityJfrWriter.write(
                analysis(carryMatches), carryFile, new CompatibilityJfrWriter.Options(QUANTUM, 10));
        assertThat(carry.syntheticEvents()).as("Remainder carry failed").isEqualTo(2);
        assertThat(carry.omittedRemainderNanos()).as("Remainder carry failed").isEqualTo("0");
        long secondIntervalEvents = executionSamples(carryFile).stream()
                .filter(event -> event.getThread("sampledThread").getJavaName().equals("carry-two"))
                .count();
        assertThat(secondIntervalEvents)
                .as("Carried weight timestamp convention changed")
                .isEqualTo(1);
    }

    @Test
    void coarsensQuantumAtEventCap(@TempDir Path directory) throws Exception {
        Path capped = directory.resolve("capped.jfr");
        var coarsened = CompatibilityJfrWriter.write(
                analysis(matches()), capped, new CompatibilityJfrWriter.Options(QUANTUM, 6));
        assertThat(coarsened.quantumRaised())
                .as("Expansion cap did not coarsen the quantum")
                .isTrue();
        assertThat(coarsened.syntheticEvents())
                .as("Coarsened plan still exceeded the cap")
                .isLessThanOrEqualTo(6);
        assertThat(coarsened.requestedQuantumNanos())
                .as("Requested quantum was not reported")
                .isEqualTo(QUANTUM);
        assertThat(capped).as("Coarsened plan published no output").isRegularFile();
    }

    @Test
    void failsAtEventCapUnderFailPolicy(@TempDir Path directory) {
        Path failing = directory.resolve("failing.jfr");
        assertThatExceptionOfType(IOException.class)
                .as("Expansion cap was ignored under the fail policy")
                .isThrownBy(() -> CompatibilityJfrWriter.write(
                        analysis(matches()),
                        failing,
                        new CompatibilityJfrWriter.Options(QUANTUM, 6, CompatibilityJfrWriter.EventLimitPolicy.FAIL)))
                .withMessageContaining("limit");
        assertThat(failing).as("Cap failure published partial output").doesNotExist();
    }

    @Test
    void neverReplacesExistingDestination(@TempDir Path directory) throws Exception {
        OfflineCorrelator.Analysis analysis = analysis(matches());
        Path occupied = directory.resolve("occupied.jfr");
        Files.writeString(occupied, "keep");
        assertThatExceptionOfType(FileAlreadyExistsException.class)
                .as("Existing destination was replaced")
                .isThrownBy(() -> CompatibilityJfrWriter.write(
                        analysis, occupied, new CompatibilityJfrWriter.Options(QUANTUM, 100)));
        assertThat(occupied).as("Existing destination contents changed").hasContent("keep");
        Path empty = directory.resolve("empty.jfr");
        Files.createFile(empty);
        assertThatExceptionOfType(FileAlreadyExistsException.class)
                .as("Existing empty destination was replaced")
                .isThrownBy(() -> CompatibilityJfrWriter.write(
                        analysis, empty, new CompatibilityJfrWriter.Options(QUANTUM, 100)));
        assertThat(empty).as("Existing empty destination contents changed").isEmptyFile();
    }

    @Test
    void concurrentPublicationIsExclusive(@TempDir Path directory) throws Exception {
        OfflineCorrelator.Analysis analysis = analysis(matches());
        Path raced = directory.resolve("raced.jfr");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = pool.invokeAll(
                    List.of(
                            () -> CompatibilityJfrWriter.write(
                                    analysis, raced, new CompatibilityJfrWriter.Options(QUANTUM, 100)),
                            () -> CompatibilityJfrWriter.write(
                                    analysis, raced, new CompatibilityJfrWriter.Options(QUANTUM, 100))),
                    1,
                    TimeUnit.MINUTES);
            int successes = 0;
            int collisions = 0;
            for (var future : futures) {
                assertThat(future.isCancelled())
                        .as("A concurrent write timed out")
                        .isFalse();
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof FileAlreadyExistsException) collisions++;
                    else throw e;
                }
            }
            assertThat(successes)
                    .as("Concurrent no-replace publication was not exclusive")
                    .isEqualTo(1);
            assertThat(collisions)
                    .as("Concurrent no-replace publication was not exclusive")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(executionSamples(raced))
                .as("Concurrent winner produced invalid recording")
                .hasSize(7);
    }

    /** async-profiler's own converter, built by the Gradle task and named by {@code -Djonoffcpu.jfrconv}. */
    @Test
    void asyncProfilerConverterReadsOutput(@TempDir Path directory) throws Exception {
        String configured = System.getProperty("jonoffcpu.jfrconv", "");
        assertThat(configured)
                .as("-Djonoffcpu.jfrconv must name async-profiler's jfrconv (the integrationTest task sets it)")
                .isNotBlank();
        Path converter = Path.of(configured);
        assertThat(Files.isExecutable(converter))
                .as("jfrconv %s is not executable", converter)
                .isTrue();
        Path input = directory.resolve("compatibility.jfr");
        CompatibilityJfrWriter.write(analysis(matches()), input, new CompatibilityJfrWriter.Options(QUANTUM, 100));
        Path collapsed = directory.resolve("ap-converter.collapsed");
        Process process = new ProcessBuilder(converter.toString(), "--cpu", input.toString(), collapsed.toString())
                .redirectErrorStream(true)
                .start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(2, TimeUnit.MINUTES))
                    .as("AP jfrconv did not exit: %s", output)
                    .isTrue();
            assertThat(process.exitValue())
                    .as("AP jfrconv rejected compatibility JFR: %s", output)
                    .isZero();
        } finally {
            process.destroyForcibly();
        }
        assertThat(Files.readString(collapsed))
                .as("AP converter lost reconstructed stacks")
                .contains("Alpha.alpha", "Beta.beta");
    }
}
