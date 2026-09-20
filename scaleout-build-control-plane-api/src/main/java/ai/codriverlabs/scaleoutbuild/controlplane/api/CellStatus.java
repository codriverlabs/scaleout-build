/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import java.util.List;

/**
 * State of one matrix cell.
 *
 * @param cell              cell identifier, {@code BUILDKIND/ARCHITECTURE}, e.g.
 *                          {@code NATIVE/ARM64}. This is the same string used as the {@code cell}
 *                          query parameter on {@link BuildApi#streamLogs} and as the key in
 *                          {@link LogEvent#nextSince()}
 * @param state             lifecycle state
 * @param taskArn           backend task identifier, or {@code null} before launch. Exposed as an
 *                          opaque troubleshooting handle — clients never act on it, but hiding it
 *                          would make incidents harder to diagnose for no benefit inside one account
 * @param exitCode          container exit code once terminal, else {@code null}
 * @param failureReason     human-readable failure detail when {@link CellState#FAILED}
 * @param spotInterruptions how many times capacity was reclaimed and the cell relaunched
 * @param artifacts         produced artifacts, populated once {@link CellState#SUCCEEDED}
 */
public record CellStatus(String cell, CellState state, String taskArn, Integer exitCode,
                         String failureReason, int spotInterruptions,
                         List<ArtifactDescriptor> artifacts) {

    public CellStatus {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}
