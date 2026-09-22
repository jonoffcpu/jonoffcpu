// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

/**
 * What happened to a row that is not invalid: it matched, or it did not and there is a reason to
 * report. {@code UNRESOLVED} is an unmatched row with nothing more to say, which classifies as
 * {@code unmatched} on the source side and {@code orphan} on the JFR side.
 */
enum Outcome {
    UNRESOLVED(null),
    MATCHED(null),
    AFTER_AP_STOP("source-interval-after-ap-stop"),
    NOT_IN_SELECTED_JFR("sample-not-present-in-selected-jfr");

    private static final Outcome[] VALUES = values();

    private final String text;

    Outcome(String text) {
        this.text = text;
    }

    String text() {
        return text;
    }

    static Outcome of(byte ordinal) {
        return VALUES[ordinal];
    }
}
