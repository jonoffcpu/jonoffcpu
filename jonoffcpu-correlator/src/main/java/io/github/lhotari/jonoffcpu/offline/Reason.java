// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

/**
 * Why a source row or a JFR sample is invalid. These strings appear verbatim in {@code
 * jonoffcpu-classified-records.jsonl} and are the vocabulary operators and the kernel-proof tools
 * match on, so this enum is their single definition. The columns store the ordinal in one byte.
 */
enum Reason {
    NONE(null),
    INVALID_COOKIE("invalid-cookie-or-capture-identity"),
    NEGATIVE_DURATION("negative-source-duration"),
    OUTSIDE_CAPTURE("source-interval-outside-capture"),
    POLICY_MISMATCH("source-policy-or-target-mismatch"),
    DURATION_POLICY("duration-policy-mismatch"),
    DUPLICATE_COOKIE("duplicate-cookie"),
    INVALID_PAIR("invalid-pair"),
    PAIR_AFTER_AP_STOP("sample-for-source-after-ap-stop"),
    THREAD_IDENTITY_MISMATCH("thread-identity-mismatch"),
    FAILED_SIGNAL_REQUEST("sample-for-failed-signal-request"),
    INVALID_HANDLER_DELAY("invalid-handler-delay"),
    HANDLER_DELAY_LIMIT("handler-delay-limit-exceeded");

    private static final Reason[] VALUES = values();

    private final String text;

    Reason(String text) {
        this.text = text;
    }

    String text() {
        return text;
    }

    static Reason of(byte ordinal) {
        return VALUES[ordinal];
    }
}
