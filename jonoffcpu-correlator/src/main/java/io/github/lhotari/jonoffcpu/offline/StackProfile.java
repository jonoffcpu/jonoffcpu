// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import io.github.lhotari.jonoffcpu.profile.ProfileProto;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The stack profile: deduplicated stacks with their counters, from which any collapsed-stack slice can be
 * rendered again without re-reading the capture and the JFR. {@code docs/schema/jonoffcpu-profile.proto} is the
 * file format's definition; this class holds the expanded, in-memory form.
 *
 * <p>Entries are kept in one canonical order — by Java stack, kernel stack, user stack, reason, task state and
 * thread — so writing the same profile twice gives the same bytes, and the string, frame and node ids the file
 * assigns follow from that order rather than from the order intervals were matched in.
 */
record StackProfile(Header header, List<Entry> entries) {
    static final byte[] MAGIC = "JONOFFPRF\0".getBytes(StandardCharsets.US_ASCII);
    static final int FORMAT_VERSION = 1;
    static final int HEADER_BYTES = MAGIC.length + 2;
    /** Schema 2 added {@link Kind#JFR_NATIVE}; a schema 1 profile tags every frame of its Java stacks JAVA. */
    static final int SCHEMA_VERSION = 2;

    static final int OLDEST_SCHEMA_VERSION = 1;
    static final String PRODUCER = "jonoffcpu-correlator";
    static final String WEIGHT_SEMANTICS = "selected-observed-offcpu-nanoseconds";
    /** The reason vocabulary written into every profile; {@code Entry.reason} indexes it. */
    static final List<String> REASONS =
            Arrays.stream(OffCpuReason.values()).map(OffCpuReason::label).toList();

    private static final int MAX_RECORD_BYTES = 1 << 20;

    /** A frame's kind; its ordinal plus one is the wire value. */
    enum Kind {
        JAVA,
        USER,
        KERNEL,
        /**
         * A frame of the Java stack that async-profiler did not record as Java code ({@code Native}, {@code C++},
         * {@code Kernel}, or no type): rendered like a Java frame, but its name is never read as a package.
         */
        JFR_NATIVE
    }

    /** One frame: a Java frame's collapsed name, or a native frame's symbol and module. */
    record Frame(Kind kind, String name, String module) {}

    record Provenance(
            String sessionId,
            long captureEpoch,
            String sourceSha256,
            String originalJfrSha256,
            String samplingJson,
            String thinningProbability,
            long thinningSeed,
            long windowFromNanos,
            long windowToNanos,
            String timeSplitJson) {}

    record Header(
            List<Provenance> sources,
            List<String> dimensions,
            boolean estimateAvailable,
            String reportJson,
            String label,
            List<String> dimensionsDropped,
            boolean timeSplitAvailable) {
        Header {
            sources = List.copyOf(sources);
            dimensions = List.copyOf(dimensions);
            dimensionsDropped = List.copyOf(dimensionsDropped);
        }

        /** A header whose entries carry no sleeping/run-queue split. */
        Header(
                List<Provenance> sources,
                List<String> dimensions,
                boolean estimateAvailable,
                String reportJson,
                String label,
                List<String> dimensionsDropped) {
            this(sources, dimensions, estimateAvailable, reportJson, label, dimensionsDropped, false);
        }

        /** The correlation-time thinning of the (single) source, or {@link Thinning#NONE}. */
        Thinning thinning() {
            if (sources.size() != 1) return Thinning.NONE;
            Provenance source = sources.get(0);
            return source.thinningProbability().equals("1")
                    ? Thinning.NONE
                    : Thinning.of(source.thinningProbability(), source.thinningSeed());
        }
    }

    /** Stacks are root first; a null stack is absent. {@code thread} is null when not grouped or unknown. */
    record Entry(
            List<Frame> javaStack,
            List<Frame> kernelStack,
            List<Frame> userStack,
            OffCpuReason reason,
            int taskState,
            String thread,
            long intervals,
            long observedNanos,
            long estimatedNanos,
            Split split) {
        /** An entry whose time is all unsplit. */
        Entry(
                List<Frame> javaStack,
                List<Frame> kernelStack,
                List<Frame> userStack,
                OffCpuReason reason,
                int taskState,
                String thread,
                long intervals,
                long observedNanos,
                long estimatedNanos) {
            this(
                    javaStack,
                    kernelStack,
                    userStack,
                    reason,
                    taskState,
                    thread,
                    intervals,
                    observedNanos,
                    estimatedNanos,
                    Split.unsplit(observedNanos, estimatedNanos));
        }

        Key key() {
            return new Key(javaStack, kernelStack, userStack, reason, taskState, thread);
        }
    }

    /**
     * An entry's observed and estimated nanoseconds split by what the thread was doing: sleeping until its wakeup,
     * waiting on a run queue, or unsplit when the interval had no usable run-queue reading. Each triple sums to its
     * total; {@link TimeSplit} holds the rule.
     */
    record Split(
            long sleeping,
            long runqueue,
            long unsplit,
            long estimatedSleeping,
            long estimatedRunqueue,
            long estimatedUnsplit) {
        static Split unsplit(long observedNanos, long estimatedNanos) {
            return new Split(0, 0, observedNanos, 0, 0, estimatedNanos);
        }

        Split plus(Split other) throws IOException {
            return new Split(
                    U64.add(sleeping, other.sleeping, "Profile sleeping"),
                    U64.add(runqueue, other.runqueue, "Profile run queue"),
                    U64.add(unsplit, other.unsplit, "Profile unsplit"),
                    U64.add(estimatedSleeping, other.estimatedSleeping, "Profile sleeping estimate"),
                    U64.add(estimatedRunqueue, other.estimatedRunqueue, "Profile run queue estimate"),
                    U64.add(estimatedUnsplit, other.estimatedUnsplit, "Profile unsplit estimate"));
        }

        /** The part of one kind, observed or estimated. */
        long nanos(TimeSplit.Part part, boolean estimated) {
            return switch (part) {
                case SLEEPING -> estimated ? estimatedSleeping : sleeping;
                case RUNQUEUE -> estimated ? estimatedRunqueue : runqueue;
                case UNSPLIT -> estimated ? estimatedUnsplit : unsplit;
            };
        }

        /** Whether each triple adds up to its entry's total. */
        boolean adds(long observedNanos, long estimatedNanos) {
            try {
                return U64.add(U64.add(sleeping, runqueue, "split"), unsplit, "split") == observedNanos
                        && U64.add(U64.add(estimatedSleeping, estimatedRunqueue, "split"), estimatedUnsplit, "split")
                                == estimatedNanos;
            } catch (IOException overflow) {
                return false;
            }
        }
    }

    record Key(
            List<Frame> javaStack,
            List<Frame> kernelStack,
            List<Frame> userStack,
            OffCpuReason reason,
            int taskState,
            String thread) {}

    StackProfile {
        entries = canonical(entries);
    }

    long totalIntervals() {
        long total = 0;
        for (Entry entry : entries) total = Math.addExact(total, entry.intervals());
        return total;
    }

    long totalObservedNanos() throws IOException {
        long total = 0;
        for (Entry entry : entries) total = U64.add(total, entry.observedNanos(), "Profile duration");
        return total;
    }

    long totalEstimatedNanos() throws IOException {
        long total = 0;
        for (Entry entry : entries) total = U64.add(total, entry.estimatedNanos(), "Profile estimate");
        return total;
    }

    // ---- building from a correlation -----------------------------------------------------------

    /**
     * The profile of one streamed correlation. {@code estimateAvailable} is the population estimate's own
     * verdict: the per-entry estimates are the same inverse-probability sum and are only as trustworthy.
     */
    static StackProfile of(
            CorrelationResult result,
            String reportJson,
            String label,
            String sourceSha256,
            String originalJfrSha256,
            boolean estimateAvailable)
            throws IOException {
        JfrDictionaries dictionaries = result.dictionaries();
        NativeStacks nativeStacks = result.nativeStacks();
        ProfileAccumulator profile = result.profile();
        Map<Integer, List<Frame>> javaStacks = new HashMap<>();
        Map<Integer, List<Frame>> kernelStacks = new HashMap<>();
        Map<Integer, List<Frame>> userStacks = new HashMap<>();
        List<Entry> entries = new ArrayList<>(profile.entries().size());
        for (var item : profile.entries().entrySet()) {
            ProfileAccumulator.Key key = item.getKey();
            ProfileAccumulator.Counters counters = item.getValue();
            List<Frame> java = javaStacks.computeIfAbsent(key.collapsed(), id -> {
                List<Frame> frames = new ArrayList<>();
                boolean[] javaFrames = dictionaries.collapsedJava(id);
                // A collapsed key's frames never contain ';', so splitting it is lossless.
                String[] names = dictionaries.collapsedKey(id).split(";", -1);
                for (int index = 0; index < names.length; index++) {
                    frames.add(new Frame(javaFrames[index] ? Kind.JAVA : Kind.JFR_NATIVE, names[index], ""));
                }
                return List.copyOf(frames);
            });
            entries.add(new Entry(
                    java,
                    nativeStack(key.kernel(), nativeStacks, kernelStacks, Kind.KERNEL),
                    nativeStack(key.user(), nativeStacks, userStacks, Kind.USER),
                    OffCpuReason.ofOrdinal(key.reason()),
                    key.taskState(),
                    profile.threadName(key.thread()),
                    counters.intervals,
                    counters.observedNanos(),
                    counters.estimatedNanos().longValueExact(),
                    counters.split()));
        }
        CaptureInput capture = result.capture();
        Provenance provenance = new Provenance(
                CaptureInput.text(capture.inputs, "sessionId"),
                CaptureInput.number(capture.inputs, "captureEpoch"),
                sourceSha256,
                originalJfrSha256,
                CaptureInput.object(capture.start, "sampling").toString(),
                result.thinning().probability(),
                result.thinning().seed(),
                result.clipFromNanos() == null ? 0 : result.clipFromNanos(),
                result.clipToNanos() == null ? 0 : result.clipToNanos(),
                capture.start.has("timeSplit")
                        ? CaptureInput.object(capture.start, "timeSplit").toString()
                        : "");
        return new StackProfile(
                new Header(
                        List.of(provenance),
                        profile.dimensions(),
                        estimateAvailable,
                        reportJson,
                        label,
                        profile.dropped(),
                        result.timeSplit().available()),
                entries);
    }

    private static List<Frame> nativeStack(
            int stackId, NativeStacks nativeStacks, Map<Integer, List<Frame>> cache, Kind kind) {
        if (stackId < 0) return null;
        return cache.computeIfAbsent(stackId, id -> {
            NativeStacks.Frame[] frames = nativeStacks.frames(id);
            List<Frame> stack = new ArrayList<>(frames.length);
            for (NativeStacks.Frame frame : frames) stack.add(new Frame(kind, frame.symbol(), frame.module()));
            return List.copyOf(stack);
        });
    }

    // ---- merging -------------------------------------------------------------------------------

    /**
     * Sums the counters of entries with identical keys across profiles. Merged weights are summed durations:
     * they say what dominates across the inputs, not what fraction of any one window it took.
     */
    static StackProfile merge(List<StackProfile> profiles) throws IOException {
        CaptureInput.require(!profiles.isEmpty(), "Nothing to merge");
        List<String> dimensions = profiles.get(0).header().dimensions();
        String label = profiles.get(0).header().label();
        List<Provenance> sources = new ArrayList<>();
        Set<String> dropped = new LinkedHashSet<>();
        boolean estimateAvailable = true;
        boolean timeSplitAvailable = false;
        Map<Key, long[]> merged = new LinkedHashMap<>();
        Map<Key, Split> splits = new HashMap<>();
        Map<List<String>, List<Frame>> javaStacks = javaStacksByName(profiles);
        for (StackProfile profile : profiles) {
            Header header = profile.header();
            CaptureInput.require(header.dimensions().equals(dimensions), "Profiles with different grouping dimensions");
            CaptureInput.require(header.label().equals(label), "Profiles with different collapsed labels");
            for (Provenance source : header.sources()) {
                // A thinned profile's weights are rescaled by its own probability; a sum of differently rescaled
                // profiles has no single scale to apply afterwards.
                CaptureInput.require(source.thinningProbability().equals("1"), "A thinned profile cannot be merged");
                sources.add(source);
            }
            dropped.addAll(header.dimensionsDropped());
            estimateAvailable &= header.estimateAvailable();
            // An input without the split contributes its time as unsplit, which its entries already say.
            timeSplitAvailable |= header.timeSplitAvailable();
            for (Entry entry : profile.entries()) {
                Key key = entry.javaStack() == null
                        ? entry.key()
                        : new Key(
                                javaStacks.get(names(entry.javaStack())),
                                entry.kernelStack(),
                                entry.userStack(),
                                entry.reason(),
                                entry.taskState(),
                                entry.thread());
                long[] counters = merged.computeIfAbsent(key, ignored -> new long[3]);
                counters[0] = Math.addExact(counters[0], entry.intervals());
                counters[1] = U64.add(counters[1], entry.observedNanos(), "Profile duration");
                counters[2] = U64.add(counters[2], entry.estimatedNanos(), "Profile estimate");
                Split split = splits.get(key);
                splits.put(key, split == null ? entry.split() : split.plus(entry.split()));
            }
        }
        List<Entry> entries = new ArrayList<>(merged.size());
        for (var item : merged.entrySet()) {
            Key key = item.getKey();
            long[] counters = item.getValue();
            entries.add(new Entry(
                    key.javaStack(),
                    key.kernelStack(),
                    key.userStack(),
                    key.reason(),
                    key.taskState(),
                    key.thread(),
                    counters[0],
                    counters[1],
                    counters[2],
                    splits.get(key)));
        }
        return new StackProfile(
                new Header(sources, dimensions, estimateAvailable, "", label, List.copyOf(dropped), timeSplitAvailable),
                entries);
    }

    /**
     * One Java stack per distinct list of frame names across the inputs, so a stack merges whatever kinds its inputs
     * gave it: a frame is JAVA only when every input says so. A schema 1 input calls every frame JAVA, which a newer
     * input's JFR_NATIVE therefore overrules.
     */
    private static Map<List<String>, List<Frame>> javaStacksByName(List<StackProfile> profiles) {
        Map<List<String>, List<Frame>> stacks = new HashMap<>();
        for (StackProfile profile : profiles) {
            for (Entry entry : profile.entries()) {
                if (entry.javaStack() == null) continue;
                stacks.merge(names(entry.javaStack()), entry.javaStack(), StackProfile::agreedKinds);
            }
        }
        return stacks;
    }

    private static List<Frame> agreedKinds(List<Frame> left, List<Frame> right) {
        if (left.equals(right)) return left;
        List<Frame> agreed = new ArrayList<>(left.size());
        for (int index = 0; index < left.size(); index++) {
            Frame frame = left.get(index);
            agreed.add(frame.kind() == Kind.JAVA ? right.get(index) : frame);
        }
        return List.copyOf(agreed);
    }

    private static List<String> names(List<Frame> stack) {
        return stack.stream().map(Frame::name).toList();
    }

    // ---- canonical order -----------------------------------------------------------------------

    private static final Comparator<List<Frame>> STACK_ORDER = (left, right) -> {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        for (int index = 0; index < Math.min(left.size(), right.size()); index++) {
            Frame a = left.get(index);
            Frame b = right.get(index);
            int order = a.name().compareTo(b.name());
            if (order == 0) order = a.module().compareTo(b.module());
            if (order == 0) order = a.kind().compareTo(b.kind());
            if (order != 0) return order;
        }
        return Integer.compare(left.size(), right.size());
    };

    private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparing(Entry::javaStack, STACK_ORDER)
            .thenComparing(Entry::kernelStack, STACK_ORDER)
            .thenComparing(Entry::userStack, STACK_ORDER)
            .thenComparing(Entry::reason)
            .thenComparingLong(entry -> Integer.toUnsignedLong(entry.taskState()))
            .thenComparing(Entry::thread, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static List<Entry> canonical(List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(ENTRY_ORDER);
        return List.copyOf(sorted);
    }

    // ---- writing -------------------------------------------------------------------------------

    /** Writes a new file; an existing one is never replaced. */
    void write(Path file) throws IOException {
        try (OutputStream output = new BufferedOutputStream(
                Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), 1 << 16)) {
            write(output);
        }
    }

    void write(OutputStream output) throws IOException {
        output.write(MAGIC);
        output.write(FORMAT_VERSION & 0xff);
        output.write(FORMAT_VERSION >>> 8);
        ProfileProto.ProfileStart.Builder start = ProfileProto.ProfileStart.newBuilder()
                .setSchemaVersion(SCHEMA_VERSION)
                .setProducer(PRODUCER)
                .addAllDimension(header.dimensions())
                .addAllReason(REASONS)
                .setWeightSemantics(WEIGHT_SEMANTICS)
                .setEstimateAvailable(header.estimateAvailable())
                .setTimeSplitAvailable(header.timeSplitAvailable())
                .setReportJson(header.reportJson())
                .setLabel(header.label());
        for (Provenance source : header.sources()) {
            start.addSource(ProfileProto.Provenance.newBuilder()
                    .setSessionId(source.sessionId())
                    .setCaptureEpoch(source.captureEpoch())
                    .setSourceSha256(source.sourceSha256())
                    .setOriginalJfrSha256(source.originalJfrSha256())
                    .setSamplingJson(source.samplingJson())
                    .setThinningProbability(source.thinningProbability())
                    .setThinningSeed(source.thinningSeed())
                    .setWindowFromNanos(source.windowFromNanos())
                    .setWindowToNanos(source.windowToNanos())
                    .setTimeSplitJson(source.timeSplitJson()));
        }
        emit(output, ProfileProto.Record.newBuilder().setProfileStart(start).build());
        Interner interner = new Interner(output);
        for (Entry entry : entries) {
            ProfileProto.Entry.Builder row = ProfileProto.Entry.newBuilder()
                    .setJavaStack(interner.stack(entry.javaStack()))
                    .setKernelStack(interner.stack(entry.kernelStack()))
                    .setUserStack(interner.stack(entry.userStack()))
                    .setReason(entry.reason().ordinal())
                    .setTaskState(entry.taskState())
                    .setThreadNameString(entry.thread() == null ? 0 : interner.string(entry.thread()))
                    .setIntervals(entry.intervals())
                    .setObservedNanos(entry.observedNanos())
                    .setEstimatedNanos(entry.estimatedNanos());
            if (header.timeSplitAvailable()) {
                Split split = entry.split();
                row.setSleepingNanos(split.sleeping())
                        .setRunqueueNanos(split.runqueue())
                        .setUnsplitNanos(split.unsplit())
                        .setEstimatedSleepingNanos(split.estimatedSleeping())
                        .setEstimatedRunqueueNanos(split.estimatedRunqueue())
                        .setEstimatedUnsplitNanos(split.estimatedUnsplit());
            } else {
                CaptureInput.require(
                        entry.split().equals(Split.unsplit(entry.observedNanos(), entry.estimatedNanos())),
                        "A profile without the time split has a split entry");
            }
            emit(output, ProfileProto.Record.newBuilder().setEntry(row).build());
        }
        emit(
                output,
                ProfileProto.Record.newBuilder()
                        .setProfileEnd(ProfileProto.ProfileEnd.newBuilder()
                                .setEntries(entries.size())
                                .setTotalIntervals(totalIntervals())
                                .setTotalObservedNanos(totalObservedNanos())
                                .setTotalEstimatedNanos(totalEstimatedNanos())
                                .addAllDimensionDropped(header.dimensionsDropped()))
                        .build());
    }

    private static void emit(OutputStream output, ProfileProto.Record record) throws IOException {
        record.writeDelimitedTo(output);
    }

    /** Assigns dense ids in first-use order and writes each string, frame and node before its first use. */
    private static final class Interner {
        private final OutputStream output;
        private final Map<String, Integer> strings = new HashMap<>();
        private final Map<Frame, Integer> frames = new HashMap<>();
        private final Map<Long, Integer> nodes = new HashMap<>();

        Interner(OutputStream output) {
            this.output = output;
        }

        int string(String value) throws IOException {
            Integer id = strings.get(value);
            if (id != null) return id;
            int next = strings.size() + 1;
            strings.put(value, next);
            emit(
                    output,
                    ProfileProto.Record.newBuilder()
                            .setString(ProfileProto.StringRecord.newBuilder()
                                    .setId(next)
                                    .setValue(value))
                            .build());
            return next;
        }

        int frame(Frame frame) throws IOException {
            Integer id = frames.get(frame);
            if (id != null) return id;
            int name = string(frame.name());
            int module = frame.module().isEmpty() ? 0 : string(frame.module());
            int next = frames.size() + 1;
            frames.put(frame, next);
            emit(
                    output,
                    ProfileProto.Record.newBuilder()
                            .setFrame(ProfileProto.FrameRecord.newBuilder()
                                    .setId(next)
                                    .setKindValue(frame.kind().ordinal() + 1)
                                    .setNameString(name)
                                    .setModuleString(module))
                            .build());
            return next;
        }

        int stack(List<Frame> stack) throws IOException {
            if (stack == null) return 0;
            int parent = 0;
            for (Frame frame : stack) {
                int frameId = frame(frame);
                long key = ((long) parent << 32) | frameId;
                Integer id = nodes.get(key);
                if (id == null) {
                    id = nodes.size() + 1;
                    nodes.put(key, id);
                    emit(
                            output,
                            ProfileProto.Record.newBuilder()
                                    .setStackNode(ProfileProto.StackNode.newBuilder()
                                            .setId(id)
                                            .setParent(parent)
                                            .setFrame(frameId))
                                    .build());
                }
                parent = id;
            }
            // An empty stack still needs a distinct, non-absent name: it cannot occur for Java stacks, whose
            // empty form is the "[stack unavailable]" frame, and native stacks are never empty on the wire.
            CaptureInput.require(parent != 0, "Empty stack in profile");
            return parent;
        }
    }

    // ---- reading -------------------------------------------------------------------------------

    /** Reads and validates a profile file: framing, reference order, and the end record's totals. */
    static StackProfile read(Path file) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            return read(input);
        }
    }

    static StackProfile read(InputStream input) throws IOException {
        byte[] header = input.readNBytes(HEADER_BYTES);
        CaptureInput.require(
                header.length == HEADER_BYTES && Arrays.equals(Arrays.copyOf(header, MAGIC.length), MAGIC),
                "Not a jonoffcpu stack profile");
        int version = (header[MAGIC.length] & 0xff) | (header[MAGIC.length + 1] & 0xff) << 8;
        CaptureInput.require(version == FORMAT_VERSION, "Unsupported stack profile format version " + version);
        ProfileProto.Record first = next(input);
        CaptureInput.require(
                first != null && first.getRecordCase() == ProfileProto.Record.RecordCase.PROFILE_START,
                "Stack profile does not start with profile_start");
        ProfileProto.ProfileStart start = first.getProfileStart();
        int schema = start.getSchemaVersion();
        CaptureInput.require(
                schema >= OLDEST_SCHEMA_VERSION && schema <= SCHEMA_VERSION,
                "Unsupported stack profile schema " + schema);
        // Kinds a profile's schema does not define are refused rather than guessed.
        int kinds = schema == 1 ? Kind.KERNEL.ordinal() + 1 : Kind.values().length;
        CaptureInput.require(start.getReasonList().equals(REASONS), "Unsupported stack profile reason vocabulary");
        CaptureInput.require(
                start.getWeightSemantics().equals(WEIGHT_SEMANTICS), "Unsupported stack profile weight semantics");
        boolean timeSplitAvailable = start.getTimeSplitAvailable();
        List<Provenance> sources = new ArrayList<>();
        for (ProfileProto.Provenance source : start.getSourceList()) {
            sources.add(new Provenance(
                    source.getSessionId(),
                    source.getCaptureEpoch(),
                    source.getSourceSha256(),
                    source.getOriginalJfrSha256(),
                    source.getSamplingJson(),
                    source.getThinningProbability(),
                    source.getThinningSeed(),
                    source.getWindowFromNanos(),
                    source.getWindowToNanos(),
                    source.getTimeSplitJson()));
        }
        List<String> strings = new ArrayList<>();
        strings.add(null);
        List<Frame> frames = new ArrayList<>();
        frames.add(null);
        List<int[]> nodes = new ArrayList<>();
        nodes.add(null);
        Map<Integer, List<Frame>> expanded = new HashMap<>();
        List<Entry> entries = new ArrayList<>();
        ProfileProto.ProfileEnd end = null;
        ProfileProto.Record record;
        while ((record = next(input)) != null) {
            CaptureInput.require(end == null, "Records follow profile_end");
            switch (record.getRecordCase()) {
                case STRING -> {
                    CaptureInput.require(record.getString().getId() == strings.size(), "Out-of-order string id");
                    strings.add(record.getString().getValue());
                }
                case FRAME -> {
                    ProfileProto.FrameRecord frame = record.getFrame();
                    CaptureInput.require(frame.getId() == frames.size(), "Out-of-order frame id");
                    int kind = frame.getKindValue();
                    CaptureInput.require(kind >= 1 && kind <= kinds, "Invalid frame kind");
                    frames.add(new Frame(
                            Kind.values()[kind - 1],
                            reference(strings, frame.getNameString(), false),
                            frame.getModuleString() == 0 ? "" : reference(strings, frame.getModuleString(), false)));
                }
                case STACK_NODE -> {
                    ProfileProto.StackNode node = record.getStackNode();
                    CaptureInput.require(node.getId() == nodes.size(), "Out-of-order stack node id");
                    CaptureInput.require(
                            node.getParent() < nodes.size() && node.getFrame() > 0 && node.getFrame() < frames.size(),
                            "Stack node references an unwritten record");
                    nodes.add(new int[] {node.getParent(), node.getFrame()});
                }
                case ENTRY -> {
                    ProfileProto.Entry entry = record.getEntry();
                    OffCpuReason reason = OffCpuReason.fromWire(entry.getReason());
                    CaptureInput.require(reason != null, "Invalid entry reason");
                    Split split = new Split(
                            entry.getSleepingNanos(),
                            entry.getRunqueueNanos(),
                            entry.getUnsplitNanos(),
                            entry.getEstimatedSleepingNanos(),
                            entry.getEstimatedRunqueueNanos(),
                            entry.getEstimatedUnsplitNanos());
                    if (timeSplitAvailable) {
                        CaptureInput.require(
                                split.adds(entry.getObservedNanos(), entry.getEstimatedNanos()),
                                "Stack profile entry split does not add up to its totals");
                    } else {
                        // A profile without the split (including one written before it existed) is all unsplit.
                        CaptureInput.require(
                                split.equals(new Split(0, 0, 0, 0, 0, 0)),
                                "Stack profile entry carries a split its header does not announce");
                        split = Split.unsplit(entry.getObservedNanos(), entry.getEstimatedNanos());
                    }
                    entries.add(new Entry(
                            stack(nodes, frames, expanded, entry.getJavaStack()),
                            stack(nodes, frames, expanded, entry.getKernelStack()),
                            stack(nodes, frames, expanded, entry.getUserStack()),
                            reason,
                            entry.getTaskState(),
                            entry.getThreadNameString() == 0
                                    ? null
                                    : reference(strings, entry.getThreadNameString(), false),
                            entry.getIntervals(),
                            entry.getObservedNanos(),
                            entry.getEstimatedNanos(),
                            split));
                }
                case PROFILE_END -> end = record.getProfileEnd();
                default -> throw new IOException("Unexpected stack profile record: " + record.getRecordCase());
            }
        }
        CaptureInput.require(end != null, "Stack profile has no profile_end");
        StackProfile profile = new StackProfile(
                new Header(
                        sources,
                        start.getDimensionList(),
                        start.getEstimateAvailable(),
                        start.getReportJson(),
                        start.getLabel(),
                        end.getDimensionDroppedList(),
                        timeSplitAvailable),
                entries);
        CaptureInput.require(
                end.getEntries() == entries.size()
                        && end.getTotalIntervals() == profile.totalIntervals()
                        && end.getTotalObservedNanos() == profile.totalObservedNanos()
                        && end.getTotalEstimatedNanos() == profile.totalEstimatedNanos(),
                "Stack profile totals do not match its entries");
        return profile;
    }

    private static String reference(List<String> strings, int id, boolean optional) throws IOException {
        if (optional && id == 0) return null;
        CaptureInput.require(id > 0 && id < strings.size(), "Reference to an unwritten string");
        return strings.get(id);
    }

    private static List<Frame> stack(
            List<int[]> nodes, List<Frame> frames, Map<Integer, List<Frame>> expanded, int leaf) throws IOException {
        if (leaf == 0) return null;
        CaptureInput.require(leaf < nodes.size(), "Entry references an unwritten stack node");
        List<Frame> cached = expanded.get(leaf);
        if (cached != null) return cached;
        List<Frame> stack = new ArrayList<>();
        for (int node = leaf; node != 0; node = nodes.get(node)[0]) stack.add(frames.get(nodes.get(node)[1]));
        java.util.Collections.reverse(stack);
        List<Frame> result = List.copyOf(stack);
        expanded.put(leaf, result);
        return result;
    }

    private static ProfileProto.Record next(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) return null;
        long length = first & 0x7f;
        int shift = 7;
        int value = first;
        while ((value & 0x80) != 0) {
            value = input.read();
            CaptureInput.require(value >= 0 && shift < 35, "Truncated stack profile record length");
            length |= (long) (value & 0x7f) << shift;
            shift += 7;
        }
        // The length is checked before anything is allocated for the record.
        CaptureInput.require(length <= MAX_RECORD_BYTES, "Stack profile record too large");
        byte[] bytes = input.readNBytes((int) length);
        CaptureInput.require(bytes.length == length, "Truncated stack profile record");
        return ProfileProto.Record.parseFrom(bytes);
    }
}
