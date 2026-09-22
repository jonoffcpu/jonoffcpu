// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

final class JsonSupport {
    static final int MAX_CONTROL_BYTES = 64 * 1024;
    static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();
    private static final Pattern DECIMAL = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern HANDLE = Pattern.compile("[0-9a-f]{16}");

    private JsonSupport() {}

    static JsonObject parseObject(String json, String description) {
        return parseObject(json, description, MAX_CONTROL_BYTES);
    }

    static JsonObject parseObject(String json, String description, int maximumBytes) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
            throw new IllegalArgumentException(description + " is missing or exceeds " + maximumBytes + " bytes");
        }
        JsonElement value;
        try {
            value = JsonParser.parseString(json);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid " + description + " JSON", error);
        }
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException(description + " must be a JSON object");
        }
        return value.getAsJsonObject();
    }

    static JsonObject requireControlSuccess(String json, String operation, String state) {
        JsonObject result = controlEnvelope(json, operation);
        if (!requireBoolean(result, "ok")) {
            JsonObject error = requireObject(result, "error");
            throw new IllegalStateException(
                    operation + " failed [" + requireString(error, "code") + "]: " + requireString(error, "message"));
        }
        if (!state.equals(requireString(result, "state"))) {
            throw new IllegalStateException(operation + " returned unexpected state: " + result);
        }
        return result;
    }

    static JsonObject controlEnvelope(String json, String operation) {
        JsonObject result = parseObject(json, operation + " response");
        requireNumber(result, "schemaVersion", 1, 1);
        requireNumber(result, "abiVersion", 1, 1);
        requireBoolean(result, "ok");
        requireString(result, "state");
        return result;
    }

    static JsonObject requireObject(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Missing/object field " + name);
        }
        return value.getAsJsonObject();
    }

    static String requireString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing/string field " + name);
        }
        return value.getAsString();
    }

    static boolean requireBoolean(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Missing/boolean field " + name);
        }
        return value.getAsBoolean();
    }

    static long requireNumber(JsonObject object, String name, long minimum, long maximum) {
        JsonElement value = object.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing/integer field " + name);
        }
        String text = value.getAsString();
        if (!DECIMAL.matcher(text).matches()) {
            throw new IllegalArgumentException("Invalid integer field " + name);
        }
        long number;
        try {
            number = Long.parseLong(text);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Out-of-range integer field " + name, error);
        }
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException("Out-of-range integer field " + name);
        }
        return number;
    }

    /** Like {@link #requireNumber} but accepts negative values, such as a failed BPF stack id. */
    static long requireSignedNumber(JsonObject object, String name, long minimum, long maximum) {
        JsonElement value = object.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing/integer field " + name);
        }
        String text = value.getAsString();
        boolean negative = text.startsWith("-");
        if (!DECIMAL.matcher(negative ? text.substring(1) : text).matches()) {
            throw new IllegalArgumentException("Invalid integer field " + name);
        }
        long number;
        try {
            number = Long.parseLong(text);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Out-of-range integer field " + name, error);
        }
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException("Out-of-range integer field " + name);
        }
        return number;
    }

    static String requireDecimal(JsonObject object, String name) {
        String value = requireString(object, name);
        if (!DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid decimal string field " + name);
        }
        return value;
    }

    static long parseHandle(JsonObject response) {
        String value = requireString(response, "handle");
        if (!HANDLE.matcher(value).matches() || value.equals("0000000000000000")) {
            throw new IllegalArgumentException("Invalid native collector handle");
        }
        return Long.parseUnsignedLong(value, 16);
    }

    static void requireEqual(String field, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(field + " mismatch: expected " + expected + ", got " + actual);
        }
    }

    static String errorCode(Throwable error) {
        String name = error.getClass().getSimpleName();
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
