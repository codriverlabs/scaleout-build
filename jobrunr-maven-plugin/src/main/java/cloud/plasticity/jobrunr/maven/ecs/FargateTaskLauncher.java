/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.ecs;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AssignPublicIp;
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration;
import software.amazon.awssdk.services.ecs.model.CapacityProviderStrategyItem;
import software.amazon.awssdk.services.ecs.model.Failure;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.Task;

/**
 * Launches and stops Fargate Spot tasks running the build agent.
 *
 * <p>Every launch prefers {@code FARGATE_SPOT} with an on-demand {@code FARGATE} fallback
 * expressed as capacity provider {@code base}/{@code weight}, rather than as separate retry logic:
 * ECS itself falls back to on-demand capacity when Spot capacity is unavailable for the
 * higher-weighted strategy item, so the fallback is declared once per launch rather than reimplemented
 * as a "retry with a different launch type" loop.
 */
public final class FargateTaskLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(FargateTaskLauncher.class);

    private static final String CAPACITY_PROVIDER_SPOT = "FARGATE_SPOT";
    private static final String CAPACITY_PROVIDER_ON_DEMAND = "FARGATE";

    private final EcsClient ecsClient;

    public FargateTaskLauncher(EcsClient ecsClient) {
        this.ecsClient = Objects.requireNonNull(ecsClient, "ecsClient");
    }

    /**
     * Runs one task from {@code taskDefinitionArn} against {@code clusterSettings}'s cluster and
     * network configuration.
     *
     * @param preferOnDemand when {@code true}, weights the on-demand {@code FARGATE} capacity
     *                       provider above {@code FARGATE_SPOT} — used for the on-demand fallback
     *                       after a build has already been interrupted by Spot reclamation more than
     *                       the configured number of times
     * @return the launched task's ARN
     * @throws FargateLaunchException if ECS reports a launch failure (e.g. no Spot capacity
     *         available and no fallback configured)
     */
    public String runTask(EcsClusterSettings clusterSettings, String taskDefinitionArn,
                          boolean preferOnDemand) {
        Objects.requireNonNull(clusterSettings, "clusterSettings");
        Objects.requireNonNull(taskDefinitionArn, "taskDefinitionArn");

        RunTaskRequest request = RunTaskRequest.builder()
                .cluster(clusterSettings.clusterArn())
                .taskDefinition(taskDefinitionArn)
                // launchType must be omitted whenever capacityProviderStrategy is set -- ECS
                // rejects the request otherwise. Verified against the RunTask API reference; this
                // was wrong in an earlier draft of this class.
                .capacityProviderStrategy(capacityProviderStrategy(preferOnDemand))
                .count(1)
                .networkConfiguration(NetworkConfiguration.builder()
                        .awsvpcConfiguration(AwsVpcConfiguration.builder()
                                .subnets(clusterSettings.subnetIds())
                                .securityGroups(clusterSettings.securityGroupIds())
                                .assignPublicIp(clusterSettings.assignPublicIp()
                                        ? AssignPublicIp.ENABLED : AssignPublicIp.DISABLED)
                                .build())
                        .build())
                .build();

        RunTaskResponse response = ecsClient.runTask(request);
        if (!response.failures().isEmpty()) {
            String reasons = response.failures().stream()
                    .map(Failure::reason)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("unknown reason");
            throw new FargateLaunchException("RunTask failed: " + reasons);
        }
        if (response.tasks().isEmpty()) {
            throw new FargateLaunchException(
                    "RunTask returned no task and no failure reason; this should not happen");
        }
        Task task = response.tasks().get(0);
        LOG.info("Launched task {} ({})", task.taskArn(),
                preferOnDemand ? "on-demand fallback" : "Spot-preferred");
        return task.taskArn();
    }

    /** Requests the task be stopped, e.g. after the overall build timeout elapses. */
    public void stopTask(EcsClusterSettings clusterSettings, String taskArn, String reason) {
        Objects.requireNonNull(clusterSettings, "clusterSettings");
        Objects.requireNonNull(taskArn, "taskArn");
        LOG.warn("Stopping task {}: {}", taskArn, reason);
        ecsClient.stopTask(b -> b.cluster(clusterSettings.clusterArn()).task(taskArn).reason(reason));
    }

    private static java.util.List<CapacityProviderStrategyItem> capacityProviderStrategy(
            boolean preferOnDemand) {
        if (preferOnDemand) {
            return java.util.List.of(
                    CapacityProviderStrategyItem.builder()
                            .capacityProvider(CAPACITY_PROVIDER_ON_DEMAND).weight(4).base(1).build(),
                    CapacityProviderStrategyItem.builder()
                            .capacityProvider(CAPACITY_PROVIDER_SPOT).weight(1).base(0).build());
        }
        return java.util.List.of(
                CapacityProviderStrategyItem.builder()
                        .capacityProvider(CAPACITY_PROVIDER_SPOT).weight(4).base(1).build(),
                CapacityProviderStrategyItem.builder()
                        .capacityProvider(CAPACITY_PROVIDER_ON_DEMAND).weight(1).base(0).build());
    }
}
