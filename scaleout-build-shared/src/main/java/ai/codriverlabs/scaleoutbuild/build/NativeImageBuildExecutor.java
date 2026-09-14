/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code native-image @<argfile>} against a staged build.
 *
 * <p>The argfile is passed through unchanged and the process runs with the staging root as its
 * working directory. That is what lets one argfile serve both a local run and a container run: every
 * path inside it is relative to that root.
 *
 * <p>No Maven is involved. The remote side compiles an already-built classpath, which is why the
 * container only needs a native-image distribution and not a build tool.
 */
public final class NativeImageBuildExecutor implements BuildExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(NativeImageBuildExecutor.class);

    private final BuildEnvironment environment;

    public NativeImageBuildExecutor(BuildEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public BuildResult execute(BuildCellRequest request, BuildLog log)
            throws BuildFailedException, InterruptedException {
        Objects.requireNonNull(request, "request");
        if (!request.getBuildKind().requiresArchitecture()) {
            throw new BuildFailedException(
                    "NativeImageBuildExecutor does not handle build kind " + request.getBuildKind()
                            + "; JVM-kind cells never invoke native-image");
        }
        BuildLog output = log == null ? BuildLog.discarding() : log;

        Path stagingRoot = environment.resolveStagingRoot(request);
        Path argFile = stagingRoot.resolve(request.getArgFileName());
        if (!Files.isRegularFile(argFile)) {
            throw new BuildFailedException("Argument file not found: " + argFile);
        }
        createOutputDirectory(stagingRoot);

        List<String> command = buildCommand(request);
        output.line("[scaleout-build] " + String.join(" ", command));
        output.line("[scaleout-build] working directory: " + stagingRoot);

        Instant startedAt = Instant.now();
        Process process = startProcess(command, stagingRoot);
        Thread pump = startOutputPump(process, output);
        try {
            int exitCode = awaitExit(process, request.getTimeoutMinutes(), command);
            pump.join(TimeUnit.SECONDS.toMillis(10));
            Duration duration = Duration.between(startedAt, Instant.now());
            if (exitCode != 0) {
                throw new BuildFailedException(
                        "native-image failed with exit code " + exitCode + " after " + duration,
                        exitCode);
            }
            List<Path> artifacts =
                    ArtifactCollector.collect(stagingRoot, request.getExpectedArtifacts(), startedAt);
            if (artifacts.isEmpty()) {
                throw new BuildFailedException(
                        "native-image reported success but produced no artifact under " + stagingRoot);
            }
            artifacts.forEach(artifact -> output.line("[scaleout-build] produced " + artifact));
            return new BuildResult(exitCode, duration, artifacts);
        } catch (InterruptedException e) {
            // Typically a Fargate Spot interruption: stop the builder; the caller (BuildMojo's
            // local-first path, or the agent's single-shot execution) decides what happens next.
            LOG.warn("Build interrupted, terminating native-image process");
            process.destroyForcibly();
            throw e;
        } finally {
            pump.interrupt();
        }
    }

    private List<String> buildCommand(BuildCellRequest request) {
        List<String> command = new ArrayList<>(environment.nativeImageCommand());
        command.add("@" + request.getArgFileName());
        command.addAll(request.getBuildKind().nativeImageFlags(request.getProfileRelativePath()));
        command.addAll(request.getExtraNativeImageArgs());
        return List.copyOf(command);
    }

    private void createOutputDirectory(Path stagingRoot) throws BuildFailedException {
        try {
            Files.createDirectories(stagingRoot.resolve(StagingLayout.OUTPUT_DIR_NAME));
        } catch (IOException e) {
            throw new BuildFailedException("Failed to create output directory under " + stagingRoot, e);
        }
    }

    private Process startProcess(List<String> command, Path workingDirectory)
            throws BuildFailedException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Path tempDirectory = environment.tempDirectory();
        if (tempDirectory != null) {
            // Keep native-image scratch traffic on local ephemeral storage, not the shared mount.
            builder.environment().put("TMPDIR", tempDirectory.toAbsolutePath().toString());
        }
        try {
            return builder.start();
        } catch (IOException e) {
            throw new BuildFailedException(
                    "Failed to start native-image using command " + command
                            + " (is the command available on this host or in the builder image?)", e);
        }
    }

    private Thread startOutputPump(Process process, BuildLog output) {
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.line(line);
                }
            } catch (IOException e) {
                LOG.debug("Stopped reading native-image output: {}", e.getMessage());
            }
        }, "native-image-output");
        pump.setDaemon(true);
        pump.start();
        return pump;
    }

    private int awaitExit(Process process, int timeoutMinutes, List<String> command)
            throws InterruptedException, BuildFailedException {
        if (timeoutMinutes <= 0) {
            return process.waitFor();
        }
        if (process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            return process.exitValue();
        }
        process.destroyForcibly();
        throw new BuildFailedException(
                "native-image exceeded the " + timeoutMinutes + " minute timeout: "
                        + String.join(" ", command));
    }
}
