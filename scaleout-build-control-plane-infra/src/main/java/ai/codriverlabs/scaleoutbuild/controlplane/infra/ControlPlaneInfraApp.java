/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * CDK entry point.
 *
 * <p>Account and region come from {@code CDK_DEFAULT_ACCOUNT}/{@code CDK_DEFAULT_REGION}, which the
 * CDK CLI computes from the active credentials and injects into this process. Per
 * {@code scaleout-test-infra/README.md}, export {@code AWS_REGION} to target a region — setting
 * {@code CDK_DEFAULT_REGION} directly has no effect, because the CLI overwrites it.
 */
public final class ControlPlaneInfraApp {

    private ControlPlaneInfraApp() {
    }

    public static void main(String[] args) {
        App app = new App();
        new ControlPlaneInfraStack(app, "ScaleoutBuildControlPlane", StackProps.builder()
                .env(Environment.builder()
                        .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                        .region(System.getenv("CDK_DEFAULT_REGION"))
                        .build())
                .description("scaleout-build control plane and the ECS data plane it drives")
                .build());
        app.synth();
    }
}
