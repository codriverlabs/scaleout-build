/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cloud.plasticity.jobrunr.build.Architecture;
import cloud.plasticity.jobrunr.build.BuildLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link AgentMain#run} — the single-shot read-config/build/exit lifecycle — against a
 * fake {@code native-image} script, without a container.
 */
class AgentMainTest {

    private static final Architecture HOST = Architecture.host().orElse(Architecture.X86_64);

    @Test
    void exitsSuccessfullyAfterBuilding(@TempDir Path mountRoot) throws Exception {
        Path fakeNativeImage = writeFakeNativeImage(mountRoot, 0);
        String stagingRelativePath = "builds/agent-test-1/" + HOST.stagingDirName();
        Path stagingRoot = mountRoot.resolve(stagingRelativePath);
        Files.createDirectories(stagingRoot);
        Files.writeString(stagingRoot.resolve("native-image.args"), "-o\noutput/app\n");

        AgentConfig config = AgentConfig.fromMap(Map.of(
                AgentConfig.ENV_MOUNT_ROOT, mountRoot.toString(),
                AgentConfig.ENV_BUILD_ID, "agent-test-1",
                AgentConfig.ENV_BUILD_KIND, "native",
                AgentConfig.ENV_ARCH, HOST.name(),
                AgentConfig.ENV_STAGING_RELATIVE_PATH, stagingRelativePath,
                AgentConfig.ENV_EXPECTED_ARTIFACTS, "app",
                AgentConfig.ENV_NATIVE_IMAGE, fakeNativeImage.toString()));

        int exitCode = AgentMain.run(config, BuildLog.discarding());

        assertThat(exitCode).isEqualTo(AgentMain.EXIT_SUCCESS);
        assertThat(stagingRoot.resolve("output/app")).exists();
    }

    @Test
    void exitsWithFailureWhenNativeImageFails(@TempDir Path mountRoot) throws Exception {
        Path fakeNativeImage = writeFakeNativeImage(mountRoot, 1);
        String stagingRelativePath = "builds/agent-test-2/" + HOST.stagingDirName();
        Path stagingRoot = mountRoot.resolve(stagingRelativePath);
        Files.createDirectories(stagingRoot);
        Files.writeString(stagingRoot.resolve("native-image.args"), "-o\noutput/app\n");

        AgentConfig config = AgentConfig.fromMap(Map.of(
                AgentConfig.ENV_MOUNT_ROOT, mountRoot.toString(),
                AgentConfig.ENV_BUILD_ID, "agent-test-2",
                AgentConfig.ENV_BUILD_KIND, "native",
                AgentConfig.ENV_ARCH, HOST.name(),
                AgentConfig.ENV_STAGING_RELATIVE_PATH, stagingRelativePath,
                AgentConfig.ENV_NATIVE_IMAGE, fakeNativeImage.toString()));

        int exitCode = AgentMain.run(config, BuildLog.discarding());

        assertThat(exitCode).isEqualTo(AgentMain.EXIT_BUILD_FAILED);
    }

    @Test
    void passesThePgoInstrumentFlagThrough(@TempDir Path mountRoot) throws Exception {
        Path fakeNativeImage = writeCapturingFakeNativeImage(mountRoot);
        String stagingRelativePath = "builds/agent-test-3/" + HOST.stagingDirName();
        Path stagingRoot = mountRoot.resolve(stagingRelativePath);
        Files.createDirectories(stagingRoot);
        Files.writeString(stagingRoot.resolve("native-image.args"), "-o\noutput/app\n");

        AgentConfig config = AgentConfig.fromMap(Map.of(
                AgentConfig.ENV_MOUNT_ROOT, mountRoot.toString(),
                AgentConfig.ENV_BUILD_ID, "agent-test-3",
                AgentConfig.ENV_BUILD_KIND, "native-pgo-instrument",
                AgentConfig.ENV_ARCH, HOST.name(),
                AgentConfig.ENV_STAGING_RELATIVE_PATH, stagingRelativePath,
                AgentConfig.ENV_NATIVE_IMAGE, fakeNativeImage.toString()));

        int exitCode = AgentMain.run(config, BuildLog.discarding());

        assertThat(exitCode).isEqualTo(AgentMain.EXIT_SUCCESS);
        assertThat(Files.readString(stagingRoot.resolve("captured-args.txt")))
                .contains("--pgo-instrument");
    }

    private static Path writeFakeNativeImage(Path baseDir, int exitCode) throws Exception {
        Path script = baseDir.resolve("fake-native-image.sh");
        Files.writeString(script, """
                #!/bin/sh
                set -e
                if [ %d -eq 0 ]; then
                  mkdir -p output
                  printf 'binary' > output/app
                fi
                exit %d
                """.formatted(exitCode, exitCode));
        setExecutable(script);
        return script;
    }

    private static Path writeCapturingFakeNativeImage(Path baseDir) throws Exception {
        Path script = baseDir.resolve("fake-native-image-capture.sh");
        Files.writeString(script, """
                #!/bin/sh
                echo "$@" > captured-args.txt
                mkdir -p output
                printf 'binary' > output/app
                exit 0
                """);
        setExecutable(script);
        return script;
    }

    private static void setExecutable(Path script) throws Exception {
        Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }
}
