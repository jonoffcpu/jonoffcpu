// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import java.nio.file.Path;

/** Thin JNI access to the single registry owned by {@code libjonoffcpu_native}. */
public final class NativeCollector {
    private static Path loadedPath;

    private NativeCollector() {}

    public static synchronized void load(Path library) {
        Path resolved = library.toAbsolutePath().normalize();
        if (loadedPath == null) {
            System.load(resolved.toString());
            loadedPath = resolved;
        } else if (!loadedPath.equals(resolved)) {
            throw new IllegalStateException("NativeCollector is already bound to " + loadedPath);
        }
    }

    public static native String prepare(String configJson);

    public static native String enable(long handle, String captureJson);

    public static native String stop(long handle, long timeoutMillis);

    public static native String close(long handle);
}
