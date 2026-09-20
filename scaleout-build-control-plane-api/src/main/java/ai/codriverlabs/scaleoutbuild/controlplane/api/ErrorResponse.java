/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import java.util.List;

/**
 * Error envelope used by every non-2xx response, so clients have one shape to parse.
 *
 * @param error        stable machine-readable code, e.g. {@code BuildNotFound},
 *                     {@code InputsMissing}, {@code InvalidState}, {@code ConcurrencyLimitReached}.
 *                     Clients branch on this, never on {@link #message()}
 * @param message      human-readable detail, safe to print
 * @param requestId    server request id, for correlating with service logs in a support request
 * @param missingItems for {@code InputsMissing}, the digests still absent from staging; empty
 *                     otherwise
 */
public record ErrorResponse(String error, String message, String requestId,
                            List<String> missingItems) {

    public ErrorResponse {
        missingItems = missingItems == null ? List.of() : List.copyOf(missingItems);
    }

    public static ErrorResponse of(String error, String message, String requestId) {
        return new ErrorResponse(error, message, requestId, List.of());
    }
}
