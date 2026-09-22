// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The interned JFR side of a correlation: one entry per distinct resolved stack and per distinct
 * thread, however many samples reference them.
 *
 * <p>The capture stream already interns its native stacks. The JFR does not, so the correlator
 * interns the delivery stacks itself: a sample keeps only an id, and the frames, the collapsed key
 * and the thread identity are stored once. Two stacks share an id exactly when {@code
 * CompatibilityJfrWriter.canonicalStack} would give them the same key — the truncation flag and
 * every frame's type, class, method, descriptor, line and bytecode index. Record equality and that
 * key can only disagree for an <em>empty</em> class name, which differs from a null one in the
 * canonical key but not in the synthetic writer's output; {@code RecordedMethod} never reports one.
 *
 * <p>Interning is done without materializing a key per sample: a 64-bit hash is folded over the raw
 * frame list, and a hash hit is confirmed by comparing the raw list against the stored frames. Only
 * a genuinely new stack allocates.
 */
final class JfrDictionaries {
    private static final String UNAVAILABLE = "[stack unavailable]";
    private static final String UNRESOLVED = "[unresolved]";

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
    private final Map<Thread, Integer> threadIds = new HashMap<>();
    private final List<Thread> threads = new ArrayList<>();
    private long stringBytes;
    private long frameBytes;

    /** Interns the frame list of one sample and returns its stack id. */
    int internStack(List<?> rawFrames, boolean truncated, int maxFrames) throws IOException {
        CaptureInput.require(rawFrames.size() <= maxFrames, "Stack frame count limit exceeded");
        for (Object raw : rawFrames) {
            CaptureInput.require(raw instanceof Map<?, ?>, "Invalid stack frame");
        }
        long hash = hash(rawFrames, truncated);
        int[] candidates = stacksByHash.get(hash);
        if (candidates != null) {
            for (int candidate : candidates) {
                if (matches(candidate, rawFrames, truncated)) return candidate;
            }
        }
        Frame[] frames = new Frame[rawFrames.size()];
        for (int index = 0; index < frames.length; index++) {
            Map<?, ?> raw = (Map<?, ?>) rawFrames.get(index);
            frames[index] = new Frame(
                    pool(text(raw, "type")),
                    pool(text(raw, "className")),
                    pool(text(raw, "methodName")),
                    pool(text(raw, "descriptor")),
                    number(raw, "lineNumber"),
                    number(raw, "bytecodeIndex"));
        }
        int id = stackFrames.size();
        stackFrames.add(frames);
        stackTruncated.add(truncated);
        stackCollapsed.add(internCollapsed(collapsed(frames)));
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
        return stringBytes + frameBytes + stacksByHash.size() * 64L + collapsedKeys.size() * 64L + threads.size() * 96L;
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

    private int internCollapsed(String key) {
        Integer existing = collapsedIds.get(key);
        if (existing != null) return existing;
        int id = collapsedKeys.size();
        collapsedKeys.add(key);
        collapsedIds.put(key, id);
        stringBytes += 48L + 2L * key.length();
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

    private boolean matches(int stackId, List<?> rawFrames, boolean truncated) {
        Frame[] frames = stackFrames.get(stackId);
        if (frames.length != rawFrames.size() || stackTruncated.get(stackId) != truncated) return false;
        for (int index = 0; index < frames.length; index++) {
            Map<?, ?> raw = (Map<?, ?>) rawFrames.get(index);
            Frame frame = frames[index];
            if (!Objects.equals(frame.type(), text(raw, "type"))
                    || !Objects.equals(frame.className(), text(raw, "className"))
                    || !Objects.equals(frame.methodName(), text(raw, "methodName"))
                    || !Objects.equals(frame.descriptor(), text(raw, "descriptor"))
                    || frame.lineNumber() != number(raw, "lineNumber")
                    || frame.bytecodeIndex() != number(raw, "bytecodeIndex")) {
                return false;
            }
        }
        return true;
    }

    private static long hash(List<?> rawFrames, boolean truncated) {
        long hash = truncated ? 0x9e3779b97f4a7c15L : 0x165667b19e3779f9L;
        for (Object raw : rawFrames) {
            Map<?, ?> frame = (Map<?, ?>) raw;
            hash = fold(hash, Objects.hashCode(text(frame, "type")));
            hash = fold(hash, Objects.hashCode(text(frame, "className")));
            hash = fold(hash, Objects.hashCode(text(frame, "methodName")));
            hash = fold(hash, Objects.hashCode(text(frame, "descriptor")));
            hash = fold(hash, number(frame, "lineNumber"));
            hash = fold(hash, number(frame, "bytecodeIndex"));
        }
        return hash;
    }

    private static long fold(long hash, int value) {
        return (hash ^ value) * 0xff51afd7ed558ccdL;
    }

    private static String text(Map<?, ?> frame, String key) {
        Object value = frame.get(key);
        return value == null ? null : value.toString();
    }

    private static int number(Map<?, ?> frame, String key) {
        Object value = frame.get(key);
        return value instanceof Number found ? found.intValue() : 0;
    }
}
