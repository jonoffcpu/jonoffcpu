// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import com.google.gson.JsonObject;
import java.nio.file.Path;

public final class NativeCollectorJniTest {
    public static void main(String[] args) {
        NativeCollector.load(Path.of(args[0]));
        JsonObject invalid = JsonSupport.controlEnvelope(NativeCollector.prepare("{}"), "prepare");
        check(!invalid.get("ok").getAsBoolean(), "invalid prepare unexpectedly succeeded");
        check(
                JsonSupport.requireString(JsonSupport.requireObject(invalid, "error"), "code")
                        .equals("invalid_config"),
                "unexpected prepare error");

        JsonObject unknown = JsonSupport.controlEnvelope(NativeCollector.close(0x8000000000000001L), "close");
        check(!unknown.get("ok").getAsBoolean(), "unknown close unexpectedly succeeded");
        check(
                JsonSupport.requireString(JsonSupport.requireObject(unknown, "error"), "code")
                        .equals("invalid_handle"),
                "unexpected close error");
        System.out.println("NativeCollector JNI fixtures passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
