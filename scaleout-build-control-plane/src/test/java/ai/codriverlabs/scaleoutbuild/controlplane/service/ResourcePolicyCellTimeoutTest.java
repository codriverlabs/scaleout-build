/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.controlplane.config.ControlPlaneConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-compile budget the server hands the agent.
 *
 * <p>This is the one requested dimension that was neither defaulted nor clamped. {@code BuildSpec} documented
 * {@code 0} as "server default" while no default existed, so a cell ran with no deadline at all; and unlike
 * cpu, memory, ephemeral storage and the overall timeout, whatever a client did send was passed through
 * unbounded. Both failures are silent — nothing errors, the cost simply lands on someone's bill — which is
 * why they are fixed here in tests rather than left to a comment.
 */
class ResourcePolicyCellTimeoutTest {

    private static final int DEFAULT = 30;
    private static final int MAX = 120;

    private ResourcePolicy policy;

    @BeforeEach
    void setUp() {
        ControlPlaneConfig config = mock(ControlPlaneConfig.class);
        ControlPlaneConfig.Ecs ecs = mock(ControlPlaneConfig.Ecs.class);
        ControlPlaneConfig.Limits limits = mock(ControlPlaneConfig.Limits.class);
        when(config.ecs()).thenReturn(ecs);
        when(config.limits()).thenReturn(limits);
        when(ecs.defaultCellTimeoutMinutes()).thenReturn(DEFAULT);
        when(limits.maxCellTimeoutMinutes()).thenReturn(MAX);
        policy = new ResourcePolicy(config);
    }

    /** The contract BuildSpec always documented and nothing implemented. */
    @Test
    void zeroResolvesToTheServerDefault() {
        assertThat(policy.resolveCellTimeoutMinutes(0)).isEqualTo(DEFAULT);
    }

    /** A negative would previously have reached AgentEnvironment and emitted nothing, i.e. no deadline. */
    @Test
    void negativeResolvesToTheServerDefaultRatherThanNoDeadline() {
        assertThat(policy.resolveCellTimeoutMinutes(-1)).isEqualTo(DEFAULT);
    }

    @Test
    void aReasonableRequestIsHonoured() {
        assertThat(policy.resolveCellTimeoutMinutes(15)).isEqualTo(15);
        assertThat(policy.resolveCellTimeoutMinutes(MAX)).isEqualTo(MAX);
    }

    /**
     * The exposure this closes: unclamped, a client asking for 9999 got a ~7-day compile budget, and the
     * container backstop derived from it inherited that. The only ceiling left was the reaper firing at the
     * clamped deadline, which made a cost control depend on an optional component.
     */
    @Test
    void anAbsurdRequestIsClampedToThePolicyCeiling() {
        assertThat(policy.resolveCellTimeoutMinutes(9999)).isEqualTo(MAX);
    }
}
