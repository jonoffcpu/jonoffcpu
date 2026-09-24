// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.jonoffcpu.jonoffcpu.agent.ManifestProto.Manifest;
import io.github.jonoffcpu.jonoffcpu.agent.ManifestProto.ManifestState;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureFormat;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto.CaptureFinalized;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto.FinalizedState;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto.Record;
import io.github.jonoffcpu.jonoffcpu.capture.ProtoJson;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordingFile;

/**
 * What every end-to-end capture is checked for, with the packaged correlator's command line run as a user runs it,
 * from its JAR in a class loader of its own: the agent and the correlator each embed their own copy of the capture
 * format's classes.
 */
final class CaptureChecks {
    static final String SOURCE = "jonoffcpu-capture.pb";
    static final String JFR = "jonoffcpu-capture.jfr";
    static final String MANIFEST = "jonoffcpu-capture.manifest.json";

    /** The asyncProfilerOptions the packaged smoke starts async-profiler with. */
    static final String PROFILER_OPTIONS =
            "event=cpu,alloc=1m,wall=10ms,lock=1ms,jfrsync=profile,file=" + AgentRuntime.OUT + "/" + JFR;

    private CaptureChecks() {}

    /** The correlator's output and exit code. */
    record Run(int exitCode, String output) {}

    /**
     * The packaged correlator's command line, {@code OffCpuCorrelator.run}, which never exits the JVM. It is loaded
     * from the correlator JAR alone, beside the JDK, so its classes never meet the agent's.
     */
    private static Method correlatorRun;

    /**
     * Runs {@code java -jar jonoffcpu-correlator.jar args} in-process, with its standard output and error captured.
     * An analysis failure, which the command line throws, is exit code -1 with the exception in the output.
     */
    static synchronized Run correlator(String... args) throws Exception {
        if (correlatorRun == null) {
            ClassLoader loader = new URLClassLoader(
                    new URL[] {AgentRuntime.correlatorJar().toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            correlatorRun = Class.forName("io.github.jonoffcpu.jonoffcpu.offline.OffCpuCorrelator", true, loader)
                    .getMethod("run", String[].class);
        }
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            System.setErr(capture);
            int exitCode;
            try {
                exitCode = (int) correlatorRun.invoke(null, (Object) args);
            } catch (InvocationTargetException failure) {
                capture.println(failure.getCause());
                exitCode = -1;
            }
            return new Run(exitCode, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    /** Runs the correlator, which must succeed. */
    static String correlate(String... args) throws Exception {
        Run run = correlator(args);
        assertThat(run.exitCode())
                .as("the correlator's exit code; output:%n%s", run.output())
                .isZero();
        return run.output();
    }

    /**
     * A JSON file the correlator wrote, as a generic tree: its formats are the correlator's own messages, printed in
     * the proto3 JSON mapping, so 64-bit integers are decimal strings and enums their value names.
     */
    static Struct json(Path file) throws Exception {
        assertThat(file).isRegularFile();
        return jsonLine(Files.readString(file));
    }

    static Struct jsonLine(String json) throws Exception {
        return ProtoJson.parse(json, Struct.newBuilder()).build();
    }

    /** The value at a path of field names, which must exist. */
    static Value value(Struct object, String... path) {
        Value value = Value.newBuilder().setStructValue(object).build();
        for (String name : path) {
            assertThat(value.hasStructValue()).as("an object holds %s", name).isTrue();
            assertThat(value.getStructValue().getFieldsMap())
                    .as("the fields around %s", name)
                    .containsKey(name);
            value = value.getStructValue().getFieldsOrThrow(name);
        }
        return value;
    }

    static Struct object(Struct object, String... path) {
        return value(object, path).getStructValue();
    }

    static String string(Struct object, String... path) {
        return value(object, path).getStringValue();
    }

    /** An integer, which the proto3 JSON mapping prints as a string when it has 64 bits and as a number otherwise. */
    static long number(Struct object, String... path) {
        Value value = value(object, path);
        return value.hasStringValue() ? Long.parseLong(value.getStringValue()) : (long) value.getNumberValue();
    }

    static Manifest manifest(Path out) throws Exception {
        Path file = out.resolve(MANIFEST);
        assertThat(file).isRegularFile();
        return ProtoJson.parse(Files.readString(file), Manifest.newBuilder()).build();
    }

    /** The manifest says the capture completed. */
    static Manifest completeManifest(Path out) throws Exception {
        Manifest manifest = manifest(out);
        assertThat(manifest.getComplete()).as("manifest complete: %s", manifest).isTrue();
        assertThat(manifest.getState()).as("manifest state").isEqualTo(ManifestState.MANIFEST_STATE_COMPLETE);
        return manifest;
    }

    /**
     * The capture stream's records, read with the capture codec. A truncated final record, which only an abrupt
     * exit leaves, is dropped when {@code allowTruncatedTail} says so and is a failure otherwise.
     */
    static List<Record> captureRecords(Path out, boolean allowTruncatedTail) throws Exception {
        List<Record> records = new ArrayList<>();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(out.resolve(SOURCE)))) {
            CaptureFormat.readHeader(input);
            CaptureFormat.Framed framed;
            while ((framed = CaptureFormat.next(input, 1024 * 1024)) != null) {
                if (framed.truncated()) {
                    assertThat(allowTruncatedTail)
                            .as("the stream ends inside a record")
                            .isTrue();
                    break;
                }
                records.add(framed.record());
            }
        }
        return records;
    }

    /** The capture ends in exactly one complete footer, which it returns. */
    static CaptureFinalized completeFooter(Path out) throws Exception {
        List<Record> records = captureRecords(out, false);
        assertThat(records.stream().filter(Record::hasCaptureFinalized))
                .as("captureFinalized records")
                .hasSize(1);
        Record last = records.get(records.size() - 1);
        assertThat(last.hasCaptureFinalized())
                .as("the footer is the last record")
                .isTrue();
        CaptureFinalized footer = last.getCaptureFinalized();
        assertThat(footer.getState()).as("footer state").isEqualTo(FinalizedState.FINALIZED_STATE_COMPLETE);
        return footer;
    }

    /** The number of events of each type in the recording, by each event's resolved name. */
    static Map<String, Long> eventCounts(Path jfr) throws Exception {
        Map<String, Long> counts = new TreeMap<>();
        try (RecordingFile file = new RecordingFile(jfr)) {
            while (file.hasMoreEvents())
                counts.merge(file.readEvent().getEventType().getName(), 1L, Long::sum);
        }
        return counts;
    }

    /**
     * The combined recording holds CPU, allocation, wall-clock, lock, signal-cookie, ordinary JDK and test marker
     * events: async-profiler's jfrsync=profile recording and the JVM's own share one file.
     */
    static Map<String, Long> checkMixedRecording(Path jfr) throws Exception {
        Map<String, Long> counts = eventCounts(jfr);
        assertThat(counts)
                .as("event categories of the combined recording")
                .containsKeys(
                        "jdk.ExecutionSample",
                        "profiler.SignalSample",
                        "profiler.WallClockSample",
                        "jdk.JavaMonitorEnter",
                        "jdk.JVMInformation",
                        "jdk.GCHeapSummary",
                        "jonoffcpu.IntegrationMarker");
        assertThat(counts.getOrDefault("jdk.ObjectAllocationInNewTLAB", 0L)
                        + counts.getOrDefault("jdk.ObjectAllocationOutsideTLAB", 0L))
                .as("allocation samples")
                .isPositive();
        return counts;
    }

    /** Correlates the capture into {@code analysis} and checks that it verified off-CPU matches. */
    static Struct correlateVerified(Path out, Path analysis, String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "--source", out.resolve(SOURCE).toString(),
                "--jfr", out.resolve(JFR).toString(),
                "--output", analysis.toString()));
        args.addAll(List.of(extra));
        correlate(args.toArray(String[]::new));
        Struct report = json(analysis.resolve("jonoffcpu-report.json"));
        assertThat(number(report, "matched")).as("matched off-CPU samples").isPositive();
        assertThat(number(report, "invalidSource")).as("invalid source rows").isZero();
        assertThat(number(report, "invalidJfr")).as("invalid JFR samples").isZero();
        return report;
    }
}
