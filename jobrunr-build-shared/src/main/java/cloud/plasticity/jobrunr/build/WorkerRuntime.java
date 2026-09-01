/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import java.time.Duration;
import java.util.Objects;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.server.BackgroundJobServerConfiguration;
import org.jobrunr.server.JobActivator;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.utils.mapper.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single-purpose JobRunr worker that runs one build and then stops.
 *
 * <p>Used by both the container agent and the plugin's local mode, so the code path exercised by
 * unit tests is the same one that runs on Fargate.
 *
 * <p>Constructs {@link BackgroundJobServer} directly instead of going through JobRunr's static
 * {@code JobRunr.configure()}. That global is a poor fit for a Maven plugin, which lives in a
 * long-running JVM that may execute several builds in one session.
 */
public final class WorkerRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerRuntime.class);

    /** JobRunr rejects poll intervals below this. */
    private static final int MINIMUM_POLL_INTERVAL_SECONDS = 5;

    private final BackgroundJobServer server;
    private final JobCompletionTracker tracker;
    private final WorkerOptions options;

    private WorkerRuntime(BackgroundJobServer server, JobCompletionTracker tracker,
                          WorkerOptions options) {
        this.server = server;
        this.tracker = tracker;
        this.options = options;
    }

    /**
     * Starts a worker that polls {@code storageProvider} and runs builds through {@code executor}.
     *
     * <p>The storage provider must already have its {@code JobMapper} set; use
     * {@link cloud.plasticity.jobrunr.build.storage.StorageProviderFactory}.
     */
    public static WorkerRuntime start(StorageProvider storageProvider, JsonMapper jsonMapper,
                                      BuildExecutor executor, WorkerOptions options) {
        Objects.requireNonNull(storageProvider, "storageProvider");
        Objects.requireNonNull(jsonMapper, "jsonMapper");
        Objects.requireNonNull(executor, "executor");
        WorkerOptions effectiveOptions = options == null ? WorkerOptions.defaults() : options;

        JobCompletionTracker tracker = new JobCompletionTracker();
        BuildJobRequestHandler handler =
                new BuildJobRequestHandler(executor, tracker, effectiveOptions.buildLog());

        BackgroundJobServerConfiguration configuration =
                BackgroundJobServerConfiguration.usingStandardBackgroundJobServerConfiguration()
                        .andName(effectiveOptions.name())
                        .andWorkerCount(effectiveOptions.workerCount())
                        .andPollIntervalInSeconds(Math.max(MINIMUM_POLL_INTERVAL_SECONDS,
                                (int) effectiveOptions.pollInterval().toSeconds()));

        BackgroundJobServer server = new BackgroundJobServer(
                storageProvider, jsonMapper, activatorFor(handler), configuration);
        server.start();
        LOG.info("Worker '{}' started with {} worker thread(s), polling every {}s",
                effectiveOptions.name(), effectiveOptions.workerCount(),
                effectiveOptions.pollInterval().toSeconds());
        return new WorkerRuntime(server, tracker, effectiveOptions);
    }

    /**
     * Supplies the per-worker handler instance. Returns {@code null} for anything else so JobRunr
     * falls back to its own instantiation for job types this worker does not own.
     */
    private static JobActivator activatorFor(BuildJobRequestHandler handler) {
        return new JobActivator() {
            @Override
            public <T> T activateJob(Class<T> type) {
                return type.isAssignableFrom(BuildJobRequestHandler.class) ? type.cast(handler) : null;
            }
        };
    }

    public JobCompletionTracker tracker() {
        return tracker;
    }

    /**
     * Waits for {@code expectedJobs} jobs to terminate.
     *
     * @return false if the overall timeout elapsed, or if no job was ever claimed within the idle
     *         timeout — the signal an ephemeral task uses to exit instead of billing while idle
     */
    public boolean awaitCompletion(int expectedJobs) throws InterruptedException {
        return tracker.awaitCompletion(expectedJobs, options.overallTimeout(), options.idleTimeout());
    }

    @Override
    public void close() {
        try {
            server.stop();
            LOG.info("Worker '{}' stopped after {} finished job(s)",
                    options.name(), tracker.finishedCount());
        } catch (RuntimeException e) {
            LOG.warn("Worker '{}' did not stop cleanly: {}", options.name(), e.getMessage());
        }
    }

    /** Tuning knobs for {@link WorkerRuntime}. */
    public static final class WorkerOptions {

        private String name = "jobrunr-build-worker";
        private int workerCount = 1;
        private Duration pollInterval = Duration.ofSeconds(MINIMUM_POLL_INTERVAL_SECONDS);
        private Duration overallTimeout = Duration.ofHours(2);
        private Duration idleTimeout = Duration.ofMinutes(5);
        private BuildLog buildLog = BuildLog.defaultLog();

        public static WorkerOptions defaults() {
            return new WorkerOptions();
        }

        public WorkerOptions name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /**
         * One by default: a task exists to run a single build, and concurrent native-image runs on
         * one task would contend for the memory that makes the build viable at all.
         */
        public WorkerOptions workerCount(int workerCount) {
            if (workerCount < 1) {
                throw new IllegalArgumentException("workerCount must be at least 1");
            }
            this.workerCount = workerCount;
            return this;
        }

        public WorkerOptions pollInterval(Duration pollInterval) {
            this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
            return this;
        }

        public WorkerOptions overallTimeout(Duration overallTimeout) {
            this.overallTimeout = Objects.requireNonNull(overallTimeout, "overallTimeout");
            return this;
        }

        /** Set to {@code null} to wait indefinitely for a job to be claimed. */
        public WorkerOptions idleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
            return this;
        }

        public WorkerOptions buildLog(BuildLog buildLog) {
            this.buildLog = buildLog;
            return this;
        }

        public String name() {
            return name;
        }

        public int workerCount() {
            return workerCount;
        }

        public Duration pollInterval() {
            return pollInterval;
        }

        public Duration overallTimeout() {
            return overallTimeout;
        }

        public Duration idleTimeout() {
            return idleTimeout;
        }

        public BuildLog buildLog() {
            return buildLog;
        }
    }
}
