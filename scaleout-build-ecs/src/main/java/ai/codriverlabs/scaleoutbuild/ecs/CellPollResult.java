/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

/**
 * The result of polling one cell, backend-agnostic.
 *
 * <p>Carries the same three things {@code BuildService.refreshFromEcs()} already extracts from
 * {@code DescribeTasks} for the Fargate path — mapped state, exit code (if terminal and known),
 * and failure reason (if failed) — generalized so a MicroVM launcher can report the equivalent
 * from a different source (see {@code docs/design/control-plane/microvm-build-backend-phase1.md}'s
 * "Poll" section for what that source is and why).
 *
 * <p>{@link #state()} is this module's own {@link CellLaunchState}, not the control plane's
 * {@code CellState} wire-contract enum — {@code scaleout-build-ecs} deliberately does not depend
 * on {@code scaleout-build-control-plane-api} (see this package's {@code CellLauncher} javadoc and
 * the module's own pom description: it exists so neither the plugin nor the control plane forces
 * an unwanted dependency on the other). {@code BuildService}, which already depends on both
 * modules, maps between the two at the boundary — the same place it already maps ECS's own
 * {@code lastStatus} strings to {@code CellState} today.
 *
 * @param state         the mapped cell state, in this module's own vocabulary
 * @param exitCode      the process exit code, if known and the cell reached a terminal state;
 *                      {@code null} otherwise
 * @param failureReason human-readable reason, set only when {@code state == FAILED}
 */
public record CellPollResult(CellLaunchState state, Integer exitCode, String failureReason) {

    public static CellPollResult provisioning() {
        return new CellPollResult(CellLaunchState.PROVISIONING, null, null);
    }

    public static CellPollResult running() {
        return new CellPollResult(CellLaunchState.RUNNING, null, null);
    }

    public static CellPollResult succeeded(int exitCode) {
        return new CellPollResult(CellLaunchState.SUCCEEDED, exitCode, null);
    }

    public static CellPollResult failed(Integer exitCode, String reason) {
        return new CellPollResult(CellLaunchState.FAILED, exitCode, reason);
    }
}
