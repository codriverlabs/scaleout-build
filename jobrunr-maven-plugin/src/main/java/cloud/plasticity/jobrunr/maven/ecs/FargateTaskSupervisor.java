/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.ecs;

import cloud.plasticity.jobrunr.build.BuildLog;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Container;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches one Fargate task through to completion, without any orchestration layer above ECS — the
 * "pure ECS" alternative to {@code docs/DESIGN.md}'s Step Functions design, for when the matrix is
 * small and fixed (e.g. exactly x86_64 + arm64) and the extra declarative machinery isn't worth it.
 * See {@code docs/PURE_ECS_ALTERNATIVE.md} for the tradeoff analysis.
 *
 * <p><b>Spot interruption detection is a real gap relative to Step Functions' typed
 * {@code Retry}/{@code ErrorEquals}, and worth understanding precisely rather than assuming.</b>
 * ECS's {@code stopCode} field is a strict enum — confirmed against the SDK model —
 * of only {@code TaskFailedToStart}, {@code EssentialContainerExited}, and {@code UserInitiated}.
 * There is no dedicated stop code for a Spot reclaim. AWS's documented signal is the free-text
 * {@code stoppedReason} field, whose value for a genuine Spot interruption is specifically
 * {@code "Your Spot Task was interrupted."} (confirmed against AWS's own troubleshooting guidance
 * and support threads, not guessed) — this class matches that string, but it is not a stable typed
 * value the way Step Functions' error names are, and a wording change on AWS's side would silently
 * break the match. Also worth knowing: a stopped task's details, including {@code stoppedReason},
 * are only available via {@code DescribeTasks} for one hour after the task stops — not a concern
 * for this class's own tight poll loop, but relevant if anyone reuses this detection logic
 * elsewhere against an already-stopped task discovered later.
 */
public final class FargateTaskSupervisor {

    private static final Logger LOG = LoggerFactory.getLogger(FargateTaskSupervisor.class);

    /**
     * The exact, documented free-text value ECS uses for a genuine Fargate Spot reclaim. Matched
     * case-sensitively and verbatim rather than with a loose substring check, since a partial match
     * risks false positives against an unrelated {@code stoppedReason} that happens to share words.
     */
    static final String SPOT_INTERRUPTION_STOPPED_REASON = "Your Spot Task was interrupted.";

    private final FargateTaskLauncher launcher;
    private final CloudWatchLogTailer logTailer;
    private final EcsClient ecsClient;

    public FargateTaskSupervisor(FargateTaskLauncher launcher, CloudWatchLogTailer logTailer,
                                 EcsClient ecsClient) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.logTailer = Objects.requireNonNull(logTailer, "logTailer");
        this.ecsClient = Objects.requireNonNull(ecsClient, "ecsClient");
    }

    /** Tuning knobs for one supervised task. */
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

        public SupervisionOptions maxSpotInterruptionsBeforeOnDemand(int max) {
            if (max < 0) {
                throw new IllegalArgumentException("max must not be negative");
            }
            this.maxSpotInterruptionsBeforeOnDemand = max;
            return this;
        }
    }

    /** Outcome of supervising one task (across however many Spot-interruption relaunches). */
    public record SupervisionResult(boolean succeeded, boolean timedOut, String failureReason,
                                    int spotInterruptions) {
    }

    /**
     * Launches the task and supervises it — tailing its logs and relaunching on Spot interruption —
     * until it exits, is stopped for a genuine reason, or the overall timeout elapses.
     */
    public SupervisionResult supervise(EcsClusterSettings clusterSettings, String taskDefinitionArn,
                                       List<KeyValuePair> environment, String logStreamNamePrefix,
                                       BuildLog logSink, SupervisionOptions options)
            throws InterruptedException {
        Objects.requireNonNull(clusterSettings, "clusterSettings");
        Objects.requireNonNull(taskDefinitionArn, "taskDefinitionArn");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(logStreamNamePrefix, "logStreamNamePrefix");
        Objects.requireNonNull(logSink, "logSink");
        SupervisionOptions effectiveOptions = options == null ? SupervisionOptions.defaults() : options;

        String currentTaskArn = launcher.runTask(clusterSettings, taskDefinitionArn, environment,
                false);
        int spotInterruptions = 0;
        Instant logsSince = Instant.EPOCH;
        Instant deadline = Instant.now().plus(effectiveOptions.overallTimeout);

        while (true) {
            logsSince = tailLogsBestEffort(clusterSettings, logStreamNamePrefix, logsSince, logSink);

            Task task = describeTask(clusterSettings, currentTaskArn);
            if (task != null && "STOPPED".equals(task.lastStatus())) {
                if (isSpotInterruption(task)) {
                    spotInterruptions++;
                    boolean preferOnDemand =
                            spotInterruptions > effectiveOptions.maxSpotInterruptionsBeforeOnDemand;
                    LOG.warn("Task {} was reclaimed by Spot (interruption #{}); relaunching{}",
                            currentTaskArn, spotInterruptions,
                            preferOnDemand ? " preferring on-demand capacity" : "");
                    currentTaskArn = launcher.runTask(clusterSettings, taskDefinitionArn, environment,
                            preferOnDemand);
                    logsSince = Instant.EPOCH; // new task, new log stream
                } else {
                    return terminalResult(task, spotInterruptions);
                }
            }

            if (Instant.now().isAfter(deadline)) {
                launcher.stopTask(clusterSettings, currentTaskArn,
                        "jobrunr:build exceeded its overall timeout");
                return new SupervisionResult(false, true,
                        "Exceeded overall timeout of " + effectiveOptions.overallTimeout, spotInterruptions);
            }

            Thread.sleep(effectiveOptions.pollInterval.toMillis());
        }
    }

    private SupervisionResult terminalResult(Task task, int spotInterruptions) {
        if (task.containers().isEmpty()) {
            return new SupervisionResult(false, false,
                    "Task stopped with no container status reported: " + task.stoppedReason(),
                    spotInterruptions);
        }
        Container container = task.containers().get(0);
        Integer exitCode = container.exitCode();
        if (exitCode != null && exitCode == 0) {
            LOG.info("Task {} succeeded", task.taskArn());
            return new SupervisionResult(true, false, null, spotInterruptions);
        }
        String reason = String.format(Locale.ROOT,
                "Task %s stopped: %s (container exit code %s, container reason: %s)",
                task.taskArn(), task.stoppedReason(), exitCode, container.reason());
        LOG.error(reason);
        return new SupervisionResult(false, false, reason, spotInterruptions);
    }

    private boolean isSpotInterruption(Task task) {
        return SPOT_INTERRUPTION_STOPPED_REASON.equals(task.stoppedReason());
    }

    private Task describeTask(EcsClusterSettings clusterSettings, String taskArn) {
        var response = ecsClient.describeTasks(
                b -> b.cluster(clusterSettings.clusterArn()).tasks(taskArn));
        return response.tasks().isEmpty() ? null : response.tasks().get(0);
    }

    private Instant tailLogsBestEffort(EcsClusterSettings clusterSettings, String logStreamNamePrefix,
                                       Instant since, BuildLog logSink) {
        try {
            return logTailer.pollOnce(clusterSettings.logGroupName(), logStreamNamePrefix, since,
                    logSink);
        } catch (RuntimeException e) {
            // Log tailing is a convenience, not load-bearing: a transient CloudWatch Logs error
            // must not abort the build. The next poll's larger time window naturally catches up.
            LOG.debug("Log tailing failed for this poll, will retry next poll: {}", e.getMessage());
            return since;
        }
    }
}
