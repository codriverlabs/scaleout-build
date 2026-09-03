/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.ecs;

/** Raised when ECS reports a {@code RunTask} failure, e.g. no capacity available. */
public class FargateLaunchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FargateLaunchException(String message) {
        super(message);
    }

    public FargateLaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
