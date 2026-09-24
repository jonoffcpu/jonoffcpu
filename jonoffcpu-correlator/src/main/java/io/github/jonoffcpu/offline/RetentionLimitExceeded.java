// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.offline;

import java.io.IOException;

/**
 * The retention budget was reached. The message is unchanged, so callers that match on text keep
 * working; the type exists so the degradation ladder can tell this apart from a semantic failure
 * and retry, which is the whole difference between a limit and an error.
 */
final class RetentionLimitExceeded extends IOException {
    private static final long serialVersionUID = 1L;

    private final long retainedBytes;
    private final long limitBytes;
    private final Long lastObservationEndNanos;

    RetentionLimitExceeded(long retainedBytes, long limitBytes, Long lastObservationEndNanos) {
        super("Decoded input budget exceeded");
        this.retainedBytes = retainedBytes;
        this.limitBytes = limitBytes;
        this.lastObservationEndNanos = lastObservationEndNanos;
    }

    long retainedBytes() {
        return retainedBytes;
    }

    long limitBytes() {
        return limitBytes;
    }

    /** The end of the last interval decoded before the limit, which is where a narrowed window cuts. */
    Long lastObservationEndNanos() {
        return lastObservationEndNanos;
    }

    RetentionLimitExceeded withLastObservationEnd(long endNanos) {
        return new RetentionLimitExceeded(retainedBytes, limitBytes, endNanos);
    }
}
