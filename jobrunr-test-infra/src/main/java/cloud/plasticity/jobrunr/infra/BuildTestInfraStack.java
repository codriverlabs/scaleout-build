/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.infra;

import java.util.List;
import java.util.stream.Collectors;
import software.amazon.awscdk.Aws;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.ISubnet;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3files.CfnFileSystem;
import software.amazon.awscdk.services.s3files.CfnMountTarget;
import software.constructs.Construct;

/**
 * Minimal, disposable AWS environment for exercising {@code aws-ecs:build}'s {@code FARGATE}
 * launch type with S3 Files staging against real AWS.
 *
 * <p>Deliberately narrow in scope — this is a test harness, not a production reference
 * architecture: public subnets with no NAT gateway (keeps cost near zero and avoids NAT setup;
 * requires {@code aws-ecs.assignPublicIp=true}), one AZ's worth of resources kept to the minimum
 * needed to run one task, one week of log retention, and every resource tagged/named so it's
 * obvious this stack is disposable. Everything a live {@code aws-ecs:build} invocation needs is
 * emitted as a {@link CfnOutput} — see {@code jobrunr-test-infra/README.md} for how to feed them
 * into the plugin's parameters.
 *
 * <p>Every IAM permission here traces to a specific, verified source rather than a guess:
 *
 * <ul>
 *   <li>The S3 Files service role's trust policy and permissions (S3 bucket access, plus the
 *       {@code events:*} permissions for the {@code DO-NOT-DELETE-S3-Files*} rules S3 Files
 *       creates to detect object changes) are copied directly from AWS's own {@code CfnFileSystem}
 *       CDK documentation example — not reconstructed from general S3/EventBridge knowledge.
 *   <li>The ECS task role's two-part policy (S3 Files client access, plus a separate inline S3
 *       read policy) matches AWS's "Prerequisites for S3 Files" documentation exactly: {@code
 *       AmazonS3FilesClientFullAccess} for connecting to and interacting with the file system
 *       (read + write, since the agent both reads staged inputs and writes its output through the
 *       mount), plus {@code s3:GetObject}/{@code s3:GetObjectVersion}/{@code s3:ListBucket} scoped
 *       to the staging bucket for direct S3 reads. The task role does not need broader S3 write
 *       access to the bucket directly for the mount-based launch types —
 *       {@code S3StagingSink}/{@code S3ArtifactRetriever} run with the <em>plugin's</em>
 *       credentials, not the task's; the task only ever reads/writes through the S3 Files mount
 *       there. A separate, narrowly scoped {@code s3:PutObject} statement is granted in addition,
 *       specifically for {@code aws-ecs.agentUsesDirectS3Io=true}: in that mode the agent uploads
 *       produced artifacts itself, directly, under this role's own credentials, rather than
 *       through a mount — see {@code createTaskRole}'s Javadoc and
 *       {@code docs/PURE_ECS_ALTERNATIVE.md}'s "agent's own direct S3 calls" section for why this
 *       is a genuinely separate requirement from the S3 Files prerequisites above it.
 * </ul>
 */
public class BuildTestInfraStack extends Stack {

    private static final String CONTAINER_NAME = "jobrunr-build-agent";
    private static final String LOG_GROUP_NAME = "/jobrunr/build-agent";

    public BuildTestInfraStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        Vpc vpc = Vpc.Builder.create(this, "Vpc")
                .maxAzs(2)
                .natGateways(0)
                // S3 Files mounting fails to resolve its DNS name without these -- confirmed as a
                // real, distinct failure mode ("DNS name resolution fails") separate from security
                // group misconfiguration, not assumed to already be CDK's default.
                .enableDnsHostnames(true)
                .enableDnsSupport(true)
                .subnetConfiguration(List.of(software.amazon.awscdk.services.ec2.SubnetConfiguration
                        .builder()
                        .name("public")
                        .subnetType(SubnetType.PUBLIC)
                        .build()))
                .build();

        Bucket stagingBucket = Bucket.Builder.create(this, "StagingBucket")
                // Required, not optional: S3 Files relies on object versions for consistency
                // (confirmed against AWS's own CfnFileSystem CDK documentation example).
                .versioned(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();

        CfnFileSystem fileSystem = createS3FilesFileSystem(stagingBucket);
        SecurityGroup mountTargetSg = createMountTargets(vpc, fileSystem);

        SecurityGroup taskSecurityGroup = SecurityGroup.Builder.create(this, "TaskSecurityGroup")
                .vpc(vpc)
                .description("Fargate task ENIs for aws-ecs:build test runs")
                .allowAllOutbound(true)
                .build();
        // Mount target SG must accept inbound TCP 2049 from the task SG (confirmed as the exact,
        // specific port S3 Files mounting uses -- "Connection timeout is the #1 mount failure",
        // per AWS's own troubleshooting guidance -- not the transitEncryptionPort default (2999)
        // seen in an unrelated task-definition example, which is a different setting entirely).
        mountTargetSg.addIngressRule(Peer.securityGroupId(taskSecurityGroup.getSecurityGroupId()),
                Port.tcp(2049), "NFS (2049) from the Fargate task security group");

        Cluster cluster = Cluster.Builder.create(this, "Cluster")
                .vpc(vpc)
                .clusterName("jobrunr-build-test")
                .containerInsightsV2(software.amazon.awscdk.services.ecs.ContainerInsights.ENABLED)
                .build();

        Repository agentRepository = Repository.Builder.create(this, "AgentRepository")
                .repositoryName("jobrunr-build-agent")
                .removalPolicy(RemovalPolicy.DESTROY)
                .emptyOnDelete(true)
                .build();

        LogGroup logGroup = LogGroup.Builder.create(this, "AgentLogGroup")
                .logGroupName(LOG_GROUP_NAME)
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Role executionRole = Role.Builder.create(this, "ExecutionRole")
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName(
                        "service-role/AmazonECSTaskExecutionRolePolicy")))
                .build();

        Role taskRole = createTaskRole(stagingBucket);

        List<ISubnet> publicSubnets = vpc.getPublicSubnets();
        String subnetIds = publicSubnets.stream().map(ISubnet::getSubnetId)
                .collect(Collectors.joining(","));

        CfnOutput.Builder.create(this, "ClusterArn").value(cluster.getClusterArn()).build();
        CfnOutput.Builder.create(this, "SubnetIds").value(subnetIds).build();
        CfnOutput.Builder.create(this, "SecurityGroupId")
                .value(taskSecurityGroup.getSecurityGroupId()).build();
        CfnOutput.Builder.create(this, "ExecutionRoleArn").value(executionRole.getRoleArn()).build();
        CfnOutput.Builder.create(this, "TaskRoleArn").value(taskRole.getRoleArn()).build();
        CfnOutput.Builder.create(this, "S3FilesFileSystemArn")
                .value(fileSystem.getAttrFileSystemArn()).build();
        CfnOutput.Builder.create(this, "LogGroupName").value(logGroup.getLogGroupName()).build();
        CfnOutput.Builder.create(this, "S3Bucket").value(stagingBucket.getBucketName()).build();
        CfnOutput.Builder.create(this, "AgentRepositoryUri")
                .value(agentRepository.getRepositoryUri()).build();
        CfnOutput.Builder.create(this, "Region").value(Aws.REGION).build();
    }

    /**
     * S3 Files needs its own service role, separate from the ECS task role, to sync data between
     * the bucket and the file system. Trust policy and permissions copied directly from AWS's own
     * {@code CfnFileSystem} CDK documentation example, including the {@code
     * elasticfilesystem.amazonaws.com} service principal (not {@code s3files.amazonaws.com} --
     * kept exactly as documented, not "corrected" to what might look more consistent).
     */
    private CfnFileSystem createS3FilesFileSystem(Bucket stagingBucket) {
        Role s3FilesRole = Role.Builder.create(this, "S3FilesRole")
                .assumedBy(new ServicePrincipal("elasticfilesystem.amazonaws.com"))
                .build();
        s3FilesRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("s3:ListBucket*"))
                .resources(List.of(stagingBucket.getBucketArn()))
                .build());
        s3FilesRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("s3:AbortMultipartUpload", "s3:DeleteObject", "s3:GetObject*",
                        "s3:List*", "s3:PutObject*"))
                .resources(List.of(stagingBucket.arnForObjects("*")))
                .build());
        s3FilesRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("events:DeleteRule", "events:DisableRule", "events:EnableRule",
                        "events:PutRule", "events:PutTargets", "events:RemoveTargets"))
                .resources(List.of("arn:" + Aws.PARTITION + ":events:*:*:rule/DO-NOT-DELETE-S3-Files*"))
                .conditions(java.util.Map.of("StringEquals", java.util.Map.of(
                        "events:ManagedBy", "elasticfilesystem.amazonaws.com")))
                .build());
        s3FilesRole.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("events:DescribeRule", "events:ListRuleNamesByTarget",
                        "events:ListRules", "events:ListTargetsByRule"))
                .resources(List.of("arn:" + Aws.PARTITION + ":events:*:*:rule/*"))
                .build());

        return CfnFileSystem.Builder.create(this, "S3FilesFileSystem")
                .bucket(stagingBucket.getBucketArn())
                .roleArn(s3FilesRole.getRoleArn())
                .build();
    }

    /**
     * S3 Files needs an explicit mount target per Availability Zone — it is not automatically
     * reachable the moment the file system exists, unlike what {@code S3FilesVolumeConfiguration}'s
     * ECS-side shape (just a {@code fileSystemArn}) might suggest (confirmed against AWS's "Mount
     * target for S3 Files" documentation: "each Availability Zone supports only one mount
     * target").
     */
    private SecurityGroup createMountTargets(Vpc vpc, CfnFileSystem fileSystem) {        SecurityGroup mountTargetSg = SecurityGroup.Builder.create(this, "MountTargetSecurityGroup")
                .vpc(vpc)
                .description("S3 Files mount targets for aws-ecs:build test runs")
                .allowAllOutbound(true)
                .build();

        List<ISubnet> publicSubnets = vpc.getPublicSubnets();
        for (int i = 0; i < publicSubnets.size(); i++) {
            CfnMountTarget.Builder.create(this, "MountTarget" + i)
                    .fileSystemId(fileSystem.getAttrFileSystemId())
                    .subnetId(publicSubnets.get(i).getSubnetId())
                    .securityGroups(List.of(mountTargetSg.getSecurityGroupId()))
                    .build();
        }
        return mountTargetSg;
    }

    /**
     * The ECS task role's policy: the two-part S3 Files policy matching AWS's "Prerequisites for
     * S3 Files" documentation exactly (see the class javadoc for why the task role doesn't also
     * need direct S3 write access to the bucket <em>for the mount-based launch types</em>), plus a
     * separate {@code s3:PutObject} statement needed only when {@code aws-ecs.agentUsesDirectS3Io}
     * is enabled -- see the class javadoc's third bullet for exactly why this is a genuinely new
     * requirement, not already covered by the S3 Files policy above it.
     */
    private Role createTaskRole(Bucket stagingBucket) {
        Role taskRole = Role.Builder.create(this, "TaskRole")
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonS3FilesClientFullAccess")))
                .build();
        taskRole.addToPolicy(PolicyStatement.Builder.create()
                .sid("S3ObjectReadAccess")
                .effect(Effect.ALLOW)
                .actions(List.of("s3:GetObject", "s3:GetObjectVersion"))
                .resources(List.of(stagingBucket.arnForObjects("*")))
                .build());
        taskRole.addToPolicy(PolicyStatement.Builder.create()
                .sid("S3BucketListAccess")
                .effect(Effect.ALLOW)
                .actions(List.of("s3:ListBucket"))
                .resources(List.of(stagingBucket.getBucketArn()))
                .build());
        // Only needed for aws-ecs.agentUsesDirectS3Io=true: S3Io.uploadOutputDirectory() calls
        // PutObject directly from inside the container, under this role's credentials, rather than
        // writing through the S3 Files mount the way the mount-based modes do (where the *agent's*
        // writes go through the mount, and S3StagingSink/S3ArtifactRetriever's own S3 writes run
        // under the plugin's credentials, not this role's). Granted unconditionally rather than
        // gated behind a stack parameter: it's a narrow, object-scoped permission addition on a
        // disposable test bucket, and keeping both mount-based and direct-S3-calls testing possible
        // against the same deployed stack is more useful than minimizing this one extra statement.
        taskRole.addToPolicy(PolicyStatement.Builder.create()
                .sid("S3ObjectWriteAccessForDirectS3IoMode")
                .effect(Effect.ALLOW)
                .actions(List.of("s3:PutObject"))
                .resources(List.of(stagingBucket.arnForObjects("*")))
                .build());
        return taskRole;
    }
}
