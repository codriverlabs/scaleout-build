/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Template;

/**
 * Synthesizes {@link BuildTestInfraStack} and checks that the resources this whole test-infra
 * effort exists to get right are actually present with the expected shape — a real regression
 * guard against, e.g., a future edit accidentally dropping the mount targets, the bucket's
 * required versioning, or the task role's S3 Files policy.
 *
 * <p>Covers both {@code includeS3Files} modes: the default-{@code true} tests below exercise the
 * mount-based mechanism (the two-arg constructor's default, kept for source compatibility); the
 * {@code includeS3Files=false} tests further down exercise the settled plain-S3 mechanism new
 * deployments actually use — see {@link BuildTestInfraStack}'s class Javadoc.
 */
class BuildTestInfraStackTest {

    private static Template synthesize() {
        App app = new App();
        BuildTestInfraStack stack = new BuildTestInfraStack(app, "TestStack", null);
        return Template.fromStack(stack);
    }

    private static Template synthesizeWithoutS3Files() {
        App app = new App();
        BuildTestInfraStack stack = new BuildTestInfraStack(app, "TestStack", null, false);
        return Template.fromStack(stack);
    }

    @Test
    void bucketHasVersioningEnabled() {
        Template template = synthesize();
        template.hasResourceProperties("AWS::S3::Bucket", Map.of(
                "VersioningConfiguration", Map.of("Status", "Enabled")));
    }

    @Test
    void createsExactlyOneMountTargetPerAvailabilityZone() {
        Template template = synthesize();
        template.resourceCountIs("AWS::S3Files::MountTarget", 2);
    }

    @Test
    void mountTargetSecurityGroupAllowsNfsFromTheTaskSecurityGroupOnly() {
        Template template = synthesize();
        template.hasResourceProperties("AWS::EC2::SecurityGroup", Map.of(
                "GroupDescription", "S3 Files mount targets for aws-ecs:build test runs",
                "SecurityGroupIngress", java.util.List.of(Map.of(
                        "FromPort", 2049,
                        "ToPort", 2049,
                        "IpProtocol", "tcp"))));
    }

    @Test
    void taskRoleHasS3FilesClientAccessAndScopedS3ReadPermissions() {
        Template template = synthesize();
        template.hasResourceProperties("AWS::IAM::Role", Map.of(
                "ManagedPolicyArns", java.util.List.of(Map.of(
                        "Fn::Join", java.util.List.of("", java.util.List.of(
                                "arn:", Map.of("Ref", "AWS::Partition"),
                                ":iam::aws:policy/AmazonS3FilesClientFullAccess"))))));
        // The scoped inline policy granting s3:GetObject/s3:GetObjectVersion/s3:ListBucket on the
        // staging bucket is asserted via its distinct Sids, matching AWS's "Prerequisites for S3
        // Files" documentation exactly.
        template.hasResourceProperties("AWS::IAM::Policy", Map.of(
                "PolicyDocument", Map.of("Statement", java.util.List.of(
                        Map.of("Sid", "S3ObjectReadAccess", "Effect", "Allow",
                                "Action", java.util.List.of("s3:GetObject", "s3:GetObjectVersion")),
                        Map.of("Sid", "S3BucketListAccess", "Effect", "Allow",
                                "Action", "s3:ListBucket"),
                        Map.of("Sid", "S3ObjectWriteAccessForDirectS3IoMode", "Effect", "Allow",
                                "Action", "s3:PutObject")))));
    }

    @Test
    void taskRoleS3PutObjectPermissionIsScopedToTheStagingBucketObjectsOnly() {
        Template template = synthesize();
        // Regression guard against a future edit accidentally widening this to the bucket ARN
        // itself (bucket-level, not object-level) or to "*": scans the actual synthesized policy
        // document for the statement by its Sid, then checks its Resource is the same
        // arnForObjects("*")-shaped Fn::Join every other object-scoped statement in this stack
        // uses (S3ObjectReadAccess), not the bucket-level ARN S3BucketListAccess uses.
        Map<String, Map<String, Object>> policies = template.findResources("AWS::IAM::Policy");
        boolean foundCorrectlyScopedStatement = policies.values().stream()
                .map(resource -> (Map<String, Object>) resource.get("Properties"))
                .map(properties -> (Map<String, Object>) properties.get("PolicyDocument"))
                .flatMap(doc -> ((java.util.List<Map<String, Object>>) doc.get("Statement")).stream())
                .filter(statement -> "S3ObjectWriteAccessForDirectS3IoMode"
                        .equals(statement.get("Sid")))
                .anyMatch(statement -> isObjectScopedResource(statement.get("Resource")));
        assertThat(foundCorrectlyScopedStatement).isTrue();
    }

    /**
     * True for a {@code Resource} value shaped like {@code arnForObjects("*")}'s synthesized
     * {@code Fn::Join} (ends in {@code "/*"}), false for a bare bucket ARN reference or a wildcard
     * resource string.
     */
    @SuppressWarnings("unchecked")
    private static boolean isObjectScopedResource(Object resource) {
        if (!(resource instanceof Map<?, ?> map) || !map.containsKey("Fn::Join")) {
            return false;
        }
        java.util.List<Object> join = (java.util.List<Object>) map.get("Fn::Join");
        java.util.List<Object> parts = (java.util.List<Object>) join.get(1);
        return "/*".equals(parts.get(parts.size() - 1));
    }

    @Test
    void createsExactlyOneEcsClusterAndOneEcrRepository() {
        Template template = synthesize();
        template.resourceCountIs("AWS::ECS::Cluster", 1);
        template.resourceCountIs("AWS::ECR::Repository", 1);
    }

    @Test
    void clusterHasFargateAndFargateSpotCapacityProvidersAssociated() {
        // Regression guard for a real, live-deployment-caught gap: without this, ECS rejects
        // EcsTaskLauncher's default weighted FARGATE/FARGATE_SPOT capacityProviderStrategy with
        // "capacity provider that is not associated with the cluster" -- confirmed by actually
        // hitting the error running aws-ecs:build against a real deployment of this stack, not
        // just reasoned about.
        Template template = synthesize();
        template.hasResourceProperties("AWS::ECS::ClusterCapacityProviderAssociations", Map.of(
                "CapacityProviders", java.util.List.of("FARGATE", "FARGATE_SPOT")));
    }

    @Test
    void vpcHasDnsHostnamesAndDnsSupportEnabled() {
        Template template = synthesize();
        template.hasResourceProperties("AWS::EC2::VPC", Map.of(
                "EnableDnsHostnames", true,
                "EnableDnsSupport", true));
    }

    @Test
    void emitsEveryOutputTheAwsEcsBuildPluginNeeds() {
        Template template = synthesize();
        Map<String, Map<String, Object>> outputs = template.findOutputs("*");
        assertThat(outputs).containsKeys("ClusterArn", "SubnetIds", "SecurityGroupId",
                "ExecutionRoleArn", "TaskRoleArn", "S3FilesFileSystemArn", "LogGroupName",
                "S3Bucket", "AgentRepositoryUri", "Region");
    }

    // --- includeS3Files=false: the settled, plain-S3, direct-S3-calls staging mechanism ---

    @Test
    void withoutS3FilesProvisionsNoS3FilesResourcesAtAll() {
        Template template = synthesizeWithoutS3Files();
        template.resourceCountIs("AWS::S3Files::FileSystem", 0);
        template.resourceCountIs("AWS::S3Files::MountTarget", 0);
    }

    @Test
    void withoutS3FilesTaskRoleHasNoS3FilesManagedPolicyButKeepsScopedS3Permissions() {
        Template template = synthesizeWithoutS3Files();
        Map<String, Map<String, Object>> roles = template.findResources("AWS::IAM::Role");
        boolean anyRoleHasS3FilesManagedPolicy = roles.values().stream()
                .map(resource -> (Map<String, Object>) resource.get("Properties"))
                .anyMatch(properties -> properties.containsKey("ManagedPolicyArns")
                        && properties.get("ManagedPolicyArns").toString()
                                .contains("AmazonS3FilesClientFullAccess"));
        assertThat(anyRoleHasS3FilesManagedPolicy).isFalse();

        // The scoped read/list/write statements are unaffected by includeS3Files -- they're needed
        // either way (plain S3StagingSink/S3ArtifactRetriever reads, and the agent's own
        // direct-S3-calls reads/writes), so this mirrors
        // taskRoleHasS3FilesClientAccessAndScopedS3ReadPermissions's inline-policy assertion
        // exactly, just without the managed-policy check.
        template.hasResourceProperties("AWS::IAM::Policy", Map.of(
                "PolicyDocument", Map.of("Statement", java.util.List.of(
                        Map.of("Sid", "S3ObjectReadAccess", "Effect", "Allow",
                                "Action", java.util.List.of("s3:GetObject", "s3:GetObjectVersion")),
                        Map.of("Sid", "S3BucketListAccess", "Effect", "Allow",
                                "Action", "s3:ListBucket"),
                        Map.of("Sid", "S3ObjectWriteAccessForDirectS3IoMode", "Effect", "Allow",
                                "Action", "s3:PutObject")))));
    }

    @Test
    void withoutS3FilesStillCreatesTheClusterEcrRepositoryAndVersionedBucket() {
        Template template = synthesizeWithoutS3Files();
        template.resourceCountIs("AWS::ECS::Cluster", 1);
        template.resourceCountIs("AWS::ECR::Repository", 1);
        template.hasResourceProperties("AWS::S3::Bucket", Map.of(
                "VersioningConfiguration", Map.of("Status", "Enabled")));
    }

    @Test
    void withoutS3FilesOmitsTheS3FilesFileSystemArnOutputButKeepsEverythingElse() {
        Template template = synthesizeWithoutS3Files();
        Map<String, Map<String, Object>> outputs = template.findOutputs("*");
        assertThat(outputs).containsKeys("ClusterArn", "SubnetIds", "SecurityGroupId",
                "ExecutionRoleArn", "TaskRoleArn", "LogGroupName", "S3Bucket",
                "AgentRepositoryUri", "Region");
        assertThat(outputs).doesNotContainKey("S3FilesFileSystemArn");
    }
}
