// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The C library the running JVM is linked against. The embedded native bundle is built separately for glibc
 * and musl, and a library from the wrong flavour fails to relocate, so the flavour is detected from the
 * loader actually mapped into this process rather than from distribution metadata.
 */
enum NativeLibc {
    GLIBC("glibc"),
    MUSL("musl");

    /** Explicit override, for hosts where neither the process maps nor the loader path are conclusive. */
    static final String OVERRIDE_PROPERTY = "io.github.jonoffcpu.agent.nativeLibc";

    private static final Path PROCESS_MAPS = Path.of("/proc/self/maps");

    private final String label;

    NativeLibc(String label) {
        this.label = label;
    }

    /** Bundle directory name under {@code META-INF/native} for this libc and architecture. */
    String platform(String architecture) {
        return this == MUSL ? "linux-musl-" + architecture : "linux-" + architecture;
    }

    /**
     * Detects the libc of this process: the override property first, then the mapped loader, then the
     * system loader path. {@code architecture} is the bundle architecture, used for the loader file name.
     */
    static NativeLibc detect(String architecture) {
        return fromOverride(System.getProperty(OVERRIDE_PROPERTY))
                .or(() -> fromMaps(readProcessMaps()))
                .or(() -> fromLoaderFiles(Path.of("/"), architecture))
                .orElseThrow(
                        () -> new UnsupportedOperationException("Cannot determine whether this JVM uses glibc or musl;"
                                + " set -D" + OVERRIDE_PROPERTY + "=glibc or =musl"));
    }

    static Optional<NativeLibc> fromOverride(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "glibc", "gnu" -> Optional.of(GLIBC);
            case "musl" -> Optional.of(MUSL);
            default ->
                throw new IllegalArgumentException(
                        "Unsupported " + OVERRIDE_PROPERTY + " value '" + value + "'; expected glibc or musl");
        };
    }

    /**
     * Classifies {@code /proc/self/maps} content by the mapped dynamic loader or libc. musl maps
     * {@code ld-musl-<arch>.so.1} (also aliased as {@code libc.musl-<arch>.so.1}); glibc maps {@code libc.so.6}.
     */
    static Optional<NativeLibc> fromMaps(String maps) {
        boolean musl = false;
        boolean glibc = false;
        for (String line : maps.split("\n")) {
            int slash = line.indexOf(" /");
            if (slash < 0) {
                continue;
            }
            String path = line.substring(slash + 1);
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.startsWith("ld-musl-") || name.startsWith("libc.musl-")) {
                musl = true;
            } else if (name.equals("libc.so.6")) {
                glibc = true;
            }
        }
        if (musl && glibc) {
            throw new IllegalStateException("Both musl and glibc loaders are mapped into this JVM; set -D"
                    + OVERRIDE_PROPERTY + "=glibc or =musl");
        }
        if (musl) {
            return Optional.of(MUSL);
        }
        if (glibc) {
            return Optional.of(GLIBC);
        }
        return Optional.empty();
    }

    /** Falls back to the musl loader's fixed install path; absence is inconclusive rather than glibc. */
    static Optional<NativeLibc> fromLoaderFiles(Path root, String architecture) {
        for (String directory : List.of("lib", "usr/lib")) {
            if (Files.isRegularFile(root.resolve(directory).resolve("ld-musl-" + architecture + ".so.1"))) {
                return Optional.of(MUSL);
            }
        }
        return Optional.empty();
    }

    private static String readProcessMaps() {
        try {
            return Files.readString(PROCESS_MAPS, StandardCharsets.ISO_8859_1);
        } catch (IOException | RuntimeException unavailable) {
            return "";
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
