/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.auth;

/** Raised when a request carries no usable verified caller identity. Always maps to HTTP 401. */
public class UnauthenticatedException extends Exception {

    private static final long serialVersionUID = 1L;

    public UnauthenticatedException(String message) {
        super(message);
    }
}
