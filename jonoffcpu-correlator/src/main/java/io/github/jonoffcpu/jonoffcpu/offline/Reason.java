// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

import io.github.jonoffcpu.jonoffcpu.offline.ReportProto.RowReason;

/**
 * Why a source row or a JFR sample is invalid. The classified records carry it as {@link RowReason}, the vocabulary
 * operators and the kernel-proof tools match on, which {@code jonoffcpu-report.proto} defines. The columns store the
 * ordinal in one byte.
 */
enum Reason {
    NONE(null),
    INVALID_COOKIE(RowReason.ROW_REASON_INVALID_COOKIE_OR_CAPTURE_IDENTITY),
    NEGATIVE_DURATION(RowReason.ROW_REASON_NEGATIVE_SOURCE_DURATION),
    OUTSIDE_CAPTURE(RowReason.ROW_REASON_SOURCE_INTERVAL_OUTSIDE_CAPTURE),
    POLICY_MISMATCH(RowReason.ROW_REASON_SOURCE_POLICY_OR_TARGET_MISMATCH),
    DURATION_POLICY(RowReason.ROW_REASON_DURATION_POLICY_MISMATCH),
    DUPLICATE_COOKIE(RowReason.ROW_REASON_DUPLICATE_COOKIE),
    INVALID_PAIR(RowReason.ROW_REASON_INVALID_PAIR),
    PAIR_AFTER_AP_STOP(RowReason.ROW_REASON_SAMPLE_FOR_SOURCE_AFTER_AP_STOP),
    THREAD_IDENTITY_MISMATCH(RowReason.ROW_REASON_THREAD_IDENTITY_MISMATCH),
    FAILED_SIGNAL_REQUEST(RowReason.ROW_REASON_SAMPLE_FOR_FAILED_SIGNAL_REQUEST),
    INVALID_HANDLER_DELAY(RowReason.ROW_REASON_INVALID_HANDLER_DELAY),
    HANDLER_DELAY_LIMIT(RowReason.ROW_REASON_HANDLER_DELAY_LIMIT_EXCEEDED);

    private static final Reason[] VALUES = values();

    private final RowReason proto;

    Reason(RowReason proto) {
        this.proto = proto;
    }

    /** The classified records' value, or null for {@link #NONE}. */
    RowReason proto() {
        return proto;
    }

    static Reason of(byte ordinal) {
        return VALUES[ordinal];
    }
}
