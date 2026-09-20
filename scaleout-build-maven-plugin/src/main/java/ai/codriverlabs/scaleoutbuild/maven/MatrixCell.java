/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;

/**
 * One matrix cell: a build kind, and (for every kind but JVM) a target architecture.
 *
 * <p>Promoted out of {@code BuildMojo} to a top-level type because it is the unit of work every
 * build backend deals in, not a detail of the Mojo that happens to compute the matrix. Keeping it
 * nested would force a backend implementation — including the forthcoming control-plane client, see
 * {@code docs/design/control-plane/migration-from-direct-ecs-access.md} — to depend on the Mojo
 * class purely to name its own input type.
 *
 * @param buildKind    what to build
 * @param architecture the target architecture, or {@code null} for kinds that do not require one
 *                     ({@link BuildKind#requiresArchitecture()})
 */
public record MatrixCell(BuildKind buildKind, Architecture architecture) {

    @Override
    public String toString() {
        return architecture == null ? buildKind.toString() : buildKind + "/" + architecture;
    }
}
