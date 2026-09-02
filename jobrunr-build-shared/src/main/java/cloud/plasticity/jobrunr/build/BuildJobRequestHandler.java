/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import java.util.Objects;
import java.util.Optional;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JobRunr entry point for a remote native-image build.
 *
 * <p>Deliberately thin: it verifies the job belongs on this host, delegates to a {@link
 * BuildExecutor}, and reports completion. Keeping the build logic out of the handler is what lets the
 * plugin's local mode and the container agent share one code path.
 *
 * <p>Instances are supplied by a {@code JobActivator} rather than constructed reflectively, because
 * the executor and tracker are per-worker collaborators.
 */
public class BuildJobRequestHandler implements JobRequestHandler<BuildJobRequest> {

    private static final Logger LOG = LoggerFactory.getLogger(BuildJobRequestHandler.class);

    private final BuildExecutor executor;
    private final JobCompletionTracker tracker;
    private final BuildLog buildLog;

    public BuildJobRequestHandler(BuildExecutor executor, JobCompletionTracker tracker,
                                  BuildLog buildLog) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.buildLog = buildLog == null ? BuildLog.defaultLog() : buildLog;
    }

    /**
     * Retries disabled: a {@code native-image} failure (bad classpath, missing binary, OOM) is not
     * transient, and JobRunr's default retry-with-backoff would also break the single-job
     * {@link JobCompletionTracker#awaitCompletion} contract by running this method more than once.
     * Fargate Spot interruption is handled separately, by re-queueing on graceful worker shutdown
     * rather than through this mechanism.
     */
    @Override
    @Job(retries = 0)
    public void run(BuildJobRequest request) throws Exception {
        verifyArchitecture(request);
        tracker.jobStarted();
        LOG.info("Starting {} build for {} (job payload: {})",
                request.getArchitecture(), request.getBuildId(), request);
        try {
            BuildResult result = executor.execute(request, buildLog);
            LOG.info("Completed {} build for {} in {} producing {}",
                    request.getArchitecture(), request.getBuildId(), result.duration(),
                    result.artifacts());
        } finally {
            tracker.jobFinished();
        }
    }

    /**
     * Fails fast when a job reaches the wrong hardware.
     *
     * <p>Per-architecture JobRunr schemas should make this unreachable. If it does trigger it means
     * a worker was pointed at the wrong schema, so the message names both sides rather than letting
     * native-image fail later with something obscure.
     */
    private void verifyArchitecture(BuildJobRequest request) {
        Architecture requested = request.getArchitecture();
        if (requested == null) {
            throw new IllegalArgumentException(
                    "Job " + request.getBuildId() + " has no target architecture");
        }
        if (requested.matchesHost()) {
            return;
        }
        String hostArch = Architecture.host().map(Enum::name)
                .orElseGet(() -> "unrecognised (os.arch=" + System.getProperty("os.arch") + ")");
        throw new IllegalStateException(
                "Job " + request.getBuildId() + " targets " + requested + " but this worker is "
                        + hostArch + ". native-image cannot cross-compile; check that the worker is "
                        + "reading the " + requested.schemaSuffix() + " schema.");
    }

    /** The architecture this worker can serve, exposed for diagnostics. */
    public static Optional<Architecture> hostArchitecture() {
        return Architecture.host();
    }
}
