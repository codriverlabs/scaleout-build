/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

/** Raised when ECS reports a {@code RunTask} failure, e.g. no capacity available. */
public class EcsLaunchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EcsLaunchException(String message) {
        super(message);
    }

    public EcsLaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
