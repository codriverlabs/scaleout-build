/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cloud.plasticity.jobrunr.build.Architecture;
import cloud.plasticity.jobrunr.build.BuildJobRequest;
import cloud.plasticity.jobrunr.build.storage.StorageProviderFactory;
import cloud.plasticity.jobrunr.build.storage.StorageSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.utils.mapper.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link AgentMain#run} — the claim/build/exit lifecycle — without a container or a real
 * database, using in-memory storage in place of DSQL. This is the same code path
 * {@link AgentMain#main} drives after building a real DSQL-backed provider; only the storage
 * backing differs, per the design note that the plugin's local mode and the container agent share
 * one worker code path.
 */
class AgentMainTest {

    @Test
    void exitsSuccessfullyAfterProcessingOneJob(@TempDir Path mountRoot) throws Exception {
        Architecture architecture = Architecture.host().orElse(Architecture.X86_64);
        Path fakeNativeImage = writeFakeNativeImage(mountRoot);
        AgentConfig config = AgentConfig.fromMap(Map.of(
                AgentConfig.ENV_DSQL_ENDPOINT, "unused.dsql.us-east-1.on.aws",
                AgentConfig.ENV_DSQL_REGION, "us-east-1",
                AgentConfig.ENV_MOUNT_ROOT, mountRoot.toString(),
                AgentConfig.ENV_ARCH, architecture.name(),
                AgentConfig.ENV_NATIVE_IMAGE, fakeNativeImage.toString(),
                AgentConfig.ENV_IDLE_TIMEOUT_SECONDS, "10",
                AgentConfig.ENV_MAX_DURATION_MINUTES, "1"));

        JsonMapper jsonMapper = StorageProviderFactory.jsonMapper();
        StorageProvider storageProvider =
                StorageProviderFactory.create(StorageSettings.inMemory(), jsonMapper);

        String stagingRelativePath = "builds/agent-test-1/" + architecture.stagingDirName();
        Path stagingRoot = mountRoot.resolve(stagingRelativePath);
        Files.createDirectories(stagingRoot);
        Files.writeString(stagingRoot.resolve("native-image.args"), "-o\noutput/app\n");

        new JobRequestScheduler(storageProvider).enqueue(BuildJobRequest.builder()
                .buildId("agent-test-1")
                .architecture(architecture)
                .stagingRelativePath(stagingRelativePath)
                .expectedArtifacts(java.util.List.of("app"))
                .build());

        int exitCode = AgentMain.run(config, storageProvider, jsonMapper);

        assertThat(exitCode).isEqualTo(AgentMain.EXIT_SUCCESS);
        assertThat(stagingRoot.resolve("output/app")).exists();
    }

    @Test
    void exitsWithNoJobProcessedWhenNothingIsEnqueuedBeforeIdleTimeout(@TempDir Path mountRoot) {
        Architecture architecture = Architecture.host().orElse(Architecture.X86_64);
        AgentConfig config = AgentConfig.fromMap(Map.of(
                AgentConfig.ENV_DSQL_ENDPOINT, "unused.dsql.us-east-1.on.aws",
                AgentConfig.ENV_DSQL_REGION, "us-east-1",
                AgentConfig.ENV_MOUNT_ROOT, mountRoot.toString(),
                AgentConfig.ENV_ARCH, architecture.name(),
                AgentConfig.ENV_IDLE_TIMEOUT_SECONDS, "1",
                AgentConfig.ENV_MAX_DURATION_MINUTES, "1"));

        JsonMapper jsonMapper = StorageProviderFactory.jsonMapper();
        StorageProvider storageProvider =
                StorageProviderFactory.create(StorageSettings.inMemory(), jsonMapper);

        int exitCode = AgentMain.run(config, storageProvider, jsonMapper);

        assertThat(exitCode).isEqualTo(AgentMain.EXIT_NO_JOB_PROCESSED);
    }

    /** Writes a fake {@code native-image} shell script that writes the binary named by {@code -o}. */
    private static Path writeFakeNativeImage(Path baseDir) throws Exception {
        Path script = baseDir.resolve("fake-native-image.sh");
        Files.writeString(script, """
                #!/bin/sh
                set -e
                argfile="${1#@}"
                outname=$(awk '/^-o$/{getline; print; exit}' "$argfile")
                mkdir -p "$(dirname "$outname")"
                printf 'binary' > "$outname"
                """);
        Files.setPosixFilePermissions(script, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        return script;
    }
}
