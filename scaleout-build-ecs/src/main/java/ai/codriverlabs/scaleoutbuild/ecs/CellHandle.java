/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

/**
 * Opaque handle to a launched cell, returned by {@link CellLauncher#launch} and passed back to
 * {@link CellLauncher#poll}/{@link CellLauncher#stop}.
 *
 * <p>Sealed so a {@code switch} over the two cases is exhaustive at compile time — the same reason
 * {@link ai.codriverlabs.scaleoutbuild.build.Architecture} and other closed vocabularies in this
 * codebase are enums rather than open class hierarchies.
 */
public sealed interface CellHandle {

    /** @return a stable string form, for persistence (see {@code BuildRecord.CellRecord}) and logs */
    String describe();

    /** A Fargate task, identified by its ARN. */
    record FargateHandle(String taskArn) implements CellHandle {
        @Override
        public String describe() {
            return taskArn;
        }
    }

    /** A Lambda MicroVM, identified by its microvm id. The endpoint is informational only — see
     * {@code docs/design/control-plane/microvm-build-backend-phase1.md}'s "Why no ingress
     * connector" section for why the control plane never calls it. */
    record MicroVmHandle(String microvmId, String endpoint) implements CellHandle {
        @Override
        public String describe() {
            return microvmId;
        }
    }
}
