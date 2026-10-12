/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.infra;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.GlobalSecondaryIndexProps;
import software.amazon.awscdk.services.dynamodb.ProjectionType;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.ec2.IpAddresses;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.TagMutability;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.Schedule;
import software.amazon.awscdk.services.events.targets.LambdaFunction;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionUrl;
import software.amazon.awscdk.services.lambda.FunctionUrlAuthType;
import software.amazon.awscdk.services.lambda.FunctionUrlOptions;
import software.amazon.awscdk.services.lambda.InvokeMode;
import software.amazon.awscdk.services.lambda.LayerVersion;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

/**
 * One stack containing the whole builder environment: the ECS data plane the builds run on, plus the
 * control plane in front of it.
 *
 * <h2>Why this is one stack and not two</h2>
 *
 * The cluster, staging bucket and task roles were originally {@code scaleout-test-infra}, a stack
 * documented as disposable because each developer deployed their own copy and ran Maven against it
 * directly. Once the control plane is the only way in, those resources stop being test scaffolding and
 * become the service's long-lived data plane — shared, and with no consumer other than this service.
 *
 * <p>Splitting them would mean a 100% one-directional dependency across a stack boundary, bridged by
 * SSM parameters that can go stale and point at a destroyed cluster, and {@code iam:PassRole} narrowed
 * against a string CDK cannot validate instead of a direct role reference.
 *
 * <p>{@code scaleout-test-infra} has been deleted. It was originally to be kept frozen so that a
 * differential test could build the example app both ways and compare — but the direct-ECS path is not
 * being retained at all, so there is nothing to compare against and no reason to keep it. Its ECR
 * repository and log group names collided with this stack's, so the old stack had to be destroyed
 * before this one could deploy.
 */
public class ControlPlaneInfraStack extends Stack {

    /**
     * Official AWS Lambda Web Adapter layer account. The adapter bridges the Lambda Runtime API to the
     * HTTP server Quarkus runs, and is what makes {@code RESPONSE_STREAM} work for a JVM/native app.
     */
    private static final String LWA_LAYER_ACCOUNT = "753240598075";
    private static final String LWA_LAYER_VERSION = "30"; // v1.1.0

    public ControlPlaneInfraStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        /*
         * Deployment mode, chosen by CDK context rather than baked in:
         *
         *   -c runtimeMode=jvm            JAVA_25 on ARM_64, from function-jvm.zip
         *   -c runtimeMode=native         provided.al2023, from function-native.zip
         *   -c nativeArch=x86|arm64       which architecture the native binary was built for
         *
         * JVM is the default because it needs no GraalVM toolchain, so a first deploy works from a
         * plain `mvn package`. A JVM zip is architecture-neutral -- it is bytecode -- so ARM_64 is
         * simply chosen for price/performance, with nothing multi-arch to build.
         *
         * A native binary is the opposite: it is architecture-specific, so nativeArch must match the
         * Mandrel image it was produced by. Claiming the wrong one yields an Exec format error at cold
         * start, which is why the scripts derive it from the build rather than leaving it to be typed.
         */
        String runtimeMode = String.valueOf(
                this.getNode().tryGetContext("runtimeMode") == null ? "jvm"
                        : this.getNode().tryGetContext("runtimeMode"));
        boolean jvmMode = !"native".equalsIgnoreCase(runtimeMode);
        boolean nativeX86 = "x86".equalsIgnoreCase(
                String.valueOf(this.getNode().tryGetContext("nativeArch")));

        // JVM: ARM_64 for price/performance, since the zip runs on either. Native: whatever it was
        // compiled for.
        Architecture serviceArch = jvmMode ? Architecture.ARM_64
                : (nativeX86 ? Architecture.X86_64 : Architecture.ARM_64);
        boolean arm64 = serviceArch == Architecture.ARM_64;

        /*
         * Where the deployable zips come from. Development resolves straight out of target/, which is
         * what the local scripts use; otherwise from assets/, which is what a release or CI pipeline
         * populates. Distinct filenames per mode, so building JVM and deploying native fails with a
         * missing file instead of starting a function that cannot execute its own handler.
         */
        /*
         * Not validated here: the enum lives in scaleout-build-ecs, which the CDK module deliberately does
         * not depend on. A bad value therefore fails on the first build with a message naming the accepted
         * set, rather than at synth. Worth knowing, but adding a module dependency to move the error a few
         * minutes earlier is the wrong trade.
         */
        String capacityStrategy = String.valueOf(
                this.getNode().tryGetContext("fargateCapacityStrategy") == null ? "spot-preferred"
                        : this.getNode().tryGetContext("fargateCapacityStrategy"));

        boolean development = !"false".equals(
                String.valueOf(this.getNode().tryGetContext("development")));
        String serviceZip = development
                ? "../scaleout-build-control-plane/target/" + (jvmMode ? "function-jvm.zip"
                        : "function-native.zip")
                : "../assets/control-plane-" + (jvmMode ? "jvm" : "native") + ".zip";
        String reaperZip = development
                ? "../scaleout-build-control-plane-reaper/target/reaper.jar"
                : "../assets/reaper.jar";

        // --- Data plane -----------------------------------------------------------------------

        // Public subnets only and no NAT gateway: build tasks need outbound access to ECR, S3 and
        // CloudWatch, and a NAT gateway would cost more than every build combined. Requires
        // assignPublicIp on the tasks, which the service config defaults to true.
        Vpc vpc = Vpc.Builder.create(this, "Vpc")
                .ipAddresses(IpAddresses.cidr("10.0.0.0/16"))
                .maxAzs(2)
                .natGateways(0)
                .subnetConfiguration(List.of(SubnetConfiguration.builder()
                        .name("public")
                        .subnetType(SubnetType.PUBLIC)
                        .cidrMask(24)
                        .build()))
                .enableDnsHostnames(true)
                .enableDnsSupport(true)
                .build();

        SecurityGroup taskSecurityGroup = SecurityGroup.Builder.create(this, "TaskSecurityGroup")
                .vpc(vpc)
                .description("scaleout build agent tasks: egress only")
                .allowAllOutbound(true)
                .build();

        /*
         * RETAIN, where the retired scaleout-test-infra used DESTROY. This bucket holds the content-addressed store
         * shared by every build and developer, so `cdk destroy` must not take it: losing it discards
         * the dedup cache that keeps uploads near-free, plus every produced artifact. The cost is that
         * a destroy leaves it behind for manual cleanup, which is the correct trade for a long-lived
         * service and the wrong one for a disposable test stack.
         *
         * Named deterministically, consistently with every other resource here (cluster
         * `scaleout-build`, table `scaleout-builds`, repository `scaleout-build-agent`, log groups
         * under `/scaleout-build/`), rather than left to CDK's generated name. Bucket names are
         * globally unique, so account and region are part of it -- which also makes it regional by
         * construction, so two regions can host independent deployments.
         *
         * A predictable name is operationally useful: an engineer can find the staging prefix in the
         * console without being handed the name, which is why clients do not need it either. They
         * receive presigned URLs, which already carry bucket, key, expiry and signature.
         *
         * Consequence of a fixed name plus RETAIN, worth knowing before the first teardown: after
         * `cdk destroy` the bucket survives, so a later `cdk deploy` fails with "bucket already
         * exists" rather than silently creating a second one. Recovery is `cdk import` to re-adopt it,
         * or deleting it deliberately. That is the intended failure mode -- the alternative is a
         * generated name that quietly orphans a bucket full of artifacts on every redeploy.
         */
        Bucket stagingBucket = Bucket.Builder.create(this, "StagingBucket")
                .bucketName(String.format("scaleout-build-staging-%s-%s",
                        this.getAccount(), this.getRegion()))
                .versioned(true)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .enforceSsl(true)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        Cluster cluster = Cluster.Builder.create(this, "Cluster")
                .clusterName("scaleout-build")
                .vpc(vpc)
                .containerInsightsV2(software.amazon.awscdk.services.ecs.ContainerInsights.ENABLED)
                /*
                 * Required, not an optimisation.
                 *
                 * EcsTaskLauncher launches FARGATE work through a capacityProviderStrategy of
                 * FARGATE_SPOT with an on-demand fallback, rather than a plain launchType. ECS rejects
                 * any strategy naming a provider that is not associated with the cluster:
                 * "The specified capacity provider strategy cannot contain a capacity provider that is
                 * not associated with the cluster."
                 *
                 * This associates both FARGATE and FARGATE_SPOT. The predecessor stack did the same; the
                 * consolidated stack dropped it, and the first real build failed on every cell. Nothing
                 * detected it earlier because the error only appears at RunTask, and no synth assertion
                 * covered the pairing between the launcher's strategy and the cluster's providers.
                 */
                .enableFargateCapacityProviders(true)
                .build();

        Repository agentRepository = Repository.Builder.create(this, "AgentRepository")
                .repositoryName("scaleout-build-agent")
                .imageTagMutability(TagMutability.MUTABLE)
                .emptyOnDelete(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        LogGroup agentLogGroup = LogGroup.Builder.create(this, "AgentLogGroup")
                .logGroupName("/scaleout-build/build-agent")
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Role executionRole = Role.Builder.create(this, "TaskExecutionRole")
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName(
                        "service-role/AmazonECSTaskExecutionRolePolicy")))
                .build();

        Role taskRole = Role.Builder.create(this, "TaskRole")
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .build();
        // The agent reads staged inputs and writes produced artifacts under its own credentials in the
        // direct-S3 staging mode.
        stagingBucket.grantReadWrite(taskRole);

        // --- Build state ----------------------------------------------------------------------

        /*
         * RETAIN for the same reason as the bucket: this is the build history a developer inspects the
         * morning after a failure, and destroying the stack should not erase it.
         */
        Table buildsTable = Table.Builder.create(this, "BuildsTable")
                .tableName("scaleout-builds")
                .partitionKey(Attribute.builder().name("buildId").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .timeToLiveAttribute("ttl")
                .pointInTimeRecoverySpecification(
                        software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification
                                .builder().pointInTimeRecoveryEnabled(true).build())
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        // "List my builds" without a table scan. Build ids sort chronologically, so querying this
        // index backwards yields newest-first for free.
        buildsTable.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
                .indexName("owner-index")
                .partitionKey(Attribute.builder().name("ownerKey").type(AttributeType.STRING).build())
                .sortKey(Attribute.builder().name("buildId").type(AttributeType.STRING).build())
                .projectionType(ProjectionType.ALL)
                .build());

        // --- Control plane service ------------------------------------------------------------

        LogGroup serviceLogGroup = LogGroup.Builder.create(this, "ServiceLogGroup")
                .logGroupName("/aws/lambda/scaleout-build-control-plane")
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        String lwaLayerArn = String.format("arn:aws:lambda:%s:%s:layer:LambdaAdapterLayer%s:%s",
                this.getRegion(), LWA_LAYER_ACCOUNT, arm64 ? "Arm64" : "X86", LWA_LAYER_VERSION);

        Function serviceFunction = Function.Builder.create(this, "ControlPlaneFunction")
                .functionName("scaleout-build-control-plane")
                // provided.al2023 with the GraalVM binary named `bootstrap`. Deliberately native-only:
                // a managed-runtime JVM variant needs a launcher script whose handler value and jar
                // name are a second thing to keep in sync, which is broken in the reference project
                // this pattern came from.
                .runtime(jvmMode ? Runtime.JAVA_25 : Runtime.PROVIDED_AL2023)
                .architecture(serviceArch)
                /*
                 * JVM mode: run.sh, shipped inside the zip, which the Web Adapter's exec wrapper starts.
                 * Native mode: bootstrap, the renamed GraalVM binary, which is the handler itself.
                 * The JVM launcher derives its jar name at build time so the two cannot drift.
                 */
                .handler(jvmMode ? "run.sh" : "bootstrap")
                .code(Code.fromAsset(serviceZip))
                /*
                 * Sized against measured usage rather than guessed headroom. See
                 * docs/design/control-plane/lambda-resource-usage.md for the figures these come from:
                 * peak 226 MB observed in JVM mode, 145 MB native.
                 *
                 * Memory also buys vCPU -- Lambda allocates it in proportion, roughly one vCPU at
                 * 1769 MB -- so these values trade cost against latency, not just against headroom. The
                 * JVM figure is the tighter of the two: 226 MB measured against 384 MB provisioned, and
                 * an overrun is an OOM kill rather than a slowdown, so it is worth re-checking with
                 * scripts/lambda-usage.sh after any change that grows the working set.
                 */
                .memorySize(jvmMode ? 384 : 256)
                .layers(List.of(LayerVersion.fromLayerVersionArn(this, "LambdaWebAdapter", lwaLayerArn)))
                // The SSE endpoint holds a connection while a build runs, so this is the streaming
                // budget rather than a request timeout. LogStreamResource hands over at 780s.
                .timeout(Duration.seconds(900))
                .logGroup(serviceLogGroup)
                .environment(Map.ofEntries(
                        Map.entry("SCALEOUT_BUILDS_TABLE", buildsTable.getTableName()),
                        Map.entry("SCALEOUT_STAGING_BUCKET", stagingBucket.getBucketName()),
                        /*
                         * Capacity policy, settable per deployment without editing code:
                         *   cdk deploy -c fargateCapacityStrategy=on-demand-preferred
                         *
                         * Spot reclaims tasks mid-build and a native-image compile is three to five
                         * minutes of work to lose, so a release deployment may want on-demand while a
                         * development one takes the discount. Accepted values are validated by
                         * FargateCapacityStrategy.parse, which names them all on a typo.
                         */
                        Map.entry("SCALEOUT_ECS_FARGATE_CAPACITY_STRATEGY", capacityStrategy),
                        Map.entry("SCALEOUT_ECS_CLUSTER_ARN", cluster.getClusterArn()),
                        Map.entry("SCALEOUT_ECS_SUBNET_IDS", String.join(",",
                                vpc.selectSubnets(SubnetSelection.builder()
                                        .subnetType(SubnetType.PUBLIC).build()).getSubnetIds())),
                        Map.entry("SCALEOUT_ECS_SECURITY_GROUP_IDS",
                                taskSecurityGroup.getSecurityGroupId()),
                        Map.entry("SCALEOUT_ECS_EXECUTION_ROLE_ARN", executionRole.getRoleArn()),
                        Map.entry("SCALEOUT_ECS_TASK_ROLE_ARN", taskRole.getRoleArn()),
                        Map.entry("SCALEOUT_ECS_LOG_GROUP_NAME", agentLogGroup.getLogGroupName()),
                        Map.entry("SCALEOUT_ECS_AGENT_IMAGE",
                                agentRepository.getRepositoryUri() + ":latest"),
                        // LWA wiring, all four required together.
                        Map.entry("AWS_LWA_INVOKE_MODE", "response_stream"),
                        Map.entry("AWS_LAMBDA_EXEC_WRAPPER", "/opt/bootstrap"),
                        Map.entry("READINESS_CHECK_PATH", "/q/health/ready"),
                        Map.entry("PORT", "8080"),
                        Map.entry("SCALEOUT_LOG_JSON", "true")))
                .build();

        FunctionUrl serviceUrl = serviceFunction.addFunctionUrl(FunctionUrlOptions.builder()
                .authType(FunctionUrlAuthType.AWS_IAM)
                .invokeMode(InvokeMode.RESPONSE_STREAM)
                .build());

        buildsTable.grantReadWriteData(serviceFunction);
        stagingBucket.grantReadWrite(serviceFunction);

        serviceFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("ecs:RunTask", "ecs:DescribeTasks", "ecs:StopTask",
                        "ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition",
                        "ecs:ListTaskDefinitions", "ecs:TagResource"))
                // RegisterTaskDefinition and ListTaskDefinitions are registry-level and cannot be
                // scoped to a cluster; the task-scoped actions are constrained by the PassRole
                // statement below, which is what actually prevents running arbitrary workloads.
                .resources(List.of("*"))
                .build());

        /*
         * The narrowest statement in the stack, and the one that matters most.
         *
         * Without a resource constraint here, anyone able to invoke the Function URL could have the
         * service launch a task under ANY role in the account -- turning this service into a generic
         * privilege-escalation primitive. Restricting PassRole to these two specific roles, and to the
         * ECS tasks service, is what bounds the service's authority to "run the build agent".
         */
        serviceFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("iam:PassRole"))
                .resources(List.of(executionRole.getRoleArn(), taskRole.getRoleArn()))
                .conditions(Map.of("StringEquals",
                        Map.of("iam:PassedToService", "ecs-tasks.amazonaws.com")))
                .build());

        // Reading the agent's log streams to relay them over SSE.
        agentLogGroup.grantRead(serviceFunction);

        // --- Reaper ---------------------------------------------------------------------------

        Function reaperFunction = Function.Builder.create(this, "ReaperFunction")
                .functionName("scaleout-build-control-plane-reaper")
                .runtime(Runtime.JAVA_25)
                // Always ARM_64: a jar is architecture-neutral, so this is a pricing choice and is
                // deliberately independent of whichever mode the service is deployed in.
                .architecture(Architecture.ARM_64)
                .handler("ai.codriverlabs.scaleoutbuild.controlplane.reaper.BuildReaperHandler"
                        + "::handleRequest")
                .code(Code.fromAsset(reaperZip))
                .memorySize(512)
                .timeout(Duration.seconds(120))
                .environment(Map.of(
                        "SCALEOUT_BUILDS_TABLE", buildsTable.getTableName(),
                        "SCALEOUT_ECS_CLUSTER_ARN", cluster.getClusterArn(),
                        "SCALEOUT_HEARTBEAT_GRACE_SECONDS", "120"))
                .build();
        // No Function URL on purpose: the only principal that may invoke this is the EventBridge rule
        // below. An endpoint on the service itself would be reachable through the public URL.
        buildsTable.grantReadWriteData(reaperFunction);
        reaperFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("ecs:StopTask", "ecs:DescribeTasks"))
                .resources(List.of("*"))
                .build());
        // Same action already granted to the service function below for the admin image-build
        // flow's own cleanup, granted a second time here because the reaper runs as its own
        // function with its own role -- see docs/design/control-plane/
        // microvm-build-backend-phase1.md's "CDK additions" section. Resource-unscoped like the
        // ECS actions above: TerminateMicrovm takes a microvm id, not an image ARN, so there is
        // nothing narrower to scope it to the way CreateMicrovmImage's actions below are scoped
        // to the image name.
        reaperFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("lambda:TerminateMicrovm"))
                .resources(List.of("*"))
                .build());

        Rule.Builder.create(this, "ReaperSchedule")
                .description("Stops ECS tasks of builds whose client stopped heartbeating")
                .schedule(Schedule.rate(Duration.minutes(1)))
                .targets(List.of(LambdaFunction.Builder.create(reaperFunction).build()))
                .build();

        // --- Lambda MicroVM backend -----------------------------------------------------------
        //
        // Optional: only provisioned when the CDK context key `microvmAdminRoleArn` is set.
        // Absence is not an error -- deployments that only use Fargate do not need these resources.
        //
        // The admin role ARN identifies the IAM role (typically an Identity Center permission set
        // role) whose bearers may trigger MicroVM image builds via POST /admin/microvm-images.
        // Exact ARN matching is used server-side -- see AdminResource for the security rationale.

        String microvmAdminRoleArn = String.valueOf(
                this.getNode().tryGetContext("microvmAdminRoleArn") == null ? ""
                        : this.getNode().tryGetContext("microvmAdminRoleArn"));

        // Memory baseline (MiB) the agent MicroVM image is built at -- vCPU scales proportionally
        // (2 GB memory = 1 vCPU) and the platform bursts up to 4x this baseline automatically,
        // billed per second only for what is actually used above it. Must be one of
        // MicroVmImageManager.SUPPORTED_MINIMUM_MEMORY_MIB (512/1024/2048/4096/8192);
        // AdminResource/MicroVmImageManager validate this at build-trigger time rather than here,
        // so an invalid context value fails loudly on the first real "images build" call instead
        // of silently at synth time. Confirmed against AWS's own documentation (Lambda MicroVMs'
        // "MicroVM images" page, "MicroVM sizing" section) that this is a build-time property of
        // the image, not a per-RunMicrovm choice -- the same reason this is deployment context
        // rather than a CLI/API-caller-supplied value. Read here (alongside microvmAdminRoleArn)
        // rather than inside the microvmEnabled block below: image building is gated by the admin
        // role, not by microvmEnabled, which only gates whether built images are actually used to
        // launch build-execution cells.
        String microvmMinimumMemoryMib = String.valueOf(
                this.getNode().tryGetContext("microvmMinimumMemoryMib") == null ? "2048"
                        : this.getNode().tryGetContext("microvmMinimumMemoryMib"));

        if (!microvmAdminRoleArn.isBlank()) {
            // Bucket for the code artifact ZIPs submitted to CreateMicrovmImage.
            Bucket microvmArtifactsBucket = Bucket.Builder.create(this, "MicrovmArtifactsBucket")
                    .bucketName(String.format("scaleout-build-microvm-artifacts-%s-%s",
                            this.getAccount(), this.getRegion()))
                    .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                    .enforceSsl(true)
                    .removalPolicy(RemovalPolicy.RETAIN)
                    .build();

            // Role the MicroVM platform assumes during image builds.
            // Trusts lambda.amazonaws.com -- confirmed against AWS's own documentation
            // (Lambda MicroVMs' "Security and permissions" page): "Both roles require a trust
            // policy that allows the Lambda service principal lambda.amazonaws.com to perform
            // sts:AssumeRole and sts:TagSession." There is no separate
            // "lambda-microvms.amazonaws.com" principal -- confirmed the hard way, by actually
            // deploying: IAM rejected it outright with "Invalid principal in policy."
            //
            // assumedBy() alone only grants sts:AssumeRole (CDK's ServicePrincipal default
            // action); sts:TagSession is added as a second trust-policy statement afterward,
            // same pattern CDK's own docs use for EMR's SetSourceIdentity/TagSession case.
            Role microvmBuildRole = Role.Builder.create(this, "MicrovmBuildRole")
                    .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                    // Em dash removed: IAM role descriptions are restricted to a limited ASCII +
                    // Latin-1 character set, and the em dash originally here fell outside it.
                    // Also confirmed the hard way -- IAM rejected the role creation with a regex
                    // validation error.
                    .description("Role for Lambda MicroVM image builds - scaleout-build agent")
                    .build();
            microvmBuildRole.getAssumeRolePolicy().addStatements(PolicyStatement.Builder.create()
                    .effect(Effect.ALLOW)
                    .principals(List.of(new ServicePrincipal("lambda.amazonaws.com")))
                    .actions(List.of("sts:TagSession"))
                    .build());
            microvmArtifactsBucket.grantRead(microvmBuildRole);
            agentLogGroup.grantWrite(microvmBuildRole);

            // Role the running MicroVM assumes at runtime.
            // Needs staging bucket and log group access (same as the Fargate task role).
            // Same corrected service principal and sts:TagSession statement as microvmBuildRole.
            Role microvmExecutionRole = Role.Builder.create(this, "MicrovmExecutionRole")
                    .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                    .description("Execution role for scaleout-build agent MicroVMs at runtime")
                    .build();
            microvmExecutionRole.getAssumeRolePolicy().addStatements(PolicyStatement.Builder.create()
                    .effect(Effect.ALLOW)
                    .principals(List.of(new ServicePrincipal("lambda.amazonaws.com")))
                    .actions(List.of("sts:TagSession"))
                    .build());
            stagingBucket.grantReadWrite(microvmExecutionRole);
            agentLogGroup.grantWrite(microvmExecutionRole);

            // Grant the control plane Lambda the MicroVM operations it needs.
            //
            // CreateMicrovmImage is split into its own Resource: "*" statement, confirmed required
            // the hard way: AWS's own least-privilege example policy for Lambda MicroVMs
            // (Security and permissions page) places lambda:CreateMicrovmImage outside the
            // resource-scoped statement entirely, alongside lambda:ListMicrovms/ListMicrovmImages.
            // Scoping it to the target image's ARN -- the first version of this code did -- fails
            // every call with "no identity-based policy allows the lambda:CreateMicrovmImage
            // action", because the image does not exist yet at the moment of the call; there is no
            // resource for a resource-scoped statement to match against. The actions that DO
            // name an existing image (GetMicrovmImage, GetMicrovmImageBuild, TerminateMicrovm) stay
            // scoped to the fixed image name, same reasoning as before: it prevents the service
            // from creating/touching arbitrarily-named images.
            serviceFunction.addToRolePolicy(PolicyStatement.Builder.create()
                    .effect(Effect.ALLOW)
                    .actions(List.of("lambda:CreateMicrovmImage"))
                    .resources(List.of("*"))
                    .build());
            serviceFunction.addToRolePolicy(PolicyStatement.Builder.create()
                    .effect(Effect.ALLOW)
                    .actions(List.of(
                            "lambda:GetMicrovmImage",
                            "lambda:GetMicrovmImageBuild",
                            "lambda:TerminateMicrovm"))
                    // Scoped to the image name to prevent the service from touching arbitrary images.
                    .resources(List.of(
                            String.format("arn:aws:lambda:%s:%s:microvm-image:scaleout-build-agent",
                                    this.getRegion(), this.getAccount())))
                    .build());

            // PassRole for both MicroVM roles -- scoped to the Lambda service principal, the one
            // the role's own trust policy actually names (confirmed above: lambda.amazonaws.com,
            // not "lambda-microvms.amazonaws.com", which doesn't exist as a service principal).
            // Build role was missing entirely before this fix: CreateMicrovmImage passes
            // microvmBuildRole, and the service function had no PassRole grant for it at all,
            // confirmed the hard way -- "User: ... is not authorized to perform: iam:PassRole on
            // resource: .../MicrovmBuildRole..." on the first real triggerImageBuild call.
            serviceFunction.addToRolePolicy(PolicyStatement.Builder.create()
                    .effect(Effect.ALLOW)
                    .actions(List.of("iam:PassRole"))
                    .resources(List.of(microvmBuildRole.getRoleArn(), microvmExecutionRole.getRoleArn()))
                    .conditions(Map.of("StringEquals",
                            Map.of("iam:PassedToService", "lambda.amazonaws.com")))
                    .build());

            // PassNetworkConnector for the AWS-managed default egress connector, found the hard
            // way on the first real CreateMicrovmImage call: "User: ... is not authorized to
            // perform: lambda:PassNetworkConnector on resource:
            // arn:aws:lambda:us-east-1:aws:network-connector:aws-network-connector:INTERNET_EGRESS".
            // MicroVmImageManager.triggerBuild passes no egressNetworkConnectors, so the platform
            // defaults the image-build-time connector to this fixed, AWS-owned ARN (account "aws",
            // not this account) rather than leaving it unset -- confirmed by the exact resource ARN
            // in the error, not assumed. Account-agnostic and stable across regions in the sense
            // that AWS owns it, so this is a fixed string rather than something built from
            // this.getRegion()/this.getAccount().
            serviceFunction.addToRolePolicy(PolicyStatement.Builder.create()
                    .effect(Effect.ALLOW)
                    .actions(List.of("lambda:PassNetworkConnector"))
                    .resources(List.of(
                            "arn:aws:lambda:" + this.getRegion()
                                    + ":aws:network-connector:aws-network-connector:INTERNET_EGRESS"))
                    .build());

            // PassNetworkConnector for the AWS-managed default INGRESS connector, found the hard
            // way on the first real RunMicrovm call (not CreateMicrovmImage -- a different
            // operation, a different default connector): "User: ... is not authorized to
            // perform: lambda:PassNetworkConnector on resource:
            // arn:aws:lambda:us-east-1:aws:network-connector:aws-network-connector:HTTP_INGRESS".
            // MicroVmCellLauncher.launch() sets no ingress connector at all (deliberately -- see
            // its own javadoc on why the control plane never calls into a MicroVM), yet the
            // platform still defaults RunMicrovm to this fixed, AWS-owned HTTP_INGRESS connector
            // rather than running with no ingress path. Confirmed by the exact resource ARN in
            // the error, the same way INTERNET_EGRESS was confirmed above -- neither default was
            // documented anywhere found during this phase's design or implementation, only
            // surfaced by actually calling the real APIs.
            serviceFunction.addToRolePolicy(PolicyStatement.Builder.create()
                    .effect(Effect.ALLOW)
                    .actions(List.of("lambda:PassNetworkConnector"))
                    .resources(List.of(
                            "arn:aws:lambda:" + this.getRegion()
                                    + ":aws:network-connector:aws-network-connector:HTTP_INGRESS"))
                    .build());

            // Wire MicroVM config into the Lambda environment.
            serviceFunction.addEnvironment("SCALEOUT_MICROVM_ADMIN_ROLE_ARNS", microvmAdminRoleArn);
            serviceFunction.addEnvironment("SCALEOUT_MICROVM_BUILD_ROLE_ARN", microvmBuildRole.getRoleArn());
            serviceFunction.addEnvironment("SCALEOUT_MICROVM_ARTIFACTS_BUCKET", microvmArtifactsBucket.getBucketName());
            serviceFunction.addEnvironment("SCALEOUT_MICROVM_EXECUTION_ROLE_ARN", microvmExecutionRole.getRoleArn());
            serviceFunction.addEnvironment("SCALEOUT_MICROVM_MINIMUM_MEMORY_MIB", microvmMinimumMemoryMib);

            microvmArtifactsBucket.grantRead(serviceFunction);

            CfnOutput.Builder.create(this, "MicrovmBuildRoleArn")
                    .value(microvmBuildRole.getRoleArn())
                    .description("Role ARN for Lambda MicroVM image builds").build();
            CfnOutput.Builder.create(this, "MicrovmExecutionRoleArn")
                    .value(microvmExecutionRole.getRoleArn())
                    .description("Execution role ARN for running MicroVMs").build();
            // Output id "MicrovmArtifactsBucketName" rather than "MicrovmArtifactsBucket" --
            // the latter collides with the Bucket construct's own id a few lines above, in the
            // same scope (`this`). CDK rejects duplicate construct ids, and this specific
            // collision had never been exercised by any synth before this step, because
            // verify-synth.sh never sets microvmAdminRoleArn. Found by actually synthesizing with
            // it set while verifying this step's own CDK additions, not by inspection.
            CfnOutput.Builder.create(this, "MicrovmArtifactsBucketName")
                    .value(microvmArtifactsBucket.getBucketName())
                    .description("S3 bucket for MicroVM code artifact ZIPs").build();

            // --- Build-execution MicroVM (arm64 cells launched as MicroVMs, not Fargate) -------
            //
            // Deliberately NESTED under microvmAdminRoleArn's gate rather than independently
            // gated -- see docs/design/control-plane/microvm-build-backend-phase1.md's open
            // question 2 for the two options and this document's resolution. Build-execution
            // reuses microvmExecutionRole (created above) unchanged, per that document's "Config
            // additions" reasoning: identical permission set, same trust boundary, no isolation
            // benefit from a second role. That reuse is only possible because this role already
            // exists by the time this code runs -- it cannot be created independently of the
            // admin flow's own block, which is the concrete reason "independent gate" was not
            // chosen: there would be nothing for an independent gate's execution role to reuse.
            //
            // A deployment can still build images without yet trusting them for real builds:
            // microvmAdminRoleArn set + microvmEnabled unset/false builds and qualifies an image
            // with no builds routed to it. Flipping to arm64-on-MicroVM is then microvmEnabled=true
            // plus the image/version/connector context keys, independent of the admin gate itself.

            boolean microvmEnabled = Boolean.parseBoolean(String.valueOf(
                    this.getNode().tryGetContext("microvmEnabled") == null ? "false"
                            : this.getNode().tryGetContext("microvmEnabled")));
            String microvmImageArn = String.valueOf(
                    this.getNode().tryGetContext("microvmImageArn") == null ? ""
                            : this.getNode().tryGetContext("microvmImageArn"));
            String microvmImageVersion = String.valueOf(
                    this.getNode().tryGetContext("microvmImageVersion") == null ? ""
                            : this.getNode().tryGetContext("microvmImageVersion"));
            String microvmVpcEgressConnectorArn = String.valueOf(
                    this.getNode().tryGetContext("microvmVpcEgressConnectorArn") == null ? ""
                            : this.getNode().tryGetContext("microvmVpcEgressConnectorArn"));

            // RunMicrovm/GetMicrovm granted whenever the admin gate is open, not only when
            // microvmEnabled is currently true -- the same reasoning TerminateMicrovm above
            // already has (confirmed read first): a config flip to enable builds should not also
            // require a redeploy just to grant the IAM action it needs, and an unused grant is not
            // itself a cost or a live behavior change (ControlPlaneConfig.MicroVm.enabled() is
            // still what gates BuildService.cellLauncher() at runtime).
            serviceFunction.addToRolePolicy(PolicyStatement.Builder.create()
                    .effect(Effect.ALLOW)
                    .actions(List.of(
                            "lambda:RunMicrovm",
                            "lambda:GetMicrovm"))
                    // Same image-name scoping as the admin actions above.
                    .resources(List.of(
                            String.format("arn:aws:lambda:%s:%s:microvm-image:scaleout-build-agent",
                                    this.getRegion(), this.getAccount())))
                    .build());

            serviceFunction.addEnvironment("SCALEOUT_MICROVM_ENABLED", String.valueOf(microvmEnabled));
            serviceFunction.addEnvironment("SCALEOUT_MICROVM_IMAGE_ARN", microvmImageArn);
            serviceFunction.addEnvironment("SCALEOUT_MICROVM_IMAGE_VERSION", microvmImageVersion);
            serviceFunction.addEnvironment("SCALEOUT_MICROVM_VPC_EGRESS_CONNECTOR_ARN",
                    microvmVpcEgressConnectorArn);
        }

        // --- Outputs --------------------------------------------------------------------------

        StringParameter.Builder.create(this, "ServiceUrlParameter")
                .parameterName("/scaleout-build/control-plane/endpoint")
                .stringValue(serviceUrl.getUrl())
                .description("scaleout-build control plane Function URL; the only value a developer "
                        + "needs to configure")
                .build();

        CfnOutput.Builder.create(this, "ControlPlaneEndpoint").value(serviceUrl.getUrl()).build();
        // Surfaced so `cdk deploy` output states which mode is live: the two are indistinguishable
        // from the endpoint alone, and a native deploy that silently fell back would otherwise be
        // invisible until someone checked the console.
        CfnOutput.Builder.create(this, "ServiceRuntimeMode")
                .value((jvmMode ? "jvm" : "native") + "/" + (arm64 ? "arm64" : "x86_64"))
                .build();
        CfnOutput.Builder.create(this, "AgentRepositoryUri")
                .value(agentRepository.getRepositoryUri()).build();
        CfnOutput.Builder.create(this, "ClusterArn").value(cluster.getClusterArn()).build();
        CfnOutput.Builder.create(this, "StagingBucketName").value(stagingBucket.getBucketName()).build();
        CfnOutput.Builder.create(this, "BuildsTableName").value(buildsTable.getTableName()).build();
    }
}
