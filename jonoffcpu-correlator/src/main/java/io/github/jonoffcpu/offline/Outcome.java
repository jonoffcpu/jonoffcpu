// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.offline;

import io.github.jonoffcpu.offline.ReportProto.RowReason;

/**
 * What happened to a row that is not invalid: it matched, or it did not and there is a reason to
 * report. {@code UNRESOLVED} is an unmatched row with nothing more to say, which classifies as
 * {@code unmatched} on the source side and {@code orphan} on the JFR side.
 */
enum Outcome {
    UNRESOLVED(null),
    MATCHED(null),
    AFTER_AP_STOP(RowReason.ROW_REASON_SOURCE_INTERVAL_AFTER_AP_STOP),
    NOT_IN_SELECTED_JFR(RowReason.ROW_REASON_SAMPLE_NOT_PRESENT_IN_SELECTED_JFR);

    private static final Outcome[] VALUES = values();

    private final RowReason proto;

    Outcome(RowReason proto) {
        this.proto = proto;
    }

    /** The classified records' reason, or null when there is none to report. */
    RowReason proto() {
        return proto;
    }

    static Outcome of(byte ordinal) {
        return VALUES[ordinal];
    }
}
