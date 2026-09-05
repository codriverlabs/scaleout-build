/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * Entry point for the {@code aws-ecs:build} test infrastructure CDK app.
 *
 * <p>Deploys {@link BuildTestInfraStack} — a minimal, disposable AWS environment for exercising
 * the plugin's {@code FARGATE} launch type with S3 Files staging against real AWS. Not part of the
 * plugin's release artifact; see {@code jobrunr-test-infra/README.md} for how to deploy, what it
 * costs, and how to feed its outputs into an {@code aws-ecs:build} invocation.
 *
 * <p>Account and region come from the standard CDK CLI environment variables
 * ({@code CDK_DEFAULT_ACCOUNT}/{@code CDK_DEFAULT_REGION}, populated by the CLI from the active
 * AWS credentials/profile) rather than being hardcoded — this app is meant to be deployed into
 * whichever account/region the caller's credentials point at, the same as any other CDK app.
 */
public final class InfraApp {

    private InfraApp() {
    }

    public static void main(String[] args) {
        App app = new App();

        String account = System.getenv("CDK_DEFAULT_ACCOUNT");
        String region = System.getenv("CDK_DEFAULT_REGION");
        Environment.Builder envBuilder = Environment.builder();
        if (account != null) {
            envBuilder.account(account);
        }
        if (region != null) {
            envBuilder.region(region);
        }

        new BuildTestInfraStack(app, "JobrunrBuildTestInfra", StackProps.builder()
                .env(envBuilder.build())
                .description("Disposable test infra for aws-ecs:build (FARGATE + S3 Files). "
                        + "See jobrunr-test-infra/README.md.")
                .build());

        app.synth();
    }
}
