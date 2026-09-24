// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import com.google.protobuf.Timestamp;
import io.github.lhotari.jonoffcpu.agent.ManifestProto.ArtifactPaths;
import io.github.lhotari.jonoffcpu.agent.ManifestProto.AsyncProfiler;
import io.github.lhotari.jonoffcpu.agent.ManifestProto.Failure;
import io.github.lhotari.jonoffcpu.agent.ManifestProto.FailureCode;
import io.github.lhotari.jonoffcpu.agent.ManifestProto.Manifest;
import io.github.lhotari.jonoffcpu.agent.ManifestProto.ManifestState;
import io.github.lhotari.jonoffcpu.agent.ManifestProto.ThreadPolicy;
import io.github.lhotari.jonoffcpu.capture.CaptureProto.AnalysisInputs;
import io.github.lhotari.jonoffcpu.capture.ProtoJson;
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

/**
 * The capture's audit manifest, the {@link Manifest} message, which the controller fills in step by step. Every
 * write replaces the file atomically with the message's proto3 JSON, so a reader sees one complete state or the
 * previous one.
 */
final class ManifestStore {
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    private final Path captureDirectory;
    private final Path manifestPath;
    private final Path sourcePath;
    private final Path jfrPath;
    private final Object jfrDevice;
    private final Object jfrInode;
    private final Manifest.Builder manifest;

    private ManifestStore(
            Path captureDirectory, Path jfrPath, Object jfrDevice, Object jfrInode, Manifest.Builder manifest)
            throws IOException {
        this.captureDirectory = captureDirectory;
        this.manifestPath = Path.of(manifest.getArtifactPaths().getManifest());
        this.sourcePath = Path.of(manifest.getArtifactPaths().getSource());
        this.jfrPath = jfrPath;
        this.jfrDevice = jfrDevice;
        this.jfrInode = jfrInode;
        this.manifest = manifest;
        write(true);
    }

    /**
     * A capture file that sits next to the correlation stream and shares its stem: {@code jonoffcpu-capture.pb}
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
        Manifest.Builder root = Manifest.newBuilder()
                .setSessionId(sessionId)
                .setComplete(false)
                .setState(ManifestState.MANIFEST_STATE_PREPARING)
                .setMode(
                        config.profilerOnly()
                                ? ManifestProto.CaptureMode.CAPTURE_MODE_PROFILER_ONLY
                                : ManifestProto.CaptureMode.CAPTURE_MODE_SIGNAL_CAPTURE)
                .setCreatedAt(now())
                .setArtifactPaths(ArtifactPaths.newBuilder()
                        .setSource(correlation.toString())
                        .setJfr(jfr.toString())
                        .setManifest(manifestPath.toString()))
                .setTargetPid(Math.toIntExact(config.targetPid()))
                .setAsyncProfiler(AsyncProfiler.newBuilder()
                        .setRequestedLibraryPath(config.asyncProfilerLibrary().toString())
                        .setRequestedOptions(config.asyncProfilerOptions())
                        .setProtocol("signal-capture-v1"))
                .setSampling(config.sampling())
                .setTimeSplit(config.timeSplit())
                .setThreadPolicy(ThreadPolicy.newBuilder()
                        .setControllerWorker("jonoffcpu-signal-controller")
                        .setControllerWorkerDaemon(true)
                        .setSourceRuntimeWorkers(1)
                        .setSignalTargets("source-selected target application threads"))
                .setJdkVersion(System.getProperty("java.version"));
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
        return sourcePath;
    }

    Path jfrPath() {
        return jfrPath;
    }

    /** The manifest being built; a change is published by the next {@link #checkpoint} or state write. */
    Manifest.Builder root() {
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

    void state(ManifestState state) throws IOException {
        manifest.setState(state).setComplete(false);
        write(false);
    }

    void failure(ManifestState state, Throwable error) throws IOException {
        Failure.Builder failure = Failure.newBuilder()
                .setCode(failureCode(error))
                .setException(error.getClass().getName())
                .setMessage(bounded(error.getMessage() == null ? error.toString() : error.getMessage()))
                .setRecordedAt(now());
        if (error instanceof CollectorException collector) failure.setCollectorError(collector.error());
        manifest.setState(state).setComplete(false).setFailure(failure);
        write(false);
    }

    void complete(AnalysisInputs analysisInputs) throws IOException {
        manifest.setAnalysisInputs(analysisInputs)
                .clearFailure()
                .setState(ManifestState.MANIFEST_STATE_COMPLETE)
                .setComplete(true)
                .setCompletedAt(now());
        write(false);
    }

    void checkpoint() throws IOException {
        write(false);
    }

    static Timestamp now() {
        Instant now = Instant.now();
        return Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
    }

    private static FailureCode failureCode(Throwable error) {
        if (error instanceof CollectorException) return FailureCode.FAILURE_CODE_COLLECTOR_ERROR;
        if (error instanceof IllegalStateException) return FailureCode.FAILURE_CODE_INVALID_STATE;
        if (error instanceof IllegalArgumentException) return FailureCode.FAILURE_CODE_INVALID_ARGUMENT;
        if (error instanceof IOException) return FailureCode.FAILURE_CODE_IO_ERROR;
        if (error instanceof InterruptedException) return FailureCode.FAILURE_CODE_INTERRUPTED;
        return FailureCode.FAILURE_CODE_INTERNAL_ERROR;
    }

    private void write(boolean create) throws IOException {
        byte[] bytes = (ProtoJson.pretty(manifest.build()) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MANIFEST_BYTES) {
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
