/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildMojoLocalRemoteSplitTest {

    private static final Architecture HOST = Architecture.host().orElse(Architecture.X86_64);
    private static final Architecture OTHER =
            HOST == Architecture.X86_64 ? Architecture.ARM64 : Architecture.X86_64;

    @Test
    void byDefaultAHostMatchingCellBuildsLocallyAndTheOtherGoesRemote() {
        var hostCell = new MatrixCell(BuildKind.NATIVE, HOST);
        var otherCell = new MatrixCell(BuildKind.NATIVE, OTHER);

        var split = BuildMojo.splitLocalAndRemote(List.of(hostCell, otherCell), false);

        assertThat(split.localCells()).containsExactly(hostCell);
        assertThat(split.remoteCells()).containsExactly(otherCell);
    }

    @Test
    void forceRemoteSendsEveryCellRemoteEvenTheHostMatchingOne() {
        var hostCell = new MatrixCell(BuildKind.NATIVE, HOST);
        var otherCell = new MatrixCell(BuildKind.NATIVE, OTHER);

        var split = BuildMojo.splitLocalAndRemote(List.of(hostCell, otherCell), true);

        assertThat(split.localCells()).isEmpty();
        assertThat(split.remoteCells()).containsExactly(hostCell, otherCell);
    }

    @Test
    void anEmptyCellListProducesTwoEmptySplitsRegardlessOfForceRemote() {
        assertThat(BuildMojo.splitLocalAndRemote(List.of(), false).localCells()).isEmpty();
        assertThat(BuildMojo.splitLocalAndRemote(List.of(), false).remoteCells()).isEmpty();
        assertThat(BuildMojo.splitLocalAndRemote(List.of(), true).localCells()).isEmpty();
        assertThat(BuildMojo.splitLocalAndRemote(List.of(), true).remoteCells()).isEmpty();
    }
}
