/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build;

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
     * @throws BuildFailedException when the build tool reports failure
     * @throws InterruptedException when execution is interrupted, typically a Fargate Spot
     *         interruption; the caller decides whether/how to retry
     */
    BuildResult execute(BuildCellRequest request, BuildLog log)
            throws BuildFailedException, InterruptedException;
}
