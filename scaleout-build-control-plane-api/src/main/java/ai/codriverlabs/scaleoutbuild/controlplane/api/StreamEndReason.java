/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

/** Why a {@link BuildApi#streamLogs} stream ended, which determines whether to reconnect. */
public enum StreamEndReason {

    /** Every cell reached a terminal state. Do not reconnect. */
    BUILD_TERMINAL,

    /**
     * The streaming function is approaching its 15-minute ceiling. Reconnect with
     * {@code since} from {@link LogEvent#nextSince()}. This is expected, not an error — builds
     * routinely outlive one Lambda invocation.
     */
    LAMBDA_TIMEOUT,

    /** Approaching the streamed-response size ceiling. Reconnect the same way. */
    PAYLOAD_LIMIT;

    /** @return whether the client should reconnect to continue receiving output */
    public boolean shouldReconnect() {
        return this != BUILD_TERMINAL;
    }
}
