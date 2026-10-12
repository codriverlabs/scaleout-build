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
 * <p><b>{@code backendPreference} is the one deliberate, documented exception to that rule.</b>
 * It still names no infrastructure directly (no cluster, role, bucket, or image ARN crosses the
 * wire) — it only expresses which compute backend an {@code arm64} cell should prefer, and the
 * server has the final say regardless of what is requested (see
 * {@code ResourcePolicy.validateBackendPreference} and {@code BuildService.cellLauncher}). This
 * reverses an earlier, narrower design: Phase 1's own doc stated backend selection was "deployment
 * policy, not a per-build choice, with deliberately no AUTO fallback". That held as long as the
 * only lever was the deployment's own {@code microvmEnabled} switch, which is an availability
 * toggle ("can this deployment use MicroVM at all"), not a placement choice ("should this
 * particular build use it"). Collapsing those two questions into one switch left no way for a
 * developer who knows their own workload's cost profile to opt out of MicroVM on a deployment
 * that defaults to it, or opt in on one that does not default to it. Real pricing data settled
 * which way to resolve that gap: MicroVM's per-unit vCPU-second and memory-GB-second rates are
 * roughly 3–3.7x Fargate's in {@code us-east-1} (confirmed against the AWS Price List API, not
 * estimated), so a workload that saturates CPU and memory for its entire duration — which
 * {@code native-image} measurably does, per
 * {@code docs/design/control-plane/fargate-task-resource-usage.md} — can cost meaningfully more
 * on MicroVM than on Fargate for the identical work, despite MicroVM's "pay only for what you
 * burst above baseline" billing being a genuine win for bursty, idle-heavy workloads. A developer
 * who knows which shape their own build has needs a real lever, not just the deployment's own
 * default.
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
 * @param backendPreference     {@code auto} (default, matches the deployment's own policy — the
 *                              only behavior that existed before this field), {@code fargate}
 *                              (force every cell in this build onto Fargate, including
 *                              {@code arm64} ones, regardless of the deployment's own default), or
 *                              {@code microvm} (force {@code arm64} cells onto Lambda MicroVM;
 *                              rejected at build-creation time, not silently substituted, if the
 *                              deployment does not have MicroVM enabled or if any requested
 *                              architecture is {@code x86_64} — MicroVM genuinely cannot run it).
 *                              Case-insensitive; blank or {@code null} means {@code auto}.
 */
public record BuildSpec(List<String> buildKinds, List<String> architectures, String mainClass,
                        String imageName, String nativeImageCommand,
                        List<String> extraNativeImageArgs, List<String> extraBuildArgs,
                        int timeoutMinutes, int overallTimeoutMinutes, String backendPreference) {

    /** Default when a client sends {@code null} or blank: today's deployment-decides behavior. */
    public static final String BACKEND_AUTO = "auto";

    public BuildSpec {
        buildKinds = buildKinds == null ? List.of() : List.copyOf(buildKinds);
        architectures = architectures == null ? List.of() : List.copyOf(architectures);
        extraNativeImageArgs =
                extraNativeImageArgs == null ? List.of() : List.copyOf(extraNativeImageArgs);
        extraBuildArgs = extraBuildArgs == null ? List.of() : List.copyOf(extraBuildArgs);
        backendPreference = backendPreference == null || backendPreference.isBlank()
                ? BACKEND_AUTO : backendPreference;
    }
}
