// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

/**
 * Names of the files the correlator writes into its output directory. Every file starts with {@code jonoffcpu-}
 * so that it is recognisable in a shared directory or a support bundle; partial mode puts {@code INCOMPLETE-} in
 * front of that so the incomplete set sorts and reads apart from a complete analysis.
 */
final class OutputFiles {
    static final String PREFIX = "jonoffcpu-";
    static final String INCOMPLETE_PREFIX = "INCOMPLETE-" + PREFIX;

    static final String REPORT_SUFFIX = "report.json";
    static final String COLLAPSED_SUFFIX = "offcpu-stacks.collapsed";
    static final String CLASSIFIED_RECORDS_SUFFIX = "classified-records.jsonl";
    static final String MATCHES_SUFFIX = "matches.jsonl";
    static final String PROFILE_SUFFIX = "offcpu-profile.pb";
    static final String SUMMARY_JSON_SUFFIX = "summary.json";
    static final String SUMMARY_MD_SUFFIX = "summary.md";

    static final String REPORT = PREFIX + REPORT_SUFFIX;
    static final String COLLAPSED = PREFIX + COLLAPSED_SUFFIX;
    static final String CLASSIFIED_RECORDS = PREFIX + CLASSIFIED_RECORDS_SUFFIX;
    static final String MATCHES = PREFIX + MATCHES_SUFFIX;
    static final String PROFILE = PREFIX + PROFILE_SUFFIX;
    /** The analysis digest, for people and AI agents; the Markdown is rendered from the JSON. */
    static final String SUMMARY_JSON = PREFIX + SUMMARY_JSON_SUFFIX;

    static final String SUMMARY_MD = PREFIX + SUMMARY_MD_SUFFIX;
    /** Written last; a directory without it is not a complete analysis. */
    static final String COMPLETE = PREFIX + "complete.json";

    static final String INCOMPLETE_REPORT = INCOMPLETE_PREFIX + REPORT_SUFFIX;
    static final String INCOMPLETE_COLLAPSED = INCOMPLETE_PREFIX + COLLAPSED_SUFFIX;
    static final String INCOMPLETE_CLASSIFIED_RECORDS = INCOMPLETE_PREFIX + CLASSIFIED_RECORDS_SUFFIX;
    static final String INCOMPLETE_MATCHES = INCOMPLETE_PREFIX + MATCHES_SUFFIX;
    static final String INCOMPLETE_PAIRS = INCOMPLETE_PREFIX + "pairs.jsonl";
    /** Partial mode's marker, published last; it never promotes the directory to complete. */
    static final String PARTIAL = PREFIX + "partial.json";
    /** Narrowed-window marker, published last; like the partial marker, it never promotes to complete. */
    static final String NARROWED = PREFIX + "narrowed.json";

    /** One output's name under the prefix this run publishes with. */
    /** The collapsed stacks of one switch-out reason, written beside the combined file when a capture mixes reasons. */
    static String collapsedForReason(String prefix, OffCpuReason reason) {
        return prefix + "offcpu-stacks-" + reason.label() + ".collapsed";
    }

    static String name(String prefix, String suffix) {
        return prefix + suffix;
    }

    private OutputFiles() {}
}
