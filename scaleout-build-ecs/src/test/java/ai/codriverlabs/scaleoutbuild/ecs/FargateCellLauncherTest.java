/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Container;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.StopTaskRequest;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

/**
 * Verifies {@link FargateCellLauncher} is a pure extraction: the {@code RunTaskRequest}/
 * {@code StopTaskRequest} it produces are identical in shape to what the pre-extraction inline
 * code in {@code BuildService} produced, per
 * {@code docs/design/control-plane/microvm-build-backend-phase1.md}'s implementation-order step 1.
 */
@ExtendWith(MockitoExtension.class)
class FargateCellLauncherTest {

    private static final String CLUSTER_ARN = "arn:aws:ecs:us-east-1:123456789012:cluster/scaleout-build";
    private static final String TASK_DEF_ARN =
            "arn:aws:ecs:us-east-1:123456789012:task-definition/scaleout-build-agent-native-x86_64:1";
    private static final String TASK_ARN = "arn:aws:ecs:us-east-1:123456789012:task/scaleout-build/abc123";

    @Mock
    private EcsClient ecsClient;

    private FargateCellLauncher cellLauncher;
    private EcsClusterSettings clusterSettings;

    @BeforeEach
    void setUp() {
        clusterSettings = new EcsClusterSettings(
                EcsLaunchType.FARGATE,
                CLUSTER_ARN,
                List.of("subnet-1", "subnet-2"),
                List.of("sg-1"),
                false,
                "arn:aws:iam::123456789012:role/exec",
                "arn:aws:iam::123456789012:role/task",
                null, null, null, null, null,
                "/scaleout-build/build-agent",
                "us-east-1",
                true); // agentUsesDirectS3Io -- no mount volume needed
        cellLauncher = new FargateCellLauncher(ecsClient, clusterSettings);
    }

    private CellLaunchRequest sampleRequest() {
        return new CellLaunchRequest(
                clusterSettings,
                new AgentContainerSettings("123456789012.dkr.ecr.us-east-1.amazonaws.com/agent", "4096",
                        "8192", 0),
                "01J8ZQ",
                BuildKind.NATIVE,
                Architecture.X86_64,
                "builds/01J8ZQ/native/x86_64",
                Map.of("SCALEOUT_BUILD_ID", "01J8ZQ", "SCALEOUT_BUILD_ARCH", "X86_64"));
    }

    @Test
    void launch_registersTaskDefinitionAndRunsTask() throws CellLaunchException {
        when(ecsClient.describeTaskDefinition(any(java.util.function.Consumer.class)))
                .thenThrow(software.amazon.awssdk.services.ecs.model.ClientException.builder()
                        .message("not found").build());
        when(ecsClient.registerTaskDefinition(any(
                software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn(TASK_DEF_ARN).build())
                        .build());
        ArgumentCaptor<RunTaskRequest> runTaskCaptor = ArgumentCaptor.forClass(RunTaskRequest.class);
        when(ecsClient.runTask(runTaskCaptor.capture())).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn(TASK_ARN).build())
                .build());

        CellHandle handle = cellLauncher.launch(sampleRequest());

        assertThat(handle).isInstanceOf(CellHandle.FargateHandle.class);
        assertThat(((CellHandle.FargateHandle) handle).taskArn()).isEqualTo(TASK_ARN);

        RunTaskRequest captured = runTaskCaptor.getValue();
        assertThat(captured.cluster()).isEqualTo(CLUSTER_ARN);
        assertThat(captured.taskDefinition()).isEqualTo(TASK_DEF_ARN);
        assertThat(captured.overrides().containerOverrides().get(0).environment())
                .extracting(e -> e.name() + "=" + e.value())
                .containsExactlyInAnyOrder("SCALEOUT_BUILD_ID=01J8ZQ", "SCALEOUT_BUILD_ARCH=X86_64");
    }

    @Test
    void launch_wrapsRuntimeFailureAsCellLaunchException() {
        when(ecsClient.describeTaskDefinition(any(java.util.function.Consumer.class)))
                .thenThrow(software.amazon.awssdk.services.ecs.model.ClientException.builder()
                        .message("not found").build());
        when(ecsClient.registerTaskDefinition(any(
                software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest.class)))
                .thenThrow(software.amazon.awssdk.services.ecs.model.ServerException.builder()
                        .message("boom").build());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cellLauncher.launch(sampleRequest()))
                .isInstanceOf(CellLaunchException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void poll_mapsRunningTask() {
        when(ecsClient.describeTasks(any(java.util.function.Consumer.class))).thenReturn(
                DescribeTasksResponse.builder()
                        .tasks(Task.builder().taskArn(TASK_ARN).lastStatus("RUNNING").build())
                        .build());

        CellPollResult result = cellLauncher.poll(new CellHandle.FargateHandle(TASK_ARN));

        assertThat(result.state()).isEqualTo(CellLaunchState.RUNNING);
    }

    @Test
    void poll_mapsSuccessfulStop() {
        when(ecsClient.describeTasks(any(java.util.function.Consumer.class))).thenReturn(
                DescribeTasksResponse.builder()
                        .tasks(Task.builder().taskArn(TASK_ARN).lastStatus("STOPPED")
                                .containers(Container.builder().exitCode(0).build()).build())
                        .build());

        CellPollResult result = cellLauncher.poll(new CellHandle.FargateHandle(TASK_ARN));

        assertThat(result.state()).isEqualTo(CellLaunchState.SUCCEEDED);
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void poll_mapsFailedStopWithReason() {
        when(ecsClient.describeTasks(any(java.util.function.Consumer.class))).thenReturn(
                DescribeTasksResponse.builder()
                        .tasks(Task.builder().taskArn(TASK_ARN).lastStatus("STOPPED")
                                .stoppedReason("native-image failed")
                                .containers(Container.builder().exitCode(1).build()).build())
                        .build());

        CellPollResult result = cellLauncher.poll(new CellHandle.FargateHandle(TASK_ARN));

        assertThat(result.state()).isEqualTo(CellLaunchState.FAILED);
        assertThat(result.failureReason()).isEqualTo("native-image failed");
    }

    @Test
    void pollBatch_describesAllGivenTasksInOneCall() {
        when(ecsClient.describeTasks(any(java.util.function.Consumer.class))).thenReturn(
                DescribeTasksResponse.builder()
                        .tasks(
                                Task.builder().taskArn("task-1").lastStatus("RUNNING").build(),
                                Task.builder().taskArn("task-2").lastStatus("STOPPED")
                                        .containers(Container.builder().exitCode(0).build()).build())
                        .build());

        Map<String, CellPollResult> results = cellLauncher.pollBatch(List.of("task-1", "task-2"));

        assertThat(results).hasSize(2);
        assertThat(results.get("task-1").state()).isEqualTo(CellLaunchState.RUNNING);
        assertThat(results.get("task-2").state()).isEqualTo(CellLaunchState.SUCCEEDED);
    }

    @Test
    void pollBatch_emptyListMakesNoCall() {
        Map<String, CellPollResult> results = cellLauncher.pollBatch(List.of());

        assertThat(results).isEmpty();
    }

    @Test
    void stop_issuesStopTaskWithReason() {
        when(ecsClient.stopTask(any(java.util.function.Consumer.class)))
                .thenReturn(software.amazon.awssdk.services.ecs.model.StopTaskResponse.builder().build());

        cellLauncher.stop(new CellHandle.FargateHandle(TASK_ARN), "cancelled by test");

        ArgumentCaptor<java.util.function.Consumer<StopTaskRequest.Builder>> stopCaptor =
                ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(ecsClient).stopTask(stopCaptor.capture());
        StopTaskRequest.Builder builder = StopTaskRequest.builder();
        stopCaptor.getValue().accept(builder);
        StopTaskRequest captured = builder.build();
        assertThat(captured.cluster()).isEqualTo(CLUSTER_ARN);
        assertThat(captured.task()).isEqualTo(TASK_ARN);
        assertThat(captured.reason()).isEqualTo("cancelled by test");
    }

    @Test
    void stop_toleratesAlreadyStoppedTask() {
        when(ecsClient.stopTask(any(java.util.function.Consumer.class)))
                .thenThrow(software.amazon.awssdk.services.ecs.model.ClientException.builder()
                        .message("already stopped").build());

        // Must not throw -- same tolerance BuildService.stopRunningCells() already had inline.
        cellLauncher.stop(new CellHandle.FargateHandle(TASK_ARN), "cancelled by test");
    }
}
