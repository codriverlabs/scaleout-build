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
 * become the service's long-lived data plane — shared, and with no consumer other than this service
 * once migration Phase 3 revokes direct access.
 *
 * <p>Splitting them would mean a 100% one-directional dependency across a stack boundary, bridged by
 * SSM parameters that can go stale and point at a destroyed cluster, and {@code iam:PassRole} narrowed
 * against a string CDK cannot validate instead of a direct role reference.
 *
 * <p>{@code scaleout-test-infra} is kept, frozen, until migration Phase 3: Phase 1's gate is a
 * differential test that builds the example app both ways and compares, which needs both environments
 * to exist at once. It is deliberately <em>not</em> refactored to share constructs with this stack —
 * editing the working stack that the differential test depends on carries more risk than temporary
 * duplication in a module scheduled for deletion.
 */
public class ControlPlaneInfraStack extends Stack {

    /**
     * Official AWS Lambda Web Adapter layer account. The adapter bridges the Lambda Runtime API to the
     * HTTP server Quarkus runs, and is what makes {@code RESPONSE_STREAM} work for a JVM/native app.
     */
    private static final String LWA_LAYER_ACCOUNT = "753240598075";
    private static final String LWA_LAYER_VERSION = "25";

    public ControlPlaneInfraStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        boolean arm64 = !"x86_64".equalsIgnoreCase(
                String.valueOf(this.getNode().tryGetContext("serviceArch")));

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
         * RETAIN, unlike scaleout-test-infra's DESTROY. This bucket holds the content-addressed store
         * shared by every build and developer, so `cdk destroy` must not take it: losing it discards
         * the dedup cache that keeps uploads near-free, plus every produced artifact. The cost is that
         * a destroy leaves it behind for manual cleanup, which is the correct trade for a long-lived
         * service and the wrong one for a disposable test stack.
         */
        Bucket stagingBucket = Bucket.Builder.create(this, "StagingBucket")
                .versioned(true)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .enforceSsl(true)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        Cluster cluster = Cluster.Builder.create(this, "Cluster")
                .clusterName("scaleout-build")
                .vpc(vpc)
                .containerInsightsV2(software.amazon.awscdk.services.ecs.ContainerInsights.ENABLED)
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
                .runtime(Runtime.PROVIDED_AL2023)
                .architecture(arm64 ? Architecture.ARM_64 : Architecture.X86_64)
                .handler("bootstrap")
                .code(Code.fromAsset("../scaleout-build-control-plane/target/function.zip"))
                .layers(List.of(LayerVersion.fromLayerVersionArn(this, "LambdaWebAdapter", lwaLayerArn)))
                .memorySize(512)
                // The SSE endpoint holds a connection while a build runs, so this is the streaming
                // budget rather than a request timeout. LogStreamResource hands over at 780s.
                .timeout(Duration.seconds(900))
                .logGroup(serviceLogGroup)
                .environment(Map.ofEntries(
                        Map.entry("SCALEOUT_BUILDS_TABLE", buildsTable.getTableName()),
                        Map.entry("SCALEOUT_STAGING_BUCKET", stagingBucket.getBucketName()),
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
                .architecture(arm64 ? Architecture.ARM_64 : Architecture.X86_64)
                .handler("ai.codriverlabs.scaleoutbuild.controlplane.reaper.BuildReaperHandler"
                        + "::handleRequest")
                .code(Code.fromAsset("../scaleout-build-control-plane-reaper/target/reaper.jar"))
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

        Rule.Builder.create(this, "ReaperSchedule")
                .description("Stops ECS tasks of builds whose client stopped heartbeating")
                .schedule(Schedule.rate(Duration.minutes(1)))
                .targets(List.of(LambdaFunction.Builder.create(reaperFunction).build()))
                .build();

        // --- Outputs --------------------------------------------------------------------------

        StringParameter.Builder.create(this, "ServiceUrlParameter")
                .parameterName("/scaleout-build/control-plane/endpoint")
                .stringValue(serviceUrl.getUrl())
                .description("scaleout-build control plane Function URL; the only value a developer "
                        + "needs to configure")
                .build();

        CfnOutput.Builder.create(this, "ControlPlaneEndpoint").value(serviceUrl.getUrl()).build();
        CfnOutput.Builder.create(this, "AgentRepositoryUri")
                .value(agentRepository.getRepositoryUri()).build();
        CfnOutput.Builder.create(this, "ClusterArn").value(cluster.getClusterArn()).build();
        CfnOutput.Builder.create(this, "StagingBucketName").value(stagingBucket.getBucketName()).build();
        CfnOutput.Builder.create(this, "BuildsTableName").value(buildsTable.getTableName()).build();
    }
}
