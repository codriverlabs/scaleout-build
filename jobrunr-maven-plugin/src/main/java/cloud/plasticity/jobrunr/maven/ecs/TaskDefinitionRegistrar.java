/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.ecs;

import cloud.plasticity.jobrunr.build.Architecture;
import cloud.plasticity.jobrunr.build.BuildKind;
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
import software.amazon.awssdk.services.ecs.model.LogConfiguration;
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
 * per-cell as ECS task overrides by the Step Functions state machine's {@code RunTask.sync} state
 * (see {@code docs/DESIGN.md} §5), not baked into the definition itself. That is what lets one task
 * definition per (build kind, architecture) combination serve every build, rather than needing a new
 * revision per invocation.
 */
public final class TaskDefinitionRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(TaskDefinitionRegistrar.class);

    /** Tag key holding the content hash used to detect an unchanged configuration. */
    static final String CONFIG_HASH_TAG_KEY = "jobrunr:configHash";

    private static final String CONTAINER_NAME = "jobrunr-build-agent";
    private static final String S3_FILES_VOLUME_NAME = "jobrunr-build-mount";
    private static final String LOG_STREAM_PREFIX = "jobrunr-build";

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
        ContainerDefinition containerDefinition = ContainerDefinition.builder()
                .name(CONTAINER_NAME)
                .image(containerSettings.agentImageUri())
                .essential(true)
                .logConfiguration(LogConfiguration.builder()
                        .logDriver("awslogs")
                        .options(Map.of(
                                "awslogs-group", clusterSettings.logGroupName(),
                                "awslogs-region", clusterSettings.region(),
                                "awslogs-stream-prefix", LOG_STREAM_PREFIX))
                        .build())
                .build();

        S3FilesVolumeConfiguration.Builder s3FilesConfig = S3FilesVolumeConfiguration.builder()
                .fileSystemArn(clusterSettings.s3FilesFileSystemArn());
        if (clusterSettings.s3FilesRootDirectory() != null) {
            s3FilesConfig.rootDirectory(clusterSettings.s3FilesRootDirectory());
        }
        if (clusterSettings.s3FilesAccessPointArn() != null) {
            s3FilesConfig.accessPointArn(clusterSettings.s3FilesAccessPointArn());
        }
        Volume s3FilesVolume = Volume.builder()
                .name(S3_FILES_VOLUME_NAME)
                .s3filesVolumeConfiguration(s3FilesConfig.build())
                .build();

        RegisterTaskDefinitionRequest.Builder requestBuilder = RegisterTaskDefinitionRequest.builder()
                .family(family)
                .networkMode(NetworkMode.AWSVPC)
                .requiresCompatibilities(Compatibility.FARGATE)
                .cpu(containerSettings.cpu())
                .memory(containerSettings.memory())
                .executionRoleArn(clusterSettings.executionRoleArn())
                .taskRoleArn(clusterSettings.taskRoleArn())
                .containerDefinitions(containerDefinition)
                .volumes(s3FilesVolume)
                .tags(Tag.builder().key(CONFIG_HASH_TAG_KEY).value(configHash).build());

        if (buildKind.requiresArchitecture()) {
            requestBuilder.runtimePlatform(RuntimePlatform.builder()
                    .cpuArchitecture(architecture.ecsCpuArchitecture())
                    .operatingSystemFamily("LINUX")
                    .build());
        }
        if (containerSettings.ephemeralStorageGiB() > 0) {
            requestBuilder.ephemeralStorage(
                    EphemeralStorage.builder().sizeInGiB(containerSettings.ephemeralStorageGiB()).build());
        }
        return requestBuilder.build();
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
        parts.add("executionRoleArn=" + clusterSettings.executionRoleArn());
        parts.add("taskRoleArn=" + clusterSettings.taskRoleArn());
        parts.add("s3FilesFileSystemArn=" + clusterSettings.s3FilesFileSystemArn());
        parts.add("s3FilesRootDirectory=" + clusterSettings.s3FilesRootDirectory());
        parts.add("s3FilesAccessPointArn=" + clusterSettings.s3FilesAccessPointArn());
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
