/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.backend;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The per-invocation knobs a {@link BuildBackend} needs that are not infrastructure identity.
 *
 * <p>Infrastructure identity (cluster, subnets, roles, log group, agent image, sizing) already has
 * homes in {@code EcsClusterSettings} and {@code AgentContainerSettings}, so this record carries
 * only what is left: the staging bucket, the supervision timings, and the parts of the build request
 * that vary per invocation.
 *
 * <p>Every field here except {@code s3Bucket} survives the migration to the control plane —
 * {@code overallTimeout} and {@code extraNativeImageArgs} travel in the API's {@code buildSpec},
 * while {@code pollInterval} and {@code maxSpotInterruptionsBeforeOnDemand} become server-side
 * policy. See {@code docs/design/control-plane/migration-from-direct-ecs-access.md}.
 *
 * @param s3Bucket                         staging bucket for inputs and produced artifacts
 * @param workDirectory                    resolved local directory to download artifacts into
 * @param pollIntervalSeconds              how often to poll ECS and CloudWatch while supervising
 * @param overallTimeoutMinutes            wall-clock ceiling for one cell's task
 * @param maxSpotInterruptionsBeforeOnDemand Spot reclaims tolerated before preferring on-demand
 * @param timeoutMinutes                   per-build timeout handed to the agent; 0 means unset
 * @param extraNativeImageArgs             appended to every {@code native-image} invocation
 * @param profilePath                      local {@code default.iprof}, required by PGO-optimize
 *                                         kinds and otherwise {@code null}
 */
public record RemoteBuildOptions(String s3Bucket, Path workDirectory, int pollIntervalSeconds,
                                 int overallTimeoutMinutes, int maxSpotInterruptionsBeforeOnDemand,
                                 int timeoutMinutes, List<String> extraNativeImageArgs,
                                 String profilePath) {

    public RemoteBuildOptions {
        Objects.requireNonNull(s3Bucket, "s3Bucket");
        Objects.requireNonNull(workDirectory, "workDirectory");
        extraNativeImageArgs = extraNativeImageArgs == null ? List.of()
                : List.copyOf(extraNativeImageArgs);
    }
}
