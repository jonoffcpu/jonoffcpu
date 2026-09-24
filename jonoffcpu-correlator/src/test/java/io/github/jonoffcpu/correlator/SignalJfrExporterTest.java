// SPDX-License-Identifier: MIT

package io.github.jonoffcpu.correlator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import io.github.jonoffcpu.codec.ProtoJson;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dependency-free fixtures exercise JFR decoding, including signed Java representations of unsigned
 * cookies.
 */
class SignalJfrExporterTest {
    private static final String SESSION = UUID.randomUUID().toString();
    private static final long EPOCH = 0x80000001L;
    private static final long COOKIE = (EPOCH << 32) | 0xabcdef01L;

    @Name("profiler.SignalCapture")
    @StackTrace(false)
    public static class Capture extends Event {
        public int schemaVersion = 1;
        public String sessionId = SESSION;
        public long captureEpoch = EPOCH;
        public String signalDelivery = "queued";
        public int signal = 35;
        public long processId = ProcessHandle.current().pid();
        public long processStartTimeMillis = 1000;
    }

    @Name("profiler.SignalSample")
    @StackTrace(true)
    public static class Sample extends Event {
        public long correlationId = COOKIE;
        public long monotonicTimeNanos = -1;
    }

    @Name("profiler.SignalSampleV2")
    public static class FutureSample extends Event {}

    @Name("profiler.SignalCaptureStats")
    @StackTrace(false)
    public static class Stats extends Event {
        public int schemaVersion = 1;
        public String sessionId = SESSION;
        public long captureEpoch = EPOCH;
        public long admittedSignals = 2;
        public long invalidSignalCode;
        public long zeroCookie;
        public long zeroSequence;
        public long staleEpoch;
        public long acceptedCookies = 2;
        public long captureFailures;
        public long submittedSamples = 2;
    }

    private static Path record(Path dir, String name, Runnable emit) throws IOException {
        Path path = dir.resolve(name + ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(Capture.class);
            recording.enable(Sample.class).withStackTrace();
            recording.enable(Stats.class);
            recording.enable(FutureSample.class);
            recording.start();
            emit.run();
            recording.stop();
            recording.dump(path);
        }
        return path;
    }

    /** The exported JSON Lines, each parsed back strictly into its record. */
    private static List<SignalProto.SignalRecord> export(Path input) throws IOException {
        StringWriter output = new StringWriter();
        SignalJfrExporter.export(input, output);
        List<SignalProto.SignalRecord> records = new ArrayList<>();
        for (String line : output.toString().split("\n")) {
            records.add(
                    ProtoJson.parse(line, SignalProto.SignalRecord.newBuilder()).build());
        }
        return records;
    }

    private static void invalid(Path input, String expected) {
        StringWriter output = new StringWriter();
        assertThatIOException()
                .as("Expected invalid recording: " + expected)
                .isThrownBy(() -> SignalJfrExporter.export(input, output))
                .withMessageContaining(expected);
        assertThat(output.toString())
                .as("Invalid stream marked complete")
                .doesNotContain("\"parseComplete\":true")
                .doesNotContain("\"end\"");
    }

    @Test
    void valid(@TempDir Path dir) throws IOException {
        String originalName = Thread.currentThread().getName();
        String name = "quoted\"\\\n\t\u0001Ω";
        List<SignalProto.SignalRecord> records;
        try {
            Thread.currentThread().setName(name);
            Path valid = record(dir, "valid", () -> {
                new Capture().commit();
                new Sample().commit();
                new Capture().commit();
                new Stats().commit();
                // JFR buffer serialization can place samples after terminal stats.
                new Sample().commit();
            });
            records = export(valid);
        } finally {
            Thread.currentThread().setName(originalName);
        }
        List<SignalProto.SignalSample> samples = records.stream()
                .filter(SignalProto.SignalRecord::hasSample)
                .map(SignalProto.SignalRecord::getSample)
                .toList();
        assertThat(samples).hasSize(2);
        SignalProto.SignalSample sample = samples.get(0);
        assertThat(sample.getCorrelationId()).as("Cookie bits lost").isEqualTo(COOKIE);
        assertThat(Long.toUnsignedString(sample.getMonotonicTimeNanos()))
                .as("Unsigned nanos lost")
                .isEqualTo("18446744073709551615");
        assertThat(sample.getThreadName()).as("JSON escaping incorrect").isEqualTo(name);
        // The sample's stack ends in the lambda that committed it.
        assertThat(sample.getFramesList())
                .as("Sample stack missing")
                .anyMatch(frame -> frame.getMethodName().startsWith("lambda$valid$"));
        assertThat(sample.hasOsThreadId()).as("OS thread identity missing").isTrue();
        assertThat(records.get(records.size() - 1).getEnd())
                .as("Missing clean end record")
                .isEqualTo(SignalProto.SignalEnd.newBuilder()
                        .setParseComplete(true)
                        .setCaptures(2)
                        .setSamples(2)
                        .setStats(1)
                        .build());
    }

    @Test
    void zeroSamples(@TempDir Path dir) throws IOException {
        List<SignalProto.SignalRecord> zeroSamples = export(record(dir, "zero", () -> {
            new Capture().commit();
            new Stats().commit();
        }));
        SignalProto.SignalRecord end = zeroSamples.get(zeroSamples.size() - 1);
        assertThat(end.getEnd().getParseComplete())
                .as("Valid zero-sample capture rejected")
                .isTrue();
        assertThat(end.getEnd().getSamples()).isZero();
    }

    @Test
    void missingContext(@TempDir Path dir) throws IOException {
        invalid(record(dir, "missing-context", () -> new Sample().commit()), "Missing signal capture context");
    }

    @Test
    void missingStats(@TempDir Path dir) throws IOException {
        invalid(record(dir, "missing-stats", () -> new Capture().commit()), "Missing terminal");
    }

    @Test
    void doubleStats(@TempDir Path dir) throws IOException {
        invalid(
                record(dir, "double-stats", () -> {
                    new Capture().commit();
                    new Stats().commit();
                    new Stats().commit();
                }),
                "Multiple terminal");
    }

    @Test
    void badEpoch(@TempDir Path dir) throws IOException {
        invalid(
                record(dir, "bad-epoch", () -> {
                    new Capture().commit();
                    Sample sample = new Sample();
                    sample.correlationId = (5L << 32) | 1;
                    sample.commit();
                }),
                "cookie does not belong");
    }

    @Test
    void zeroSequence(@TempDir Path dir) throws IOException {
        invalid(
                record(dir, "zero-sequence", () -> {
                    new Capture().commit();
                    Sample sample = new Sample();
                    sample.correlationId = EPOCH << 32;
                    sample.commit();
                }),
                "cookie does not belong");
    }

    @Test
    void newSchema(@TempDir Path dir) throws IOException {
        invalid(
                record(dir, "new-schema", () -> {
                    Capture capture = new Capture();
                    capture.schemaVersion = 2;
                    capture.commit();
                }),
                "unsupported schema");
    }

    @Test
    void newEvent(@TempDir Path dir) throws IOException {
        invalid(
                record(dir, "new-event", () -> {
                    new Capture().commit();
                    new FutureSample().commit();
                    new Stats().commit();
                }),
                "Unsupported signal event type");
    }

    @Test
    void newContext(@TempDir Path dir) throws IOException {
        invalid(
                record(dir, "new-context", () -> {
                    new Capture().commit();
                    Capture capture = new Capture();
                    capture.sessionId = UUID.randomUUID().toString();
                    capture.commit();
                }),
                "Conflicting signal capture context");
    }

    @Test
    void statsContext(@TempDir Path dir) throws IOException {
        invalid(
                record(dir, "stats-context", () -> {
                    new Capture().commit();
                    Stats stats = new Stats();
                    stats.captureEpoch = 3;
                    stats.commit();
                }),
                "Conflicting signal capture identity");
    }
}
