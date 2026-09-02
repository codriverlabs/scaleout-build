/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.ecs;

import cloud.plasticity.jobrunr.build.BuildLog;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobId;
import org.jobrunr.jobs.states.FailedState;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches one build job to completion, relaunching its Fargate Spot task if reclaimed and failing
 * the Maven build on remote failure or timeout.
 *
 * <p>Per the design's explicit rule, completion is always determined from the job's state in the
 * database — {@code SUCCEEDED} or {@code FAILED} — never from the task's own status. Concurrent
 * same-architecture tasks can steal each other's jobs, which is harmless, but it means a task
 * reaching a terminal ECS status is not itself a completion signal. The task's status is only
 * consulted to notice a Spot interruption (the job returned to {@code ENQUEUED} while the task that
 * was processing it is no longer running) so the supervisor knows to launch a replacement.
 */
public final class BuildSupervisor {

    private static final Logger LOG = LoggerFactory.getLogger(BuildSupervisor.class);

    private final FargateTaskLauncher launcher;
    private final CloudWatchLogTailer logTailer;
    private final StorageProvider storageProvider;
    private final software.amazon.awssdk.services.ecs.EcsClient ecsClient;

    public BuildSupervisor(FargateTaskLauncher launcher, CloudWatchLogTailer logTailer,
                           StorageProvider storageProvider,
                           software.amazon.awssdk.services.ecs.EcsClient ecsClient) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.logTailer = Objects.requireNonNull(logTailer, "logTailer");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.ecsClient = Objects.requireNonNull(ecsClient, "ecsClient");
    }

    /** Tuning knobs for one supervised build. */
    public static final class SupervisionOptions {
        private Duration pollInterval = Duration.ofSeconds(5);
        private Duration overallTimeout = Duration.ofHours(2);
        private int maxSpotInterruptionsBeforeOnDemand = 2;

        public static SupervisionOptions defaults() {
            return new SupervisionOptions();
        }

        public SupervisionOptions pollInterval(Duration pollInterval) {
            this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
            return this;
        }

        public SupervisionOptions overallTimeout(Duration overallTimeout) {
            this.overallTimeout = Objects.requireNonNull(overallTimeout, "overallTimeout");
            return this;
        }

        /**
         * After this many Spot reclamations of the same job, relaunches prefer on-demand capacity
         * instead of continuing to prefer Spot.
         */
        public SupervisionOptions maxSpotInterruptionsBeforeOnDemand(int max) {
            if (max < 0) {
                throw new IllegalArgumentException("max must not be negative");
            }
            this.maxSpotInterruptionsBeforeOnDemand = max;
            return this;
        }
    }

    /** Outcome of supervising one build to completion, timeout, or failure. */
    public record SupervisionResult(boolean succeeded, String failureReason, int spotInterruptions) {
    }

    /**
     * Launches the first task and supervises the job until it succeeds, fails, or the overall
     * timeout elapses, relaunching on Spot interruption and tailing the task's logs throughout.
     */
    public SupervisionResult supervise(EcsClusterSettings clusterSettings, String taskDefinitionArn,
                                       JobId jobId, String logStreamNamePrefix, BuildLog logSink,
                                       SupervisionOptions options) throws InterruptedException {
        Objects.requireNonNull(clusterSettings, "clusterSettings");
        Objects.requireNonNull(taskDefinitionArn, "taskDefinitionArn");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(logStreamNamePrefix, "logStreamNamePrefix");
        Objects.requireNonNull(logSink, "logSink");
        SupervisionOptions effectiveOptions = options == null ? SupervisionOptions.defaults() : options;

        String currentTaskArn = launcher.runTask(clusterSettings, taskDefinitionArn, false);
        int spotInterruptions = 0;
        StateName lastObservedState = StateName.ENQUEUED;
        Instant logsSince = Instant.EPOCH;
        Instant deadline = Instant.now().plus(effectiveOptions.overallTimeout);

        while (true) {
            logsSince = logTailer.pollOnce(clusterSettings.logGroupName(), logStreamNamePrefix,
                    logsSince, logSink);

            Job job = storageProvider.getJobById(jobId);
            StateName currentState = job.getState();

            if (currentState == StateName.SUCCEEDED) {
                LOG.info("Job {} succeeded", jobId);
                return new SupervisionResult(true, null, spotInterruptions);
            }
            if (currentState == StateName.FAILED) {
                String reason = describeFailure(job);
                LOG.error("Job {} failed: {}", jobId, reason);
                return new SupervisionResult(false, reason, spotInterruptions);
            }

            if (lastObservedState == StateName.PROCESSING && currentState == StateName.ENQUEUED
                    && !isTaskStillRunning(clusterSettings, currentTaskArn)) {
                spotInterruptions++;
                boolean preferOnDemand =
                        spotInterruptions > effectiveOptions.maxSpotInterruptionsBeforeOnDemand;
                LOG.warn("Job {} was re-queued after its task stopped (interruption #{}); "
                        + "relaunching{}", jobId, spotInterruptions,
                        preferOnDemand ? " preferring on-demand capacity" : "");
                currentTaskArn = launcher.runTask(clusterSettings, taskDefinitionArn, preferOnDemand);
            }
            lastObservedState = currentState;

            if (Instant.now().isAfter(deadline)) {
                launcher.stopTask(clusterSettings, currentTaskArn,
                        "jobrunr:build exceeded its overall timeout");
                return new SupervisionResult(false,
                        "Exceeded overall timeout of " + effectiveOptions.overallTimeout
                                + " waiting for job " + jobId + " to finish",
                        spotInterruptions);
            }

            Thread.sleep(effectiveOptions.pollInterval.toMillis());
        }
    }

    private boolean isTaskStillRunning(EcsClusterSettings clusterSettings, String taskArn) {
        var response = ecsClient.describeTasks(
                b -> b.cluster(clusterSettings.clusterArn()).tasks(taskArn));
        return response.tasks().stream()
                .anyMatch(task -> !"STOPPED".equals(task.lastStatus()));
    }

    private String describeFailure(Job job) {
        return job.getJobStatesOfType(FailedState.class)
                .reduce((first, second) -> second)
                .map(state -> state.getMessage() + " (" + state.getExceptionType() + ": "
                        + state.getExceptionMessage() + ")")
                .orElse("Job ended in FAILED state with no recorded exception details");
    }
}
