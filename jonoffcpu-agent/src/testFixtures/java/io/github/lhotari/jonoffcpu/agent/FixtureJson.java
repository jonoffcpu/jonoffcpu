// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Indented JSON of the plain values the workloads report as evidence to the proof tools: maps, lists, records,
 * strings, numbers and booleans. The workloads run with only the agent JAR beside them, whose JSON libraries are
 * relocated, so they print their evidence themselves.
 */
final class FixtureJson {
    private FixtureJson() {}

    static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, "");
        return out.append('\n').toString();
    }

    private static void write(StringBuilder out, Object value, String indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            string(out, text);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            object(out, map.entrySet().iterator(), map.isEmpty(), indent);
        } else if (value instanceof List<?> list) {
            String inner = indent + "  ";
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                out.append(i == 0 ? "\n" : ",\n").append(inner);
                write(out, list.get(i), inner);
            }
            out.append(list.isEmpty() ? "]" : "\n" + indent + "]");
        } else if (value.getClass().isRecord()) {
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    component.getAccessor().setAccessible(true);
                    fields.put(component.getName(), component.getAccessor().invoke(value));
                } catch (ReflectiveOperationException error) {
                    throw new IllegalStateException(error);
                }
            }
            write(out, fields, indent);
        } else {
            throw new IllegalArgumentException("No JSON form for " + value.getClass());
        }
    }

    private static void object(
            StringBuilder out, Iterator<? extends Map.Entry<?, ?>> entries, boolean empty, String indent) {
        String inner = indent + "  ";
        out.append('{');
        boolean first = true;
        while (entries.hasNext()) {
            Map.Entry<?, ?> entry = entries.next();
            out.append(first ? "\n" : ",\n").append(inner);
            first = false;
            string(out, String.valueOf(entry.getKey()));
            out.append(": ");
            write(out, entry.getValue(), inner);
        }
        out.append(empty ? "}" : "\n" + indent + "}");
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
