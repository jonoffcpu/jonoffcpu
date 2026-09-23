// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.capture.CaptureProto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Encodes fixture rows, which are written as JSON for readability, into the capture stream's binary
 * framing. Control records keep their JSON verbatim, so a fixture can still inject malformed control
 * JSON; stack and observation rows are converted field by field.
 */
final class CaptureStreamFixture {
    private CaptureStreamFixture() {}

    static byte[] encode(List<JsonObject> rows) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(header());
        for (JsonObject row : rows) {
            record(row).writeDelimitedTo(bytes);
        }
        return bytes.toByteArray();
    }

    /** A control record whose JSON is supplied verbatim, for malformed-input fixtures. */
    static byte[] controlRecord(String json) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CaptureProto.Record.newBuilder()
                .setCaptureStart(CaptureProto.ControlJson.newBuilder().setJson(json))
                .build()
                .writeDelimitedTo(bytes);
        return bytes.toByteArray();
    }

    static byte[] header() {
        byte[] header = new byte[CaptureStream.HEADER_BYTES];
        System.arraycopy(CaptureStream.MAGIC, 0, header, 0, CaptureStream.MAGIC.length);
        header[CaptureStream.MAGIC.length] = (byte) CaptureStream.FORMAT_VERSION;
        return header;
    }

    static CaptureProto.Record record(JsonObject row) {
        String type = row.get("recordType").getAsString();
        return switch (type) {
            case "captureStart" ->
                CaptureProto.Record.newBuilder().setCaptureStart(control(row)).build();
            case "captureEnd" ->
                CaptureProto.Record.newBuilder().setCaptureEnd(control(row)).build();
            case "captureFinalized" ->
                CaptureProto.Record.newBuilder()
                        .setCaptureFinalized(control(row))
                        .build();
            case "stack" ->
                CaptureProto.Record.newBuilder().setStack(stack(row)).build();
            case "observation" ->
                CaptureProto.Record.newBuilder()
                        .setObservation(observation(row))
                        .build();
            default -> throw new IllegalArgumentException("Unknown fixture record type: " + type);
        };
    }

    private static CaptureProto.ControlJson control(JsonObject row) {
        return CaptureProto.ControlJson.newBuilder().setJson(row.toString()).build();
    }

    private static CaptureProto.Stack stack(JsonObject row) {
        CaptureProto.Stack.Builder stack =
                CaptureProto.Stack.newBuilder().setId(row.get("stackId").getAsLong());
        for (JsonElement frame : row.getAsJsonArray("frames")) {
            JsonObject value = frame.getAsJsonObject();
            stack.addFrame(CaptureProto.Frame.newBuilder()
                    .setAddress(Long.parseUnsignedLong(value.get("address").getAsString(), 16))
                    .setSymbol(text(value, "symbol"))
                    .setModule(text(value, "module")));
        }
        return stack.build();
    }

    private static CaptureProto.Observation observation(JsonObject row) {
        CaptureProto.Observation.Builder builder = CaptureProto.Observation.newBuilder()
                .setCorrelationId(
                        Long.parseUnsignedLong(row.get("correlationId").getAsString(), 16))
                .setHostTgid((int) row.get("hostTgid").getAsLong())
                .setHostTid((int) row.get("hostTid").getAsLong())
                .setTargetTgid((int) row.get("targetTgid").getAsLong())
                .setTargetTid(
                        row.get("targetTid").isJsonNull()
                                ? 0
                                : (int) row.get("targetTid").getAsLong())
                .setProcessGenerationNs(
                        Long.parseUnsignedLong(row.get("processGenerationNs").getAsString()))
                .setThreadGenerationNs(
                        Long.parseUnsignedLong(row.get("threadGenerationNs").getAsString()))
                .setRegistrationToken(
                        Long.parseUnsignedLong(row.get("registrationToken").getAsString(), 16))
                .setStartMonotonicNs(
                        Long.parseUnsignedLong(row.get("startMonotonicNanos").getAsString()))
                .setEndMonotonicNs(
                        Long.parseUnsignedLong(row.get("endMonotonicNanos").getAsString()))
                .setAdmissionThreshold(row.get("admissionThreshold").getAsLong())
                .setSignalResult(row.get("signalResult").getAsLong())
                .setComm(text(row, "comm"))
                .setKernelStackId(row.get("kernelStackId").getAsLong())
                .setUserStackId(row.get("userStackId").getAsLong())
                .setKernelStackError(text(row, "kernelStackError"))
                .setUserStackError(text(row, "userStackError"));
        // A classified observation names its reason; the raw wire number lets a fixture write an invalid one.
        if (row.has("offCpuReasonValue"))
            builder.setReasonValue(row.get("offCpuReasonValue").getAsInt());
        JsonElement reason = row.get("offCpuReason");
        if (reason != null && !reason.isJsonNull()) {
            builder.setReasonValue(
                    switch (reason.getAsString()) {
                        case "blocked" -> 1;
                        case "runnable" -> 2;
                        case "preempted" -> 3;
                        default -> throw new IllegalArgumentException("fixture reason: " + reason);
                    });
        }
        if (row.has("prevTaskState"))
            builder.setPrevTaskState((int) row.get("prevTaskState").getAsLong());
        if (row.has("preempted")) builder.setPreempted(row.get("preempted").getAsBoolean());
        return builder.build();
    }

    private static String text(JsonObject row, String key) {
        JsonElement value = row.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
