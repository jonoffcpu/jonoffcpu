// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.capture;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

/**
 * The one way jonoffcpu shows a message as JSON: protobuf's proto3 JSON mapping, with every field that has no
 * presence printed even at its default, so a zero counter or an empty list is visible, and a field with presence
 * printed only when it is set. Parsing is strict: an unknown field is an error.
 */
public final class ProtoJson {
    private static final JsonFormat.Printer PRETTY = JsonFormat.printer().alwaysPrintFieldsWithNoPresence();
    private static final JsonFormat.Printer COMPACT = PRETTY.omittingInsignificantWhitespace();
    private static final JsonFormat.Parser PARSER = JsonFormat.parser();

    private ProtoJson() {}

    /** Indented JSON, for a file or a terminal. */
    public static String pretty(Message message) {
        try {
            return PRETTY.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Message cannot be printed as JSON", e);
        }
    }

    /** One line of JSON, for JSON Lines output. */
    public static String line(Message message) {
        try {
            return COMPACT.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Message cannot be printed as JSON", e);
        }
    }

    /** Merges JSON into a builder, rejecting unknown fields. */
    public static <B extends Message.Builder> B parse(String json, B builder) throws InvalidProtocolBufferException {
        PARSER.merge(json, builder);
        return builder;
    }
}
