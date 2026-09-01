/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sink for a build's console output.
 *
 * <p>Remote builds write to the container's stdout so the ECS {@code awslogs} driver ships lines to
 * CloudWatch, where the plugin tails them. Deliberately not JobRunr's {@code jobContext().logger()},
 * which would persist every line of a multi-minute native-image build into the job store.
 */
@FunctionalInterface
public interface BuildLog {

    void line(String line);

    /** Writes each line to the given SLF4J logger at INFO. */
    static BuildLog slf4j(Logger logger) {
        return logger::info;
    }

    /** Writes each line to the logger named after {@link NativeImageBuildExecutor}. */
    static BuildLog defaultLog() {
        return slf4j(LoggerFactory.getLogger(NativeImageBuildExecutor.class));
    }

    /** Discards output; intended for tests that assert on results rather than logs. */
    static BuildLog discarding() {
        return line -> {
        };
    }
}
