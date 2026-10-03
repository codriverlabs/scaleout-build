/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.cli;

import ai.codriverlabs.scaleoutbuild.controlplane.api.client.SigV4Signer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import software.amazon.awssdk.regions.Region;

/**
 * Typed HTTP client for the scaleout-build control plane, used by the CLI.
 *
 * <p>Uses {@link java.net.http.HttpClient} (JDK built-in) and the shared {@link SigV4Signer}
 * from the API module. No JAX-RS implementation on the classpath — the CLI is a standalone fat
 * jar that should be as light as possible.
 *
 * <p>Errors are propagated as {@link CliException} with the HTTP status and body included, so
 * picocli command handlers can display them uniformly.
 */
public final class ControlPlaneHttpClient {

    private final String endpoint;
    private final SigV4Signer signer;
    private final Region region;
    private final HttpClient http;

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ControlPlaneHttpClient(String endpoint, String region) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.region = Region.of(region);
        this.signer = new SigV4Signer();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    // ── Build operations ─────────────────────────────────────────────────────────────────────

    /** {@code GET /builds?limit=N} */
    public String listBuilds(int limit) throws CliException {
        return get("/builds?limit=" + limit);
    }

    /** {@code GET /builds/{id}} */
    public String getBuild(String buildId) throws CliException {
        return get("/builds/" + buildId);
    }

    /** {@code DELETE /builds/{id}} */
    public String cancelBuild(String buildId) throws CliException {
        return sendRequest("DELETE", "/builds/" + buildId, null);
    }

    /** {@code GET /builds/{id}/artifacts} */
    public String listArtifacts(String buildId) throws CliException {
        return get("/builds/" + buildId + "/artifacts");
    }

    // ── Admin / MicroVM operations ────────────────────────────────────────────────────────────

    /** {@code POST /admin/microvm-images} */
    public String triggerImageBuild(String artifactS3Uri) throws CliException {
        String body;
        try {
            body = MAPPER.writeValueAsString(new TriggerBody(artifactS3Uri));
        } catch (Exception e) {
            throw new CliException("Failed to serialize request: " + e.getMessage(), 0);
        }
        return sendRequest("POST", "/admin/microvm-images", body);
    }

    /** {@code GET /admin/microvm-images/{identifier}} */
    public String getImageStatus(String imageIdentifier) throws CliException {
        return get("/admin/microvm-images/" + imageIdentifier);
    }

    // ── HTTP plumbing ─────────────────────────────────────────────────────────────────────────

    private String get(String path) throws CliException {
        return sendRequest("GET", path, null);
    }

    private String sendRequest(String method, String path, String jsonBody) throws CliException {
        URI uri = URI.create(endpoint + path);
        byte[] bodyBytes = jsonBody == null ? null : jsonBody.getBytes(StandardCharsets.UTF_8);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");

        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }

        // Sign first (SigV4Signer mutates the builder by adding auth headers)
        signer.sign(builder, method, uri, bodyBytes, region);

        // Set the method and body after signing (builder state already set for signing)
        switch (method) {
            case "GET" -> builder.GET();
            case "DELETE" -> builder.DELETE();
            case "POST" -> builder.POST(bodyBytes == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        }

        return send(builder.build());
    }

    private String send(HttpRequest request) throws CliException {
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new CliException(
                        "Server returned " + response.statusCode() + ": " + response.body(),
                        response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new CliException("Request failed: " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("Request interrupted", 0);
        }
    }

    private record TriggerBody(String artifactS3Uri) {}
}
