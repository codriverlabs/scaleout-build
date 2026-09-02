/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.ecs;

import cloud.plasticity.jobrunr.build.Architecture;
import cloud.plasticity.jobrunr.build.storage.DsqlConnectionSettings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Compatibility;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.EphemeralStorage;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
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
 * Registers the ECS task definition for one architecture's build agent, idempotently.
 *
 * <p>ECS has no "register only if changed" operation of its own — every call to
 * {@code RegisterTaskDefinition} creates a new revision, even when nothing changed. Repeated plugin
 * invocations would otherwise accumulate an unbounded number of near-identical revisions. This class
 * avoids that by tagging each registered task definition with a content hash of the fields that
 * matter, and skipping registration when the desired configuration already matches the family's
 * current revision.
 *
 * <p>The hash is computed from the plugin's own input settings, not from the AWS SDK request object,
 * specifically so it is stable across SDK versions and unaffected by fields ECS fills in itself
 * (revision number, registration timestamp, ARNs).
 */
public final class TaskDefinitionRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(TaskDefinitionRegistrar.class);

    /** Tag key holding the content hash used to detect an unchanged configuration. */
    static final String CONFIG_HASH_TAG_KEY = "jobrunr:configHash";

    private static final String CONTAINER_NAME = "jobrunr-build-agent";
    private static final String S3_FILES_VOLUME_NAME = "jobrunr-build-mount";
    private static final String MOUNT_PATH = "/mnt/build";
    private static final String LOG_STREAM_PREFIX = "jobrunr-build";

    private final EcsClient ecsClient;

    public TaskDefinitionRegistrar(EcsClient ecsClient) {
        this.ecsClient = Objects.requireNonNull(ecsClient, "ecsClient");
    }

    /**
     * Registers (or reuses) the task definition for {@code architecture}.
     *
     * @return the ARN of the task definition to run, either a pre-existing revision whose
     *         configuration already matches, or a freshly registered one
     */
    public String registerIfChanged(EcsClusterSettings clusterSettings,
                                    AgentContainerSettings containerSettings,
                                    DsqlConnectionSettings dsqlConnectionSettings,
                                    String schemaPrefix, Architecture architecture) {
        Objects.requireNonNull(clusterSettings, "clusterSettings");
        Objects.requireNonNull(containerSettings, "containerSettings");
        Objects.requireNonNull(dsqlConnectionSettings, "dsqlConnectionSettings");
        Objects.requireNonNull(schemaPrefix, "schemaPrefix");
        Objects.requireNonNull(architecture, "architecture");

        String family = taskDefinitionFamily(architecture);
        Map<String, String> environment = buildEnvironment(clusterSettings, dsqlConnectionSettings,
                schemaPrefix, architecture);
        String configHash = computeConfigHash(clusterSettings, containerSettings, architecture,
                environment);

        Optional<String> existingArnIfUnchanged = findUnchangedRevision(family, configHash);
        if (existingArnIfUnchanged.isPresent()) {
            LOG.info("Task definition for {} is unchanged (family {}); reusing {}", architecture,
                    family, existingArnIfUnchanged.get());
            return existingArnIfUnchanged.get();
        }

        RegisterTaskDefinitionRequest request = buildRequest(family, clusterSettings,
                containerSettings, architecture, environment, configHash);
        RegisterTaskDefinitionResponse response = ecsClient.registerTaskDefinition(request);
        String arn = response.taskDefinition().taskDefinitionArn();
        LOG.info("Registered new task definition revision for {}: {}", architecture, arn);
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
                                                        Architecture architecture,
                                                        Map<String, String> environment,
                                                        String configHash) {
        List<KeyValuePair> environmentPairs = environment.entrySet().stream()
                .map(entry -> KeyValuePair.builder().name(entry.getKey()).value(entry.getValue()).build())
                .toList();

        ContainerDefinition containerDefinition = ContainerDefinition.builder()
                .name(CONTAINER_NAME)
                .image(containerSettings.agentImageUri())
                .essential(true)
                .environment(environmentPairs)
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
                .runtimePlatform(RuntimePlatform.builder()
                        .cpuArchitecture(architecture.ecsCpuArchitecture())
                        .operatingSystemFamily("LINUX")
                        .build())
                .tags(Tag.builder().key(CONFIG_HASH_TAG_KEY).value(configHash).build());

        if (containerSettings.ephemeralStorageGiB() > 0) {
            requestBuilder.ephemeralStorage(
                    EphemeralStorage.builder().sizeInGiB(containerSettings.ephemeralStorageGiB()).build());
        }
        return requestBuilder.build();
    }

    /**
     * Environment variables matching the names {@code AgentConfig} reads, so the container started
     * from this task definition needs no further configuration beyond what ECS injects.
     */
    private Map<String, String> buildEnvironment(EcsClusterSettings clusterSettings,
                                                  DsqlConnectionSettings dsqlConnectionSettings,
                                                  String schemaPrefix, Architecture architecture) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JOBRUNR_BUILD_MOUNT_ROOT", MOUNT_PATH);
        environment.put("JOBRUNR_BUILD_ARCH", architecture.name());
        environment.put("JOBRUNR_BUILD_DSQL_ENDPOINT", dsqlConnectionSettings.clusterEndpoint());
        environment.put("JOBRUNR_BUILD_DSQL_REGION", dsqlConnectionSettings.region());
        environment.put("JOBRUNR_BUILD_DSQL_USER", dsqlConnectionSettings.databaseUser());
        environment.put("JOBRUNR_BUILD_SCHEMA_PREFIX", schemaPrefix);
        return environment;
    }

    private String taskDefinitionFamily(Architecture architecture) {
        return CONTAINER_NAME + "-" + architecture.schemaSuffix();
    }

    /**
     * Canonical content hash of every field that should trigger a new revision when it changes.
     * Deliberately excludes anything ECS assigns itself (revision, ARNs, registeredAt).
     */
    private String computeConfigHash(EcsClusterSettings clusterSettings,
                                     AgentContainerSettings containerSettings,
                                     Architecture architecture, Map<String, String> environment) {
        // TreeMap for the environment gives a deterministic key order regardless of insertion order.
        Map<String, String> sortedEnvironment = new TreeMap<>(environment);
        List<String> parts = new ArrayList<>();
        parts.add("image=" + containerSettings.agentImageUri());
        parts.add("cpu=" + containerSettings.cpu());
        parts.add("memory=" + containerSettings.memory());
        parts.add("ephemeralStorageGiB=" + containerSettings.ephemeralStorageGiB());
        parts.add("cpuArchitecture=" + architecture.ecsCpuArchitecture());
        parts.add("executionRoleArn=" + clusterSettings.executionRoleArn());
        parts.add("taskRoleArn=" + clusterSettings.taskRoleArn());
        parts.add("s3FilesFileSystemArn=" + clusterSettings.s3FilesFileSystemArn());
        parts.add("s3FilesRootDirectory=" + clusterSettings.s3FilesRootDirectory());
        parts.add("s3FilesAccessPointArn=" + clusterSettings.s3FilesAccessPointArn());
        parts.add("logGroupName=" + clusterSettings.logGroupName());
        parts.add("region=" + clusterSettings.region());
        sortedEnvironment.forEach((key, value) -> parts.add("env." + key + "=" + value));

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
