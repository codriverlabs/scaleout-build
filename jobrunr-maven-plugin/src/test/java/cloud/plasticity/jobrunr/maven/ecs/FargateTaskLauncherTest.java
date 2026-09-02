/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.ecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.CapacityProviderStrategyItem;
import software.amazon.awssdk.services.ecs.model.Failure;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.StopTaskRequest;
import software.amazon.awssdk.services.ecs.model.Task;

@ExtendWith(MockitoExtension.class)
class FargateTaskLauncherTest {

    @Mock
    private EcsClient ecsClient;

    private FargateTaskLauncher launcher;
    private EcsClusterSettings clusterSettings;

    @BeforeEach
    void setUp() {
        launcher = new FargateTaskLauncher(ecsClient);
        clusterSettings = new EcsClusterSettings(
                "arn:aws:ecs:us-east-1:123456789012:cluster/jobrunr-build",
                List.of("subnet-1", "subnet-2"),
                List.of("sg-1"),
                false,
                "arn:aws:iam::123456789012:role/exec",
                "arn:aws:iam::123456789012:role/task",
                "arn:aws:s3files:us-east-1:123456789012:file-system/fs-abc123",
                null, null,
                "/jobrunr/build-agent",
                "us-east-1");
    }

    @Test
    void runsATaskAndReturnsItsArn() {
        when(ecsClient.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:aws:ecs:...:task/abc").build())
                .build());

        String taskArn = launcher.runTask(clusterSettings, "arn:...:task-definition/x:1", false);

        assertThat(taskArn).isEqualTo("arn:aws:ecs:...:task/abc");
    }

    @Test
    void prefersSpotByDefaultAndOmitsLaunchType() {
        when(ecsClient.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/abc").build())
                .build());

        launcher.runTask(clusterSettings, "arn:...:task-definition/x:1", false);

        ArgumentCaptor<RunTaskRequest> captor = ArgumentCaptor.forClass(RunTaskRequest.class);
        verify(ecsClient).runTask(captor.capture());
        RunTaskRequest request = captor.getValue();

        // launchType must be omitted whenever capacityProviderStrategy is set, or ECS rejects the
        // request outright.
        assertThat(request.launchTypeAsString()).isNull();
        assertThat(request.capacityProviderStrategy())
                .extracting(CapacityProviderStrategyItem::capacityProvider)
                .containsExactly("FARGATE_SPOT", "FARGATE");
    }

    @Test
    void prefersOnDemandWhenRequested() {
        when(ecsClient.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/abc").build())
                .build());

        launcher.runTask(clusterSettings, "arn:...:task-definition/x:1", true);

        ArgumentCaptor<RunTaskRequest> captor = ArgumentCaptor.forClass(RunTaskRequest.class);
        verify(ecsClient).runTask(captor.capture());

        assertThat(captor.getValue().capacityProviderStrategy())
                .extracting(CapacityProviderStrategyItem::capacityProvider)
                .containsExactly("FARGATE", "FARGATE_SPOT");
    }

    @Test
    void throwsWithTheFailureReasonWhenEcsReportsAFailure() {
        when(ecsClient.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .failures(Failure.builder().reason("Capacity is not available").build())
                .build());

        assertThatThrownBy(() -> launcher.runTask(clusterSettings, "arn:...:task-definition/x:1", false))
                .isInstanceOf(FargateLaunchException.class)
                .hasMessageContaining("Capacity is not available");
    }

    @Test
    void stopTaskPassesTheReasonThrough() {
        launcher.stopTask(clusterSettings, "arn:...:task/abc", "timeout");

        ArgumentCaptor<java.util.function.Consumer<StopTaskRequest.Builder>> captor =
                ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(ecsClient).stopTask(captor.capture());
        StopTaskRequest.Builder builder = StopTaskRequest.builder();
        captor.getValue().accept(builder);
        StopTaskRequest request = builder.build();

        assertThat(request.task()).isEqualTo("arn:...:task/abc");
        assertThat(request.reason()).isEqualTo("timeout");
    }
}
