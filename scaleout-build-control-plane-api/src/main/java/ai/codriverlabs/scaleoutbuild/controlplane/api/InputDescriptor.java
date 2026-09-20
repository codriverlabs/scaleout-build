/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

/**
 * One staged input, identified by content rather than by name.
 *
 * <p>The digest is what makes upload negotiation possible: the server answers
 * {@link BuildApi#createBuild} with presigned URLs only for digests it does not already hold. In
 * practice dependency jars are always already present and only the project's own jar needs
 * uploading — measured over three end-to-end runs, {@code commons-lang3} had exactly one
 * content-addressed entry while the project jar had one per run, because Maven's jar output is not
 * byte-reproducible.
 *
 * @param path       path relative to the cell's staging root, e.g. {@code lib/commons-lang3.jar}
 * @param sha256     lowercase hex SHA-256 of the file's bytes
 * @param sizeBytes  file size, for the server to sanity-check and to report dedup savings
 */
public record InputDescriptor(String path, String sha256, long sizeBytes) {
}
