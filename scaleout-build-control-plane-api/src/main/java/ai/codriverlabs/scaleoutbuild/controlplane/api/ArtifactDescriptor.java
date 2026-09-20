/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

/**
 * One artifact a cell produced.
 *
 * <p>{@code sha256} is reported but is not a reproducibility guarantee: two builds of identical
 * sources produce different digests today, because Maven's own jar output embeds timestamps and so
 * the {@code native-image} input differs. Treat it as an integrity check for a single download, not
 * as a build fingerprint.
 *
 * @param path      artifact name relative to the cell's output directory
 * @param sha256    lowercase hex SHA-256 of the produced bytes
 * @param sizeBytes size in bytes
 */
public record ArtifactDescriptor(String path, String sha256, long sizeBytes) {
}
