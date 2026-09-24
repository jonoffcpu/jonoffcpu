// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Embedded extraction and the async-profiler C API bridge, from the agent JAR on the classpath. */
@Tag("host-native")
class NativeBundleLoaderTest {
    @Test
    void extractsTheHostBundleAndReachesAsyncProfiler() {
        NativeBundleLoader.Bundle bundle = NativeBundleLoader.load();
        assertThat(bundle.agent()).as("agent library").isRegularFile();
        assertThat(bundle.collector()).as("collector library").isRegularFile();
        assertThat(bundle.profiler()).as("async-profiler library").isRegularFile();
        assertThat(NativeProfiler.execute("version"))
                .as("async-profiler C API version")
                .isNotBlank();
        assertThat(NativeProfiler.execute("status,signalcookie"))
                .as("the embedded async-profiler's generic signal-cookie interface")
                .contains("signal-capture-v1 inactive");
        assertThat(NativeBundleLoader.load())
                .as("native bundle loading is idempotent")
                .isEqualTo(bundle);
    }
}
