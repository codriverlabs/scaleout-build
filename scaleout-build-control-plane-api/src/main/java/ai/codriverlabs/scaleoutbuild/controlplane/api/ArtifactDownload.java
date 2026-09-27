/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import java.time.Instant;

/**
 * One downloadable artifact: where to get it, and what to call it.
 *
 * <p>Separate from {@link UploadTarget} because the two directions need different information. An upload is
 * keyed by content digest, so the digest is all the client needs. A download needs the artifact's
 * <em>name</em>, and {@code UploadTarget} has nowhere to put it.
 *
 * <p>That gap was a real bug rather than an inelegance. With downloads typed as {@code UploadTarget}, the
 * client had no filename to write to and fell back to the configured {@code imageName} for every download
 * in the list — which meant a cell producing several artifacts overwrote them into a single file, and a
 * build with no {@code imageName} (any framework-generated argfile names its own output) threw a
 * {@code NullPointerException} instead.
 *
 * @param path      artifact file name, relative to the cell's output prefix
 * @param url       presigned GET URL
 * @param expiresAt when {@code url} stops working
 */
public record ArtifactDownload(String path, String url, Instant expiresAt) {

    public ArtifactDownload {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required: it names the file to write");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
    }
}
