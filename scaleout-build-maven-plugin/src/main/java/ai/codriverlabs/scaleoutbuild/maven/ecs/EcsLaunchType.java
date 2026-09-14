/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.ecs;

/**
 * Which ECS compute model a matrix cell's task runs on. Named after ECS's own
 * {@code requiresCompatibilities}/{@code Compatibility} values ({@code FARGATE}, {@code
 * MANAGED_INSTANCES}, {@code EC2}) rather than a plugin-invented vocabulary, so the mapping between
 * this enum and what actually shows up in the registered task definition is direct.
 *
 * <p>The three launch types split into two staging mechanisms, not three, because of one concrete
 * constraint confirmed against AWS's docs: S3 Files volumes are supported on {@link #FARGATE} and
 * {@link #MANAGED_INSTANCES}, but explicitly unsupported on the raw {@link #EC2} launch type — "If
 * you configure an S3 file system in a task definition and attempt to run it on the Amazon EC2
 * launch type, the task will fail at launch." So:
 *
 * <ul>
 *   <li>{@link #FARGATE} and {@link #MANAGED_INSTANCES} both use an S3 Files volume
 *       ({@code EcsClusterSettings#s3FilesFileSystemArn}), mounted by ECS itself.
 *   <li>{@link #EC2} instead uses a {@code host} bind-mount volume
 *       ({@code EcsClusterSettings#ec2HostMountPath}) pointing at a path where Mountpoint for
 *       Amazon S3 has already been mounted by the container instance's user-data — see
 *       {@code docs/PURE_ECS_ALTERNATIVE.md} §0 for why this mount is deliberately set up at the
 *       host level rather than inside the agent's container (no {@code SYS_ADMIN}/{@code
 *       /dev/fuse} needed in the task definition at all).
 * </ul>
 *
 * <p>Amazon ECS Managed Instances also differs from both plain Fargate and plain EC2 in how
 * capacity is requested: it is always addressed through a named {@code capacityProviderStrategy}
 * entry (the capacity provider itself, including its IAM infrastructure role and EC2 instance
 * profile, is provisioned out of band — this plugin only ever references it by name, the same
 * "pre-provisioned input" boundary already established for the cluster, VPC, and IAM roles). The
 * raw {@link #EC2} launch type can either reference a capacity provider the same way, or fall back
 * to {@code launchType: EC2} directly against unmanaged container instances already registered on
 * the cluster with no capacity provider at all.
 */
public enum EcsLaunchType {
    FARGATE,
    MANAGED_INSTANCES,
    EC2;

    /** Whether this launch type stages via an S3 Files volume rather than a host bind mount. */
    public boolean usesS3Files() {
        return this == FARGATE || this == MANAGED_INSTANCES;
    }
}
