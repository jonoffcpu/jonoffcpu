// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.agent;

import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto.AsyncProfilerStats;
import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto.SignalDelivery;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** async-profiler's {@code signal-capture-v1} command replies, parsed strictly. */
public final class CaptureProtocol {
    private static final String VERSION = "signal-capture-v1";
    private static final Pattern REASON = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    public record Active(String sessionId, int signal, long epoch, SignalDelivery delivery) {}

    public record Stopped(
            String sessionId,
            int signal,
            long epoch,
            SignalDelivery delivery,
            boolean finalized,
            long stoppedAtMonotonicNanos,
            String reason,
            AsyncProfilerStats counters) {}

    /** The protocol's name of a delivery policy, which the agent configuration also uses. */
    public static String deliveryName(SignalDelivery delivery) {
        return switch (delivery) {
            case SIGNAL_DELIVERY_QUEUED -> "queued";
            case SIGNAL_DELIVERY_COALESCING -> "coalescing";
            default -> throw new IllegalArgumentException("Not a signal delivery policy: " + delivery);
        };
    }

    public static SignalDelivery parseDelivery(String value) {
        if ("queued".equals(value)) return SignalDelivery.SIGNAL_DELIVERY_QUEUED;
        if ("coalescing".equals(value)) return SignalDelivery.SIGNAL_DELIVERY_COALESCING;
        throw new IllegalArgumentException("Invalid signal delivery policy: " + value);
    }

    private CaptureProtocol() {}

    static boolean isInactive(String response) {
        return oneLine(response).equals(VERSION + " inactive");
    }

    static Active parseActive(String response) {
        Parsed parsed = parse(response, null);
        String id = required(parsed.values, "id");
        int signal = decimalInt(required(parsed.values, "signal"), "signal", 1, 64);
        long epoch = decimalLong(required(parsed.values, "epoch"), "epoch", 1, 0xffffffffL);
        return new Active(id, signal, epoch, parseDelivery(required(parsed.values, "delivery")));
    }

    public static Stopped parseStopped(String response) {
        Parsed parsed = parse(response, "stopped");
        String id = required(parsed.values, "id");
        int signal = decimalInt(required(parsed.values, "signal"), "signal", 1, 64);
        long epoch = decimalLong(required(parsed.values, "epoch"), "epoch", 1, 0xffffffffL);
        String finalizedText = required(parsed.values, "finalized");
        if (!finalizedText.equals("true") && !finalizedText.equals("false")) {
            throw new IllegalArgumentException("Invalid AP finalized field");
        }
        long stoppedAt = decimalUnsigned(required(parsed.values, "stopped-at"), "stopped-at");
        String reason = required(parsed.values, "reason");
        if (!REASON.matcher(reason).matches()) throw new IllegalArgumentException("Invalid AP stop reason");
        AsyncProfilerStats counters = AsyncProfilerStats.newBuilder()
                .setAdmittedSignals(counter(parsed, "admitted"))
                .setInvalidSignalCode(counter(parsed, "invalid-code"))
                .setZeroCookie(counter(parsed, "zero-cookie"))
                .setZeroSequence(counter(parsed, "zero-sequence"))
                .setStaleEpoch(counter(parsed, "stale-epoch"))
                .setAcceptedCookies(counter(parsed, "accepted"))
                .setCaptureFailures(counter(parsed, "capture-failures"))
                .setSubmittedSamples(counter(parsed, "submitted"))
                .build();
        return new Stopped(
                id,
                signal,
                epoch,
                parseDelivery(required(parsed.values, "delivery")),
                Boolean.parseBoolean(finalizedText),
                stoppedAt,
                reason,
                counters);
    }

    private static long counter(Parsed parsed, String key) {
        return decimalUnsigned(required(parsed.values, key), key);
    }

    private static Parsed parse(String response, String expectedWord) {
        String line = oneLine(response);
        String[] tokens = line.split(" ");
        int index = 0;
        if (tokens.length == 0 || !tokens[index++].equals(VERSION)) {
            throw new IllegalArgumentException("Unsupported AP capture protocol response: " + line);
        }
        if (expectedWord != null && (index >= tokens.length || !tokens[index++].equals(expectedWord))) {
            throw new IllegalArgumentException("Unexpected AP capture response: " + line);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (; index < tokens.length; index++) {
            int equals = tokens[index].indexOf('=');
            if (equals <= 0 || equals == tokens[index].length() - 1) {
                throw new IllegalArgumentException("Malformed AP capture token: " + tokens[index]);
            }
            String key = tokens[index].substring(0, equals);
            if (values.putIfAbsent(key, tokens[index].substring(equals + 1)) != null) {
                throw new IllegalArgumentException("Duplicate AP capture field: " + key);
            }
        }
        return new Parsed(values);
    }

    private static String oneLine(String response) {
        if (response == null) {
            throw new IllegalArgumentException("Missing AP capture response");
        }
        String line = response.endsWith("\n") ? response.substring(0, response.length() - 1) : response;
        if (line.isEmpty() || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0 || line.contains("  ")) {
            throw new IllegalArgumentException("AP capture response must be exactly one protocol line");
        }
        return line;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing AP capture field: " + key);
        }
        return value;
    }

    private static int decimalInt(String value, String name, int minimum, int maximum) {
        return (int) decimalLong(value, name, minimum, maximum);
    }

    private static long decimalLong(String value, String name, long minimum, long maximum) {
        if (value.isEmpty()
                || (value.length() > 1 && value.charAt(0) == '0')
                || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Invalid decimal AP capture field: " + name);
        }
        long result;
        try {
            result = Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Out-of-range AP capture field: " + name, error);
        }
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException("Out-of-range AP capture field: " + name);
        }
        return result;
    }

    /** An unsigned 64-bit decimal, returned in a long's bits. */
    private static long decimalUnsigned(String value, String name) {
        if (value.isEmpty()
                || (value.length() > 1 && value.charAt(0) == '0')
                || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Invalid decimal AP capture field: " + name);
        }
        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Out-of-range AP capture field: " + name, error);
        }
    }

    private record Parsed(Map<String, String> values) {}
}
