// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

/**
 * An open-addressed {@code long -> int} map for the cookie index and the announced-stack-id set.
 *
 * <p>The join needs one bit more than a plain map: whether a key was seen once or more than once.
 * {@link #observe} records the first slot and replaces it with {@link #DUPLICATE} on any later
 * observation, which is all duplicate-cookie invalidation needs; the audit pass re-reads the
 * offending rows when it needs their content. Sixteen bytes per entry replaces the roughly 150 a
 * {@code HashMap<String, ArrayList<Row>>} entry costs.
 *
 * <p>Zero is a legal key — an observation whose cookie decodes to zero is invalid but still belongs
 * in its duplicate bucket — so it is held in a dedicated field rather than colliding with the empty
 * slot marker.
 */
final class LongIntMap {
    static final int ABSENT = -1;
    static final int DUPLICATE = Integer.MIN_VALUE;

    private long[] keys;
    private int[] values;
    private int mask;
    private int size;
    private int growAt;
    private boolean hasZero;
    private int zeroValue;

    LongIntMap(int expected) {
        int capacity = 16;
        while (capacity < Math.max(16, expected) * 2) capacity <<= 1;
        allocate(capacity);
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new int[capacity];
        mask = capacity - 1;
        growAt = capacity / 2;
    }

    /** SplitMix64's finalizer: cookies carry their epoch in the high half, so the low bits alone collide. */
    private static long mix(long key) {
        long value = key;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    /** Records a slot for this key, or marks the key duplicated if it already has one. */
    void observe(long key, int slot) {
        if (key == 0) {
            zeroValue = hasZero ? DUPLICATE : slot;
            hasZero = true;
            return;
        }
        int index = (int) mix(key) & mask;
        while (keys[index] != 0) {
            if (keys[index] == key) {
                values[index] = DUPLICATE;
                return;
            }
            index = (index + 1) & mask;
        }
        keys[index] = key;
        values[index] = slot;
        if (++size >= growAt) grow();
    }

    /** Unconditional insert, for uses that carry no duplicate semantics (the announced-stack set). */
    void put(long key, int value) {
        if (key == 0) {
            hasZero = true;
            zeroValue = value;
            return;
        }
        int index = (int) mix(key) & mask;
        while (keys[index] != 0) {
            if (keys[index] == key) {
                values[index] = value;
                return;
            }
            index = (index + 1) & mask;
        }
        keys[index] = key;
        values[index] = value;
        if (++size >= growAt) grow();
    }

    int get(long key) {
        if (key == 0) return hasZero ? zeroValue : ABSENT;
        int index = (int) mix(key) & mask;
        while (keys[index] != 0) {
            if (keys[index] == key) return values[index];
            index = (index + 1) & mask;
        }
        return ABSENT;
    }

    boolean contains(long key) {
        return get(key) != ABSENT;
    }

    int size() {
        return size + (hasZero ? 1 : 0);
    }

    long retainedBytes() {
        return (long) keys.length * (Long.BYTES + Integer.BYTES);
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        allocate(oldKeys.length * 2);
        size = 0;
        for (int index = 0; index < oldKeys.length; index++) {
            if (oldKeys[index] != 0) put(oldKeys[index], oldValues[index]);
        }
    }
}
