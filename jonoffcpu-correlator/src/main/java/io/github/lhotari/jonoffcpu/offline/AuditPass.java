// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The second read. Correlation's per-row documents exist only to be written into
 * {@code jonoffcpu-classified-records.jsonl}, so instead of retaining them through the join they
 * are decoded again here, one at a time, and handed to a sink with the classification pass one
 * computed. Both files have already been digest-verified and are in page cache, so the second read
 * costs one sequential scan.
 *
 * <p>Order is the contract: every source row in capture file order, then every JFR sample in
 * recording order, which is exactly what the retained {@code classify} produced.
 */
final class AuditPass {
    interface Sink {
        void source(int rowNumber, int slot, String classification, String reason, JsonObject observation)
                throws IOException;

        void jfr(int rowNumber, int slot, String classification, String reason, JsonObject sample) throws IOException;
    }

    private AuditPass() {}

    static void run(
            CorrelationResult result, Path source, Path jfr, OfflineCorrelator.JfrSelection selection, Sink sink)
            throws IOException {
        readSource(result, source, sink);
        readJfrSamples(result, jfr, selection, sink);
    }

    /**
     * {@code CaptureStream.next(input, Integer.MAX_VALUE)} is safe here only because the same file
     * already passed {@code limits.maxLineBytes()} in pass one: every record in it is already known
     * to be no larger than that bound, so relaxing the re-read's own ceiling cannot let a corrupt or
     * oversized record through. If a future caller ever drove this method against an unverified file,
     * the real limit would need to travel through {@link CorrelationResult} instead.
     */
    private static void readSource(CorrelationResult result, Path source, Sink sink) throws IOException {
        boolean partial = result.capture().partial;
        Map<Long, JsonArray> stacks = new HashMap<>();
        int slot = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            CaptureStream.readHeader(input);
            CaptureStream.Framed framed;
            while ((framed = CaptureStream.next(input, Integer.MAX_VALUE)) != null) {
                if (framed.truncated()) break;
                switch (framed.record().getRecordCase()) {
                    case STACK -> {
                        JsonObject stack =
                                CaptureStream.stackRow(framed.record().getStack());
                        stacks.put(stack.get("stackId").getAsLong(), stack.getAsJsonArray("frames"));
                    }
                    case OBSERVATION -> {
                        JsonObject row =
                                CaptureStream.observationRow(framed.record().getObservation());
                        Reason reason = result.sources().reason(slot);
                        Outcome outcome = result.sources().outcome(slot);
                        sink.source(
                                slot + 2,
                                slot,
                                classification(reason, outcome, true, partial),
                                reasonText(reason, outcome),
                                expandStacks(row, stacks));
                        slot++;
                    }
                    default -> {
                        // Control records carry no per-row audit output.
                    }
                }
            }
        }
        CaptureInput.require(slot == result.sources().size(), "Capture changed between correlation passes");
    }

    private static void readJfrSamples(
            CorrelationResult result, Path jfr, OfflineCorrelator.JfrSelection selection, Sink sink)
            throws IOException {
        boolean partial = result.capture().partial;
        int[] slot = new int[1];
        SignalJfrExporter.RowConsumer consumer = raw -> {
            if (!"sample".equals(raw.get("recordType"))) return;
            int index = slot[0]++;
            Reason reason = result.samples().reason(index);
            Outcome outcome = result.samples().outcome(index);
            sink.jfr(
                    index + 1,
                    index,
                    classification(reason, outcome, false, partial),
                    reasonText(reason, outcome),
                    new com.google.gson.GsonBuilder()
                            .serializeNulls()
                            .create()
                            .toJsonTree(raw)
                            .getAsJsonObject());
        };
        OfflineCorrelator.readJfr(result.capture(), jfr, selection, consumer);
        CaptureInput.require(slot[0] == result.samples().size(), "JFR changed between correlation passes");
    }

    static String classification(Reason reason, Outcome outcome, boolean sourceStream, boolean partial) {
        if (reason != Reason.NONE) return "invalid";
        if (outcome == Outcome.MATCHED) return partial ? "provisional-match" : "matched";
        return (sourceStream ? "unmatched" : "orphan") + (partial ? "-prefix" : "");
    }

    static String reasonText(Reason reason, Outcome outcome) {
        if (reason != Reason.NONE) return reason.text();
        return outcome == Outcome.MATCHED ? null : outcome.text();
    }

    /**
     * Puts an interned stack back into the echoed row, so a classified record stays self-contained:
     * a reader of the audit file never has to resolve a stack id against the capture stream.
     */
    static JsonObject expandStacks(JsonObject observation, Map<Long, JsonArray> stacks) {
        JsonObject expanded = observation.deepCopy();
        for (String stack : List.of("kernelStack", "userStack")) {
            JsonElement id = expanded.remove(stack + "Id");
            JsonElement error = expanded.remove(stack + "Error");
            JsonObject value = new JsonObject();
            value.add("stackId", id);
            value.add("errorCode", error == null ? JsonNull.INSTANCE : error);
            boolean failed = error != null && !error.isJsonNull();
            value.addProperty("status", failed ? "error" : "ok");
            JsonArray frames = failed ? new JsonArray() : stacks.get(id.getAsLong());
            value.add("frames", frames == null ? new JsonArray() : frames);
            expanded.add(stack, value);
        }
        return expanded;
    }
}
