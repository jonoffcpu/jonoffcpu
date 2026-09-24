// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The interned JFR side of a correlation: one entry per distinct resolved stack and per distinct
 * thread, however many samples reference them.
 *
 * <p>The capture stream already interns its native stacks. The JFR does not, so the correlator
 * interns the delivery stacks itself: a sample keeps only an id, and the frames, the collapsed key
 * and the thread identity are stored once. Two stacks share an id exactly when they agree on the
 * truncation flag and every frame's type, class, method, descriptor, line and bytecode index.
 *
 * <p>A frame's type, class, method and descriptor are each either set or absent in {@code SignalProto.JfrFrame},
 * and this interner keeps an absent field (null) apart from an empty one. The sole producer of these frames, {@code
 * SignalJfrExporter}, sets the class, method and descriptor together from one {@code RecordedMethod} or leaves all
 * three absent, and {@code RecordedMethod} never reports an empty string, so the distinction is never exercised; where
 * it could be, this interner is strictly finer than the collapsed key: it can only split what should stay one stack,
 * never merge two genuinely distinct ones, which is the property the golden outputs depend on.
 *
 * <p>Interning is done without materializing a key per sample: a 64-bit hash is folded over the raw
 * frame list, and a hash hit is confirmed by comparing the raw list against the stored frames. Only
 * a genuinely new stack allocates.
 *
 * <p>Each collapsed key also records which of its frames are Java frames, by async-profiler's frame type, since the
 * key alone cannot tell {@code libjvm.so.Unsafe_Park} from {@code a.b.Class.method}. A frame position is Java only
 * when every stack that collapses to the key has a Java type there: a frame that is sometimes native counts as
 * native.
 */
final class JfrDictionaries {
    private static final String UNAVAILABLE = "[stack unavailable]";
    private static final String UNRESOLVED = "[unresolved]";
    /** async-profiler's frame types for Java code; {@code Native}, {@code C++} and {@code Kernel} are not. */
    private static final Set<String> JAVA_TYPES = Set.of("Interpreted", "JIT compiled", "C1 compiled", "Inlined");

    record Frame(
            String type, String className, String methodName, String descriptor, int lineNumber, int bytecodeIndex) {}

    record Thread(long osThreadId, long javaThreadId, String name) {}

    private final Map<String, String> strings = new HashMap<>();
    private final Map<Long, int[]> stacksByHash = new HashMap<>();
    private final List<Frame[]> stackFrames = new ArrayList<>();
    private final List<Boolean> stackTruncated = new ArrayList<>();
    private final List<Integer> stackCollapsed = new ArrayList<>();
    private final Map<String, Integer> collapsedIds = new HashMap<>();
    private final List<String> collapsedKeys = new ArrayList<>();
    private final List<boolean[]> collapsedJava = new ArrayList<>();
    private final Map<Thread, Integer> threadIds = new HashMap<>();
    private final List<Thread> threads = new ArrayList<>();
    private long stringBytes;
    private long frameBytes;
    private long javaFlagBytes;

    /** Interns the frame list of one sample and returns its stack id. */
    int internStack(List<SignalProto.JfrFrame> rawFrames, boolean truncated, int maxFrames) throws IOException {
        CaptureInput.require(rawFrames.size() <= maxFrames, "Stack frame count limit exceeded");
        long hash = hash(rawFrames, truncated);
        int[] candidates = stacksByHash.get(hash);
        if (candidates != null) {
            for (int candidate : candidates) {
                if (matches(candidate, rawFrames, truncated)) return candidate;
            }
        }
        Frame[] frames = new Frame[rawFrames.size()];
        for (int index = 0; index < frames.length; index++) {
            SignalProto.JfrFrame raw = rawFrames.get(index);
            frames[index] = new Frame(
                    pool(type(raw)),
                    pool(className(raw)),
                    pool(methodName(raw)),
                    pool(descriptor(raw)),
                    raw.getLineNumber(),
                    raw.getBytecodeIndex());
        }
        int id = stackFrames.size();
        stackFrames.add(frames);
        stackTruncated.add(truncated);
        stackCollapsed.add(internCollapsed(collapsed(frames), javaFrames(frames)));
        stacksByHash.merge(hash, new int[] {id}, (existing, added) -> {
            int[] grown = Arrays.copyOf(existing, existing.length + 1);
            grown[existing.length] = added[0];
            return grown;
        });
        frameBytes += 16L + frames.length * 48L;
        return id;
    }

    Frame[] frames(int stackId) {
        return stackFrames.get(stackId);
    }

    boolean truncated(int stackId) {
        return stackTruncated.get(stackId);
    }

    int stackCount() {
        return stackFrames.size();
    }

    int collapsedOf(int stackId) {
        return stackCollapsed.get(stackId);
    }

    String collapsedKey(int collapsedId) {
        return collapsedKeys.get(collapsedId);
    }

    /** Which frames of a collapsed key, root first like the key, are Java frames. */
    boolean[] collapsedJava(int collapsedId) {
        return collapsedJava.get(collapsedId);
    }

    /** Whether async-profiler's frame type names Java code; a missing or unknown type does not. */
    static boolean isJavaType(String type) {
        return type != null && JAVA_TYPES.contains(type);
    }

    int collapsedCount() {
        return collapsedKeys.size();
    }

    int internThread(Long osThreadId, Long javaThreadId, String name) {
        Thread thread =
                new Thread(osThreadId == null ? 0L : osThreadId, javaThreadId == null ? 0L : javaThreadId, pool(name));
        Integer existing = threadIds.get(thread);
        if (existing != null) return existing;
        int id = threads.size();
        threads.add(thread);
        threadIds.put(thread, id);
        return id;
    }

    Thread thread(int threadId) {
        return threads.get(threadId);
    }

    long retainedBytes() {
        return stringBytes
                + frameBytes
                + stacksByHash.size() * 64L
                + collapsedKeys.size() * 64L
                + threads.size() * 96L
                + javaFlagBytes;
    }

    /**
     * The collapsed key: root first, class and method dotted, joined with semicolons. This is the
     * flame graph's identity for a stack, so its escaping is part of the output contract.
     */
    private static String collapsed(Frame[] frames) {
        if (frames.length == 0) return UNAVAILABLE;
        StringBuilder key = new StringBuilder();
        for (int index = frames.length - 1; index >= 0; index--) {
            if (key.length() > 0) key.append(';');
            String type = frames[index].className() == null ? "" : frames[index].className();
            String method = frames[index].methodName() == null ? UNRESOLVED : frames[index].methodName();
            String name = type.isEmpty() ? method : type + "." + method;
            for (int position = 0; position < name.length(); position++) {
                char character = name.charAt(position);
                key.append(
                        switch (character) {
                            case ';' -> ':';
                            case '\n', '\r' -> ' ';
                            default -> character;
                        });
            }
        }
        return key.toString();
    }

    /** The Java flags of a stack, root first; the empty stack's single placeholder frame is not Java. */
    private static boolean[] javaFrames(Frame[] frames) {
        if (frames.length == 0) return new boolean[1];
        boolean[] java = new boolean[frames.length];
        for (int index = 0; index < frames.length; index++) {
            java[frames.length - 1 - index] = isJavaType(frames[index].type());
        }
        return java;
    }

    private int internCollapsed(String key, boolean[] java) {
        Integer existing = collapsedIds.get(key);
        if (existing != null) {
            // Stacks that differ only in a frame's type share the key; they must agree for the frame to be Java.
            boolean[] agreed = collapsedJava.get(existing);
            for (int index = 0; index < agreed.length; index++) agreed[index] &= java[index];
            return existing;
        }
        int id = collapsedKeys.size();
        collapsedKeys.add(key);
        collapsedJava.add(java);
        collapsedIds.put(key, id);
        stringBytes += 48L + 2L * key.length();
        javaFlagBytes += 16L + java.length;
        return id;
    }

    private String pool(String value) {
        if (value == null) return null;
        String existing = strings.get(value);
        if (existing != null) return existing;
        strings.put(value, value);
        stringBytes += 48L + 2L * value.length();
        return value;
    }

    private boolean matches(int stackId, List<SignalProto.JfrFrame> rawFrames, boolean truncated) {
        Frame[] frames = stackFrames.get(stackId);
        if (frames.length != rawFrames.size() || stackTruncated.get(stackId) != truncated) return false;
        for (int index = 0; index < frames.length; index++) {
            SignalProto.JfrFrame raw = rawFrames.get(index);
            Frame frame = frames[index];
            if (!Objects.equals(frame.type(), type(raw))
                    || !Objects.equals(frame.className(), className(raw))
                    || !Objects.equals(frame.methodName(), methodName(raw))
                    || !Objects.equals(frame.descriptor(), descriptor(raw))
                    || frame.lineNumber() != raw.getLineNumber()
                    || frame.bytecodeIndex() != raw.getBytecodeIndex()) {
                return false;
            }
        }
        return true;
    }

    private static long hash(List<SignalProto.JfrFrame> rawFrames, boolean truncated) {
        long hash = truncated ? 0x9e3779b97f4a7c15L : 0x165667b19e3779f9L;
        for (SignalProto.JfrFrame frame : rawFrames) {
            hash = fold(hash, Objects.hashCode(type(frame)));
            hash = fold(hash, Objects.hashCode(className(frame)));
            hash = fold(hash, Objects.hashCode(methodName(frame)));
            hash = fold(hash, Objects.hashCode(descriptor(frame)));
            hash = fold(hash, frame.getLineNumber());
            hash = fold(hash, frame.getBytecodeIndex());
        }
        return hash;
    }

    private static long fold(long hash, int value) {
        return (hash ^ value) * 0xff51afd7ed558ccdL;
    }

    private static String type(SignalProto.JfrFrame frame) {
        return frame.hasType() ? frame.getType() : null;
    }

    private static String className(SignalProto.JfrFrame frame) {
        return frame.hasClassName() ? frame.getClassName() : null;
    }

    private static String methodName(SignalProto.JfrFrame frame) {
        return frame.hasMethodName() ? frame.getMethodName() : null;
    }

    private static String descriptor(SignalProto.JfrFrame frame) {
        return frame.hasMethodDescriptor() ? frame.getMethodDescriptor() : null;
    }
}
