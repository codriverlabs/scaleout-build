/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.agent;

import cloud.plasticity.jobrunr.build.BuildEnvironment;
import cloud.plasticity.jobrunr.build.BuildExecutor;
import cloud.plasticity.jobrunr.build.BuildLog;
import cloud.plasticity.jobrunr.build.NativeImageBuildExecutor;
import cloud.plasticity.jobrunr.build.WorkerRuntime;
import cloud.plasticity.jobrunr.build.storage.DsqlConnectionSettings;
import cloud.plasticity.jobrunr.build.storage.StorageProviderFactory;
import cloud.plasticity.jobrunr.build.storage.StorageSettings;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.utils.mapper.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the worker mounted into the builder image.
 *
 * <p>Lifecycle: read configuration, start a single-worker JobRunr server against this
 * architecture's schema, process the configured number of jobs, exit. The container is therefore
 * ephemeral — there is no long-running service to scale down.
 *
 * <p>{@code SIGTERM} (a Fargate Spot reclaim, among others) triggers a graceful worker shutdown so
 * JobRunr re-queues the in-flight job for a replacement task instead of failing the build.
 */
public final class AgentMain {

    private static final Logger LOG = LoggerFactory.getLogger(AgentMain.class);

    /** Distinguishes "did the work" from "found nothing to do" for the surrounding task. */
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_NO_JOB_PROCESSED = 3;
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

        StorageProvider storageProvider;
        JsonMapper jsonMapper = StorageProviderFactory.jsonMapper();
        try {
            storageProvider = buildStorageProvider(config, jsonMapper);
        } catch (RuntimeException e) {
            LOG.error("Failed to connect to the DSQL job store: {}", e.getMessage(), e);
            System.exit(EXIT_CONFIGURATION_ERROR);
            return;
        }

        int exitCode = run(config, storageProvider, jsonMapper);
        System.exit(exitCode);
    }

    /**
     * Builds the DSQL-backed storage provider for this agent's architecture, using the schema
     * derived from {@link AgentConfig#schemaPrefix()} and {@link AgentConfig#architecture()} — the
     * same derivation the plugin side uses, so both name the same schema for the same architecture.
     */
    private static StorageProvider buildStorageProvider(AgentConfig config, JsonMapper jsonMapper) {
        DsqlConnectionSettings connectionSettings = DsqlConnectionSettings.of(
                config.dsqlEndpoint(), config.dsqlRegion(), config.dsqlUser());
        StorageSettings storageSettings =
                StorageSettings.dsql(connectionSettings, config.schemaPrefix());
        return StorageProviderFactory.create(storageSettings, config.architecture(), jsonMapper);
    }

    /**
     * Runs the worker against an already-constructed storage provider.
     *
     * <p>Separated from {@link #main(String[])} so the whole agent path — claim, build, exit — can be
     * exercised without a container or a database.
     *
     * @return process exit code
     */
    public static int run(AgentConfig config, StorageProvider storageProvider, JsonMapper jsonMapper) {
        BuildEnvironment environment = BuildEnvironment.builder(config.mountRoot())
                .nativeImageCommand(config.nativeImageCommand())
                .tempDirectory(config.tempDirectory())
                .build();
        BuildExecutor executor = new NativeImageBuildExecutor(environment);

        WorkerRuntime.WorkerOptions options = WorkerRuntime.WorkerOptions.defaults()
                .name("jobrunr-build-agent-" + config.architecture().schemaSuffix())
                .workerCount(1)
                .idleTimeout(config.idleTimeout())
                .overallTimeout(config.maxDuration())
                .buildLog(BuildLog.defaultLog());

        try (WorkerRuntime worker = WorkerRuntime.start(storageProvider, jsonMapper, executor, options)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.warn("Received shutdown signal, stopping worker so the job can be re-queued");
                worker.close();
            }, "agent-shutdown"));

            boolean completed = worker.awaitCompletion(config.jobsToProcess());
            if (completed) {
                return EXIT_SUCCESS;
            }
            if (worker.tracker().startedCount() == 0) {
                LOG.warn("No job was claimed within {}; exiting so the task stops billing",
                        config.idleTimeout());
                return EXIT_NO_JOB_PROCESSED;
            }
            LOG.error("Worker exceeded its maximum duration of {} with {} job(s) finished",
                    config.maxDuration(), worker.tracker().finishedCount());
            return EXIT_NO_JOB_PROCESSED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting for the build to finish; job will be re-queued");
            return EXIT_NO_JOB_PROCESSED;
        }
    }
}
