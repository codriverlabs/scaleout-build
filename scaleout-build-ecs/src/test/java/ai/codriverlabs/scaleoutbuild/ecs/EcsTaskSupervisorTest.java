/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Container;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.Task;

// Every test here drives a bounded polling loop; per the lesson learned wiring BuildSupervisorTest
// in an earlier session, a real bug in the polling logic should fail fast with a clear timeout
// instead of hanging the whole build indefinitely.
@ExtendWith(MockitoExtension.class)
@Timeout(10)
class EcsTaskSupervisorTest {

    @Mock
    private EcsClient ecsClient;
    @Mock
    private CloudWatchLogsClient logsClient;

    private EcsTaskLauncher launcher;
    private CloudWatchLogTailer logTailer;
    private EcsTaskSupervisor supervisor;
    private EcsClusterSettings clusterSettings;
    private List<KeyValuePair> environment;
    private List<String> logLines;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        launcher = new EcsTaskLauncher(ecsClient);
        logTailer = new CloudWatchLogTailer(logsClient);
        supervisor = new EcsTaskSupervisor(launcher, logTailer, ecsClient);
        clusterSettings = new EcsClusterSettings(
                EcsLaunchType.FARGATE,
                "arn:aws:ecs:us-east-1:123456789012:cluster/scaleout-build",
                List.of("subnet-1"), List.of("sg-1"), false,
                "arn:aws:iam::123456789012:role/exec", "arn:aws:iam::123456789012:role/task",
                "arn:aws:s3files:us-east-1:123456789012:file-system/fs-abc123", null, null, null, null,
                "/scaleout-build/build-agent", "us-east-1", false);
        environment = List.of(KeyValuePair.builder().name("SCALEOUT_BUILD_ARCH").value("ARM64").build());
        logLines = new java.util.ArrayList<>();

        // lenient(): the stream-name composition test below is a pure unit test of a static helper
        // and never polls CloudWatch, so strict stubbing would fail it for not using this stub.
        // Relaxing "was this stub used" here does not weaken any assertion in the tailing tests.
        org.mockito.Mockito.lenient().when(logsClient.filterLogEvents(any(Consumer.class)))
                .thenReturn(FilterLogEventsResponse.builder().events(List.of()).build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void succeedsWhenTheContainerExitsZero() throws InterruptedException {
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").build()).build());
        when(ecsClient.describeTasks(any(Consumer.class))).thenReturn(DescribeTasksResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").lastStatus("STOPPED")
                        .stoppedReason("Essential container in task exited")
                        .containers(Container.builder().exitCode(0).build()).build())
                .build());

        var result = supervisor.supervise(clusterSettings, "arn:...:task-definition/x:1", environment,
                "scaleout-build", logLines::add,
                EcsTaskSupervisor.SupervisionOptions.defaults().pollInterval(Duration.ofMillis(10)));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.spotInterruptions()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsWhenTheContainerExitsNonZero() throws InterruptedException {
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").build()).build());
        when(ecsClient.describeTasks(any(Consumer.class))).thenReturn(DescribeTasksResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").lastStatus("STOPPED")
                        .stoppedReason("Essential container in task exited")
                        .containers(Container.builder().exitCode(1).reason("native-image failed")
                                .build())
                        .build())
                .build());

        var result = supervisor.supervise(clusterSettings, "arn:...:task-definition/x:1", environment,
                "scaleout-build", logLines::add,
                EcsTaskSupervisor.SupervisionOptions.defaults().pollInterval(Duration.ofMillis(10)));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.failureReason()).contains("native-image failed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void relaunchesOnTheDocumentedSpotInterruptionMessageThenSucceeds() throws InterruptedException {
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class)))
                .thenReturn(RunTaskResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/1").build()).build())
                .thenReturn(RunTaskResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/2").build()).build());
        when(ecsClient.describeTasks(any(Consumer.class)))
                .thenReturn(DescribeTasksResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/1").lastStatus("STOPPED")
                                // The exact, documented free-text value for a genuine Spot
                                // reclaim -- verified against AWS's own troubleshooting guidance,
                                // not guessed.
                                .stoppedReason("Your Spot Task was interrupted.")
                                .containers(Container.builder().exitCode(1).build()).build())
                        .build())
                .thenReturn(DescribeTasksResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/2").lastStatus("STOPPED")
                                .stoppedReason("Essential container in task exited")
                                .containers(Container.builder().exitCode(0).build()).build())
                        .build());

        var result = supervisor.supervise(clusterSettings, "arn:...:task-definition/x:1", environment,
                "scaleout-build", logLines::add,
                EcsTaskSupervisor.SupervisionOptions.defaults().pollInterval(Duration.ofMillis(10)));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.spotInterruptions()).isEqualTo(1);
        verify(ecsClient, times(2)).runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotTreatAnUnrelatedStoppedReasonAsASpotInterruption() throws InterruptedException {
        // Regression guard: this class matches the documented Spot-interruption message
        // case-sensitively and verbatim, not with a loose substring check -- an unrelated
        // stoppedReason must be treated as a genuine (failed) stop, not silently relaunched.
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").build()).build());
        when(ecsClient.describeTasks(any(Consumer.class))).thenReturn(DescribeTasksResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").lastStatus("STOPPED")
                        .stoppedReason("Task failed to start")
                        .containers(Container.builder().exitCode(1).build()).build())
                .build());

        var result = supervisor.supervise(clusterSettings, "arn:...:task-definition/x:1", environment,
                "scaleout-build", logLines::add,
                EcsTaskSupervisor.SupervisionOptions.defaults().pollInterval(Duration.ofMillis(10)));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.spotInterruptions()).isZero();
        verify(ecsClient, times(1)).runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopsTheTaskAndFailsOnOverallTimeout() throws InterruptedException {
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").build()).build());
        when(ecsClient.describeTasks(any(Consumer.class))).thenReturn(DescribeTasksResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").lastStatus("RUNNING").build())
                .build());

        var result = supervisor.supervise(clusterSettings, "arn:...:task-definition/x:1", environment,
                "scaleout-build", logLines::add,
                EcsTaskSupervisor.SupervisionOptions.defaults()
                        .pollInterval(Duration.ofMillis(10))
                        .overallTimeout(Duration.ofMillis(50)));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.succeeded()).isFalse();
        verify(ecsClient).stopTask(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void escalatesToOnDemandAfterTheConfiguredNumberOfInterruptions() throws InterruptedException {
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class)))
                .thenReturn(RunTaskResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/1").build()).build())
                .thenReturn(RunTaskResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/2").build()).build())
                .thenReturn(RunTaskResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/3").build()).build());
        when(ecsClient.describeTasks(any(Consumer.class)))
                .thenReturn(DescribeTasksResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/1").lastStatus("STOPPED")
                                .stoppedReason("Your Spot Task was interrupted.")
                                .containers(Container.builder().exitCode(1).build()).build())
                        .build())
                .thenReturn(DescribeTasksResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/2").lastStatus("STOPPED")
                                .stoppedReason("Your Spot Task was interrupted.")
                                .containers(Container.builder().exitCode(1).build()).build())
                        .build())
                .thenReturn(DescribeTasksResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/3").lastStatus("STOPPED")
                                .stoppedReason("Essential container in task exited")
                                .containers(Container.builder().exitCode(0).build()).build())
                        .build());

        var result = supervisor.supervise(clusterSettings, "arn:...:task-definition/x:1", environment,
                "scaleout-build", logLines::add,
                EcsTaskSupervisor.SupervisionOptions.defaults()
                        .pollInterval(Duration.ofMillis(10))
                        .maxSpotInterruptionsBeforeOnDemand(1));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.spotInterruptions()).isEqualTo(2);
        // First relaunch (interruption #1, not yet past the threshold of 1) still prefers Spot;
        // the second relaunch (interruption #2, past the threshold) should prefer on-demand.
        var captor = org.mockito.ArgumentCaptor
                .forClass(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class);
        verify(ecsClient, times(3)).runTask(captor.capture());
        List<String> firstProviders = captor.getAllValues().get(1).capacityProviderStrategy().stream()
                .map(software.amazon.awssdk.services.ecs.model.CapacityProviderStrategyItem::capacityProvider)
                .toList();
        List<String> secondProviders = captor.getAllValues().get(2).capacityProviderStrategy().stream()
                .map(software.amazon.awssdk.services.ecs.model.CapacityProviderStrategyItem::capacityProvider)
                .toList();
        assertThat(firstProviders).containsExactly("FARGATE_SPOT", "FARGATE");
        assertThat(secondProviders).containsExactly("FARGATE", "FARGATE_SPOT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void managedInstancesNeverTreatsAStopAsASpotInterruptionEvenWithTheFargateSpotWording()
            throws InterruptedException {
        // Regression guard for the documented gap in EcsTaskSupervisor's javadoc: the Fargate-Spot
        // stoppedReason string must never be reused to drive relaunch logic for MANAGED_INSTANCES,
        // since that exact wording has not been verified for that launch type.
        EcsClusterSettings managedInstancesSettings = new EcsClusterSettings(
                EcsLaunchType.MANAGED_INSTANCES,
                "arn:aws:ecs:us-east-1:123456789012:cluster/scaleout-build",
                List.of("subnet-1"), List.of("sg-1"), false,
                "arn:aws:iam::123456789012:role/exec", "arn:aws:iam::123456789012:role/task",
                "arn:aws:s3files:us-east-1:123456789012:file-system/fs-abc123", null, null, null,
                "managed-instances-cp", "/scaleout-build/build-agent", "us-east-1", false);
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").build()).build());
        when(ecsClient.describeTasks(any(Consumer.class))).thenReturn(DescribeTasksResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").lastStatus("STOPPED")
                        .stoppedReason("Your Spot Task was interrupted.")
                        .containers(Container.builder().exitCode(1).build()).build())
                .build());

        var result = supervisor.supervise(managedInstancesSettings, "arn:...:task-definition/x:1",
                environment, "scaleout-build", logLines::add,
                EcsTaskSupervisor.SupervisionOptions.defaults().pollInterval(Duration.ofMillis(10)));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.spotInterruptions()).isZero();
        verify(ecsClient, times(1)).runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class));
    }

    /**
     * Regression guard for a bug confirmed against real AWS: every matrix cell's task definition
     * shares one {@code awslogs-stream-prefix}, so tailing on that bare prefix matched every
     * concurrent cell's stream. Each cell then printed the other cell's lines under its own label,
     * and the single {@code logsSince} watermark — advanced by whichever stream emitted most
     * recently — silently dropped the slower task's genuinely-new lines. The filter must therefore
     * be the task-scoped stream name, {@code <prefix>/<container-name>/<task-id>}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void tailsOnlyTheSupervisedTasksOwnLogStreamNotTheSharedPrefix() throws InterruptedException {
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder()
                        .taskArn("arn:aws:ecs:us-east-1:123456789012:task/scaleout-build/abc123def456")
                        .build())
                .build());
        when(ecsClient.describeTasks(any(Consumer.class))).thenReturn(DescribeTasksResponse.builder()
                .tasks(Task.builder()
                        .taskArn("arn:aws:ecs:us-east-1:123456789012:task/scaleout-build/abc123def456")
                        .lastStatus("STOPPED")
                        .stoppedReason("Essential container in task exited")
                        .containers(Container.builder().exitCode(0).build()).build())
                .build());

        supervisor.supervise(clusterSettings, "arn:...:task-definition/x:1", environment,
                "scaleout-build", logLines::add,
                EcsTaskSupervisor.SupervisionOptions.defaults().pollInterval(Duration.ofMillis(10)));

        var captor = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(logsClient, org.mockito.Mockito.atLeastOnce()).filterLogEvents(captor.capture());
        var builder = software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest
                .builder();
        ((Consumer<software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest.Builder>)
                captor.getValue()).accept(builder);
        assertThat(builder.build().logStreamNamePrefix())
                .isEqualTo("scaleout-build/scaleout-build-agent/abc123def456");
    }

    @Test
    void composesTheAwslogsStreamNameFromTheTaskIdSegmentOfTheArn() {
        assertThat(EcsTaskSupervisor.logStreamNameFor("scaleout-build",
                "arn:aws:ecs:eu-west-1:864899852480:task/scaleout-build-test/4ca29c85a3d649a7b805fcf7f912dfd0"))
                .isEqualTo("scaleout-build/scaleout-build-agent/4ca29c85a3d649a7b805fcf7f912dfd0");
        // Two tasks in the same cluster must never collapse onto the same stream name -- that
        // collision is exactly what the shared-prefix bug amounted to.
        assertThat(EcsTaskSupervisor.logStreamNameFor("scaleout-build", "arn:...:task/cluster/aaa"))
                .isNotEqualTo(
                        EcsTaskSupervisor.logStreamNameFor("scaleout-build", "arn:...:task/cluster/bbb"));
    }
}

