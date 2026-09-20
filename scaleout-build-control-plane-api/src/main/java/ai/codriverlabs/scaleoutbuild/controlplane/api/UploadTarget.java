/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import java.time.Instant;

/**
 * A presigned S3 target the client uses directly, bypassing this API.
 *
 * <p>Presigning is what keeps developers free of S3 permissions: the URL carries the service's
 * authority, scoped to one key and one expiry. Clients must not cache these beyond
 * {@link #expiresAt} — re-request instead.
 *
 * @param sha256    digest this target is for (upload) or the artifact's digest (download)
 * @param method    HTTP method to use, {@code PUT} for uploads and {@code GET} for downloads
 * @param url       the presigned URL
 * @param expiresAt when the URL stops working
 */
public record UploadTarget(String sha256, String method, String url, Instant expiresAt) {
}
