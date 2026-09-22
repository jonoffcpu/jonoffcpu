// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

final class ArtifactVerifier {
    private static final int MAX_LINE_BYTES = 1024 * 1024;
    private static final BigInteger MAX_U64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    record Artifact(String path, String bytes, String sha256) {
        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("path", path);
            result.addProperty("bytes", bytes);
            result.addProperty("sha256", sha256);
            return result;
        }
    }

    record RawArtifact(String path, String rawBytes, String rawSha256) {
        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("path", path);
            result.addProperty("rawBytes", rawBytes);
            result.addProperty("rawSha256", rawSha256);
            return result;
        }
    }

    record JfrResult(long processId, long processStartTimeMillis, Map<String, String> counters) {}

    private ArtifactVerifier() {}

    static JsonObject verifySource(
            Path source,
            JsonObject stop,
            String sessionId,
            long epoch,
            int signal,
            String delivery,
            SamplingConfig sampling,
            long hostTgid,
            long targetPid,
            JsonObject verifiedIdentity)
            throws IOException {
        JsonObject expectedEnd = JsonSupport.requireObject(stop, "captureEnd");
        JsonObject start = null;
        JsonObject end = null;
        long observations = 0;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            String line;
            while ((line = readLine(input)) != null) {
                JsonObject row = JsonSupport.parseObject(line, "source NDJSON row", MAX_LINE_BYTES);
                JsonSupport.requireNumber(row, "schemaVersion", 1, 1);
                String type = JsonSupport.requireString(row, "recordType");
                if (end != null) {
                    throw new IOException("Source rows follow captureEnd");
                }
                switch (type) {
                    case "captureStart" -> {
                        if (start != null || observations != 0)
                            throw new IOException("Duplicate/out-of-order captureStart");
                        requireIdentity(row, sessionId, epoch);
                        JsonSupport.requireEqual(
                                "sourceId", "jonoffcpu.offcpu.v1", JsonSupport.requireString(row, "sourceId"));
                        JsonSupport.requireEqual(
                                "signal",
                                (long) signal,
                                JsonSupport.requireNumber(row, "signal", 1, Integer.MAX_VALUE));
                        JsonSupport.requireEqual(
                                "signalDelivery", delivery, JsonSupport.requireString(row, "signalDelivery"));
                        JsonSupport.requireEqual(
                                "sampling", sampling.json(), JsonSupport.requireObject(row, "sampling"));
                        JsonSupport.requireEqual(
                                "hostTgid", hostTgid, JsonSupport.requireNumber(row, "hostTgid", 1, 0xffffffffL));
                        JsonSupport.requireEqual(
                                "targetPid", targetPid, JsonSupport.requireNumber(row, "targetPid", 1, 0xffffffffL));
                        JsonSupport.requireEqual(
                                "registrationToken",
                                JsonSupport.requireString(verifiedIdentity, "registrationToken"),
                                JsonSupport.requireString(row, "registrationToken"));
                        JsonSupport.requireEqual(
                                "processGenerationNs",
                                JsonSupport.requireDecimal(verifiedIdentity, "processGenerationNs"),
                                JsonSupport.requireDecimal(row, "processGenerationNs"));
                        JsonSupport.requireEqual(
                                "timeNamespaceInode",
                                JsonSupport.requireDecimal(verifiedIdentity, "timeNamespaceInode"),
                                JsonSupport.requireDecimal(row, "timeNamespaceInode"));
                        for (String key : new String[] {"pidNamespaceDevice", "pidNamespaceInode"}) {
                            BigInteger expected = requireU64(verifiedIdentity, key);
                            if (expected.signum() == 0) throw new IOException("Invalid namespace identity: " + key);
                            JsonSupport.requireEqual(key, expected, requireU64(row, key));
                        }
                        start = row;
                    }
                    case "observation" -> {
                        if (start == null) throw new IOException("Observation before captureStart");
                        validateObservation(row, sessionId, epoch, sampling, hostTgid, targetPid, verifiedIdentity);
                        observations++;
                    }
                    case "captureEnd" -> {
                        if (start == null) throw new IOException("captureEnd before captureStart");
                        requireIdentity(row, sessionId, epoch);
                        JsonSupport.requireEqual(
                                "source end state", "complete", JsonSupport.requireString(row, "state"));
                        end = row;
                    }
                    default -> throw new IOException("Unknown source record type: " + type);
                }
            }
        }
        if (start == null || end == null) throw new IOException("Incomplete source stream");
        if (!end.equals(expectedEnd)) throw new IOException("Source captureEnd differs from native stop response");
        JsonObject counters = JsonSupport.requireObject(end, "counters");
        JsonObject kernel = JsonSupport.requireObject(counters, "kernel");
        for (String key : new String[] {
            "switchOuts",
            "schedulerExitSwitches",
            "schedulerExitNoSwitches",
            "lifetimeRejections",
            "eligibleIntervals",
            "eligibleDurationMicros",
            "admissionRejections",
            "selectedIntervals",
            "sequenceExhaustions",
            "sequenceContentions",
            "threadStateFailures",
            "kernelStackFailures",
            "userStackFailures",
            "signalFailures",
            "ringReserveFailures"
        }) {
            requireU64(kernel, key);
        }
        if (requireU64(kernel, "targetNamespaceFailures").signum() != 0) {
            throw new IOException("Source target namespace mapping failed");
        }
        JsonObject userspace = JsonSupport.requireObject(counters, "userspace");
        BigInteger received = requireU64(userspace, "receivedObservations");
        BigInteger written = requireU64(userspace, "writtenObservations");
        if (!written.equals(BigInteger.valueOf(observations))) {
            throw new IOException("Source written observation count mismatch");
        }
        if (!received.equals(written)) throw new IOException("Source received/written observation count mismatch");
        // Missing native stacks remain explicit observations; they do not mean the source file was
        // lost.
        requireU64(userspace, "symbolizationFailures");
        for (String key : new String[] {"writeFailures", "pollFailures", "drainTimedOut"}) {
            if (requireU64(userspace, key).signum() != 0) {
                throw new IOException("Source " + key + " is nonzero");
            }
        }
        if (JsonSupport.requireBoolean(end, "drainTimedOut")) throw new IOException("Source drain timed out");
        BigInteger started = requireU64(end, "startedMonotonicNanos");
        BigInteger stopped = requireU64(end, "stoppedMonotonicNanos");
        BigInteger detached = requireU64(end, "detachedMonotonicNanos");
        BigInteger drained = requireU64(end, "drainCompletedMonotonicNanos");
        if (started.compareTo(stopped) > 0 || stopped.compareTo(detached) > 0 || detached.compareTo(drained) > 0) {
            throw new IOException("Source lifecycle timestamps are out of order");
        }
        return end;
    }

    static JfrResult verifyJfr(
            Path jfr, String sessionId, long epoch, int signal, String delivery, Map<String, String> expectedCounters)
            throws IOException {
        JfrValidation validation = new JfrValidation(sessionId, epoch, signal, delivery, expectedCounters);
        try (RecordingFile recording = new RecordingFile(jfr)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                if (event.getEventType().getName().startsWith("profiler.Signal")) {
                    validation.accept(event);
                }
            }
        }
        return validation.result();
    }

    /** Confirms the recording parses with the public JDK reader; profiler-only captures carry no jonoffcpu events. */
    static void requireReadableJfr(Path jfr) throws IOException {
        try (RecordingFile recording = new RecordingFile(jfr)) {
            while (recording.hasMoreEvents()) {
                recording.readEvent();
            }
        }
    }

    /** Creates a correlation stream that holds only a finalization footer, for captures without an eBPF source. */
    static void writeFooterOnly(Path source, JsonObject footer) throws IOException {
        byte[] bytes = (footer + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > JsonSupport.MAX_CONTROL_BYTES)
            throw new IOException("captureFinalized footer exceeds 64 KiB");
        try (FileChannel file = FileChannel.open(source, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
        }
    }

    static Artifact artifact(Path directory, Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Artifact is not a regular file: " + path);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return new Artifact(
                directory.relativize(path).toString(), Long.toString(Files.size(path)), hex(digest.digest()));
    }

    static RawArtifact rawArtifact(Path directory, Path path) throws IOException {
        Artifact artifact = artifact(directory, path);
        return new RawArtifact(artifact.path(), artifact.bytes(), artifact.sha256());
    }

    static void appendFinalized(Path source, RawArtifact raw, JsonObject footer) throws IOException {
        RawArtifact current = rawArtifact(source.getParent(), source);
        if (!raw.rawBytes().equals(current.rawBytes()) || !raw.rawSha256().equals(current.rawSha256())) {
            throw new IOException("Correlation file changed after native finalization validation");
        }
        byte[] bytes = (footer + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > JsonSupport.MAX_CONTROL_BYTES)
            throw new IOException("captureFinalized footer exceeds 64 KiB");
        long expected = Long.parseLong(raw.rawBytes());
        try (FileChannel file = FileChannel.open(source, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            if (file.size() != expected || file.position() != expected) {
                throw new IOException("Correlation file changed before finalization footer append");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
            if (file.size() != Math.addExact(expected, bytes.length)) {
                throw new IOException("Correlation footer append size mismatch");
            }
        }
    }

    private static void requireIdentity(JsonObject value, String sessionId, long epoch) {
        JsonSupport.requireEqual("sessionId", sessionId, JsonSupport.requireString(value, "sessionId"));
        JsonSupport.requireEqual(
                "captureEpoch", epoch, JsonSupport.requireNumber(value, "captureEpoch", 1, 0xffffffffL));
    }

    private static BigInteger requireU64(JsonObject object, String key) {
        String value = JsonSupport.requireDecimal(object, key);
        BigInteger number = new BigInteger(value);
        if (number.compareTo(MAX_U64) > 0) throw new IllegalArgumentException("u64 overflow: " + key);
        return number;
    }

    private static void validateObservation(
            JsonObject row,
            String sessionId,
            long epoch,
            SamplingConfig sampling,
            long hostTgid,
            long targetPid,
            JsonObject verifiedIdentity)
            throws IOException {
        requireIdentity(row, sessionId, epoch);
        JsonSupport.requireEqual(
                "observation sourceId", "jonoffcpu.offcpu.v1", JsonSupport.requireString(row, "sourceId"));
        JsonSupport.requireEqual(
                "observation hostTgid", hostTgid, JsonSupport.requireNumber(row, "hostTgid", 1, 0xffffffffL));
        JsonSupport.requireNumber(row, "hostTid", 1, 0xffffffffL);
        JsonSupport.requireEqual(
                "observation targetTgid", targetPid, JsonSupport.requireNumber(row, "targetTgid", 1, 0xffffffffL));
        JsonSupport.requireNumber(row, "targetTid", 1, 0xffffffffL);
        String cookieText = JsonSupport.requireString(row, "correlationId");
        if (!cookieText.matches("[0-9a-f]{16}")) throw new IOException("Invalid observation cookie");
        long cookie = Long.parseUnsignedLong(cookieText, 16);
        if ((cookie >>> 32) != epoch || (cookie & 0xffffffffL) == 0) {
            throw new IOException("Observation cookie does not belong to capture epoch");
        }
        JsonSupport.requireEqual(
                "observation processGenerationNs",
                JsonSupport.requireDecimal(verifiedIdentity, "processGenerationNs"),
                JsonSupport.requireDecimal(row, "processGenerationNs"));
        JsonSupport.requireEqual(
                "observation registrationToken",
                JsonSupport.requireString(verifiedIdentity, "registrationToken"),
                JsonSupport.requireString(row, "registrationToken"));
        requireU64(row, "threadGenerationNs");
        BigInteger start = requireU64(row, "startMonotonicNanos");
        BigInteger end = requireU64(row, "endMonotonicNanos");
        if (start.compareTo(end) > 0) throw new IOException("Observation has negative duration");
        // The kernel records the exact threshold it drew against; recompute it from the policy and duration.
        JsonSupport.requireEqual(
                "observation admissionThreshold",
                sampling.admissionThreshold(end.subtract(start).longValueExact()),
                JsonSupport.requireNumber(row, "admissionThreshold", 1, SamplingConfig.CERTAIN_ADMISSION));
        for (String stackName : new String[] {"kernelStack", "userStack"}) {
            JsonObject stack = JsonSupport.requireObject(row, stackName);
            String status = JsonSupport.requireString(stack, "status");
            if (!status.equals("ok") && !status.equals("error")) throw new IOException("Invalid stack status");
            JsonElement frames = stack.get("frames");
            if (frames == null || !frames.isJsonArray()) throw new IOException("Missing stack frames");
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (line.size() == 0) return null;
                throw new IOException("Source NDJSON final row has no newline");
            }
            if (value == '\n') return line.toString(StandardCharsets.UTF_8);
            if (line.size() == MAX_LINE_BYTES) throw new IOException("Source NDJSON row exceeds 1 MiB");
            line.write(value);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private record JfrContext(long processId, long processStartTimeMillis) {}

    private static final class JfrValidation {
        private final String sessionId;
        private final long epoch;
        private final int signal;
        private final String delivery;
        private final Map<String, String> expectedCounters;
        private JfrContext context;
        private Map<String, String> counters;

        private JfrValidation(
                String sessionId, long epoch, int signal, String delivery, Map<String, String> expectedCounters) {
            this.sessionId = sessionId;
            this.epoch = epoch;
            this.signal = signal;
            this.delivery = delivery;
            this.expectedCounters = expectedCounters;
        }

        private void accept(RecordedEvent event) throws IOException {
            String type = event.getEventType().getName();
            try {
                switch (type) {
                    case "profiler.SignalCapture" -> acceptCapture(event);
                    case "profiler.SignalSample" -> acceptSample(event);
                    case "profiler.SignalCaptureStats" -> acceptStats(event);
                    default -> throw new IOException("Unsupported signal event type: " + type);
                }
            } catch (IllegalArgumentException | ClassCastException error) {
                throw new IOException("Invalid " + type + " event", error);
            }
        }

        private void acceptCapture(RecordedEvent event) throws IOException {
            requireIdentity(event);
            if (event.getInt("signal") != signal) {
                throw new IOException("JFR signal does not match the configured signal");
            }
            if (!delivery.equals(event.getString("signalDelivery"))) {
                throw new IOException("JFR signal delivery does not match the configured policy");
            }
            long processId = event.getLong("processId");
            long processStartTimeMillis = event.getLong("processStartTimeMillis");
            if (processId < 1 || processStartTimeMillis < 0) {
                throw new IOException("Invalid JFR process identity");
            }
            JfrContext found = new JfrContext(processId, processStartTimeMillis);
            if (context != null && !context.equals(found)) {
                throw new IOException("Conflicting JFR context");
            }
            context = found;
        }

        private void acceptSample(RecordedEvent event) throws IOException {
            if (context == null) {
                throw new IOException("Signal sample precedes JFR capture context");
            }
            long cookie = event.getLong("correlationId");
            if (cookie >>> 32 != epoch || (cookie & 0xffffffffL) == 0) {
                throw new IOException("Signal sample cookie does not belong to capture epoch");
            }
        }

        private void acceptStats(RecordedEvent event) throws IOException {
            if (context == null) {
                throw new IOException("Signal capture stats precede JFR capture context");
            }
            if (counters != null) {
                throw new IOException("Duplicate JFR stats");
            }
            requireIdentity(event);
            Map<String, String> found = new LinkedHashMap<>();
            for (Map.Entry<String, String> expected : expectedCounters.entrySet()) {
                String value = Long.toUnsignedString(event.getLong(expected.getKey()));
                if (!expected.getValue().equals(value)) {
                    throw new IOException("JFR/AP counter mismatch: " + expected.getKey());
                }
                found.put(expected.getKey(), value);
            }
            counters = Map.copyOf(found);
        }

        private void requireIdentity(RecordedEvent event) throws IOException {
            if (event.getInt("schemaVersion") != 1
                    || !sessionId.equals(event.getString("sessionId"))
                    || epoch != event.getLong("captureEpoch")) {
                throw new IOException("Conflicting signal capture identity or unsupported schema");
            }
        }

        private JfrResult result() throws IOException {
            if (context == null || counters == null) {
                throw new IOException("Incomplete JFR validation stream");
            }
            return new JfrResult(context.processId(), context.processStartTimeMillis(), counters);
        }
    }
}
