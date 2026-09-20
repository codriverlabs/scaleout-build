/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import java.time.Instant;
import java.util.List;

/**
 * Response to {@link BuildApi#createBuild}.
 *
 * @param buildId                  server-assigned identifier, sortable by creation time
 * @param state                    always {@link BuildState#PENDING} on creation
 * @param cells                    the resolved matrix, one entry per cell
 * @param uploads                  presigned {@code PUT} targets for digests the server lacks; empty
 *                                 when every input was already staged
 * @param alreadyStaged            digests the server already held, for reporting dedup hits
 * @param appliedResources         sizing actually applied after clamping, which may differ from what
 *                                 was requested
 * @param heartbeatIntervalSeconds how often the client should call {@link BuildApi#heartbeat}
 * @param expiresAt                absolute deadline after which the build is reaped regardless of
 *                                 heartbeats
 */
public record CreateBuildResponse(String buildId, BuildState state, List<CellStatus> cells,
                                  List<UploadTarget> uploads, List<String> alreadyStaged,
                                  RequestedResources appliedResources, int heartbeatIntervalSeconds,
                                  Instant expiresAt) {

    public CreateBuildResponse {
        cells = cells == null ? List.of() : List.copyOf(cells);
        uploads = uploads == null ? List.of() : List.copyOf(uploads);
        alreadyStaged = alreadyStaged == null ? List.of() : List.copyOf(alreadyStaged);
    }
}
