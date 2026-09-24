// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.agent;

import java.nio.file.Path;

/**
 * Thin JNI access to the single registry owned by {@code libjonoffcpu_native}. Requests and replies are encoded
 * {@code jonoffcpu-collector.proto} messages: {@code prepare} takes a {@code PrepareRequest} and {@code enable} an
 * {@code EnableRequest}, and every call returns a {@code CollectorReply}.
 */
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

    public static native byte[] prepare(byte[] prepareRequest);

    public static native byte[] enable(long handle, byte[] enableRequest);

    public static native byte[] stop(long handle, long timeoutMillis);

    public static native byte[] close(long handle);
}
