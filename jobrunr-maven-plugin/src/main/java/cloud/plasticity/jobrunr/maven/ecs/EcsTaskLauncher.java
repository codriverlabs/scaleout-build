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
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskOverride;

/**
 * Launches and stops ECS tasks directly, without any orchestration layer above ECS, across all
 * three supported launch types ({@link EcsLaunchType#FARGATE}, {@link
 * EcsLaunchType#MANAGED_INSTANCES}, {@link EcsLaunchType#EC2}).
 *
 * <p>This is the "pure ECS" alternative to {@code docs/DESIGN.md}'s Step Functions design: for a
 * small, fixed matrix (e.g. exactly x86_64 + arm64), calling {@code RunTask} directly from the
 * plugin and supervising each task with {@link EcsTaskSupervisor} is simpler than deploying and
 * versioning a state machine. The tradeoff, and when Step Functions is worth it again, is documented
 * in {@code docs/PURE_ECS_ALTERNATIVE.md}.
 *
 * <p>How capacity is requested differs by launch type:
 *
 * <ul>
 *   <li>{@code FARGATE}: every launch prefers {@code FARGATE_SPOT} with an on-demand
 *       {@code FARGATE} fallback expressed as capacity provider {@code base}/{@code weight}, rather
 *       than as separate retry logic — ECS itself falls back to on-demand capacity when Spot
 *       capacity is unavailable for the higher-weighted strategy item. {@code preferOnDemand} flips
 *       which side is weighted higher, used after repeated Spot interruptions
 *       (see {@link EcsTaskSupervisor}).
 *   <li>{@code MANAGED_INSTANCES}: targets the single named capacity provider from {@code
 *       EcsClusterSettings#capacityProviderName()} — there is no AWS-managed Spot/on-demand pair to
 *       weight between the way there is for Fargate; Spot vs. on-demand for Managed Instances is a
 *       property of that capacity provider's own configuration (provisioned out of band), so
 *       {@code preferOnDemand} has no effect here.
 *   <li>{@code EC2}: targets the named capacity provider the same way as {@code MANAGED_INSTANCES}
 *       if one is configured, or falls back to {@code launchType: EC2} directly against unmanaged
 *       container instances already registered on the cluster if not. {@code preferOnDemand} has
 *       no effect here either.
 * </ul>
 */
public final class EcsTaskLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(EcsTaskLauncher.class);

    private static final String CAPACITY_PROVIDER_SPOT = "FARGATE_SPOT";
    private static final String CAPACITY_PROVIDER_ON_DEMAND = "FARGATE";
    private static final String CONTAINER_NAME = "jobrunr-build-agent";

    private final EcsClient ecsClient;

    public EcsTaskLauncher(EcsClient ecsClient) {
        this.ecsClient = Objects.requireNonNull(ecsClient, "ecsClient");
    }

    /**
     * Runs one task from {@code taskDefinitionArn} against {@code clusterSettings}'s cluster and
     * network configuration, with {@code environment} applied as a container override — this is
     * how the agent learns which build kind/architecture/staging path it was launched for, since
     * the task definition itself carries no per-build configuration (see
     * {@link TaskDefinitionRegistrar}).
     *
     * @param preferOnDemand when {@code true} and {@code clusterSettings.launchType() == FARGATE},
     *                       weights the on-demand {@code FARGATE} capacity provider above
     *                       {@code FARGATE_SPOT} — used for the on-demand fallback after a build
     *                       has already been interrupted by Spot reclamation more than the
     *                       configured number of times. Has no effect for other launch types.
     * @return the launched task's ARN
     * @throws EcsLaunchException if ECS reports a launch failure (e.g. no Spot capacity
     *         available and no fallback configured)
     */
    public String runTask(EcsClusterSettings clusterSettings, String taskDefinitionArn,
                          List<KeyValuePair> environment, boolean preferOnDemand) {
        Objects.requireNonNull(clusterSettings, "clusterSettings");
        Objects.requireNonNull(taskDefinitionArn, "taskDefinitionArn");
        Objects.requireNonNull(environment, "environment");

        RunTaskRequest.Builder requestBuilder = RunTaskRequest.builder()
                .cluster(clusterSettings.clusterArn())
                .taskDefinition(taskDefinitionArn)
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
                        .build());
        // launchType and capacityProviderStrategy are mutually exclusive -- ECS rejects a request
        // specifying both (confirmed against the RunTask API reference), so exactly one of the two
        // branches below is ever taken.
        List<CapacityProviderStrategyItem> strategy = capacityProviderStrategy(clusterSettings,
                preferOnDemand);
        if (strategy == null) {
            requestBuilder.launchType(LaunchType.EC2);
        } else {
            requestBuilder.capacityProviderStrategy(strategy);
        }

        RunTaskResponse response = ecsClient.runTask(requestBuilder.build());
        if (!response.failures().isEmpty()) {
            String reasons = response.failures().stream()
                    .map(Failure::reason)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("unknown reason");
            throw new EcsLaunchException("RunTask failed: " + reasons);
        }
        if (response.tasks().isEmpty()) {
            throw new EcsLaunchException(
                    "RunTask returned no task and no failure reason; this should not happen");
        }
        Task task = response.tasks().get(0);
        LOG.info("Launched task {} ({}, {})", task.taskArn(), clusterSettings.launchType(),
                preferOnDemand ? "on-demand fallback" : "default capacity preference");
        return task.taskArn();
    }

    /** Requests the task be stopped, e.g. after the overall build timeout elapses. */
    public void stopTask(EcsClusterSettings clusterSettings, String taskArn, String reason) {
        Objects.requireNonNull(clusterSettings, "clusterSettings");
        Objects.requireNonNull(taskArn, "taskArn");
        LOG.warn("Stopping task {}: {}", taskArn, reason);
        ecsClient.stopTask(b -> b.cluster(clusterSettings.clusterArn()).task(taskArn).reason(reason));
    }

    /**
     * @return the capacity provider strategy to use, or {@code null} to signal that
     *         {@code launchType: EC2} should be used directly instead (only possible for
     *         {@link EcsLaunchType#EC2} with no {@code capacityProviderName} configured)
     */
    private static List<CapacityProviderStrategyItem> capacityProviderStrategy(
            EcsClusterSettings clusterSettings, boolean preferOnDemand) {
        return switch (clusterSettings.launchType()) {
            case FARGATE -> fargateCapacityProviderStrategy(preferOnDemand);
            case MANAGED_INSTANCES -> List.of(CapacityProviderStrategyItem.builder()
                    .capacityProvider(clusterSettings.capacityProviderName())
                    .weight(1)
                    .base(1)
                    .build());
            case EC2 -> clusterSettings.capacityProviderName() == null ? null
                    : List.of(CapacityProviderStrategyItem.builder()
                            .capacityProvider(clusterSettings.capacityProviderName())
                            .weight(1)
                            .base(1)
                            .build());
        };
    }

    private static List<CapacityProviderStrategyItem> fargateCapacityProviderStrategy(
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
