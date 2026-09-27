/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import java.util.List;
import java.util.Objects;

/**
 * Pre-provisioned AWS resources the plugin registers task definitions and launches tasks against.
 *
 * <p>Per the design's ownership split, none of these are created by the plugin: the cluster,
 * VPC/subnets, security groups, staging mount (S3 Files file system, or the EC2 host path Mountpoint
 * for Amazon S3 is mounted at via user-data), any capacity provider, and IAM roles are all
 * provisioned out of band. The plugin only ever calls {@code RegisterTaskDefinition} and
 * {@code RunTask} against them.
 *
 * <p>Which fields are required depends on {@code launchType} — see {@link EcsLaunchType}'s javadoc
 * for why the three launch types split into two staging mechanisms:
 *
 * <ul>
 *   <li>{@code FARGATE}/{@code MANAGED_INSTANCES}: {@code s3FilesFileSystemArn} required;
 *       {@code ec2HostMountPath} must be {@code null}.
 *   <li>{@code EC2}: {@code ec2HostMountPath} required; {@code s3FilesFileSystemArn} (and the other
 *       {@code s3Files*} fields) must be {@code null}.
 *   <li>{@code MANAGED_INSTANCES}: {@code capacityProviderName} required (there is no AWS-managed
 *       default the way {@code FARGATE}/{@code FARGATE_SPOT} are for plain Fargate).
 *   <li>{@code EC2}: {@code capacityProviderName} optional — when {@code null}, tasks launch with
 *       {@code launchType: EC2} directly against unmanaged container instances already registered
 *       on the cluster, rather than through a capacity provider.
 * </ul>
 *
 * <p><b>{@code agentUsesDirectS3Io=true} overrides all of the mount-related requirements above,
 * for every launch type</b>: when the agent makes its own direct S3 calls instead of reading and
 * writing through a mount, no mount infrastructure exists to require — {@code
 * s3FilesFileSystemArn}/{@code s3FilesRootDirectory}/{@code s3FilesAccessPointArn}/{@code
 * ec2HostMountPath} are all simply unused and may be {@code null} regardless of {@code
 * launchType}. {@code capacityProviderName}'s requiredness is unaffected — that's an orthogonal,
 * launch-type-driven capacity concern, not a staging-mechanism one. See {@code
 * docs/PURE_ECS_ALTERNATIVE.md}'s "agent's own direct S3 calls" section for the full rationale.
 *
 * @param launchType           which ECS compute model the task runs on
 * @param clusterArn           ECS cluster the tasks run in
 * @param subnetIds            subnets for the task's {@code awsvpc} network configuration
 * @param securityGroupIds     security groups for the task's {@code awsvpc} network configuration
 * @param assignPublicIp       whether the task gets a public IP; only needed if the subnets are
 *                             public and the task has no other path to reach S3/CloudWatch
 * @param executionRoleArn     role ECS uses to pull the image and write logs
 * @param taskRoleArn          role the running container itself assumes (S3, S3 Files, etc.)
 * @param s3FilesFileSystemArn ARN of the pre-provisioned S3 Files file system, e.g.
 *                             {@code arn:aws:s3files:region:account:file-system/fs-xxxxx};
 *                             required for {@code FARGATE}/{@code MANAGED_INSTANCES} unless
 *                             {@code agentUsesDirectS3Io} is {@code true}, must be {@code null}
 *                             for {@code EC2}
 * @param s3FilesRootDirectory optional root directory within the S3 Files file system, or
 *                             {@code null} to mount its root; unused for {@code EC2} or when
 *                             {@code agentUsesDirectS3Io} is {@code true}
 * @param s3FilesAccessPointArn optional S3 access point ARN scoping the mount, or {@code null};
 *                             unused for {@code EC2} or when {@code agentUsesDirectS3Io} is
 *                             {@code true}
 * @param ec2HostMountPath     absolute path on the EC2 container instance where Mountpoint for
 *                             Amazon S3 has already been mounted by the instance's user-data;
 *                             required for {@code EC2} unless {@code agentUsesDirectS3Io} is
 *                             {@code true}, must be {@code null} otherwise
 * @param capacityProviderName name of the pre-provisioned capacity provider to target via
 *                             {@code capacityProviderStrategy}; required for
 *                             {@code MANAGED_INSTANCES}, optional for {@code EC2} ({@code null}
 *                             means launch with {@code launchType: EC2} directly instead), unused
 *                             for {@code FARGATE} (which always targets the AWS-managed
 *                             {@code FARGATE}/{@code FARGATE_SPOT} providers)
 * @param logGroupName         CloudWatch Logs group the agent's stdout/stderr is sent to
 * @param region               AWS region everything above lives in
 * @param agentUsesDirectS3Io  whether the agent makes its own S3 calls instead of reading/writing
 *                             through a mount — see the class Javadoc's override note above
 */
public record EcsClusterSettings(
        EcsLaunchType launchType,
        String clusterArn,
        List<String> subnetIds,
        List<String> securityGroupIds,
        boolean assignPublicIp,
        String executionRoleArn,
        String taskRoleArn,
        String s3FilesFileSystemArn,
        String s3FilesRootDirectory,
        String s3FilesAccessPointArn,
        String ec2HostMountPath,
        String capacityProviderName,
        String logGroupName,
        String region,
        boolean agentUsesDirectS3Io,
        FargateCapacityStrategy fargateCapacityStrategy) {

    /** Defaults the capacity strategy, for callers that do not care which Fargate capacity is used. */
    public EcsClusterSettings(EcsLaunchType launchType, String clusterArn, List<String> subnetIds,
                              List<String> securityGroupIds, boolean assignPublicIp,
                              String executionRoleArn, String taskRoleArn, String s3FilesFileSystemArn,
                              String s3FilesRootDirectory, String s3FilesAccessPointArn,
                              String ec2HostMountPath, String capacityProviderName, String logGroupName,
                              String region, boolean agentUsesDirectS3Io) {
        this(launchType, clusterArn, subnetIds, securityGroupIds, assignPublicIp, executionRoleArn,
                taskRoleArn, s3FilesFileSystemArn, s3FilesRootDirectory, s3FilesAccessPointArn,
                ec2HostMountPath, capacityProviderName, logGroupName, region, agentUsesDirectS3Io,
                FargateCapacityStrategy.SPOT_PREFERRED);
    }

    public EcsClusterSettings(EcsLaunchType launchType, String clusterArn, List<String> subnetIds,
                              List<String> securityGroupIds, boolean assignPublicIp,
                              String executionRoleArn, String taskRoleArn,
                              String s3FilesFileSystemArn, String s3FilesRootDirectory,
                              String s3FilesAccessPointArn, String ec2HostMountPath,
                              String capacityProviderName, String logGroupName, String region,
                              boolean agentUsesDirectS3Io,
        FargateCapacityStrategy fargateCapacityStrategy) {
        this.launchType = Objects.requireNonNull(launchType, "launchType");
        this.clusterArn = requireNonBlank(clusterArn, "clusterArn");
        this.subnetIds = requireNonEmpty(subnetIds, "subnetIds");
        this.securityGroupIds = requireNonEmpty(securityGroupIds, "securityGroupIds");
        this.assignPublicIp = assignPublicIp;
        this.executionRoleArn = requireNonBlank(executionRoleArn, "executionRoleArn");
        this.taskRoleArn = requireNonBlank(taskRoleArn, "taskRoleArn");
        this.logGroupName = requireNonBlank(logGroupName, "logGroupName");
        this.region = requireNonBlank(region, "region");
        this.agentUsesDirectS3Io = agentUsesDirectS3Io;

        if (agentUsesDirectS3Io) {
            // No mount infrastructure needed at all, on any launch type -- see the class Javadoc's
            // override note. Still enforce the null-elsewhere direction (whichever fields the
            // *other* branch would have nulled out) so a caller can't accidentally combine
            // agentUsesDirectS3Io=true with, e.g., an EC2 host mount path that would then be
            // silently ignored rather than rejected.
            requireNull(s3FilesFileSystemArn, "s3FilesFileSystemArn", launchType);
            requireNull(s3FilesRootDirectory, "s3FilesRootDirectory", launchType);
            requireNull(s3FilesAccessPointArn, "s3FilesAccessPointArn", launchType);
            requireNull(ec2HostMountPath, "ec2HostMountPath", launchType);
            this.s3FilesFileSystemArn = null;
            this.s3FilesRootDirectory = null;
            this.s3FilesAccessPointArn = null;
            this.ec2HostMountPath = null;
        } else if (launchType.usesS3Files()) {
            this.s3FilesFileSystemArn = requireNonBlank(s3FilesFileSystemArn, "s3FilesFileSystemArn");
            this.s3FilesRootDirectory = s3FilesRootDirectory;
            this.s3FilesAccessPointArn = s3FilesAccessPointArn;
            requireNull(ec2HostMountPath, "ec2HostMountPath", launchType);
            this.ec2HostMountPath = null;
        } else {
            requireNull(s3FilesFileSystemArn, "s3FilesFileSystemArn", launchType);
            requireNull(s3FilesRootDirectory, "s3FilesRootDirectory", launchType);
            requireNull(s3FilesAccessPointArn, "s3FilesAccessPointArn", launchType);
            this.s3FilesFileSystemArn = null;
            this.s3FilesRootDirectory = null;
            this.s3FilesAccessPointArn = null;
            this.ec2HostMountPath = requireNonBlank(ec2HostMountPath, "ec2HostMountPath");
        }

        // Null-tolerant: only FARGATE consults it, and a null there means "the default preset".
        this.fargateCapacityStrategy = fargateCapacityStrategy == null
                ? FargateCapacityStrategy.SPOT_PREFERRED : fargateCapacityStrategy;

        if (launchType == EcsLaunchType.MANAGED_INSTANCES) {
            this.capacityProviderName = requireNonBlank(capacityProviderName, "capacityProviderName");
        } else if (launchType == EcsLaunchType.EC2) {
            this.capacityProviderName = capacityProviderName; // optional
        } else {
            requireNull(capacityProviderName, "capacityProviderName", launchType);
            this.capacityProviderName = null;
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireNull(String value, String name, EcsLaunchType launchType) {
        if (value != null) {
            throw new IllegalArgumentException(name + " must not be set for launch type " + launchType);
        }
    }

    private static List<String> requireNonEmpty(List<String> value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return List.copyOf(value);
    }
}
