// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.capture;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Writes and reads capture streams of records, for the agent's and the correlator's fixtures alike. */
public final class CaptureRecordFixture {
    private CaptureRecordFixture() {}

    /** A whole stream: the header, then every record. */
    public static byte[] encode(List<CaptureProto.Record> records) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(CaptureFormat.header());
        for (CaptureProto.Record record : records) CaptureFormat.write(bytes, record);
        return bytes.toByteArray();
    }

    /** One record, length-delimited, without a header. */
    public static byte[] encode(CaptureProto.Record record) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CaptureFormat.write(bytes, record);
        return bytes.toByteArray();
    }

    /** Appends one record to a stream the fixture is building. */
    public static void append(Path stream, CaptureProto.Record record) throws IOException {
        Files.write(stream, encode(record), StandardOpenOption.APPEND);
    }

    /** Every record of a complete stream. */
    public static List<CaptureProto.Record> read(Path stream) throws IOException {
        List<CaptureProto.Record> records = new ArrayList<>();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(stream))) {
            CaptureFormat.readHeader(input);
            CaptureFormat.Framed framed;
            while ((framed = CaptureFormat.next(input, 1024 * 1024)) != null) {
                if (framed.truncated()) throw new IOException("Truncated fixture record");
                records.add(framed.record());
            }
        }
        return records;
    }
}
