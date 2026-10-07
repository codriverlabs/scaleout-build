/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildSpec;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildRequest;
import ai.codriverlabs.scaleoutbuild.controlplane.api.InputDescriptor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * Exercises {@link ControlPlaneClient} against a real loopback {@link HttpServer} instead of
 * mocking the class itself, per {@code docs/design/testing/unit-test-strategy.md}'s "Pattern C".
 *
 * <p>Covers the union of what the two predecessor clients' tests covered: build lifecycle
 * (create/start/status/cancel/heartbeat/list, previously only tested in the plugin), log streaming
 * and presigned transfer (previously only tested, if at all, in the plugin), and admin/MicroVM
 * endpoints (previously only tested in the CLI).
 */
class ControlPlaneClientTest {

    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("AKIAEXAMPLE", "secretExampleKeyMaterial"));

    private HttpServer server;
    private ControlPlaneClient client;
    private final List<CapturedRequest> captured = new CopyOnWriteArrayList<>();
    private volatile int nextStatus = 200;
    private volatile String nextBody = "{}";

    private record CapturedRequest(String method, String path, String query, String body,
                                   String authorizationHeader) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        SigV4Signer signer = new SigV4Signer(CREDENTIALS, SigV4Signer.SERVICE_LAMBDA);
        client = new ControlPlaneClient(
                "http://localhost:" + server.getAddress().getPort(), signer,
                software.amazon.awssdk.regions.Region.EU_WEST_1);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (var in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        captured.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(),
                body,
                exchange.getRequestHeaders().getFirst("Authorization")));

        byte[] responseBytes = nextBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(nextStatus, responseBytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(responseBytes);
        }
    }

    private static CreateBuildRequest sampleCreateRequest() {
        BuildSpec spec = new BuildSpec(List.of("native"), List.of("x86_64"), null, "app", null,
                List.of(), List.of(), 0, 0);
        return new CreateBuildRequest(spec,
                List.of(new InputDescriptor("app.jar", "sha256abc", 1024L)), null, null);
    }

    // ── createBuild / startBuild / status / heartbeat / cancel ──────────────────────────────────

    @Test
    void createBuild_postsToBuilds_andSigns() throws ControlPlaneClientException {
        nextStatus = 201;
        nextBody = "{\"buildId\":\"b1\",\"state\":\"PENDING\",\"cells\":[],\"uploads\":[]}";

        client.createBuild(sampleCreateRequest());

        CapturedRequest req = captured.get(0);
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/builds");
        assertThat(req.body()).contains("\"buildKinds\":[\"native\"]");
        assertThat(req.authorizationHeader()).startsWith("AWS4-HMAC-SHA256");
    }

    @Test
    void startBuild_postsToStartSubPath() throws ControlPlaneClientException {
        nextStatus = 202;
        nextBody = "{\"buildId\":\"b1\",\"state\":\"RUNNING\",\"cells\":[]}";

        client.startBuild("b1");

        assertThat(captured.get(0).method()).isEqualTo("POST");
        assertThat(captured.get(0).path()).isEqualTo("/builds/b1/start");
    }

    @Test
    void status_getsBuildById() throws ControlPlaneClientException {
        nextBody = "{\"buildId\":\"b1\",\"state\":\"RUNNING\",\"cells\":[]}";

        BuildStatus status = client.status("b1");

        assertThat(captured.get(0).method()).isEqualTo("GET");
        assertThat(captured.get(0).path()).isEqualTo("/builds/b1");
        assertThat(status.buildId()).isEqualTo("b1");
    }

    @Test
    void heartbeat_postsToHeartbeatSubPath() throws ControlPlaneClientException {
        nextBody = "{\"buildId\":\"b1\",\"state\":\"RUNNING\",\"cells\":[]}";

        client.heartbeat("b1");

        assertThat(captured.get(0).method()).isEqualTo("POST");
        assertThat(captured.get(0).path()).isEqualTo("/builds/b1/heartbeat");
    }

    @Test
    void cancel_issuesDeleteWithNoBody() throws ControlPlaneClientException {
        nextBody = "{\"buildId\":\"b1\",\"state\":\"CANCELLED\",\"cells\":[]}";

        client.cancel("b1");

        CapturedRequest req = captured.get(0);
        assertThat(req.method()).isEqualTo("DELETE");
        assertThat(req.path()).isEqualTo("/builds/b1");
        assertThat(req.body()).isEmpty();
    }

    @Test
    void artifacts_getsArtifactsSubPath() throws ControlPlaneClientException {
        nextBody = "{\"cells\":[]}";

        client.artifacts("b1");

        assertThat(captured.get(0).method()).isEqualTo("GET");
        assertThat(captured.get(0).path()).isEqualTo("/builds/b1/artifacts");
    }

    @Test
    void listBuilds_sendsLimitAsQueryParameterAndParsesJsonArray() throws ControlPlaneClientException {
        nextBody = "[{\"buildId\":\"b1\",\"state\":\"RUNNING\",\"cells\":[]},"
                + "{\"buildId\":\"b2\",\"state\":\"SUCCEEDED\",\"cells\":[]}]";

        List<BuildStatus> builds = client.listBuilds(20);

        assertThat(captured.get(0).method()).isEqualTo("GET");
        assertThat(captured.get(0).path()).isEqualTo("/builds");
        assertThat(captured.get(0).query()).isEqualTo("limit=20");
        assertThat(builds).hasSize(2);
        assertThat(builds).extracting(BuildStatus::buildId).containsExactly("b1", "b2");
    }

    // ── streamLogs ───────────────────────────────────────────────────────────────────────────────

    @Test
    void streamLogs_parsesSseDataLinesAndIgnoresKeepalives() throws ControlPlaneClientException {
        server.createContext("/builds/b1/logs", exchange -> {
            captured.add(new CapturedRequest(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(), "",
                    exchange.getRequestHeaders().getFirst("Authorization")));
            String sse = ": keepalive\n\n"
                    + "data: {\"type\":\"LOG\",\"cell\":\"NATIVE/X86_64\",\"message\":\"hello\"}\n\n"
                    + "data: {\"type\":\"STREAM_END\",\"reason\":\"BUILD_TERMINAL\"}\n\n";
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        List<String> messages = new CopyOnWriteArrayList<>();
        var last = client.streamLogs("b1", null, event -> {
            if (event.message() != null) {
                messages.add(event.message());
            }
        });

        assertThat(messages).containsExactly("hello");
        assertThat(last.reason()).isEqualTo(
                ai.codriverlabs.scaleoutbuild.controlplane.api.StreamEndReason.BUILD_TERMINAL);
    }

    @Test
    void streamLogs_includesSinceAsQueryParameterWhenProvided() throws ControlPlaneClientException {
        server.createContext("/builds/b1/logs", exchange -> {
            captured.add(new CapturedRequest(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(), "",
                    null));
            byte[] bytes = "data: {\"type\":\"STREAM_END\",\"reason\":\"BUILD_TERMINAL\"}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        client.streamLogs("b1", 12345L, event -> {});

        assertThat(captured.get(0).query()).isEqualTo("since=12345");
    }

    // ── putPresigned / getPresigned (unsigned on purpose) ───────────────────────────────────────

    @Test
    void putPresigned_uploadsFileContentsAndIsNotSigned(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws IOException, ControlPlaneClientException {
        Path file = tmp.resolve("artifact.bin");
        Files.writeString(file, "artifact-bytes");
        nextStatus = 200;

        client.putPresigned("http://localhost:" + server.getAddress().getPort() + "/presigned-put",
                file);

        CapturedRequest req = captured.get(0);
        assertThat(req.method()).isEqualTo("PUT");
        assertThat(req.body()).isEqualTo("artifact-bytes");
        assertThat(req.authorizationHeader())
                .as("presigned transfers must not be SigV4-signed by this client -- the URL's "
                        + "own query-string signature would be invalidated by an Authorization "
                        + "header")
                .isNull();
    }

    @Test
    void getPresigned_downloadsToDestinationFile(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws ControlPlaneClientException, IOException {
        nextStatus = 200;
        nextBody = "downloaded-content";
        Path destination = tmp.resolve("nested").resolve("output.bin");

        client.getPresigned(
                "http://localhost:" + server.getAddress().getPort() + "/presigned-get",
                destination);

        assertThat(Files.readString(destination)).isEqualTo("downloaded-content");
        assertThat(captured.get(0).authorizationHeader()).isNull();
    }

    // ── admin / MicroVM ──────────────────────────────────────────────────────────────────────────

    @Test
    void triggerImageBuild_postsJsonBodyAndParsesTypedResponse() throws ControlPlaneClientException {
        nextStatus = 202;
        nextBody = "{\"imageName\":\"scaleout-build-agent\",\"imageVersion\":null,"
                + "\"state\":\"CREATING\",\"imageArn\":null}";

        var response = client.triggerImageBuild("s3://bucket/agent-1.0.1.zip");

        CapturedRequest req = captured.get(0);
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/admin/microvm-images");
        assertThat(req.body()).isEqualTo("{\"artifactS3Uri\":\"s3://bucket/agent-1.0.1.zip\"}");
        assertThat(response.isBuilding()).isTrue();
    }

    @Test
    void getImageStatus_pathIncludesIdentifier() throws ControlPlaneClientException {
        nextBody = "{\"imageName\":\"scaleout-build-agent\",\"imageVersion\":\"1.0\","
                + "\"state\":\"CREATED\",\"imageArn\":\"arn:aws:lambda:...\"}";

        var response = client.getImageStatus("1.0");

        assertThat(captured.get(0).method()).isEqualTo("GET");
        assertThat(captured.get(0).path()).isEqualTo("/admin/microvm-images/1.0");
        assertThat(response.isActive()).isTrue();
    }

    // ── error mapping ────────────────────────────────────────────────────────────────────────────

    @Test
    void unexpectedStatusIsMappedToControlPlaneClientExceptionWithStatusCode() {
        nextStatus = 403;
        nextBody = "{\"error\":\"forbidden\",\"message\":\"not authorized\",\"missingItems\":[]}";

        assertThatThrownBy(() -> client.status("b1"))
                .isInstanceOf(ControlPlaneClientException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("not authorized")
                .satisfies(e -> assertThat(((ControlPlaneClientException) e).statusCode())
                        .isEqualTo(403));
    }

    @Test
    void nonErrorResponseBodyFallsBackToRawTruncatedBody() {
        nextStatus = 500;
        nextBody = "<html>Internal Server Error</html>";

        assertThatThrownBy(() -> client.status("b1"))
                .isInstanceOf(ControlPlaneClientException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("<html>Internal Server Error</html>");
    }
}
