// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The JNI bridge's control envelope, through the collector library of the embedded bundle. */
@Tag("host-native")
class NativeCollectorJniTest {
    /** The collector of the bundle the agent JAR embeds for this JVM, which binds it once per JVM. */
    @BeforeAll
    static void loadCollector() {
        NativeBundleLoader.load();
    }

    @Test
    void invalidPrepareIsRejected() {
        JsonObject invalid = JsonSupport.controlEnvelope(NativeCollector.prepare("{}"), "prepare");
        assertThat(invalid.get("ok").getAsBoolean())
                .as("invalid prepare succeeded")
                .isFalse();
        assertThat(JsonSupport.requireString(JsonSupport.requireObject(invalid, "error"), "code"))
                .isEqualTo("invalid_config");
    }

    @Test
    void unknownHandleCloseIsRejected() {
        JsonObject unknown = JsonSupport.controlEnvelope(NativeCollector.close(0x8000000000000001L), "close");
        assertThat(unknown.get("ok").getAsBoolean())
                .as("unknown close succeeded")
                .isFalse();
        assertThat(JsonSupport.requireString(JsonSupport.requireObject(unknown, "error"), "code"))
                .isEqualTo("invalid_handle");
    }
}
