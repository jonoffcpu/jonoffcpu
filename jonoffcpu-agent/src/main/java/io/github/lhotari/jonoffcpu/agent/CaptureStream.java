// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.capture.CaptureProto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Framing of the capture stream defined by {@code docs/schema/jonoffcpu-capture.proto}: a fixed header
 * followed by length-delimited protobuf records. The agent reads the stream to verify it and appends the
 * finalization footer as one more record.
 */
final class CaptureStream {
    static final byte[] MAGIC = "JONOFFCPU\0".getBytes(StandardCharsets.US_ASCII);
    static final int FORMAT_VERSION = 1;
    static final int HEADER_BYTES = MAGIC.length + 2;

    private CaptureStream() {}

    static void readHeader(InputStream input) throws IOException {
        byte[] header = input.readNBytes(HEADER_BYTES);
        if (header.length != HEADER_BYTES || !Arrays.equals(header, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new IOException("Not a jonoffcpu capture stream: missing header");
        }
        int version = (header[MAGIC.length] & 0xff) | ((header[MAGIC.length + 1] & 0xff) << 8);
        if (version != FORMAT_VERSION) throw new IOException("Unsupported capture format version: " + version);
    }

    /** The next record, or null at a clean end of stream; a truncated tail is an error for the agent. */
    static CaptureProto.Record next(InputStream input, int maxRecordBytes) throws IOException {
        long length = 0;
        int shift = 0;
        boolean started = false;
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (!started) return null;
                throw new IOException("Source stream ends inside a record length");
            }
            started = true;
            length |= (long) (value & 0x7f) << shift;
            if ((value & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) throw new IOException("Malformed record length");
        }
        // The claimed length is checked before it is used to read, so a corrupt stream cannot drive a
        // large allocation.
        if (length > maxRecordBytes) throw new IOException("Source record exceeds " + maxRecordBytes + " bytes");
        byte[] message = input.readNBytes((int) length);
        if (message.length != length) throw new IOException("Source stream ends inside a record");
        return CaptureProto.Record.parseFrom(message);
    }

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

    static String controlJson(CaptureProto.Record record) throws IOException {
        return switch (record.getRecordCase()) {
            case CAPTURE_START -> record.getCaptureStart().getJson();
            case CAPTURE_END -> record.getCaptureEnd().getJson();
            case CAPTURE_FINALIZED -> record.getCaptureFinalized().getJson();
            default -> throw new IOException("Not a control record");
        };
    }

    /** The finalization footer as one length-delimited record, to append to a finished capture. */
    static byte[] footerRecord(JsonObject footer) throws IOException {
        String json = footer.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > JsonSupport.MAX_CONTROL_BYTES) {
            throw new IOException("captureFinalized footer exceeds 64 KiB");
        }
        CaptureProto.Record record = CaptureProto.Record.newBuilder()
                .setCaptureFinalized(
                        CaptureProto.ControlJson.newBuilder().setJson(json).build())
                .build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        record.writeDelimitedTo(bytes);
        return bytes.toByteArray();
    }

    /** A whole stream that holds only the footer, for a capture that never loaded an eBPF source. */
    static byte[] footerStream(JsonObject footer) throws IOException {
        byte[] record = footerRecord(footer);
        byte[] stream = new byte[HEADER_BYTES + record.length];
        System.arraycopy(MAGIC, 0, stream, 0, MAGIC.length);
        stream[MAGIC.length] = (byte) (FORMAT_VERSION & 0xff);
        stream[MAGIC.length + 1] = (byte) (FORMAT_VERSION >>> 8);
        System.arraycopy(record, 0, stream, HEADER_BYTES, record.length);
        return stream;
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
