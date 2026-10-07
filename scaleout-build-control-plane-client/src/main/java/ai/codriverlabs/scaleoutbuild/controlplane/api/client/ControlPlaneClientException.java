/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api.client;

/**
 * Thrown by {@link ControlPlaneClient} for any failure calling the control plane: a non-2xx HTTP
 * response, a transport-level failure (connection refused, timeout), or an interrupted request.
 *
 * <p>Deliberately not {@link java.io.IOException}: the Maven plugin's prior client overloaded
 * {@code IOException} to mean both "the socket actually broke" and "the server said no," which
 * meant a caller catching it could not distinguish a 403 from a dropped connection without parsing
 * the message string. This type carries {@link #statusCode()} instead, exactly matching the shape
 * {@code scaleout-build-cli}'s prior {@code CliException} already had.
 *
 * <p>Checked, not unchecked, matching how both of this module's predecessors were actually used:
 * every existing call site (the Maven plugin's {@code BuildMojo}, the CLI's picocli command
 * classes) already declares and branches on this failure explicitly rather than relying on an
 * undeclared exception propagating past a boundary nobody is watching. See
 * {@code docs/design/control-plane/control-plane-client-consolidation.md}'s "Resolved" section for
 * the full reasoning — making this unchecked would still compile at every current call site, but
 * would stop the compiler forcing any new call site to decide how to handle a control-plane
 * failure, which is the exact kind of silent fallback this codebase's own convention (see
 * {@code CONTRIBUTING.md}: "fail loudly rather than fall through") exists to prevent.
 */
public final class ControlPlaneClientException extends Exception {

    private final int statusCode;

    public ControlPlaneClientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ControlPlaneClientException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** The HTTP status code, or {@code 0} for failures that never got a response (network, timeout,
     * interruption). */
    public int statusCode() {
        return statusCode;
    }
}
