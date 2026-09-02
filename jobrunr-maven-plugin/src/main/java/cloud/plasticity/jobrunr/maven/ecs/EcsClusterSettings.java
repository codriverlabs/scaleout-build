/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.ecs;

import java.util.List;
import java.util.Objects;

/**
 * Pre-provisioned AWS resources the plugin registers task definitions and launches tasks against.
 *
 * <p>Per the design's ownership split, none of these are created by the plugin: the cluster,
 * VPC/subnets, security groups, S3 file system with its mount target, and IAM roles are all
 * provisioned out of band. The plugin only ever calls {@code RegisterTaskDefinition} and
 * {@code RunTask} against them.
 *
 * @param clusterArn           ECS cluster the tasks run in
 * @param subnetIds            subnets for the task's {@code awsvpc} network configuration
 * @param securityGroupIds     security groups for the task's {@code awsvpc} network configuration
 * @param assignPublicIp       whether the task gets a public IP; only needed if the subnets are
 *                             public and the task has no other path to reach DSQL/S3/CloudWatch
 * @param executionRoleArn     role ECS uses to pull the image and write logs
 * @param taskRoleArn          role the running container itself assumes (DSQL, S3 Files, etc.)
 * @param s3FilesFileSystemArn ARN of the pre-provisioned S3 Files file system, e.g.
 *                             {@code arn:aws:s3files:region:account:file-system/fs-xxxxx}
 * @param s3FilesRootDirectory optional root directory within the S3 Files file system, or
 *                             {@code null} to mount its root
 * @param s3FilesAccessPointArn optional S3 access point ARN scoping the mount, or {@code null}
 * @param logGroupName         CloudWatch Logs group the agent's stdout/stderr is sent to
 * @param region               AWS region everything above lives in
 */
public record EcsClusterSettings(
        String clusterArn,
        List<String> subnetIds,
        List<String> securityGroupIds,
        boolean assignPublicIp,
        String executionRoleArn,
        String taskRoleArn,
        String s3FilesFileSystemArn,
        String s3FilesRootDirectory,
        String s3FilesAccessPointArn,
        String logGroupName,
        String region) {

    public EcsClusterSettings(String clusterArn, List<String> subnetIds, List<String> securityGroupIds,
                              boolean assignPublicIp, String executionRoleArn, String taskRoleArn,
                              String s3FilesFileSystemArn, String s3FilesRootDirectory,
                              String s3FilesAccessPointArn, String logGroupName, String region) {
        this.clusterArn = requireNonBlank(clusterArn, "clusterArn");
        this.subnetIds = requireNonEmpty(subnetIds, "subnetIds");
        this.securityGroupIds = requireNonEmpty(securityGroupIds, "securityGroupIds");
        this.assignPublicIp = assignPublicIp;
        this.executionRoleArn = requireNonBlank(executionRoleArn, "executionRoleArn");
        this.taskRoleArn = requireNonBlank(taskRoleArn, "taskRoleArn");
        this.s3FilesFileSystemArn = requireNonBlank(s3FilesFileSystemArn, "s3FilesFileSystemArn");
        this.s3FilesRootDirectory = s3FilesRootDirectory;
        this.s3FilesAccessPointArn = s3FilesAccessPointArn;
        this.logGroupName = requireNonBlank(logGroupName, "logGroupName");
        this.region = requireNonBlank(region, "region");
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static List<String> requireNonEmpty(List<String> value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return List.copyOf(value);
    }
}
