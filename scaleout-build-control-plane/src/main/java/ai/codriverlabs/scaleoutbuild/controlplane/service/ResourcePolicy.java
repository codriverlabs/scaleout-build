/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.service;

import ai.codriverlabs.scaleoutbuild.controlplane.api.RequestedResources;
import ai.codriverlabs.scaleoutbuild.controlplane.config.ControlPlaneConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;

/**
 * Applies the server's resource envelope to what a client asked for.
 *
 * <p>Clamps rather than rejects: a client asking for more than policy allows should still get a
 * build, with the applied values echoed back so the difference is visible rather than silent. The
 * envelope exists because the client no longer pays for its own IAM — without a ceiling, any caller
 * could request arbitrarily large Fargate tasks.
 */
@ApplicationScoped
public class ResourcePolicy {

    /** Fargate includes this much ephemeral storage; an explicit size must exceed it. */
    private static final int FARGATE_INCLUDED_EPHEMERAL_GIB = 20;

    private final ControlPlaneConfig config;

    @Inject
    public ResourcePolicy(ControlPlaneConfig config) {
        this.config = config;
    }

    /** @return the sizing actually applied, never null */
    public RequestedResources apply(RequestedResources requested) {
        var limits = config.limits();
        String cpu = clampNumeric(value(requested == null ? null : requested.cpu(),
                limits.defaultCpu()), limits.maxCpu(), limits.defaultCpu());
        String memory = clampNumeric(value(requested == null ? null : requested.memory(),
                limits.defaultMemory()), limits.maxMemory(), limits.defaultMemory());
        int storage = requested == null || requested.ephemeralStorageGiB() <= 0
                ? limits.defaultEphemeralStorageGiB()
                : clampEphemeralStorage(requested.ephemeralStorageGiB(),
                        limits.maxEphemeralStorageGiB());
        return new RequestedResources(cpu, memory, storage);
    }

    /**
     * ECS accepts either no ephemeral storage size, or one of at least 21 GiB — a request between 1 and 20
     * fails the launch with "EphemeralStorage size should be at least 21".
     *
     * <p>Anything in that range is therefore mapped to 0, which omits the field and yields Fargate's
     * included 20 GiB at no charge. That matches what such a request means: the caller wants a small
     * footprint, and 20 GiB free is the smallest available. Passing it through instead produces an ECS
     * error naming a constant nobody configured.
     */
    private int clampEphemeralStorage(int requested, int max) {
        if (requested <= 0) {
            return 0;
        }
        if (requested <= FARGATE_INCLUDED_EPHEMERAL_GIB) {
            return 0;
        }
        return Math.min(requested, max);
    }

    /** @return the effective per-cell wall-clock ceiling in minutes */
    public int clampOverallTimeoutMinutes(int requested) {
        int max = config.limits().maxOverallTimeoutMinutes();
        if (requested <= 0) {
            return max;
        }
        return Math.min(requested, max);
    }

    public void validateBuildKind(String buildKind) throws InvalidRequestException {
        if (!config.limits().allowedBuildKinds().contains(buildKind.toLowerCase(Locale.ROOT))) {
            throw new InvalidRequestException("buildKind '" + buildKind + "' is not permitted; allowed: "
                    + config.limits().allowedBuildKinds());
        }
    }

    public void validateArchitecture(String architecture) throws InvalidRequestException {
        if (!config.limits().allowedArchitectures().contains(architecture.toLowerCase(Locale.ROOT))) {
            throw new InvalidRequestException("architecture '" + architecture
                    + "' is not permitted; allowed: " + config.limits().allowedArchitectures());
        }
    }

    private static String value(String requested, String fallback) {
        return requested == null || requested.isBlank() ? fallback : requested;
    }

    /**
     * Fargate CPU and memory are numeric strings. A non-numeric value falls back to the default
     * rather than failing, because rejecting a build over a malformed sizing hint would be a worse
     * outcome than building it at the default size.
     */
    private static String clampNumeric(String requested, String max, String fallback) {
        try {
            long requestedValue = Long.parseLong(requested.trim());
            long maxValue = Long.parseLong(max.trim());
            return Long.toString(Math.min(requestedValue, maxValue));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
