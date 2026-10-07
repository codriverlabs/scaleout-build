/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import java.util.List;
import java.util.Objects;

/**
 * Everything a {@link CellLauncher} needs to launch one cell, backend-agnostic.
 *
 * <p>Carries the same values {@code BuildService.start()} previously assembled inline for the
 * Fargate-only path (cluster settings, container settings, build identity, agent environment) —
 * extracted into one record so both {@link FargateCellLauncher} and the MicroVM launcher receive
 * an identical shape rather than one of them reaching for ECS-specific types the other has no use
 * for.
 *
 * @param clusterSettings      Fargate/ECS cluster and network configuration; read by
 *                             {@link FargateCellLauncher} only, carried through for the MicroVM
 *                             launcher only insofar as it needs {@code region()}/{@code logGroupName()}
 * @param containerSettings    agent image URI and resource sizing, shared by both backends
 * @param buildId              correlates staging paths and log output across cells
 * @param buildKind            the matrix cell's build kind
 * @param architecture         the matrix cell's architecture — {@code ARM64} is the only value a
 *                             MicroVM launcher ever receives; a dispatcher must not route
 *                             {@code X86_64} to it
 * @param stagingRelativePath  the cell's staging prefix, as produced by
 *                             {@code StagingLayout#stagingPath}
 * @param agentEnvironment     the variables the agent needs, as a plain map — see
 *                             {@code AgentEnvironment.Builder#toMap()} for why this is a map
 *                             rather than the ECS-specific {@code List<KeyValuePair>}
 */
public record CellLaunchRequest(
        EcsClusterSettings clusterSettings,
        AgentContainerSettings containerSettings,
        String buildId,
        BuildKind buildKind,
        Architecture architecture,
        String stagingRelativePath,
        java.util.Map<String, String> agentEnvironment) {

    public CellLaunchRequest {
        Objects.requireNonNull(clusterSettings, "clusterSettings");
        Objects.requireNonNull(containerSettings, "containerSettings");
        Objects.requireNonNull(buildId, "buildId");
        Objects.requireNonNull(buildKind, "buildKind");
        Objects.requireNonNull(architecture, "architecture");
        Objects.requireNonNull(stagingRelativePath, "stagingRelativePath");
        agentEnvironment = agentEnvironment == null ? java.util.Map.of()
                : java.util.Map.copyOf(agentEnvironment);
    }

    /** The {@code BUILDKIND/ARCHITECTURE} cell identifier, matching {@code BuildRecord.CellRecord#getCell()}. */
    public String cellId() {
        return buildKind + "/" + architecture;
    }

    /** {@code agentEnvironment} as ECS {@code KeyValuePair}s, for {@link FargateCellLauncher}. */
    List<software.amazon.awssdk.services.ecs.model.KeyValuePair> agentEnvironmentAsKeyValuePairs() {
        return agentEnvironment.entrySet().stream()
                .map(e -> software.amazon.awssdk.services.ecs.model.KeyValuePair.builder()
                        .name(e.getKey()).value(e.getValue()).build())
                .toList();
    }
}
