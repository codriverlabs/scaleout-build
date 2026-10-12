/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildSpec;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CellState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.InputDescriptor;
import ai.codriverlabs.scaleoutbuild.controlplane.auth.CallerIdentity;
import ai.codriverlabs.scaleoutbuild.controlplane.auth.CallerIdentityResolver;
import ai.codriverlabs.scaleoutbuild.controlplane.store.BuildRecord;
import ai.codriverlabs.scaleoutbuild.controlplane.store.BuildRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.lambdamicrovms.LambdaMicrovmsClient;
import software.amazon.awssdk.services.lambdamicrovms.model.RunMicrovmRequest;
import software.amazon.awssdk.services.lambdamicrovms.model.RunMicrovmResponse;
import software.amazon.awssdk.services.lambdamicrovms.model.TerminateMicrovmRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Exercises {@code BuildService.cellLauncher(Architecture)}'s MicroVM dispatch path, in a
 * separate test class from {@link BuildServiceTest} rather than as additional {@code @Test}
 * methods there.
 *
 * <p>This separation exists for a reason found the hard way: {@code @TestProfile} applies at the
 * class level in Quarkus, and the first attempt enabled {@code scaleout.micro-vm.enabled=true}
 * globally in {@code application.properties}'s {@code %test.} profile instead. That broke
 * {@code BuildServiceTest.start_launches_one_ecs_task_per_cell}, which launches one
 * {@code NATIVE/ARM64} cell expecting Fargate dispatch — enabling the flag silently rerouted it to
 * {@code MicroVmCellLauncher}, where {@code microvms.runMicrovm(...)} was unstubbed and returned
 * {@code null}. That failure is itself the proof of the design doc's own "Backend selection: by
 * architecture, not configuration" warning: enabling MicroVM is a deployment-wide behavior change,
 * not a per-test toggle, so it belongs in its own profile rather than the shared default one.
 */
@QuarkusTest
@TestProfile(BuildServiceMicrovmDispatchTest.MicrovmEnabledProfile.class)
class BuildServiceMicrovmDispatchTest {

    /** Overrides only {@code scaleout.micro-vm.enabled}; the image/version/connector values
     * already exist in the base {@code %test.} profile (see {@code application.properties}). */
    public static class MicrovmEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("scaleout.micro-vm.enabled", "true");
        }
    }

    private static final CallerIdentity CALLER = CallerIdentityResolver.of(
            "arn:aws:iam::000000000000:user/test-user", null, null);
    private static final String TASK_ARN =
            "arn:aws:ecs:eu-west-1:000000000000:task/test-cluster/abc123";
    private static final String TASK_DEF_ARN =
            "arn:aws:ecs:eu-west-1:000000000000:task-definition/scaleout-build-agent:1";
    private static final String MICROVM_ID = "microvm-abc123";
    private static final String ENDPOINT =
            "https://microvm-abc123.lambda-microvms.eu-west-1.amazonaws.com";

    @InjectMock
    EcsClient ecs;

    @InjectMock
    LambdaMicrovmsClient microvms;

    @InjectMock
    S3Client s3Client;

    @InjectMock
    BuildRepository repository;

    @InjectMock
    StagingService staging;

    @Inject
    BuildService service;

    @BeforeEach
    void stubDefaults() {
        when(repository.countActiveByOwner(any())).thenReturn(0L);
        org.mockito.Mockito.doNothing().when(repository).put(any());
        when(repository.putIfStateIs(any(), any())).thenReturn(true);

        when(staging.missingDigests(any(), any())).thenReturn(List.of());
        when(staging.stagingPath(any(), any(), any(), any())).thenReturn("builds/test/native/arm64");
        when(staging.outputPrefix(any(), any(), any(), any()))
                .thenReturn("builds/test/native/arm64/output/");
        when(staging.listOutputNames(any(), any(), any(), any())).thenReturn(List.of("app"));

        when(ecs.describeTaskDefinition(any(java.util.function.Consumer.class))).thenReturn(
                DescribeTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn(TASK_DEF_ARN).revision(0).build())
                        .build());
        when(ecs.registerTaskDefinition(
                any(software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest.class)))
                .thenReturn(RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn(TASK_DEF_ARN).build())
                        .build());
        when(ecs.runTask(any(RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn(TASK_ARN).build())
                .build());
    }

    @Test
    void start_launchesArm64CellsOnMicrovmAndX86_64CellsOnFargate() throws InvalidRequestException {
        BuildRecord record = stagedBuild("mv1", List.of("NATIVE/X86_64", "NATIVE/ARM64"));
        when(repository.findOwned("mv1", CALLER.ownerKey())).thenReturn(Optional.of(record));
        when(microvms.runMicrovm(any(RunMicrovmRequest.class))).thenReturn(RunMicrovmResponse.builder()
                .microvmId(MICROVM_ID)
                .endpoint(ENDPOINT)
                .build());

        Optional<BuildStatus> status = service.start(CALLER, "mv1");

        // Exactly one cell on each backend -- not two on either.
        verify(ecs, times(1)).runTask(any(RunTaskRequest.class));
        verify(microvms, times(1)).runMicrovm(any(RunMicrovmRequest.class));
        assertThat(status).isPresent();
        assertThat(status.get().cells()).extracting(c -> c.cell())
                .containsExactlyInAnyOrder("NATIVE/X86_64", "NATIVE/ARM64");
    }

    /**
     * The actual point of {@code backendPreference}: on a deployment where {@code auto} would
     * route this {@code arm64} cell to MicroVM (this test class's own
     * {@code MicrovmEnabledProfile}), an explicit {@code fargate} preference overrides that
     * default. This is the lever a cost-conscious caller needs — see {@code BuildSpec
     * .backendPreference}'s own javadoc for the pricing data this exists to let them act on.
     */
    @Test
    void start_fargatePreferenceOverridesTheDeploymentsMicrovmDefault() throws InvalidRequestException {
        BuildRecord record = stagedBuild("mv5", List.of("NATIVE/ARM64"), "fargate");
        when(repository.findOwned("mv5", CALLER.ownerKey())).thenReturn(Optional.of(record));

        service.start(CALLER, "mv5");

        verify(ecs, times(1)).runTask(any(RunTaskRequest.class));
        verify(microvms, never()).runMicrovm(any(RunMicrovmRequest.class));
    }

    /**
     * The inverse: an explicit {@code microvm} preference is honored even if a future deployment
     * ever makes {@code auto}'s own default something other than "MicroVM when available" — this
     * test does not depend on {@code MicrovmEnabledProfile}'s default at all, it asserts the
     * explicit choice is what drove dispatch, independent of what {@code auto} would have done.
     */
    @Test
    void start_microvmPreferenceDispatchesToMicrovm() throws InvalidRequestException {
        BuildRecord record = stagedBuild("mv6", List.of("NATIVE/ARM64"), "microvm");
        when(repository.findOwned("mv6", CALLER.ownerKey())).thenReturn(Optional.of(record));
        when(microvms.runMicrovm(any(RunMicrovmRequest.class))).thenReturn(RunMicrovmResponse.builder()
                .microvmId(MICROVM_ID)
                .endpoint(ENDPOINT)
                .build());

        service.start(CALLER, "mv6");

        verify(microvms, times(1)).runMicrovm(any(RunMicrovmRequest.class));
        verify(ecs, never()).runTask(any(RunTaskRequest.class));
    }

    @Test
    void start_recordsMicrovmIdAndEndpointForArm64Cell() throws InvalidRequestException {
        BuildRecord record = stagedBuild("mv2", List.of("NATIVE/ARM64"));
        when(repository.findOwned("mv2", CALLER.ownerKey())).thenReturn(Optional.of(record));
        when(microvms.runMicrovm(any(RunMicrovmRequest.class))).thenReturn(RunMicrovmResponse.builder()
                .microvmId(MICROVM_ID)
                .endpoint(ENDPOINT)
                .build());

        service.start(CALLER, "mv2");

        ArgumentCaptor<BuildRecord> saved = ArgumentCaptor.forClass(BuildRecord.class);
        verify(repository, times(1)).put(saved.capture());
        BuildRecord.CellRecord cell = saved.getValue().cell("NATIVE/ARM64");
        assertThat(cell.getBackend()).isEqualTo("MICROVM");
        assertThat(cell.getMicrovmId()).isEqualTo(MICROVM_ID);
        assertThat(cell.getMicrovmEndpoint()).isEqualTo(ENDPOINT);
        assertThat(cell.getTaskArn()).isNull();
    }

    @Test
    void cancel_terminatesMicrovmForArm64Cell() throws InvalidRequestException {
        BuildRecord record = runningBuild("mv3", List.of("NATIVE/ARM64"));
        record.getCells().get(0).setBackend("MICROVM");
        record.getCells().get(0).setMicrovmId(MICROVM_ID);
        when(repository.findOwned("mv3", CALLER.ownerKey())).thenReturn(Optional.of(record));

        service.cancel(CALLER, "mv3");

        ArgumentCaptor<TerminateMicrovmRequest> captor =
                ArgumentCaptor.forClass(TerminateMicrovmRequest.class);
        verify(microvms, times(1)).terminateMicrovm(captor.capture());
        assertThat(captor.getValue().microvmIdentifier()).isEqualTo(MICROVM_ID);
        verify(ecs, never()).stopTask(any(java.util.function.Consumer.class));
    }

    @Test
    void refresh_pollsMicrovmViaStatusJsonInsteadOfEcs() {
        BuildRecord record = runningBuild("mv4", List.of("NATIVE/ARM64"));
        record.getCells().get(0).setBackend("MICROVM");
        record.getCells().get(0).setMicrovmId(MICROVM_ID);
        when(repository.findOwned("mv4", CALLER.ownerKey())).thenReturn(Optional.of(record));
        String statusJson = """
                {"buildId":"mv4","cell":"NATIVE/ARM64","state":"SUCCEEDED","exitCode":0,
                 "artifacts":[],"completedAt":"2026-10-10T00:00:00Z"}
                """;
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(),
                        statusJson.getBytes(StandardCharsets.UTF_8)));

        Optional<BuildStatus> status = service.status(CALLER, "mv4");

        assertThat(status).isPresent();
        assertThat(status.get().cells().get(0).state()).isEqualTo(CellState.SUCCEEDED);
        assertThat(status.get().state()).isEqualTo(BuildState.SUCCEEDED);
        // The MicroVM path never calls DescribeTasks for a MicroVM-backed cell.
        verify(ecs, never()).describeTasks(any(java.util.function.Consumer.class));
    }

    // ── helpers (mirroring BuildServiceTest's own, kept local since @TestProfile is class-scoped
    // and these two test classes must not share mutable test state) ───────────────────────────

    private static BuildRecord stagedBuild(String id, List<String> cells) {
        return buildRecord(id, BuildState.STAGED, CellState.PENDING, cells, null);
    }

    private static BuildRecord stagedBuild(String id, List<String> cells, String backendPreference) {
        return buildRecord(id, BuildState.STAGED, CellState.PENDING, cells, backendPreference);
    }

    private static BuildRecord runningBuild(String id, List<String> cells) {
        return buildRecord(id, BuildState.RUNNING, CellState.RUNNING, cells, null);
    }

    private static BuildRecord buildRecord(String id, BuildState state, CellState cellState,
                                           List<String> cellIds, String backendPreference) {
        BuildRecord record = new BuildRecord();
        record.setBuildId(id);
        record.setOwnerKey(CALLER.ownerKey());
        record.setOwnerArn(CALLER.roleArn());
        record.setState(state);
        record.setCreatedAt(Instant.now().minusSeconds(60));
        record.setLastHeartbeatAt(Instant.now().minusSeconds(10));
        record.setExpiresAt(Instant.now().plusSeconds(3600));
        record.setTtl(Instant.now().plusSeconds(86400).getEpochSecond());
        record.setOverallTimeoutMinutes(30);
        record.setAppliedCpu("4096");
        record.setAppliedMemory("8192");
        record.setAppliedEphemeralStorageGiB(0);
        record.setBuildSpec(new BuildSpec(List.of("native"), List.of("arm64"), null, "app", null,
                List.of(), List.of(), 0, 0, backendPreference));
        record.setInputs(List.of(new InputDescriptor("app.jar", "sha256abc", 1024L)));

        List<BuildRecord.CellRecord> cellList = new ArrayList<>();
        for (String cellId : cellIds) {
            BuildRecord.CellRecord cell = new BuildRecord.CellRecord();
            cell.setCell(cellId);
            cell.setState(cellState);
            cellList.add(cell);
        }
        record.setCells(cellList);
        return record;
    }
}
