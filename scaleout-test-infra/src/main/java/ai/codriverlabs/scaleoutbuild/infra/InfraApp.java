/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * Entry point for the {@code aws-ecs:build} test infrastructure CDK app.
 *
 * <p>Deploys {@link BuildTestInfraStack} — a minimal, disposable AWS environment for exercising
 * the plugin against real AWS. Not part of the plugin's release artifact; see
 * {@code scaleout-test-infra/README.md} for how to deploy, what it costs, and how to feed its
 * outputs into an {@code aws-ecs:build} invocation.
 *
 * <p>Account and region come from the standard CDK CLI environment variables
 * ({@code CDK_DEFAULT_ACCOUNT}/{@code CDK_DEFAULT_REGION}, populated by the CLI from the active
 * AWS credentials/profile) rather than being hardcoded — this app is meant to be deployed into
 * whichever account/region the caller's credentials point at, the same as any other CDK app.
 *
 * <p>{@code includeS3Files} (CDK context key, default {@code false}) selects which staging
 * mechanism the deployed stack provisions — see {@link BuildTestInfraStack}'s Javadoc for the
 * full explanation. Defaults to {@code false} here because plain S3 with content-hash dedup is
 * the settled staging design for new deployments (see {@code docs/STAGING_ALTERNATIVES.md});
 * pass {@code -c includeS3Files=true} to provision the mount-based mechanism instead.
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

        Object includeS3FilesContext = app.getNode().tryGetContext("includeS3Files");
        boolean includeS3Files = includeS3FilesContext != null
                && Boolean.parseBoolean(includeS3FilesContext.toString());

        new BuildTestInfraStack(app, "ScaleoutBuildTestInfra", StackProps.builder()
                .env(envBuilder.build())
                .description("Disposable test infra for aws-ecs:build (" 
                        + (includeS3Files ? "FARGATE + S3 Files" : "plain S3, direct-S3-calls agent I/O")
                        + "). See scaleout-test-infra/README.md.")
                .build(), includeS3Files);

        app.synth();
    }
}
