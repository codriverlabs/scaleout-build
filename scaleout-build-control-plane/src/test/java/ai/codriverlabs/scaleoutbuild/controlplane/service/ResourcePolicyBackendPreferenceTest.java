/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.controlplane.config.ControlPlaneConfig;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code BuildSpec.backendPreference}'s validation: the one place a client's explicit
 * {@code fargate}/{@code microvm} choice is either accepted or rejected outright, before
 * {@code BuildService.cellLauncher} ever sees it.
 *
 * <p>Rejected here rather than silently substituted at launch time — see this method's own
 * javadoc in {@code ResourcePolicy} for why an explicit request for an unsatisfiable backend is a
 * real "no" the caller needs to see, not a quiet fallback to whatever {@code auto} would have
 * done anyway.
 */
class ResourcePolicyBackendPreferenceTest {

    private ControlPlaneConfig.MicroVm microVm;
    private ResourcePolicy policy;

    @BeforeEach
    void setUp() {
        ControlPlaneConfig config = mock(ControlPlaneConfig.class);
        microVm = mock(ControlPlaneConfig.MicroVm.class);
        when(config.microVm()).thenReturn(microVm);
        policy = new ResourcePolicy(config);
    }

    private void microVmFullyAvailable() {
        when(microVm.enabled()).thenReturn(true);
        when(microVm.imageArn()).thenReturn(Optional.of("arn:aws:lambda:us-east-1:123:microvm-image:x"));
        when(microVm.imageVersion()).thenReturn(Optional.of("1.0"));
        when(microVm.executionRoleArn()).thenReturn(Optional.of("arn:aws:iam::123:role/exec"));
    }

    @Test
    void autoIsAlwaysValid() throws Exception {
        policy.validateBackendPreference("auto", List.of("arm64"));
        policy.validateBackendPreference("AUTO", List.of("x86_64"));
    }

    @Test
    void fargateIsAlwaysValid_evenWhenMicroVmIsNotConfiguredAtAll() throws Exception {
        // microVm mock has no stubbed enabled()/imageArn()/etc -- Mockito defaults
        // (false/empty) are exactly "not configured", and fargate must not care either way.
        policy.validateBackendPreference("fargate", List.of("arm64"));
        policy.validateBackendPreference("Fargate", List.of("x86_64"));
    }

    @Test
    void unrecognizedValueIsRejected() {
        assertThatThrownBy(() -> policy.validateBackendPreference("ec2", List.of("arm64")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("ec2")
                .hasMessageContaining("auto");
    }

    @Test
    void microVmIsRejectedForX86_64_regardlessOfDeploymentConfig() {
        microVmFullyAvailable();
        assertThatThrownBy(() -> policy.validateBackendPreference("microvm", List.of("x86_64")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("x86_64");
    }

    @Test
    void microVmIsRejectedWhenTheDeploymentHasNotEnabledIt() {
        // microVm.enabled() defaults to false (unstubbed) -- not available.
        assertThatThrownBy(() -> policy.validateBackendPreference("microvm", List.of("arm64")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not have the MicroVM backend enabled");
    }

    @Test
    void microVmIsRejectedWhenEnabledButMissingRequiredSettings() {
        when(microVm.enabled()).thenReturn(true);
        when(microVm.imageArn()).thenReturn(Optional.empty()); // enabled, but no image configured yet
        assertThatThrownBy(() -> policy.validateBackendPreference("microvm", List.of("arm64")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("not have the MicroVM backend enabled");
    }

    @Test
    void microVmIsAcceptedForArm64_whenFullyAvailable() throws Exception {
        microVmFullyAvailable();
        policy.validateBackendPreference("microvm", List.of("arm64"));
        policy.validateBackendPreference("MicroVM", List.of("arm64"));
    }
}
