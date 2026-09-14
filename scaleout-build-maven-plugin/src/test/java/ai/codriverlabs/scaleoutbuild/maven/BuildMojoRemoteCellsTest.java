/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import ai.codriverlabs.scaleoutbuild.maven.planner.NativeImageInputPlan;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ClientException;
import software.amazon.awssdk.services.ecs.model.Container;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * Exercises {@code BuildMojo}'s pure-ECS remote-cell orchestration — stage, register, launch
 * directly, supervise, retrieve, attach, with no orchestration layer above ECS — through the
 * package-visible {@code runRemoteCells} overload that accepts explicit AWS clients.
 */
@ExtendWith(MockitoExtension.class)
@Timeout(20)
class BuildMojoRemoteCellsTest {

    @Mock
    private S3Client s3Client;
    @Mock
    private EcsClient ecsClient;
    @Mock
    private CloudWatchLogsClient logsClient;
    @Mock
    private MavenProjectHelper projectHelper;

    private BuildMojo mojo;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp(@TempDir Path targetDir) throws Exception {
        mojo = new BuildMojo();
        MavenProject project = new MavenProject();
        Build build = new Build();
        build.setDirectory(targetDir.toString());
        build.setFinalName("test-app");
        project.setBuild(build);

        setField(mojo, "project", project);
        setField(mojo, "projectHelper", projectHelper);
        setField(mojo, "s3Bucket", "test-bucket");
        setField(mojo, "clusterArn", "arn:aws:ecs:us-east-1:123456789012:cluster/build");
        setField(mojo, "subnetIds", List.of("subnet-1"));
        setField(mojo, "securityGroupIds", List.of("sg-1"));
        setField(mojo, "executionRoleArn", "arn:aws:iam::123456789012:role/exec");
        setField(mojo, "taskRoleArn", "arn:aws:iam::123456789012:role/task");
        setField(mojo, "s3FilesFileSystemArn",
                "arn:aws:s3files:us-east-1:123456789012:file-system/fs-abc");
        setField(mojo, "logGroupName", "/scaleout-build/build");
        setField(mojo, "region", "us-east-1");
        setField(mojo, "agentImageUri", "quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25");
        setField(mojo, "agentCpu", "4096");
        setField(mojo, "agentMemory", "16384");
        setField(mojo, "maxSpotInterruptionsBeforeOnDemand", 2);
        setField(mojo, "pollIntervalSeconds", 1);
        setField(mojo, "overallTimeoutMinutes", 1);

        when(logsClient.filterLogEvents(any(Consumer.class)))
                .thenReturn(FilterLogEventsResponse.builder().events(List.of()).build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void stagesRegistersLaunchesSupervisesAndAttachesOnSuccess() throws Exception {
        when(ecsClient.describeTaskDefinition(any(Consumer.class)))
                .thenThrow(ClientException.builder().message("not found").build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn("arn:...:task-definition/agent-native-arm64:1")
                                .build())
                        .build());
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").build()).build());
        when(ecsClient.describeTasks(any(Consumer.class))).thenReturn(DescribeTasksResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").lastStatus("STOPPED")
                        .stoppedReason("Essential container in task exited")
                        .containers(Container.builder().exitCode(0).build()).build())
                .build());

        when(s3Client.headObject(any(Consumer.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(4L).build());
        when(s3Client.getObject(any(GetObjectRequest.class), any(Path.class)))
                .thenAnswer(invocation -> {
                    Path destination = invocation.getArgument(1);
                    Files.writeString(destination, "bin");
                    return null;
                });

        NativeImageInputPlan generatedPlan = NativeImageInputPlan.generated(List.of(),
                "-o\noutput/test-app\n", List.of("test-app"));
        List<BuildMojo.MatrixCell> remoteCells =
                List.of(newCell(BuildKind.NATIVE, Architecture.ARM64));

        List<String> failures = mojo.runRemoteCells(remoteCells, generatedPlan, "b1", s3Client,
                ecsClient, logsClient);

        assertThat(failures).isEmpty();
        verify(ecsClient, times(1)).registerTaskDefinition(any(RegisterTaskDefinitionRequest.class));
        verify(ecsClient, times(1)).runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class));
        verify(projectHelper, times(1)).attachArtifact(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void omitsTheS3BucketOverrideByDefault() throws Exception {
        var captor = stubForOneSuccessfulLaunchAndCaptureRunTaskRequest();

        NativeImageInputPlan generatedPlan = NativeImageInputPlan.generated(List.of(),
                "-o\noutput/test-app\n", List.of("test-app"));
        mojo.runRemoteCells(List.of(newCell(BuildKind.NATIVE, Architecture.ARM64)), generatedPlan,
                "b1", s3Client, ecsClient, logsClient);

        List<software.amazon.awssdk.services.ecs.model.KeyValuePair> environment =
                capturedEnvironment(captor);
        assertThat(environment).noneMatch(kv -> "JOBRUNR_BUILD_S3_BUCKET".equals(kv.name()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void setsTheS3BucketOverrideWhenDirectS3IoIsEnabled() throws Exception {
        setField(mojo, "agentUsesDirectS3Io", true);
        // Direct-S3-calls mode needs no mount infrastructure at all -- clearing this proves
        // EcsClusterSettings genuinely doesn't require it here anymore, rather than happening to
        // pass only because setUp() already populated it for the mount-based default case.
        setField(mojo, "s3FilesFileSystemArn", null);
        var captor = stubForOneSuccessfulLaunchAndCaptureRunTaskRequest();

        NativeImageInputPlan generatedPlan = NativeImageInputPlan.generated(List.of(),
                "-o\noutput/test-app\n", List.of("test-app"));
        mojo.runRemoteCells(List.of(newCell(BuildKind.NATIVE, Architecture.ARM64)), generatedPlan,
                "b1", s3Client, ecsClient, logsClient);

        List<software.amazon.awssdk.services.ecs.model.KeyValuePair> environment =
                capturedEnvironment(captor);
        assertThat(environment)
                .anyMatch(kv -> "JOBRUNR_BUILD_S3_BUCKET".equals(kv.name())
                        && "test-bucket".equals(kv.value()));
    }

    @SuppressWarnings("unchecked")
    private org.mockito.ArgumentCaptor<software.amazon.awssdk.services.ecs.model.RunTaskRequest>
            stubForOneSuccessfulLaunchAndCaptureRunTaskRequest() {
        when(ecsClient.describeTaskDefinition(any(Consumer.class)))
                .thenThrow(ClientException.builder().message("not found").build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn("arn:...:task-definition/agent-native-arm64:1")
                                .build())
                        .build());
        var captor = org.mockito.ArgumentCaptor
                .forClass(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class);
        when(ecsClient.runTask(captor.capture())).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").build()).build());
        when(ecsClient.describeTasks(any(Consumer.class))).thenReturn(DescribeTasksResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").lastStatus("STOPPED")
                        .stoppedReason("Essential container in task exited")
                        .containers(Container.builder().exitCode(0).build()).build())
                .build());
        when(s3Client.headObject(any(Consumer.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(4L).build());
        when(s3Client.getObject(any(GetObjectRequest.class), any(Path.class)))
                .thenAnswer(invocation -> {
                    Path destination = invocation.getArgument(1);
                    Files.writeString(destination, "bin");
                    return null;
                });
        return captor;
    }

    private static List<software.amazon.awssdk.services.ecs.model.KeyValuePair> capturedEnvironment(
            org.mockito.ArgumentCaptor<software.amazon.awssdk.services.ecs.model.RunTaskRequest>
                    captor) {
        return captor.getValue().overrides().containerOverrides().get(0).environment();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsAFailureWhenTheContainerExitsNonZero() throws Exception {
        when(ecsClient.describeTaskDefinition(any(Consumer.class)))
                .thenThrow(ClientException.builder().message("not found").build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn("arn:...:task-definition/agent-native-arm64:1")
                                .build())
                        .build());
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").build()).build());
        when(ecsClient.describeTasks(any(Consumer.class))).thenReturn(DescribeTasksResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").lastStatus("STOPPED")
                        .stoppedReason("Essential container in task exited")
                        .containers(Container.builder().exitCode(1).reason("no capacity").build())
                        .build())
                .build());

        NativeImageInputPlan generatedPlan = NativeImageInputPlan.generated(List.of(),
                "-o\noutput/test-app\n", List.of("test-app"));
        List<BuildMojo.MatrixCell> remoteCells =
                List.of(newCell(BuildKind.NATIVE, Architecture.ARM64));

        List<String> failures = mojo.runRemoteCells(remoteCells, generatedPlan, "b1", s3Client,
                ecsClient, logsClient);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).contains("no capacity");
        verify(projectHelper, times(0)).attachArtifact(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void supervisesMultipleRemoteCellsConcurrently() throws Exception {
        when(ecsClient.describeTaskDefinition(any(Consumer.class)))
                .thenThrow(ClientException.builder().message("not found").build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder().taskDefinitionArn("arn:...:1")
                                .build())
                        .build());
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class)))
                .thenReturn(RunTaskResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/x86").build()).build())
                .thenReturn(RunTaskResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/arm").build()).build());
        when(ecsClient.describeTasks(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<software.amazon.awssdk.services.ecs.model.DescribeTasksRequest.Builder> consumer =
                    invocation.getArgument(0);
            var builder = software.amazon.awssdk.services.ecs.model.DescribeTasksRequest.builder();
            consumer.accept(builder);
            String taskArn = builder.build().tasks().get(0);
            return DescribeTasksResponse.builder()
                    .tasks(Task.builder().taskArn(taskArn).lastStatus("STOPPED")
                            .stoppedReason("Essential container in task exited")
                            .containers(Container.builder().exitCode(0).build()).build())
                    .build();
        });

        when(s3Client.headObject(any(Consumer.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(4L).build());
        when(s3Client.getObject(any(GetObjectRequest.class), any(Path.class)))
                .thenAnswer(invocation -> {
                    Path destination = invocation.getArgument(1);
                    Files.writeString(destination, "bin");
                    return null;
                });

        NativeImageInputPlan generatedPlan = NativeImageInputPlan.generated(List.of(),
                "-o\noutput/test-app\n", List.of("test-app"));
        List<BuildMojo.MatrixCell> remoteCells = List.of(
                newCell(BuildKind.NATIVE, Architecture.X86_64),
                newCell(BuildKind.NATIVE, Architecture.ARM64));

        List<String> failures = mojo.runRemoteCells(remoteCells, generatedPlan, "b1", s3Client,
                ecsClient, logsClient);

        assertThat(failures).isEmpty();
        verify(ecsClient, times(2)).runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class));
        verify(projectHelper, times(2)).attachArtifact(any(), any(), any(), any());
    }

    private static BuildMojo.MatrixCell newCell(BuildKind buildKind, Architecture architecture) {
        return new BuildMojo.MatrixCell(buildKind, architecture);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = BuildMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
