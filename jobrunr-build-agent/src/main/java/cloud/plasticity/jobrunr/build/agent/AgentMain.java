/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.agent;

import cloud.plasticity.jobrunr.build.BuildCellRequest;
import cloud.plasticity.jobrunr.build.BuildEnvironment;
import cloud.plasticity.jobrunr.build.BuildExecutor;
import cloud.plasticity.jobrunr.build.BuildFailedException;
import cloud.plasticity.jobrunr.build.BuildLog;
import cloud.plasticity.jobrunr.build.BuildResult;
import cloud.plasticity.jobrunr.build.NativeImageBuildExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the worker mounted into the builder image.
 *
 * <p>Single-shot: read the one matrix cell this task was launched for (via environment variables
 * set as ECS task overrides by the Step Functions state machine's {@code RunTask.sync} state, see
 * {@code docs/DESIGN.md} §5), run it, exit. No polling loop and no job store — there is nothing to
 * claim, since the state machine already decided which cell this specific task runs.
 *
 * <p>A Fargate Spot interruption simply kills this process; Step Functions' {@code Retry} on the
 * calling state relaunches a replacement task for the same cell, per §5.
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
        System.exit(run(config, BuildLog.defaultLog()));
    }

    /**
     * Runs the one configured build cell to completion.
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
                    + "Step Functions will relaunch this cell", config.buildId());
            return EXIT_BUILD_FAILED;
        }
    }
}
