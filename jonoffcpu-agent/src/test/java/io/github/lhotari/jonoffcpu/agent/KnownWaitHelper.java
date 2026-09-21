// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

/** Linux clock and wait operations used by the known-wait attribution fixture. */
final class KnownWaitHelper {
    static {
        String library = System.getProperty("jonoffcpu.knownWaitLibrary");
        if (library == null || library.isBlank()) {
            throw new IllegalStateException("Missing jonoffcpu.knownWaitLibrary");
        }
        System.load(library);
    }

    private KnownWaitHelper() {}

    static native long currentTid();

    static native long monotonicNanos();

    static native int nativeWaitNanos(long durationNanos);
}
