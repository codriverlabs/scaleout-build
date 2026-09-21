/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.backend;

import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import ai.codriverlabs.scaleoutbuild.maven.MatrixCell;
import ai.codriverlabs.scaleoutbuild.ecs.AgentContainerSettings;
import ai.codriverlabs.scaleoutbuild.ecs.CloudWatchLogTailer;
import ai.codriverlabs.scaleoutbuild.ecs.EcsClusterSettings;
import ai.codriverlabs.scaleoutbuild.ecs.EcsTaskLauncher;
import ai.codriverlabs.scaleoutbuild.ecs.EcsTaskSupervisor;
import ai.codriverlabs.scaleoutbuild.ecs.TaskDefinitionRegistrar;
import ai.codriverlabs.scaleoutbuild.planner.NativeImageInputPlan;
import ai.codriverlabs.scaleoutbuild.staging.S3ArtifactRetriever;
import ai.codriverlabs.scaleoutbuild.staging.S3StagingSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Builds remote cells by talking to ECS, S3, and CloudWatch Logs directly, with the credentials of
 * whoever ran Maven.
 *
 * <p>This is the behaviour the plugin has always had, lifted out of {@code BuildMojo} unchanged. It
 * is also the behaviour the control-plane migration is designed to retire: it requires every
 * developer to hold `ecs:RunTask`/`DescribeTasks`/`StopTask`, S3 read/write on the staging bucket,
 * and CloudWatch Logs read, plus a populated copy of the infrastructure's identity (cluster ARN,
 * subnets, security groups, both role ARNs). See
 * {@code docs/design/control-plane/migration-from-direct-ecs-access.md}.
 *
 * <p>Note what this class implies about cancellation, because it is the property a service backend
 * must recreate rather than inherit: the calling process <em>is</em> the supervisor. If Maven dies,
 * the supervision loop dies with it, and nothing stops the running Fargate tasks. Today that is
 * invisible because a developer who kills their build is also the person paying attention to it;
 * behind a control plane, a server-side heartbeat and reaper are required to cover the same case.
 */
public final class DirectEcsBuildBackend implements BuildBackend {

    private final S3Client s3Client;
    private final EcsClient ecsClient;
    private final CloudWatchLogsClient logsClient;
    private final EcsClusterSettings clusterSettings;
    private final AgentContainerSettings containerSettings;
    private final RemoteBuildOptions options;
    private final Log log;

    public DirectEcsBuildBackend(S3Client s3Client, EcsClient ecsClient,
                                 CloudWatchLogsClient logsClient,
                                 EcsClusterSettings clusterSettings,
                                 AgentContainerSettings containerSettings,
                                 RemoteBuildOptions options, Log log) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.ecsClient = Objects.requireNonNull(ecsClient, "ecsClient");
        this.logsClient = Objects.requireNonNull(logsClient, "logsClient");
        this.clusterSettings = Objects.requireNonNull(clusterSettings, "clusterSettings");
        this.containerSettings = Objects.requireNonNull(containerSettings, "containerSettings");
        this.options = Objects.requireNonNull(options, "options");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public List<String> runCells(List<MatrixCell> cells, NativeImageInputPlan plan, String buildId,
                                ArtifactSink artifactSink)
            throws IOException, MojoExecutionException, MojoFailureException, InterruptedException {
        S3StagingSink stagingSink = new S3StagingSink(s3Client, options.s3Bucket());
        TaskDefinitionRegistrar registrar = new TaskDefinitionRegistrar(ecsClient);
        EcsTaskLauncher launcher = new EcsTaskLauncher(ecsClient);
        CloudWatchLogTailer logTailer = new CloudWatchLogTailer(logsClient);
        EcsTaskSupervisor supervisor = new EcsTaskSupervisor(launcher, logTailer, ecsClient);

        // Stage every cell's inputs and register its task definition up front, sequentially --
        // both are cheap and this keeps the concurrent section below to just the part that
        // actually benefits from running in parallel: watching each task run.
        List<CellLaunchPlan> launchPlans = new ArrayList<>();
        for (MatrixCell cell : cells) {
            String stagingRelativePath = StagingLayout.defaults()
                    .stagingPath(buildId, cell.buildKind(), cell.architecture());
            long staged = stagingSink.stage(plan, stagingRelativePath);
            log.info(String.format(Locale.ROOT, "Staged %s (%d bytes) for %s", stagingRelativePath,
                    staged, cell));

            String profileRelativePath = null;
            if (cell.buildKind().requiresProfile()) {
                profileRelativePath = stageRemoteProfile(stagingRelativePath);
            }

            String taskDefinitionArn = registrar.registerIfChanged(clusterSettings, containerSettings,
                    cell.buildKind(), cell.architecture());
            List<KeyValuePair> environment = buildTaskOverrideEnvironment(buildId, cell,
                    stagingRelativePath, plan, profileRelativePath);
            launchPlans.add(new CellLaunchPlan(cell, taskDefinitionArn, environment));
        }

        return superviseAllCellsConcurrently(launchPlans, supervisor, buildId, plan, artifactSink);
    }

    /** Everything one cell's task launch needs, computed once before the concurrent section. */
    private record CellLaunchPlan(MatrixCell cell, String taskDefinitionArn,
                                  List<KeyValuePair> environment) {
    }

    /**
     * Launches and supervises every remote cell's task concurrently, one thread per cell, since
     * there is no Map state doing the fan-out for us. Bounded to {@code launchPlans.size()}
     * threads — this plugin never launches more tasks than that in one invocation, so there is no
     * reason to cap concurrency below it the way {@code MaxConcurrency} would on a Map state.
     */
    private List<String> superviseAllCellsConcurrently(List<CellLaunchPlan> launchPlans,
                                                       EcsTaskSupervisor supervisor, String buildId,
                                                       NativeImageInputPlan plan,
                                                       ArtifactSink artifactSink)
            throws MojoExecutionException, InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(launchPlans.size());
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (CellLaunchPlan launchPlan : launchPlans) {
                Callable<String> task = () -> superviseOneCell(launchPlan, supervisor, buildId, plan,
                        artifactSink);
                futures.add(executor.submit(task));
            }

            List<String> failures = new ArrayList<>();
            for (Future<String> future : futures) {
                try {
                    String failure = future.get();
                    if (failure != null) {
                        failures.add(failure);
                    }
                } catch (ExecutionException e) {
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
     *         its artifacts have been handed to the {@link ArtifactSink})
     */
    private String superviseOneCell(CellLaunchPlan launchPlan, EcsTaskSupervisor supervisor,
                                   String buildId, NativeImageInputPlan plan,
                                   ArtifactSink artifactSink) throws InterruptedException {
        MatrixCell cell = launchPlan.cell();
        EcsTaskSupervisor.SupervisionOptions supervisionOptions = EcsTaskSupervisor.SupervisionOptions
                .defaults()
                .pollInterval(Duration.ofSeconds(Math.max(1, options.pollIntervalSeconds())))
                .overallTimeout(Duration.ofMinutes(Math.max(1, options.overallTimeoutMinutes())))
                .maxSpotInterruptionsBeforeOnDemand(
                        Math.max(0, options.maxSpotInterruptionsBeforeOnDemand()));

        EcsTaskSupervisor.SupervisionResult result = supervisor.supervise(clusterSettings,
                launchPlan.taskDefinitionArn(), launchPlan.environment(),
                TaskDefinitionRegistrar.LOG_STREAM_PREFIX,
                line -> log.info("[" + cell + "] " + line), supervisionOptions);

        if (result.timedOut()) {
            return cell + ": " + result.failureReason();
        }
        if (!result.succeeded()) {
            return cell + " failed remotely: " + result.failureReason();
        }

        try {
            Path destinationDir = options.workDirectory().resolve("remote-artifacts")
                    .resolve(cell.toString().replace('/', '-'));
            S3ArtifactRetriever retriever = new S3ArtifactRetriever(s3Client, options.s3Bucket());
            List<Path> artifacts = retriever.retrieve(buildId, cell.buildKind(), cell.architecture(),
                    plan.expectedArtifacts(), destinationDir);
            if (artifacts.isEmpty()) {
                return cell + " reported success but no artifact was found in S3";
            }
            artifactSink.accept(cell, artifacts);
            return null;
        } catch (IOException e) {
            String causeDetail = e.getCause() != null && e.getCause().getMessage() != null
                    ? " (" + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage() + ")"
                    : "";
            return cell + " succeeded remotely but its artifact could not be downloaded: "
                    + e.getMessage() + causeDetail;
        }
    }

    private String stageRemoteProfile(String stagingRelativePath)
            throws IOException, MojoFailureException {
        Path source = Path.of(options.profilePath());
        if (!Files.isRegularFile(source)) {
            throw new MojoFailureException(
                    "aws-ecs.profilePath does not exist: " + options.profilePath());
        }
        String relativeName = "default.iprof";
        String key = stagingRelativePath.endsWith("/")
                ? stagingRelativePath + relativeName : stagingRelativePath + "/" + relativeName;
        try {
            s3Client.putObject(b -> b.bucket(options.s3Bucket()).key(key),
                    RequestBody.fromFile(source));
        } catch (SdkException e) {
            throw new IOException(
                    "Failed to upload profile to s3://" + options.s3Bucket() + "/" + key, e);
        }
        return relativeName;
    }

    /** Task-override environment variables matching {@code AgentConfig}'s exact names. */
    private List<KeyValuePair> buildTaskOverrideEnvironment(String buildId, MatrixCell cell,
                                                            String stagingRelativePath,
                                                            NativeImageInputPlan plan,
                                                            String profileRelativePath) {
        List<KeyValuePair> environment = new ArrayList<>();
        environment.add(env("SCALEOUT_BUILD_MOUNT_ROOT", TaskDefinitionRegistrar.MOUNT_CONTAINER_PATH));
        if (clusterSettings.agentUsesDirectS3Io()) {
            environment.add(env("SCALEOUT_BUILD_S3_BUCKET", options.s3Bucket()));
        }
        environment.add(env("SCALEOUT_BUILD_ID", buildId));
        environment.add(env("SCALEOUT_BUILD_KIND", cell.buildKind().configValue()));
        environment.add(env("SCALEOUT_BUILD_ARCH", cell.architecture().name()));
        environment.add(env("SCALEOUT_BUILD_STAGING_RELATIVE_PATH", stagingRelativePath));
        environment.add(env("SCALEOUT_BUILD_ARG_FILE_NAME", plan.argsFileName()));
        if (profileRelativePath != null) {
            environment.add(env("SCALEOUT_BUILD_PROFILE_RELATIVE_PATH", profileRelativePath));
        }
        if (!plan.expectedArtifacts().isEmpty()) {
            environment.add(env("SCALEOUT_BUILD_EXPECTED_ARTIFACTS",
                    String.join(",", plan.expectedArtifacts())));
        }
        if (!options.extraNativeImageArgs().isEmpty()) {
            environment.add(env("SCALEOUT_BUILD_EXTRA_NATIVE_IMAGE_ARGS",
                    String.join(" ", options.extraNativeImageArgs())));
        }
        if (options.timeoutMinutes() > 0) {
            environment.add(env("SCALEOUT_BUILD_TIMEOUT_MINUTES",
                    String.valueOf(options.timeoutMinutes())));
        }
        return environment;
    }

    private static KeyValuePair env(String name, String value) {
        return KeyValuePair.builder().name(name).value(value).build();
    }
}
