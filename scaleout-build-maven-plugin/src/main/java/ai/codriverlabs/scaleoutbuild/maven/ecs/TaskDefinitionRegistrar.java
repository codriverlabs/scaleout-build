/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.ecs;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Compatibility;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.EphemeralStorage;
import software.amazon.awssdk.services.ecs.model.HostVolumeProperties;
import software.amazon.awssdk.services.ecs.model.LogConfiguration;
import software.amazon.awssdk.services.ecs.model.MountPoint;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionResponse;
import software.amazon.awssdk.services.ecs.model.RuntimePlatform;
import software.amazon.awssdk.services.ecs.model.S3FilesVolumeConfiguration;
import software.amazon.awssdk.services.ecs.model.Tag;
import software.amazon.awssdk.services.ecs.model.TaskDefinitionField;
import software.amazon.awssdk.services.ecs.model.Volume;

/**
 * Registers the ECS task definition for one (build kind, architecture) matrix cell, idempotently.
 *
 * <p>ECS has no "register only if changed" operation of its own — every call to
 * {@code RegisterTaskDefinition} creates a new revision, even when nothing changed. Repeated plugin
 * invocations would otherwise accumulate an unbounded number of near-identical revisions. This class
 * avoids that by tagging each registered task definition with a content hash of the fields that
 * matter, and skipping registration when the desired configuration already matches the family's
 * current revision.
 *
 * <p>The task definition carries no per-build environment variables — those (build kind,
 * architecture, staging paths, the {@code .iprof} path for {@code NATIVE_PGO_OPTIMIZE}) are supplied
 * per-cell as ECS task overrides by {@code BuildMojo}'s own {@code RunTask} call (via
 * {@link EcsTaskLauncher}, see {@code BuildMojo.buildTaskOverrideEnvironment}), not baked into the
 * definition itself. That is what lets one task definition per (build kind, architecture)
 * combination serve every build, rather than needing a new revision per invocation.
 */
public final class TaskDefinitionRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(TaskDefinitionRegistrar.class);

    /** Tag key holding the content hash used to detect an unchanged configuration. */
    static final String CONFIG_HASH_TAG_KEY = "scaleout:configHash";

    /**
     * Name of the single container in the generated task definition. Public because it is the
     * middle segment of the {@code awslogs} driver's stream name
     * ({@code <awslogs-stream-prefix>/<container-name>/<task-id>}), so anything that needs to
     * address one task's log stream exactly — {@link EcsTaskSupervisor} — has to compose it from
     * the same value used here. Previously duplicated as a private constant in
     * {@link EcsTaskLauncher} too; a single source avoids the two drifting apart and silently
     * breaking stream-name composition.
     */
    public static final String CONTAINER_NAME = "scaleout-build-agent";
    private static final String MOUNT_VOLUME_NAME = "scaleout-build-mount";
    /** Container path the staging mount is exposed at; must match {@code AgentConfig}'s default. */
    public static final String MOUNT_CONTAINER_PATH = "/mnt/build";
    /** Value for the container's {@code awslogs-stream-prefix}; referenced by {@code BuildMojo}. */
    public static final String LOG_STREAM_PREFIX = "scaleout-build";

    private final EcsClient ecsClient;

    public TaskDefinitionRegistrar(EcsClient ecsClient) {
        this.ecsClient = Objects.requireNonNull(ecsClient, "ecsClient");
    }

    /**
     * Registers (or reuses) the task definition for {@code buildKind}/{@code architecture}.
     *
     * @param architecture required when {@code buildKind.requiresArchitecture()}; ignored (may be
     *                     {@code null}) otherwise — {@link BuildKind#JVM} never reaches this method
     *                     in practice, since JVM cells never launch a remote task, but the family
     *                     naming still needs a consistent answer if it is called
     * @return the ARN of the task definition to run, either a pre-existing revision whose
     *         configuration already matches, or a freshly registered one
     */
    public String registerIfChanged(EcsClusterSettings clusterSettings,
                                    AgentContainerSettings containerSettings,
                                    BuildKind buildKind, Architecture architecture) {
        Objects.requireNonNull(clusterSettings, "clusterSettings");
        Objects.requireNonNull(containerSettings, "containerSettings");
        Objects.requireNonNull(buildKind, "buildKind");
        if (buildKind.requiresArchitecture()) {
            Objects.requireNonNull(architecture,
                    "architecture is required for build kind " + buildKind);
        }

        String family = taskDefinitionFamily(buildKind, architecture);
        String configHash = computeConfigHash(clusterSettings, containerSettings, buildKind,
                architecture);

        Optional<String> existingArnIfUnchanged = findUnchangedRevision(family, configHash);
        if (existingArnIfUnchanged.isPresent()) {
            LOG.info("Task definition for {}/{} is unchanged (family {}); reusing {}", buildKind,
                    architecture, family, existingArnIfUnchanged.get());
            return existingArnIfUnchanged.get();
        }

        RegisterTaskDefinitionRequest request = buildRequest(family, clusterSettings,
                containerSettings, buildKind, architecture, configHash);
        RegisterTaskDefinitionResponse response = ecsClient.registerTaskDefinition(request);
        String arn = response.taskDefinition().taskDefinitionArn();
        LOG.info("Registered new task definition revision for {}/{}: {}", buildKind, architecture,
                arn);
        return arn;
    }

    /**
     * @return the current task definition ARN for {@code family} if it exists and its
     *         {@value #CONFIG_HASH_TAG_KEY} tag matches {@code desiredConfigHash}; empty if the
     *         family does not exist yet, or its latest revision differs
     */
    private Optional<String> findUnchangedRevision(String family, String desiredConfigHash) {
        try {
            var response = ecsClient.describeTaskDefinition(b -> b.taskDefinition(family)
                    .include(TaskDefinitionField.TAGS));
            String existingHash = response.tags().stream()
                    .filter(tag -> CONFIG_HASH_TAG_KEY.equals(tag.key()))
                    .map(Tag::value)
                    .findFirst()
                    .orElse(null);
            if (desiredConfigHash.equals(existingHash)) {
                return Optional.of(response.taskDefinition().taskDefinitionArn());
            }
            return Optional.empty();
        } catch (software.amazon.awssdk.services.ecs.model.ClientException e) {
            // ECS raises ClientException (not a "not found" specific type) both when the family has
            // never been registered and for other client-side mistakes; a missing family is by far
            // the common case for a first-ever plugin invocation, so this is treated as "no existing
            // revision" rather than rethrown. A genuinely wrong family name would also land here and
            // then get treated as new, which registerTaskDefinition's own family field validates.
            return Optional.empty();
        }
    }

    private RegisterTaskDefinitionRequest buildRequest(String family,
                                                        EcsClusterSettings clusterSettings,
                                                        AgentContainerSettings containerSettings,
                                                        BuildKind buildKind, Architecture architecture,
                                                        String configHash) {
        ContainerDefinition.Builder containerDefinitionBuilder = ContainerDefinition.builder()
                .name(CONTAINER_NAME)
                .image(containerSettings.agentImageUri())
                .essential(true)
                .logConfiguration(LogConfiguration.builder()
                        .logDriver("awslogs")
                        .options(Map.of(
                                "awslogs-group", clusterSettings.logGroupName(),
                                "awslogs-region", clusterSettings.region(),
                                "awslogs-stream-prefix", LOG_STREAM_PREFIX))
                        .build());

        RegisterTaskDefinitionRequest.Builder requestBuilder = RegisterTaskDefinitionRequest.builder()
                .family(family)
                .networkMode(NetworkMode.AWSVPC)
                .requiresCompatibilities(requiresCompatibility(clusterSettings.launchType()))
                .cpu(containerSettings.cpu())
                .memory(containerSettings.memory())
                .executionRoleArn(clusterSettings.executionRoleArn())
                .taskRoleArn(clusterSettings.taskRoleArn())
                .tags(Tag.builder().key(CONFIG_HASH_TAG_KEY).value(configHash).build());

        if (clusterSettings.agentUsesDirectS3Io()) {
            // No mount at all -- the agent reads/writes S3 directly (see S3Io's class Javadoc), so
            // there is nothing to declare a task-level volume for or mount into the container.
            requestBuilder.containerDefinitions(containerDefinitionBuilder.build());
        } else {
            // Every mount-based launch type mounts the staging volume at the same container path
            // the agent expects (AgentConfig's default SCALEOUT_BUILD_MOUNT_ROOT) -- without this,
            // the `volumes` entry below declares the volume at the task level but never actually
            // mounts it into the container, leaving the agent writing to an ordinary, empty,
            // non-shared container-filesystem directory instead.
            containerDefinitionBuilder.mountPoints(MountPoint.builder()
                    .sourceVolume(MOUNT_VOLUME_NAME)
                    .containerPath(MOUNT_CONTAINER_PATH)
                    .build());
            requestBuilder.containerDefinitions(containerDefinitionBuilder.build())
                    .volumes(buildMountVolume(clusterSettings));
        }

        if (buildKind.requiresArchitecture()) {
            requestBuilder.runtimePlatform(RuntimePlatform.builder()
                    .cpuArchitecture(architecture.ecsCpuArchitecture())
                    .operatingSystemFamily("LINUX")
                    .build());
        }
        // ephemeralStorage is Fargate-only: it's not a valid parameter for MANAGED_INSTANCES tasks
        // (confirmed against AWS's task-definition-differences documentation) and has no meaning
        // for EC2 tasks, which use the container instance's own disk.
        if (clusterSettings.launchType() == EcsLaunchType.FARGATE
                && containerSettings.ephemeralStorageGiB() > 0) {
            requestBuilder.ephemeralStorage(
                    EphemeralStorage.builder().sizeInGiB(containerSettings.ephemeralStorageGiB()).build());
        }
        return requestBuilder.build();
    }

    private static Compatibility requiresCompatibility(EcsLaunchType launchType) {
        return switch (launchType) {
            case FARGATE -> Compatibility.FARGATE;
            case MANAGED_INSTANCES -> Compatibility.MANAGED_INSTANCES;
            case EC2 -> Compatibility.EC2;
        };
    }

    /**
     * {@code FARGATE}/{@code MANAGED_INSTANCES} use an S3 Files volume, mounted by ECS itself.
     * {@code EC2} instead bind-mounts a host path where Mountpoint for Amazon S3 has already been
     * mounted by the container instance's user-data — S3 Files is not supported on the raw EC2
     * launch type (confirmed against AWS's docs: a task configured with one fails at launch there).
     */
    private static Volume buildMountVolume(EcsClusterSettings clusterSettings) {
        if (clusterSettings.launchType().usesS3Files()) {
            S3FilesVolumeConfiguration.Builder s3FilesConfig = S3FilesVolumeConfiguration.builder()
                    .fileSystemArn(clusterSettings.s3FilesFileSystemArn());
            if (clusterSettings.s3FilesRootDirectory() != null) {
                s3FilesConfig.rootDirectory(clusterSettings.s3FilesRootDirectory());
            }
            if (clusterSettings.s3FilesAccessPointArn() != null) {
                s3FilesConfig.accessPointArn(clusterSettings.s3FilesAccessPointArn());
            }
            return Volume.builder()
                    .name(MOUNT_VOLUME_NAME)
                    .s3filesVolumeConfiguration(s3FilesConfig.build())
                    .build();
        }
        return Volume.builder()
                .name(MOUNT_VOLUME_NAME)
                .host(HostVolumeProperties.builder()
                        .sourcePath(clusterSettings.ec2HostMountPath())
                        .build())
                .build();
    }

    private String taskDefinitionFamily(BuildKind buildKind, Architecture architecture) {
        String suffix = buildKind.requiresArchitecture() ? architecture.schemaSuffix()
                : buildKind.configValue();
        return CONTAINER_NAME + "-" + buildKind.configValue() + "-" + suffix;
    }

    /**
     * Canonical content hash of every field that should trigger a new revision when it changes.
     * Deliberately excludes anything ECS assigns itself (revision, ARNs, registeredAt).
     */
    private String computeConfigHash(EcsClusterSettings clusterSettings,
                                     AgentContainerSettings containerSettings, BuildKind buildKind,
                                     Architecture architecture) {
        List<String> parts = new ArrayList<>();
        parts.add("image=" + containerSettings.agentImageUri());
        parts.add("cpu=" + containerSettings.cpu());
        parts.add("memory=" + containerSettings.memory());
        parts.add("ephemeralStorageGiB=" + containerSettings.ephemeralStorageGiB());
        parts.add("buildKind=" + buildKind);
        parts.add("cpuArchitecture="
                + (buildKind.requiresArchitecture() ? architecture.ecsCpuArchitecture() : "n/a"));
        parts.add("launchType=" + clusterSettings.launchType());
        parts.add("executionRoleArn=" + clusterSettings.executionRoleArn());
        parts.add("taskRoleArn=" + clusterSettings.taskRoleArn());
        parts.add("s3FilesFileSystemArn=" + clusterSettings.s3FilesFileSystemArn());
        parts.add("s3FilesRootDirectory=" + clusterSettings.s3FilesRootDirectory());
        parts.add("s3FilesAccessPointArn=" + clusterSettings.s3FilesAccessPointArn());
        parts.add("ec2HostMountPath=" + clusterSettings.ec2HostMountPath());
        parts.add("logGroupName=" + clusterSettings.logGroupName());
        parts.add("region=" + clusterSettings.region());

        String canonical = String.join("\n", parts);
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
