// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The analysis digest, {@code jonoffcpu-summary.json} and {@code .md}: a bounded summary of one capture for people and
 * AI agents, which states the capture's coverage and losses, where the time went, the ranked tables of {@link Top},
 * the heaviest transformed stacks, and for each table the command that reproduces it. The Markdown is rendered from
 * the JSON, so the two cannot disagree.
 */
final class Digest {
    static final int SCHEMA_VERSION = 1;

    /** What the digest is computed with. {@code app} empty ranks by the collapsed leaf instead of a boundary. */
    record Options(
            List<StackTransforms.Sourced> app,
            List<StackTransforms.Sourced> idle,
            List<StackTransforms.Sourced> machinery,
            int limit) {}

    private Digest() {}

    /** The default options: {@code preset:jvm-idle}, {@code preset:jvm-wait-machinery}, no application pattern. */
    static Options defaults() throws IOException {
        return new Options(
                List.of(),
                Cli.sourced(List.of(), "--idle-from", List.of("preset:jvm-idle")),
                Cli.sourced(List.of(), "--machinery-from", List.of("preset:jvm-wait-machinery")),
                20);
    }

    /**
     * The digest of a profile. {@code profilePath} is how the reproduce commands name the profile; {@code report} is
     * the correlation report, or null when there is none to summarise.
     */
    static JsonObject of(StackProfile profile, String profilePath, JsonObject report, Options options)
            throws IOException {
        boolean withApp = !options.app().isEmpty();
        List<StackTransforms.Sourced> collapse = options.machinery();
        // Canonical names keep generated-class addresses out of the callers, and out of a comparison between runs.
        StackTransforms transforms = new StackTransforms(
                true,
                List.of(),
                List.of(),
                List.of(),
                false,
                List.of(),
                withApp ? List.of() : collapse,
                false,
                StackTransforms.ThreadFrame.NONE);
        Top.Options topOptions = new Top.Options(
                withApp ? Top.By.BOUNDARY : Top.By.SELF,
                options.app(),
                options.idle(),
                options.machinery(),
                options.limit(),
                transforms,
                StackProfileRenderer.PackageNames.FULL,
                StackProfileRenderer.Weights.OBSERVED,
                null,
                StackProfileRenderer.Filter.NONE);
        List<String> topCommand = new ArrayList<>(List.of(Cli.NAME, "top", "--profile", profilePath));
        topCommand.addAll(patternOptions("--app", "--app-from", options.app()));
        topCommand.addAll(patternOptions("--idle", "--idle-from", options.idle()));
        topCommand.add("--canonical-names");
        if (!withApp) {
            topCommand.addAll(List.of("--by", "self"));
            topCommand.addAll(patternOptions("--collapse-leaf", "--collapse-leaf-from", collapse));
        }
        topCommand.addAll(List.of("--limit", Integer.toString(options.limit()), "--format", "md"));
        Top.Input input = Top.fromProfile(Path.of(profilePath), profile, topOptions);
        JsonObject tables = Top.tables(input, topOptions, Top.shell(topCommand));

        JsonObject digest = new JsonObject();
        digest.addProperty("schemaVersion", SCHEMA_VERSION);
        digest.addProperty("profile", profilePath);
        digest.addProperty("run", StackProfileRenderer.defaultRun(profile));
        digest.addProperty("estimateAvailable", profile.header().estimateAvailable());
        digest.addProperty("timeSplitAvailable", profile.header().timeSplitAvailable());
        if (report != null) digest.add("capture", capture(report));
        digest.add("selection", tables.get("selection"));
        digest.add("whereTheTimeWent", tables.get("totals"));

        JsonObject busy = new JsonObject();
        busy.addProperty("by", withApp ? "boundary" : "self");
        busy.addProperty("command", Top.shell(topCommand));
        busy.add("rows", tables.get("rows"));
        digest.add("busy", busy);

        JsonObject pools = new JsonObject();
        if (withApp) {
            pools.addProperty("command", Top.shell(topCommand));
            pools.add("rows", tables.get("noApplicationFrame"));
        } else if (input.pools()) {
            List<String> poolCommand = new ArrayList<>(topCommand);
            int by = poolCommand.indexOf("--by");
            poolCommand.set(by + 1, "pool");
            Top.Options poolOptions = new Top.Options(
                    Top.By.POOL,
                    options.app(),
                    options.idle(),
                    options.machinery(),
                    options.limit(),
                    transforms,
                    StackProfileRenderer.PackageNames.FULL,
                    StackProfileRenderer.Weights.OBSERVED,
                    null,
                    StackProfileRenderer.Filter.NONE);
            pools.addProperty("command", Top.shell(poolCommand));
            pools.add(
                    "rows",
                    Top.tables(input, poolOptions, Top.shell(poolCommand)).get("rows"));
        }
        if (pools.has("rows")) digest.add("busyNoApplicationFrameByPool", pools);

        JsonObject idle = new JsonObject();
        idle.addProperty("by", withApp ? "boundary" : "self");
        idle.addProperty("command", Top.shell(topCommand));
        idle.add("rows", tables.get("idle"));
        digest.add("idle", idle);

        digest.add("heaviestStacks", heaviest(profile, profilePath, options, withApp));
        digest.add("warnings", tables.get("warnings"));
        return digest;
    }

    private static List<String> patternOptions(String inline, String fromFile, List<StackTransforms.Sourced> patterns) {
        List<String> words = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (StackTransforms.Sourced pattern : patterns) {
            if (pattern.source().equals("inline")) {
                words.addAll(List.of(inline, pattern.pattern()));
            } else if (!sources.contains(pattern.source())) {
                sources.add(pattern.source());
                words.addAll(List.of(fromFile, pattern.source()));
            }
        }
        return words;
    }

    /** The capture's coverage and losses, from the correlation report. */
    static JsonObject capture(JsonObject report) {
        JsonObject capture = new JsonObject();
        JsonObject inputs = report.getAsJsonObject("analysisInputs");
        if (inputs != null) {
            capture.addProperty("sessionId", string(inputs, "sessionId"));
            if (inputs.has("sampling")) capture.add("sampling", inputs.get("sampling"));
        }
        for (String name : List.of(
                "sourceRows",
                "matched",
                "sourceRowsWithoutSelectedJfrSample",
                "orphanJfr",
                "invalidSource",
                "invalidJfr",
                "identityUnverified",
                "selectedObservedDurationNanos")) {
            if (report.has(name) && !report.get(name).isJsonNull()) capture.add(name, report.get(name));
        }
        JsonObject counters = report.getAsJsonObject("sourceCounters");
        if (counters != null) {
            JsonObject loss = new JsonObject();
            JsonObject kernel = counters.getAsJsonObject("kernel");
            JsonObject userspace = counters.getAsJsonObject("userspace");
            for (String name :
                    List.of("selectedIntervals", "sequenceContentions", "sequenceExhaustions", "ringReserveFailures")) {
                if (kernel != null && kernel.has(name)) loss.add(name, kernel.get(name));
            }
            for (String name : List.of("receivedObservations", "writeFailures", "pollFailures")) {
                if (userspace != null && userspace.has(name)) loss.add(name, userspace.get(name));
            }
            capture.add("loss", loss);
        }
        JsonObject delays = report.getAsJsonObject("handlerDelayNanos");
        if (delays != null) {
            JsonObject handler = new JsonObject();
            for (String name : List.of("p50", "p99", "max")) {
                if (delays.has(name)) handler.add(name, delays.get(name));
            }
            capture.add("handlerDelayNanos", handler);
        }
        JsonObject reasons = report.getAsJsonObject("offCpuReasons");
        if (reasons != null) {
            if (reasons.has("selected")) capture.add("reasons", reasons.get("selected"));
            if (reasons.has("kernelSwitchOuts")) capture.add("kernelSwitchOuts", reasons.get("kernelSwitchOuts"));
        }
        if (report.has("populationEstimate") && report.get("populationEstimate").isJsonObject()) {
            JsonObject estimate = report.getAsJsonObject("populationEstimate");
            JsonObject summary = new JsonObject();
            for (String name : List.of("status", "estimatedDurationNanos", "accountedLoss", "unavailableReasons")) {
                if (estimate.has(name)) summary.add(name, estimate.get(name));
            }
            capture.add("populationEstimate", summary);
        }
        if (report.has("degradation")) {
            JsonArray steps = report.getAsJsonObject("degradation").getAsJsonArray("stepsApplied");
            if (steps != null) capture.add("degradationSteps", steps);
        }
        return capture;
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull()
                ? object.get(name).getAsString()
                : null;
    }

    /** The heaviest busy stacks after the transforms that make them short, ten lines. */
    private static JsonObject heaviest(StackProfile profile, String profilePath, Options options, boolean withApp)
            throws IOException {
        StackTransforms transforms = withApp
                ? new StackTransforms(
                        true,
                        List.of(),
                        List.of(),
                        options.app(),
                        false,
                        List.of(),
                        options.machinery(),
                        false,
                        StackTransforms.ThreadFrame.NONE)
                : new StackTransforms(
                        true,
                        List.of(),
                        Cli.sourced(List.of(), "--trim-root-from", List.of("preset:jvm-infra")),
                        List.of(),
                        false,
                        List.of(),
                        options.machinery(),
                        false,
                        StackTransforms.ThreadFrame.NONE);
        StackProfileRenderer.Filter filter = StackProfileRenderer.Filter.of(
                List.of(),
                options.idle().stream().map(StackTransforms.Sourced::pattern).toList());
        StackProfileRenderer.Slice slice = StackProfileRenderer.render(
                profile,
                null,
                StackProfileRenderer.StackKinds.JAVA,
                StackProfileRenderer.Weights.OBSERVED,
                StackProfileRenderer.ReasonFrame.AUTO,
                StackProfileRenderer.Time.TOTAL,
                filter,
                StackProfileRenderer.PackageNames.DROP,
                transforms);
        List<String> command = new ArrayList<>(List.of(Cli.NAME, "stacks", "--profile", profilePath));
        command.addAll(patternOptions("--exclude", "--exclude-from", options.idle()));
        if (withApp) {
            command.addAll(patternOptions("--root-at", "--root-at-from", options.app()));
        } else {
            command.addAll(List.of("--trim-root-from", "preset:jvm-infra"));
        }
        command.addAll(patternOptions("--collapse-leaf", "--collapse-leaf-from", options.machinery()));
        command.addAll(List.of("--canonical-names", "--package-names", "drop", "--output", "busy.collapsed"));
        JsonObject heaviest = new JsonObject();
        heaviest.addProperty("command", Top.shell(command));
        heaviest.addProperty("lines", slice.nanos().size());
        heaviest.addProperty(
                "meanDepth",
                Cli.meanDepth(slice.nanos(), profile.header().label().length()));
        List<Map.Entry<String, BigInteger>> lines =
                new ArrayList<>(slice.nanos().entrySet());
        lines.sort(
                Map.Entry.<String, BigInteger>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        JsonArray top = new JsonArray();
        for (Map.Entry<String, BigInteger> line : lines.subList(0, Math.min(10, lines.size()))) {
            JsonObject item = new JsonObject();
            item.addProperty(
                    "stack", line.getKey().substring(profile.header().label().length()));
            item.addProperty(
                    "seconds",
                    new BigDecimal(line.getValue())
                            .divide(BigDecimal.valueOf(1_000_000_000L))
                            .setScale(3, java.math.RoundingMode.HALF_EVEN));
            top.add(item);
        }
        heaviest.add("top", top);
        return heaviest;
    }

    // ---- Markdown ------------------------------------------------------------------------------------------------

    /** The digest as Markdown, rendered from its JSON. */
    static String markdown(JsonObject digest) {
        StringBuilder text = new StringBuilder();
        text.append("# jonoffcpu analysis digest\n\n");
        text.append("Profile `").append(digest.get("profile").getAsString()).append("`");
        if (!digest.get("run").getAsString().isEmpty()) {
            text.append(", run `").append(digest.get("run").getAsString()).append('`');
        }
        text.append(". Times are observed seconds of off-CPU time")
                .append(
                        digest.get("estimateAvailable").getAsBoolean()
                                ? "; the population estimate is available (top --weights estimated)."
                                : "; the population estimate is unavailable, so under proportional or uniform sampling short"
                                        + " waits are under-weighted.")
                .append("\n\n");
        if (digest.has("capture")) capture(digest.getAsJsonObject("capture"), text);

        JsonObject totals = digest.getAsJsonObject("whereTheTimeWent");
        text.append("## Where the time went\n\n| Slice | Entries | Intervals | s |\n|---|---:|---:|---:|\n");
        for (String[] slice : new String[][] {
            {"selected", "All selected"},
            {"idle", "Idle"},
            {"busy", "Busy"},
            {"busyApplication", "Busy, with an application frame"},
            {"busyNoApplicationFrame", "Busy, no application frame"},
            {"overExclusion", "Over-exclusion check: idle entries with a lock-acquire frame"}
        }) {
            if (!totals.has(slice[0])) continue;
            JsonObject sum = totals.getAsJsonObject(slice[0]);
            text.append("| ")
                    .append(slice[1])
                    .append(" | ")
                    .append(sum.get("entries").getAsLong())
                    .append(" | ")
                    .append(sum.get("intervals").getAsLong())
                    .append(" | ")
                    .append(Top.number(sum.get("value")))
                    .append(" |\n");
        }
        text.append("\nIdle means a frame of the stack matched one of the idle patterns ")
                .append(patterns(digest.getAsJsonObject("selection").getAsJsonArray("idle")))
                .append(". A nonzero over-exclusion check means idle patterns hid waits on a lock or monitor.\n\n");

        JsonObject busy = digest.getAsJsonObject("busy");
        boolean boundary = busy.get("by").getAsString().equals("boundary");
        text.append(
                boundary
                        ? "## Busy, by application boundary\n\nEach row is the deepest application frame (its boundary) and"
                                + " the blocker below it.\n\n"
                        : "## Busy, by leaf\n\nNo application pattern was given, so each row is the stack's leaf after"
                                + " collapsing the wait machinery; pass --app to rank by application boundary.\n\n");
        section(busy, text);
        if (digest.has("busyNoApplicationFrameByPool")) {
            text.append(
                    boundary
                            ? "## Busy without an application frame, by pool\n\nNative symbolization matters here: on musl"
                                    + " every native frame is /lib/ld-musl-<arch>.so.1, and idle JVM threads count as busy.\n\n"
                            : "## Busy, by pool\n\n");
            section(digest.getAsJsonObject("busyNoApplicationFrameByPool"), text);
        }
        text.append(boundary ? "## Idle, by boundary\n\n" : "## Idle, by leaf\n\n");
        section(digest.getAsJsonObject("idle"), text);

        JsonObject heaviest = digest.getAsJsonObject("heaviestStacks");
        text.append("## Heaviest transformed stacks\n\nThe busy slice renders as ")
                .append(heaviest.get("lines").getAsLong())
                .append(" lines at a mean depth of ")
                .append(heaviest.get("meanDepth").getAsBigDecimal().toPlainString())
                .append(" frames. Reproduce: `")
                .append(heaviest.get("command").getAsString())
                .append("`\n\n| s | Stack |\n|---:|---|\n");
        for (JsonElement element : heaviest.getAsJsonArray("top")) {
            JsonObject line = element.getAsJsonObject();
            text.append("| ")
                    .append(Top.number(line.get("seconds")))
                    .append(" | ")
                    .append(Top.code(line.get("stack").getAsString()))
                    .append(" |\n");
        }
        JsonArray warnings = digest.getAsJsonArray("warnings");
        if (!warnings.isEmpty()) {
            text.append('\n');
            for (JsonElement warning : warnings)
                text.append("> **Warning:** ").append(warning.getAsString()).append("\n");
        }
        text.append("\n## How to reproduce\n\n");
        text.append("- Busy and idle tables: `")
                .append(busy.get("command").getAsString())
                .append("`\n");
        if (digest.has("busyNoApplicationFrameByPool")
                && !digest.getAsJsonObject("busyNoApplicationFrameByPool")
                        .get("command")
                        .getAsString()
                        .equals(busy.get("command").getAsString())) {
            text.append("- Pool table: `")
                    .append(digest.getAsJsonObject("busyNoApplicationFrameByPool")
                            .get("command")
                            .getAsString())
                    .append("`\n");
        }
        text.append("- Heaviest stacks: `")
                .append(heaviest.get("command").getAsString())
                .append("`\n");
        text.append("- Any other question: `")
                .append(Cli.NAME)
                .append(" export --profile ")
                .append(digest.get("profile").getAsString())
                .append(" --format jsonl --output entries.jsonl`\n");
        return text.toString();
    }

    private static String patterns(JsonArray patterns) {
        List<String> sources = new ArrayList<>();
        for (JsonElement element : patterns) {
            JsonObject pattern = element.getAsJsonObject();
            String source = pattern.get("source").getAsString();
            String shown =
                    source.equals("inline") ? "`" + pattern.get("pattern").getAsString() + "`" : source;
            if (!sources.contains(shown)) sources.add(shown);
        }
        return sources.isEmpty() ? "(none)" : String.join(", ", sources);
    }

    private static void section(JsonObject section, StringBuilder text) {
        JsonArray rows = section.getAsJsonArray("rows");
        String keyName = "key";
        if (!rows.isEmpty()) {
            for (String name : List.of("boundary", "pool")) {
                if (rows.get(0).getAsJsonObject().has(name)) keyName = name;
            }
        }
        StringBuilder table = new StringBuilder();
        Top.table(rows, keyName, "s", "Intervals", "intervals", table);
        text.append(table).append('\n');
    }

    private static void capture(JsonObject capture, StringBuilder text) {
        text.append("## Capture\n\n");
        if (capture.has("reasons"))
            text.append("- Reasons recorded: ").append(capture.get("reasons")).append('\n');
        if (capture.has("sampling"))
            text.append("- Sampling: `").append(capture.get("sampling")).append("`\n");
        if (capture.has("sourceRows")) {
            text.append("- Source rows ")
                    .append(capture.get("sourceRows").getAsString())
                    .append(", matched ")
                    .append(capture.has("matched") ? capture.get("matched").getAsString() : "?")
                    .append(", outside the selected JFR window ")
                    .append(
                            capture.has("sourceRowsWithoutSelectedJfrSample")
                                    ? capture.get("sourceRowsWithoutSelectedJfrSample")
                                            .getAsString()
                                    : "0")
                    .append(", orphan JFR ")
                    .append(capture.has("orphanJfr") ? capture.get("orphanJfr").getAsString() : "0")
                    .append(", invalid ")
                    .append(
                            capture.has("invalidSource")
                                    ? capture.get("invalidSource").getAsString()
                                    : "0")
                    .append('/')
                    .append(
                            capture.has("invalidJfr")
                                    ? capture.get("invalidJfr").getAsString()
                                    : "0")
                    .append('\n');
        }
        if (capture.has("loss")) {
            List<String> parts = new ArrayList<>();
            for (var entry : capture.getAsJsonObject("loss").entrySet()) {
                parts.add(entry.getKey() + " " + entry.getValue().getAsString());
            }
            text.append("- Collector: ").append(String.join(", ", parts)).append('\n');
        }
        if (capture.has("handlerDelayNanos")) {
            JsonObject delays = capture.getAsJsonObject("handlerDelayNanos");
            text.append("- Signal handler delay: p50 ")
                    .append(micros(delays.get("p50")))
                    .append(", p99 ")
                    .append(micros(delays.get("p99")))
                    .append('\n');
        }
        if (capture.has("kernelSwitchOuts")) {
            List<String> parts = new ArrayList<>();
            for (var entry : capture.getAsJsonObject("kernelSwitchOuts").entrySet()) {
                parts.add(entry.getKey() + " " + entry.getValue().getAsString());
            }
            text.append("- Kernel switch-outs by reason: ")
                    .append(String.join(", ", parts))
                    .append('\n');
        }
        if (capture.has("populationEstimate")) {
            JsonObject estimate = capture.getAsJsonObject("populationEstimate");
            text.append("- Population estimate: ").append(estimate.get("status").getAsString());
            if (estimate.has("accountedLoss") && estimate.get("accountedLoss").isJsonObject()) {
                JsonObject loss = estimate.getAsJsonObject("accountedLoss");
                text.append(", accounted loss ")
                        .append(loss.get("intervals").getAsString())
                        .append(" intervals (")
                        .append(Top.percent(loss.get("fraction").getAsBigDecimal()))
                        .append(", ")
                        .append(loss.get("reason").getAsString())
                        .append(")");
            }
            text.append('\n');
        }
        if (capture.has("degradationSteps")
                && !capture.getAsJsonArray("degradationSteps").isEmpty()) {
            text.append("- Degradation steps applied: ")
                    .append(capture.get("degradationSteps"))
                    .append('\n');
        }
        text.append('\n');
    }

    private static String micros(JsonElement nanos) {
        if (nanos == null || nanos.isJsonNull()) return "n/a";
        return new BigDecimal(nanos.getAsString())
                        .divide(BigDecimal.valueOf(1000))
                        .setScale(1, java.math.RoundingMode.HALF_EVEN)
                + " µs";
    }

    /** Writes both files; they must not exist. */
    static void write(JsonObject digest, Path json, Path markdown) throws IOException {
        try (BufferedWriter writer = OffCpuCorrelator.newFile(json)) {
            new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(digest, writer);
            writer.newLine();
        }
        try (BufferedWriter writer = OffCpuCorrelator.newFile(markdown)) {
            writer.write(markdown(digest));
        }
    }
}
