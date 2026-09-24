// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.agent;

import io.github.jonoffcpu.capture.CollectorProto.CollectorError;

/** The native collector answered a call with an error reply, which the manifest records as it was returned. */
final class CollectorException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final transient CollectorError error;

    CollectorException(String operation, CollectorError error) {
        super(operation + " failed [" + error.getCode() + "]: " + error.getMessage());
        this.error = error;
    }

    CollectorError error() {
        return error;
    }
}
