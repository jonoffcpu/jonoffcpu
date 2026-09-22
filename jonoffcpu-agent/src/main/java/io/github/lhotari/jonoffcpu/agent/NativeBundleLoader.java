// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Extracts and verifies the native bundle embedded in the agent JAR. */
final class NativeBundleLoader {
    private static final String WORK_DIRECTORY_PROPERTY = "io.github.lhotari.jonoffcpu.nativeWorkDir";
    private static Bundle loaded;

    private NativeBundleLoader() {}

    static synchronized Bundle load() {
        if (loaded != null) {
            return loaded;
        }
        String platform = platform();
        Map<String, String> checksums = readChecksums();
        if (checksums.keySet().stream().noneMatch(entry -> entry.startsWith(platform + "/"))) {
            throw new UnsupportedOperationException("The agent JAR has no native bundle for " + platform
                    + "; embedded bundles: " + bundledPlatforms(checksums));
        }
        try {
            Path directory = createExtractionDirectory();
            setOwnerOnly(directory, true);
            Path collector = extract(directory, platform, "libjonoffcpu_native.so", checksums);
            Path profiler = extract(directory, platform, "libasyncProfiler.so", checksums);
            Path agent = extract(directory, platform, "libjonoffcpu.so", checksums);
            collector.toFile().deleteOnExit();
            profiler.toFile().deleteOnExit();
            agent.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
            NativeCollector.load(agent);
            NativeProfiler.initialize(profiler);
            loaded = new Bundle(agent, collector, profiler);
            return loaded;
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Cannot extract the embedded jonoffcpu native bundle for " + platform, error);
        }
    }

    private static Path createExtractionDirectory() throws IOException {
        String configured = System.getProperty(WORK_DIRECTORY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return Files.createTempDirectory("jonoffcpu-native-")
                    .toAbsolutePath()
                    .normalize();
        }
        Path parent = Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        parent = parent.toRealPath();
        return Files.createTempDirectory(parent, "jonoffcpu-native-");
    }

    private static String platform() {
        if (!System.getProperty("os.name", "").equalsIgnoreCase("Linux")) {
            throw new UnsupportedOperationException("jonoffcpu supports only 64-bit Linux");
        }
        String model = System.getProperty("sun.arch.data.model", "64");
        if (!model.equals("64")) {
            throw new UnsupportedOperationException("jonoffcpu requires a 64-bit JVM");
        }
        String architecture =
                switch (System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT)) {
                    case "amd64", "x86_64" -> "x86_64";
                    case "aarch64", "arm64" -> "aarch64";
                    default ->
                        throw new UnsupportedOperationException(
                                "Unsupported jonoffcpu Linux architecture: " + System.getProperty("os.arch"));
                };
        return NativeLibc.detect(architecture).platform(architecture);
    }

    private static String bundledPlatforms(Map<String, String> checksums) {
        return checksums.keySet().stream()
                .map(entry -> entry.substring(0, entry.indexOf('/')))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static Map<String, String> readChecksums() {
        String resource = "/META-INF/native/SHA256SUMS";
        try (InputStream input = NativeBundleLoader.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Agent JAR is missing " + resource);
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (String line : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines()
                    .toList()) {
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split("  ", 2);
                if (fields.length != 2
                        || !fields[0].matches("[0-9a-f]{64}")
                        || result.putIfAbsent(fields[1], fields[0]) != null) {
                    throw new IllegalStateException("Malformed native checksum entry: " + line);
                }
            }
            return Map.copyOf(result);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read embedded native checksums", error);
        }
    }

    private static Path extract(Path directory, String platform, String fileName, Map<String, String> checksums)
            throws IOException {
        String relative = platform + "/" + fileName;
        String expected = checksums.get(relative);
        if (expected == null) {
            throw new IllegalStateException("Native checksum manifest has no entry for " + relative);
        }
        String resource = "/META-INF/native/" + relative;
        Path temporary = directory.resolve(fileName + ".tmp");
        Path target = directory.resolve(fileName);
        try (InputStream input = NativeBundleLoader.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Agent JAR is missing " + resource);
            }
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        setOwnerOnly(temporary, false);
        String actual = sha256(temporary);
        if (!actual.equals(expected)) {
            Files.deleteIfExists(temporary);
            throw new IllegalStateException("Embedded native library checksum mismatch for " + relative);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void setOwnerOnly(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rwx------"));
        }
    }

    record Bundle(Path agent, Path collector, Path profiler) {}
}
