// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** JNI bridge to the async-profiler C API. */
final class NativeProfiler {
    private static Path loadedLibrary;

    private NativeProfiler() {}

    private static native void initialize0(String library);

    static synchronized void initialize(Path library) {
        Path resolved = library.toAbsolutePath().normalize();
        if (loadedLibrary == null) {
            // Loading through the JVM invokes async-profiler's JNI_OnLoad with the active JavaVM.
            // That initialization is required by JVMTI-backed features such as jfrsync.
            System.load(resolved.toString());
            loadedLibrary = resolved;
        } else if (!loadedLibrary.equals(resolved)) {
            throw new IllegalStateException(
                    "async-profiler is already initialized from a different library: " + loadedLibrary);
        }
        initialize0(resolved.toString());
    }

    private static native byte[] execute0(byte[] command);

    static String execute(String command) {
        if (command == null || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Profiler command must be a non-null NUL-free string");
        }
        byte[] output = execute0(command.getBytes(StandardCharsets.UTF_8));
        return output.length == 0 ? "OK" : new String(output, StandardCharsets.UTF_8);
    }
}
