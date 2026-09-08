/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cloud.plasticity.jobrunr.build.Architecture;
import cloud.plasticity.jobrunr.build.BuildLog;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Exercises {@link AgentMain#runWithDirectS3Io} — the full download-to-local-directory, build,
 * upload-produced-artifacts loop — against SeaweedFS's real S3 gateway, with a fake
 * {@code native-image} script standing in for the real compiler (same substitution
 * {@code AgentMainTest} makes for the mount-mode path).
 *
 * <p>This is the direct-S3-calls I/O mode's end-to-end proof: everything {@link S3IoTest} checks in
 * isolation (download preserves structure, upload preserves structure) has to compose correctly with
 * {@link cloud.plasticity.jobrunr.build.NativeImageBuildExecutor}'s own working-directory and
 * output-directory conventions for a real build to succeed and its artifact to actually reach S3.
 */
@Testcontainers
class AgentMainDirectS3IoTest {

    @Container
    private static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>("chrislusf/seaweedfs:4.23")
            .withCommand("server", "-s3", "-s3.port=8333", "-dir=/data",
                    "-master.volumeSizeLimitMB=100", "-s3.allowEmptyFolder=true")
            .withExposedPorts(8333)
            .waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static final String BUCKET = "jobrunr-build-agent-e2e-test";
    private static final Architecture HOST = Architecture.host().orElse(Architecture.X86_64);

    private static S3Client s3Client;

    @BeforeAll
    static void createClientAndBucket() {
        String endpoint = "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333);
        s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @Test
    void downloadsBuildsThenUploadsProducedArtifactsBackToS3(@TempDir Path tempDir) throws Exception {
        String stagingRelativePath = "builds/direct-s3-test-1/" + HOST.stagingDirName();
        s3Client.putObject(b -> b.bucket(BUCKET).key(stagingRelativePath + "/native-image.args"),
                RequestBody.fromString("-o\noutput/app\n"));
        s3Client.putObject(b -> b.bucket(BUCKET).key(stagingRelativePath + "/lib/dep.jar"),
                RequestBody.fromString("fake-dep-bytes"));

        Path fakeNativeImage = writeFakeNativeImageThatReadsItsLibDir(tempDir);

        AgentConfig config = AgentConfig.fromMap(Map.of(
                AgentConfig.ENV_S3_BUCKET, BUCKET,
                AgentConfig.ENV_BUILD_ID, "direct-s3-test-1",
                AgentConfig.ENV_BUILD_KIND, "native",
                AgentConfig.ENV_ARCH, HOST.name(),
                AgentConfig.ENV_STAGING_RELATIVE_PATH, stagingRelativePath,
                AgentConfig.ENV_EXPECTED_ARTIFACTS, "app",
                AgentConfig.ENV_TEMP_DIR, tempDir.toString(),
                AgentConfig.ENV_NATIVE_IMAGE, fakeNativeImage.toString()));

        assertThat(config.usesDirectS3Io()).isTrue();

        int exitCode = AgentMain.runWithDirectS3Io(config, BuildLog.discarding(), s3Client);

        assertThat(exitCode).isEqualTo(AgentMain.EXIT_SUCCESS);
        String uploadedArtifact = s3Client.getObjectAsBytes(
                b -> b.bucket(BUCKET).key(stagingRelativePath + "/output/app")).asUtf8String();
        assertThat(uploadedArtifact).isEqualTo("built-using-fake-dep-bytes");

        // The local temp staging directory is cleaned up after the run -- verified directly rather
        // than assumed, since a leaked directory per build would accumulate on ephemeral storage
        // across every task this container ever ran (even though the agent is single-shot in
        // production, nothing stops a test or a future change from calling this twice).
        Path localStagingRoot = tempDir.resolve("s3-staging").resolve("direct-s3-test-1");
        assertThat(localStagingRoot).doesNotExist();
    }

    @Test
    void doesNotUploadAnythingWhenTheBuildFails(@TempDir Path tempDir) throws Exception {
        String stagingRelativePath = "builds/direct-s3-test-2/" + HOST.stagingDirName();
        s3Client.putObject(b -> b.bucket(BUCKET).key(stagingRelativePath + "/native-image.args"),
                RequestBody.fromString("-o\noutput/app\n"));

        Path fakeNativeImage = writeFailingFakeNativeImage(tempDir);

        AgentConfig config = AgentConfig.fromMap(Map.of(
                AgentConfig.ENV_S3_BUCKET, BUCKET,
                AgentConfig.ENV_BUILD_ID, "direct-s3-test-2",
                AgentConfig.ENV_BUILD_KIND, "native",
                AgentConfig.ENV_ARCH, HOST.name(),
                AgentConfig.ENV_STAGING_RELATIVE_PATH, stagingRelativePath,
                AgentConfig.ENV_TEMP_DIR, tempDir.toString(),
                AgentConfig.ENV_NATIVE_IMAGE, fakeNativeImage.toString()));

        int exitCode = AgentMain.runWithDirectS3Io(config, BuildLog.discarding(), s3Client);

        assertThat(exitCode).isEqualTo(AgentMain.EXIT_BUILD_FAILED);
        boolean outputUploaded = s3Client.listObjectsV2(b -> b.bucket(BUCKET)
                        .prefix(stagingRelativePath + "/output/"))
                .contents().stream().findAny().isPresent();
        assertThat(outputUploaded).isFalse();
    }

    /** Proves the downloaded lib/dep.jar is actually reachable from the build's working directory. */
    private static Path writeFakeNativeImageThatReadsItsLibDir(Path baseDir) throws Exception {
        Path script = baseDir.resolve("fake-native-image-reads-lib.sh");
        Files.writeString(script, """
                #!/bin/sh
                set -e
                mkdir -p output
                printf 'built-using-' > output/app
                cat lib/dep.jar >> output/app
                exit 0
                """);
        setExecutable(script);
        return script;
    }

    private static Path writeFailingFakeNativeImage(Path baseDir) throws Exception {
        Path script = baseDir.resolve("fake-native-image-fails.sh");
        Files.writeString(script, """
                #!/bin/sh
                exit 1
                """);
        setExecutable(script);
        return script;
    }

    private static void setExecutable(Path script) throws Exception {
        Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }
}
