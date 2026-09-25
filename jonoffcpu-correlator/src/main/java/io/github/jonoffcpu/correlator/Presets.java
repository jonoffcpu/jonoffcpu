// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pattern lists bundled with the correlator, named {@code preset:NAME} wherever a pattern file is accepted. Each is a
 * pattern file like any other: a comment header stating its purpose and caveats, then one pattern per line.
 */
final class Presets {
    static final String PREFIX = "preset:";

    /** Every bundled preset meant for the option it is given to, as that preset's {@code # options:} line says. */
    static final String ALL = PREFIX + "*";

    /** The header line naming the options whose {@link #ALL} includes the preset, such as {@code # options: hide}. */
    private static final String OPTIONS = "# options:";

    /** A preset source the option cannot use: an unknown preset, or {@link #ALL} for an option without presets. */
    static final class Unusable extends IllegalArgumentException {
        Unusable(String message) {
            super(message);
        }
    }

    /** Every bundled preset, in the order {@code stacks --list-presets} prints them. */
    static final List<String> NAMES = List.of("jvm-infra", "jvm-wait-machinery", "jvm-waiting", "jvm-dispatch");

    private static final String DIRECTORY = "/io/github/jonoffcpu/correlator/presets/";

    private Presets() {}

    static List<String> lines(String name) throws IOException {
        if (!NAMES.contains(name)) {
            throw new Unusable("Unknown preset: " + name + " (one of " + String.join(", ", NAMES) + ", or *)");
        }
        try (InputStream input = Presets.class.getResourceAsStream(DIRECTORY + name + ".txt")) {
            if (input == null) throw new IOException("Missing bundled preset " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
        }
    }

    /** The options a preset is meant for, without their {@code --} and {@code -from}: {@code hide}, {@code waiting}. */
    static List<String> options(String name) throws IOException {
        for (String line : lines(name)) {
            if (line.startsWith(OPTIONS)) {
                return Arrays.stream(line.substring(OPTIONS.length()).split(","))
                        .map(String::trim)
                        .filter(option -> !option.isEmpty())
                        .toList();
            }
        }
        return List.of();
    }

    /**
     * The presets {@link #ALL} stands for in {@code fromOption}, such as {@code --hide-from}, in the order {@code
     * --list-presets} prints them; refused when none is meant for the option, rather than expanding to nothing.
     */
    static List<String> all(String fromOption) throws IOException {
        String option = fromOption.replaceFirst("^--", "").replaceFirst("-from$", "");
        List<String> presets = new ArrayList<>();
        for (String name : NAMES) {
            if (options(name).contains(option)) presets.add(PREFIX + name);
        }
        if (presets.isEmpty()) {
            throw new Unusable(fromOption + " " + ALL + " matches no bundled preset: none is meant for " + fromOption);
        }
        return presets;
    }

    /** A {@code -from} argument as the files and presets it stands for: {@link #ALL} expanded, anything else as is. */
    static List<String> expand(String fromOption, String file) throws IOException {
        return file.equals(ALL) ? all(fromOption) : List.of(file);
    }

    /** Every preset with its header and patterns, as {@code --list-presets} prints them. */
    static String listing() throws IOException {
        StringBuilder text = new StringBuilder();
        for (String name : NAMES) {
            if (text.length() > 0) text.append('\n');
            text.append(PREFIX).append(name);
            List<String> options = options(name);
            if (!options.isEmpty()) {
                text.append(" (" + ALL + " of ")
                        .append(String.join(
                                ", ",
                                options.stream()
                                        .map(option -> "--" + option + "-from")
                                        .toList()))
                        .append(')');
            }
            text.append('\n');
            for (String line : lines(name)) text.append("  ").append(line).append('\n');
        }
        return text.toString();
    }
}
