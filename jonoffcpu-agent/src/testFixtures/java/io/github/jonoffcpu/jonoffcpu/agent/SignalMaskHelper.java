// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.agent;

/** Thread-local signal-mask controls used only by the opt-in pressure integration fixture. */
final class SignalMaskHelper {
    static {
        String library = System.getProperty("jonoffcpu.signalMaskLibrary");
        if (library == null || library.isBlank()) {
            throw new IllegalStateException("Missing jonoffcpu.signalMaskLibrary");
        }
        System.load(library);
    }

    private SignalMaskHelper() {}

    static void blocked(int signal, boolean blocked) {
        int result = setBlocked(signal, blocked);
        if (result != 0) {
            throw new IllegalStateException("pthread_sigmask failed with " + result);
        }
    }

    static native int setBlocked(int signal, boolean blocked);

    static native long currentTid();
}
