/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

/** Per-cell lifecycle state. */
public enum CellState {

    /** Not yet launched. */
    PENDING,

    /** Task launched; container not yet running. */
    PROVISIONING,

    /** Container running. */
    RUNNING,

    /** Exited zero and produced at least one artifact. */
    SUCCEEDED,

    /** Exited non-zero, timed out, or produced no artifact. */
    FAILED,

    /** Stopped before completing, by client cancel or server reap. */
    CANCELLED;

    /** @return whether no further state change is possible */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
