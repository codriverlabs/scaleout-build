/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codriverlabs.scaleoutbuild.controlplane.api.client.SigV4Signer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * Exercises {@link ControlPlaneHttpClient} against a real loopback {@link HttpServer} instead of
 * mocking the class itself, per {@code docs/design/testing/unit-test-strategy.md}'s "Pattern C".
 *
 * <p>{@link ScaleoutCliTest} mocks this class to test command parsing and delegation; it never
 * executes this class's own body. These tests cover exactly what that one does not: the literal
 * path/query construction, the SigV4 signing call, the request body serialization, and the
 * HTTP-status-to-{@link CliException} mapping — the same call path where the doubled-query-string
 * SigV4 bug lived (see {@code SigV4SignerTest}), invisible to any test that mocks the signer away.
 */
class ControlPlaneHttpClientTest {

    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("AKIAEXAMPLE", "secretExampleKeyMaterial"));

    private HttpServer server;
    private ControlPlaneHttpClient client;
    private final List<CapturedRequest> captured = new CopyOnWriteArrayList<>();
    private volatile int nextStatus = 200;
    private volatile String nextBody = "{\"ok\":true}";

    private record CapturedRequest(String method, String path, String query, String body,
                                   String authorizationHeader) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        SigV4Signer signer = new SigV4Signer(CREDENTIALS, SigV4Signer.SERVICE_LAMBDA);
        client = new ControlPlaneHttpClient(
                "http://localhost:" + server.getAddress().getPort(), "eu-west-1", signer);
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

    // ── listBuilds ───────────────────────────────────────────────────────────────────────────

    @Test
    void listBuilds_sendsLimitAsQueryParameterOnGet() throws CliException {
        client.listBuilds(20);

        assertThat(captured).hasSize(1);
        CapturedRequest req = captured.get(0);
        assertThat(req.method()).isEqualTo("GET");
        assertThat(req.path()).isEqualTo("/builds");
        assertThat(req.query()).isEqualTo("limit=20");
        assertThat(req.body()).isEmpty();
    }

    @Test
    void listBuilds_requestIsSigned() throws CliException {
        client.listBuilds(20);

        // Not asserting signature correctness here -- SigV4SignerTest owns that. Only asserting
        // that ControlPlaneHttpClient actually invokes the signer and the header makes it onto
        // the wire, which is exactly the step a test mocking this class would never exercise.
        assertThat(captured.get(0).authorizationHeader()).startsWith("AWS4-HMAC-SHA256");
    }

    @Test
    void listBuilds_returnsResponseBodyVerbatim() throws CliException {
        nextBody = "{\"builds\":[{\"buildId\":\"abc\"}]}";

        String result = client.listBuilds(20);

        assertThat(result).isEqualTo(nextBody);
    }

    // ── getBuild / cancelBuild / listArtifacts: path construction ──────────────────────────────

    @Test
    void getBuild_pathIncludesBuildId() throws CliException {
        client.getBuild("build-123");

        assertThat(captured.get(0).method()).isEqualTo("GET");
        assertThat(captured.get(0).path()).isEqualTo("/builds/build-123");
    }

    @Test
    void cancelBuild_issuesDeleteWithNoBody() throws CliException {
        client.cancelBuild("build-123");

        CapturedRequest req = captured.get(0);
        assertThat(req.method()).isEqualTo("DELETE");
        assertThat(req.path()).isEqualTo("/builds/build-123");
        assertThat(req.body()).isEmpty();
    }

    @Test
    void listArtifacts_pathIncludesBuildIdAndArtifactsSuffix() throws CliException {
        client.listArtifacts("build-123");

        assertThat(captured.get(0).path()).isEqualTo("/builds/build-123/artifacts");
    }

    // ── admin / MicroVM operations ───────────────────────────────────────────────────────────

    @Test
    void triggerImageBuild_sendsJsonBodyWithArtifactUri() throws CliException {
        client.triggerImageBuild("s3://bucket/agent-1.0.1.zip");

        CapturedRequest req = captured.get(0);
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/admin/microvm-images");
        assertThat(req.body()).isEqualTo("{\"artifactS3Uri\":\"s3://bucket/agent-1.0.1.zip\"}");
    }

    @Test
    void getImageStatus_pathIncludesIdentifier() throws CliException {
        client.getImageStatus("1.0.1");

        assertThat(captured.get(0).method()).isEqualTo("GET");
        assertThat(captured.get(0).path()).isEqualTo("/admin/microvm-images/1.0.1");
    }

    // ── error mapping ────────────────────────────────────────────────────────────────────────

    @Test
    void serverErrorStatusIsMappedToCliExceptionWithStatusCodeAndBody() {
        nextStatus = 403;
        nextBody = "{\"error\":\"not authorized\"}";

        assertThatThrownBy(() -> client.listBuilds(20))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("not authorized")
                .satisfies(e -> assertThat(((CliException) e).statusCode()).isEqualTo(403));
    }

    @Test
    void successStatusDoesNotThrow() throws CliException {
        nextStatus = 200;

        String result = client.listBuilds(20);

        assertThat(result).isNotNull();
    }
}
