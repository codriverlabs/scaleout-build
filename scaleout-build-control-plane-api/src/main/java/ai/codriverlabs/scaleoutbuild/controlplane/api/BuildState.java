/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

/**
 * Build-level lifecycle state.
 *
 * <p>{@link #PENDING} → {@link #STAGED} → {@link #RUNNING} → one of {@link #SUCCEEDED},
 * {@link #FAILED}, {@link #CANCELLED}, {@link #EXPIRED}.
 */
public enum BuildState {

    /** Registered; inputs may still be uploading. Nothing has been launched. */
    PENDING,

    /** Every manifest digest is present in staging; ready for {@link BuildApi#startBuild}. */
    STAGED,

    /** At least one cell's task is provisioning or running. */
    RUNNING,

    /** Every cell succeeded and produced at least one artifact. */
    SUCCEEDED,

    /** At least one cell failed, timed out, or produced no artifact. */
    FAILED,

    /** Cancelled by the client via {@link BuildApi#cancelBuild}. */
    CANCELLED,

    /**
     * Reaped server-side: either the client stopped heartbeating, or the build passed its absolute
     * deadline. Distinct from {@link #CANCELLED} on purpose — a rising {@code EXPIRED} count means
     * clients are dying without cancelling, which is a signal worth alarming on rather than noise.
     */
    EXPIRED;

    /** @return whether no further state change is possible */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
