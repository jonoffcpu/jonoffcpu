// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Acceptance check that keeps scheduler intervals separate from delivery-stack evidence. */
public final class KnownWaitAttributionCheck {
    private static final Set<String> REQUIRED_THREADS = Set.of(
            "jonoffcpu-known-sleep",
            "jonoffcpu-known-park",
            "jonoffcpu-known-native-wait",
            "jonoffcpu-known-rapid-reblock");

    private KnownWaitAttributionCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected source NDJSON, original JFR, workload JSON, and report JSON");
        }
        Path source = Path.of(args[0]);
        Path jfr = Path.of(args[1]);
        Path metadata = Path.of(args[2]);
        Path output = Path.of(args[3]);
        OfflineCorrelator.Analysis analysis =
                OfflineCorrelator.correlate(source, jfr, OfflineCorrelator.Limits.defaults());
        Fixture fixture = readFixture(metadata);
        check(
                analysis.invalidSource() == 0 && analysis.invalidJfr() == 0,
                "Known-wait capture contains invalid source or JFR rows");
        check(analysis.identityUnverified() == 0, "Known-wait matches lack verified OS-thread identity");

        Map<Long, ThreadSummary> summaries = new HashMap<>();
        fixture.tids().forEach((name, tid) -> summaries.put(tid, new ThreadSummary(name, tid)));
        for (Operation operation : fixture.operations()) {
            ThreadSummary summary = summaries.get(operation.tid());
            summary.fixtureOperations++;
            summary.fixtureRequestedNanos = summary.fixtureRequestedNanos.add(operation.requested());
            summary.fixtureActualNanos = summary.fixtureActualNanos.add(operation.actual());
        }
        for (OfflineCorrelator.ClassifiedRecord classified : analysis.records()) {
            if (!classified.stream().equals("source")
                    || classified.classification().equals("invalid")) continue;
            JsonObject row = classified.record();
            ThreadSummary summary = summaries.get(row.get("targetTid").getAsLong());
            if (summary == null) continue;
            BigInteger start = decimal(row, "startMonotonicNanos");
            BigInteger end = decimal(row, "endMonotonicNanos");
            BigInteger overlap = fixture.overlap(summary.tid, start, end);
            if (overlap.signum() > 0) {
                summary.sourceRows++;
                summary.sourceIntervalNanos = summary.sourceIntervalNanos.add(end.subtract(start));
                summary.sourceFixtureOverlapNanos = summary.sourceFixtureOverlapNanos.add(overlap);
            }
        }
        for (OfflineCorrelator.Match match : analysis.matches()) {
            ThreadSummary summary =
                    summaries.get(match.observation().get("targetTid").getAsLong());
            if (summary == null) continue;
            summary.matchedRows++;
            summary.matchedIntervalNanos = summary.matchedIntervalNanos.add(match.durationNanos());
            summary.handlerDelays.add(match.handlerDelayNanos());
            summary.deliveryStacks.merge(stack(match.sample()), 1, Integer::sum);
            BigInteger handlerTime =
                    decimal(match.observation(), "endMonotonicNanos").add(match.handlerDelayNanos());
            if (fixture.insidePhase(summary.tid, "reblock", handlerTime)) summary.deliveredDuringReblock++;
        }

        JsonObject threads = new JsonObject();
        for (ThreadSummary summary : summaries.values().stream()
                .sorted(java.util.Comparator.comparing(item -> item.name))
                .toList()) {
            check(
                    summary.sourceRows > 0 && summary.sourceIntervalNanos.signum() > 0,
                    "No scheduler source interval overlapped fixture waits for " + summary.name);
            check(
                    summary.matchedRows > 0 && !summary.deliveryStacks.isEmpty(),
                    "No signal-delivery stack matched " + summary.name);
            check(
                    summary.handlerDelays.stream().allMatch(delay -> delay.signum() >= 0),
                    "Negative handler delay for " + summary.name);
            JsonObject row = new JsonObject();
            row.addProperty("targetTid", summary.tid);
            row.addProperty("fixtureOperations", summary.fixtureOperations);
            row.addProperty("fixtureRequestedNanos", summary.fixtureRequestedNanos.toString());
            row.addProperty("fixtureActualNanos", summary.fixtureActualNanos.toString());
            row.addProperty("sourceRowsOverlappingFixture", summary.sourceRows);
            row.addProperty("sourceObservedIntervalNanos", summary.sourceIntervalNanos.toString());
            row.addProperty("sourceOverlapWithFixtureNanos", summary.sourceFixtureOverlapNanos.toString());
            row.addProperty("matchedDeliveryRows", summary.matchedRows);
            row.addProperty("matchedDeliveryWeightedNanos", summary.matchedIntervalNanos.toString());
            row.add("handlerDelayNanos", delaySummary(summary.handlerDelays));
            row.add(
                    "signalDeliveryStacks",
                    new GsonBuilder().create().toJsonTree(new TreeMap<>(summary.deliveryStacks)));
            if (summary.name.equals("jonoffcpu-known-rapid-reblock")) {
                row.addProperty("deliveriesWhoseTranslatedHandlerTimeWasDuringReblock", summary.deliveredDuringReblock);
            }
            threads.add(summary.name, row);
        }

        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("result", "pass");
        report.addProperty("sourceTimingSemantics", "scheduler off-CPU intervals on Linux CLOCK_MONOTONIC");
        report.addProperty("stackTimingSemantics", "post-resumption signal-delivery stack");
        report.addProperty(
                "handlerDelaySemantics", "source interval end to translated async-profiler handler timestamp");
        report.addProperty("blockingStackIdentityClaimed", false);
        report.addProperty("sourceRows", analysis.sourceRows());
        report.addProperty("matchedRows", analysis.matched());
        report.add("threads", threads);
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        System.out.println("Known-wait attribution check passed: " + output);
    }

    private static Fixture readFixture(Path path) throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(path)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        check(root.get("schemaVersion").getAsInt() == 1, "Unsupported fixture schema");
        check(root.get("clock").getAsString().equals("Linux CLOCK_MONOTONIC"), "Unexpected fixture clock");
        Map<String, Long> tids = new TreeMap<>();
        for (var entry : root.getAsJsonObject("targetTids").entrySet()) {
            tids.put(entry.getKey(), entry.getValue().getAsLong());
        }
        check(tids.keySet().equals(REQUIRED_THREADS), "Fixture thread set is incomplete");
        check(new HashSet<>(tids.values()).size() == REQUIRED_THREADS.size(), "Fixture TIDs are not unique");
        int iterations = root.get("iterations").getAsInt();
        Map<String, String> expectedKinds = Map.of(
                "jonoffcpu-known-sleep", "sleep",
                "jonoffcpu-known-park", "park",
                "jonoffcpu-known-native-wait", "native-wait",
                "jonoffcpu-known-rapid-reblock", "rapid-reblock");
        List<Operation> operations = new ArrayList<>();
        JsonArray array = root.getAsJsonArray("operations");
        for (JsonElement element : array) {
            JsonObject row = element.getAsJsonObject();
            Operation operation = new Operation(
                    row.get("threadName").getAsString(),
                    row.get("targetTid").getAsLong(),
                    row.get("waitKind").getAsString(),
                    row.get("phase").getAsString(),
                    decimal(row, "requestedDurationNanos"),
                    decimal(row, "startMonotonicNanos"),
                    decimal(row, "requestedEndMonotonicNanos"),
                    decimal(row, "endMonotonicNanos"),
                    decimal(row, "actualDurationNanos"));
            check(tids.getOrDefault(operation.threadName(), -1L) == operation.tid(), "Operation TID mismatch");
            check(
                    operation.requestedEnd().subtract(operation.start()).equals(operation.requested()),
                    "Invalid requested wait timing");
            check(
                    operation.end().compareTo(operation.start()) >= 0
                            && operation.end().subtract(operation.start()).equals(operation.actual()),
                    "Invalid actual wait timing");
            check(
                    operation.requested().signum() > 0 && operation.actual().signum() > 0,
                    "Wait timing must be positive");
            check(
                    operation.kind().equals(expectedKinds.get(operation.threadName())),
                    "Unexpected wait kind for " + operation.threadName());
            operations.add(operation);
        }
        for (String name : REQUIRED_THREADS) {
            long expected = name.equals("jonoffcpu-known-rapid-reblock") ? iterations * 2L : iterations;
            check(
                    operations.stream()
                                    .filter(operation -> operation.threadName().equals(name))
                                    .count()
                            == expected,
                    "Unexpected operation count for " + name);
        }
        check(
                operations.stream()
                                .filter(operation -> operation.phase().equals("reblock"))
                                .count()
                        == iterations,
                "Rapid re-block phase count is incomplete");
        return new Fixture(tids, operations);
    }

    private static JsonObject delaySummary(List<BigInteger> values) {
        values.sort(BigInteger::compareTo);
        JsonObject result = new JsonObject();
        result.addProperty("count", values.size());
        result.addProperty("p50", percentile(values, 50).toString());
        result.addProperty("p99", percentile(values, 99).toString());
        result.addProperty("max", values.get(values.size() - 1).toString());
        return result;
    }

    private static BigInteger percentile(List<BigInteger> values, int percentile) {
        int rank = (int) ((values.size() * (long) percentile + 99) / 100);
        return values.get(rank - 1);
    }

    private static String stack(JsonObject sample) {
        JsonArray frames = sample.getAsJsonArray("frames");
        if (frames.isEmpty()) return "[stack unavailable]";
        List<String> names = new ArrayList<>();
        for (int index = frames.size() - 1; index >= 0; index--) {
            JsonObject frame = frames.get(index).getAsJsonObject();
            String type = text(frame, "className", "");
            String method = text(frame, "methodName", "[unresolved]");
            names.add((type.isEmpty() ? method : type + "." + method).replace(';', ':'));
        }
        return String.join(";", names);
    }

    private static String text(JsonObject row, String key, String fallback) {
        JsonElement value = row.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static BigInteger decimal(JsonObject row, String key) {
        return new BigInteger(row.get(key).getAsString());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Operation(
            String threadName,
            long tid,
            String kind,
            String phase,
            BigInteger requested,
            BigInteger start,
            BigInteger requestedEnd,
            BigInteger end,
            BigInteger actual) {}

    private record Fixture(Map<String, Long> tids, List<Operation> operations) {
        BigInteger overlap(long tid, BigInteger start, BigInteger end) {
            BigInteger total = BigInteger.ZERO;
            for (Operation operation : operations) {
                if (operation.tid() != tid) continue;
                BigInteger from = start.max(operation.start());
                BigInteger to = end.min(operation.end());
                total = total.add(to.subtract(from).max(BigInteger.ZERO));
            }
            return total;
        }

        boolean insidePhase(long tid, String phase, BigInteger time) {
            return operations.stream()
                    .anyMatch(operation -> operation.tid() == tid
                            && operation.phase().equals(phase)
                            && time.compareTo(operation.start()) >= 0
                            && time.compareTo(operation.end()) <= 0);
        }
    }

    private static final class ThreadSummary {
        final String name;
        final long tid;
        int sourceRows;
        int matchedRows;
        int fixtureOperations;
        int deliveredDuringReblock;
        BigInteger fixtureRequestedNanos = BigInteger.ZERO;
        BigInteger fixtureActualNanos = BigInteger.ZERO;
        BigInteger sourceIntervalNanos = BigInteger.ZERO;
        BigInteger sourceFixtureOverlapNanos = BigInteger.ZERO;
        BigInteger matchedIntervalNanos = BigInteger.ZERO;
        final List<BigInteger> handlerDelays = new ArrayList<>();
        final Map<String, Integer> deliveryStacks = new HashMap<>();

        ThreadSummary(String name, long tid) {
            this.name = name;
            this.tid = tid;
        }
    }
}
