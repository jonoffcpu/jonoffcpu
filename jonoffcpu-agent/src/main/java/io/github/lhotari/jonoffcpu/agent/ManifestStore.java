// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

final class ManifestStore {
    private final Path captureDirectory;
    private final Path manifestPath;
    private final Path jfrPath;
    private final Object jfrDevice;
    private final Object jfrInode;
    private final JsonObject manifest;

    private ManifestStore(Path captureDirectory, Path jfrPath, Object jfrDevice, Object jfrInode, JsonObject manifest)
            throws IOException {
        this.captureDirectory = captureDirectory;
        this.manifestPath = Path.of(
                manifest.getAsJsonObject("artifactPaths").get("manifest").getAsString());
        this.jfrPath = jfrPath;
        this.jfrDevice = jfrDevice;
        this.jfrInode = jfrInode;
        this.manifest = manifest;
        write(true);
    }

    /**
     * A capture file that sits next to the correlation stream and shares its stem: {@code jonoffcpu-capture.ndjson}
     * gets {@code jonoffcpu-capture.manifest.json}, so the whole capture is recognisable by one name. Only the
     * final extension of the file name is replaced; a leading dot or a dot in a directory name is not one.
     */
    static Path sibling(Path correlation, String suffix) {
        String name = correlation.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return correlation.resolveSibling(stem + suffix);
    }

    static ManifestStore create(AgentConfig config, String sessionId) throws IOException {
        Path correlation = config.correlationOutput();
        Path directory = correlation.getParent();
        Path jfr = config.jfrOutput() == null ? sibling(correlation, ".jfr") : config.jfrOutput();
        Path manifestPath = sibling(correlation, ".manifest.json");
        if (correlation.equals(jfr) || correlation.equals(manifestPath) || jfr.equals(manifestPath)) {
            throw new IOException("Capture artifact paths must be distinct");
        }
        if (Files.exists(correlation) || Files.exists(jfr)) {
            throw new IOException("Capture artifact path already exists");
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("sessionId", sessionId);
        root.addProperty("complete", false);
        root.addProperty("state", "preparing");
        root.addProperty("createdAt", Instant.now().toString());

        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("source", correlation.toString());
        artifacts.addProperty("jfr", jfr.toString());
        artifacts.addProperty("manifest", manifestPath.toString());
        root.add("artifactPaths", artifacts);

        JsonObject target = new JsonObject();
        target.addProperty("targetPid", config.targetPid());
        root.add("target", target);

        JsonObject profiler = new JsonObject();
        profiler.addProperty(
                "requestedLibraryPath", config.asyncProfilerLibrary().toString());
        profiler.addProperty("requestedOptions", config.asyncProfilerOptions());
        profiler.addProperty("protocol", "signal-capture-v1");
        root.add("asyncProfiler", profiler);
        root.add("sampling", config.sampling().json());
        root.add("timeSplit", config.timeSplit().json());

        JsonObject threads = new JsonObject();
        threads.addProperty("schemaVersion", 1);
        threads.addProperty("controllerWorker", "jonoffcpu-signal-controller");
        threads.addProperty("controllerWorkerDaemon", true);
        threads.addProperty("sourceRuntimeWorkers", 1);
        threads.addProperty("signalTargets", "source-selected target application threads");
        root.add("threadPolicy", threads);
        root.addProperty("jdkVersion", System.getProperty("java.version"));
        boolean reserved = false;
        try {
            try (FileChannel file = FileChannel.open(jfr, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                file.force(true);
            }
            reserved = true;
            Map<String, Object> identity = Files.readAttributes(jfr, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
            ManifestStore store = new ManifestStore(directory, jfr, identity.get("dev"), identity.get("ino"), root);
            try (FileChannel directoryHandle = FileChannel.open(directory, StandardOpenOption.READ)) {
                directoryHandle.force(true);
            }
            return store;
        } catch (IOException failure) {
            if (reserved) Files.deleteIfExists(jfr);
            throw failure;
        }
    }

    Path directory() {
        return captureDirectory;
    }

    Path manifestPath() {
        return manifestPath;
    }

    Path sourcePath() {
        return Path.of(manifest.getAsJsonObject("artifactPaths").get("source").getAsString());
    }

    Path jfrPath() {
        return jfrPath;
    }

    JsonObject root() {
        return manifest;
    }

    void verifyJfrIdentity() throws IOException {
        if (!Files.isRegularFile(jfrPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Reserved JFR output is no longer a regular file: " + jfrPath);
        }
        Map<String, Object> identity = Files.readAttributes(jfrPath, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
        if (!jfrDevice.equals(identity.get("dev")) || !jfrInode.equals(identity.get("ino"))) {
            throw new IOException("Reserved JFR output identity changed before finalization");
        }
    }

    void state(String state) throws IOException {
        manifest.addProperty("state", state);
        manifest.addProperty("complete", false);
        write(false);
    }

    void failure(String state, Throwable error) throws IOException {
        manifest.addProperty("state", state);
        manifest.addProperty("complete", false);
        JsonObject failure = new JsonObject();
        failure.addProperty("code", JsonSupport.errorCode(error));
        failure.addProperty("message", bounded(error.getMessage() == null ? error.toString() : error.getMessage()));
        failure.addProperty("recordedAt", Instant.now().toString());
        manifest.add("failure", failure);
        write(false);
    }

    void complete(JsonObject analysisInputs) throws IOException {
        manifest.add("analysisInputs", analysisInputs);
        manifest.remove("failure");
        manifest.addProperty("state", "complete");
        manifest.addProperty("complete", true);
        manifest.addProperty("completedAt", Instant.now().toString());
        write(false);
    }

    void checkpoint() throws IOException {
        write(false);
    }

    private void write(boolean create) throws IOException {
        byte[] bytes = (JsonSupport.GSON.toJson(manifest) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024 * 1024) {
            throw new IOException("Manifest exceeds 1 MiB");
        }
        if (create) {
            try (FileChannel file =
                    FileChannel.open(manifestPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writeFully(file, bytes);
                file.force(true);
            }
        } else {
            Path temporary = Files.createTempFile(captureDirectory, ".manifest-", ".tmp");
            try {
                try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    writeFully(file, bytes);
                    file.force(true);
                }
                preservePosixPermissions(manifestPath, temporary);
                Files.move(
                        temporary, manifestPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        try (FileChannel directory = FileChannel.open(captureDirectory, StandardOpenOption.READ)) {
            directory.force(true);
        }
    }

    private static void preservePosixPermissions(Path source, Path target) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Atomic replacement still works on file systems without POSIX permissions.
        }
    }

    private static void writeFully(FileChannel file, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            file.write(buffer);
        }
    }

    private static String bounded(String value) {
        return value.length() <= 4096 ? value : value.substring(0, 4096);
    }
}
