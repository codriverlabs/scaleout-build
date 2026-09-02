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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Exercises {@link S3StagingSink} and {@link S3ArtifactRetriever} against a real S3 API surface.
 *
 * <p>Pinned to the {@code localstack/localstack:4.13} Community image explicitly: newer
 * {@code localstack/localstack} tags (from March 2026 onward) require a {@code
 * LOCALSTACK_AUTH_TOKEN} to even start, which this project does not have and should not need for
 * unit-level S3 API testing.
 */
@Testcontainers
class S3StagingIntegrationTest {

    private static final DockerImageName LOCALSTACK_IMAGE =
            DockerImageName.parse("localstack/localstack:4.13").asCompatibleSubstituteFor("localstack/localstack");

    @Container
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(LOCALSTACK_IMAGE).withServices(LocalStackContainer.Service.S3);

    private static final String BUCKET = "jobrunr-build-test";

    private static S3Client s3Client;

    @BeforeAll
    static void createClientAndBucket() {
        s3Client = S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .region(Region.of(LOCALSTACK.getRegion()))
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
                plan.generatedArgsContent().orElseThrow().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(firstBuildTransferred)
                .isEqualTo(Files.size(artifact) + Files.size(dependency) + expectedArgsBytes);

        assertObjectExists("builds/build-1/x86_64/app.jar");
        assertObjectExists("builds/build-1/x86_64/lib/dep.jar");
        assertObjectExists("builds/build-1/x86_64/native-image.args");

        // Second build reuses the same source files: only the (unique) argfile should be uploaded;
        // the classpath jars are already in the content-addressed store and only need a
        // server-side copy.
        long secondBuildTransferred = sink.stage(plan, "builds/build-2/x86_64");
        assertThat(secondBuildTransferred).isEqualTo(expectedArgsBytes);
        assertObjectExists("builds/build-2/x86_64/app.jar");
        assertObjectExists("builds/build-2/x86_64/lib/dep.jar");
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
        String key = "builds/build-3/x86_64/output/my-app";
        s3Client.putObject(b -> b.bucket(BUCKET).key(key),
                software.amazon.awssdk.core.sync.RequestBody.fromString("binary-content"));

        S3ArtifactRetriever retriever = new S3ArtifactRetriever(s3Client, BUCKET);
        List<Path> downloaded =
                assertDoesNotThrow(() -> retriever.retrieve("build-3", Architecture.X86_64,
                        List.of("my-app"), destinationDir));

        assertThat(downloaded).hasSize(1);
        assertThat(downloaded.get(0)).exists().hasContent("binary-content");
    }

    @Test
    void retrievesByScanningWhenNoExpectedNamesAreGiven(@TempDir Path destinationDir) {
        String key = "builds/build-4/arm64/output/self-named-binary";
        s3Client.putObject(b -> b.bucket(BUCKET).key(key),
                software.amazon.awssdk.core.sync.RequestBody.fromString("arm64-binary"));

        S3ArtifactRetriever retriever = new S3ArtifactRetriever(s3Client, BUCKET);
        List<Path> downloaded = assertDoesNotThrow(
                () -> retriever.retrieve("build-4", Architecture.ARM64, List.of(), destinationDir));

        assertThat(downloaded).hasSize(1);
        assertThat(downloaded.get(0).getFileName().toString()).isEqualTo("self-named-binary");
        assertThat(downloaded.get(0)).hasContent("arm64-binary");
    }

    private static void assertObjectExists(String key) {
        assertThat(s3Client.headObject(b -> b.bucket(BUCKET).key(key)).contentLength()).isNotNegative();
    }

    private static <T> T assertDoesNotThrow(java.util.concurrent.Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw new AssertionError("Expected no exception, but got: " + e, e);
        }
    }
}
