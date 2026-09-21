// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import java.nio.file.Files;

/** End-to-end fixture for embedded extraction and the async-profiler C API bridge. */
public final class NativeBundleLoaderTest {
    private NativeBundleLoaderTest() {}

    public static void main(String[] args) {
        NativeBundleLoader.Bundle bundle = NativeBundleLoader.load();
        check(Files.isRegularFile(bundle.agent()), "agent library was not extracted");
        check(Files.isRegularFile(bundle.collector()), "collector library was not extracted");
        check(Files.isRegularFile(bundle.profiler()), "async-profiler library was not extracted");
        String version = NativeProfiler.execute("version");
        check(!version.isBlank(), "async-profiler C API returned no version");
        String status = NativeProfiler.execute("status,signalcookie");
        check(
                status.contains("signal-capture-v1 inactive"),
                "embedded async-profiler lacks the generic signal-cookie interface: " + status);
        check(NativeBundleLoader.load().equals(bundle), "native bundle loading is not idempotent");
        System.out.println("Embedded native bundle fixture passed");
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
