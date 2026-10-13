/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

/**
 * This module's own cell-state vocabulary, deliberately separate from the control plane's
 * {@code CellState} wire-contract enum — see {@link CellPollResult}'s javadoc for why.
 *
 * <p>Same four values as {@code CellState} (minus {@code PENDING}, which is a pre-launch state
 * this module never reports — a cell doesn't exist as far as a {@link CellLauncher} is concerned
 * until {@link CellLauncher#launch} has been called), because both describe the same underlying
 * lifecycle. The duplication is the deliberate cost of the dependency boundary, not an oversight.
 */
public enum CellLaunchState {
    PROVISIONING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
