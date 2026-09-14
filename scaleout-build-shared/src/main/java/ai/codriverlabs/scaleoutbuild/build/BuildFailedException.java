/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build;

/** Raised when a build tool reports failure, carrying enough context to explain it upstream. */
public class BuildFailedException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int exitCode;

    public BuildFailedException(String message) {
        this(message, -1);
    }

    public BuildFailedException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public BuildFailedException(String message, Throwable cause) {
        super(message, cause);
        this.exitCode = -1;
    }

    /** Exit status of the build process, or {@code -1} when the failure happened before launch. */
    public int getExitCode() {
        return exitCode;
    }
}
