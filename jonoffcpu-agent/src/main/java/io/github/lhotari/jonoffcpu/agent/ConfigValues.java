// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

/**
 * Strict reads from the agent configuration's value tree: the mappings, lists, strings and integers that SnakeYAML's
 * safe loader produces, and that the {@code key=value} native options are parsed into.
 */
final class ConfigValues {
    private ConfigValues() {}

    static void requireKeys(Map<?, ?> value, Set<String> keys, String block) {
        for (Object key : value.keySet()) {
            if (!(key instanceof String name) || !keys.contains(name)) {
                throw new IllegalArgumentException("Unknown " + block + " key: " + key);
            }
        }
    }

    static Map<?, ?> requireMap(Map<?, ?> value, String name) {
        if (!(value.get(name) instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Missing/mapping field " + name);
        }
        return map;
    }

    static String requireString(Map<?, ?> value, String name) {
        if (!(value.get(name) instanceof String text)) {
            throw new IllegalArgumentException("Missing/string field " + name);
        }
        return text;
    }

    /** An integer in {@code minimum..maximum}; a decimal fraction, a quoted number or a boolean is not one. */
    static long requireInteger(Map<?, ?> value, String name, long minimum, long maximum) {
        Object element = value.get(name);
        BigInteger number;
        if (element instanceof Integer || element instanceof Long) {
            number = BigInteger.valueOf(((Number) element).longValue());
        } else if (element instanceof BigInteger big) {
            number = big;
        } else {
            throw new IllegalArgumentException("Missing/integer field " + name);
        }
        if (number.compareTo(BigInteger.valueOf(minimum)) < 0 || number.compareTo(BigInteger.valueOf(maximum)) > 0) {
            throw new IllegalArgumentException("Out-of-range integer field " + name);
        }
        return number.longValueExact();
    }

    static long optionalInteger(Map<?, ?> value, String name, long defaultValue, long minimum, long maximum) {
        return value.get(name) == null ? defaultValue : requireInteger(value, name, minimum, maximum);
    }
}
