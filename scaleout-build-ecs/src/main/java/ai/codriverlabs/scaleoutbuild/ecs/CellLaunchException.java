/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

/**
 * Thrown by {@link CellLauncher#launch} when the launch call itself failed — not when a build
 * subsequently fails, which is reported via {@link CellLauncher#poll} once the cell is running.
 *
 * <p>Checked, matching this codebase's existing convention of using checked exceptions for failures
 * every call site must consciously handle (see {@code ControlPlaneClientException}'s javadoc for
 * the fuller reasoning, which applies identically here): {@code BuildService.start()}'s per-cell
 * loop already catches a launch failure and marks that one cell {@code FAILED} without failing the
 * whole build, and that catch must stay deliberate as new launchers are added, not silently
 * droppable.
 */
public final class CellLaunchException extends Exception {

    public CellLaunchException(String message) {
        super(message);
    }

    public CellLaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
