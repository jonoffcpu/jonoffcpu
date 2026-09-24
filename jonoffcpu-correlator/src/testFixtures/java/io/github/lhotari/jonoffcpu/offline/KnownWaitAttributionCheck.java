// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.github.lhotari.jonoffcpu.capture.CaptureProto;
import io.github.lhotari.jonoffcpu.capture.ProtoJson;
import java.io.IOException;
import java.math.BigDecimal;
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

/**
 * Acceptance check that keeps scheduler intervals separate from delivery-stack evidence. The workload's JSON and the
 * check's report are free-form, so they are read and written as protobuf {@code Struct}s through {@code ProtoJson}.
 */
public final class KnownWaitAttributionCheck {
    private static final Set<String> REQUIRED_THREADS = Set.of(
            "jonoffcpu-known-sleep",
            "jonoffcpu-known-park",
            "jonoffcpu-known-native-wait",
            "jonoffcpu-known-rapid-reblock");

    private KnownWaitAttributionCheck() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected capture stream, original JFR, workload JSON, and report JSON");
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
        for (ReportProto.ClassifiedRecord classified : analysis.records()) {
            if (!classified.hasSource()
                    || classified.getClassification() == ReportProto.Classification.CLASSIFICATION_INVALID) continue;
            CaptureProto.Observation row = classified.getSource().getObservation();
            ThreadSummary summary = summaries.get(Integer.toUnsignedLong(row.getTargetTid()));
            if (summary == null) continue;
            BigInteger start = U64.big(row.getStartMonotonicNanos());
            BigInteger end = U64.big(row.getEndMonotonicNanos());
            BigInteger overlap = fixture.overlap(summary.tid, start, end);
            if (overlap.signum() > 0) {
                summary.sourceRows++;
                summary.sourceIntervalNanos = summary.sourceIntervalNanos.add(end.subtract(start));
                summary.sourceFixtureOverlapNanos = summary.sourceFixtureOverlapNanos.add(overlap);
                // A known wait is a sleep, a park or a blocking JNI call: the kernel must classify it blocked.
                OffCpuReason switchOut = OffCpuReason.fromWire(row.getReasonValue());
                String reason = switchOut == null ? "unspecified" : switchOut.label();
                summary.overlapByReason.merge(reason, overlap, BigInteger::add);
            }
        }
        for (OfflineCorrelator.Match match : analysis.matches()) {
            ThreadSummary summary =
                    summaries.get(Integer.toUnsignedLong(match.observation().getTargetTid()));
            if (summary == null) continue;
            summary.matchedRows++;
            summary.matchedIntervalNanos = summary.matchedIntervalNanos.add(match.durationNanos());
            summary.handlerDelays.add(match.handlerDelayNanos());
            summary.deliveryStacks.merge(stack(match.sample()), 1, Integer::sum);
            BigInteger handlerTime =
                    U64.big(match.observation().getEndMonotonicNanos()).add(match.handlerDelayNanos());
            if (fixture.insidePhase(summary.tid, "reblock", handlerTime)) summary.deliveredDuringReblock++;
        }

        Struct.Builder threads = Struct.newBuilder();
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
            BigInteger blocked = summary.overlapByReason.getOrDefault("blocked", BigInteger.ZERO);
            check(
                    blocked.multiply(BigInteger.valueOf(100))
                                    .compareTo(summary.sourceFixtureOverlapNanos.multiply(BigInteger.valueOf(95)))
                            >= 0,
                    "Known waits must be classified blocked for " + summary.name + ": " + summary.overlapByReason);
            Struct.Builder row = Struct.newBuilder()
                    .putFields("targetTid", number(summary.tid))
                    .putFields("fixtureOperations", number(summary.fixtureOperations))
                    .putFields("fixtureRequestedNanos", text(summary.fixtureRequestedNanos.toString()))
                    .putFields("fixtureActualNanos", text(summary.fixtureActualNanos.toString()))
                    .putFields("sourceRowsOverlappingFixture", number(summary.sourceRows))
                    .putFields("sourceObservedIntervalNanos", text(summary.sourceIntervalNanos.toString()))
                    .putFields("sourceOverlapWithFixtureNanos", text(summary.sourceFixtureOverlapNanos.toString()));
            Struct.Builder byReason = Struct.newBuilder();
            new TreeMap<>(summary.overlapByReason)
                    .forEach((reason, nanos) -> byReason.putFields(reason, text(nanos.toString())));
            row.putFields(
                    "sourceOverlapWithFixtureNanosByOffCpuReason",
                    Value.newBuilder().setStructValue(byReason).build());
            row.putFields("matchedDeliveryRows", number(summary.matchedRows));
            row.putFields("matchedDeliveryWeightedNanos", text(summary.matchedIntervalNanos.toString()));
            row.putFields(
                    "handlerDelayNanos",
                    Value.newBuilder()
                            .setStructValue(delaySummary(summary.handlerDelays))
                            .build());
            Struct.Builder stacks = Struct.newBuilder();
            new TreeMap<>(summary.deliveryStacks).forEach((stack, count) -> stacks.putFields(stack, number(count)));
            row.putFields(
                    "signalDeliveryStacks",
                    Value.newBuilder().setStructValue(stacks).build());
            if (summary.name.equals("jonoffcpu-known-rapid-reblock")) {
                row.putFields(
                        "deliveriesWhoseTranslatedHandlerTimeWasDuringReblock", number(summary.deliveredDuringReblock));
            }
            threads.putFields(
                    summary.name, Value.newBuilder().setStructValue(row).build());
        }

        Struct report = Struct.newBuilder()
                .putFields("result", text("pass"))
                .putFields("sourceTimingSemantics", text("scheduler off-CPU intervals on Linux CLOCK_MONOTONIC"))
                .putFields("stackTimingSemantics", text("post-resumption signal-delivery stack"))
                .putFields(
                        "handlerDelaySemantics",
                        text("source interval end to translated async-profiler handler timestamp"))
                .putFields(
                        "blockingStackIdentityClaimed",
                        Value.newBuilder().setBoolValue(false).build())
                .putFields("sourceRows", number(analysis.sourceRows()))
                .putFields("matchedRows", number(analysis.matched()))
                .putFields("threads", Value.newBuilder().setStructValue(threads).build())
                .build();
        Files.writeString(output, ProtoJson.pretty(report) + "\n");
        System.out.println("Known-wait attribution check passed: " + output);
    }

    private static Fixture readFixture(Path path) throws IOException {
        Struct root =
                ProtoJson.parse(Files.readString(path), Struct.newBuilder()).build();
        check(integer(root, "schemaVersion").intValueExact() == 1, "Unsupported fixture schema");
        check(string(root, "clock").equals("Linux CLOCK_MONOTONIC"), "Unexpected fixture clock");
        Map<String, Long> tids = new TreeMap<>();
        for (var entry : root.getFieldsOrThrow("targetTids")
                .getStructValue()
                .getFieldsMap()
                .entrySet()) {
            tids.put(entry.getKey(), integer(entry.getValue()).longValueExact());
        }
        check(tids.keySet().equals(REQUIRED_THREADS), "Fixture thread set is incomplete");
        check(new HashSet<>(tids.values()).size() == REQUIRED_THREADS.size(), "Fixture TIDs are not unique");
        int iterations = integer(root, "iterations").intValueExact();
        Map<String, String> expectedKinds = Map.of(
                "jonoffcpu-known-sleep", "sleep",
                "jonoffcpu-known-park", "park",
                "jonoffcpu-known-native-wait", "native-wait",
                "jonoffcpu-known-rapid-reblock", "rapid-reblock");
        List<Operation> operations = new ArrayList<>();
        ListValue array = root.getFieldsOrThrow("operations").getListValue();
        for (Value element : array.getValuesList()) {
            Struct row = element.getStructValue();
            Operation operation = new Operation(
                    string(row, "threadName"),
                    integer(row, "targetTid").longValueExact(),
                    string(row, "waitKind"),
                    string(row, "phase"),
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

    private static Struct delaySummary(List<BigInteger> values) {
        values.sort(BigInteger::compareTo);
        return Struct.newBuilder()
                .putFields("count", number(values.size()))
                .putFields("p50", text(percentile(values, 50).toString()))
                .putFields("p99", text(percentile(values, 99).toString()))
                .putFields("max", text(values.get(values.size() - 1).toString()))
                .build();
    }

    private static BigInteger percentile(List<BigInteger> values, int percentile) {
        int rank = (int) ((values.size() * (long) percentile + 99) / 100);
        return values.get(rank - 1);
    }

    private static String stack(SignalProto.SignalSample sample) {
        List<SignalProto.JfrFrame> frames = sample.getFramesList();
        if (frames.isEmpty()) return "[stack unavailable]";
        List<String> names = new ArrayList<>();
        for (int index = frames.size() - 1; index >= 0; index--) {
            SignalProto.JfrFrame frame = frames.get(index);
            String type = frame.hasClassName() ? frame.getClassName() : "";
            String method = frame.hasMethodName() ? frame.getMethodName() : "[unresolved]";
            names.add((type.isEmpty() ? method : type + "." + method).replace(';', ':'));
        }
        return String.join(";", names);
    }

    private static Value text(String value) {
        return Value.newBuilder().setStringValue(value).build();
    }

    private static Value number(long value) {
        return Value.newBuilder().setNumberValue(value).build();
    }

    private static String string(Struct row, String key) {
        return row.getFieldsOrThrow(key).getStringValue();
    }

    /** An integer the workload wrote as a JSON number or as a decimal string. */
    private static BigInteger integer(Value value) {
        return value.hasStringValue()
                ? new BigInteger(value.getStringValue())
                : new BigDecimal(value.getNumberValue()).toBigIntegerExact();
    }

    private static BigInteger integer(Struct row, String key) {
        return integer(row.getFieldsOrThrow(key));
    }

    private static BigInteger decimal(Struct row, String key) {
        return integer(row, key);
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
        final Map<String, BigInteger> overlapByReason = new HashMap<>();

        ThreadSummary(String name, long tid) {
            this.name = name;
            this.tid = tid;
        }
    }
}
