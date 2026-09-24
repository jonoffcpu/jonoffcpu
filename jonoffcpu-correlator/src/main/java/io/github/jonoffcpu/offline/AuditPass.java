// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.offline;

import io.github.jonoffcpu.capture.CaptureFormat;
import io.github.jonoffcpu.capture.CaptureProto;
import io.github.jonoffcpu.offline.ReportProto.Classification;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The second read. Correlation's per-row records exist only to be written into
 * {@code jonoffcpu-classified-records.jsonl}, so instead of retaining them through the join they
 * are decoded again here, one at a time, and handed to a sink with the classification pass one
 * computed. Both files have already been digest-verified and are in page cache, so the second read
 * costs one sequential scan.
 *
 * <p>Order is the contract: every <em>kept</em> source row in capture file order, then every kept
 * JFR sample in recording order, which is exactly what the retained {@code classify} produced. A
 * degraded run's columns hold only the rows thinning and window narrowing let through, so this pass
 * re-applies the same predicates ({@link CorrelationResult#keepsSource} and
 * {@link CorrelationResult#keepsSample}) and skips the rest: without that the file-row to
 * column-slot mapping slips at the first dropped row and then runs off the end of the columns.
 */
final class AuditPass {
    /** Receives each kept row's classified record with the column slot it occupies. */
    interface Sink {
        void source(int slot, ReportProto.ClassifiedRecord record) throws IOException;

        void jfr(int slot, ReportProto.ClassifiedRecord record) throws IOException;
    }

    private AuditPass() {}

    static void run(
            CorrelationResult result, Path source, Path jfr, OfflineCorrelator.JfrSelection selection, Sink sink)
            throws IOException {
        readSource(result, source, sink);
        readJfrSamples(result, jfr, selection, sink);
    }

    /**
     * {@code CaptureFormat.next(input, Integer.MAX_VALUE)} is safe here only because the same file
     * already passed {@code limits.maxLineBytes()} in pass one: every record in it is already known
     * to be no larger than that bound, so relaxing the re-read's own ceiling cannot let a corrupt or
     * oversized record through. If a future caller ever drove this method against an unverified file,
     * the real limit would need to travel through {@link CorrelationResult} instead.
     */
    private static void readSource(CorrelationResult result, Path source, Sink sink) throws IOException {
        boolean partial = result.capture().partial;
        Map<Long, List<CaptureProto.Frame>> stacks = new HashMap<>();
        int slot = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            CaptureFormat.readHeader(input);
            CaptureFormat.Framed framed;
            while ((framed = CaptureFormat.next(input, Integer.MAX_VALUE)) != null) {
                if (framed.truncated()) break;
                switch (framed.record().getRecordCase()) {
                    case STACK -> {
                        CaptureProto.Stack stack = framed.record().getStack();
                        stacks.put(stack.getId(), stack.getFrameList());
                    }
                    case OBSERVATION -> {
                        CaptureProto.Observation observation = framed.record().getObservation();
                        // Pass one already accepted these bits as a non-negative signed nanosecond
                        // value, so the raw long is the same number the engine compared against the cut.
                        if (result.keepsSource(observation.getCorrelationId(), observation.getStartMonotonicNanos())) {
                            Reason reason = result.sources().reason(slot);
                            Outcome outcome = result.sources().outcome(slot);
                            sink.source(
                                    slot,
                                    record(slot + 2, reason, outcome, true, partial)
                                            .setSource(expandStacks(observation, stacks))
                                            .build());
                            slot++;
                        }
                    }
                    default -> {
                        // Control records carry no per-row audit output.
                    }
                }
            }
        }
        // A true invariant of the kept-row mapping: the same predicate over the same records in the
        // same order yields the same count, so a mismatch really does mean the file changed.
        CaptureInput.require(slot == result.sources().size(), "Capture changed between correlation passes");
    }

    private static void readJfrSamples(
            CorrelationResult result, Path jfr, OfflineCorrelator.JfrSelection selection, Sink sink)
            throws IOException {
        boolean partial = result.capture().partial;
        int[] slot = new int[1];
        SignalJfrExporter.RowConsumer consumer = row -> {
            if (!row.hasSample()) return;
            if (!result.keepsSample(row.getSample().getCorrelationId())) return;
            int index = slot[0]++;
            Reason reason = result.samples().reason(index);
            Outcome outcome = result.samples().outcome(index);
            sink.jfr(
                    index,
                    record(index + 1, reason, outcome, false, partial)
                            .setJfr(row.getSample())
                            .build());
        };
        OfflineCorrelator.readJfr(result.capture(), jfr, selection, consumer);
        CaptureInput.require(slot[0] == result.samples().size(), "JFR changed between correlation passes");
    }

    private static ReportProto.ClassifiedRecord.Builder record(
            int row, Reason reason, Outcome outcome, boolean sourceStream, boolean partial) {
        ReportProto.ClassifiedRecord.Builder record = ReportProto.ClassifiedRecord.newBuilder()
                .setRow(row)
                .setClassification(classification(reason, outcome, sourceStream, partial));
        ReportProto.RowReason why = rowReason(reason, outcome);
        if (why != null) record.setReason(why);
        return record;
    }

    static Classification classification(Reason reason, Outcome outcome, boolean sourceStream, boolean partial) {
        if (reason != Reason.NONE) return Classification.CLASSIFICATION_INVALID;
        if (outcome == Outcome.MATCHED) {
            return partial ? Classification.CLASSIFICATION_PROVISIONAL_MATCH : Classification.CLASSIFICATION_MATCHED;
        }
        if (sourceStream) {
            return partial ? Classification.CLASSIFICATION_UNMATCHED_PREFIX : Classification.CLASSIFICATION_UNMATCHED;
        }
        return partial ? Classification.CLASSIFICATION_ORPHAN_PREFIX : Classification.CLASSIFICATION_ORPHAN;
    }

    static ReportProto.RowReason rowReason(Reason reason, Outcome outcome) {
        return reason != Reason.NONE ? reason.proto() : outcome.proto();
    }

    /**
     * Puts the interned stacks back beside the observation, so a classified record stays self-contained: a reader
     * of the audit file never has to resolve a stack id against the capture stream.
     */
    static ReportProto.ClassifiedObservation expandStacks(
            CaptureProto.Observation observation, Map<Long, List<CaptureProto.Frame>> stacks) {
        ReportProto.ClassifiedObservation.Builder expanded =
                ReportProto.ClassifiedObservation.newBuilder().setObservation(observation);
        if (observation.getKernelStackError().isEmpty()) {
            expanded.addAllKernelFrames(stacks.getOrDefault(observation.getKernelStackId(), List.of()));
        }
        if (observation.getUserStackError().isEmpty()) {
            expanded.addAllUserFrames(stacks.getOrDefault(observation.getUserStackId(), List.of()));
        }
        return expanded.build();
    }
}
