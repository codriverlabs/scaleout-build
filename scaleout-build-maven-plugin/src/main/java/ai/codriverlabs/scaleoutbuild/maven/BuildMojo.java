/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.ArtifactCollector;
import ai.codriverlabs.scaleoutbuild.build.BuildCellRequest;
import ai.codriverlabs.scaleoutbuild.build.BuildEnvironment;
import ai.codriverlabs.scaleoutbuild.build.BuildExecutor;
import ai.codriverlabs.scaleoutbuild.build.BuildFailedException;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import ai.codriverlabs.scaleoutbuild.build.BuildResult;
import ai.codriverlabs.scaleoutbuild.build.NativeImageBuildExecutor;
import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import ai.codriverlabs.scaleoutbuild.maven.backend.BuildBackend;
import ai.codriverlabs.scaleoutbuild.maven.backend.ServiceBuildBackend;
import ai.codriverlabs.scaleoutbuild.controlplane.api.RequestedResources;
import ai.codriverlabs.scaleoutbuild.ecs.AgentContainerSettings;
import ai.codriverlabs.scaleoutbuild.ecs.CloudWatchLogTailer;
import ai.codriverlabs.scaleoutbuild.ecs.EcsClusterSettings;
import ai.codriverlabs.scaleoutbuild.ecs.EcsLaunchType;
import ai.codriverlabs.scaleoutbuild.ecs.EcsTaskLauncher;
import ai.codriverlabs.scaleoutbuild.ecs.EcsTaskSupervisor;
import ai.codriverlabs.scaleoutbuild.ecs.TaskDefinitionRegistrar;
import ai.codriverlabs.scaleoutbuild.planner.InputPlanningException;
import ai.codriverlabs.scaleoutbuild.planner.NativeImageInputPlan;
import ai.codriverlabs.scaleoutbuild.planner.NativeImageInputPlanner;
import ai.codriverlabs.scaleoutbuild.planner.ProjectInputs;
import ai.codriverlabs.scaleoutbuild.staging.LocalStagingSink;
import ai.codriverlabs.scaleoutbuild.staging.S3ArtifactRetriever;
import ai.codriverlabs.scaleoutbuild.staging.S3StagingSink;
import java.io.IOException;
import java.nio.file.Path;
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
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code scaleout-build:build} — computes a GraalVM build matrix and runs it through the builder
 * control plane, with no
 * orchestration layer above it (see {@code docs/PURE_ECS_ALTERNATIVE.md} for why, and when a
 * declarative orchestrator like Step Functions would be worth reintroducing instead).
 *
 * <p>Every requested cell whose architecture matches the host runs directly, in-process, via
 * {@link NativeImageBuildExecutor} — no AWS involved. Cells that don't match the host (and every
 * {@link BuildKind#JVM} cell needs no build at all — it is the project's already-packaged jar) each
 * get their own {@code RunTask} call and their own {@link EcsTaskSupervisor}, run concurrently
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
    @Parameter(property = "scaleout-build.buildKinds")
    private List<String> buildKinds;

    /** Target architectures for every non-JVM build kind. Defaults to the host architecture. */
    @Parameter(property = "scaleout-build.architectures")
    private List<String> architectures;

    /**
     * Local path to a {@code .iprof} profile, required when {@code native-pgo-optimize} is one of
     * the requested build kinds. Per {@code docs/DESIGN.md} §3, collecting this profile (running an
     * instrumented binary against real traffic) is out of scope for this plugin.
     */
    @Parameter(property = "scaleout-build.profilePath")
    private String profilePath;

    /** Explicit main class for derived-argfile builds; read from the artifact manifest if omitted. */
    @Parameter(property = "scaleout-build.mainClass")
    private String mainClass;

    /** Explicit output binary name for derived-argfile builds; defaults to the project's final name. */
    @Parameter(property = "scaleout-build.imageName")
    private String imageName;

    /** Extra {@code native-image} arguments appended for derived-argfile builds. */
    @Parameter
    private List<String> extraBuildArgs;

    /** Extra arguments appended to every invocation, after the argfile and build-kind flags. */
    @Parameter
    private List<String> extraNativeImageArgs;

    /** Command that invokes {@code native-image} for local-first cells, space-separated. */
    @Parameter(property = "scaleout-build.nativeImageCommand", defaultValue = "native-image")
    private String nativeImageCommand;

    /** Directory local-first builds are staged into; defaults to {@code target/scaleout-build}. */
    @Parameter(property = "scaleout-build.workDirectory")
    private String workDirectory;

    /** Soft timeout applied to each {@code native-image} process; 0 disables it. */
    @Parameter(property = "scaleout-build.timeoutMinutes", defaultValue = "0")
    private int timeoutMinutes;

    /** Overall time to wait for the remote matrix execution to finish before failing the goal. */
    @Parameter(property = "scaleout-build.overallTimeoutMinutes", defaultValue = "120")
    private int overallTimeoutMinutes;

    /** Skips the goal entirely, for profiles that only want native builds on CI. */
    /**
     * Control plane endpoint — the Lambda Function URL. The only deployment-specific value this plugin
     * needs: the signing region is parsed out of the URL host
     * ({@code <id>.lambda-url.<region>.on.aws}), and everything else about the infrastructure is the
     * service's concern. See {@code docs/design/control-plane/migration-from-direct-ecs-access.md}.
     */
    /**
     * Directory holding a {@code native-image.args} produced by GraalVM's {@code native-maven-plugin}
     * ({@code mvn native:write-args-file}). Set this for Spring Boot AOT, Helidon, or any project whose
     * native build goes through that plugin.
     *
     * <p>Leave unset for Quarkus, which is detected automatically from {@code target/native-sources}, and
     * for plain GraalVM projects, where the arguments are derived from the runtime classpath.
     *
     * <p>There is deliberately no default. {@code write-args-file} takes its location from the
     * {@code graalvm.native-image.args-file} property, and guessing wrong would not fail loudly — it would
     * fall through to the derived strategy and build a binary that omits the AOT-generated reflection
     * configuration, which fails at run time rather than at build time.
     */
    @Parameter(property = "scaleout-build.argsFileDirectory")
    private String argsFileDirectory;

    @Parameter(property = "scaleout-build.endpoint", required = true)
    private String endpoint;

    @Parameter(property = "scaleout-build.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Sends every non-JVM cell to ECS regardless of whether its architecture matches the host —
     * the default behavior otherwise always prefers building a host-matching cell locally with no
     * AWS calls at all. Set this when you specifically want every cell built remotely, e.g. to keep
     * the local machine's toolchain out of the loop entirely or to exercise the remote path for a
     * cell that happens to match the host.
     */
    @Parameter(property = "scaleout-build.forceRemote", defaultValue = "false")
    private boolean forceRemote;

    // --- Remote orchestration configuration; only required if any cell cannot run locally. ---


    @Parameter(property = "scaleout-build.requestedCpu", defaultValue = "4096")
    private String requestedCpu;
    @Parameter(property = "scaleout-build.requestedMemory", defaultValue = "16384")
    private String requestedMemory;
    @Parameter(property = "scaleout-build.requestedEphemeralStorageGiB", defaultValue = "0")
    private int requestedEphemeralStorageGiB;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("scaleout-build:build skipped (scaleout-build.skip=true)");
            return;
        }

        List<MatrixCell> cells = resolveMatrix();
        String buildId = UUID.randomUUID().toString();
        List<String> failures = new ArrayList<>();

        List<MatrixCell> jvmCells = cells.stream().filter(c -> c.buildKind() == BuildKind.JVM).toList();
        List<MatrixCell> nativeishCells =
                cells.stream().filter(c -> c.buildKind() != BuildKind.JVM).toList();

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
                    "scaleout-build:build failed for " + failures.size() + " cell(s):\n"
                            + String.join("\n", failures));
        }
    }

    // --- Matrix computation ---------------------------------------------------------------

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
                nativeishCells.stream().filter(c -> c.architecture().matchesHost()).toList();
        List<MatrixCell> remoteCells =
                nativeishCells.stream().filter(c -> !c.architecture().matchesHost()).toList();
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
                    "scaleout-build.profilePath must be set when native-pgo-optimize is requested");
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
                    + "yet; is scaleout-build:build bound after the package phase?");
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
                StagingLayout.defaults().stagingPath(buildId, cell.buildKind(), cell.architecture());
        LocalStagingSink stagingSink = new LocalStagingSink(mountRoot);
        long staged = stagingSink.stage(plan, stagingRelativePath);
        getLog().info(String.format(Locale.ROOT, "Staged %s (%d bytes) for %s", stagingRelativePath,
                staged, cell));

        String profileRelativePath = null;
        if (cell.buildKind().requiresProfile()) {
            profileRelativePath = stageProfile(mountRoot, stagingRelativePath);
        }

        BuildCellRequest request = BuildCellRequest.builder()
                .buildId(buildId)
                .buildKind(cell.buildKind())
                .architecture(cell.architecture())
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
            throw new MojoFailureException("scaleout-build.profilePath does not exist: " + profilePath);
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
        if (isBlank(endpoint)) {
            throw new MojoFailureException("scaleout-build.endpoint is required: "
                    + "remote cells are built by the control plane, which has no default address. "
                    + "Deploy it with scripts/deploy-control-plane.sh and use the endpoint it prints.");
        }
        BuildBackend backend = new ServiceBuildBackend(new ServiceBuildBackend.ServiceBuildOptions(
                endpoint, resolveWorkDirectory(), mainClass, imageName, nativeImageCommand,
                extraNativeImageArgs, extraBuildArgs, timeoutMinutes, overallTimeoutMinutes,
                new RequestedResources(requestedCpu, requestedMemory, requestedEphemeralStorageGiB),
                "scaleout-build-maven-plugin/" + pluginVersion()), getLog());
        return backend.runCells(remoteCells, plan, buildId, this::attachArtifacts);
    }

    /** Reported to the service so an operator can spot clients predating a contract change. */
    private String pluginVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // --- Shared helpers ---------------------------------------------------------------------

    private void attachArtifacts(MatrixCell cell, List<Path> artifacts) {
        for (Path artifact : artifacts) {
            String classifierBase = cell.architecture() == null ? cell.buildKind().configValue()
                    : cell.buildKind().configValue() + "-" + cell.architecture().artifactClassifier();
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
        return Path.of(project.getBuild().getDirectory(), "scaleout-build");
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
                imageName, extraBuildArgs == null ? List.of() : extraBuildArgs,
                isBlank(argsFileDirectory) ? null : Path.of(argsFileDirectory));
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
