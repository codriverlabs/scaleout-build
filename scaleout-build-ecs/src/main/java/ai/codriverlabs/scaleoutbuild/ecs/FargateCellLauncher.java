/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Task;

/**
 * {@link CellLauncher} for Fargate (and the other ECS launch types this module already supports),
 * via {@link EcsTaskLauncher}/{@link TaskDefinitionRegistrar}.
 *
 * <p>Pure extraction: every call this class makes is identical in shape to what
 * {@code BuildService.start()}/{@code refreshFromEcs()}/{@code stopRunningCells()} already did
 * inline before the {@link CellLauncher} abstraction existed. No ECS behavior changes here — the
 * {@code RunTaskRequest}/{@code StopTaskRequest} this class produces are byte-for-byte what the
 * inlined version produced, which is what makes this an extraction rather than a rewrite.
 *
 * <p>Constructed per-request (same lifetime {@code TaskDefinitionRegistrar}/{@code EcsTaskLauncher}
 * already had inline — {@code new} per {@code BuildService} call, not a long-lived singleton) with
 * the cluster settings fixed for that construction, because every cell in one build shares the
 * same cluster — the same reason {@code BuildService.stopRunningCells()} computed
 * {@code clusterSettings()} once per call rather than once per cell before this extraction.
 */
public final class FargateCellLauncher implements CellLauncher {

    private final EcsClient ecsClient;
    private final EcsClusterSettings clusterSettings;
    private final TaskDefinitionRegistrar registrar;
    private final EcsTaskLauncher launcher;

    public FargateCellLauncher(EcsClient ecsClient, EcsClusterSettings clusterSettings) {
        this.ecsClient = Objects.requireNonNull(ecsClient, "ecsClient");
        this.clusterSettings = Objects.requireNonNull(clusterSettings, "clusterSettings");
        this.registrar = new TaskDefinitionRegistrar(ecsClient);
        this.launcher = new EcsTaskLauncher(ecsClient);
    }

    @Override
    public CellHandle launch(CellLaunchRequest request) throws CellLaunchException {
        try {
            String taskDefinitionArn = registrar.registerIfChanged(request.clusterSettings(),
                    request.containerSettings(), request.buildKind(), request.architecture());
            String taskArn = launcher.runTask(request.clusterSettings(), taskDefinitionArn,
                    request.agentEnvironmentAsKeyValuePairs(), false);
            return new CellHandle.FargateHandle(taskArn);
        } catch (RuntimeException e) {
            throw new CellLaunchException("Fargate launch failed: " + e.getMessage(), e);
        }
    }

    /**
     * Polls one task directly, with its own {@code DescribeTasks} call. {@code BuildService}
     * should prefer {@link #pollBatch} across every non-terminal Fargate-backend cell in one call
     * instead of calling this once per cell — see that method's javadoc for why batching matters.
     * This single-cell form exists to satisfy the {@link CellLauncher} interface uniformly with
     * the MicroVM launcher, which has no batch poll operation to prefer.
     */
    @Override
    public CellPollResult poll(CellHandle handle) {
        CellHandle.FargateHandle h = (CellHandle.FargateHandle) handle;
        var response = ecsClient.describeTasks(b -> b.cluster(clusterSettings.clusterArn())
                .tasks(List.of(h.taskArn())));
        if (response.tasks().isEmpty()) {
            return CellPollResult.provisioning();
        }
        return mapTask(response.tasks().get(0));
    }

    /**
     * Polls every given task in one {@code DescribeTasks} call (up to 100 per AWS's own limit),
     * rather than one call per cell — {@code DescribeTasks} supports batching and the Fargate path
     * has always used it that way, so the {@link CellLauncher} abstraction must not regress a
     * multi-cell build into N calls for N cells when one call already covers all of them.
     *
     * @return results keyed by task ARN, in no particular order
     */
    public java.util.Map<String, CellPollResult> pollBatch(List<String> taskArns) {
        if (taskArns.isEmpty()) {
            return java.util.Map.of();
        }
        var response = ecsClient.describeTasks(b -> b.cluster(clusterSettings.clusterArn())
                .tasks(taskArns));
        java.util.Map<String, CellPollResult> results = new java.util.LinkedHashMap<>();
        for (Task task : response.tasks()) {
            results.put(task.taskArn(), mapTask(task));
        }
        return results;
    }

    private static CellPollResult mapTask(Task task) {
        return switch (task.lastStatus()) {
            case "RUNNING" -> CellPollResult.running();
            case "STOPPED" -> {
                Integer exitCode = task.containers().isEmpty() ? null
                        : task.containers().get(0).exitCode();
                if (exitCode != null && exitCode == 0) {
                    yield CellPollResult.succeeded(exitCode);
                }
                yield CellPollResult.failed(exitCode, task.stoppedReason());
            }
            default -> CellPollResult.provisioning();
        };
    }

    @Override
    public void stop(CellHandle handle, String reason) {
        CellHandle.FargateHandle h = (CellHandle.FargateHandle) handle;
        try {
            launcher.stopTask(clusterSettings, h.taskArn(), reason);
        } catch (RuntimeException e) {
            // Already stopped, or transient -- same tolerance BuildService.stopRunningCells()
            // already has inline today for this exact call.
        }
    }
}
