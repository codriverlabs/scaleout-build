/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.staging;

import ai.codriverlabs.scaleoutbuild.planner.NativeImageInputPlan;
import java.io.IOException;

/**
 * Places a plan's files into a staging area.
 *
 * <p>Two implementations are expected: a local directory (used by local mode and tests) and an S3
 * bucket fronting an ECS S3 Files volume. The agent reads a plain directory path either way, so the
 * transport stays invisible to the build itself.
 */
public interface StagingSink {

    /**
     * Stages a plan under {@code stagingRelativePath}, creating the output directory.
     *
     * @return number of bytes actually transferred, which for a deduplicating sink can be far less
     *         than {@link NativeImageInputPlan#totalBytes()}
     */
    long stage(NativeImageInputPlan plan, String stagingRelativePath) throws IOException;
}
