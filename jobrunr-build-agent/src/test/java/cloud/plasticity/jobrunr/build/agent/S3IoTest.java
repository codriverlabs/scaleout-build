/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Exercises {@link S3Io} against SeaweedFS's S3 gateway — the same real, independently implemented
 * S3-protocol server {@code S3StagingIntegrationTest} uses on the plugin side, rather than an
 * AWS-emulating test double, for the same reason: this class's correctness (recursive download
 * preserving relative structure, recursive upload, escaping-path rejection) should hold against
 * standard S3 API behaviour, not one emulator's interpretation of it.
 */
@Testcontainers
class S3IoTest {

    @Container
    private static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>("chrislusf/seaweedfs:4.23")
            .withCommand("server", "-s3", "-s3.port=8333", "-dir=/data",
                    "-master.volumeSizeLimitMB=100", "-s3.allowEmptyFolder=true")
            .withExposedPorts(8333)
            .waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static final String BUCKET = "jobrunr-build-agent-test";

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
    void downloadsEveryObjectUnderAPrefixPreservingRelativeStructure(@TempDir Path localDir)
            throws IOException {
        String prefix = "builds/io-test-1/native/x86_64";
        putObject(prefix + "/native-image.args", "-o\noutput/app\n");
        putObject(prefix + "/lib/dep.jar", "fake-dep-bytes");
        putObject(prefix + "/app.jar", "fake-app-bytes");

        S3Io s3Io = new S3Io(s3Client, BUCKET);
        int downloaded = s3Io.downloadStagingDirectory(prefix, localDir);

        assertThat(downloaded).isEqualTo(3);
        assertThat(localDir.resolve("native-image.args")).hasContent("-o\noutput/app\n");
        assertThat(localDir.resolve("lib/dep.jar")).hasContent("fake-dep-bytes");
        assertThat(localDir.resolve("app.jar")).hasContent("fake-app-bytes");
    }

    @Test
    void failsWhenThePrefixHasNoObjects(@TempDir Path localDir) {
        S3Io s3Io = new S3Io(s3Client, BUCKET);
        assertThatThrownBy(() -> s3Io.downloadStagingDirectory("builds/never-staged/native/x86_64",
                localDir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No staged inputs found");
    }

    @Test
    void uploadsEveryRegularFileUnderALocalDirectoryPreservingRelativeStructure(
            @TempDir Path localOutputDir) throws IOException {
        Files.writeString(localOutputDir.resolve("app"), "fake-binary-bytes");
        Files.createDirectories(localOutputDir.resolve("nested"));
        Files.writeString(localOutputDir.resolve("nested/extra"), "fake-nested-bytes");

        S3Io s3Io = new S3Io(s3Client, BUCKET);
        String keyPrefix = "builds/io-test-2/native/x86_64/output";
        int uploaded = s3Io.uploadOutputDirectory(localOutputDir, keyPrefix);

        assertThat(uploaded).isEqualTo(2);
        assertThat(getObjectAsString(keyPrefix + "/app")).isEqualTo("fake-binary-bytes");
        assertThat(getObjectAsString(keyPrefix + "/nested/extra")).isEqualTo("fake-nested-bytes");
    }

    @Test
    void roundTripsADownloadThenUploadThroughTheSamePrefixConvention(@TempDir Path localDir)
            throws IOException {
        String stagingPrefix = "builds/io-test-3/native/x86_64";
        putObject(stagingPrefix + "/native-image.args", "-o\noutput/app\n");

        S3Io s3Io = new S3Io(s3Client, BUCKET);
        Path localStagingDir = localDir.resolve(stagingPrefix);
        s3Io.downloadStagingDirectory(stagingPrefix, localStagingDir);

        Path localOutputDir = localStagingDir.resolve("output");
        Files.createDirectories(localOutputDir);
        Files.writeString(localOutputDir.resolve("app"), "fake-produced-binary");

        String outputPrefix = stagingPrefix + "/output";
        s3Io.uploadOutputDirectory(localOutputDir, outputPrefix);

        assertThat(getObjectAsString(outputPrefix + "/app")).isEqualTo("fake-produced-binary");
    }

    private static void putObject(String key, String content) {
        s3Client.putObject(b -> b.bucket(BUCKET).key(key),
                software.amazon.awssdk.core.sync.RequestBody.fromString(content));
    }

    private static String getObjectAsString(String key) {
        return s3Client.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)).asUtf8String();
    }
}
