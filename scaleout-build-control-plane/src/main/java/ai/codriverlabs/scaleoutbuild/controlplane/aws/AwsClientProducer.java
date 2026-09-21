/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.aws;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import ai.codriverlabs.scaleoutbuild.ecs.CloudWatchLogTailer;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Produces the AWS clients that have no Quarkus extension in the platform BOM.
 *
 * <p>Only DynamoDB has one ({@code quarkus-amazon-dynamodb}), so S3, the S3 presigner, and ECS are
 * built here — the same split {@code ecp-tenant-service} uses, where EC2 and Secrets Manager are raw
 * SDK while IAM and STS come from extensions.
 *
 * <p>All use {@link UrlConnectionHttpClient} rather than the default Apache or Netty client. In a
 * Lambda this is the right trade in both directions: no connection pool to keep warm across a frozen
 * execution environment, and no Netty to register for reflection in the native image. Clients are
 * {@code @ApplicationScoped} so one instance is reused across invocations of a warm environment,
 * which is where credential and region resolution would otherwise repeat per request.
 */
@ApplicationScoped
public class AwsClientProducer {

    /**
     * Resolved once from the standard chain. In Lambda this is always set via {@code AWS_REGION}, so
     * this never reaches out to IMDS.
     */
    private static Region region() {
        return new DefaultAwsRegionProviderChain().getRegion();
    }

    @Produces
    @ApplicationScoped
    public S3Client s3Client() {
        return S3Client.builder()
                .region(region())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Produces
    @ApplicationScoped
    public S3Presigner s3Presigner() {
        // The presigner signs locally and issues no HTTP requests of its own, so it needs no client.
        return S3Presigner.builder().region(region()).build();
    }

    @Produces
    @ApplicationScoped
    public EcsClient ecsClient() {
        return EcsClient.builder()
                .region(region())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Produces
    @ApplicationScoped
    public CloudWatchLogsClient cloudWatchLogsClient() {
        return CloudWatchLogsClient.builder()
                .region(region())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    /** The tailer is stateless; one instance is reused across streaming invocations. */
    @Produces
    @ApplicationScoped
    public CloudWatchLogTailer logTailer(CloudWatchLogsClient logsClient) {
        return new CloudWatchLogTailer(logsClient);
    }

    void closeLogs(@Disposes CloudWatchLogsClient client) {
        client.close();
    }

    void closeS3(@Disposes S3Client client) {
        client.close();
    }

    void closePresigner(@Disposes S3Presigner presigner) {
        presigner.close();
    }

    void closeEcs(@Disposes EcsClient client) {
        client.close();
    }
}
