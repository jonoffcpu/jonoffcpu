// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.capture.CaptureProto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;

/**
 * Framing of the capture stream defined by {@code jonoffcpu-capture-codec/src/main/proto/jonoffcpu-capture.proto}:
 * a fixed header followed by length-delimited protobuf records. The reader never allocates on a claimed length it
 * has not checked, and reports a truncated final record instead of decoding a partial one.
 */
final class CaptureStream {
    static final byte[] MAGIC = "JONOFFCPU\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    static final int FORMAT_VERSION = 1;
    static final int HEADER_BYTES = MAGIC.length + 2;

    private CaptureStream() {}

    /** One framed record: its exact bytes, for digesting, and either the message or a truncated tail. */
    record Framed(byte[] bytes, CaptureProto.Record record) {
        boolean truncated() {
            return record == null;
        }
    }

    static byte[] readHeader(InputStream input) throws IOException {
        byte[] header = input.readNBytes(HEADER_BYTES);
        if (header.length != HEADER_BYTES
                || !java.util.Arrays.equals(header, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new IOException("Not a jonoffcpu capture stream: missing header");
        }
        int version = (header[MAGIC.length] & 0xff) | ((header[MAGIC.length + 1] & 0xff) << 8);
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported capture format version: " + version);
        }
        return header;
    }

    /** The next record, or null at a clean end of stream. */
    static Framed next(InputStream input, int maxRecordBytes) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        long length = 0;
        int shift = 0;
        while (true) {
            int value = input.read();
            if (value < 0) {
                return raw.size() == 0 ? null : new Framed(raw.toByteArray(), null);
            }
            raw.write(value);
            length |= (long) (value & 0x7f) << shift;
            if ((value & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) throw new IOException("Malformed record length");
        }
        // The claimed length is checked before it is used to read, so a corrupt stream cannot drive a
        // large allocation.
        if (length > maxRecordBytes) throw new IOException("Source record byte limit exceeded");
        byte[] message = input.readNBytes((int) length);
        raw.write(message, 0, message.length);
        if (message.length != length) return new Framed(raw.toByteArray(), null);
        return new Framed(raw.toByteArray(), CaptureProto.Record.parseFrom(message));
    }

    /** The record type name the stream has always used, for diagnostics and error messages. */
    static String recordType(CaptureProto.Record record) throws IOException {
        return switch (record.getRecordCase()) {
            case CAPTURE_START -> "captureStart";
            case STACK -> "stack";
            case OBSERVATION -> "observation";
            case CAPTURE_END -> "captureEnd";
            case CAPTURE_FINALIZED -> "captureFinalized";
            case RECORD_NOT_SET -> throw new IOException("Unknown source record");
        };
    }

    /** The JSON a control record carries verbatim; the caller parses it with the strict reader. */
    static String controlJson(CaptureProto.Record record) throws IOException {
        return switch (record.getRecordCase()) {
            case CAPTURE_START -> record.getCaptureStart().getJson();
            case CAPTURE_END -> record.getCaptureEnd().getJson();
            case CAPTURE_FINALIZED -> record.getCaptureFinalized().getJson();
            default -> throw new IOException("Not a control record");
        };
    }

    static JsonObject stackRow(CaptureProto.Stack stack) {
        JsonObject row = new JsonObject();
        row.addProperty("recordType", "stack");
        row.addProperty("stackId", stack.getId());
        JsonArray frames = new JsonArray();
        for (CaptureProto.Frame frame : stack.getFrameList()) {
            JsonObject value = new JsonObject();
            value.addProperty("address", HexFormat.of().toHexDigits(frame.getAddress()));
            value.addProperty("symbol", frame.getSymbol().isEmpty() ? null : frame.getSymbol());
            value.addProperty("module", frame.getModule().isEmpty() ? null : frame.getModule());
            frames.add(value);
        }
        row.add("frames", frames);
        return row;
    }

    static JsonObject observationRow(CaptureProto.Observation observation) {
        JsonObject row = new JsonObject();
        row.addProperty("recordType", "observation");
        row.addProperty("correlationId", HexFormat.of().toHexDigits(observation.getCorrelationId()));
        row.addProperty("hostTgid", Integer.toUnsignedLong(observation.getHostTgid()));
        row.addProperty("hostTid", Integer.toUnsignedLong(observation.getHostTid()));
        row.addProperty("targetTgid", Integer.toUnsignedLong(observation.getTargetTgid()));
        row.addProperty("targetTid", Integer.toUnsignedLong(observation.getTargetTid()));
        row.addProperty("processGenerationNs", Long.toUnsignedString(observation.getProcessGenerationNs()));
        row.addProperty("threadGenerationNs", Long.toUnsignedString(observation.getThreadGenerationNs()));
        row.addProperty("registrationToken", HexFormat.of().toHexDigits(observation.getRegistrationToken()));
        row.addProperty("startMonotonicNanos", Long.toUnsignedString(observation.getStartMonotonicNs()));
        row.addProperty("endMonotonicNanos", Long.toUnsignedString(observation.getEndMonotonicNs()));
        row.addProperty("admissionThreshold", observation.getAdmissionThreshold());
        row.addProperty("signalResult", observation.getSignalResult());
        row.addProperty("comm", observation.getComm());
        row.addProperty("kernelStackId", observation.getKernelStackId());
        row.addProperty("userStackId", observation.getUserStackId());
        if (!observation.getKernelStackError().isEmpty()) {
            row.addProperty("kernelStackError", observation.getKernelStackError());
        }
        if (!observation.getUserStackError().isEmpty()) {
            row.addProperty("userStackError", observation.getUserStackError());
        }
        // A schemaVersion 2 observation carries none of the classification, and its row keeps the old shape.
        if (observation.getReasonValue() != 0 || observation.hasPrevTaskState() || observation.hasPreempted()) {
            row.addProperty("offCpuReason", offCpuReason(observation.getReasonValue()));
            if (observation.hasPrevTaskState()) {
                row.addProperty("prevTaskState", Integer.toUnsignedLong(observation.getPrevTaskState()));
            }
            if (observation.hasPreempted()) row.addProperty("preempted", observation.getPreempted());
        }
        if (observation.hasRunqueueNanos()) {
            row.addProperty("runqueueNanos", Long.toUnsignedString(observation.getRunqueueNanos()));
        }
        return row;
    }

    /** The lowercase reason name, or null for a value this reader does not know. */
    static String offCpuReason(int value) {
        return switch (value) {
            case 1 -> "blocked";
            case 2 -> "runnable";
            case 3 -> "preempted";
            default -> null;
        };
    }

    /** The kernel's classification: preemption wins, then a zero task state is TASK_RUNNING. */
    static String classifyOffCpu(boolean preempted, long prevTaskState) {
        if (preempted) return "preempted";
        return prevTaskState == 0 ? "runnable" : "blocked";
    }
}
