/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.ecs;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AssignPublicIp;
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration;
import software.amazon.awssdk.services.ecs.model.CapacityProviderStrategyItem;
import software.amazon.awssdk.services.ecs.model.Failure;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskOverride;

/**
 * Launches and stops Fargate Spot tasks directly, without any orchestration layer above ECS.
 *
 * <p>This is the "pure ECS" alternative to {@code docs/DESIGN.md}'s Step Functions design: for a
 * small, fixed matrix (e.g. exactly x86_64 + arm64), calling {@code RunTask} directly from the
 * plugin and supervising each task with {@link FargateTaskSupervisor} is simpler than deploying and
 * versioning a state machine. The tradeoff, and when Step Functions is worth it again, is documented
 * in {@code docs/PURE_ECS_ALTERNATIVE.md}.
 *
 * <p>Every launch prefers {@code FARGATE_SPOT} with an on-demand {@code FARGATE} fallback expressed
 * as capacity provider {@code base}/{@code weight}, rather than as separate retry logic: ECS itself
 * falls back to on-demand capacity when Spot capacity is unavailable for the higher-weighted
 * strategy item.
 */
public final class FargateTaskLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(FargateTaskLauncher.class);

    private static final String CAPACITY_PROVIDER_SPOT = "FARGATE_SPOT";
    private static final String CAPACITY_PROVIDER_ON_DEMAND = "FARGATE";
    private static final String CONTAINER_NAME = "jobrunr-build-agent";

    private final EcsClient ecsClient;

    public FargateTaskLauncher(EcsClient ecsClient) {
        this.ecsClient = Objects.requireNonNull(ecsClient, "ecsClient");
    }

    /**
     * Runs one task from {@code taskDefinitionArn} against {@code clusterSettings}'s cluster and
     * network configuration, with {@code environment} applied as a container override — this is
     * how the agent learns which build kind/architecture/staging path it was launched for, since
     * the task definition itself carries no per-build configuration (see
     * {@link TaskDefinitionRegistrar}).
     *
     * @param preferOnDemand when {@code true}, weights the on-demand {@code FARGATE} capacity
     *                       provider above {@code FARGATE_SPOT} — used for the on-demand fallback
     *                       after a build has already been interrupted by Spot reclamation more
     *                       than the configured number of times
     * @return the launched task's ARN
     * @throws FargateLaunchException if ECS reports a launch failure (e.g. no Spot capacity
     *         available and no fallback configured)
     */
    public String runTask(EcsClusterSettings clusterSettings, String taskDefinitionArn,
                          List<KeyValuePair> environment, boolean preferOnDemand) {
        Objects.requireNonNull(clusterSettings, "clusterSettings");
        Objects.requireNonNull(taskDefinitionArn, "taskDefinitionArn");
        Objects.requireNonNull(environment, "environment");

        RunTaskRequest request = RunTaskRequest.builder()
                .cluster(clusterSettings.clusterArn())
                .taskDefinition(taskDefinitionArn)
                // launchType must be omitted whenever capacityProviderStrategy is set -- ECS
                // rejects the request otherwise (confirmed against the RunTask API reference).
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
                .overrides(TaskOverride.builder()
                        .containerOverrides(b -> b.name(CONTAINER_NAME).environment(environment))
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

    private static List<CapacityProviderStrategyItem> capacityProviderStrategy(
            boolean preferOnDemand) {
        if (preferOnDemand) {
            return List.of(
                    CapacityProviderStrategyItem.builder()
                            .capacityProvider(CAPACITY_PROVIDER_ON_DEMAND).weight(4).base(1).build(),
                    CapacityProviderStrategyItem.builder()
                            .capacityProvider(CAPACITY_PROVIDER_SPOT).weight(1).base(0).build());
        }
        return List.of(
                CapacityProviderStrategyItem.builder()
                        .capacityProvider(CAPACITY_PROVIDER_SPOT).weight(4).base(1).build(),
                CapacityProviderStrategyItem.builder()
                        .capacityProvider(CAPACITY_PROVIDER_ON_DEMAND).weight(1).base(0).build());
    }
}
