/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.ecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.plasticity.jobrunr.build.Architecture;
import cloud.plasticity.jobrunr.build.BuildKind;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ClientException;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.Tag;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

@ExtendWith(MockitoExtension.class)
class TaskDefinitionRegistrarTest {

    @Mock
    private EcsClient ecsClient;

    private TaskDefinitionRegistrar registrar;
    private EcsClusterSettings clusterSettings;
    private AgentContainerSettings containerSettings;

    @BeforeEach
    void setUp() {
        registrar = new TaskDefinitionRegistrar(ecsClient);
        clusterSettings = new EcsClusterSettings(
                EcsLaunchType.FARGATE,
                "arn:aws:ecs:us-east-1:123456789012:cluster/jobrunr-build",
                List.of("subnet-1", "subnet-2"),
                List.of("sg-1"),
                false,
                "arn:aws:iam::123456789012:role/jobrunr-build-execution",
                "arn:aws:iam::123456789012:role/jobrunr-build-task",
                "arn:aws:s3files:us-east-1:123456789012:file-system/fs-abc123",
                null,
                null,
                null,
                null,
                "/jobrunr/build-agent",
                "us-east-1");
        containerSettings = AgentContainerSettings.of(
                "quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25", "4096", "16384");
    }

    @Test
    void registersANewTaskDefinitionWhenTheFamilyDoesNotExistYet() {
        when(ecsClient.describeTaskDefinition(any(java.util.function.Consumer.class)))
                .thenThrow(ClientException.builder().message("family not found").build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn("arn:aws:ecs:us-east-1:123456789012:task-definition/"
                                        + "jobrunr-build-agent-native-x86_64:1")
                                .build())
                        .build());

        String arn = registrar.registerIfChanged(clusterSettings, containerSettings,
                BuildKind.NATIVE, Architecture.X86_64);

        assertThat(arn).isEqualTo("arn:aws:ecs:us-east-1:123456789012:task-definition/"
                + "jobrunr-build-agent-native-x86_64:1");
        verify(ecsClient, times(1)).registerTaskDefinition(any(RegisterTaskDefinitionRequest.class));
    }

    @Test
    void skipsRegistrationWhenTheExistingRevisionsConfigHashMatches() {
        // Capture the hash the registrar would compute by first triggering a real registration...
        String[] capturedHash = new String[1];
        when(ecsClient.describeTaskDefinition(any(java.util.function.Consumer.class)))
                .thenThrow(ClientException.builder().message("family not found").build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenAnswer(invocation -> {
                    RegisterTaskDefinitionRequest request = invocation.getArgument(0);
                    capturedHash[0] = request.tags().get(0).value();
                    return RegisterTaskDefinitionResponse.builder()
                            .taskDefinition(TaskDefinition.builder()
                                    .taskDefinitionArn(
                                            "arn:...:task-definition/jobrunr-build-agent-native-x86_64:1")
                                    .build())
                            .build();
                });
        registrar.registerIfChanged(clusterSettings, containerSettings, BuildKind.NATIVE,
                Architecture.X86_64);
        assertThat(capturedHash[0]).isNotBlank();

        // ...then simulate a second invocation where DescribeTaskDefinition reports that hash as
        // already tagged on the current revision.
        var freshMock = org.mockito.Mockito.mock(EcsClient.class);
        when(freshMock.describeTaskDefinition(any(java.util.function.Consumer.class)))
                .thenReturn(DescribeTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn(
                                        "arn:aws:ecs:us-east-1:123456789012:task-definition/"
                                                + "jobrunr-build-agent-native-x86_64:1")
                                .build())
                        .tags(Tag.builder().key(TaskDefinitionRegistrar.CONFIG_HASH_TAG_KEY)
                                .value(capturedHash[0]).build())
                        .build());
        TaskDefinitionRegistrar secondRegistrar = new TaskDefinitionRegistrar(freshMock);

        String arn = secondRegistrar.registerIfChanged(clusterSettings, containerSettings,
                BuildKind.NATIVE, Architecture.X86_64);

        assertThat(arn).isEqualTo("arn:aws:ecs:us-east-1:123456789012:task-definition/"
                + "jobrunr-build-agent-native-x86_64:1");
        verify(freshMock, never()).registerTaskDefinition(any(RegisterTaskDefinitionRequest.class));
    }

    @Test
    void registersANewRevisionWhenTheConfigHashDiffers() {
        when(ecsClient.describeTaskDefinition(any(java.util.function.Consumer.class)))
                .thenReturn(DescribeTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn(
                                        "arn:...:task-definition/jobrunr-build-agent-native-x86_64:1")
                                .build())
                        .tags(Tag.builder().key(TaskDefinitionRegistrar.CONFIG_HASH_TAG_KEY)
                                .value("some-stale-hash-from-a-previous-configuration").build())
                        .build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn(
                                        "arn:...:task-definition/jobrunr-build-agent-native-x86_64:2")
                                .build())
                        .build());

        String arn = registrar.registerIfChanged(clusterSettings, containerSettings, BuildKind.NATIVE,
                Architecture.X86_64);

        assertThat(arn).isEqualTo("arn:...:task-definition/jobrunr-build-agent-native-x86_64:2");
        verify(ecsClient, times(1)).registerTaskDefinition(any(RegisterTaskDefinitionRequest.class));
    }

    @Test
    void tagsTheRegistrationWithAConfigHashAndUsesADistinctFamilyPerBuildKind() {
        when(ecsClient.describeTaskDefinition(any(java.util.function.Consumer.class)))
                .thenThrow(ClientException.builder().message("not found").build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn(
                                        "arn:...:task-definition/jobrunr-build-agent-native-pgo-optimize"
                                                + "-arm64:1")
                                .build())
                        .build());

        registrar.registerIfChanged(clusterSettings, containerSettings,
                BuildKind.NATIVE_PGO_OPTIMIZE, Architecture.ARM64);

        var captor = org.mockito.ArgumentCaptor.forClass(RegisterTaskDefinitionRequest.class);
        verify(ecsClient).registerTaskDefinition(captor.capture());
        RegisterTaskDefinitionRequest request = captor.getValue();

        assertThat(request.family()).isEqualTo("jobrunr-build-agent-native-pgo-optimize-arm64");
        assertThat(request.tags()).anySatisfy(tag ->
                assertThat(tag.key()).isEqualTo(TaskDefinitionRegistrar.CONFIG_HASH_TAG_KEY));
        assertThat(request.requiresCompatibilities())
                .containsExactly(software.amazon.awssdk.services.ecs.model.Compatibility.FARGATE);
        assertThat(request.volumes()).hasSize(1);
        assertThat(request.volumes().get(0).s3filesVolumeConfiguration().fileSystemArn())
                .isEqualTo("arn:aws:s3files:us-east-1:123456789012:file-system/fs-abc123");
        assertThat(request.runtimePlatform().cpuArchitectureAsString()).isEqualTo("ARM64");
        assertThat(request.containerDefinitions()).hasSize(1);
        // Regression guard: the volume must actually be mounted into the container, not just
        // declared at the task level -- without an explicit mountPoints entry, ECS never mounts an
        // S3 Files (or any other) volume into a container at all.
        assertThat(request.containerDefinitions().get(0).mountPoints()).singleElement().satisfies(mp -> {
            assertThat(mp.sourceVolume()).isEqualTo(request.volumes().get(0).name());
            assertThat(mp.containerPath()).isEqualTo(TaskDefinitionRegistrar.MOUNT_CONTAINER_PATH);
        });
        // No per-build environment variables baked into the task definition -- those arrive as
        // task overrides from RunTask instead.
        assertThat(request.containerDefinitions().get(0).environment()).isEmpty();
    }

    @Test
    void managedInstancesUsesAnS3FilesVolumeAndTheManagedInstancesCompatibility() {
        EcsClusterSettings managedInstancesSettings = new EcsClusterSettings(
                EcsLaunchType.MANAGED_INSTANCES,
                "arn:aws:ecs:us-east-1:123456789012:cluster/jobrunr-build",
                List.of("subnet-1", "subnet-2"), List.of("sg-1"), false,
                "arn:aws:iam::123456789012:role/jobrunr-build-execution",
                "arn:aws:iam::123456789012:role/jobrunr-build-task",
                "arn:aws:s3files:us-east-1:123456789012:file-system/fs-abc123", null, null, null,
                "managed-instances-cp", "/jobrunr/build-agent", "us-east-1");
        when(ecsClient.describeTaskDefinition(any(java.util.function.Consumer.class)))
                .thenThrow(ClientException.builder().message("not found").build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn("arn:...:task-definition/x:1").build())
                        .build());

        registrar.registerIfChanged(managedInstancesSettings, containerSettings, BuildKind.NATIVE,
                Architecture.X86_64);

        var captor = org.mockito.ArgumentCaptor.forClass(RegisterTaskDefinitionRequest.class);
        verify(ecsClient).registerTaskDefinition(captor.capture());
        RegisterTaskDefinitionRequest request = captor.getValue();

        assertThat(request.requiresCompatibilities()).containsExactly(
                software.amazon.awssdk.services.ecs.model.Compatibility.MANAGED_INSTANCES);
        assertThat(request.volumes().get(0).s3filesVolumeConfiguration()).isNotNull();
        assertThat(request.volumes().get(0).host()).isNull();
        // ephemeralStorage is not a valid parameter for MANAGED_INSTANCES tasks (confirmed against
        // AWS's task-definition-differences documentation) -- must be omitted even if the container
        // settings request one.
        assertThat(request.ephemeralStorage()).isNull();
    }

    @Test
    void ec2UsesAHostBindMountVolumeAndTheEc2CompatibilityAndOmitsEphemeralStorage() {
        AgentContainerSettings withEphemeralStorage = new AgentContainerSettings(
                "quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25", "4096", "16384", 50);
        EcsClusterSettings ec2Settings = new EcsClusterSettings(
                EcsLaunchType.EC2,
                "arn:aws:ecs:us-east-1:123456789012:cluster/jobrunr-build",
                List.of("subnet-1", "subnet-2"), List.of("sg-1"), false,
                "arn:aws:iam::123456789012:role/jobrunr-build-execution",
                "arn:aws:iam::123456789012:role/jobrunr-build-task",
                null, null, null,
                "/mnt/build", "ec2-asg-cp", "/jobrunr/build-agent", "us-east-1");
        when(ecsClient.describeTaskDefinition(any(java.util.function.Consumer.class)))
                .thenThrow(ClientException.builder().message("not found").build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn("arn:...:task-definition/x:1").build())
                        .build());

        registrar.registerIfChanged(ec2Settings, withEphemeralStorage, BuildKind.NATIVE,
                Architecture.X86_64);

        var captor = org.mockito.ArgumentCaptor.forClass(RegisterTaskDefinitionRequest.class);
        verify(ecsClient).registerTaskDefinition(captor.capture());
        RegisterTaskDefinitionRequest request = captor.getValue();

        assertThat(request.requiresCompatibilities())
                .containsExactly(software.amazon.awssdk.services.ecs.model.Compatibility.EC2);
        assertThat(request.volumes().get(0).s3filesVolumeConfiguration()).isNull();
        assertThat(request.volumes().get(0).host().sourcePath()).isEqualTo("/mnt/build");
        assertThat(request.containerDefinitions().get(0).mountPoints()).singleElement().satisfies(mp ->
                assertThat(mp.containerPath()).isEqualTo(TaskDefinitionRegistrar.MOUNT_CONTAINER_PATH));
        assertThat(request.ephemeralStorage()).isNull();
    }

    @Test
    void differentBuildKindsForTheSameArchitectureProduceDifferentFamilies() {
        when(ecsClient.describeTaskDefinition(any(java.util.function.Consumer.class)))
                .thenThrow(ClientException.builder().message("not found").build());
        when(ecsClient.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder().taskDefinitionArn("arn:...:1").build())
                        .build());

        registrar.registerIfChanged(clusterSettings, containerSettings, BuildKind.NATIVE,
                Architecture.X86_64);
        registrar.registerIfChanged(clusterSettings, containerSettings,
                BuildKind.NATIVE_PGO_INSTRUMENT, Architecture.X86_64);

        var captor = org.mockito.ArgumentCaptor.forClass(RegisterTaskDefinitionRequest.class);
        verify(ecsClient, times(2)).registerTaskDefinition(captor.capture());
        List<String> families = captor.getAllValues().stream()
                .map(RegisterTaskDefinitionRequest::family).toList();

        assertThat(families).containsExactly(
                "jobrunr-build-agent-native-x86_64",
                "jobrunr-build-agent-native-pgo-instrument-x86_64");
    }
}
