// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.capture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Framing of the capture stream defined by {@code jonoffcpu-capture.proto}: a fixed header followed by
 * length-delimited {@link CaptureProto.Record} messages. The reader never allocates on a claimed length it has not
 * checked, and reports a truncated final record instead of decoding a partial one.
 */
public final class CaptureFormat {
    private static final byte[] MAGIC = "JONOFFCPU\0".getBytes(StandardCharsets.US_ASCII);
    public static final int FORMAT_VERSION = 2;
    public static final int HEADER_BYTES = MAGIC.length + 2;

    private CaptureFormat() {}

    /** One framed record: its exact bytes, for digesting, and either the message or a truncated tail. */
    public record Framed(byte[] bytes, CaptureProto.Record record) {
        public boolean truncated() {
            return record == null;
        }
    }

    /** The header a stream of this format version starts with. */
    public static byte[] header() {
        byte[] header = Arrays.copyOf(MAGIC, HEADER_BYTES);
        header[MAGIC.length] = (byte) FORMAT_VERSION;
        header[MAGIC.length + 1] = (byte) (FORMAT_VERSION >>> 8);
        return header;
    }

    /** Reads and checks the header, returning its bytes for digesting. */
    public static byte[] readHeader(InputStream input) throws IOException {
        byte[] header = input.readNBytes(HEADER_BYTES);
        if (header.length != HEADER_BYTES || !Arrays.equals(header, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new IOException("Not a jonoffcpu capture stream: missing header");
        }
        int version = (header[MAGIC.length] & 0xff) | ((header[MAGIC.length + 1] & 0xff) << 8);
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported capture format version: " + version);
        }
        return header;
    }

    /** The next record, or null at a clean end of stream. */
    public static Framed next(InputStream input, int maxRecordBytes) throws IOException {
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

    /** Writes one record, length-delimited. */
    public static void write(OutputStream output, CaptureProto.Record record) throws IOException {
        record.writeDelimitedTo(output);
    }
}
