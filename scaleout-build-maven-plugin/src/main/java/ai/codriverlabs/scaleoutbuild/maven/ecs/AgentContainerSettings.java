/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.ecs;

import java.util.Objects;

/**
 * Per-architecture container sizing and image reference for the agent task definition.
 *
 * @param agentImageUri full image URI, e.g. the unmodified Mandrel builder image, optionally
 *                      through an ECR pull-through cache
 * @param cpu           Fargate task-level CPU units, e.g. {@code "4096"} for 4 vCPU
 * @param memory        Fargate task-level memory in MiB, e.g. {@code "16384"} for 16 GiB
 * @param ephemeralStorageGiB ephemeral storage in GiB (20-200 per Fargate limits), or 0 to omit
 *                            and use the platform default
 */
public record AgentContainerSettings(
        String agentImageUri,
        String cpu,
        String memory,
        int ephemeralStorageGiB) {

    public AgentContainerSettings(String agentImageUri, String cpu, String memory,
                                  int ephemeralStorageGiB) {
        this.agentImageUri = requireNonBlank(agentImageUri, "agentImageUri");
        this.cpu = requireNonBlank(cpu, "cpu");
        this.memory = requireNonBlank(memory, "memory");
        if (ephemeralStorageGiB != 0 && (ephemeralStorageGiB < 20 || ephemeralStorageGiB > 200)) {
            throw new IllegalArgumentException(
                    "ephemeralStorageGiB must be 0 (omit) or between 20 and 200, was "
                            + ephemeralStorageGiB);
        }
        this.ephemeralStorageGiB = ephemeralStorageGiB;
    }

    public static AgentContainerSettings of(String agentImageUri, String cpu, String memory) {
        return new AgentContainerSettings(agentImageUri, cpu, memory, 0);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return Objects.requireNonNull(value);
    }
}
