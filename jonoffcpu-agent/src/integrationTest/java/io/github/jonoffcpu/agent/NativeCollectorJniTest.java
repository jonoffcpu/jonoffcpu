// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jonoffcpu.capture.CollectorProto.CollectorErrorCode;
import io.github.jonoffcpu.capture.CollectorProto.CollectorReply;
import io.github.jonoffcpu.capture.CollectorProto.CollectorState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The JNI bridge's encoded replies, through the collector library of the embedded bundle. */
@Tag("host-native")
class NativeCollectorJniTest {
    /** The collector of the bundle the agent JAR embeds for this JVM, which binds it once per JVM. */
    @BeforeAll
    static void loadCollector() {
        NativeBundleLoader.load();
    }

    @Test
    void emptyPrepareIsRejected() {
        CollectorReply invalid = SignalCaptureController.reply(NativeCollector.prepare(new byte[0]), "prepare");
        assertThat(invalid.hasError()).as("an empty prepare request succeeded").isTrue();
        assertThat(invalid.getState()).isEqualTo(CollectorState.COLLECTOR_STATE_ERROR);
        assertThat(invalid.getError().getCode()).isEqualTo(CollectorErrorCode.COLLECTOR_ERROR_CODE_INVALID_CONFIG);
    }

    @Test
    void unknownHandleCloseIsRejected() {
        CollectorReply unknown = SignalCaptureController.reply(NativeCollector.close(0x8000000000000001L), "close");
        assertThat(unknown.hasError()).as("closing an unknown handle succeeded").isTrue();
        assertThat(unknown.getError().getCode()).isEqualTo(CollectorErrorCode.COLLECTOR_ERROR_CODE_INVALID_HANDLE);
    }
}
