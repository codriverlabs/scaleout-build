/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.service;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ArtifactDescriptor;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ArtifactListResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildSpec;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CellState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CellStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildRequest;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.InputDescriptor;
import ai.codriverlabs.scaleoutbuild.controlplane.api.RequestedResources;
import ai.codriverlabs.scaleoutbuild.controlplane.api.UploadTarget;
import ai.codriverlabs.scaleoutbuild.controlplane.auth.CallerIdentity;
import ai.codriverlabs.scaleoutbuild.controlplane.config.ControlPlaneConfig;
import ai.codriverlabs.scaleoutbuild.controlplane.store.BuildRecord;
import ai.codriverlabs.scaleoutbuild.controlplane.store.BuildRepository;
import ai.codriverlabs.scaleoutbuild.ecs.AgentContainerSettings;
import ai.codriverlabs.scaleoutbuild.ecs.AgentEnvironment;
import ai.codriverlabs.scaleoutbuild.ecs.EcsClusterSettings;
import ai.codriverlabs.scaleoutbuild.ecs.EcsLaunchType;
import ai.codriverlabs.scaleoutbuild.ecs.EcsTaskLauncher;
import ai.codriverlabs.scaleoutbuild.ecs.TaskDefinitionRegistrar;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.ecs.EcsClient;

/** Build lifecycle: create, start, observe, cancel. Every method is ownership-scoped. */
@ApplicationScoped
public class BuildService {

    private static final Logger LOG = Logger.getLogger(BuildService.class);

    /*
     * An INSTANCE field, deliberately, not `private static final SecureRandom`.
     *
     * GraalVM runs static initializers at image *build* time, so a static SecureRandom would be
     * constructed during the build and baked into the image heap complete with its cached seed. Every
     * Lambda instance started from that image would then emit the same sequence of build-id suffixes.
     * native-image refuses to build rather than let that happen:
     *
     *   UnsupportedFeatureException: Detected an instance of Random/SplittableRandom class in the
     *   image heap. Instances created during image generation have cached seed values and don't behave
     *   as expected.
     *
     * That refusal is doing real work here. The build id is the DynamoDB table's partition key, so a
     * repeated suffix means two owners' builds can collide on the same item and overwrite each other's
     * records -- the per-owner GSI does not protect the base table. It is not an access-control issue,
     * because BuildRepository.findOwned() returns empty for "not yours" exactly as it does for
     * "absent", so a guessed id grants nothing; it is a correctness issue.
     *
     * This bean is @ApplicationScoped, so CDI constructs it at runtime and the field is seeded then.
     * The alternative fix -- passing --initialize-at-run-time for this class -- would work too, but it
     * puts the correctness guarantee in a build flag far away from the code that depends on it.
     */
    private final SecureRandom random = new SecureRandom();

    private final BuildRepository repository;
    private final StagingService staging;
    private final ResourcePolicy policy;
    private final ControlPlaneConfig config;
    private final EcsClient ecs;

    @Inject
    public BuildService(BuildRepository repository, StagingService staging, ResourcePolicy policy,
                        ControlPlaneConfig config, EcsClient ecs) {
        this.repository = repository;
        this.staging = staging;
        this.policy = policy;
        this.config = config;
        this.ecs = ecs;
    }

    // --- Create -------------------------------------------------------------------------------

    public CreateBuildResponse create(CallerIdentity caller, CreateBuildRequest request)
            throws InvalidRequestException {
        BuildSpec spec = request.buildSpec();
        if (spec == null || spec.buildKinds().isEmpty() || spec.architectures().isEmpty()) {
            throw new InvalidRequestException("buildSpec must name at least one buildKind and architecture");
        }
        for (String kind : spec.buildKinds()) {
            policy.validateBuildKind(kind);
        }
        for (String arch : spec.architectures()) {
            policy.validateArchitecture(arch);
        }
        if (request.inputs().isEmpty()) {
            throw new InvalidRequestException("inputs must not be empty");
        }

        long active = repository.countActiveByOwner(caller.ownerKey());
        int max = config.limits().maxConcurrentBuildsPerOwner();
        if (active >= max) {
            throw new InvalidRequestException("concurrency limit reached: " + active + " of " + max
                    + " builds already running for this caller");
        }

        RequestedResources applied = policy.apply(request.requestedResources());
        int timeout = policy.clampOverallTimeoutMinutes(spec.overallTimeoutMinutes());

        BuildRecord record = new BuildRecord();
        record.setBuildId(newBuildId());
        record.setOwnerKey(caller.ownerKey());
        record.setOwnerArn(caller.roleArn());
        record.setOwnerPerHuman(caller.perHuman());
        record.setState(BuildState.PENDING);
        record.setBuildSpec(spec);
        record.setInputs(request.inputs());
        record.setAppliedCpu(applied.cpu());
        record.setAppliedMemory(applied.memory());
        record.setAppliedEphemeralStorageGiB(applied.ephemeralStorageGiB());
        record.setOverallTimeoutMinutes(timeout);
        record.setCells(resolveCells(spec));
        Instant now = Instant.now();
        record.setCreatedAt(now);
        record.setLastHeartbeatAt(now);
        record.setExpiresAt(now.plus(Duration.ofMinutes(timeout)));
        // Retained well past the build so a developer can still inspect a failure the next morning.
        record.setTtl(now.plus(Duration.ofDays(14)).getEpochSecond());

        List<String> missing = staging.missingDigests(caller.ownerKey(), request.inputs());
        List<UploadTarget> uploads = new ArrayList<>();
        for (String digest : missing) {
            uploads.add(staging.presignUpload(caller.ownerKey(), digest));
        }
        List<String> alreadyStaged = request.inputs().stream()
                .map(InputDescriptor::sha256)
                .distinct()
                .filter(d -> !missing.contains(d))
                .toList();

        if (missing.isEmpty()) {
            record.setState(BuildState.STAGED);
        }
        repository.put(record);
        LOG.infof("Created build %s for %s (%d uploads needed, %d already staged)",
                record.getBuildId(), caller.ownerKey(), uploads.size(), alreadyStaged.size());

        return new CreateBuildResponse(record.getBuildId(), record.getState(),
                toCellStatuses(record), uploads, alreadyStaged, applied,
                config.limits().heartbeatIntervalSeconds(), record.getExpiresAt());
    }

    /**
     * Time-ordered identifier. Not a UUID: builds are listed newest-first straight off the GSI sort
     * key, which requires the id itself to sort chronologically.
     */
    private String newBuildId() {
        byte[] entropy = new byte[8];
        random.nextBytes(entropy);
        StringBuilder suffix = new StringBuilder();
        for (byte b : entropy) {
            suffix.append(String.format("%02x", b));
        }
        return String.format(Locale.ROOT, "%013d-%s", Instant.now().toEpochMilli(), suffix);
    }

    private List<BuildRecord.CellRecord> resolveCells(BuildSpec spec) {
        List<BuildRecord.CellRecord> cells = new ArrayList<>();
        for (String kind : spec.buildKinds()) {
            BuildKind buildKind = BuildKind.parse(kind);
            for (String arch : spec.architectures()) {
                BuildRecord.CellRecord cell = new BuildRecord.CellRecord();
                cell.setCell(buildKind + "/" + Architecture.parse(arch));
                cell.setState(CellState.PENDING);
                cells.add(cell);
            }
        }
        return cells;
    }

    // --- Start --------------------------------------------------------------------------------

    public Optional<BuildStatus> start(CallerIdentity caller, String buildId)
            throws InvalidRequestException {
        Optional<BuildRecord> found = repository.findOwned(buildId, caller.ownerKey());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BuildRecord record = found.get();
        if (record.getState() != BuildState.PENDING && record.getState() != BuildState.STAGED) {
            throw new InvalidRequestException("build is " + record.getState()
                    + "; only PENDING or STAGED builds can be started");
        }

        List<String> stillMissing = staging.missingDigests(record.getOwnerKey(), record.getInputs());
        if (!stillMissing.isEmpty()) {
            throw new InvalidRequestException(
                    "inputs not yet staged: " + String.join(", ", stillMissing));
        }

        BuildState expected = record.getState();
        EcsClusterSettings clusterSettings = clusterSettings();
        AgentContainerSettings containerSettings = new AgentContainerSettings(
                config.ecs().agentImage(), record.getAppliedCpu(), record.getAppliedMemory(),
                record.getAppliedEphemeralStorageGiB());
        var registrar = new TaskDefinitionRegistrar(ecs);
        var launcher = new EcsTaskLauncher(ecs);

        record.setState(BuildState.RUNNING);
        record.setStartedAt(Instant.now());
        // Claim the transition before launching anything, so two concurrent start calls cannot both
        // launch the matrix. Losing the race is not an error for the caller -- the build is running.
        if (!repository.putIfStateIs(record, expected)) {
            return repository.findOwned(buildId, caller.ownerKey()).map(this::toStatus);
        }

        for (BuildRecord.CellRecord cell : record.getCells()) {
            BuildKind buildKind = BuildKind.parse(cell.getCell().split("/")[0]);
            Architecture architecture = Architecture.parse(cell.getCell().split("/")[1]);
            try {
                staging.materializeCell(record.getOwnerKey(), record.getBuildId(), buildKind,
                        architecture, record.getInputs());
                String taskDefinitionArn = registrar.registerIfChanged(clusterSettings,
                        containerSettings, buildKind, architecture);
                var environment = AgentEnvironment
                        .builder(record.getBuildId(), buildKind, architecture,
                                staging.stagingPath(record.getOwnerKey(), record.getBuildId(), buildKind, architecture),
                                ai.codriverlabs.scaleoutbuild.build.StagingLayout.DEFAULT_ARGS_FILE_NAME)
                        .s3Bucket(config.ecs().agentUsesDirectS3Io() ? config.stagingBucket() : null)
                        .expectedArtifacts(List.of(record.getBuildSpec().imageName()))
                        .extraNativeImageArgs(record.getBuildSpec().extraNativeImageArgs())
                        .timeoutMinutes(record.getBuildSpec().timeoutMinutes())
                        .build();
                String taskArn = launcher.runTask(clusterSettings, taskDefinitionArn, environment,
                        false);
                cell.setTaskDefinitionArn(taskDefinitionArn);
                cell.setTaskArn(taskArn);
                cell.setState(CellState.PROVISIONING);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to launch %s for build %s", cell.getCell(),
                        record.getBuildId());
                cell.setState(CellState.FAILED);
                cell.setFailureReason("launch failed: " + e.getMessage());
            }
        }
        if (record.allCellsTerminal()) {
            record.setState(BuildState.FAILED);
        }
        repository.put(record);
        return Optional.of(toStatus(record));
    }

    // --- Observe, cancel, heartbeat -----------------------------------------------------------

    public Optional<BuildStatus> status(CallerIdentity caller, String buildId) {
        return repository.findOwned(buildId, caller.ownerKey())
                .map(this::refreshFromEcs)
                .map(this::toStatus);
    }

    public List<BuildStatus> listMine(CallerIdentity caller, int limit) {
        return repository.listByOwner(caller.ownerKey(), limit).stream().map(this::toStatus).toList();
    }

    /** Idempotent: cancelling a terminal build returns its existing state rather than failing. */
    public Optional<BuildStatus> cancel(CallerIdentity caller, String buildId) {
        Optional<BuildRecord> found = repository.findOwned(buildId, caller.ownerKey());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BuildRecord record = found.get();
        if (record.getState().isTerminal()) {
            return Optional.of(toStatus(record));
        }
        stopRunningCells(record, "cancelled by " + caller.ownerKey());
        record.setState(BuildState.CANCELLED);
        repository.put(record);
        return Optional.of(toStatus(record));
    }

    /**
     * Stops every non-terminal cell's task, and only returns once StopTask has been issued for each.
     * Fire-and-forget would leave paid-for Fargate tasks running.
     */
    void stopRunningCells(BuildRecord record, String reason) {
        var launcher = new EcsTaskLauncher(ecs);
        EcsClusterSettings clusterSettings = clusterSettings();
        for (BuildRecord.CellRecord cell : record.getCells()) {
            if (cell.getState().isTerminal() || cell.getTaskArn() == null) {
                cell.setState(CellState.CANCELLED);
                continue;
            }
            try {
                launcher.stopTask(clusterSettings, cell.getTaskArn(), reason);
            } catch (RuntimeException e) {
                // Already stopped, or transient. Recording the intent matters more than the call.
                LOG.warnf("StopTask failed for %s (%s): %s", cell.getCell(), cell.getTaskArn(),
                        e.getMessage());
            }
            cell.setState(CellState.CANCELLED);
        }
    }

    public Optional<BuildStatus> heartbeat(CallerIdentity caller, String buildId) {
        return repository.findOwned(buildId, caller.ownerKey()).map(record -> {
            record.setLastHeartbeatAt(Instant.now());
            repository.put(record);
            return toStatus(record);
        });
    }

    public Optional<ArtifactListResponse> artifacts(CallerIdentity caller, String buildId) {
        return repository.findOwned(buildId, caller.ownerKey()).map(record -> {
            List<ArtifactListResponse.CellArtifacts> cells = new ArrayList<>();
            for (BuildRecord.CellRecord cell : record.getCells()) {
                List<UploadTarget> downloads = new ArrayList<>();
                if (cell.getState() == CellState.SUCCEEDED) {
                    BuildKind buildKind = BuildKind.parse(cell.getCell().split("/")[0]);
                    Architecture architecture = Architecture.parse(cell.getCell().split("/")[1]);
                    String prefix = staging.outputPrefix(record.getOwnerKey(), record.getBuildId(), buildKind, architecture);
                    for (String artifact : cell.getArtifactPaths()) {
                        String key = prefix.endsWith("/") ? prefix + artifact : prefix + "/" + artifact;
                        downloads.add(staging.presignDownload(key, null));
                    }
                }
                cells.add(new ArtifactListResponse.CellArtifacts(cell.getCell(), downloads));
            }
            return new ArtifactListResponse(record.getBuildId(), cells);
        });
    }

    // --- ECS reconciliation -------------------------------------------------------------------

    /**
     * Refreshes non-terminal cells from ECS. DescribeTasks is the source of truth: this service does
     * not hold a supervision loop the way the plugin's direct backend does, so state is pulled on
     * read rather than pushed.
     */
    public BuildRecord refreshFromEcs(BuildRecord record) {
        List<String> taskArns = record.getCells().stream()
                .filter(c -> !c.getState().isTerminal() && c.getTaskArn() != null)
                .map(BuildRecord.CellRecord::getTaskArn)
                .toList();
        if (taskArns.isEmpty()) {
            return record;
        }
        var response = ecs.describeTasks(b -> b.cluster(config.ecs().clusterArn()).tasks(taskArns));
        boolean changed = false;
        for (var task : response.tasks()) {
            BuildRecord.CellRecord cell = record.getCells().stream()
                    .filter(c -> task.taskArn().equals(c.getTaskArn()))
                    .findFirst().orElse(null);
            if (cell == null) {
                continue;
            }
            CellState previous = cell.getState();
            switch (task.lastStatus()) {
                case "RUNNING" -> cell.setState(CellState.RUNNING);
                case "STOPPED" -> {
                    Integer exitCode = task.containers().isEmpty() ? null
                            : task.containers().get(0).exitCode();
                    cell.setExitCode(exitCode);
                    if (exitCode != null && exitCode == 0) {
                        cell.setState(CellState.SUCCEEDED);
                        if (cell.getArtifactPaths().isEmpty()) {
                            cell.setArtifactPaths(
                                    new ArrayList<>(List.of(record.getBuildSpec().imageName())));
                        }
                    } else {
                        cell.setState(CellState.FAILED);
                        cell.setFailureReason(task.stoppedReason());
                    }
                }
                default -> cell.setState(CellState.PROVISIONING);
            }
            changed |= previous != cell.getState();
        }
        if (changed) {
            if (record.allCellsTerminal()) {
                boolean anyFailed = record.getCells().stream()
                        .anyMatch(c -> c.getState() == CellState.FAILED);
                record.setState(anyFailed ? BuildState.FAILED : BuildState.SUCCEEDED);
            }
            repository.put(record);
        }
        return record;
    }

    private EcsClusterSettings clusterSettings() {
        var ecsConfig = config.ecs();
        return new EcsClusterSettings(
                EcsLaunchType.valueOf(ecsConfig.launchType().toUpperCase(Locale.ROOT)),
                ecsConfig.clusterArn(), ecsConfig.subnetIds(), ecsConfig.securityGroupIds(),
                ecsConfig.assignPublicIp(), ecsConfig.executionRoleArn(), ecsConfig.taskRoleArn(),
                null, null, null, null, ecsConfig.capacityProviderName().orElse(null),
                ecsConfig.logGroupName(), regionOf(ecsConfig.clusterArn()),
                ecsConfig.agentUsesDirectS3Io());
    }

    /** ECS cluster ARNs are {@code arn:aws:ecs:<region>:<account>:cluster/<name>}. */
    private static String regionOf(String clusterArn) {
        String[] parts = clusterArn.split(":");
        return parts.length > 3 ? parts[3] : "us-east-1";
    }

    private List<CellStatus> toCellStatuses(BuildRecord record) {
        return record.getCells().stream()
                .map(c -> new CellStatus(c.getCell(), c.getState(), c.getTaskArn(), c.getExitCode(),
                        c.getFailureReason(), c.getSpotInterruptions(),
                        c.getArtifactPaths().stream()
                                .map(p -> new ArtifactDescriptor(p, null, 0L)).toList()))
                .toList();
    }

    BuildStatus toStatus(BuildRecord record) {
        return new BuildStatus(record.getBuildId(), record.getState(), record.getOwnerArn(),
                toCellStatuses(record), record.getCreatedAt(), record.getStartedAt(),
                record.getUpdatedAt(), record.getExpiresAt());
    }
}
