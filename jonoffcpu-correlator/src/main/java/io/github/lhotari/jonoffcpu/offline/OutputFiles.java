// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

/**
 * Names of the files the correlator writes into its output directory. Every file starts with {@code jonoffcpu-}
 * so that it is recognisable in a shared directory or a support bundle; partial mode puts {@code INCOMPLETE-} in
 * front of that so the incomplete set sorts and reads apart from a complete analysis.
 */
final class OutputFiles {
    static final String PREFIX = "jonoffcpu-";
    static final String INCOMPLETE_PREFIX = "INCOMPLETE-" + PREFIX;

    static final String REPORT = PREFIX + "report.json";
    static final String COLLAPSED = PREFIX + "offcpu-stacks.collapsed";
    static final String SYNTHETIC_JFR = PREFIX + "offcpu-synthetic.jfr";
    static final String CLASSIFIED_RECORDS = PREFIX + "classified-records.jsonl";
    static final String MATCHES = PREFIX + "matches.jsonl";
    /** Written last; a directory without it is not a complete analysis. */
    static final String COMPLETE = PREFIX + "complete.json";

    static final String INCOMPLETE_REPORT = INCOMPLETE_PREFIX + "report.json";
    static final String INCOMPLETE_COLLAPSED = INCOMPLETE_PREFIX + "offcpu-stacks.collapsed";
    static final String INCOMPLETE_CLASSIFIED_RECORDS = INCOMPLETE_PREFIX + "classified-records.jsonl";
    static final String INCOMPLETE_PAIRS = INCOMPLETE_PREFIX + "pairs.jsonl";
    /** Partial mode's marker, published last; it never promotes the directory to complete. */
    static final String PARTIAL = PREFIX + "partial.json";

    private OutputFiles() {}
}
