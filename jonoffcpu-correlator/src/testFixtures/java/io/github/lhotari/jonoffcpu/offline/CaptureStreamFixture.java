// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.capture.CaptureRecordFixture;
import java.io.IOException;
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

    static byte[] header() {
        byte[] header = new byte[CaptureStream.HEADER_BYTES];
        System.arraycopy(CaptureStream.MAGIC, 0, header, 0, CaptureStream.MAGIC.length);
        header[CaptureStream.MAGIC.length] = (byte) CaptureStream.FORMAT_VERSION;
        return header;
    }
}
