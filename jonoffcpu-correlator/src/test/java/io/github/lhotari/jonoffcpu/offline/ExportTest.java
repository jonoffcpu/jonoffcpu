// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SQL-friendly export: frame arrays, canonical stacks, pools, the run column, numeric counters, run metadata. The
 * checks that DuckDB reads the export are in the integration test {@code ExportDuckDbTest}.
 */
class ExportTest {
    static StackProfile.Frame frame(StackProfile.Kind kind, String name) {
        return new StackProfile.Frame(kind, name, kind == StackProfile.Kind.USER ? "libc.so.6" : "");
    }

    private static List<JsonObject> jsonl(Path file) throws Exception {
        List<JsonObject> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file))
            rows.add(JsonParser.parseString(line).getAsJsonObject());
        return rows;
    }

    static Path export(Path dir, String name, Path profile, String... extra) throws Exception {
        Path output = dir.resolve(name);
        List<String> args =
                new ArrayList<>(List.of("export", "--profile", profile.toString(), "--output", output.toString()));
        args.addAll(List.of(extra));
        CommandLineTest.Invocation invocation = CommandLineTest.invoke(args.toArray(String[]::new));
        assertThat(invocation.code()).as("Export failed: %s", invocation).isZero();
        return output;
    }

    /** Two entries: one with every stack and a lambda frame, one past 2^53 - 1 observed nanoseconds. */
    private static Path profile(Path dir) throws Exception {
        var java = StackProfile.Kind.JAVA;
        var jfrNative = StackProfile.Kind.JFR_NATIVE;
        List<StackProfile.Entry> entries = List.of(
                new StackProfile.Entry(
                        List.of(
                                frame(java, "a.B$$Lambda.0x0000000081a06030.run"),
                                frame(jfrNative, "libjvm.so.Unsafe_Park")),
                        List.of(
                                frame(StackProfile.Kind.KERNEL, "__schedule+0x1f"),
                                frame(StackProfile.Kind.KERNEL, "__traceiter_sched_switch+0x3")),
                        List.of(frame(StackProfile.Kind.USER, "futex_wait+0x10")),
                        OffCpuReason.BLOCKED,
                        1,
                        "pulsar-io-3-25",
                        2,
                        2000,
                        4000),
                new StackProfile.Entry(
                        List.of(frame(java, "x.App.main")),
                        null,
                        null,
                        OffCpuReason.BLOCKED,
                        1,
                        "[tid=12345]",
                        1,
                        // Past 2^53 - 1, a JSON number would lose precision, so it stays a string.
                        (1L << 53) + 1,
                        0));
        Path profile = dir.resolve("profile.pb");
        List<StackProfile.Provenance> sources = List.of(
                new StackProfile.Provenance("session-1", 7, "", "", "{\"reasons\":[\"blocked\"]}", "1", 0, 10, 20, ""));
        new StackProfile(
                        new StackProfile.Header(
                                sources, List.of("reason", "kernel", "user", "thread"), false, "{}", "", List.of()),
                        entries)
                .write(profile);
        return profile;
    }

    @Test
    void jsonlRows(@TempDir Path dir) throws Exception {
        List<JsonObject> rows = jsonl(export(dir, "rows.jsonl", profile(dir), "--format", "jsonl"));
        JsonObject first = rows.get(0);
        JsonObject second = rows.get(1);
        assertThat(first.getAsJsonArray("javaFrames").toString())
                .as("Java frames as an array: %s", first)
                .isEqualTo("[\"a.B$$Lambda.0x0000000081a06030.run\",\"libjvm.so.Unsafe_Park\"]");
        assertThat(first.getAsJsonArray("javaFrameKinds").toString())
                .as("Kinds: %s", first)
                .isEqualTo("[\"java\",\"native\"]");
        assertThat(first.getAsJsonArray("kernelFrames").toString())
                .as("Native frames as the joined columns render them: %s", first)
                .isEqualTo("[\"__schedule\"]");
        assertThat(first.getAsJsonArray("userFrames").toString())
                .as("Native frames as the joined columns render them: %s", first)
                .isEqualTo("[\"futex_wait\"]");
        assertThat(first.get("canonicalJavaStack").getAsString())
                .as("Canonical stack: %s", first)
                .isEqualTo("a.B$$Lambda.run;libjvm.so.Unsafe_Park");
        assertThat(first.get("threadPool").getAsString()).as("Pool: %s", first).isEqualTo("pulsar-io-#-#");
        assertThat(second.get("threadPool").getAsString())
                .as("Pool of a thread id")
                .isEqualTo("[tid=#]");
        assertThat(first.get("observedNanos").getAsJsonPrimitive().isNumber())
                .as("Counters are numbers: %s", first)
                .isTrue();
        assertThat(first.get("observedNanos").getAsLong())
                .as("Counters are numbers: %s", first)
                .isEqualTo(2000);
        assertThat(second.get("observedNanos").getAsJsonPrimitive().isString())
                .as("Past 2^53 - 1 a counter stays a string: %s", second)
                .isTrue();
        assertThat(second.get("observedNanos").getAsString())
                .as("Past 2^53 - 1 a counter stays a string: %s", second)
                .isEqualTo(Long.toString((1L << 53) + 1));
        assertThat(first.get("run").getAsString())
                .as("Run defaults to the first session: %s", first)
                .isEqualTo("session-1");
        assertThat(first.get("estimateAvailable").getAsBoolean())
                .as("The estimate's validity is on every row: %s", first)
                .isFalse();
        assertThat(second.get("kernelFrames").isJsonNull())
                .as("An absent stack is null")
                .isTrue();
        // Existing fields keep their names and order; the new ones follow them.
        List<String> names = new ArrayList<>(first.keySet());
        assertThat(names)
                .as("Field order")
                .startsWith("reason", "taskState", "thread")
                .endsWith("estimateAvailable");
        assertThat(names.indexOf("javaStackKinds")).as("Field order: %s", names).isEqualTo(15);
    }

    @Test
    void numbersAsStrings(@TempDir Path dir) throws Exception {
        List<JsonObject> strings =
                jsonl(export(dir, "strings.jsonl", profile(dir), "--format", "jsonl", "--numbers", "string"));
        assertThat(strings.get(0).get("observedNanos").getAsJsonPrimitive().isString())
                .as("--numbers string")
                .isTrue();
    }

    @Test
    void csvAndRunMetadata(@TempDir Path dir) throws Exception {
        Path metadata = dir.resolve("run.json");
        List<String> csv = Files.readAllLines(export(
                dir, "rows.csv", profile(dir), "--run-label", "baseline", "--run-metadata", metadata.toString()));
        assertThat(csv.get(0)).endsWith(",canonical_java_stack,thread_pool,run,estimate_available");
        assertThat(csv.get(1))
                .as("CSV row")
                .endsWith(",a.B$$Lambda.run;libjvm.so.Unsafe_Park,pulsar-io-#-#,baseline,false");
        JsonObject run = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
        JsonObject source = run.getAsJsonArray("sources").get(0).getAsJsonObject();
        assertThat(run.get("run").getAsString()).as("Run metadata: %s", run).isEqualTo("baseline");
        assertThat(run.get("entries").getAsInt()).as("Run metadata: %s", run).isEqualTo(2);
        assertThat(run.get("intervals").getAsInt()).as("Run metadata: %s", run).isEqualTo(3);
        assertThat(run.get("observedNanos").getAsString())
                .as("Run metadata: %s", run)
                .isEqualTo(Long.toString((1L << 53) + 2001));
        assertThat(source.get("captureEpoch").getAsInt())
                .as("Run metadata: %s", run)
                .isEqualTo(7);
        assertThat(source.get("windowToNanos").getAsLong())
                .as("Run metadata: %s", run)
                .isEqualTo(20);
        assertThat(run.get("estimateAvailable").getAsBoolean())
                .as("Run metadata: %s", run)
                .isFalse();
    }

    @Test
    void unknownNumbersIsAUsageError(@TempDir Path dir) throws Exception {
        CommandLineTest.usageError(
                "expected one of number, string",
                "export",
                "--profile",
                profile(dir).toString(),
                "--output",
                dir.resolve("refused").toString(),
                "--numbers",
                "text");
    }
}
