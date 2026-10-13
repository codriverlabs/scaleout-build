/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

/**
 * One matrix cell's launch/poll/stop operations, backend-agnostic.
 *
 * <p>Two implementations: {@link FargateCellLauncher} (both architectures, the existing path,
 * extracted rather than rewritten) and a Lambda MicroVM launcher (ARM64 only — see
 * {@code docs/design/control-plane/microvm-build-backend-phase1.md}).
 *
 * <p>Deliberately a different interface from the Maven plugin's own {@code BuildBackend}
 * ({@code scaleout-build-maven-plugin}'s client-side local/remote cell-splitting abstraction) —
 * same word "backend" in both names, unrelated problems in unrelated processes. Naming this
 * {@code CellLauncher} rather than reusing or shadowing that name avoids a false association.
 */
public interface CellLauncher {

    /**
     * Launches one cell.
     *
     * @throws CellLaunchException if the launch itself failed (not if the subsequent build fails —
     *         that is reported by {@link #poll} once the cell is running)
     */
    CellHandle launch(CellLaunchRequest request) throws CellLaunchException;

    /** Polls a launched cell's current state. */
    CellPollResult poll(CellHandle handle);

    /**
     * Stops a running cell. Best-effort: a cell that already stopped (naturally, or via a previous
     * stop call) is not an error — the caller only needs the stop to have been attempted, the same
     * tolerance {@link EcsTaskLauncher#stopTask} already has.
     */
    void stop(CellHandle handle, String reason);
}
