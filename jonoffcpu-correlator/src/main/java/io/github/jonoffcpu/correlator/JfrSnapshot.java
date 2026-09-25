// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;

/**
 * Accumulates the {@link SignalProto.JfrRecording} snapshot while the recording is read: the first event of each
 * {@link #EVENTS kind} that describes the process or machine, whole, the initial system properties and environment
 * variables, and async-profiler's own {@code jdk.ActiveRecording}. Everything else in the recording is ignored.
 */
final class JfrSnapshot {
    /** The events kept whole, the first of each kind. */
    static final List<String> EVENTS =
            List.of("jdk.JVMInformation", "jdk.OSInformation", "jdk.CPUInformation", "jdk.ContainerConfiguration");

    /** The fields of {@code jdk.JVMInformation} that hold the command line, which can carry secrets. */
    static final List<String> COMMAND_LINE = List.of("jvmArguments", "javaArguments");

    private static final String ASYNC_PROFILER = "async-profiler ";

    private final SignalProto.JfrRecording.Builder recording = SignalProto.JfrRecording.newBuilder();
    private final TreeMap<String, String> properties = new TreeMap<>();
    private final TreeMap<String, String> environment = new TreeMap<>();

    void accept(RecordedEvent event) {
        String type = event.getEventType().getName();
        switch (type) {
            case "jdk.InitialSystemProperty" -> put(properties, event);
            case "jdk.InitialEnvironmentVariable" -> put(environment, event);
            case "jdk.ActiveRecording" -> {
                String name = event.hasField("name") ? event.getString("name") : null;
                if (name != null && name.startsWith(ASYNC_PROFILER) && !recording.hasAsyncProfilerVersion()) {
                    recording.setAsyncProfilerVersion(name.substring(ASYNC_PROFILER.length()));
                }
            }
            default -> {
                if (EVENTS.contains(type) && !recording.containsEvents(type)) {
                    recording.putEvents(type, struct(event));
                }
            }
        }
    }

    private static void put(TreeMap<String, String> map, RecordedEvent event) {
        if (!event.hasField("key") || !event.hasField("value")) return;
        String key = event.getString("key");
        String value = event.getString("value");
        if (key != null && value != null) map.putIfAbsent(key, value);
    }

    /**
     * Every field of a recorded object: a timestamp or a duration as its ISO-8601 text, a nested object as a nested
     * struct, an array as a list, a number, boolean or string as itself.
     */
    static Struct struct(RecordedObject object) {
        Struct.Builder struct = Struct.newBuilder();
        for (ValueDescriptor field : object.getFields()) {
            String name = field.getName();
            String content = field.getContentType();
            Value value;
            if ("jdk.jfr.Timestamp".equals(content)) {
                Instant instant = object.getInstant(name);
                value = text(instant == null ? null : instant.toString());
            } else if ("jdk.jfr.Timespan".equals(content)) {
                value = text(object.getDuration(name).toString());
            } else {
                value = value(object.getValue(name));
            }
            struct.putFields(name, value);
        }
        return struct.build();
    }

    private static Value value(Object value) {
        if (value == null)
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        if (value instanceof RecordedObject object) {
            return Value.newBuilder().setStructValue(struct(object)).build();
        }
        if (value instanceof Object[] array) {
            ListValue.Builder list = ListValue.newBuilder();
            for (Object element : array) list.addValues(value(element));
            return Value.newBuilder().setListValue(list).build();
        }
        if (value instanceof Boolean flag)
            return Value.newBuilder().setBoolValue(flag).build();
        if (value instanceof Number number)
            return Value.newBuilder().setNumberValue(number.doubleValue()).build();
        return text(value.toString());
    }

    private static Value text(String text) {
        return text == null
                ? Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()
                : Value.newBuilder().setStringValue(text).build();
    }

    /** The snapshot, with the recording's chunk bounds. */
    SignalProto.JfrRecording build(Instant start, Instant end) {
        return recording
                .clone()
                .setStart(SignalJfrExporter.timestamp(start))
                .setEnd(SignalJfrExporter.timestamp(end))
                .putAllSystemProperties(properties)
                .putAllEnvironmentVariables(environment)
                .build();
    }

    /** The recording without what can hold secrets: the command line, system properties and environment variables. */
    static SignalProto.JfrRecording withoutProcessDetails(SignalProto.JfrRecording recording) {
        SignalProto.JfrRecording.Builder stripped =
                recording.toBuilder().clearSystemProperties().clearEnvironmentVariables();
        Struct jvm = recording.getEventsMap().get("jdk.JVMInformation");
        if (jvm != null) {
            Struct.Builder kept = jvm.toBuilder();
            for (String field : COMMAND_LINE) kept.removeFields(field);
            stripped.putEvents("jdk.JVMInformation", kept.build());
        }
        return stripped.build();
    }

    // ---- reading the snapshot back ---------------------------------------------------------------------------------

    /** A field of a kept event as text, or null when the event or the field is absent or not text. */
    static String text(SignalProto.JfrRecording recording, String event, String field) {
        Value value = field(recording, event, field);
        return value != null && value.hasStringValue() ? value.getStringValue() : null;
    }

    /** A field of a kept event as a whole number, or null when absent or not a number. */
    static Long number(SignalProto.JfrRecording recording, String event, String field) {
        Value value = field(recording, event, field);
        return value != null && value.hasNumberValue() ? (long) value.getNumberValue() : null;
    }

    private static Value field(SignalProto.JfrRecording recording, String event, String field) {
        Struct struct = recording.getEventsMap().get(event);
        return struct == null ? null : struct.getFieldsMap().get(field);
    }
}
