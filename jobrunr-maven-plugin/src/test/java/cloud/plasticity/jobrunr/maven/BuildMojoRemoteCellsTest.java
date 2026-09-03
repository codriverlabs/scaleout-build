/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.plasticity.jobrunr.build.Architecture;
import cloud.plasticity.jobrunr.build.BuildKind;
import cloud.plasticity.jobrunr.maven.planner.NativeImageInputPlan;
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
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ClientException;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.CreateStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.ListStateMachinesResponse;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

/**
 * Exercises {@code BuildMojo}'s remote-cell orchestration — staging, task definition
 * registration, state machine deployment, execution supervision, and artifact retrieval — through
 * the package-visible {@code runRemoteCells} overload that accepts explicit AWS clients, against
 * mocked ECS/S3/Step Functions rather than real AWS.
 */
@ExtendWith(MockitoExtension.class)
@Timeout(15)
class BuildMojoRemoteCellsTest {

    @Mock
    private S3Client s3Client;
    @Mock
    private EcsClient ecsClient;
    @Mock
    private SfnClient sfnClient;
    @Mock
    private MavenProjectHelper projectHelper;

    private BuildMojo mojo;

    @BeforeEach
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
        setField(mojo, "logGroupName", "/jobrunr/build");
        setField(mojo, "region", "us-east-1");
        setField(mojo, "agentImageUri", "quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25");
        setField(mojo, "agentCpu", "4096");
        setField(mojo, "agentMemory", "16384");
        setField(mojo, "stateMachineExecutionRoleArn", "arn:aws:iam::123456789012:role/sfn-exec");
        setField(mojo, "stateMachineName", "jobrunr-build-matrix");
        setField(mojo, "maxAttemptsPerCell", 2);
        setField(mojo, "overallTimeoutMinutes", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void stagesRegistersDeploysSupervisesAndAttachesOnSuccess() throws Exception {
        when(ecsClient.describeTaskDefinition(any(Consumer.class)))
                .thenThrow(ClientException.builder().message("not found").build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn("arn:...:task-definition/agent-native-arm64:1")
                                .build())
                        .build());
        when(sfnClient.listStateMachines(any(Consumer.class)))
                .thenReturn(ListStateMachinesResponse.builder().stateMachines(List.of()).build());
        when(sfnClient.createStateMachine(any(Consumer.class)))
                .thenReturn(CreateStateMachineResponse.builder()
                        .stateMachineArn("arn:...:stateMachine:jobrunr-build-matrix").build());
        when(sfnClient.startExecution(any(Consumer.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn("arn:...:execution:x")
                        .build());
        String output = """
                [{"buildId":"b1","buildKind":"native","architecture":"ARM64","success":true}]""";
        when(sfnClient.describeExecution(any(Consumer.class)))
                .thenReturn(DescribeExecutionResponse.builder().status(ExecutionStatus.SUCCEEDED)
                        .output(output).build());

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
                ecsClient, sfnClient);

        assertThat(failures).isEmpty();
        verify(ecsClient, times(1)).registerTaskDefinition(any(RegisterTaskDefinitionRequest.class));
        verify(sfnClient, times(1)).createStateMachine(any(Consumer.class));
        verify(sfnClient, times(1)).startExecution(any(Consumer.class));
        verify(projectHelper, times(1)).attachArtifact(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsAFailureForACellTheExecutionReportedAsFailed() throws Exception {
        when(ecsClient.describeTaskDefinition(any(Consumer.class)))
                .thenThrow(ClientException.builder().message("not found").build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn("arn:...:task-definition/agent-native-arm64:1")
                                .build())
                        .build());
        when(sfnClient.listStateMachines(any(Consumer.class)))
                .thenReturn(ListStateMachinesResponse.builder().stateMachines(List.of()).build());
        when(sfnClient.createStateMachine(any(Consumer.class)))
                .thenReturn(CreateStateMachineResponse.builder()
                        .stateMachineArn("arn:...:stateMachine:jobrunr-build-matrix").build());
        when(sfnClient.startExecution(any(Consumer.class)))
                .thenReturn(StartExecutionResponse.builder().executionArn("arn:...:execution:x")
                        .build());
        String output = """
                [{"buildId":"b1","buildKind":"native","architecture":"ARM64","success":false,
                  "error":"States.TaskFailed","cause":"no capacity"}]""";
        when(sfnClient.describeExecution(any(Consumer.class)))
                .thenReturn(DescribeExecutionResponse.builder().status(ExecutionStatus.SUCCEEDED)
                        .output(output).build());

        NativeImageInputPlan generatedPlan = NativeImageInputPlan.generated(List.of(),
                "-o\noutput/test-app\n", List.of("test-app"));
        List<BuildMojo.MatrixCell> remoteCells =
                List.of(newCell(BuildKind.NATIVE, Architecture.ARM64));

        List<String> failures = mojo.runRemoteCells(remoteCells, generatedPlan, "b1", s3Client,
                ecsClient, sfnClient);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).contains("no capacity");
        verify(projectHelper, times(0)).attachArtifact(any(), any(), any(), any());
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
