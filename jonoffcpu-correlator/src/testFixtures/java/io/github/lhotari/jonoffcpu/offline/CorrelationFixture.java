// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

/**
 * The correlator's synthetic inputs: the signal-cookie JFR events, recordings of them, and the finalized capture
 * stream whose observations match those recordings' samples.
 */
final class CorrelationFixture {
    private CorrelationFixture() {}

    private static final String SESSION = UUID.randomUUID().toString();
    private static final long EPOCH = 0x80000001L;
    private static final long COOKIE = EPOCH << 32 | 1;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    @Name("profiler.SignalCapture")
    @StackTrace(false)
    public static class Capture extends Event {
        public int schemaVersion = 1;
        public String sessionId = SESSION;
        public long captureEpoch = EPOCH;
        public String signalDelivery = "queued";
        public int signal = 35;
        public long processId = ProcessHandle.current().pid();
        public long processStartTimeMillis = 1000;
    }

    @Name("profiler.SignalSample")
    @StackTrace(true)
    public static class Sample extends Event {
        public long correlationId = COOKIE;
        public long monotonicTimeNanos = 5000;
    }

    @Name("profiler.SignalCaptureStats")
    @StackTrace(false)
    public static class Stats extends Event {
        public int schemaVersion = 1;
        public String sessionId = SESSION;
        public long captureEpoch = EPOCH;
        public long admittedSignals = 1;
        public long invalidSignalCode;
        public long zeroCookie;
        public long zeroSequence;
        public long staleEpoch;
        public long acceptedCookies = 1;
        public long captureFailures;
        public long submittedSamples = 1;
    }

    static JsonObject row(String type) {
        JsonObject row = new JsonObject();
        row.addProperty("schemaVersion", 2);
        row.addProperty("recordType", type);
        row.addProperty("sessionId", SESSION);
        row.addProperty("captureEpoch", EPOCH);
        return row;
    }

    static Path recording(Path dir, int count) throws IOException {
        Path file = dir.resolve("original-" + count + ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(Capture.class);
            recording.enable(Sample.class).withStackTrace();
            recording.enable(Stats.class);
            recording.start();
            new Capture().commit();
            for (int i = 0; i < count; i++) new Sample().commit();
            Stats stats = new Stats();
            stats.admittedSignals = stats.acceptedCookies = stats.submittedSamples = count;
            stats.commit();
            recording.stop();
            recording.dump(file);
        }
        return file;
    }

    static Path partialRecording(Path dir, int count) throws IOException {
        Path file = dir.resolve("partial-" + count + ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(Sample.class).withStackTrace();
            recording.start();
            for (int i = 0; i < count; i++) new Sample().commit();
            recording.stop();
            recording.dump(file);
        }
        return file;
    }

    static JsonObject observation(long tid) {
        JsonObject row = row("observation");
        row.addProperty("sourceId", "jonoffcpu.offcpu.v1");
        row.addProperty("correlationId", "8000000100000001");
        row.addProperty("hostTgid", 123);
        row.addProperty("hostTid", 456);
        row.addProperty("targetTid", tid);
        row.addProperty("targetTgid", ProcessHandle.current().pid());
        row.addProperty("admissionThreshold", 42949673);
        row.addProperty("processGenerationNs", "100");
        row.addProperty("registrationToken", "0000000000000001");
        row.addProperty("threadGenerationNs", "200");
        row.addProperty("startMonotonicNanos", "1000");
        row.addProperty("endMonotonicNanos", "4000");
        row.addProperty("signalResult", 0);
        row.addProperty("kernelStackId", KERNEL_STACK_ID);
        row.addProperty("userStackId", USER_STACK_ID);
        return row;
    }

    /** The two interned stacks every fixture observation references. */
    static final long KERNEL_STACK_ID = 11;

    static final long USER_STACK_ID = 12;

    static JsonObject stack(long stackId, String symbol) {
        JsonObject row = row("stack");
        row.addProperty("sourceId", "jonoffcpu.offcpu.v1");
        row.addProperty("stackId", stackId);
        JsonArray frames = new JsonArray();
        JsonObject frame = new JsonObject();
        frame.addProperty("address", "00007f0000000001");
        frame.addProperty("symbol", symbol);
        frame.addProperty("module", "libtest.so");
        frames.add(frame);
        row.add("frames", frames);
        return row;
    }

    static JsonObject uniformSampling() {
        JsonObject admission = new JsonObject();
        admission.addProperty("policy", "uniform");
        admission.addProperty("probability", "0.01");
        admission.addProperty("probabilityThreshold", 42949673);
        return sampling(admission);
    }

    static JsonObject sampling(JsonObject admission) {
        JsonObject sampling = new JsonObject();
        sampling.add("minOffCpuMicros", JsonNull.INSTANCE);
        sampling.add("maxOffCpuMicros", JsonNull.INSTANCE);
        sampling.add("admission", admission);
        return sampling;
    }

    static Path source(Path dir, Path jfr, List<JsonObject> observations) throws IOException {
        return source(dir, jfr, observations, uniformSampling());
    }

    static Path source(Path dir, Path jfr, List<JsonObject> observations, JsonObject sampling) throws IOException {
        return source(dir, jfr, observations, sampling, 2, new JsonObject());
    }

    /**
     * A finalized capture of the given control schema version. {@code extraKernelCounters} are added to the
     * {@code captureEnd} kernel counters, as a version 3 collector reports per-reason switch-outs.
     */
    static Path source(
            Path dir,
            Path jfr,
            List<JsonObject> observations,
            JsonObject sampling,
            int schemaVersion,
            JsonObject extraKernelCounters)
            throws IOException {
        return source(dir, jfr, observations, sampling, schemaVersion, extraKernelCounters, null);
    }

    /** As above, with a version 4 capture's {@code timeSplit} block when it is non-null. */
    static Path source(
            Path dir,
            Path jfr,
            List<JsonObject> observations,
            JsonObject sampling,
            int schemaVersion,
            JsonObject extraKernelCounters,
            JsonObject timeSplit)
            throws IOException {
        List<JsonObject> samples = new ArrayList<>();
        List<JsonObject> statsRows = new ArrayList<>();
        SignalJfrExporter.visit(jfr, raw -> {
            JsonObject row = GSON.toJsonTree(raw).getAsJsonObject();
            if (raw.get("recordType").equals("sample")) samples.add(row);
            if (raw.get("recordType").equals("stats")) statsRows.add(row);
        });
        JsonObject start = row("captureStart");
        start.addProperty("schemaVersion", schemaVersion);
        start.addProperty("sourceId", "jonoffcpu.offcpu.v1");
        start.addProperty("signal", 35);
        start.addProperty("signalDelivery", "queued");
        start.addProperty("hostTgid", 123);
        start.addProperty("targetPid", ProcessHandle.current().pid());
        start.add("sampling", sampling);
        if (timeSplit != null) start.add("timeSplit", timeSplit);
        start.addProperty("processGenerationNs", "100");
        start.addProperty("timeNamespaceInode", "42");
        start.addProperty("pidNamespaceDevice", "4");
        start.addProperty("pidNamespaceInode", "43");
        start.addProperty("registrationToken", "0000000000000001");
        start.addProperty("startedMonotonicNanos", "500");
        JsonObject end = row("captureEnd");
        end.addProperty("schemaVersion", schemaVersion);
        end.addProperty("state", "complete");
        end.addProperty("drainTimedOut", false);
        end.addProperty("startedMonotonicNanos", "500");
        end.addProperty("stoppedMonotonicNanos", "6000");
        end.addProperty("detachedMonotonicNanos", "7000");
        end.addProperty("drainCompletedMonotonicNanos", "8000");
        JsonObject userspace = new JsonObject();
        userspace.addProperty("receivedObservations", Integer.toString(observations.size()));
        userspace.addProperty("writtenObservations", Integer.toString(observations.size()));
        userspace.addProperty("writeFailures", "0");
        userspace.addProperty("pollFailures", "0");
        JsonObject counters = new JsonObject();
        counters.add("userspace", userspace);
        JsonObject kernel = new JsonObject();
        kernel.addProperty("targetNamespaceFailures", "0");
        kernel.addProperty("eligibleIntervals", Integer.toString(observations.size()));
        kernel.addProperty("admissionRejections", "0");
        kernel.addProperty("selectedIntervals", Integer.toString(observations.size()));
        for (String key : List.of(
                "ringReserveFailures",
                "sequenceExhaustions",
                "sequenceContentions",
                "lifetimeRejections",
                "threadStateFailures")) {
            kernel.addProperty(key, "0");
        }
        for (var extra : extraKernelCounters.entrySet()) kernel.add(extra.getKey(), extra.getValue());
        counters.add("kernel", kernel);
        end.add("counters", counters);
        List<JsonObject> rows = new ArrayList<>();
        rows.add(start);
        // Stacks are announced once, before the observations that reference them.
        rows.add(stack(KERNEL_STACK_ID, "kernel_wait"));
        rows.add(stack(USER_STACK_ID, "user_wait"));
        rows.addAll(observations);
        rows.add(end);
        byte[] bytes = CaptureStreamFixture.encode(rows);
        JsonObject footer = row("captureFinalized");
        footer.addProperty("schemaVersion", schemaVersion);
        footer.addProperty("state", "complete");
        JsonObject inputs = start.deepCopy();
        inputs.remove("recordType");
        inputs.addProperty("clockVerified", true);
        inputs.addProperty("monotonicOffsetNanos", "0");
        inputs.add("verifiedIdentity", inputs.deepCopy());
        JsonObject stats = statsRows.get(0).deepCopy();
        for (String key : List.of("schemaVersion", "recordType", "sessionId", "captureEpoch", "startTime"))
            stats.remove(key);
        inputs.add("apStats", stats);
        JsonObject sourceArtifact = new JsonObject();
        sourceArtifact.addProperty("path", "an-unrelated-old-location");
        sourceArtifact.addProperty("rawBytes", Integer.toString(bytes.length));
        sourceArtifact.addProperty(
                "rawSha256", CaptureInput.hex(CaptureInput.sha256().digest(bytes)));
        inputs.add("sourceArtifact", sourceArtifact);
        JsonObject jfrArtifact = new JsonObject();
        jfrArtifact.addProperty("path", "an-unrelated-old-jfr-location");
        jfrArtifact.addProperty("bytes", Long.toString(Files.size(jfr)));
        jfrArtifact.addProperty("sha256", CaptureInput.digest(jfr));
        inputs.add("jfrArtifact", jfrArtifact);
        footer.add("analysisInputs", inputs);
        footer.addProperty(
                "apStopResponse",
                "signal-capture-v1 stopped id="
                        + SESSION
                        + " delivery=queued signal=35 epoch="
                        + EPOCH
                        + " finalized=true stopped-at=9000 reason=explicit"
                        + " admitted="
                        + samples.size()
                        + " invalid-code=0 zero-cookie=0 zero-sequence=0 stale-epoch=0"
                        + " accepted="
                        + samples.size()
                        + " capture-failures=0 submitted="
                        + samples.size()
                        + "\n");
        Path result = dir.resolve("source.capture");
        rows.add(footer);
        Files.write(result, CaptureStreamFixture.encode(rows));
        return result;
    }

    static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    /** The stream decoded back into the JSON rows the fixtures manipulate. */
    static List<JsonObject> readRows(Path source) throws IOException {
        List<JsonObject> rows = new ArrayList<>();
        try (java.io.InputStream input = new java.io.BufferedInputStream(Files.newInputStream(source))) {
            CaptureStream.readHeader(input);
            CaptureStream.Framed framed;
            while ((framed = CaptureStream.next(input, 1024 * 1024)) != null) {
                if (framed.truncated()) throw new IOException("Truncated fixture record");
                rows.add(
                        switch (framed.record().getRecordCase()) {
                            case STACK -> CaptureStream.stackRow(framed.record().getStack());
                            case OBSERVATION ->
                                CaptureStream.observationRow(framed.record().getObservation());
                            default ->
                                JsonParser.parseString(CaptureStream.controlJson(framed.record()))
                                        .getAsJsonObject();
                        });
            }
        }
        return rows;
    }
}
