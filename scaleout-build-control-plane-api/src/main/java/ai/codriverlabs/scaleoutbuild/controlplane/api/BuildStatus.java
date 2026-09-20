/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import java.time.Instant;
import java.util.List;

/**
 * Response to {@link BuildApi#getBuild}.
 *
 * <p>Build state lives separately from build logs, which is what keeps this call cheap and the log
 * stream independently resumable.
 *
 * @param buildId   the build
 * @param state     build-level state
 * @param ownerArn  normalized owner principal; returned so a client can confirm which identity the
 *                  server attributed the build to, which is the difference between "no such build"
 *                  and "not yours" when debugging shared-role setups
 * @param cells     per-cell state
 * @param createdAt registration time
 * @param startedAt launch time, or {@code null} if not yet started
 * @param updatedAt last state change
 * @param expiresAt absolute deadline
 */
public record BuildStatus(String buildId, BuildState state, String ownerArn, List<CellStatus> cells,
                          Instant createdAt, Instant startedAt, Instant updatedAt,
                          Instant expiresAt) {

    public BuildStatus {
        cells = cells == null ? List.of() : List.copyOf(cells);
    }
}
