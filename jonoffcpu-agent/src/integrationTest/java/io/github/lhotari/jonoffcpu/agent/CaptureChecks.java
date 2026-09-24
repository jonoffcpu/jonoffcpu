// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
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
            correlatorRun = Class.forName("io.github.lhotari.jonoffcpu.offline.OffCpuCorrelator", true, loader)
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

    static JsonObject json(Path file) throws Exception {
        assertThat(file).isRegularFile();
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    static JsonObject manifest(Path out) throws Exception {
        return json(out.resolve(MANIFEST));
    }

    /** The manifest says the capture completed. */
    static JsonObject completeManifest(Path out) throws Exception {
        JsonObject manifest = manifest(out);
        assertThat(manifest.get("complete").getAsBoolean())
                .as("manifest complete: %s", manifest)
                .isTrue();
        assertThat(manifest.get("state").getAsString()).as("manifest state").isEqualTo("complete");
        return manifest;
    }

    /** The capture stream as JSON rows, through the correlator's dump. */
    static List<JsonObject> captureRows(Path out) throws Exception {
        List<JsonObject> rows = new ArrayList<>();
        for (String line : correlate("dump", "--source", out.resolve(SOURCE).toString())
                .lines()
                .toList()) {
            if (line.startsWith("{")) rows.add(JsonParser.parseString(line).getAsJsonObject());
        }
        return rows;
    }

    /** The capture ends in exactly one complete footer, which it returns. */
    static JsonObject completeFooter(Path out) throws Exception {
        List<JsonObject> rows = captureRows(out);
        List<JsonObject> footers = rows.stream()
                .filter(row -> row.has("recordType")
                        && row.get("recordType").getAsString().equals("captureFinalized"))
                .toList();
        assertThat(footers).as("captureFinalized rows").hasSize(1);
        JsonObject footer = footers.get(0);
        assertThat(rows.get(rows.size() - 1)).as("the footer is the last row").isSameAs(footer);
        assertThat(footer.get("state").getAsString()).as("footer state").isEqualTo("complete");
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
    static JsonObject correlateVerified(Path out, Path analysis, String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "--source", out.resolve(SOURCE).toString(),
                "--jfr", out.resolve(JFR).toString(),
                "--output", analysis.toString()));
        args.addAll(List.of(extra));
        correlate(args.toArray(String[]::new));
        JsonObject report = json(analysis.resolve("jonoffcpu-report.json"));
        assertThat(report.get("matched").getAsLong())
                .as("matched off-CPU samples")
                .isPositive();
        assertThat(report.get("invalidSource").getAsLong())
                .as("invalid source rows")
                .isZero();
        assertThat(report.get("invalidJfr").getAsLong())
                .as("invalid JFR samples")
                .isZero();
        return report;
    }
}
