/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.cli;

/**
 * Thrown by CLI command handlers when the control plane returns an error or a request fails.
 * Picocli catches it via {@link picocli.CommandLine.IExecutionExceptionHandler} and formats it.
 */
public final class CliException extends Exception {

    private final int statusCode;

    public CliException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /** The HTTP status code, or 0 for network errors. */
    public int statusCode() {
        return statusCode;
    }
}
