/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

/**
 * Runs one staged native-image build.
 *
 * <p>Implementations resolve paths through a {@link BuildEnvironment}, so the same executor works
 * unchanged whether the staging area is a local directory or an ECS S3 Files mount.
 */
public interface BuildExecutor {

    /**
     * Executes the build described by {@code request}.
     *
     * @throws BuildFailedException when the build tool reports failure, so JobRunr can apply its
     *         retry policy and the plugin can surface the reason
     * @throws InterruptedException when the worker is shutting down, typically a Fargate Spot
     *         interruption; the job is then re-queued rather than failed
     */
    BuildResult execute(BuildJobRequest request, BuildLog log)
            throws BuildFailedException, InterruptedException;
}
