/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import java.util.List;

/**
 * Response to {@link BuildApi#listArtifacts}: presigned download targets grouped by cell.
 *
 * @param buildId the build
 * @param cells   per-cell download targets; a cell that has not succeeded contributes an empty list
 */
public record ArtifactListResponse(String buildId, List<CellArtifacts> cells) {

    public ArtifactListResponse {
        cells = cells == null ? List.of() : List.copyOf(cells);
    }

    /**
     * @param cell      cell identifier, {@code BUILDKIND/ARCHITECTURE}
     * @param downloads presigned {@code GET} targets, one per artifact
     */
    public record CellArtifacts(String cell, List<UploadTarget> downloads) {

        public CellArtifacts {
            downloads = downloads == null ? List.of() : List.copyOf(downloads);
        }
    }
}
