/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.staging;

import static org.assertj.core.api.Assertions.assertThat;

import cloud.plasticity.jobrunr.build.Architecture;
import cloud.plasticity.jobrunr.build.StagingLayout;
import cloud.plasticity.jobrunr.maven.planner.NativeImageInputPlan;
import cloud.plasticity.jobrunr.maven.planner.StagedFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
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
 * Exercises {@link S3StagingSink} and {@link S3ArtifactRetriever} against SeaweedFS's S3 gateway,
 * a real third-party implementation of the S3 API rather than an AWS-emulating test double.
 *
 * <p>This matters specifically because {@link S3StagingSink}'s content-hash dedup depends on
 * server-side {@code CopyObject} — the very thing that makes the design's "first build ships the
 * classpath, every later build ships kilobytes" claim true. Exercising that against independently
 * implemented S3-protocol server code, rather than a same-vendor emulator, is a stronger check that
 * the sink's use of {@code CopyObject} is portable, standard S3 behaviour and not an artifact of one
 * emulator's interpretation of the API.
 *
 * <p>SeaweedFS's S3 gateway runs fully unauthenticated ("Allow All" mode) whenever no AWS
 * credentials are configured on the server side, which is what the plain {@code server -s3} command
 * below does — no {@code LOCALSTACK_AUTH_TOKEN}-style gate to work around.
 */
@Testcontainers
class S3StagingIntegrationTest {

    @Container
    private static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>("chrislusf/seaweedfs:4.23")
            .withCommand("server", "-s3", "-s3.port=8333", "-dir=/data",
                    "-master.volumeSizeLimitMB=100", "-s3.allowEmptyFolder=true")
            .withExposedPorts(8333)
            .waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static final String BUCKET = "jobrunr-build-test";

    private static S3Client s3Client;

    @BeforeAll
    static void createClientAndBucket() {
        String endpoint = "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333);
        s3Client = S3Client.builder()
                .endpointOverride(java.net.URI.create(endpoint))
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @Test
    void stagesFilesThroughTheContentAddressedStoreAndDeduplicatesOnASecondBuild(
            @TempDir Path sourceDir) throws IOException {
        Path artifact = sourceDir.resolve("app.jar");
        Files.writeString(artifact, "fake-jar-bytes-shared-across-builds");
        Path dependency = sourceDir.resolve("dep.jar");
        Files.writeString(dependency, "fake-dep-bytes-shared-across-builds");

        NativeImageInputPlan plan = NativeImageInputPlan.generated(
                List.of(new StagedFile("app.jar", artifact), new StagedFile("lib/dep.jar", dependency)),
                "-cp\napp.jar:lib/dep.jar\n-o\noutput/app\ndemo.Main\n", List.of("app"));

        S3StagingSink sink = new S3StagingSink(s3Client, BUCKET);

        long firstBuildTransferred = sink.stage(plan, "builds/build-1/x86_64");
        long expectedArgsBytes =
                plan.generatedArgsContent().orElseThrow().getBytes(StandardCharsets.UTF_8).length;
        assertThat(firstBuildTransferred)
                .isEqualTo(Files.size(artifact) + Files.size(dependency) + expectedArgsBytes);

        assertObjectExists("builds/build-1/x86_64/app.jar");
        assertObjectExists("builds/build-1/x86_64/lib/dep.jar");
        assertObjectExists("builds/build-1/x86_64/native-image.args");

        // Second build reuses the same source files: only the (unique) argfile should be uploaded;
        // the classpath jars are already in the content-addressed store and only need a real,
        // server-side CopyObject -- proven against SeaweedFS's own CopyObject implementation, not
        // an emulator's.
        long secondBuildTransferred = sink.stage(plan, "builds/build-2/x86_64");
        assertThat(secondBuildTransferred).isEqualTo(expectedArgsBytes);
        assertObjectExists("builds/build-2/x86_64/app.jar");
        assertObjectExists("builds/build-2/x86_64/lib/dep.jar");

        // The copy must be a real, independent object -- not a reference -- so the content is
        // actually readable from the second build's own key.
        String copiedContent = s3Client.getObjectAsBytes(
                b -> b.bucket(BUCKET).key("builds/build-2/x86_64/app.jar")).asUtf8String();
        assertThat(copiedContent).isEqualTo("fake-jar-bytes-shared-across-builds");
    }

    @Test
    void uploadsTheAgentJarOnlyOnce(@TempDir Path dir) throws IOException {
        Path agentJar = dir.resolve("agent.jar");
        Files.writeString(agentJar, "fake-agent-jar-bytes");

        S3StagingSink sink = new S3StagingSink(s3Client, BUCKET);

        boolean firstUpload = sink.ensureAgentJarUploaded(agentJar, "1.0.0-test");
        boolean secondUpload = sink.ensureAgentJarUploaded(agentJar, "1.0.0-test");

        assertThat(firstUpload).isTrue();
        assertThat(secondUpload).isFalse();
        assertObjectExists(StagingLayout.defaults().agentJarKey("1.0.0-test"));
    }

    @Test
    void retrievesAnExpectedArtifactByName(@TempDir Path destinationDir) {
        String key = "builds/build-3/native/x86_64/output/my-app";
        s3Client.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromString("binary-content"));

        S3ArtifactRetriever retriever = new S3ArtifactRetriever(s3Client, BUCKET);
        List<Path> downloaded = assertDoesNotThrow(() -> retriever.retrieve("build-3",
                cloud.plasticity.jobrunr.build.BuildKind.NATIVE, Architecture.X86_64,
                List.of("my-app"), destinationDir));

        assertThat(downloaded).hasSize(1);
        assertThat(downloaded.get(0)).exists().hasContent("binary-content");
    }

    @Test
    void retrievesByScanningWhenNoExpectedNamesAreGiven(@TempDir Path destinationDir) {
        String key = "builds/build-4/native/arm64/output/self-named-binary";
        s3Client.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromString("arm64-binary"));

        S3ArtifactRetriever retriever = new S3ArtifactRetriever(s3Client, BUCKET);
        List<Path> downloaded = assertDoesNotThrow(() -> retriever.retrieve("build-4",
                cloud.plasticity.jobrunr.build.BuildKind.NATIVE, Architecture.ARM64, List.of(),
                destinationDir));

        assertThat(downloaded).hasSize(1);
        assertThat(downloaded.get(0).getFileName().toString()).isEqualTo("self-named-binary");
        assertThat(downloaded.get(0)).hasContent("arm64-binary");
    }

    private static void assertObjectExists(String key) {
        assertThat(s3Client.headObject(b -> b.bucket(BUCKET).key(key)).contentLength()).isNotNegative();
    }

    private static <T> T assertDoesNotThrow(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw new AssertionError("Expected no exception, but got: " + e, e);
        }
    }
}
