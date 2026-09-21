/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/**
 * Everything the service knows about the infrastructure it drives.
 *
 * <p>This is the 22 {@code aws-ecs.*} parameters that
 * {@code docs/design/control-plane/migration-from-direct-ecs-access.md} moves off the client. They
 * are deployment configuration set by CDK, not request input: a caller who could choose
 * {@code agentImage} would choose what code runs with the task role's permissions.
 */
@ConfigMapping(prefix = "scaleout")
public interface ControlPlaneConfig {

    /** DynamoDB table holding build state. */
    @WithDefault("scaleout-builds")
    String buildsTable();

    /** S3 bucket for staged inputs and produced artifacts. */
    String stagingBucket();

    Ecs ecs();

    Limits limits();

    interface Ecs {
        String clusterArn();

        List<String> subnetIds();

        List<String> securityGroupIds();

        @WithDefault("true")
        boolean assignPublicIp();

        String executionRoleArn();

        String taskRoleArn();

        String logGroupName();

        String agentImage();

        @WithDefault("FARGATE")
        String launchType();

        Optional<String> capacityProviderName();

        /** True for the plain-S3 staging mode, which is the settled design. */
        @WithDefault("true")
        boolean agentUsesDirectS3Io();
    }

    /**
     * Server-side policy. These exist because the client no longer pays for its own IAM: without a
     * ceiling, a caller could request arbitrarily large Fargate tasks, or enough concurrent builds to
     * exhaust the account's task limits.
     */
    interface Limits {
        @WithDefault("4096")
        String defaultCpu();

        @WithDefault("16384")
        String defaultMemory();

        @WithDefault("40")
        int defaultEphemeralStorageGiB();

        @WithDefault("16384")
        String maxCpu();

        @WithDefault("65536")
        String maxMemory();

        @WithDefault("200")
        int maxEphemeralStorageGiB();

        /** Wall-clock ceiling per cell, regardless of what the client requests. */
        @WithDefault("120")
        int maxOverallTimeoutMinutes();

        /** Concurrent non-terminal builds one owner may hold. */
        @WithDefault("4")
        int maxConcurrentBuildsPerOwner();

        /** How often clients should heartbeat, and the basis for reap decisions. */
        @WithDefault("30")
        int heartbeatIntervalSeconds();

        /** Missed heartbeats tolerated before a build is considered orphaned. */
        @WithDefault("4")
        int missedHeartbeatsBeforeReap();

        /** Presigned URL lifetime. */
        @WithDefault("900")
        int presignedUrlTtlSeconds();

        /** Allowed build kinds; anything else is rejected at create time. */
        @WithDefault("native,native-pgo-instrument,native-pgo-optimize")
        List<String> allowedBuildKinds();

        @WithDefault("x86_64,arm64")
        List<String> allowedArchitectures();
    }
}
