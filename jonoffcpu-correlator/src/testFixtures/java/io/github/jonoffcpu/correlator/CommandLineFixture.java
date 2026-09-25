// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/** Runs the command line in-process, as the tests of every command do. */
final class CommandLineFixture {
    private CommandLineFixture() {}

    record Invocation(int code, String out, String err) {}

    /** Runs one command line in-process, capturing what picocli prints. */
    static Invocation invoke(String... args) throws Exception {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Cli.run(args, new PrintWriter(out), new PrintWriter(err));
        return new Invocation(code, out.toString(), err.toString());
    }

    /**
     * A reproduce command's words after {@code java -jar jonoffcpu-correlator.jar}, as a shell splits what {@link
     * Top#shell} quoted, to run it in-process.
     */
    static String[] words(String command) {
        assertThat(command).startsWith(Cli.NAME + " ");
        List<String> words = Top.words(command);
        return words.subList(1, words.size()).toArray(String[]::new);
    }

    /** Checks that a command line is refused as a usage error, with its usage and without a stack trace. */
    static Invocation usageError(String message, String... args) throws Exception {
        Invocation invocation = invoke(args);
        assertThat(invocation.code())
                .as("A usage error must return 64: %s", invocation)
                .isEqualTo(Cli.USAGE);
        assertThat(invocation.err())
                .as("The usage error must say '%s'", message)
                .contains(message)
                .as("A usage error must show the usage")
                .contains("Usage: ")
                .as("A usage error must not print a stack trace")
                .doesNotContain("Exception", "\tat ");
        return invocation;
    }
}
