/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven;

import cloud.plasticity.jobrunr.build.Architecture;
import cloud.plasticity.jobrunr.build.ArtifactCollector;
import cloud.plasticity.jobrunr.build.BuildEnvironment;
import cloud.plasticity.jobrunr.build.BuildExecutor;
import cloud.plasticity.jobrunr.build.BuildFailedException;
import cloud.plasticity.jobrunr.build.BuildJobRequest;
import cloud.plasticity.jobrunr.build.BuildLog;
import cloud.plasticity.jobrunr.build.NativeImageBuildExecutor;
import cloud.plasticity.jobrunr.build.StagingLayout;
import cloud.plasticity.jobrunr.build.WorkerRuntime;
import cloud.plasticity.jobrunr.build.storage.StorageProviderFactory;
import cloud.plasticity.jobrunr.build.storage.StorageSettings;
import cloud.plasticity.jobrunr.build.storage.StorageType;
import cloud.plasticity.jobrunr.maven.planner.InputPlanningException;
import cloud.plasticity.jobrunr.maven.planner.NativeImageInputPlan;
import cloud.plasticity.jobrunr.maven.planner.NativeImageInputPlanner;
import cloud.plasticity.jobrunr.maven.planner.ProjectInputs;
import cloud.plasticity.jobrunr.maven.staging.LocalStagingSink;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobId;
import org.jobrunr.jobs.states.FailedState;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.storage.JobNotFoundException;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.utils.mapper.JsonMapper;

/**
 * {@code jobrunr:build} — builds a native-image binary through a JobRunr job.
 *
 * <p>This is the local vertical slice from the design's Task 1: the plan/stage/enqueue/execute path
 * runs entirely in-process, using an {@code InMemoryStorageProvider} and a worker that shares this
 * JVM. It proves the job contract end-to-end without any AWS dependency. Remote dispatch to Fargate
 * Spot tasks (the point of the plugin) arrives in later tasks; today's worker is always local and
 * only builds for the host architecture, since {@code native-image} cannot cross-compile.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME)
public class BuildMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Component
    private MavenProjectHelper projectHelper;

    /**
     * Storage backing for the JobRunr job store. Only {@code inmemory} exists today; {@code dsql} and
     * {@code postgres} arrive with the storage task.
     */
    @Parameter(property = "jobrunr.storage", defaultValue = "inmemory")
    private String storage;

    /**
     * Target architectures to build for. Every entry must match the host architecture in this
     * version of the plugin, since builds always run locally; remote dispatch will lift that
     * restriction.
     */
    @Parameter(property = "jobrunr.architectures")
    private List<String> architectures;

    /** Explicit main class for derived-argfile builds; read from the artifact manifest if omitted. */
    @Parameter(property = "jobrunr.mainClass")
    private String mainClass;

    /** Explicit output binary name for derived-argfile builds; defaults to the project's final name. */
    @Parameter(property = "jobrunr.imageName")
    private String imageName;

    /** Extra {@code native-image} arguments appended for derived-argfile builds. */
    @Parameter
    private List<String> extraBuildArgs;

    /** Extra arguments appended to every invocation, after the argfile reference. */
    @Parameter
    private List<String> extraNativeImageArgs;

    /** Command that invokes {@code native-image}, space-separated. */
    @Parameter(property = "jobrunr.nativeImageCommand", defaultValue = "native-image")
    private String nativeImageCommand;

    /** Directory the build is staged into; defaults to {@code target/jobrunr-build}. */
    @Parameter(property = "jobrunr.workDirectory")
    private String workDirectory;

    /** Soft timeout applied to the {@code native-image} process; 0 disables it. */
    @Parameter(property = "jobrunr.timeoutMinutes", defaultValue = "0")
    private int timeoutMinutes;

    /** Overall time to wait for a build to finish before failing the goal. */
    @Parameter(property = "jobrunr.overallTimeoutMinutes", defaultValue = "120")
    private int overallTimeoutMinutes;

    /** Skips the goal entirely, for profiles that only want native builds on CI. */
    @Parameter(property = "jobrunr.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("jobrunr:build skipped (jobrunr.skip=true)");
            return;
        }

        List<Architecture> targets = resolveArchitectures();
        ProjectInputs inputs = toProjectInputs();

        NativeImageInputPlan plan;
        try {
            plan = new NativeImageInputPlanner().plan(inputs);
        } catch (InputPlanningException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        getLog().info("Input plan: " + plan);

        Path mountRoot = resolveWorkDirectory();
        StorageType storageType = parseStorageType();
        String buildId = UUID.randomUUID().toString();

        List<String> failures = new ArrayList<>();
        for (Architecture architecture : targets) {
            try {
                runBuild(architecture, plan, mountRoot, storageType, buildId);
            } catch (BuildFailedException e) {
                failures.add(architecture + ": " + e.getMessage());
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Failed to stage build inputs for " + architecture, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MojoExecutionException("Interrupted while building for " + architecture, e);
            }
        }

        if (!failures.isEmpty()) {
            throw new MojoFailureException(
                    "jobrunr:build failed for " + failures.size() + " architecture(s):\n"
                            + String.join("\n", failures));
        }
    }

    /**
     * Runs one architecture's build end-to-end: stage, enqueue, run a local worker until the job
     * finishes, then attach the produced artifact to the reactor.
     */
    private void runBuild(Architecture architecture, NativeImageInputPlan plan, Path mountRoot,
                          StorageType storageType, String buildId)
            throws IOException, MojoFailureException, BuildFailedException, InterruptedException {
        String stagingRelativePath = StagingLayout.defaults().stagingPath(buildId, architecture);
        LocalStagingSink stagingSink = new LocalStagingSink(mountRoot);
        long staged = stagingSink.stage(plan, stagingRelativePath);
        getLog().info(String.format(Locale.ROOT, "Staged %s (%d bytes) for %s",
                stagingRelativePath, staged, architecture));

        BuildJobRequest request = BuildJobRequest.builder()
                .buildId(buildId)
                .architecture(architecture)
                .stagingRelativePath(stagingRelativePath)
                .argFileName(plan.argsFileName())
                .expectedArtifacts(plan.expectedArtifacts())
                .extraNativeImageArgs(extraNativeImageArgs == null ? List.of() : extraNativeImageArgs)
                .timeoutMinutes(timeoutMinutes)
                .build();

        JsonMapper jsonMapper = StorageProviderFactory.jsonMapper();
        StorageProvider storageProvider =
                StorageProviderFactory.create(StorageSettings.of(storageType, null), jsonMapper);

        BuildEnvironment environment = BuildEnvironment.builder(mountRoot)
                .nativeImageCommand(splitCommand(nativeImageCommand))
                .build();
        BuildExecutor executor = new NativeImageBuildExecutor(environment);

        WorkerRuntime.WorkerOptions options = WorkerRuntime.WorkerOptions.defaults()
                .name("jobrunr-build-" + architecture.schemaSuffix())
                .workerCount(1)
                .overallTimeout(Duration.ofMinutes(Math.max(1, overallTimeoutMinutes)))
                .idleTimeout(null)
                .buildLog(line -> getLog().info(line));

        JobId jobId;
        try (WorkerRuntime worker = WorkerRuntime.start(storageProvider, jsonMapper, executor, options)) {
            JobRequestScheduler scheduler = new JobRequestScheduler(storageProvider);
            jobId = scheduler.enqueue(request);
            getLog().info("Enqueued " + architecture + " build " + jobId + " for " + buildId);

            boolean completed = worker.awaitCompletion(1);
            if (!completed) {
                throw new MojoFailureException(
                        "Timed out after " + overallTimeoutMinutes + " minute(s) waiting for the "
                                + architecture + " build to finish");
            }
        }
        checkJobSucceeded(storageProvider, jobId, architecture);

        List<Path> artifacts = collectArtifacts(mountRoot, stagingRelativePath, plan);
        attachArtifacts(architecture, artifacts);
    }

    /**
     * JobRunr discards job return values in the community edition and swallows the handler's
     * exception into the job's {@code FAILED} state rather than rethrowing it here, so a failed
     * build looks identical to a successful one from {@link WorkerRuntime#awaitCompletion} alone.
     * This re-reads the job from the store to surface the real reason.
     */
    private void checkJobSucceeded(StorageProvider storageProvider, JobId jobId, Architecture architecture)
            throws MojoFailureException {
        Job job;
        try {
            job = storageProvider.getJobById(jobId);
        } catch (JobNotFoundException e) {
            throw new MojoFailureException(
                    "Could not find job " + jobId + " after the " + architecture + " build finished", e);
        }
        Optional<FailedState> failure =
                job.getJobStatesOfType(FailedState.class).reduce((first, second) -> second);
        if (failure.isPresent()) {
            FailedState state = failure.get();
            throw new MojoFailureException(
                    architecture + " build " + jobId + " failed: " + state.getMessage() + " ("
                            + state.getExceptionType() + ": " + state.getExceptionMessage() + ")");
        }
        if (!job.hasState(StateName.SUCCEEDED)) {
            throw new MojoFailureException(
                    architecture + " build " + jobId + " ended in unexpected state "
                            + job.getState() + " instead of SUCCEEDED");
        }
    }

    private List<Path> collectArtifacts(Path mountRoot, String stagingRelativePath,
                                        NativeImageInputPlan plan) throws MojoFailureException {
        Path stagingRoot = mountRoot.resolve(stagingRelativePath).normalize();
        List<Path> artifacts =
                ArtifactCollector.collect(stagingRoot, plan.expectedArtifacts(), Instant.EPOCH);
        if (artifacts.isEmpty()) {
            throw new MojoFailureException(
                    "Build reported success but no artifact was found under " + stagingRoot);
        }
        return artifacts;
    }

    private void attachArtifacts(Architecture architecture, List<Path> artifacts) {
        for (Path artifact : artifacts) {
            String classifier = artifacts.size() == 1
                    ? architecture.artifactClassifier()
                    : architecture.artifactClassifier() + "-" + artifact.getFileName();
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

    private List<Architecture> resolveArchitectures() throws MojoFailureException {
        List<Architecture> requested;
        if (architectures == null || architectures.isEmpty()) {
            requested = List.of(Architecture.host().orElseThrow(() -> new IllegalStateException(
                    "Cannot determine the host architecture (os.arch=" + System.getProperty("os.arch")
                            + "); configure <architectures> explicitly.")));
        } else {
            requested = architectures.stream().map(Architecture::parse).distinct().toList();
        }

        List<String> mismatched = requested.stream()
                .filter(architecture -> !architecture.matchesHost())
                .map(Architecture::toString)
                .toList();
        if (!mismatched.isEmpty()) {
            throw new MojoFailureException(
                    "native-image cannot cross-compile, and this version of the plugin only builds "
                            + "locally: requested " + mismatched + " but the host is "
                            + Architecture.host().map(Enum::name).orElse(System.getProperty("os.arch"))
                            + ". Remote dispatch to other architectures is not yet implemented.");
        }
        return requested;
    }

    private StorageType parseStorageType() throws MojoExecutionException {
        try {
            return StorageType.parse(storage);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private Path resolveWorkDirectory() {
        if (workDirectory != null && !workDirectory.isBlank()) {
            return Path.of(workDirectory);
        }
        return Path.of(project.getBuild().getDirectory(), "jobrunr-build");
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
