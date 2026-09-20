/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.ecs;

import ai.codriverlabs.scaleoutbuild.build.BuildLog;
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
 * Watches one ECS task through to completion, without any orchestration layer above ECS — the
 * "pure ECS" alternative to {@code docs/DESIGN.md}'s Step Functions design, for when the matrix is
 * small and fixed (e.g. exactly x86_64 + arm64) and the extra declarative machinery isn't worth it.
 * See {@code docs/PURE_ECS_ALTERNATIVE.md} for the tradeoff analysis.
 *
 * <p><b>Spot interruption detection is a real gap relative to Step Functions' typed
 * {@code Retry}/{@code ErrorEquals}, and worth understanding precisely rather than assuming.</b>
 * ECS's {@code stopCode} field is a strict enum — confirmed against the SDK model —
 * of only {@code TaskFailedToStart}, {@code EssentialContainerExited}, and {@code UserInitiated}.
 * There is no dedicated stop code for a Spot reclaim. AWS's documented signal is the free-text
 * {@code stoppedReason} field, whose value for a genuine Fargate Spot interruption is specifically
 * {@code "Your Spot Task was interrupted."} (confirmed against AWS's own troubleshooting guidance
 * and support threads, not guessed) — this class matches that string, but it is not a stable typed
 * value the way Step Functions' error names are, and a wording change on AWS's side would silently
 * break the match. Also worth knowing: a stopped task's details, including {@code stoppedReason},
 * are only available via {@code DescribeTasks} for one hour after the task stops — not a concern
 * for this class's own tight poll loop, but relevant if anyone reuses this detection logic
 * elsewhere against an already-stopped task discovered later.
 *
 * <p><b>This detection is deliberately scoped to {@link EcsLaunchType#FARGATE} only.</b> The exact
 * {@code stoppedReason} wording ECS uses when a {@code MANAGED_INSTANCES} or {@code EC2} Spot
 * instance is reclaimed has not been verified against AWS's docs — reusing the Fargate-Spot string
 * for those launch types would either never match (a silent false negative, since an EC2/Managed
 * Instances Spot reclaim would then be treated as an ordinary terminal failure instead of retried)
 * or, worse, be presented as verified when it is not. Rather than guess, any stop on those launch
 * types is treated as terminal today; automatic Spot-interruption retry for them is a documented
 * follow-up in {@code docs/PURE_ECS_ALTERNATIVE.md}, not silently assumed to already work.
 */
public final class EcsTaskSupervisor {

    private static final Logger LOG = LoggerFactory.getLogger(EcsTaskSupervisor.class);

    /**
     * The exact, documented free-text value ECS uses for a genuine Fargate Spot reclaim. Matched
     * case-sensitively and verbatim rather than with a loose substring check, since a partial match
     * risks false positives against an unrelated {@code stoppedReason} that happens to share words.
     */
    static final String SPOT_INTERRUPTION_STOPPED_REASON = "Your Spot Task was interrupted.";

    private final EcsTaskLauncher launcher;
    private final CloudWatchLogTailer logTailer;
    private final EcsClient ecsClient;

    public EcsTaskSupervisor(EcsTaskLauncher launcher, CloudWatchLogTailer logTailer,
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
        String currentLogStreamName = logStreamNameFor(logStreamNamePrefix, currentTaskArn);
        int spotInterruptions = 0;
        Instant logsSince = Instant.EPOCH;
        Instant deadline = Instant.now().plus(effectiveOptions.overallTimeout);

        while (true) {
            logsSince = tailLogsBestEffort(clusterSettings, currentLogStreamName, logsSince, logSink);

            Task task = describeTask(clusterSettings, currentTaskArn);
            if (task != null && "STOPPED".equals(task.lastStatus())) {
                if (isSpotInterruption(clusterSettings, task)) {
                    spotInterruptions++;
                    boolean preferOnDemand =
                            spotInterruptions > effectiveOptions.maxSpotInterruptionsBeforeOnDemand;
                    LOG.warn("Task {} was reclaimed by Spot (interruption #{}); relaunching{}",
                            currentTaskArn, spotInterruptions,
                            preferOnDemand ? " preferring on-demand capacity" : "");
                    currentTaskArn = launcher.runTask(clusterSettings, taskDefinitionArn, environment,
                            preferOnDemand);
                    // New task, new log stream: both the stream name (it embeds the task id) and
                    // the watermark have to be reset, not just the watermark.
                    currentLogStreamName = logStreamNameFor(logStreamNamePrefix, currentTaskArn);
                    logsSince = Instant.EPOCH;
                } else {
                    return terminalResult(task, spotInterruptions);
                }
            }

            if (Instant.now().isAfter(deadline)) {
                launcher.stopTask(clusterSettings, currentTaskArn,
                        "scaleout:build exceeded its overall timeout");
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

    /**
     * Only {@link EcsLaunchType#FARGATE} is matched here — see the class javadoc for why this is
     * not generalized to {@code MANAGED_INSTANCES}/{@code EC2} without a verified stopped-reason
     * string for those launch types.
     */
    private boolean isSpotInterruption(EcsClusterSettings clusterSettings, Task task) {
        return clusterSettings.launchType() == EcsLaunchType.FARGATE
                && SPOT_INTERRUPTION_STOPPED_REASON.equals(task.stoppedReason());
    }

    private Task describeTask(EcsClusterSettings clusterSettings, String taskArn) {
        var response = ecsClient.describeTasks(
                b -> b.cluster(clusterSettings.clusterArn()).tasks(taskArn));
        return response.tasks().isEmpty() ? null : response.tasks().get(0);
    }

    /**
     * Composes the exact {@code awslogs} log stream name for one task:
     * {@code <awslogs-stream-prefix>/<container-name>/<task-id>}, where the task id is the last
     * segment of the task ARN ({@code arn:aws:ecs:<region>:<account>:task/<cluster>/<task-id>}).
     *
     * <p><b>Why this exists rather than tailing on {@code logStreamNamePrefix} alone.</b> Every
     * matrix cell's task definition shares one {@code awslogs-stream-prefix}
     * ({@link TaskDefinitionRegistrar#LOG_STREAM_PREFIX}), so filtering on the bare prefix matches
     * <em>every</em> concurrent cell's stream in the group — and every earlier build's streams that
     * still fall inside the poll window. That produced two distinct, confirmed failures when
     * x86_64 and arm64 cells ran concurrently against real AWS: each cell's console output
     * contained the other cell's lines under its own label, and — more seriously — because
     * {@code logsSince} advances to the newest event seen across all matched streams, genuinely new
     * lines from the slower task were older than that watermark and got silently dropped by the
     * tailer's own de-duplication. Scoping to one stream fixes both, and also stops each cell
     * re-scanning every other cell's log data on every poll.
     *
     * <p>The task ARN is available as soon as {@code RunTask} returns, which is strictly before the
     * first poll — so nothing here depends on the task having started yet. The stream itself may
     * not exist for the first few polls; {@link CloudWatchLogTailer} already treats that as normal.
     */
    static String logStreamNameFor(String logStreamNamePrefix, String taskArn) {
        int lastSlash = taskArn.lastIndexOf('/');
        String taskId = lastSlash >= 0 ? taskArn.substring(lastSlash + 1) : taskArn;
        return logStreamNamePrefix + "/" + TaskDefinitionRegistrar.CONTAINER_NAME + "/" + taskId;
    }

    private Instant tailLogsBestEffort(EcsClusterSettings clusterSettings, String logStreamName,
                                       Instant since, BuildLog logSink) {
        try {
            return logTailer.pollOnce(clusterSettings.logGroupName(), logStreamName, since,
                    logSink);
        } catch (RuntimeException e) {
            // Log tailing is a convenience, not load-bearing: a transient CloudWatch Logs error
            // must not abort the build. The next poll's larger time window naturally catches up.
            LOG.debug("Log tailing failed for this poll, will retry next poll: {}", e.getMessage());
            return since;
        }
    }
}
