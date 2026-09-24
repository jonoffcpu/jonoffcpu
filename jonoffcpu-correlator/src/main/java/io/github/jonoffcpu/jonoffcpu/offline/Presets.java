// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Pattern lists bundled with the correlator, named {@code preset:NAME} wherever a pattern file is accepted. Each is a
 * pattern file like any other: a comment header stating its purpose and caveats, then one pattern per line.
 */
final class Presets {
    static final String PREFIX = "preset:";

    /** Every bundled preset, in the order {@code stacks --list-presets} prints them. */
    static final List<String> NAMES = List.of("jvm-infra", "jvm-wait-machinery", "jvm-idle");

    private static final String DIRECTORY = "/io/github/jonoffcpu/jonoffcpu/presets/";

    private Presets() {}

    static List<String> lines(String name) throws IOException {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException(
                    "Unknown preset: " + name + " (one of " + String.join(", ", NAMES) + ")");
        }
        try (InputStream input = Presets.class.getResourceAsStream(DIRECTORY + name + ".txt")) {
            if (input == null) throw new IOException("Missing bundled preset " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
        }
    }

    /** Every preset with its header and patterns, as {@code --list-presets} prints them. */
    static String listing() throws IOException {
        StringBuilder text = new StringBuilder();
        for (String name : NAMES) {
            if (text.length() > 0) text.append('\n');
            text.append(PREFIX).append(name).append('\n');
            for (String line : lines(name)) text.append("  ").append(line).append('\n');
        }
        return text.toString();
    }
}
