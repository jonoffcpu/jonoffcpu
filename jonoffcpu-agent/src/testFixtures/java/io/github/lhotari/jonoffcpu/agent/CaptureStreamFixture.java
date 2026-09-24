// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lhotari.jonoffcpu.capture.CaptureProto;
import io.github.lhotari.jonoffcpu.capture.CaptureRecordFixture;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The capture stream of this module's codec, for fixtures: the header its reader expects, around records that
 * {@link CaptureRecordFixture} encodes from JSON rows.
 */
final class CaptureStreamFixture {
    private CaptureStreamFixture() {}

    static byte[] encode(List<JsonObject> rows) throws IOException {
        return CaptureRecordFixture.encode(header(), rows);
    }

    /** Appends one record to a stream the fixture is building. */
    static void append(Path source, JsonObject row) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CaptureRecordFixture.record(row).writeDelimitedTo(bytes);
        Files.write(source, bytes.toByteArray(), StandardOpenOption.APPEND);
    }

    /** The stream decoded back into the JSON rows the fixtures assert on. */
    static List<JsonObject> rows(Path source) throws IOException {
        List<JsonObject> rows = new ArrayList<>();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            CaptureStream.readHeader(input);
            CaptureProto.Record record;
            while ((record = CaptureStream.next(input, 1024 * 1024)) != null) {
                rows.add(
                        switch (record.getRecordCase()) {
                            case STACK -> CaptureStream.stackRow(record.getStack());
                            case OBSERVATION -> CaptureStream.observationRow(record.getObservation());
                            default ->
                                JsonParser.parseString(CaptureStream.controlJson(record))
                                        .getAsJsonObject();
                        });
            }
        }
        return rows;
    }

    private static byte[] header() {
        byte[] header = new byte[CaptureStream.HEADER_BYTES];
        System.arraycopy(CaptureStream.MAGIC, 0, header, 0, CaptureStream.MAGIC.length);
        header[CaptureStream.MAGIC.length] = (byte) CaptureStream.FORMAT_VERSION;
        return header;
    }
}
