// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.testing;

/**
 * Reports each scenario of a main-based fixture as it runs, as a test runner reports test methods, so that a long
 * fixture's progress and the scenario that failed are visible in the build output.
 */
public final class FixtureSteps {
    /** A scenario of a fixture. */
    @FunctionalInterface
    public interface Step {
        void run() throws Exception;
    }

    private FixtureSteps() {}

    /** Runs one scenario of the calling fixture class, reporting when it starts and how it ended. */
    public static void step(String name, Step step) throws Exception {
        String fixture = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .getCallerClass()
                .getSimpleName();
        String label = fixture + " > " + name;
        System.out.println(label + " STARTED");
        long started = System.nanoTime();
        try {
            step.run();
        } catch (Throwable failure) {
            System.out.println(label + " FAILED (" + millis(started) + " ms): " + failure);
            throw failure;
        }
        System.out.println(label + " PASSED (" + millis(started) + " ms)");
    }

    private static long millis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
