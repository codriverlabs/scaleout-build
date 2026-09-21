/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.service;

/** A client-correctable problem with a request. Maps to HTTP 400. */
public class InvalidRequestException extends Exception {

    private static final long serialVersionUID = 1L;

    public InvalidRequestException(String message) {
        super(message);
    }
}
