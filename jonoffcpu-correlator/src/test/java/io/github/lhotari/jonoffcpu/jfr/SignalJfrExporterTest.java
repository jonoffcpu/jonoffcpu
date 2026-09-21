// SPDX-License-Identifier: MIT

package io.github.lhotari.jonoffcpu.jfr;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

/**
 * Dependency-free fixtures exercise JFR decoding, including signed Java representations of unsigned
 * cookies.
 */
public final class SignalJfrExporterTest {
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

    private static String export(Path input) throws IOException {
        StringWriter output = new StringWriter();
        SignalJfrExporter.export(input, output);
        return output.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void invalid(Path input, String expected) throws IOException {
        StringWriter output = new StringWriter();
        try {
            SignalJfrExporter.export(input, output);
            throw new AssertionError("Expected invalid recording: " + expected);
        } catch (IOException failure) {
            check(failure.getMessage().contains(expected), "Unexpected failure: " + failure);
            check(!output.toString().contains("\"parseComplete\":true"), "Invalid stream marked complete");
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("jonoffcpu-jfr-export-test-");
        String originalName = Thread.currentThread().getName();
        try {
            Thread.currentThread().setName("quoted\"\\\n\t\u0001Ω");
            Path valid = record(dir, "valid", () -> {
                new Capture().commit();
                new Sample().commit();
                new Capture().commit();
                new Stats().commit();
                // JFR buffer serialization can place samples after terminal stats.
                new Sample().commit();
            });
            String output = export(valid);
            // Optional fixture for an independent JSON decoder.
            if (args.length == 1) {
                Files.writeString(Path.of(args[0]), output);
            }
            check(output.contains("\"correlationId\":\"80000001abcdef01\""), "Cookie bits lost");
            check(output.contains("\"monotonicTimeNanos\":\"18446744073709551615\""), "Unsigned nanos lost");
            check(output.contains("quoted\\\"\\\\\\n\\t\\u0001Ω"), "JSON escaping incorrect");
            check(output.contains("\"methodName\":\"lambda$main$0\""), "Sample stack missing");
            check(output.contains("\"osThreadId\":"), "OS thread identity missing");
            check(
                    output.endsWith("\"parseComplete\":true,\"captures\":\"2\",\"samples\":\"2\",\"stats\":\"1\"}\n"),
                    "Missing clean end record");
            String zeroSamples = export(record(dir, "zero", () -> {
                new Capture().commit();
                new Stats().commit();
            }));
            check(zeroSamples.contains("\"samples\":\"0\""), "Valid zero-sample capture rejected");
            invalid(record(dir, "missing-context", () -> new Sample().commit()), "Missing signal capture context");
            invalid(record(dir, "missing-stats", () -> new Capture().commit()), "Missing terminal");
            invalid(
                    record(dir, "double-stats", () -> {
                        new Capture().commit();
                        new Stats().commit();
                        new Stats().commit();
                    }),
                    "Multiple terminal");
            invalid(
                    record(dir, "bad-epoch", () -> {
                        new Capture().commit();
                        Sample sample = new Sample();
                        sample.correlationId = (5L << 32) | 1;
                        sample.commit();
                    }),
                    "cookie does not belong");
            invalid(
                    record(dir, "zero-sequence", () -> {
                        new Capture().commit();
                        Sample sample = new Sample();
                        sample.correlationId = EPOCH << 32;
                        sample.commit();
                    }),
                    "cookie does not belong");
            invalid(
                    record(dir, "new-schema", () -> {
                        Capture capture = new Capture();
                        capture.schemaVersion = 2;
                        capture.commit();
                    }),
                    "unsupported schema");
            invalid(
                    record(dir, "new-event", () -> {
                        new Capture().commit();
                        new FutureSample().commit();
                        new Stats().commit();
                    }),
                    "Unsupported signal event type");
            invalid(
                    record(dir, "new-context", () -> {
                        new Capture().commit();
                        Capture capture = new Capture();
                        capture.sessionId = UUID.randomUUID().toString();
                        capture.commit();
                    }),
                    "Conflicting signal capture context");
            invalid(
                    record(dir, "stats-context", () -> {
                        new Capture().commit();
                        Stats stats = new Stats();
                        stats.captureEpoch = 3;
                        stats.commit();
                    }),
                    "Conflicting signal capture identity");
            System.out.println("SignalJfrExporter fixtures passed");
        } finally {
            Thread.currentThread().setName(originalName);
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
