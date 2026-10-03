/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.mcp;

import ai.codriverlabs.scaleoutbuild.cli.ControlPlaneHttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CDI producer for {@link ControlPlaneHttpClient}.
 *
 * <p>Reads configuration from environment variables via MicroProfile Config:
 * <ul>
 *   <li>{@code SCALEOUT_ENDPOINT} → required, the Lambda Function URL</li>
 *   <li>{@code AWS_REGION} → optional, defaults to {@code eu-central-1}</li>
 * </ul>
 */
@ApplicationScoped
public class ControlPlaneClientProducer {

    @ConfigProperty(name = "scaleout.endpoint")
    String endpoint;

    @ConfigProperty(name = "aws.region", defaultValue = "eu-central-1")
    String region;

    @Produces
    @ApplicationScoped
    public ControlPlaneHttpClient controlPlaneHttpClient() {
        return new ControlPlaneHttpClient(endpoint, region);
    }
}
