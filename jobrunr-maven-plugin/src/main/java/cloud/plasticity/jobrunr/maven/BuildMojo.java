/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven;

import cloud.plasticity.jobrunr.build.Architecture;
import cloud.plasticity.jobrunr.build.ArtifactCollector;
import cloud.plasticity.jobrunr.build.BuildCellRequest;
import cloud.plasticity.jobrunr.build.BuildEnvironment;
import cloud.plasticity.jobrunr.build.BuildExecutor;
import cloud.plasticity.jobrunr.build.BuildFailedException;
import cloud.plasticity.jobrunr.build.BuildKind;
import cloud.plasticity.jobrunr.build.BuildResult;
import cloud.plasticity.jobrunr.build.NativeImageBuildExecutor;
import cloud.plasticity.jobrunr.build.StagingLayout;
import cloud.plasticity.jobrunr.maven.ecs.AgentContainerSettings;
import cloud.plasticity.jobrunr.maven.ecs.EcsClusterSettings;
import cloud.plasticity.jobrunr.maven.ecs.TaskDefinitionRegistrar;
import cloud.plasticity.jobrunr.maven.planner.InputPlanningException;
import cloud.plasticity.jobrunr.maven.planner.NativeImageInputPlan;
import cloud.plasticity.jobrunr.maven.planner.NativeImageInputPlanner;
import cloud.plasticity.jobrunr.maven.planner.ProjectInputs;
import cloud.plasticity.jobrunr.maven.staging.LocalStagingSink;
import cloud.plasticity.jobrunr.maven.staging.S3ArtifactRetriever;
import cloud.plasticity.jobrunr.maven.staging.S3StagingSink;
import cloud.plasticity.jobrunr.maven.stepfunctions.BuildMatrixStateMachineDefinition;
import cloud.plasticity.jobrunr.maven.stepfunctions.StateMachineManager;
import cloud.plasticity.jobrunr.maven.stepfunctions.StepFunctionsExecutionSupervisor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sfn.SfnClient;

/**
 * {@code fargate:build} — computes a GraalVM build matrix and runs it, per {@code docs/DESIGN.md}.
 *
 * <p>Every requested cell whose architecture matches the host runs directly, in-process, via
 * {@link NativeImageBuildExecutor} — no AWS involved. Cells that don't match the host (and every
 * {@link BuildKind#JVM} cell needs no build at all — it is the project's already-packaged jar) are
 * batched into a single AWS Step Functions execution that fans out one Fargate Spot task per cell,
 * per the state machine defined in {@link BuildMatrixStateMachineDefinition}.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME)
public class BuildMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Component
    private MavenProjectHelper projectHelper;

    /**
     * Build kinds to produce: any of {@code jvm}, {@code native}, {@code native-pgo-instrument},
     * {@code native-pgo-optimize}. Defaults to {@code [native]}.
     */
    @Parameter(property = "fargate.buildKinds")
    private List<String> buildKinds;

    /** Target architectures for every non-JVM build kind. Defaults to the host architecture. */
    @Parameter(property = "fargate.architectures")
    private List<String> architectures;

    /**
     * Local path to a {@code .iprof} profile, required when {@code native-pgo-optimize} is one of
     * the requested build kinds. Per {@code docs/DESIGN.md} §3, collecting this profile (running an
     * instrumented binary against real traffic) is out of scope for this plugin.
     */
    @Parameter(property = "fargate.profilePath")
    private String profilePath;

    /** Explicit main class for derived-argfile builds; read from the artifact manifest if omitted. */
    @Parameter(property = "fargate.mainClass")
    private String mainClass;

    /** Explicit output binary name for derived-argfile builds; defaults to the project's final name. */
    @Parameter(property = "fargate.imageName")
    private String imageName;

    /** Extra {@code native-image} arguments appended for derived-argfile builds. */
    @Parameter
    private List<String> extraBuildArgs;

    /** Extra arguments appended to every invocation, after the argfile and build-kind flags. */
    @Parameter
    private List<String> extraNativeImageArgs;

    /** Command that invokes {@code native-image} for local-first cells, space-separated. */
    @Parameter(property = "fargate.nativeImageCommand", defaultValue = "native-image")
    private String nativeImageCommand;

    /** Directory local-first builds are staged into; defaults to {@code target/fargate-build}. */
    @Parameter(property = "fargate.workDirectory")
    private String workDirectory;

    /** Soft timeout applied to each {@code native-image} process; 0 disables it. */
    @Parameter(property = "fargate.timeoutMinutes", defaultValue = "0")
    private int timeoutMinutes;

    /** Overall time to wait for the remote matrix execution to finish before failing the goal. */
    @Parameter(property = "fargate.overallTimeoutMinutes", defaultValue = "120")
    private int overallTimeoutMinutes;

    /** Skips the goal entirely, for profiles that only want native builds on CI. */
    @Parameter(property = "fargate.skip", defaultValue = "false")
    private boolean skip;

    // --- Remote orchestration configuration; only required if any cell cannot run locally. ---

    @Parameter(property = "fargate.s3Bucket")
    private String s3Bucket;
    @Parameter(property = "fargate.clusterArn")
    private String clusterArn;
    @Parameter(property = "fargate.subnetIds")
    private List<String> subnetIds;
    @Parameter(property = "fargate.securityGroupIds")
    private List<String> securityGroupIds;
    @Parameter(property = "fargate.assignPublicIp", defaultValue = "false")
    private boolean assignPublicIp;
    @Parameter(property = "fargate.executionRoleArn")
    private String executionRoleArn;
    @Parameter(property = "fargate.taskRoleArn")
    private String taskRoleArn;
    @Parameter(property = "fargate.s3FilesFileSystemArn")
    private String s3FilesFileSystemArn;
    @Parameter(property = "fargate.s3FilesRootDirectory")
    private String s3FilesRootDirectory;
    @Parameter(property = "fargate.s3FilesAccessPointArn")
    private String s3FilesAccessPointArn;
    @Parameter(property = "fargate.logGroupName")
    private String logGroupName;
    @Parameter(property = "fargate.region")
    private String region;
    @Parameter(property = "fargate.agentImageUri")
    private String agentImageUri;
    @Parameter(property = "fargate.agentCpu", defaultValue = "4096")
    private String agentCpu;
    @Parameter(property = "fargate.agentMemory", defaultValue = "16384")
    private String agentMemory;
    @Parameter(property = "fargate.agentEphemeralStorageGiB", defaultValue = "0")
    private int agentEphemeralStorageGiB;
    @Parameter(property = "fargate.stateMachineExecutionRoleArn")
    private String stateMachineExecutionRoleArn;
    @Parameter(property = "fargate.stateMachineName", defaultValue = "jobrunr-build-matrix")
    private String stateMachineName;
    @Parameter(property = "fargate.maxAttemptsPerCell", defaultValue = "2")
    private int maxAttemptsPerCell;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("fargate:build skipped (fargate.skip=true)");
            return;
        }

        List<MatrixCell> cells = resolveMatrix();
        String buildId = UUID.randomUUID().toString();
        List<String> failures = new ArrayList<>();

        List<MatrixCell> jvmCells = cells.stream().filter(c -> c.buildKind == BuildKind.JVM).toList();
        List<MatrixCell> nativeishCells =
                cells.stream().filter(c -> c.buildKind != BuildKind.JVM).toList();

        for (int i = 0; i < jvmCells.size(); i++) {
            attachJvmArtifact();
        }

        if (!nativeishCells.isEmpty()) {
            NativeImageInputPlan plan = planInputs();
            getLog().info("Input plan: " + plan);

            List<MatrixCell> localCells =
                    nativeishCells.stream().filter(c -> c.architecture.matchesHost()).toList();
            List<MatrixCell> remoteCells =
                    nativeishCells.stream().filter(c -> !c.architecture.matchesHost()).toList();

            for (MatrixCell cell : localCells) {
                try {
                    runLocalCell(cell, plan, buildId);
                } catch (BuildFailedException e) {
                    failures.add(cell + ": " + e.getMessage());
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to stage build inputs for " + cell, e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MojoExecutionException("Interrupted while building " + cell, e);
                }
            }

            if (!remoteCells.isEmpty()) {
                try {
                    failures.addAll(runRemoteCells(remoteCells, plan, buildId));
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to stage remote build inputs", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MojoExecutionException("Interrupted while supervising the remote build "
                            + "matrix execution", e);
                }
            }
        }

        if (!failures.isEmpty()) {
            throw new MojoFailureException(
                    "fargate:build failed for " + failures.size() + " cell(s):\n"
                            + String.join("\n", failures));
        }
    }

    // --- Matrix computation ---------------------------------------------------------------

    /**
     * One matrix cell: a build kind, and (for every kind but JVM) a target architecture.
     *
     * <p>Package-visible (not {@code private}) so {@code BuildMojoRemoteCellsTest} can construct
     * cells directly to exercise {@link #runRemoteCells} without going through the full
     * {@code execute()} lifecycle.
     */
    record MatrixCell(BuildKind buildKind, Architecture architecture) {
        @Override
        public String toString() {
            return architecture == null ? buildKind.toString() : buildKind + "/" + architecture;
        }
    }

    private List<MatrixCell> resolveMatrix() throws MojoFailureException {
        List<BuildKind> kinds = resolveBuildKinds();
        List<Architecture> archs = resolveArchitectures();

        List<MatrixCell> cells = new ArrayList<>();
        Set<MatrixCell> seen = new LinkedHashSet<>();
        for (BuildKind kind : kinds) {
            if (!kind.requiresArchitecture()) {
                MatrixCell cell = new MatrixCell(kind, null);
                if (seen.add(cell)) {
                    cells.add(cell);
                }
                continue;
            }
            for (Architecture arch : archs) {
                MatrixCell cell = new MatrixCell(kind, arch);
                if (seen.add(cell)) {
                    cells.add(cell);
                }
            }
        }
        if (kinds.contains(BuildKind.NATIVE_PGO_OPTIMIZE) && (profilePath == null
                || profilePath.isBlank())) {
            throw new MojoFailureException(
                    "fargate.profilePath must be set when native-pgo-optimize is requested");
        }
        return cells;
    }

    private List<BuildKind> resolveBuildKinds() throws MojoFailureException {
        if (buildKinds == null || buildKinds.isEmpty()) {
            return List.of(BuildKind.NATIVE);
        }
        try {
            return buildKinds.stream().map(BuildKind::parse).distinct().toList();
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
    }

    private List<Architecture> resolveArchitectures() throws MojoFailureException {
        if (architectures == null || architectures.isEmpty()) {
            return List.of(Architecture.host().orElseThrow(() -> new IllegalStateException(
                    "Cannot determine the host architecture (os.arch="
                            + System.getProperty("os.arch") + "); configure <architectures> explicitly.")));
        }
        try {
            return architectures.stream().map(Architecture::parse).distinct().toList();
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
    }

    // --- JVM cell -----------------------------------------------------------------------

    private void attachJvmArtifact() {
        if (project.getArtifact() == null || project.getArtifact().getFile() == null) {
            getLog().warn("build kind 'jvm' was requested but the project has no packaged artifact "
                    + "yet; is fargate:build bound after the package phase?");
            return;
        }
        getLog().info("JVM build kind: attaching the already-packaged project artifact "
                + project.getArtifact().getFile());
        // The primary artifact is already attached by the ordinary package phase; nothing further
        // to do here beyond confirming it exists, per the design's "JVM cells are never remotely
        // built" rule -- bytecode is portable, there is nothing native-image-specific to run.
    }

    // --- Local-first cells ----------------------------------------------------------------

    private NativeImageInputPlan planInputs() throws MojoExecutionException {
        try {
            return new NativeImageInputPlanner().plan(toProjectInputs());
        } catch (InputPlanningException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private void runLocalCell(MatrixCell cell, NativeImageInputPlan plan, String buildId)
            throws IOException, MojoFailureException, BuildFailedException, InterruptedException {
        Path mountRoot = resolveWorkDirectory();
        String stagingRelativePath =
                StagingLayout.defaults().stagingPath(buildId, cell.buildKind, cell.architecture);
        LocalStagingSink stagingSink = new LocalStagingSink(mountRoot);
        long staged = stagingSink.stage(plan, stagingRelativePath);
        getLog().info(String.format(Locale.ROOT, "Staged %s (%d bytes) for %s", stagingRelativePath,
                staged, cell));

        String profileRelativePath = null;
        if (cell.buildKind.requiresProfile()) {
            profileRelativePath = stageProfile(mountRoot, stagingRelativePath);
        }

        BuildCellRequest request = BuildCellRequest.builder()
                .buildId(buildId)
                .buildKind(cell.buildKind)
                .architecture(cell.architecture)
                .stagingRelativePath(stagingRelativePath)
                .argFileName(plan.argsFileName())
                .profileRelativePath(profileRelativePath)
                .expectedArtifacts(plan.expectedArtifacts())
                .extraNativeImageArgs(extraNativeImageArgs == null ? List.of() : extraNativeImageArgs)
                .timeoutMinutes(timeoutMinutes)
                .build();

        BuildEnvironment environment = BuildEnvironment.builder(mountRoot)
                .nativeImageCommand(splitCommand(nativeImageCommand))
                .build();
        BuildExecutor executor = new NativeImageBuildExecutor(environment);

        getLog().info("Building " + cell + " locally");
        BuildResult result = executor.execute(request, line -> getLog().info(line));
        getLog().info("Completed " + cell + " in " + result.duration());

        List<Path> artifacts = ArtifactCollector.collect(
                mountRoot.resolve(stagingRelativePath).normalize(), plan.expectedArtifacts(),
                Instant.EPOCH);
        if (artifacts.isEmpty()) {
            throw new BuildFailedException(cell + " reported success but produced no artifact");
        }
        attachArtifacts(cell, artifacts);
    }

    private String stageProfile(Path mountRoot, String stagingRelativePath)
            throws IOException, MojoFailureException {
        Path source = Path.of(profilePath);
        if (!java.nio.file.Files.isRegularFile(source)) {
            throw new MojoFailureException("fargate.profilePath does not exist: " + profilePath);
        }
        String relativeName = "default.iprof";
        Path destination = mountRoot.resolve(stagingRelativePath).resolve(relativeName);
        java.nio.file.Files.copy(source, destination,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return relativeName;
    }

    // --- Remote cells (Step Functions / Fargate Spot) --------------------------------------

    private List<String> runRemoteCells(List<MatrixCell> remoteCells, NativeImageInputPlan plan,
                                        String buildId) throws IOException, MojoExecutionException,
            MojoFailureException, InterruptedException {
        requireRemoteConfig();

        S3Client s3Client = S3Client.builder().region(software.amazon.awssdk.regions.Region.of(region))
                .build();
        EcsClient ecsClient =
                EcsClient.builder().region(software.amazon.awssdk.regions.Region.of(region)).build();
        SfnClient sfnClient =
                SfnClient.builder().region(software.amazon.awssdk.regions.Region.of(region)).build();
        try {
            return runRemoteCells(remoteCells, plan, buildId, s3Client, ecsClient, sfnClient);
        } finally {
            s3Client.close();
            ecsClient.close();
            sfnClient.close();
        }
    }

    /** Package-visible for testing the orchestration logic against mocked AWS clients. */
    List<String> runRemoteCells(List<MatrixCell> remoteCells, NativeImageInputPlan plan,
                                String buildId, S3Client s3Client, EcsClient ecsClient,
                                SfnClient sfnClient)
            throws IOException, MojoExecutionException, MojoFailureException, InterruptedException {
        EcsClusterSettings clusterSettings = new EcsClusterSettings(clusterArn, subnetIds,
                securityGroupIds, assignPublicIp, executionRoleArn, taskRoleArn,
                s3FilesFileSystemArn, s3FilesRootDirectory, s3FilesAccessPointArn, logGroupName,
                region);
        AgentContainerSettings containerSettings =
                new AgentContainerSettings(agentImageUri, agentCpu, agentMemory,
                        agentEphemeralStorageGiB);

        S3StagingSink stagingSink = new S3StagingSink(s3Client, s3Bucket);
        TaskDefinitionRegistrar registrar = new TaskDefinitionRegistrar(ecsClient);
        ObjectMapper objectMapper = new ObjectMapper();
        ArrayNode cellsJson = objectMapper.createArrayNode();

        for (MatrixCell cell : remoteCells) {
            String stagingRelativePath =
                    StagingLayout.defaults().stagingPath(buildId, cell.buildKind, cell.architecture);
            long staged = stagingSink.stage(plan, stagingRelativePath);
            getLog().info(String.format(Locale.ROOT, "Staged %s (%d bytes) for %s",
                    stagingRelativePath, staged, cell));

            String profileRelativePath = null;
            if (cell.buildKind.requiresProfile()) {
                profileRelativePath = stageRemoteProfile(s3Client, stagingRelativePath);
            }

            String taskDefinitionArn = registrar.registerIfChanged(clusterSettings, containerSettings,
                    cell.buildKind, cell.architecture);

            cellsJson.add(buildCellInput(clusterSettings, taskDefinitionArn, buildId, cell,
                    stagingRelativePath, plan, profileRelativePath, objectMapper));
        }

        int maxConcurrency = Math.max(1, Math.min(40, remoteCells.size()));
        ObjectNode definition = BuildMatrixStateMachineDefinition.build(objectMapper, maxConcurrency,
                Math.max(1, maxAttemptsPerCell));

        StateMachineManager stateMachineManager = new StateMachineManager(sfnClient, objectMapper);
        String stateMachineArn = stateMachineManager.deployIfChanged(stateMachineName,
                stateMachineExecutionRoleArn, definition);

        ObjectNode executionInput = objectMapper.createObjectNode();
        executionInput.set("cells", cellsJson);

        StepFunctionsExecutionSupervisor supervisor =
                new StepFunctionsExecutionSupervisor(sfnClient, objectMapper);
        StepFunctionsExecutionSupervisor.MatrixResult result = supervisor.supervise(stateMachineArn,
                "fargate-build-" + buildId, executionInput.toString(), Duration.ofSeconds(15),
                Duration.ofMinutes(Math.max(1, overallTimeoutMinutes)));

        if (result.timedOut()) {
            return List.of("remote matrix execution exceeded " + overallTimeoutMinutes
                    + " minute(s) without finishing");
        }

        List<String> failures = new ArrayList<>();
        S3ArtifactRetriever retriever = new S3ArtifactRetriever(s3Client, s3Bucket);
        for (StepFunctionsExecutionSupervisor.CellOutcome outcome : result.cellOutcomes()) {
            MatrixCell cell = findCell(remoteCells, outcome);
            if (cell == null) {
                failures.add("Could not correlate execution output entry " + outcome
                        + " back to a requested cell");
                continue;
            }
            if (!outcome.success()) {
                failures.add(cell + " failed remotely: " + outcome.error() + " ("
                        + outcome.cause() + ")");
                continue;
            }
            try {
                Path destinationDir = resolveWorkDirectory().resolve("remote-artifacts")
                        .resolve(cell.toString().replace('/', '-'));
                List<Path> artifacts = retriever.retrieve(buildId, cell.buildKind, cell.architecture,
                        plan.expectedArtifacts(), destinationDir);
                if (artifacts.isEmpty()) {
                    failures.add(cell + " reported success but no artifact was found in S3");
                    continue;
                }
                attachArtifacts(cell, artifacts);
            } catch (IOException e) {
                failures.add(cell + " succeeded remotely but its artifact could not be downloaded: "
                        + e.getMessage());
            }
        }
        return failures;
    }

    private MatrixCell findCell(List<MatrixCell> remoteCells,
                                StepFunctionsExecutionSupervisor.CellOutcome outcome) {
        for (MatrixCell cell : remoteCells) {
            if (cell.buildKind.configValue().equals(outcome.buildKind())
                    && cell.architecture.name().equals(outcome.architecture())) {
                return cell;
            }
        }
        return null;
    }

    private String stageRemoteProfile(S3Client s3Client, String stagingRelativePath)
            throws IOException, MojoFailureException {
        Path source = Path.of(profilePath);
        if (!java.nio.file.Files.isRegularFile(source)) {
            throw new MojoFailureException("fargate.profilePath does not exist: " + profilePath);
        }
        String relativeName = "default.iprof";
        String key = stagingRelativePath.endsWith("/")
                ? stagingRelativePath + relativeName : stagingRelativePath + "/" + relativeName;
        try {
            s3Client.putObject(b -> b.bucket(s3Bucket).key(key),
                    software.amazon.awssdk.core.sync.RequestBody.fromFile(source));
        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            throw new IOException("Failed to upload profile to s3://" + s3Bucket + "/" + key, e);
        }
        return relativeName;
    }

    private ObjectNode buildCellInput(EcsClusterSettings clusterSettings, String taskDefinitionArn,
                                      String buildId, MatrixCell cell, String stagingRelativePath,
                                      NativeImageInputPlan plan, String profileRelativePath,
                                      ObjectMapper objectMapper) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("clusterArn", clusterSettings.clusterArn());
        node.put("taskDefinitionArn", taskDefinitionArn);
        ArrayNode subnets = node.putArray("subnetIds");
        clusterSettings.subnetIds().forEach(subnets::add);
        ArrayNode securityGroups = node.putArray("securityGroupIds");
        clusterSettings.securityGroupIds().forEach(securityGroups::add);
        node.put("assignPublicIp", clusterSettings.assignPublicIp());
        node.put("preferOnDemand", false);
        node.put("buildId", buildId);
        node.put("buildKind", cell.buildKind.configValue());
        node.put("architecture", cell.architecture.name());

        ArrayNode environment = node.putArray("environment");
        putEnv(environment, objectMapper, "JOBRUNR_BUILD_MOUNT_ROOT", "/mnt/build");
        putEnv(environment, objectMapper, "JOBRUNR_BUILD_ID", buildId);
        putEnv(environment, objectMapper, "JOBRUNR_BUILD_KIND", cell.buildKind.configValue());
        putEnv(environment, objectMapper, "JOBRUNR_BUILD_ARCH", cell.architecture.name());
        putEnv(environment, objectMapper, "JOBRUNR_BUILD_STAGING_RELATIVE_PATH",
                stagingRelativePath);
        putEnv(environment, objectMapper, "JOBRUNR_BUILD_ARG_FILE_NAME", plan.argsFileName());
        if (profileRelativePath != null) {
            putEnv(environment, objectMapper, "JOBRUNR_BUILD_PROFILE_RELATIVE_PATH",
                    profileRelativePath);
        }
        if (!plan.expectedArtifacts().isEmpty()) {
            putEnv(environment, objectMapper, "JOBRUNR_BUILD_EXPECTED_ARTIFACTS",
                    String.join(",", plan.expectedArtifacts()));
        }
        if (extraNativeImageArgs != null && !extraNativeImageArgs.isEmpty()) {
            putEnv(environment, objectMapper, "JOBRUNR_BUILD_EXTRA_NATIVE_IMAGE_ARGS",
                    String.join(" ", extraNativeImageArgs));
        }
        if (timeoutMinutes > 0) {
            putEnv(environment, objectMapper, "JOBRUNR_BUILD_TIMEOUT_MINUTES",
                    String.valueOf(timeoutMinutes));
        }
        return node;
    }

    private void putEnv(ArrayNode environment, ObjectMapper objectMapper, String name, String value) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("Name", name);
        entry.put("Value", value);
        environment.add(entry);
    }

    private void requireRemoteConfig() throws MojoFailureException {
        List<String> missing = new ArrayList<>();
        if (isBlank(s3Bucket)) {
            missing.add("fargate.s3Bucket");
        }
        if (isBlank(clusterArn)) {
            missing.add("fargate.clusterArn");
        }
        if (subnetIds == null || subnetIds.isEmpty()) {
            missing.add("fargate.subnetIds");
        }
        if (securityGroupIds == null || securityGroupIds.isEmpty()) {
            missing.add("fargate.securityGroupIds");
        }
        if (isBlank(executionRoleArn)) {
            missing.add("fargate.executionRoleArn");
        }
        if (isBlank(taskRoleArn)) {
            missing.add("fargate.taskRoleArn");
        }
        if (isBlank(s3FilesFileSystemArn)) {
            missing.add("fargate.s3FilesFileSystemArn");
        }
        if (isBlank(logGroupName)) {
            missing.add("fargate.logGroupName");
        }
        if (isBlank(region)) {
            missing.add("fargate.region");
        }
        if (isBlank(agentImageUri)) {
            missing.add("fargate.agentImageUri");
        }
        if (isBlank(stateMachineExecutionRoleArn)) {
            missing.add("fargate.stateMachineExecutionRoleArn");
        }
        if (!missing.isEmpty()) {
            throw new MojoFailureException(
                    "The requested matrix needs at least one remote (non-local-first) build, which "
                            + "requires the following configuration to be set: " + missing);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // --- Shared helpers ---------------------------------------------------------------------

    private void attachArtifacts(MatrixCell cell, List<Path> artifacts) {
        for (Path artifact : artifacts) {
            String classifierBase = cell.architecture == null ? cell.buildKind.configValue()
                    : cell.buildKind.configValue() + "-" + cell.architecture.artifactClassifier();
            String classifier = artifacts.size() == 1 ? classifierBase
                    : classifierBase + "-" + artifact.getFileName();
            String type = guessType(artifact);
            projectHelper.attachArtifact(project, type, classifier, artifact.toFile());
            getLog().info("Attached " + artifact + " with classifier '" + classifier + "'");
        }
    }

    private String guessType(Path artifact) {
        String name = artifact.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "bin" : name.substring(dot + 1);
    }

    private Path resolveWorkDirectory() {
        if (workDirectory != null && !workDirectory.isBlank()) {
            return Path.of(workDirectory);
        }
        return Path.of(project.getBuild().getDirectory(), "fargate-build");
    }

    private List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) {
            return List.of("native-image");
        }
        return List.of(command.trim().split("\\s+"));
    }

    private ProjectInputs toProjectInputs() {
        Path targetDirectory = Path.of(project.getBuild().getDirectory());
        String finalName = project.getBuild().getFinalName();
        Path artifactFile = project.getArtifact() != null && project.getArtifact().getFile() != null
                ? project.getArtifact().getFile().toPath()
                : null;
        List<Path> runtimeClasspath = resolveRuntimeClasspath();
        return new ProjectInputs(targetDirectory, finalName, artifactFile, runtimeClasspath, mainClass,
                imageName, extraBuildArgs == null ? List.of() : extraBuildArgs);
    }

    private List<Path> resolveRuntimeClasspath() {
        try {
            List<String> elements = project.getRuntimeClasspathElements();
            List<Path> paths = new ArrayList<>();
            for (String element : elements) {
                paths.add(Path.of(element));
            }
            return paths;
        } catch (DependencyResolutionRequiredException e) {
            getLog().warn("Runtime classpath is not resolved; derived-argfile mode will see no "
                    + "dependency jars: " + e.getMessage());
            return List.of();
        }
    }
}
