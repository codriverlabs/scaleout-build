/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.planner;

/** Raised when a project's native-image inputs cannot be determined; the message is user-facing. */
public class InputPlanningException extends Exception {

    private static final long serialVersionUID = 1L;

    public InputPlanningException(String message) {
        super(message);
    }

    public InputPlanningException(String message, Throwable cause) {
        super(message, cause);
    }
}
