/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildSpec;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CellState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildRequest;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.InputDescriptor;
import ai.codriverlabs.scaleoutbuild.controlplane.auth.CallerIdentity;
import ai.codriverlabs.scaleoutbuild.controlplane.auth.CallerIdentityResolver;
import ai.codriverlabs.scaleoutbuild.controlplane.store.BuildRecord;
import ai.codriverlabs.scaleoutbuild.controlplane.store.BuildRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Container;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.ListTaskDefinitionsResponse;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.StopTaskResponse;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

/**
 * Unit tests for {@link BuildService} — the core build lifecycle engine.
 *
 * <p>Uses {@code @QuarkusTest} + {@code @InjectMock} to mock the AWS SDK clients and repository,
 * exercising the real CDI wiring and config mapping. All ECS calls go through the mocked
 * {@link EcsClient}; no AWS account or network access is needed.
 *
 * <h2>ECS call chain</h2>
 * <pre>
 *   start() -> TaskDefinitionRegistrar.registerIfChanged()
 *                -> ecs.listTaskDefinitions()
 *                -> ecs.describeTaskDefinition()  (if existing)
 *                -> ecs.registerTaskDefinition()
 *             -> EcsTaskLauncher.runTask()
 *                -> ecs.runTask()
 *   refreshFromEcs() -> ecs.describeTasks()
 *   cancel() -> EcsTaskLauncher.stopTask() -> ecs.stopTask()
 * </pre>
 */
@QuarkusTest
class BuildServiceTest {

    private static final CallerIdentity CALLER = CallerIdentityResolver.of(
            "arn:aws:iam::000000000000:user/test-user", null, null);

    private static final String TASK_ARN =
            "arn:aws:ecs:eu-west-1:000000000000:task/test-cluster/abc123";
    private static final String TASK_DEF_ARN =
            "arn:aws:ecs:eu-west-1:000000000000:task-definition/scaleout-build-agent:1";

    @InjectMock
    EcsClient ecs;

    @InjectMock
    BuildRepository repository;

    @InjectMock
    StagingService staging;

    @Inject
    BuildService service;

    @BeforeEach
    void stubDefaults() {
        // Repository: no active builds by default, put() and put/compareAndSwap are no-ops
        when(repository.countActiveByOwner(any())).thenReturn(0L);
        org.mockito.Mockito.doNothing().when(repository).put(any());
        when(repository.putIfStateIs(any(), any())).thenReturn(true);

        // Staging: no missing digests (all inputs already staged)
        when(staging.missingDigests(any(), any())).thenReturn(List.of());
        when(staging.stagingPath(any(), any(), any(), any())).thenReturn("builds/test/native/x86_64");
        when(staging.outputPrefix(any(), any(), any(), any())).thenReturn("builds/test/native/x86_64/output/");
        when(staging.listOutputNames(any(), any(), any(), any())).thenReturn(List.of("app"));

        // ECS: describeTaskDefinition returns not-found (consumer lambda)
        when(ecs.describeTaskDefinition(any(java.util.function.Consumer.class))).thenReturn(
                DescribeTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn(TASK_DEF_ARN)
                                .revision(0).build())
                        .build());
        // ECS: registerTaskDefinition takes a typed request object
        when(ecs.registerTaskDefinition(any(
                software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest.class))).thenReturn(
                RegisterTaskDefinitionResponse.builder()
                        .taskDefinition(TaskDefinition.builder()
                                .taskDefinitionArn(TASK_DEF_ARN).build())
                        .build());

        // ECS run task: returns a task with a fixed ARN
        when(ecs.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class))).thenReturn(
                RunTaskResponse.builder()
                        .tasks(Task.builder().taskArn(TASK_ARN).build())
                        .build());
    }

    // ── create() ─────────────────────────────────────────────────────────────────────────────

    @Test
    void create_returns_build_id_and_cells_for_matrix() throws InvalidRequestException {
        BuildSpec spec = new BuildSpec(List.of("native"), List.of("x86_64", "arm64"),
                null, "app", null, List.of(), List.of(), 0, 0);
        CreateBuildRequest req = new CreateBuildRequest(spec,
                List.of(new InputDescriptor("app.jar", "sha256abc", 1024L)), null, null);

        CreateBuildResponse resp = service.create(CALLER, req);

        assertThat(resp.buildId()).isNotBlank();
        assertThat(resp.cells()).hasSize(2);
        assertThat(resp.cells()).extracting(c -> c.cell())
                .containsExactlyInAnyOrder("NATIVE/X86_64", "NATIVE/ARM64");
    }

    @Test
    void create_marks_staged_when_all_inputs_already_present() throws InvalidRequestException {
        // staging returns no missing digests -> build goes straight to STAGED
        when(staging.missingDigests(any(), any())).thenReturn(List.of());

        BuildSpec spec = new BuildSpec(List.of("native"), List.of("x86_64"),
                null, "app", null, List.of(), List.of(), 0, 0);
        CreateBuildRequest req = new CreateBuildRequest(spec,
                List.of(new InputDescriptor("app.jar", "sha256abc", 1024L)), null, null);

        CreateBuildResponse resp = service.create(CALLER, req);

        assertThat(resp.state()).isEqualTo(BuildState.STAGED);
        assertThat(resp.uploads()).isEmpty();
    }

    @Test
    void create_returns_upload_targets_for_missing_inputs() throws InvalidRequestException {
        when(staging.missingDigests(any(), any())).thenReturn(List.of("sha256abc"));
        when(staging.presignUpload(any(), any())).thenReturn(
                new ai.codriverlabs.scaleoutbuild.controlplane.api.UploadTarget(
                        "sha256abc", "PUT", "https://s3/presigned", Instant.now().plusSeconds(900)));

        BuildSpec spec = new BuildSpec(List.of("native"), List.of("x86_64"),
                null, "app", null, List.of(), List.of(), 0, 0);
        CreateBuildRequest req = new CreateBuildRequest(spec,
                List.of(new InputDescriptor("app.jar", "sha256abc", 1024L)), null, null);

        CreateBuildResponse resp = service.create(CALLER, req);

        assertThat(resp.state()).isEqualTo(BuildState.PENDING);
        assertThat(resp.uploads()).hasSize(1);
        assertThat(resp.uploads().get(0).sha256()).isEqualTo("sha256abc");
    }

    @Test
    void create_rejects_when_concurrency_limit_reached() {
        when(repository.countActiveByOwner(any())).thenReturn(4L); // default max is 4

        BuildSpec spec = new BuildSpec(List.of("native"), List.of("x86_64"),
                null, "app", null, List.of(), List.of(), 0, 0);
        CreateBuildRequest req = new CreateBuildRequest(spec,
                List.of(new InputDescriptor("app.jar", "sha256abc", 1024L)), null, null);

        assertThatThrownBy(() -> service.create(CALLER, req))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("concurrency limit");
    }

    @Test
    void create_rejects_empty_build_kinds() {
        BuildSpec spec = new BuildSpec(List.of(), List.of("x86_64"),
                null, "app", null, List.of(), List.of(), 0, 0);
        CreateBuildRequest req = new CreateBuildRequest(spec,
                List.of(new InputDescriptor("app.jar", "sha256abc", 1024L)), null, null);

        assertThatThrownBy(() -> service.create(CALLER, req))
                .isInstanceOf(InvalidRequestException.class);
    }

    // ── start() ──────────────────────────────────────────────────────────────────────────────

    @Test
    void start_launches_one_ecs_task_per_cell() throws InvalidRequestException {
        BuildRecord record = stagedBuild("b1", List.of("NATIVE/X86_64", "NATIVE/ARM64"));
        when(repository.findOwned("b1", CALLER.ownerKey())).thenReturn(Optional.of(record));

        service.start(CALLER, "b1");

        // One runTask call per cell (2 cells = 2 calls)
        verify(ecs, times(2)).runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class));
    }

    @Test
    void start_rejects_already_running_build() {
        BuildRecord record = runningBuild("b2", List.of("NATIVE/X86_64"));
        when(repository.findOwned("b2", CALLER.ownerKey())).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.start(CALLER, "b2"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("RUNNING");
    }

    @Test
    void start_rejects_build_with_missing_inputs() {
        BuildRecord record = stagedBuild("b3", List.of("NATIVE/X86_64"));
        when(repository.findOwned("b3", CALLER.ownerKey())).thenReturn(Optional.of(record));
        when(staging.missingDigests(any(), any())).thenReturn(List.of("sha256missing"));

        assertThatThrownBy(() -> service.start(CALLER, "b3"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("sha256missing");
    }

    @Test
    void start_returns_empty_for_unknown_build() throws InvalidRequestException {
        when(repository.findOwned("nope", CALLER.ownerKey())).thenReturn(Optional.empty());

        assertThat(service.start(CALLER, "nope")).isEmpty();
        verify(ecs, never()).runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class));
    }

    @Test
    void start_race_condition_is_idempotent() throws InvalidRequestException {
        // putIfStateIs returns false → concurrent start already won, no ECS launch
        BuildRecord record = stagedBuild("b4", List.of("NATIVE/X86_64"));
        BuildRecord running = runningBuild("b4", List.of("NATIVE/X86_64"));
        when(repository.findOwned("b4", CALLER.ownerKey()))
                .thenReturn(Optional.of(record))  // first call
                .thenReturn(Optional.of(running)); // second call (after putIfStateIs false)
        when(repository.putIfStateIs(any(), any())).thenReturn(false);

        Optional<BuildStatus> status = service.start(CALLER, "b4");

        verify(ecs, never()).runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class));
        assertThat(status).isPresent();
        assertThat(status.get().state()).isEqualTo(BuildState.RUNNING);
    }

    // ── refreshFromEcs() ─────────────────────────────────────────────────────────────────────

    @Test
    void refresh_marks_cell_succeeded_on_exit_0() {
        BuildRecord record = runningBuild("b5", List.of("NATIVE/X86_64"));
        record.getCells().get(0).setTaskArn(TASK_ARN);

        when(ecs.describeTasks(any(java.util.function.Consumer.class))).thenReturn(
                DescribeTasksResponse.builder()
                        .tasks(Task.builder()
                                .taskArn(TASK_ARN)
                                .lastStatus("STOPPED")
                                .containers(Container.builder().exitCode(0).build())
                                .build())
                        .build());
        when(repository.findOwned("b5", CALLER.ownerKey())).thenReturn(Optional.of(record));

        Optional<BuildStatus> status = service.status(CALLER, "b5");

        assertThat(status).isPresent();
        assertThat(status.get().cells().get(0).state()).isEqualTo(CellState.SUCCEEDED);
        assertThat(status.get().state()).isEqualTo(BuildState.SUCCEEDED);
    }

    @Test
    void refresh_marks_cell_failed_on_nonzero_exit() {
        BuildRecord record = runningBuild("b6", List.of("NATIVE/X86_64"));
        record.getCells().get(0).setTaskArn(TASK_ARN);

        when(ecs.describeTasks(any(java.util.function.Consumer.class))).thenReturn(
                DescribeTasksResponse.builder()
                        .tasks(Task.builder()
                                .taskArn(TASK_ARN)
                                .lastStatus("STOPPED")
                                .stoppedReason("native-image compilation failed")
                                .containers(Container.builder().exitCode(1).build())
                                .build())
                        .build());
        when(repository.findOwned("b6", CALLER.ownerKey())).thenReturn(Optional.of(record));

        Optional<BuildStatus> status = service.status(CALLER, "b6");

        assertThat(status.get().cells().get(0).state()).isEqualTo(CellState.FAILED);
        assertThat(status.get().state()).isEqualTo(BuildState.FAILED);
    }

    @Test
    void refresh_timeout_exit_124_produces_actionable_message() {
        BuildRecord record = runningBuild("b7", List.of("NATIVE/X86_64"));
        record.getCells().get(0).setTaskArn(TASK_ARN);

        when(ecs.describeTasks(any(java.util.function.Consumer.class))).thenReturn(
                DescribeTasksResponse.builder()
                        .tasks(Task.builder()
                                .taskArn(TASK_ARN)
                                .lastStatus("STOPPED")
                                .stoppedReason("")
                                // 124 = coreutils timeout: container wall-clock exceeded
                                .containers(Container.builder().exitCode(124).build())
                                .build())
                        .build());
        when(repository.findOwned("b7", CALLER.ownerKey())).thenReturn(Optional.of(record));

        Optional<BuildStatus> status = service.status(CALLER, "b7");

        assertThat(status.get().cells().get(0).failureReason())
                .as("exit 124 should mention the timeout setting, not just say 'exit 124'")
                .containsIgnoringCase("timeout")
                .containsIgnoringCase("timeoutMinutes");
    }

    @Test
    void refresh_preserves_spot_interruption_reason() {
        BuildRecord record = runningBuild("b8", List.of("NATIVE/X86_64"));
        record.getCells().get(0).setTaskArn(TASK_ARN);

        when(ecs.describeTasks(any(java.util.function.Consumer.class))).thenReturn(
                DescribeTasksResponse.builder()
                        .tasks(Task.builder()
                                .taskArn(TASK_ARN)
                                .lastStatus("STOPPED")
                                .stoppedReason("Your Spot Task was interrupted.")
                                .containers(Container.builder().exitCode(1).build())
                                .build())
                        .build());
        when(repository.findOwned("b8", CALLER.ownerKey())).thenReturn(Optional.of(record));

        Optional<BuildStatus> status = service.status(CALLER, "b8");

        assertThat(status.get().cells().get(0).failureReason())
                .contains("Your Spot Task was interrupted.");
    }

    // ── cancel() ─────────────────────────────────────────────────────────────────────────────

    @Test
    void cancel_issues_stop_task_for_each_running_cell() throws InvalidRequestException {
        BuildRecord record = runningBuild("b9", List.of("NATIVE/X86_64", "NATIVE/ARM64"));
        record.getCells().get(0).setTaskArn(TASK_ARN + "-1");
        record.getCells().get(1).setTaskArn(TASK_ARN + "-2");
        when(repository.findOwned("b9", CALLER.ownerKey())).thenReturn(Optional.of(record));
        when(ecs.stopTask(any(java.util.function.Consumer.class)))
                .thenReturn(StopTaskResponse.builder().build());

        service.cancel(CALLER, "b9");

        verify(ecs, times(2)).stopTask(any(java.util.function.Consumer.class));
    }

    @Test
    void cancel_is_idempotent_on_terminal_build() throws InvalidRequestException {
        BuildRecord record = succeededBuild("b10", List.of("NATIVE/X86_64"));
        when(repository.findOwned("b10", CALLER.ownerKey())).thenReturn(Optional.of(record));

        Optional<BuildStatus> status = service.cancel(CALLER, "b10");

        // No StopTask issued — build was already terminal
        verify(ecs, never()).stopTask(any(java.util.function.Consumer.class));
        assertThat(status).isPresent();
        assertThat(status.get().state()).isEqualTo(BuildState.SUCCEEDED);
    }

    // ── heartbeat() ──────────────────────────────────────────────────────────────────────────

    @Test
    void heartbeat_updates_lastHeartbeatAt() {
        BuildRecord record = runningBuild("b11", List.of("NATIVE/X86_64"));
        Instant before = record.getLastHeartbeatAt();
        when(repository.findOwned("b11", CALLER.ownerKey())).thenReturn(Optional.of(record));

        service.heartbeat(CALLER, "b11");

        // heartbeat() calls put() once with the updated record
        ArgumentCaptor<BuildRecord> saved = ArgumentCaptor.forClass(BuildRecord.class);
        verify(repository, times(1)).put(saved.capture());
        assertThat(saved.getValue().getLastHeartbeatAt()).isAfterOrEqualTo(before);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private static BuildRecord stagedBuild(String id, List<String> cells) {
        return buildRecord(id, BuildState.STAGED, CellState.PENDING, cells);
    }

    private static BuildRecord runningBuild(String id, List<String> cells) {
        return buildRecord(id, BuildState.RUNNING, CellState.RUNNING, cells);
    }

    private static BuildRecord succeededBuild(String id, List<String> cells) {
        return buildRecord(id, BuildState.SUCCEEDED, CellState.SUCCEEDED, cells);
    }

    private static BuildRecord buildRecord(String id, BuildState state, CellState cellState,
                                           List<String> cellIds) {
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
        record.setBuildSpec(new BuildSpec(List.of("native"), List.of("x86_64"), null, "app", null, List.of(), List.of(), 0, 0));
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
