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
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.CapacityProviderStrategyItem;
import software.amazon.awssdk.services.ecs.model.Failure;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.StopTaskRequest;
import software.amazon.awssdk.services.ecs.model.Task;

@ExtendWith(MockitoExtension.class)
class EcsTaskLauncherTest {

    @Mock
    private EcsClient ecsClient;

    private EcsTaskLauncher launcher;
    private EcsClusterSettings fargateClusterSettings;
    private List<KeyValuePair> environment;

    @BeforeEach
    void setUp() {
        launcher = new EcsTaskLauncher(ecsClient);
        fargateClusterSettings = fargateSettings();
        environment = List.of(KeyValuePair.builder().name("JOBRUNR_BUILD_ARCH").value("ARM64").build());
    }

    private static EcsClusterSettings fargateSettings() {
        return new EcsClusterSettings(
                EcsLaunchType.FARGATE,
                "arn:aws:ecs:us-east-1:123456789012:cluster/jobrunr-build",
                List.of("subnet-1", "subnet-2"),
                List.of("sg-1"),
                false,
                "arn:aws:iam::123456789012:role/exec",
                "arn:aws:iam::123456789012:role/task",
                "arn:aws:s3files:us-east-1:123456789012:file-system/fs-abc123",
                null, null, null, null,
                "/jobrunr/build-agent",
                "us-east-1",
                false);
    }

    private static EcsClusterSettings managedInstancesSettings() {
        return new EcsClusterSettings(
                EcsLaunchType.MANAGED_INSTANCES,
                "arn:aws:ecs:us-east-1:123456789012:cluster/jobrunr-build",
                List.of("subnet-1", "subnet-2"),
                List.of("sg-1"),
                false,
                "arn:aws:iam::123456789012:role/exec",
                "arn:aws:iam::123456789012:role/task",
                "arn:aws:s3files:us-east-1:123456789012:file-system/fs-abc123",
                null, null, null,
                "managed-instances-cp",
                "/jobrunr/build-agent",
                "us-east-1",
                false);
    }

    private static EcsClusterSettings ec2SettingsWithCapacityProvider() {
        return new EcsClusterSettings(
                EcsLaunchType.EC2,
                "arn:aws:ecs:us-east-1:123456789012:cluster/jobrunr-build",
                List.of("subnet-1", "subnet-2"),
                List.of("sg-1"),
                false,
                "arn:aws:iam::123456789012:role/exec",
                "arn:aws:iam::123456789012:role/task",
                null, null, null,
                "/mnt/build",
                "ec2-asg-cp",
                "/jobrunr/build-agent",
                "us-east-1",
                false);
    }

    private static EcsClusterSettings ec2SettingsWithoutCapacityProvider() {
        return new EcsClusterSettings(
                EcsLaunchType.EC2,
                "arn:aws:ecs:us-east-1:123456789012:cluster/jobrunr-build",
                List.of("subnet-1", "subnet-2"),
                List.of("sg-1"),
                false,
                "arn:aws:iam::123456789012:role/exec",
                "arn:aws:iam::123456789012:role/task",
                null, null, null,
                "/mnt/build",
                null,
                "/jobrunr/build-agent",
                "us-east-1",
                false);
    }

    @Test
    void runsATaskAndReturnsItsArn() {
        when(ecsClient.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:aws:ecs:...:task/abc").build())
                .build());

        String taskArn = launcher.runTask(fargateClusterSettings, "arn:...:task-definition/x:1",
                environment, false);

        assertThat(taskArn).isEqualTo("arn:aws:ecs:...:task/abc");
    }

    @Test
    void fargatePrefersSpotByDefaultAndOmitsLaunchType() {
        when(ecsClient.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/abc").build())
                .build());

        launcher.runTask(fargateClusterSettings, "arn:...:task-definition/x:1", environment, false);

        ArgumentCaptor<RunTaskRequest> captor = ArgumentCaptor.forClass(RunTaskRequest.class);
        verify(ecsClient).runTask(captor.capture());
        RunTaskRequest request = captor.getValue();

        assertThat(request.launchTypeAsString()).isNull();
        assertThat(request.capacityProviderStrategy())
                .extracting(CapacityProviderStrategyItem::capacityProvider)
                .containsExactly("FARGATE_SPOT", "FARGATE");
        assertThat(request.overrides().containerOverrides()).hasSize(1);
        assertThat(request.overrides().containerOverrides().get(0).environment())
                .containsExactly(KeyValuePair.builder().name("JOBRUNR_BUILD_ARCH").value("ARM64")
                        .build());
    }

    @Test
    void fargatePrefersOnDemandWhenRequested() {
        when(ecsClient.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/abc").build())
                .build());

        launcher.runTask(fargateClusterSettings, "arn:...:task-definition/x:1", environment, true);

        ArgumentCaptor<RunTaskRequest> captor = ArgumentCaptor.forClass(RunTaskRequest.class);
        verify(ecsClient).runTask(captor.capture());

        assertThat(captor.getValue().capacityProviderStrategy())
                .extracting(CapacityProviderStrategyItem::capacityProvider)
                .containsExactly("FARGATE", "FARGATE_SPOT");
    }

    @Test
    void managedInstancesTargetsTheNamedCapacityProviderAndOmitsLaunchType() {
        when(ecsClient.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/abc").build())
                .build());

        launcher.runTask(managedInstancesSettings(), "arn:...:task-definition/x:1", environment,
                false);

        ArgumentCaptor<RunTaskRequest> captor = ArgumentCaptor.forClass(RunTaskRequest.class);
        verify(ecsClient).runTask(captor.capture());
        RunTaskRequest request = captor.getValue();

        assertThat(request.launchTypeAsString()).isNull();
        assertThat(request.capacityProviderStrategy())
                .extracting(CapacityProviderStrategyItem::capacityProvider)
                .containsExactly("managed-instances-cp");
    }

    @Test
    void ec2WithACapacityProviderTargetsItAndOmitsLaunchType() {
        when(ecsClient.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/abc").build())
                .build());

        launcher.runTask(ec2SettingsWithCapacityProvider(), "arn:...:task-definition/x:1",
                environment, false);

        ArgumentCaptor<RunTaskRequest> captor = ArgumentCaptor.forClass(RunTaskRequest.class);
        verify(ecsClient).runTask(captor.capture());
        RunTaskRequest request = captor.getValue();

        assertThat(request.launchTypeAsString()).isNull();
        assertThat(request.capacityProviderStrategy())
                .extracting(CapacityProviderStrategyItem::capacityProvider)
                .containsExactly("ec2-asg-cp");
    }

    @Test
    void ec2WithoutACapacityProviderUsesLaunchTypeDirectly() {
        when(ecsClient.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/abc").build())
                .build());

        launcher.runTask(ec2SettingsWithoutCapacityProvider(), "arn:...:task-definition/x:1",
                environment, false);

        ArgumentCaptor<RunTaskRequest> captor = ArgumentCaptor.forClass(RunTaskRequest.class);
        verify(ecsClient).runTask(captor.capture());
        RunTaskRequest request = captor.getValue();

        assertThat(request.launchTypeAsString()).isEqualTo("EC2");
        assertThat(request.capacityProviderStrategy()).isEmpty();
    }

    @Test
    void throwsWithTheFailureReasonWhenEcsReportsAFailure() {
        when(ecsClient.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .failures(Failure.builder().reason("Capacity is not available").build())
                .build());

        assertThatThrownBy(() -> launcher.runTask(fargateClusterSettings,
                "arn:...:task-definition/x:1", environment, false))
                .isInstanceOf(EcsLaunchException.class)
                .hasMessageContaining("Capacity is not available");
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopTaskPassesTheReasonThrough() {
        launcher.stopTask(fargateClusterSettings, "arn:...:task/abc", "timeout");

        ArgumentCaptor<Consumer<StopTaskRequest.Builder>> captor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(ecsClient).stopTask(captor.capture());
        StopTaskRequest.Builder builder = StopTaskRequest.builder();
        captor.getValue().accept(builder);
        StopTaskRequest request = builder.build();

        assertThat(request.task()).isEqualTo("arn:...:task/abc");
        assertThat(request.reason()).isEqualTo("timeout");
    }
}
