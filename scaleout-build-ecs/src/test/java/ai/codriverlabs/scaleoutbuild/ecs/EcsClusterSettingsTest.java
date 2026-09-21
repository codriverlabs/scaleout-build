/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link EcsClusterSettings}'s per-launch-type validation directly — previously only
 * exercised indirectly through other tests' fixture construction, never asserted on its own.
 *
 * <p>The {@code agentUsesDirectS3Io} cases specifically guard the fix that made this record's
 * mount-related fields optional across every launch type when the agent makes its own S3 calls
 * instead of reading/writing through a mount — see the class Javadoc's override note.
 */
class EcsClusterSettingsTest {

    private static final String CLUSTER_ARN = "arn:aws:ecs:us-east-1:123456789012:cluster/scaleout-build";
    private static final List<String> SUBNET_IDS = List.of("subnet-1");
    private static final List<String> SECURITY_GROUP_IDS = List.of("sg-1");
    private static final String EXECUTION_ROLE_ARN = "arn:aws:iam::123456789012:role/exec";
    private static final String TASK_ROLE_ARN = "arn:aws:iam::123456789012:role/task";
    private static final String LOG_GROUP_NAME = "/scaleout-build/build-agent";
    private static final String REGION = "us-east-1";
    private static final String S3_FILES_ARN =
            "arn:aws:s3files:us-east-1:123456789012:file-system/fs-abc123";

    @Test
    void fargateWithoutDirectS3IoRequiresAnS3FilesFileSystemArn() {
        assertThatThrownBy(() -> settings(EcsLaunchType.FARGATE, null, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("s3FilesFileSystemArn");
    }

    @Test
    void fargateWithDirectS3IoNeedsNoMountFieldsAtAll() {
        EcsClusterSettings settings = settings(EcsLaunchType.FARGATE, null, null, true);
        assertThat(settings.s3FilesFileSystemArn()).isNull();
        assertThat(settings.ec2HostMountPath()).isNull();
        assertThat(settings.agentUsesDirectS3Io()).isTrue();
    }

    @Test
    void ec2WithoutDirectS3IoRequiresAHostMountPath() {
        assertThatThrownBy(() -> settings(EcsLaunchType.EC2, null, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ec2HostMountPath");
    }

    @Test
    void ec2WithDirectS3IoNeedsNoMountFieldsAtAll() {
        EcsClusterSettings settings = settings(EcsLaunchType.EC2, null, null, true);
        assertThat(settings.s3FilesFileSystemArn()).isNull();
        assertThat(settings.ec2HostMountPath()).isNull();
    }

    @Test
    void directS3IoRejectsAnS3FilesFileSystemArnEvenThoughItWouldOtherwiseBeIgnored() {
        // A caller combining agentUsesDirectS3Io=true with a leftover s3FilesFileSystemArn from a
        // previous mount-based configuration should be told the value is meaningless now, rather
        // than have it silently ignored -- catches stale/half-migrated configuration.
        assertThatThrownBy(() -> settings(EcsLaunchType.FARGATE, S3_FILES_ARN, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("s3FilesFileSystemArn");
    }

    @Test
    void directS3IoRejectsAnEc2HostMountPathEvenThoughItWouldOtherwiseBeIgnored() {
        assertThatThrownBy(() -> settings(EcsLaunchType.EC2, null, "/mnt/build", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ec2HostMountPath");
    }

    @Test
    void managedInstancesStillRequiresACapacityProviderRegardlessOfDirectS3Io() {
        // capacityProviderName's requiredness is an orthogonal, launch-type-driven capacity
        // concern, not a staging-mechanism one -- agentUsesDirectS3Io must not relax it.
        assertThatThrownBy(() -> new EcsClusterSettings(EcsLaunchType.MANAGED_INSTANCES,
                CLUSTER_ARN, SUBNET_IDS, SECURITY_GROUP_IDS, false, EXECUTION_ROLE_ARN,
                TASK_ROLE_ARN, null, null, null, null, null, LOG_GROUP_NAME, REGION, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacityProviderName");
    }

    private static EcsClusterSettings settings(EcsLaunchType launchType, String s3FilesFileSystemArn,
                                               String ec2HostMountPath, boolean agentUsesDirectS3Io) {
        String capacityProviderName = launchType == EcsLaunchType.MANAGED_INSTANCES
                ? "managed-instances-cp" : null;
        return new EcsClusterSettings(launchType, CLUSTER_ARN, SUBNET_IDS, SECURITY_GROUP_IDS,
                false, EXECUTION_ROLE_ARN, TASK_ROLE_ARN, s3FilesFileSystemArn, null, null,
                ec2HostMountPath, capacityProviderName, LOG_GROUP_NAME, REGION,
                agentUsesDirectS3Io);
    }
}
