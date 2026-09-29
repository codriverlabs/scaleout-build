/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import java.util.List;

/**
 * What to build. Everything here is the caller's legitimate concern; nothing here names
 * infrastructure.
 *
 * <p>This is precisely the subset of the Maven plugin's 33 {@code aws-ecs.*} parameters that
 * survives the move behind the control plane — see
 * {@code docs/design/control-plane/migration-from-direct-ecs-access.md} for the full inventory.
 * Cluster, subnets, roles, bucket, log group, and agent image are deliberately absent: they are
 * service configuration, and a client that could set them could choose what code runs with the task
 * role's permissions.
 *
 * @param buildKinds            e.g. {@code native}, {@code native-pgo-instrument}; validated against
 *                              the server's allow-list
 * @param architectures         e.g. {@code x86_64}, {@code arm64}; validated against the allow-list
 * @param mainClass             entry point for the produced image
 * @param imageName             output binary name
 * @param nativeImageCommand    command to invoke, defaulted server-side when null
 * @param extraNativeImageArgs  appended to every invocation
 * @param extraBuildArgs        appended for derived-argfile builds
 * @param timeoutMinutes        per-cell {@code native-image} timeout; {@code 0} means "server default",
 *                              substituted by the service from {@code scaleout.ecs.default-cell-timeout-minutes}
 *                              (30). Between this contract being written and that substitution being added,
 *                              {@code 0} meant "no timeout at all" and a hung compile ran unbounded
 * @param overallTimeoutMinutes wall-clock ceiling per cell, clamped by server policy
 */
public record BuildSpec(List<String> buildKinds, List<String> architectures, String mainClass,
                        String imageName, String nativeImageCommand,
                        List<String> extraNativeImageArgs, List<String> extraBuildArgs,
                        int timeoutMinutes, int overallTimeoutMinutes) {

    public BuildSpec {
        buildKinds = buildKinds == null ? List.of() : List.copyOf(buildKinds);
        architectures = architectures == null ? List.of() : List.copyOf(architectures);
        extraNativeImageArgs =
                extraNativeImageArgs == null ? List.of() : List.copyOf(extraNativeImageArgs);
        extraBuildArgs = extraBuildArgs == null ? List.of() : List.copyOf(extraBuildArgs);
    }
}
