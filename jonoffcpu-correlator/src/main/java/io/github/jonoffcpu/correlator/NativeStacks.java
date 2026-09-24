// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import io.github.jonoffcpu.capture.CaptureProto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The capture's interned kernel and user stacks, retained for the stack profile. The capture announces each
 * distinct stack once, so this holds one copy per distinct stack however many observations reference it, with
 * the symbol and module strings pooled across stacks.
 *
 * <p>Frames are stored root first, the order of a collapsed stack; the kernel reports them leaf first.
 */
final class NativeStacks {
    record Frame(String symbol, String module) {}

    private final Map<Integer, Frame[]> stacks = new HashMap<>();
    private final Map<String, String> strings = new HashMap<>();
    private long retainedBytes;

    void add(long stackId, CaptureProto.Stack stack) {
        int count = stack.getFrameCount();
        Frame[] frames = new Frame[count];
        for (int index = 0; index < count; index++) {
            CaptureProto.Frame frame = stack.getFrame(index);
            frames[count - 1 - index] = new Frame(pool(frame.getSymbol()), pool(frame.getModule()));
        }
        stacks.put(Math.toIntExact(stackId), frames);
        retainedBytes += 64L + 32L * count;
    }

    /** The frames of an announced stack, root first; null for {@link SourceColumns#NO_STACK}. */
    Frame[] frames(int stackId) {
        return stackId == SourceColumns.NO_STACK ? null : stacks.get(stackId);
    }

    List<Integer> ids() {
        return new ArrayList<>(stacks.keySet());
    }

    long retainedBytes() {
        return retainedBytes;
    }

    private String pool(String value) {
        String existing = strings.get(value);
        if (existing != null) return existing;
        strings.put(value, value);
        retainedBytes += 48L + 2L * value.length();
        return value;
    }
}
