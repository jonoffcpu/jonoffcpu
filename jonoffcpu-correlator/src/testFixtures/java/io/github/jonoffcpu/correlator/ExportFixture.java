// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds stack profiles and exports them through the command line, as the unit and DuckDB export tests do. */
final class ExportFixture {
    private ExportFixture() {}

    static StackProfile.Frame frame(StackProfile.Kind kind, String name) {
        return new StackProfile.Frame(kind, name, kind == StackProfile.Kind.USER ? "libc.so.6" : "");
    }

    static Path export(Path dir, String name, Path profile, String... extra) throws Exception {
        Path output = dir.resolve(name);
        List<String> args =
                new ArrayList<>(List.of("export", "--profile", profile.toString(), "--output", output.toString()));
        args.addAll(List.of(extra));
        CommandLineFixture.Invocation invocation = CommandLineFixture.invoke(args.toArray(String[]::new));
        assertThat(invocation.code()).as("Export failed: %s", invocation).isZero();
        return output;
    }
}
