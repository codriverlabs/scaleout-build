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

    Auth auth();

    /*
     * Part of the mapping rather than loose @ConfigProperty fields, because @ConfigMapping(prefix =
     * "scaleout") makes SmallRye the owner of the whole scaleout.* namespace: any scaleout.* property
     * that is not a member of this interface fails startup with SRCFG00050 "does not map to any root".
     *
     * That is exactly how this was found -- in production, on every cold start, after 122 unit tests
     * passed. None of them booted the application, so nothing evaluated the config mapping.
     */
    interface Auth {
        /**
         * Dev escape hatch for running the service outside Lambda, where no request context header
         * exists. Defaults to false so the filter fails closed: a deployment that forgets to set this
         * rejects unauthenticated callers rather than trusting them.
         */
        @WithDefault("false")
        boolean allowDevPrincipal();

        /**
         * Optional deliberately. Over a plain String, @WithDefault("") would have SmallRye convert the
         * empty string to null and then fail injection -- which surfaces as an opaque class-init error
         * rather than a config problem.
         */
        Optional<String> devPrincipalArn();
    }

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

        /**
         * Which Fargate capacity to use: {@code spot-preferred} (default), {@code on-demand-preferred},
         * {@code spot-only}, or {@code on-demand-only}.
         *
         * <p>Server configuration rather than a client parameter, for the same reason {@code launchType}
         * and {@code capacityProviderName} were removed from the plugin: capacity is a cost and
         * reliability decision belonging to whoever operates the deployment, and a client able to demand
         * on-demand capacity could raise everyone else's bill.
         *
         * <p>Spot reclaims tasks mid-build and a native-image compile is three to five minutes of work to
         * lose, so a release pipeline may well want {@code on-demand-preferred} where a development
         * deployment wants the discount. {@code spot-only} makes cost a hard constraint: a build fails to
         * launch rather than quietly running at full price.
         */
        /**
         * Per-cell {@code native-image} timeout applied when the client asks for {@code 0}.
         *
         * <p>Exists because {@code BuildSpec.timeoutMinutes} documents "0 means server default" and, until
         * this was added, there was no server default: the 0 travelled through to
         * {@link ai.codriverlabs.scaleoutbuild.ecs.AgentEnvironment}, which only sets
         * {@code SCALEOUT_BUILD_TIMEOUT_MINUTES} when the value is positive, so the agent reached
         * {@code process.waitFor()} with no deadline and a hung compile ran until something else stopped it.
         * The reaper was therefore the only bound on a crashed client's Fargate bill, not the cost
         * optimisation its own javadoc claims.
         *
         * <p><b>Why 30 and not 15.</b> A GraalVM build is about 15 minutes at the top end for most projects,
         * and our largest measured compile is 8m46s (petclinic, arm64). A safety net belongs *above* the
         * legitimate maximum, not at it: the cost of being too generous is $0.0008 per extra minute of a
         * hung cell — 1.2 cents between a 15- and a 30-minute cap — while the cost of being too tight is a
         * legitimate large build failing with a timeout error. That asymmetry is not close, so this sits at
         * roughly 2x the practical maximum and 3.4x our worst measurement.
         *
         * <p>Clients wanting a tighter bound set {@code scaleout-build.timeoutMinutes} explicitly; it is
         * passed through untouched when positive.
         */
        @WithDefault("30")
        int defaultCellTimeoutMinutes();

        @WithDefault("spot-preferred")
        String fargateCapacityStrategy();

        Optional<String> capacityProviderName();

        /** True for the plain-S3 staging mode, which is the settled design. */
        @WithDefault("true")
        boolean agentUsesDirectS3Io();
    }

    MicroVm microVm();

    /**
     * Lambda MicroVM backend configuration.
     *
     * <p>All values are optional: if {@code buildRoleArn} or {@code artifactsBucketName} are absent,
     * the {@code /admin/microvm-images} endpoint returns {@code 503 Service Unavailable} rather than
     * attempting a build with a missing dependency.
     *
     * <p>{@code adminRoleArns} is the authorization gate on the admin endpoint. It contains the exact
     * IAM role ARNs (normalized, without session suffix) whose bearers may trigger image builds.
     * Populated from an IAM Identity Center permission set role, e.g.
     * {@code arn:aws:iam::123456789012:role/AWSReservedSSO_ScaleoutBuildAdmins_abc123}. If empty or
     * absent, the admin endpoint returns {@code 503} on every call — no role can trigger builds in
     * an unconfigured deployment.
     *
     * <p>Authorization uses exact ARN matching rather than a pattern, because partial matching
     * against a user-controlled field is a bypass surface: a role named
     * {@code AWSReservedSSO_ScaleoutBuildAdmins_abc123/anything} would match a prefix check for
     * {@code ScaleoutBuildAdmins}. Callers from Identity Center always arrive with a normalized role
     * ARN (the {@code CallerIdentityResolver} strips the session name).
     */
    interface MicroVm {

        /**
         * Exact IAM role ARNs (normalized, without session suffix) that may call the admin image-build
         * endpoint. Typically the ARN of an IAM Identity Center permission set role, e.g.
         * {@code arn:aws:iam::123456789012:role/AWSReservedSSO_ScaleoutBuildAdmins_abc123}.
         *
         * <p>Empty by default: the endpoint is disabled until explicitly configured.
         */
        @WithDefault("")
        List<String> adminRoleArns();

        /**
         * ARN of the role the MicroVM platform assumes during image builds. Must trust
         * {@code lambda-microvms.amazonaws.com} and have S3 read on the artifacts bucket and
         * CloudWatch Logs write.
         */
        Optional<String> buildRoleArn();

        /**
         * S3 bucket name where code artifact ZIPs are uploaded before triggering a build.
         * The control plane Lambda must have {@code s3:GetObject} on this bucket.
         */
        Optional<String> artifactsBucketName();

        /**
         * ARN of the role the running MicroVM assumes at runtime. Must trust
         * {@code lambda-microvms.amazonaws.com} and have S3 read/write on the staging bucket and
         * CloudWatch Logs write on the agent log group.
         */
        Optional<String> executionRoleArn();
    }

    /**
     * Server-side policy. These exist because the client no longer pays for its own IAM: without a
     * ceiling, a caller could request arbitrarily large Fargate tasks, or enough concurrent builds to
     * exhaust the account's task limits.
     */
    interface Limits {

        @WithDefault("4096")
        String defaultCpu();

        /*
         * 8 GiB, the smallest pairing Fargate permits with 4 vCPU.
         *
         * Was 16384. Measured peak RSS for native-image on a 233-jar Quarkus application is 5.3-5.6 GB, so
         * 8 GiB runs at about 68% with roughly 2.5 GB spare, and a build at this size succeeded on both
         * architectures. 4 GiB is not an option: the floor is the workload rather than a setting, and
         * capping the builder's heap does not move peak RSS -- it is dominated by native memory and the
         * image heap being constructed, not the Java heap.
         *
         * CPU is deliberately left at 4 vCPU. native-image saturates it regardless of project size, so
         * halving it roughly doubles build time; that is a latency trade rather than reclaimed slack.
         */
        @WithDefault("8192")
        String defaultMemory();

        /*
         * 0, meaning "do not specify it", which gets Fargate's included 20 GiB at no charge.
         *
         * Was 40, so every task paid for the 20 GiB above the included allowance while measured consumption
         * was 1.87-2 GB, on both a two-jar example and a 233-jar Quarkus application.
         *
         * Not 20: ECS rejects an explicit size below 21 with "EphemeralStorage size should be at least 21".
         * The included 20 GiB is what you get by omitting the field, not by asking for it -- found by
         * setting it to 20 and watching every task fail to launch. TaskDefinitionRegistrar omits the field
         * when this is 0, so 0 is how you express "the free allowance".
         */
        @WithDefault("0")
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

        /**
         * Ceiling on the per-compile budget a client may request via
         * {@code -Dscaleout-build.timeoutMinutes}.
         *
         * <p>Added because it was the one requested dimension {@link ResourcePolicy} did not clamp — cpu,
         * memory, ephemeral storage and the overall timeout all were. A client asking for 9999 got it, the
         * container backstop became that plus the margin, and the only remaining ceiling was the reaper
         * firing at the clamped {@code expiresAt}. That made the bound depend on an optional component,
         * which is the wrong shape for a cost control.
         *
         * <p>Matches {@code maxOverallTimeoutMinutes} at 120, since a per-cell compile budget exceeding the
         * whole build's wall-clock ceiling cannot be honoured anyway. The paired default lives on
         * {@code Ecs} rather than here, because it is emitted to the agent alongside the other ecs settings
         * while this is policy.
         */
        @WithDefault("120")
        int maxCellTimeoutMinutes();

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
