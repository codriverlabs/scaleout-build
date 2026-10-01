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
 * <p>Account and region are resolved from the caller's AWS credentials at deploy time, not at synth
 * time. Passing explicit account/region values at synth would bake them into the cdk.out asset
 * destinations (e.g. {@code region: us-east-1}), making a pre-synthesized bundle only deployable
 * to that region. By omitting the explicit env — or passing the context values that are null when
 * absent — CDK keeps account and region as CloudFormation pseudo-parameters
 * ({@code ${AWS::AccountId}}, {@code ${AWS::Region}}) in the asset manifest, so the same cdk.out
 * deploys to any region.
 *
 * <p>Pattern from {@code express-compute-control-plane/infra/InfraApp.java}: pass context values
 * from the CDK CLI {@code --context account=X --context region=Y}, and only set the explicit env
 * when both are provided.
 */
public final class ControlPlaneInfraApp {

    private ControlPlaneInfraApp() {
    }

    public static void main(String[] args) {
        App app = new App();

        String account = (String) app.getNode().tryGetContext("account");
        String region  = (String) app.getNode().tryGetContext("region");

        StackProps.Builder propsBuilder = StackProps.builder()
                .description("scaleout-build control plane and the ECS data plane it drives");

        if (account != null && region != null) {
            propsBuilder.env(Environment.builder()
                    .account(account).region(region).build());
        }
        // When account/region are absent, CDK uses ${AWS::AccountId}/${AWS::Region} in asset
        // destinations, keeping the cdk.out portable across regions and accounts.

        new ControlPlaneInfraStack(app, "ScaleoutBuildControlPlane", propsBuilder.build());
        app.synth();
    }
}
