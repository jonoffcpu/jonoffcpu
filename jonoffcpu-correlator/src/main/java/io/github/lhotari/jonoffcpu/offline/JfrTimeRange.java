// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;

/** Resolves absolute and recording-relative time expressions against JFR chunk timestamps. */
final class JfrTimeRange {
    private static final int CHUNK_HEADER_BYTES = 68;
    private static final byte[] MAGIC = {'F', 'L', 'R', 0};
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    record Range(Instant from, Instant to) {
        Range {
            if (from == null || to == null || !from.isBefore(to)) {
                throw new IllegalArgumentException("JFR time range must be nonempty");
            }
        }
    }

    private record RecordingBounds(Instant start, Instant end) {
        Instant exclusiveEnd() {
            return end.equals(Instant.MAX) ? end : end.plusNanos(1);
        }
    }

    private JfrTimeRange() {}

    /**
     * Resolves ISO-8601 instants, epoch milliseconds, ISO-8601 durations, or offsets such as {@code
     * 250ms}, {@code 5s}, {@code 2m}, and {@code 1h}. Offsets are measured from the JFR recording
     * start. A missing boundary selects the corresponding recording boundary.
     */
    static Range resolve(Path recording, String from, String to) throws IOException {
        RecordingBounds bounds = recordingBounds(recording);
        Instant resolvedFrom = from == null ? bounds.start() : parse(from, bounds.start());
        Instant resolvedTo = to == null ? bounds.exclusiveEnd() : parse(to, bounds.start());
        return new Range(resolvedFrom, resolvedTo);
    }

    static Instant parse(String value, Instant recordingStart) {
        if (value.matches("[0-9]+(?:ms|s|m|h)")) {
            int unit = 0;
            while (Character.isDigit(value.charAt(unit))) {
                unit++;
            }
            long amount = Long.parseLong(value.substring(0, unit));
            Duration offset =
                    switch (value.substring(unit)) {
                        case "ms" -> Duration.ofMillis(amount);
                        case "s" -> Duration.ofSeconds(amount);
                        case "m" -> Duration.ofMinutes(amount);
                        case "h" -> Duration.ofHours(amount);
                        default -> throw new IllegalArgumentException("Unsupported relative JFR time: " + value);
                    };
            return recordingStart.plus(offset);
        }
        if (value.startsWith("P")) {
            return recordingStart.plus(Duration.parse(value));
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException notEpochMillis) {
            return Instant.parse(value);
        }
    }

    private static RecordingBounds recordingBounds(Path recording) throws IOException {
        long firstStart = Long.MAX_VALUE;
        long lastEnd = Long.MIN_VALUE;
        try (FileChannel channel = FileChannel.open(recording, StandardOpenOption.READ)) {
            long size = channel.size();
            long position = 0;
            while (position < size) {
                ByteBuffer header = ByteBuffer.allocate(CHUNK_HEADER_BYTES);
                readFully(channel, header, position);
                header.flip();
                for (byte expected : MAGIC) {
                    if (header.get() != expected) {
                        throw new IOException("Invalid JFR chunk at offset " + position);
                    }
                }
                header.getShort();
                header.getShort();
                long chunkBytes = header.getLong();
                header.getLong();
                header.getLong();
                long start = header.getLong();
                long duration = header.getLong();
                if (chunkBytes < CHUNK_HEADER_BYTES || chunkBytes > size - position || duration < 0) {
                    throw new IOException("Invalid JFR chunk header at offset " + position);
                }
                long end;
                try {
                    end = Math.addExact(start, duration);
                } catch (ArithmeticException error) {
                    throw new IOException("JFR chunk timestamp overflow at offset " + position, error);
                }
                firstStart = Math.min(firstStart, start);
                lastEnd = Math.max(lastEnd, end);
                position += chunkBytes;
            }
        }
        if (firstStart == Long.MAX_VALUE) {
            throw new IOException("Cannot inspect an empty JFR recording");
        }
        return new RecordingBounds(instant(firstStart), instant(lastEnd));
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long position) throws IOException {
        while (target.hasRemaining()) {
            int count = channel.read(target, position + target.position());
            if (count < 0) {
                throw new IOException("Truncated JFR chunk header at offset " + position);
            }
        }
    }

    private static Instant instant(long epochNanos) {
        return Instant.ofEpochSecond(
                Math.floorDiv(epochNanos, NANOS_PER_SECOND), Math.floorMod(epochNanos, NANOS_PER_SECOND));
    }
}
