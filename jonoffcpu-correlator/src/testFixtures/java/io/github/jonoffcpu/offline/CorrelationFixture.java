// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.offline;

import static io.github.jonoffcpu.capture.CaptureFixtures.record;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.github.jonoffcpu.capture.CaptureFixtures;
import io.github.jonoffcpu.capture.CaptureProto;
import io.github.jonoffcpu.capture.CaptureRecordFixture;
import io.github.jonoffcpu.capture.ProtoJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
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

    static final String SESSION = UUID.randomUUID().toString();
    static final int EPOCH = 0x80000001;
    static final long COOKIE = Integer.toUnsignedLong(EPOCH) << 32 | 1;
    /** The admission threshold of the default one-percent uniform sampling. */
    static final long THRESHOLD = CaptureFixtures.ONE_PERCENT_THRESHOLD;

    @Name("profiler.SignalCapture")
    @StackTrace(false)
    public static class Capture extends Event {
        public int schemaVersion = 1;
        public String sessionId = SESSION;
        public long captureEpoch = Integer.toUnsignedLong(EPOCH);
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
        public long captureEpoch = Integer.toUnsignedLong(EPOCH);
        public long admittedSignals = 1;
        public long invalidSignalCode;
        public long zeroCookie;
        public long zeroSequence;
        public long staleEpoch;
        public long acceptedCookies = 1;
        public long captureFailures;
        public long submittedSamples = 1;
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

    /** Every signal record of a JFR, as the correlator reads them. */
    static List<SignalProto.SignalRecord> signals(Path jfr) throws IOException {
        List<SignalProto.SignalRecord> rows = new ArrayList<>();
        SignalJfrExporter.visit(jfr, rows::add);
        return rows;
    }

    /** Every sample of a JFR, in recording order. */
    static List<SignalProto.SignalSample> samples(Path jfr) throws IOException {
        return signals(jfr).stream()
                .filter(SignalProto.SignalRecord::hasSample)
                .map(SignalProto.SignalRecord::getSample)
                .toList();
    }

    /** The OS thread of a JFR's first sample, which the matching observation must name. */
    static long sampleThread(Path jfr) throws IOException {
        return samples(jfr).get(0).getOsThreadId();
    }

    /**
     * A blocked interval of 3000 ns, [1000, 4000), with the default cookie, admitted at one percent, whose sample
     * the default recording holds: {@code tid} is that sample's OS thread.
     */
    static CaptureProto.Observation.Builder observation(long tid) {
        return CaptureProto.Observation.newBuilder()
                .setCorrelationId(COOKIE)
                .setHostTgid(CaptureFixtures.HOST_TGID)
                .setHostTid(456)
                .setTargetTid((int) tid)
                .setTargetTgid((int) ProcessHandle.current().pid())
                .setProcessGenerationNanos(100)
                .setThreadGenerationNanos(200)
                .setRegistrationToken(1)
                .setStartMonotonicNanos(1000)
                .setEndMonotonicNanos(4000)
                .setAdmissionThreshold(THRESHOLD)
                .setSignalResult(0)
                .setKernelStackId(KERNEL_STACK_ID)
                .setUserStackId(USER_STACK_ID)
                .setReason(CaptureProto.OffCpuReason.OFF_CPU_REASON_BLOCKED)
                // TASK_INTERRUPTIBLE, which is what the kernel classifies as blocked.
                .setPrevTaskState(1)
                .setPreempted(false);
    }

    /** The two interned stacks every fixture observation references. */
    static final long KERNEL_STACK_ID = 11;

    static final long USER_STACK_ID = 12;

    static CaptureProto.Stack stack(long stackId, String symbol) {
        return CaptureProto.Stack.newBuilder()
                .setId(stackId)
                .addFrame(CaptureProto.Frame.newBuilder()
                        .setAddress(0x00007f0000000001L)
                        .setSymbol(symbol)
                        .setModule("libtest.so"))
                .build();
    }

    static CaptureProto.Sampling uniformSampling() {
        return CaptureFixtures.uniformSampling();
    }

    static CaptureProto.TimeSplit timeSplit(CaptureProto.TimeSplitSource source) {
        return CaptureFixtures.timeSplit(source);
    }

    /** The capture's start: this process as the target, the given sampling and time split. */
    static CaptureProto.CaptureStart.Builder captureStart(
            CaptureProto.Sampling sampling, CaptureProto.TimeSplit split) {
        return CaptureFixtures.captureStart(
                        SESSION, EPOCH, (int) ProcessHandle.current().pid())
                .setSampling(sampling)
                .setTimeSplit(split);
    }

    static Path source(Path dir, Path jfr, List<? extends CaptureProto.ObservationOrBuilder> observations)
            throws IOException {
        return source(dir, jfr, observations, uniformSampling());
    }

    static Path source(
            Path dir,
            Path jfr,
            List<? extends CaptureProto.ObservationOrBuilder> observations,
            CaptureProto.Sampling sampling)
            throws IOException {
        return source(
                dir,
                jfr,
                observations,
                sampling,
                kernel -> {},
                timeSplit(CaptureProto.TimeSplitSource.TIME_SPLIT_SOURCE_OFF));
    }

    /**
     * A finalized capture of {@code observations}, announcing the two fixture stacks first. {@code kernel} adjusts
     * the {@code capture_end} kernel counters, as a collector reports per-reason switch-outs or losses.
     */
    static Path source(
            Path dir,
            Path jfr,
            List<? extends CaptureProto.ObservationOrBuilder> observations,
            CaptureProto.Sampling sampling,
            Consumer<CaptureProto.KernelCounters.Builder> kernel,
            CaptureProto.TimeSplit timeSplit)
            throws IOException {
        List<SignalProto.SignalRecord> signals = signals(jfr);
        CaptureProto.AsyncProfilerStats stats = signals.stream()
                .filter(SignalProto.SignalRecord::hasStats)
                .findFirst()
                .orElseThrow()
                .getStats()
                .getCounters();
        CaptureProto.CaptureStart start = captureStart(sampling, timeSplit).build();
        CaptureProto.CaptureEnd.Builder end = CaptureFixtures.captureEnd(start, observations.size());
        kernel.accept(end.getKernelCountersBuilder());
        List<CaptureProto.Record> records = new ArrayList<>();
        records.add(record(start));
        // Stacks are announced once, before the observations that reference them.
        records.add(record(stack(KERNEL_STACK_ID, "kernel_wait")));
        records.add(record(stack(USER_STACK_ID, "user_wait")));
        for (CaptureProto.ObservationOrBuilder observation : observations) records.add(record(build(observation)));
        records.add(record(end.build()));
        Path result = dir.resolve("source.capture");
        Files.write(result, CaptureFixtures.finalizedStream(records, jfr, stats));
        return result;
    }

    private static CaptureProto.Observation build(CaptureProto.ObservationOrBuilder observation) {
        return observation instanceof CaptureProto.Observation.Builder builder
                ? builder.build()
                : (CaptureProto.Observation) observation;
    }

    static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    /** The stream decoded back into its records. */
    static List<CaptureProto.Record> readRecords(Path source) throws IOException {
        return CaptureRecordFixture.read(source);
    }

    /**
     * Changes the last record of one kind and re-seals the footer's source digest, so validation cannot pass merely
     * by rejecting a stale hash.
     */
    static void mutateSource(
            Path source, CaptureProto.Record.RecordCase recordCase, Consumer<CaptureProto.Record.Builder> mutation)
            throws IOException {
        List<CaptureProto.Record> records = new ArrayList<>(readRecords(source));
        int index = -1;
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).getRecordCase() == recordCase) index = i;
        }
        if (index < 0) throw new IllegalArgumentException("No " + recordCase + " record to mutate");
        CaptureProto.Record.Builder changed = records.get(index).toBuilder();
        mutation.accept(changed);
        records.set(index, changed.build());
        Files.write(source, CaptureFixtures.refinalize(records));
    }

    /** Parses one output file strictly into its message. */
    static <B extends Message.Builder> B parse(Path file, B builder) throws IOException {
        return parse(Files.readString(file), builder);
    }

    static <B extends Message.Builder> B parse(String json, B builder) throws InvalidProtocolBufferException {
        return ProtoJson.parse(json, builder);
    }

    /** Parses a JSON Lines file strictly, one message per line. */
    static <M extends Message> List<M> lines(Path file, java.util.function.Supplier<Message.Builder> builder)
            throws IOException {
        List<M> messages = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isEmpty()) continue;
            @SuppressWarnings("unchecked")
            M message = (M) ProtoJson.parse(line, builder.get()).build();
            messages.add(message);
        }
        return messages;
    }

    static ReportProto.Report report(Path file) throws IOException {
        return parse(file, ReportProto.Report.newBuilder()).build();
    }

    static List<ReportProto.ClassifiedRecord> classifiedRecords(Path file) throws IOException {
        return lines(file, ReportProto.ClassifiedRecord::newBuilder);
    }

    static List<ReportProto.Pair> pairs(Path file) throws IOException {
        return lines(file, ReportProto.Pair::newBuilder);
    }

    static ReportProto.Marker marker(Path file) throws IOException {
        return parse(file, ReportProto.Marker.newBuilder()).build();
    }
}
