/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.service;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ArtifactDescriptor;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ArtifactDownload;
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
import ai.codriverlabs.scaleoutbuild.ecs.CellHandle;
import ai.codriverlabs.scaleoutbuild.ecs.CellLaunchException;
import ai.codriverlabs.scaleoutbuild.ecs.CellLaunchRequest;
import ai.codriverlabs.scaleoutbuild.ecs.CellLauncher;
import ai.codriverlabs.scaleoutbuild.ecs.CellPollResult;
import ai.codriverlabs.scaleoutbuild.ecs.EcsClusterSettings;
import ai.codriverlabs.scaleoutbuild.ecs.FargateCapacityStrategy;
import ai.codriverlabs.scaleoutbuild.ecs.EcsLaunchType;
import ai.codriverlabs.scaleoutbuild.ecs.FargateCellLauncher;
import ai.codriverlabs.scaleoutbuild.ecs.MicroVmCellLauncher;
import ai.codriverlabs.scaleoutbuild.ecs.MicroVmSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.lambdamicrovms.LambdaMicrovmsClient;
import software.amazon.awssdk.services.s3.S3Client;

/** Build lifecycle: create, start, observe, cancel. Every method is ownership-scoped. */
@ApplicationScoped
public class BuildService {

    /**
     * Exit code coreutils {@code timeout} uses for "deadline exceeded", produced by the agent image's
     * entrypoint wrapper. {@code AgentMain} never exits with it, so it is unambiguous.
     */
    private static final int CONTAINER_TIMEOUT_EXIT_CODE = 124;

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
    private final LambdaMicrovmsClient microvms;
    private final S3Client s3Client;

    @Inject
    public BuildService(BuildRepository repository, StagingService staging, ResourcePolicy policy,
                        ControlPlaneConfig config, EcsClient ecs, LambdaMicrovmsClient microvms,
                        S3Client s3Client) {
        this.repository = repository;
        this.staging = staging;
        this.policy = policy;
        this.config = config;
        this.ecs = ecs;
        this.microvms = microvms;
        this.s3Client = s3Client;
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
        policy.validateBackendPreference(spec.backendPreference(), spec.architectures());
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
                String stagingRelativePath = staging.stagingPath(record.getOwnerKey(),
                        record.getBuildId(), buildKind, architecture);
                Map<String, String> agentEnvironment = AgentEnvironment
                        .builder(record.getBuildId(), buildKind, architecture, stagingRelativePath,
                                ai.codriverlabs.scaleoutbuild.build.StagingLayout.DEFAULT_ARGS_FILE_NAME)
                        .s3Bucket(config.ecs().agentUsesDirectS3Io() ? config.stagingBucket() : null)
                        /*
                         * imageName is legitimately absent for a pass-through build.
                         *
                         * In QUARKUS_NATIVE_SOURCES mode the argfile Quarkus generated already names its
                         * own output (-o <finalName>-runner), so the client sends no imageName and the
                         * agent discovers artifacts by scanning the output directory afterwards --
                         * exactly as NativeImageInputPlanner.planFromNativeSources documents when it
                         * returns an empty expectedArtifacts list.
                         *
                         * List.of(null) throws NullPointerException, so this previously failed every
                         * Quarkus build at launch with a bare NPE and "launch failed: null" on the
                         * client. Found by running a real 233-jar Quarkus application through the
                         * service; the two-jar example app always sets imageName, so nothing caught it.
                         */
                        .expectedArtifacts(record.getBuildSpec().imageName() == null
                                ? List.of() : List.of(record.getBuildSpec().imageName()))
                        .extraNativeImageArgs(record.getBuildSpec().extraNativeImageArgs())
                        // Substitutes the server default for 0 -- which BuildSpec documented and nothing
                        // implemented -- and clamps what a client may ask for, the way every other
                        // requested dimension already was. Without the clamp the container backstop
                        // inherits an arbitrary client value and the only ceiling left is the reaper.
                        .timeoutMinutes(policy.resolveCellTimeoutMinutes(
                                record.getBuildSpec().timeoutMinutes()))
                        .build().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                software.amazon.awssdk.services.ecs.model.KeyValuePair::name,
                                software.amazon.awssdk.services.ecs.model.KeyValuePair::value,
                                (a, b) -> b, java.util.LinkedHashMap::new));

                CellLaunchRequest request = new CellLaunchRequest(clusterSettings, containerSettings,
                        record.getBuildId(), buildKind, architecture, stagingRelativePath,
                        agentEnvironment);
                CellHandle handle = cellLauncher(architecture, record.getBuildSpec().backendPreference())
                        .launch(request);
                applyHandle(cell, handle);
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

    /**
     * Returns the {@link CellLauncher} that should launch/poll/stop {@code architecture}'s cells
     * for this build, honoring {@code backendPreference} ({@code BuildSpec.backendPreference()},
     * already validated by {@code ResourcePolicy.validateBackendPreference} at build-creation
     * time — this method trusts that validation and does not re-check it).
     *
     * <p><b>{@code fargate}</b>: forces Fargate for every architecture, including {@code arm64}.
     * <b>{@code microvm}</b>: forces {@link MicroVmCellLauncher} — reaching this branch for an
     * architecture or deployment that cannot actually satisfy it would be
     * {@code validateBackendPreference}'s own bug, not a condition this method re-defends against.
     * <b>{@code auto}</b> (the default — see {@code BuildSpec.BACKEND_AUTO}): the original,
     * pre-{@code backendPreference} behavior this phase shipped with — {@code ARM64} goes to
     * {@link MicroVmCellLauncher} only when {@code scaleout.micro-vm.enabled} is set and the
     * required MicroVM settings are present; every other case uses {@link FargateCellLauncher}.
     * See {@code docs/design/control-plane/microvm-build-backend-phase1.md}'s "Backend selection"
     * section for why {@code auto} alone was judged insufficient and this field was added: an
     * availability toggle ("can this deployment use MicroVM at all") and a placement choice
     * ("should this particular build use it") are different questions, and real pricing data
     * (MicroVM's per-unit rates run roughly 3–3.7x Fargate's for a sustained, saturating workload
     * like {@code native-image}) means a developer who knows their own build's shape needs a real
     * way to answer the second question themselves, not just inherit the deployment's default.
     *
     * <p>{@code vpcEgressConnectorArn} is deliberately not in the {@code auto} gate's required
     * set — see {@code MicroVmSettings}' own javadoc for why it is genuinely optional, not just
     * unenforced.
     */
    private CellLauncher cellLauncher(Architecture architecture, String backendPreference) {
        String preference = backendPreference == null ? BuildSpec.BACKEND_AUTO
                : backendPreference.toLowerCase(java.util.Locale.ROOT);
        if ("fargate".equals(preference)) {
            return new FargateCellLauncher(ecs, clusterSettings());
        }
        if ("microvm".equals(preference)) {
            return new MicroVmCellLauncher(microvms, s3Client, microVmSettingsFromConfig(),
                    config.stagingBucket());
        }
        // auto: unchanged from before backendPreference existed.
        if (architecture == Architecture.ARM64) {
            var microVm = config.microVm();
            if (microVm.enabled() && microVm.imageArn().isPresent() && microVm.imageVersion().isPresent()
                    && microVm.executionRoleArn().isPresent()) {
                return new MicroVmCellLauncher(microvms, s3Client, microVmSettingsFromConfig(),
                        config.stagingBucket());
            }
        }
        return new FargateCellLauncher(ecs, clusterSettings());
    }

    /**
     * Builds {@link MicroVmSettings} from the live deployment config. Shared by every call site
     * that needs a {@link MicroVmCellLauncher} — {@link #cellLauncher}'s {@code auto} and
     * {@code microvm} branches, and {@link #microVmLauncherFor} — so the same construction is not
     * repeated three times with the risk of one copy drifting from the others.
     */
    private MicroVmSettings microVmSettingsFromConfig() {
        var microVm = config.microVm();
        return new MicroVmSettings(microVm.imageArn().orElse("unused"),
                microVm.imageVersion().orElse("unused"), microVm.executionRoleArn().orElse("unused"),
                microVm.vpcEgressConnectorArn().orElse(null), config.ecs().logGroupName());
    }

    /** Applies a freshly launched cell's handle to its persisted record — the one place this
     * module's {@code CellHandle} vocabulary is translated into {@code BuildRecord.CellRecord}'s
     * stored fields. */
    private static void applyHandle(BuildRecord.CellRecord cell, CellHandle handle) {
        if (handle instanceof CellHandle.FargateHandle h) {
            cell.setBackend("FARGATE");
            cell.setTaskArn(h.taskArn());
        } else if (handle instanceof CellHandle.MicroVmHandle h) {
            cell.setBackend("MICROVM");
            cell.setMicrovmId(h.microvmId());
            cell.setMicrovmEndpoint(h.endpoint());
        }
    }

    // --- Observe, cancel, heartbeat -----------------------------------------------------------

    public Optional<BuildStatus> status(CallerIdentity caller, String buildId) {
        return repository.findOwned(buildId, caller.ownerKey())
                .map(this::refreshCellStates)
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
     * Stops every non-terminal cell, dispatching by each cell's **stored** {@code backend} field
     * (set at launch time by {@link #applyHandle}), not current config — a config flip mid-build
     * must not change how an in-flight build's cells are stopped. Only returns once a stop call has
     * been issued for each; fire-and-forget would leave paid-for Fargate tasks or MicroVMs running.
     */
    void stopRunningCells(BuildRecord record, String reason) {
        FargateCellLauncher fargateLauncher = new FargateCellLauncher(ecs, clusterSettings());
        for (BuildRecord.CellRecord cell : record.getCells()) {
            if (cell.getState().isTerminal() || cellHandleOf(cell).isEmpty()) {
                cell.setState(CellState.CANCELLED);
                continue;
            }
            CellHandle handle = cellHandleOf(cell).get();
            CellLauncher launcher = handle instanceof CellHandle.MicroVmHandle
                    ? microVmLauncherFor(record) : fargateLauncher;
            try {
                launcher.stop(handle, reason);
            } catch (RuntimeException e) {
                // Already stopped, or transient. Recording the intent matters more than the call.
                LOG.warnf("Stop failed for %s (%s): %s", cell.getCell(), handle.describe(),
                        e.getMessage());
            }
            cell.setState(CellState.CANCELLED);
        }
    }

    /** The {@code MicroVmCellLauncher} used to stop an already-launched MicroVM cell. Does not
     * re-check {@code config.microVm().enabled()}: a cell that was launched as MICROVM must be
     * stoppable via the MicroVM API regardless of what current config says — only the identity of
     * the image/role/connector used at launch time is irrelevant to issuing TerminateMicrovm, which
     * only needs the microvm id. */
    private CellLauncher microVmLauncherFor(BuildRecord record) {
        return new MicroVmCellLauncher(microvms, s3Client, microVmSettingsFromConfig(),
                config.stagingBucket());
    }

    /** Reconstructs this module's {@code CellHandle} from a persisted {@code CellRecord}, or empty
     * if the cell was never launched (no handle fields set). */
    private static Optional<CellHandle> cellHandleOf(BuildRecord.CellRecord cell) {
        if ("MICROVM".equals(cell.getBackend())) {
            return cell.getMicrovmId() == null ? Optional.empty()
                    : Optional.of(new CellHandle.MicroVmHandle(cell.getMicrovmId(),
                            cell.getMicrovmEndpoint(), null));
        }
        return cell.getTaskArn() == null ? Optional.empty()
                : Optional.of(new CellHandle.FargateHandle(cell.getTaskArn()));
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
                List<ArtifactDownload> downloads = new ArrayList<>();
                if (cell.getState() == CellState.SUCCEEDED) {
                    BuildKind buildKind = BuildKind.parse(cell.getCell().split("/")[0]);
                    Architecture architecture = Architecture.parse(cell.getCell().split("/")[1]);
                    String prefix = staging.outputPrefix(record.getOwnerKey(), record.getBuildId(), buildKind, architecture);
                    /*
                     * Listed from S3 rather than read from the build record. The record only knows an
                     * artifact name when the client supplied imageName, which a framework-generated
                     * argfile never does -- it names its own output. Listing also reports what was
                     * actually uploaded rather than what was expected, which is the more useful answer
                     * either way. Falls back to the record if the listing is empty, so a transient S3
                     * read does not silently report a successful build as artifact-less.
                     */
                    List<String> names = staging.listOutputNames(record.getOwnerKey(),
                            record.getBuildId(), buildKind, architecture);
                    if (names.isEmpty()) {
                        names = cell.getArtifactPaths();
                    }
                    for (String artifact : names) {
                        String key = prefix.endsWith("/") ? prefix + artifact : prefix + "/" + artifact;
                        UploadTarget target = staging.presignDownload(key, null);
                        downloads.add(new ArtifactDownload(artifact, target.url(), target.expiresAt()));
                    }
                }
                cells.add(new ArtifactListResponse.CellArtifacts(cell.getCell(), downloads));
            }
            return new ArtifactListResponse(record.getBuildId(), cells);
        });
    }

    // --- ECS reconciliation -------------------------------------------------------------------

    /**
     * Refreshes non-terminal cells from their respective backends. Neither backend is pushed to —
     * this service holds no supervision loop, so state is pulled on read.
     *
     * <p>Dispatches by each cell's **stored** {@code backend} field, not current config (same
     * reasoning as {@link #stopRunningCells}). Fargate cells are polled in one batched
     * {@code DescribeTasks} call via {@link FargateCellLauncher#pollBatch}, unchanged from before
     * this method had a MicroVM counterpart. MicroVM cells are polled one
     * {@code GetMicrovm}/{@code status.json}-read per cell — there is no batch {@code GetMicrovm}
     * in the SDK (confirmed absent via {@code javap} while writing {@code MicroVmCellLauncher}),
     * which is acceptable because the matrix is small (typically 1–2 MicroVM cells per build).
     */
    public BuildRecord refreshCellStates(BuildRecord record) {
        List<BuildRecord.CellRecord> fargateCells = new ArrayList<>();
        List<BuildRecord.CellRecord> microVmCells = new ArrayList<>();
        for (BuildRecord.CellRecord cell : record.getCells()) {
            if (cell.getState().isTerminal()) {
                continue;
            }
            if ("MICROVM".equals(cell.getBackend())) {
                if (cell.getMicrovmId() != null) {
                    microVmCells.add(cell);
                }
            } else if (cell.getTaskArn() != null) {
                fargateCells.add(cell);
            }
        }
        if (fargateCells.isEmpty() && microVmCells.isEmpty()) {
            return record;
        }

        boolean changed = false;

        if (!fargateCells.isEmpty()) {
            FargateCellLauncher fargateLauncher = new FargateCellLauncher(ecs, clusterSettings());
            Map<String, CellPollResult> results = fargateLauncher.pollBatch(
                    fargateCells.stream().map(BuildRecord.CellRecord::getTaskArn).toList());
            for (BuildRecord.CellRecord cell : fargateCells) {
                CellPollResult result = results.get(cell.getTaskArn());
                if (result != null) {
                    changed |= applyPollResult(record, cell, result);
                }
            }
        }

        if (!microVmCells.isEmpty()) {
            CellLauncher microVmLauncher = microVmLauncherFor(record);
            for (BuildRecord.CellRecord cell : microVmCells) {
                CellHandle handle = new CellHandle.MicroVmHandle(cell.getMicrovmId(),
                        cell.getMicrovmEndpoint(), stagingPathFor(record, cell));
                CellPollResult result = microVmLauncher.poll(handle);
                changed |= applyPollResult(record, cell, result);
            }
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

    /** The staging path a cell was launched with, recomputed from {@code StagingService} rather
     * than stored on {@code CellRecord} — {@code StagingService.stagingPath} is a pure function of
     * owner/build/kind/architecture, so recomputing it here needs no new persisted field. */
    private String stagingPathFor(BuildRecord record, BuildRecord.CellRecord cell) {
        BuildKind buildKind = BuildKind.parse(cell.getCell().split("/")[0]);
        Architecture architecture = Architecture.parse(cell.getCell().split("/")[1]);
        return staging.stagingPath(record.getOwnerKey(), record.getBuildId(), buildKind, architecture);
    }

    /**
     * Maps this module's {@code CellLaunchState} onto the control plane's own {@code CellState} —
     * the one boundary allowed to depend on both vocabularies, same as before this method existed
     * (see {@code CellPollResult}'s own javadoc for why {@code scaleout-build-ecs} itself must not).
     *
     * @return whether the cell's state actually changed
     */
    private boolean applyPollResult(BuildRecord record, BuildRecord.CellRecord cell,
                                    CellPollResult result) {
        CellState previous = cell.getState();
        switch (result.state()) {
            case RUNNING -> cell.setState(CellState.RUNNING);
            case PROVISIONING -> cell.setState(CellState.PROVISIONING);
            case SUCCEEDED -> {
                cell.setExitCode(result.exitCode());
                cell.setState(CellState.SUCCEEDED);
                /*
                 * imageName is absent for a pass-through build; see the identical comment at the
                 * other call site in start() for why List.of(null) must never be attempted here.
                 */
                if (cell.getArtifactPaths().isEmpty() && record.getBuildSpec().imageName() != null) {
                    cell.setArtifactPaths(new ArrayList<>(List.of(record.getBuildSpec().imageName())));
                }
            }
            case FAILED -> {
                cell.setExitCode(result.exitCode());
                cell.setState(CellState.FAILED);
                /*
                 * 124 is coreutils `timeout` reporting that the agent image's entrypoint wrapper hit
                 * its deadline -- the container-wide backstop, not the inner native-image budget.
                 * Without translating it the developer sees "exit 124" and an empty failure reason,
                 * which names neither the cause nor the setting that controls it. `timeout` reports
                 * 124 whether the process took SIGTERM or had to be SIGKILLed after the grace
                 * period, so this covers both. Fargate-only: a MicroVM has no equivalent wrapper, so
                 * its own CellPollResult.failed() reasons are passed through unchanged below.
                 */
                cell.setFailureReason(result.exitCode() != null
                        && result.exitCode() == CONTAINER_TIMEOUT_EXIT_CODE
                        ? "the build container exceeded its overall timeout. The compile itself has"
                                + " a separate, shorter budget, so this usually means a stall in"
                                + " staging download or artifact upload rather than a slow compile."
                                + " Raise it with -Dscaleout-build.timeoutMinutes."
                        : result.failureReason());
            }
        }
        return previous != cell.getState();
    }

    private EcsClusterSettings clusterSettings() {
        var ecsConfig = config.ecs();
        return new EcsClusterSettings(
                EcsLaunchType.valueOf(ecsConfig.launchType().toUpperCase(Locale.ROOT)),
                ecsConfig.clusterArn(), ecsConfig.subnetIds(), ecsConfig.securityGroupIds(),
                ecsConfig.assignPublicIp(), ecsConfig.executionRoleArn(), ecsConfig.taskRoleArn(),
                null, null, null, null, ecsConfig.capacityProviderName().orElse(null),
                ecsConfig.logGroupName(), regionOf(ecsConfig.clusterArn()),
                ecsConfig.agentUsesDirectS3Io(),
                // Parsed per call rather than cached: cheap, and it means a bad value surfaces as a
                // named error on the first build instead of a startup failure with no build to blame.
                FargateCapacityStrategy.parse(ecsConfig.fargateCapacityStrategy()));
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
