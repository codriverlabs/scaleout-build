/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build.agent;

import ai.codriverlabs.scaleoutbuild.build.BuildCellRequest;
import ai.codriverlabs.scaleoutbuild.build.BuildEnvironment;
import ai.codriverlabs.scaleoutbuild.build.BuildExecutor;
import ai.codriverlabs.scaleoutbuild.build.BuildFailedException;
import ai.codriverlabs.scaleoutbuild.build.BuildLog;
import ai.codriverlabs.scaleoutbuild.build.BuildResult;
import ai.codriverlabs.scaleoutbuild.build.NativeImageBuildExecutor;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import ai.codriverlabs.scaleoutbuild.build.ArtifactCollector;
import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import software.amazon.awssdk.services.s3.S3Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the worker mounted into (or, in direct-S3-calls mode, otherwise given credentials
 * for) the builder image.
 *
 * <p>Single-shot: read the one matrix cell this task was launched for (via environment variables
 * set as ECS task overrides by {@code BuildMojo}'s own {@code RunTask} call), run it, exit. No
 * polling loop and no job store — there is nothing to claim, since the plugin already decided which
 * cell this specific task runs.
 *
 * <p>Two I/O modes, selected by {@link AgentConfig#usesDirectS3Io()} (see {@link S3Io}'s class
 * Javadoc for the tradeoffs): a mount-based mode, where {@link BuildEnvironment} reads and writes
 * directly against an already-mounted {@code mountRoot}, and a direct-S3-calls mode, where this
 * class downloads inputs to a local temp directory, runs the same {@link BuildEnvironment} against
 * that local directory instead, and uploads produced artifacts back to S3 itself afterward.
 *
 * <p>A Fargate Spot interruption simply kills this process; {@code BuildMojo}'s own supervision loop
 * relaunches a replacement task for the same cell.
 */
public final class AgentMain {

    private static final Logger LOG = LoggerFactory.getLogger(AgentMain.class);

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_BUILD_FAILED = 1;
    static final int EXIT_CONFIGURATION_ERROR = 78; // EX_CONFIG

    private AgentMain() {
    }

    public static void main(String[] args) {
        AgentConfig config;
        try {
            config = AgentConfig.fromEnvironment();
        } catch (RuntimeException e) {
            LOG.error("Invalid agent configuration: {}", e.getMessage());
            System.exit(EXIT_CONFIGURATION_ERROR);
            return;
        }
        LOG.info("Starting build agent with {}", config);
        BuildLog buildLog = BuildLog.defaultLog();
        int exitCode = config.usesDirectS3Io()
                ? runWithDirectS3Io(config, buildLog, S3Client.create())
                : run(config, buildLog);
        System.exit(exitCode);
    }

    /**
     * Runs the one configured build cell to completion, against an already-mounted
     * {@code mountRoot}.
     *
     * <p>Separated from {@link #main(String[])} so the whole agent path can be exercised without a
     * container.
     *
     * @return process exit code
     */
    public static int run(AgentConfig config, BuildLog buildLog) {
        BuildEnvironment environment = BuildEnvironment.builder(config.mountRoot())
                .nativeImageCommand(config.nativeImageCommand())
                .tempDirectory(config.tempDirectory())
                .build();
        return runAgainst(config, buildLog, environment);
    }

    /**
     * Runs the one configured build cell to completion using the direct-S3-calls I/O mode:
     * downloads staged inputs to a local temp directory, builds against that directory with the
     * same {@link BuildEnvironment}/{@link NativeImageBuildExecutor} the mount mode uses unchanged,
     * then uploads produced artifacts back to S3 under the same {@code output/} key the plugin's
     * {@code S3ArtifactRetriever} already looks for — that class needs no changes for this mode.
     *
     * <p>Separated from {@link #main(String[])} so it can be exercised against a fake/local S3
     * endpoint in tests, the same way {@link #run(AgentConfig, BuildLog)} is exercised without a
     * container.
     *
     * @return process exit code
     */
    public static int runWithDirectS3Io(AgentConfig config, BuildLog buildLog, S3Client s3Client) {
        S3Io s3Io = new S3Io(s3Client, config.s3Bucket());
        // A synthetic local "mount root": BuildEnvironment.resolveStagingRoot() resolves
        // stagingRelativePath against whatever root it's given, so downloading straight into
        // localMountRoot.resolve(stagingRelativePath) makes it land exactly where that resolution
        // will independently look -- no path translation needed, and BuildEnvironment/
        // NativeImageBuildExecutor run completely unaware this root is a temp download rather than
        // an ECS mount.
        Path localMountRoot =
                config.tempDirectory().resolve("s3-staging").resolve(config.buildId());
        Path localStagingDir = localMountRoot.resolve(config.stagingRelativePath());
        try {
            s3Io.downloadStagingDirectory(config.stagingRelativePath(), localStagingDir);

            BuildEnvironment environment = BuildEnvironment.builder(localMountRoot)
                    .nativeImageCommand(config.nativeImageCommand())
                    .tempDirectory(config.tempDirectory())
                    .build();
            // Captured before the build so ArtifactCollector's scan can distinguish output from the
            // inputs that were downloaded moments earlier. Truncated to seconds because some filesystems
            // store mtime at that granularity, and a too-precise floor would exclude real output.
            Instant buildStartedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            int exitCode = runAgainst(config, buildLog, environment);

            if (exitCode == EXIT_SUCCESS) {
                Path localOutputDir = localStagingDir.resolve(StagingLayout.OUTPUT_DIR_NAME);
                /*
                 * Gather artifacts into output/ before uploading, because a pass-through argfile does not
                 * necessarily write there.
                 *
                 * A derived argfile is generated by the plugin and directs output into output/, so this is
                 * a no-op for plain GraalVM projects. A framework-generated argfile names its own output
                 * with a bare -o, which native-image resolves against the working directory -- the staging
                 * root. Quarkus does exactly that (-o <finalName>-runner), so the binary landed beside the
                 * inputs while this step uploaded an empty output/ and reported success with no artifacts.
                 *
                 * ArtifactCollector already searches both locations, so the fix is to honour what it found
                 * rather than to assume a directory. Uploading output/ as a prefix is kept: the service
                 * presigns that prefix when listing artifacts, so moving files into it preserves the S3
                 * layout both sides agree on.
                 */
                collectStrayArtifactsInto(localStagingDir, localOutputDir, config, buildStartedAt);
                String outputKeyPrefix = joinKey(config.stagingRelativePath(),
                        StagingLayout.OUTPUT_DIR_NAME);
                s3Io.uploadOutputDirectory(localOutputDir, outputKeyPrefix);
            } else {
                LOG.info("Build did not succeed (exit {}); skipping artifact upload. The agent's own "
                        + "log output above (captured by CloudWatch Logs) is the record of what "
                        + "happened, same as it would be after a mount-mode failure.", exitCode);
            }
            return exitCode;
        } catch (IOException e) {
            LOG.error("Direct-S3-calls I/O failed for build {}: {}", config.buildId(), e.getMessage(),
                    e);
            return EXIT_BUILD_FAILED;
        } finally {
            deleteRecursively(localMountRoot);
        }
    }

    private static int runAgainst(AgentConfig config, BuildLog buildLog,
                                   BuildEnvironment environment) {
        BuildExecutor executor = new NativeImageBuildExecutor(environment);

        BuildCellRequest request = BuildCellRequest.builder()
                .buildId(config.buildId())
                .buildKind(config.buildKind())
                .architecture(config.architecture())
                .stagingRelativePath(config.stagingRelativePath())
                .argFileName(config.argFileName())
                .profileRelativePath(config.profileRelativePath())
                .expectedArtifacts(config.expectedArtifacts())
                .extraNativeImageArgs(config.extraNativeImageArgs())
                .timeoutMinutes(config.timeoutMinutes())
                .build();

        try {
            BuildResult result = executor.execute(request, buildLog);
            LOG.info("Completed {} build {} for {} in {} producing {}",
                    config.buildKind(), config.buildId(), config.architecture(), result.duration(),
                    result.artifacts());
            return EXIT_SUCCESS;
        } catch (BuildFailedException e) {
            LOG.error("Build {} failed: {}", config.buildId(), e.getMessage(), e);
            return EXIT_BUILD_FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while building {} — likely a Spot interruption; "
                    + "BuildMojo will relaunch this cell", config.buildId());
            return EXIT_BUILD_FAILED;
        }
    }

    private static String joinKey(String prefix, String relativePath) {
        return prefix.endsWith("/") ? prefix + relativePath : prefix + "/" + relativePath;
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.debug("Failed to delete {} during local staging cleanup: {}", path,
                            e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.debug("Failed to walk {} during local staging cleanup: {}", root, e.getMessage());
        }
    }
    /**
     * Moves any artifact that landed outside {@code output/} into it, so the upload finds it.
     *
     * <p>Needed for pass-through argfiles. See the call site for why. Copies rather than moves nothing:
     * a file already in {@code output/} is left alone, and anything found in the staging root is moved so
     * it cannot be uploaded twice under two keys.
     */
    private static void collectStrayArtifactsInto(Path stagingRoot, Path outputDir, AgentConfig config,
                                                  Instant buildStartedAt) throws IOException {
        Files.createDirectories(outputDir);
        List<Path> artifacts = ArtifactCollector.collect(stagingRoot, config.expectedArtifacts(),
                buildStartedAt);
        for (Path artifact : artifacts) {
            if (artifact.getParent() != null && artifact.getParent().equals(outputDir)) {
                continue;
            }
            Path destination = outputDir.resolve(artifact.getFileName().toString());
            LOG.info("Artifact {} was written outside {}/; moving it there so it is uploaded",
                    artifact.getFileName(), StagingLayout.OUTPUT_DIR_NAME);
            Files.move(artifact, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        if (artifacts.isEmpty()) {
            LOG.warn("Build succeeded but no artifact was found under {} or {}/. If this is a "
                    + "framework-generated argfile, check what its -o argument names.",
                    stagingRoot, StagingLayout.OUTPUT_DIR_NAME);
        }
    }

}
