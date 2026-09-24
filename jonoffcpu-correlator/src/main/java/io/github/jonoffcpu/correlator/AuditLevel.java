// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

/**
 * How much per-row audit output to write. These are the most expensive outputs and the ones least
 * often read in full — a fifteen-thousand-row run once wrote a 71 MB classified-records file — so
 * they are selectable, and they are what the degradation ladder drops first.
 */
enum AuditLevel {
    /** Classified records and matches: everything the retained correlator always wrote. */
    FULL,
    /** Matches only; the per-row classified records are omitted and the omission is reported. */
    MATCHES,
    /** No per-row audit output at all. */
    NONE;

    static AuditLevel parse(String value) {
        return switch (value) {
            case "full" -> FULL;
            case "matches" -> MATCHES;
            case "none" -> NONE;
            default -> throw new IllegalArgumentException("Invalid audit level: " + value);
        };
    }

    String text() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
