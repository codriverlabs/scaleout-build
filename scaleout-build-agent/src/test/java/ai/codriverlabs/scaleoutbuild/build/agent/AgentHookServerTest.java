/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build.agent;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Exercises {@link AgentHookServer} as a real loopback HTTP server (posting to it exactly as the
 * Lambda MicroVM platform would), per {@code docs/design/testing/unit-test-strategy.md}'s "Pattern
 * C" — the same category of decision as {@code ControlPlaneClientTest}'s loopback server, just with
 * this test playing the platform's role instead of a client's.
 *
 * <p>Against a real S3 gateway (SeaweedFS, same substitution {@link AgentMainDirectS3IoTest}
 * already makes for {@code native-image}'s compiler) so the {@code status.json} round trip
 * (upload by {@link AgentHookServer}, read back here) exercises the real wire format rather than a
 * mocked {@code S3Client}.
 */
@Testcontainers
class AgentHookServerTest {

    @Container
    private static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>("chrislusf/seaweedfs:4.23")
            .withCommand("server", "-s3", "-s3.port=8333", "-dir=/data",
                    "-master.volumeSizeLimitMB=100", "-s3.allowEmptyFolder=true")
            .withExposedPorts(8333)
            .waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static final String BUCKET = "scaleout-build-agent-hook-test";
    private static final Architecture HOST = Architecture.host().orElse(Architecture.X86_64);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static S3Client s3Client;

    private AgentHookServer server;

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

    @AfterAll
    static void closeClient() {
        s3Client.close();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = new AgentHookServer(s3Client);
        server.start(0); // ephemeral port, so parallel test runs never collide on 9000
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void readyAlwaysReturns200Immediately() throws IOException {
        int status = post("/aws/lambda-microvms/runtime/v1/ready", new byte[0]);

        assertThat(status).isEqualTo(200);
    }

    @Test
    void runRespondsImmediatelyThenCompilesInTheBackground(@TempDir Path tempDir) throws Exception {
        String buildId = "hook-test-run-1";
        String stagingRelativePath = "builds/" + buildId + "/" + HOST.stagingDirName();
        s3Client.putObject(b -> b.bucket(BUCKET).key(stagingRelativePath + "/native-image.args"),
                RequestBody.fromString("-o\noutput/app\n"));

        Path fakeNativeImage = writeFakeNativeImage(tempDir, "built-ok", 0);
        byte[] payload = hookPayload(buildId, stagingRelativePath, tempDir, fakeNativeImage,
                "app");

        long before = System.nanoTime();
        int status = post("/aws/lambda-microvms/runtime/v1/run", payload);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - before).toMillis();

        // The actual proof the design doc calls for: /run returns well within its hook budget
        // (MicroVmImageManager.RUN_HOOK_TIMEOUT_SECONDS = 30s) rather than waiting for the compile,
        // which this fake deliberately cannot finish instantly (see writeFakeNativeImage's sleep).
        assertThat(status).isEqualTo(200);
        assertThat(elapsedMillis).isLessThan(2000);

        // The compile happens on a background thread; status.json appears once it's done.
        awaitStatusJson(stagingRelativePath);
        JsonNode report = readStatusJson(stagingRelativePath);
        assertThat(report.get("buildId").asText()).isEqualTo(buildId);
        assertThat(report.get("cell").asText()).isEqualTo("NATIVE/" + HOST.name());
        assertThat(report.get("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(report.get("exitCode").asInt()).isZero();
        assertThat(report.get("artifacts")).hasSize(1);
        assertThat(report.get("artifacts").get(0).get("path").asText()).isEqualTo("app");
        assertThat(report.get("artifacts").get(0).get("sizeBytes").asLong()).isGreaterThan(0);
    }

    @Test
    void runWritesAFailedStatusWhenTheCompileFails(@TempDir Path tempDir) throws Exception {
        String buildId = "hook-test-run-2";
        String stagingRelativePath = "builds/" + buildId + "/" + HOST.stagingDirName();
        s3Client.putObject(b -> b.bucket(BUCKET).key(stagingRelativePath + "/native-image.args"),
                RequestBody.fromString("-o\noutput/app\n"));

        Path fakeNativeImage = writeFakeNativeImage(tempDir, null, 1);
        byte[] payload = hookPayload(buildId, stagingRelativePath, tempDir, fakeNativeImage, "app");

        int status = post("/aws/lambda-microvms/runtime/v1/run", payload);
        assertThat(status).isEqualTo(200);

        awaitStatusJson(stagingRelativePath);
        JsonNode report = readStatusJson(stagingRelativePath);
        assertThat(report.get("state").asText()).isEqualTo("FAILED");
        assertThat(report.get("exitCode").asInt()).isEqualTo(1);
        assertThat(report.get("artifacts")).isEmpty();
    }

    @Test
    void runStillRespondsWithTwoHundred_whenEnvelopeIsMissingRunHookPayload() throws Exception {
        // The real failure mode found by an actual RunMicrovm call: the platform's /run body is
        // always the {microvmId, runHookPayload} envelope, so a request missing runHookPayload
        // (or any other malformed body) must not crash the hook response itself -- the agent still
        // owes the platform its 200 within the hook's budget, same as the happy path. There is
        // nothing to compile and nothing to report a status for, so this intentionally has no
        // status.json assertion -- only that the hook response itself did not fail.
        byte[] malformed = MAPPER.writeValueAsBytes(Map.of("microvmId", "microvm-test-id"));

        int status = post("/aws/lambda-microvms/runtime/v1/run", malformed);

        assertThat(status).isEqualTo(200);
    }

    private byte[] hookPayload(String buildId, String stagingRelativePath, Path tempDir,
                               Path fakeNativeImage, String expectedArtifact) throws Exception {
        // The real /run request body is the platform's own envelope, not the raw flat map --
        // confirmed against AWS's own documentation (Lambda MicroVMs' "Running and using
        // MicroVMs" page): {"microvmId": "...", "runHookPayload": "<string>"}, with
        // runHookPayload's *value* being this flat SCALEOUT_BUILD_* JSON string. An earlier
        // version of this test posted the flat map directly, which never actually exercised
        // AgentHookServer's envelope-unwrapping and would have passed even if that unwrapping
        // were missing entirely -- which it was, in production, until a real RunMicrovm call
        // caught it.
        String innerPayload = MAPPER.writeValueAsString(Map.of(
                AgentConfig.ENV_S3_BUCKET, BUCKET,
                AgentConfig.ENV_BUILD_ID, buildId,
                AgentConfig.ENV_BUILD_KIND, "native",
                AgentConfig.ENV_ARCH, HOST.name(),
                AgentConfig.ENV_STAGING_RELATIVE_PATH, stagingRelativePath,
                AgentConfig.ENV_EXPECTED_ARTIFACTS, expectedArtifact,
                AgentConfig.ENV_TEMP_DIR, tempDir.toString(),
                AgentConfig.ENV_NATIVE_IMAGE, fakeNativeImage.toString()));
        return MAPPER.writeValueAsBytes(Map.of(
                "microvmId", "microvm-test-id",
                "runHookPayload", innerPayload));
    }

    private void awaitStatusJson(String stagingRelativePath) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            boolean present = s3Client.listObjectsV2(b -> b.bucket(BUCKET)
                            .prefix(stagingRelativePath + "/status.json"))
                    .contents().stream().findAny().isPresent();
            if (present) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("status.json never appeared under " + stagingRelativePath
                + " within 10s");
    }

    private JsonNode readStatusJson(String stagingRelativePath) throws IOException {
        String json = s3Client.getObjectAsBytes(
                b -> b.bucket(BUCKET).key(stagingRelativePath + "/status.json")).asUtf8String();
        return MAPPER.readTree(json);
    }

    private int post(String path, byte[] body) throws IOException {
        URL url = URI.create("http://localhost:" + server.boundPort() + path).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.getOutputStream().write(body);
        int status = connection.getResponseCode();
        connection.disconnect();
        return status;
    }

    /** A fake {@code native-image} that sleeps briefly so /run's immediate-response guarantee is
     * actually exercised rather than coincidentally true because the fake finishes instantly. */
    private static Path writeFakeNativeImage(Path baseDir, String content, int exitCode)
            throws Exception {
        Path script = baseDir.resolve("fake-native-image.sh");
        String body = content == null
                ? """
                #!/bin/sh
                sleep 0.3
                exit %d
                """.formatted(exitCode)
                : """
                #!/bin/sh
                sleep 0.3
                mkdir -p output
                printf '%s' > output/app
                exit %d
                """.formatted(content, exitCode);
        Files.writeString(script, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return script;
    }
}
