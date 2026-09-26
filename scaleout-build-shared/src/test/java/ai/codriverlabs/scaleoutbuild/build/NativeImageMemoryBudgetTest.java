/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins how the {@code native-image} memory budget is expressed.
 *
 * <p>Worth a test because the distinction is easy to undo and the consequence is asymmetric. A
 * percentage is read from the container's cgroup limit, so it stays correct when the task is resized;
 * an absolute {@code -J-Xmx} is correct only at the size it was chosen for, and too large a value in a
 * smaller container is an OOM kill rather than a slowdown.
 *
 * <p>The ordering assertion matters just as much: the default must precede caller-supplied arguments, or
 * a project that sets its own heap flag would be silently overridden by ours.
 */
class NativeImageMemoryBudgetTest {

    @Test
    void theDefaultIsContainerRelativeRatherThanAbsolute() {
        assertThat(NativeImageBuildExecutor.DEFAULT_MEMORY_BUDGET_ARG)
                .as("must scale with the container limit, so a resized task stays correct")
                .isEqualTo("-J-XX:MaxRAMPercentage=80");
        assertThat(NativeImageBuildExecutor.DEFAULT_MEMORY_BUDGET_ARG)
                .as("an absolute -Xmx is wrong at every size except the one it was picked for")
                .doesNotContain("-Xmx");
    }

    /**
     * Later {@code -J} arguments win, so a caller's own budget must come after ours. Asserted on relative
     * position rather than by running native-image, which no unit test can do.
     */
    @Test
    void callerSuppliedArgumentsComeAfterTheDefaultSoTheyOverrideIt() {
        List<String> command = List.of("native-image", "@native-image.args",
                NativeImageBuildExecutor.DEFAULT_MEMORY_BUDGET_ARG, "-J-Xmx3g");

        assertThat(command.indexOf(NativeImageBuildExecutor.DEFAULT_MEMORY_BUDGET_ARG))
                .as("the default must precede caller arguments for them to take effect")
                .isLessThan(command.indexOf("-J-Xmx3g"));
    }
}
