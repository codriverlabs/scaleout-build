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
import cloud.plasticity.jobrunr.maven.ecs.CloudWatchLogTailer;
import cloud.plasticity.jobrunr.maven.ecs.EcsClusterSettings;
import cloud.plasticity.jobrunr.maven.ecs.FargateTaskLauncher;
import cloud.plasticity.jobrunr.maven.ecs.FargateTaskSupervisor;
import cloud.plasticity.jobrunr.maven.ecs.TaskDefinitionRegistrar;
import cloud.plasticity.jobrunr.maven.planner.InputPlanningException;
import cloud.plasticity.jobrunr.maven.planner.NativeImageInputPlan;
import cloud.plasticity.jobrunr.maven.planner.NativeImageInputPlanner;
import cloud.plasticity.jobrunr.maven.planner.ProjectInputs;
import cloud.plasticity.jobrunr.maven.staging.LocalStagingSink;
import cloud.plasticity.jobrunr.maven.staging.S3ArtifactRetriever;
import cloud.plasticity.jobrunr.maven.staging.S3StagingSink;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code aws-ecs:build} — computes a GraalVM build matrix and runs it directly against ECS, with no
 * orchestration layer above it (see {@code docs/PURE_ECS_ALTERNATIVE.md} for why, and when a
 * declarative orchestrator like Step Functions would be worth reintroducing instead).
 *
 * <p>Every requested cell whose architecture matches the host runs directly, in-process, via
 * {@link NativeImageBuildExecutor} — no AWS involved. Cells that don't match the host (and every
 * {@link BuildKind#JVM} cell needs no build at all — it is the project's already-packaged jar) each
 * get their own {@code RunTask} call and their own {@link FargateTaskSupervisor}, run concurrently
 * on a bounded thread pool sized to the number of remote cells.
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
    @Parameter(property = "aws-ecs.buildKinds")
    private List<String> buildKinds;

    /** Target architectures for every non-JVM build kind. Defaults to the host architecture. */
    @Parameter(property = "aws-ecs.architectures")
    private List<String> architectures;

    /**
     * Local path to a {@code .iprof} profile, required when {@code native-pgo-optimize} is one of
     * the requested build kinds. Per {@code docs/DESIGN.md} §3, collecting this profile (running an
     * instrumented binary against real traffic) is out of scope for this plugin.
     */
    @Parameter(property = "aws-ecs.profilePath")
    private String profilePath;

    /** Explicit main class for derived-argfile builds; read from the artifact manifest if omitted. */
    @Parameter(property = "aws-ecs.mainClass")
    private String mainClass;

    /** Explicit output binary name for derived-argfile builds; defaults to the project's final name. */
    @Parameter(property = "aws-ecs.imageName")
    private String imageName;

    /** Extra {@code native-image} arguments appended for derived-argfile builds. */
    @Parameter
    private List<String> extraBuildArgs;

    /** Extra arguments appended to every invocation, after the argfile and build-kind flags. */
    @Parameter
    private List<String> extraNativeImageArgs;

    /** Command that invokes {@code native-image} for local-first cells, space-separated. */
    @Parameter(property = "aws-ecs.nativeImageCommand", defaultValue = "native-image")
    private String nativeImageCommand;

    /** Directory local-first builds are staged into; defaults to {@code target/aws-ecs-build}. */
    @Parameter(property = "aws-ecs.workDirectory")
    private String workDirectory;

    /** Soft timeout applied to each {@code native-image} process; 0 disables it. */
    @Parameter(property = "aws-ecs.timeoutMinutes", defaultValue = "0")
    private int timeoutMinutes;

    /** Overall time to wait for the remote matrix execution to finish before failing the goal. */
    @Parameter(property = "aws-ecs.overallTimeoutMinutes", defaultValue = "120")
    private int overallTimeoutMinutes;

    /** Skips the goal entirely, for profiles that only want native builds on CI. */
    @Parameter(property = "aws-ecs.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Sends every non-JVM cell to ECS regardless of whether its architecture matches the host —
     * the default behavior otherwise always prefers building a host-matching cell locally with no
     * AWS calls at all. Set this when you specifically want every cell built remotely, e.g. to keep
     * the local machine's toolchain out of the loop entirely or to exercise the remote path for a
     * cell that happens to match the host.
     */
    @Parameter(property = "aws-ecs.forceRemote", defaultValue = "false")
    private boolean forceRemote;

    // --- Remote orchestration configuration; only required if any cell cannot run locally. ---

    @Parameter(property = "aws-ecs.s3Bucket")
    private String s3Bucket;
    @Parameter(property = "aws-ecs.clusterArn")
    private String clusterArn;
    @Parameter(property = "aws-ecs.subnetIds")
    private List<String> subnetIds;
    @Parameter(property = "aws-ecs.securityGroupIds")
    private List<String> securityGroupIds;
    @Parameter(property = "aws-ecs.assignPublicIp", defaultValue = "false")
    private boolean assignPublicIp;
    @Parameter(property = "aws-ecs.executionRoleArn")
    private String executionRoleArn;
    @Parameter(property = "aws-ecs.taskRoleArn")
    private String taskRoleArn;
    @Parameter(property = "aws-ecs.s3FilesFileSystemArn")
    private String s3FilesFileSystemArn;
    @Parameter(property = "aws-ecs.s3FilesRootDirectory")
    private String s3FilesRootDirectory;
    @Parameter(property = "aws-ecs.s3FilesAccessPointArn")
    private String s3FilesAccessPointArn;
    @Parameter(property = "aws-ecs.logGroupName")
    private String logGroupName;
    @Parameter(property = "aws-ecs.region")
    private String region;
    @Parameter(property = "aws-ecs.agentImageUri")
    private String agentImageUri;
    @Parameter(property = "aws-ecs.agentCpu", defaultValue = "4096")
    private String agentCpu;
    @Parameter(property = "aws-ecs.agentMemory", defaultValue = "16384")
    private String agentMemory;
    @Parameter(property = "aws-ecs.agentEphemeralStorageGiB", defaultValue = "0")
    private int agentEphemeralStorageGiB;
    /** How many Spot interruptions a cell tolerates before its relaunch prefers on-demand capacity. */
    @Parameter(property = "aws-ecs.maxSpotInterruptionsBeforeOnDemand", defaultValue = "2")
    private int maxSpotInterruptionsBeforeOnDemand;
    /** How often to poll ECS/CloudWatch Logs while a remote cell is running. */
    @Parameter(property = "aws-ecs.pollIntervalSeconds", defaultValue = "5")
    private int pollIntervalSeconds;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("aws-ecs:build skipped (aws-ecs.skip=true)");
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

            LocalRemoteSplit split = splitLocalAndRemote(nativeishCells, forceRemote);
            List<MatrixCell> localCells = split.localCells();
            List<MatrixCell> remoteCells = split.remoteCells();

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
                    "aws-ecs:build failed for " + failures.size() + " cell(s):\n"
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

    /** Which non-JVM cells build locally versus on ECS. */
    record LocalRemoteSplit(List<MatrixCell> localCells, List<MatrixCell> remoteCells) {
    }

    /**
     * Splits non-JVM cells into local-first and remote groups.
     *
     * <p>By default a cell whose architecture matches the host builds locally with no AWS calls at
     * all; {@code forceRemote} overrides that and sends every cell to ECS regardless of host match
     * — e.g. to keep the local machine's toolchain out of the loop entirely, or to exercise the
     * remote path for a cell that happens to match the host.
     */
    static LocalRemoteSplit splitLocalAndRemote(List<MatrixCell> nativeishCells, boolean forceRemote) {
        if (forceRemote) {
            return new LocalRemoteSplit(List.of(), nativeishCells);
        }
        List<MatrixCell> localCells =
                nativeishCells.stream().filter(c -> c.architecture.matchesHost()).toList();
        List<MatrixCell> remoteCells =
                nativeishCells.stream().filter(c -> !c.architecture.matchesHost()).toList();
        return new LocalRemoteSplit(localCells, remoteCells);
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
                    "aws-ecs.profilePath must be set when native-pgo-optimize is requested");
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
                    + "yet; is aws-ecs:build bound after the package phase?");
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
            throw new MojoFailureException("aws-ecs.profilePath does not exist: " + profilePath);
        }
        String relativeName = "default.iprof";
        Path destination = mountRoot.resolve(stagingRelativePath).resolve(relativeName);
        java.nio.file.Files.copy(source, destination,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return relativeName;
    }

    // --- Remote cells (direct Fargate Spot, no orchestration layer) -----------------------

    private List<String> runRemoteCells(List<MatrixCell> remoteCells, NativeImageInputPlan plan,
                                        String buildId) throws IOException, MojoExecutionException,
            MojoFailureException, InterruptedException {
        requireRemoteConfig();

        S3Client s3Client = S3Client.builder().region(software.amazon.awssdk.regions.Region.of(region))
                .build();
        EcsClient ecsClient =
                EcsClient.builder().region(software.amazon.awssdk.regions.Region.of(region)).build();
        CloudWatchLogsClient logsClient = CloudWatchLogsClient.builder()
                .region(software.amazon.awssdk.regions.Region.of(region)).build();
        try {
            return runRemoteCells(remoteCells, plan, buildId, s3Client, ecsClient, logsClient);
        } finally {
            s3Client.close();
            ecsClient.close();
            logsClient.close();
        }
    }

    /** Package-visible for testing the orchestration logic against mocked AWS clients. */
    List<String> runRemoteCells(List<MatrixCell> remoteCells, NativeImageInputPlan plan,
                                String buildId, S3Client s3Client, EcsClient ecsClient,
                                CloudWatchLogsClient logsClient)
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
        FargateTaskLauncher launcher = new FargateTaskLauncher(ecsClient);
        CloudWatchLogTailer logTailer = new CloudWatchLogTailer(logsClient);
        FargateTaskSupervisor supervisor = new FargateTaskSupervisor(launcher, logTailer, ecsClient);

        // Stage every cell's inputs and register its task definition up front, sequentially --
        // both are cheap and this keeps the concurrent section below to just the part that
        // actually benefits from running in parallel: watching each task run.
        List<CellLaunchPlan> launchPlans = new ArrayList<>();
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
            List<KeyValuePair> environment = buildTaskOverrideEnvironment(buildId, cell,
                    stagingRelativePath, plan, profileRelativePath);
            launchPlans.add(new CellLaunchPlan(cell, taskDefinitionArn, environment));
        }

        return superviseAllCellsConcurrently(launchPlans, clusterSettings, supervisor, buildId, plan,
                s3Client);
    }

    /** Everything one cell's task launch needs, computed once before the concurrent section. */
    private record CellLaunchPlan(MatrixCell cell, String taskDefinitionArn,
                                  List<KeyValuePair> environment) {
    }

    /**
     * Launches and supervises every remote cell's task concurrently, one thread per cell, since
     * there is no Map state doing the fan-out for us. Bounded to {@code remoteCells.size()}
     * threads — this plugin never launches more tasks than that in one invocation, so there is no
     * reason to cap concurrency below it the way {@code MaxConcurrency} would on a Map state.
     */
    private List<String> superviseAllCellsConcurrently(List<CellLaunchPlan> launchPlans,
                                                        EcsClusterSettings clusterSettings,
                                                        FargateTaskSupervisor supervisor,
                                                        String buildId, NativeImageInputPlan plan,
                                                        S3Client s3Client)
            throws MojoExecutionException, InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(launchPlans.size());
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (CellLaunchPlan launchPlan : launchPlans) {
                Callable<String> task = () -> superviseOneCell(launchPlan, clusterSettings,
                        supervisor, buildId, plan, s3Client);
                futures.add(executor.submit(task));
            }

            List<String> failures = new ArrayList<>();
            for (Future<String> future : futures) {
                try {
                    String failure = future.get();
                    if (failure != null) {
                        failures.add(failure);
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new MojoExecutionException(
                            "Unexpected error supervising a remote cell", e.getCause());
                }
            }
            return failures;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * @return a failure message if this cell did not succeed, or {@code null} on success (after
     *         its artifact has already been attached to the reactor)
     */
    private String superviseOneCell(CellLaunchPlan launchPlan, EcsClusterSettings clusterSettings,
                                     FargateTaskSupervisor supervisor, String buildId,
                                     NativeImageInputPlan plan, S3Client s3Client)
            throws InterruptedException {
        MatrixCell cell = launchPlan.cell();
        FargateTaskSupervisor.SupervisionOptions options = FargateTaskSupervisor.SupervisionOptions
                .defaults()
                .pollInterval(Duration.ofSeconds(Math.max(1, pollIntervalSeconds)))
                .overallTimeout(Duration.ofMinutes(Math.max(1, overallTimeoutMinutes)))
                .maxSpotInterruptionsBeforeOnDemand(Math.max(0, maxSpotInterruptionsBeforeOnDemand));

        FargateTaskSupervisor.SupervisionResult result = supervisor.supervise(clusterSettings,
                launchPlan.taskDefinitionArn(), launchPlan.environment(), LOG_STREAM_PREFIX,
                line -> getLog().info("[" + cell + "] " + line), options);

        if (result.timedOut()) {
            return cell + ": " + result.failureReason();
        }
        if (!result.succeeded()) {
            return cell + " failed remotely: " + result.failureReason();
        }

        try {
            Path destinationDir = resolveWorkDirectory().resolve("remote-artifacts")
                    .resolve(cell.toString().replace('/', '-'));
            S3ArtifactRetriever retriever = new S3ArtifactRetriever(s3Client, s3Bucket);
            List<Path> artifacts = retriever.retrieve(buildId, cell.buildKind, cell.architecture,
                    plan.expectedArtifacts(), destinationDir);
            if (artifacts.isEmpty()) {
                return cell + " reported success but no artifact was found in S3";
            }
            attachArtifacts(cell, artifacts);
            return null;
        } catch (IOException e) {
            return cell + " succeeded remotely but its artifact could not be downloaded: "
                    + e.getMessage();
        }
    }

    /**
     * Log stream prefix configured on the task definition's {@code awslogs-stream-prefix} — the
     * actual stream name ({@code prefix/container-name/task-id}) isn't known until the task starts,
     * so {@link CloudWatchLogTailer} matches on this prefix rather than the full stream name.
     */
    private static final String LOG_STREAM_PREFIX = TaskDefinitionRegistrar.LOG_STREAM_PREFIX;

    private String stageRemoteProfile(S3Client s3Client, String stagingRelativePath)
            throws IOException, MojoFailureException {
        Path source = Path.of(profilePath);
        if (!java.nio.file.Files.isRegularFile(source)) {
            throw new MojoFailureException("aws-ecs.profilePath does not exist: " + profilePath);
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

    /** Task-override environment variables matching {@code AgentConfig}'s exact names. */
    private List<KeyValuePair> buildTaskOverrideEnvironment(String buildId, MatrixCell cell,
                                                             String stagingRelativePath,
                                                             NativeImageInputPlan plan,
                                                             String profileRelativePath) {
        List<KeyValuePair> environment = new ArrayList<>();
        environment.add(env("JOBRUNR_BUILD_MOUNT_ROOT", "/mnt/build"));
        environment.add(env("JOBRUNR_BUILD_ID", buildId));
        environment.add(env("JOBRUNR_BUILD_KIND", cell.buildKind.configValue()));
        environment.add(env("JOBRUNR_BUILD_ARCH", cell.architecture.name()));
        environment.add(env("JOBRUNR_BUILD_STAGING_RELATIVE_PATH", stagingRelativePath));
        environment.add(env("JOBRUNR_BUILD_ARG_FILE_NAME", plan.argsFileName()));
        if (profileRelativePath != null) {
            environment.add(env("JOBRUNR_BUILD_PROFILE_RELATIVE_PATH", profileRelativePath));
        }
        if (!plan.expectedArtifacts().isEmpty()) {
            environment.add(env("JOBRUNR_BUILD_EXPECTED_ARTIFACTS",
                    String.join(",", plan.expectedArtifacts())));
        }
        if (extraNativeImageArgs != null && !extraNativeImageArgs.isEmpty()) {
            environment.add(env("JOBRUNR_BUILD_EXTRA_NATIVE_IMAGE_ARGS",
                    String.join(" ", extraNativeImageArgs)));
        }
        if (timeoutMinutes > 0) {
            environment.add(env("JOBRUNR_BUILD_TIMEOUT_MINUTES", String.valueOf(timeoutMinutes)));
        }
        return environment;
    }

    private static KeyValuePair env(String name, String value) {
        return KeyValuePair.builder().name(name).value(value).build();
    }

    private void requireRemoteConfig() throws MojoFailureException {
        List<String> missing = new ArrayList<>();
        if (isBlank(s3Bucket)) {
            missing.add("aws-ecs.s3Bucket");
        }
        if (isBlank(clusterArn)) {
            missing.add("aws-ecs.clusterArn");
        }
        if (subnetIds == null || subnetIds.isEmpty()) {
            missing.add("aws-ecs.subnetIds");
        }
        if (securityGroupIds == null || securityGroupIds.isEmpty()) {
            missing.add("aws-ecs.securityGroupIds");
        }
        if (isBlank(executionRoleArn)) {
            missing.add("aws-ecs.executionRoleArn");
        }
        if (isBlank(taskRoleArn)) {
            missing.add("aws-ecs.taskRoleArn");
        }
        if (isBlank(s3FilesFileSystemArn)) {
            missing.add("aws-ecs.s3FilesFileSystemArn");
        }
        if (isBlank(logGroupName)) {
            missing.add("aws-ecs.logGroupName");
        }
        if (isBlank(region)) {
            missing.add("aws-ecs.region");
        }
        if (isBlank(agentImageUri)) {
            missing.add("aws-ecs.agentImageUri");
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
        return Path.of(project.getBuild().getDirectory(), "aws-ecs-build");
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
