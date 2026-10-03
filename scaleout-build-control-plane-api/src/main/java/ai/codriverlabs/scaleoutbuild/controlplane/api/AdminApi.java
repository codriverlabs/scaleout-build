/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request/response types for the {@code /admin} endpoints.
 *
 * <p>Kept in one file because the admin surface is small: one trigger endpoint and one status
 * endpoint. Splitting them into separate files would add files without adding clarity.
 */
public final class AdminApi {

    private AdminApi() {}

    // ── POST /admin/microvm-images ─────────────────────────────────────────────────────────────

    /**
     * Request body for triggering a MicroVM image build.
     *
     * <p>The code artifact ZIP must already be in S3 before calling this endpoint.
     * The platform downloads it, runs {@code docker build} from the {@code Dockerfile} at the ZIP
     * root, starts the agent, calls {@code /ready}, snapshots the image, then calls {@code /validate}
     * to sample hot memory pages for prefetch. The whole sequence takes approximately 10–15 minutes.
     *
     * @param artifactS3Uri S3 URI of the code artifact ZIP, e.g.
     *                      {@code "s3://scaleout-build-microvm-artifacts-ACCOUNT-REGION/agent.zip"}.
     *                      The ZIP must contain a {@code Dockerfile} at its root.
     */
    public record TriggerImageBuildRequest(String artifactS3Uri) {}

    /**
     * Response body for {@code POST /admin/microvm-images} (202 Accepted) and
     * {@code GET /admin/microvm-images/{identifier}} (200 OK).
     *
     * <p>Image states (as returned by the platform's {@code MicrovmImageState} enum):
     * <ul>
     *   <li>{@code CREATING} — image build in progress</li>
     *   <li>{@code CREATED} — image is ready to serve {@code RunMicrovm} calls</li>
     *   <li>{@code CREATE_FAILED} — image build failed; check build logs</li>
     *   <li>{@code UPDATING} — a new version is being built from an existing image</li>
     *   <li>{@code UPDATED} — update complete; new version is active</li>
     *   <li>{@code UPDATE_FAILED} — update failed; previous version is still active</li>
     * </ul>
     *
     * @param imageName    the fixed image name ({@code scaleout-build-agent})
     * @param imageVersion the platform-assigned version string (e.g. {@code "1.0"}), or
     *                     {@code null} if not yet assigned
     * @param state        the {@code MicrovmImageState} string value (see above)
     * @param imageArn     the full image ARN, available once the image reaches a terminal
     *                     active state; {@code null} while building
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImageBuildResponse(
            String imageName,
            String imageVersion,
            String state,
            String imageArn) {

        /**
         * @return whether the image is in an active, usable state ({@code CREATED} or
         *         {@code UPDATED})
         */
        public boolean isActive() {
            return "CREATED".equalsIgnoreCase(state) || "UPDATED".equalsIgnoreCase(state);
        }

        /**
         * @return whether the image has permanently failed ({@code CREATE_FAILED} or
         *         {@code UPDATE_FAILED})
         */
        public boolean isFailed() {
            return state != null && state.toUpperCase(java.util.Locale.ROOT).endsWith("_FAILED");
        }

        /** @return whether the image is still being built */
        public boolean isBuilding() {
            return "CREATING".equalsIgnoreCase(state) || "UPDATING".equalsIgnoreCase(state);
        }
    }
}
