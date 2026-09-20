/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

/**
 * Requested per-cell compute sizing. Advisory: the server clamps to its configured envelope and
 * echoes the applied values back in {@link CreateBuildResponse#appliedResources()}.
 *
 * <p>Clamping rather than rejecting is deliberate — a client asking for more than policy allows
 * should still get a build. Uncapped client-chosen Fargate sizing would be a cost-control hole now
 * that the client no longer pays for its own IAM.
 *
 * @param cpu                 Fargate CPU units as a string, e.g. {@code "4096"}
 * @param memory              Fargate memory in MiB as a string, e.g. {@code "16384"}
 * @param ephemeralStorageGiB ephemeral storage in GiB; {@code 0} means "server default"
 */
public record RequestedResources(String cpu, String memory, int ephemeralStorageGiB) {
}
