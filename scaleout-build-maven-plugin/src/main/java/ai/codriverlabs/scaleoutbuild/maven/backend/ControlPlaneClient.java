/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.backend;

import ai.codriverlabs.scaleoutbuild.controlplane.api.ArtifactListResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildRequest;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ErrorResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.LogEvent;
import ai.codriverlabs.scaleoutbuild.controlplane.api.client.SigV4Signer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.Consumer;
import software.amazon.awssdk.regions.Region;

/**
 * Typed HTTP client for the builder control plane, over {@link java.net.http.HttpClient}.
 *
 * <p>Deliberately not a JAX-RS REST client: that would put a JAX-RS implementation and its transitive
 * dependencies on the classpath of every Maven build using this plugin, to gain nothing — the plugin
 * makes six calls with hand-held bodies.
 *
 * <p>Presigned S3 transfers go out on a <b>separate, unsigned</b> request path. A presigned URL
 * authenticates by query string and already carries a signature; adding an {@code Authorization}
 * header would invalidate it. This is the practical reason
 * {@code docs/design/control-plane/sigv4-client-signing.md} insists the signer is attached per API
 * client rather than globally to an HTTP client.
 */
final class ControlPlaneClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    /*
     * JavaTimeModule is registered explicitly rather than via findAndRegisterModules().
     *
     * findAndRegisterModules() is a ServiceLoader scan: with jackson-datatype-jsr310 absent from the
     * plugin's runtime classpath it silently finds nothing and the mapper looks fine, then fails the
     * first time an Instant crosses the wire -- which is what happened on the first real build, at
     * createBuild, on UploadTarget.expiresAt. An explicit registration cannot compile without the
     * dependency present.
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final SigV4Signer signer = new SigV4Signer();
    private final URI endpoint;
    private final Region region;

    ControlPlaneClient(String endpoint) {
        String normalized = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        this.endpoint = URI.create(normalized);
        this.region = SigV4Signer.regionOf(this.endpoint);
    }

    Region region() {
        return region;
    }

    CreateBuildResponse createBuild(CreateBuildRequest request) throws IOException {
        return send("POST", "/builds", request, CreateBuildResponse.class, 201);
    }

    BuildStatus startBuild(String buildId) throws IOException {
        return send("POST", "/builds/" + buildId + "/start", null, BuildStatus.class, 202);
    }

    BuildStatus status(String buildId) throws IOException {
        return send("GET", "/builds/" + buildId, null, BuildStatus.class, 200);
    }

    ArtifactListResponse artifacts(String buildId) throws IOException {
        return send("GET", "/builds/" + buildId + "/artifacts", null, ArtifactListResponse.class, 200);
    }

    void heartbeat(String buildId) throws IOException {
        send("POST", "/builds/" + buildId + "/heartbeat", null, BuildStatus.class, 200);
    }

    void cancel(String buildId) throws IOException {
        send("DELETE", "/builds/" + buildId, null, BuildStatus.class, 200);
    }

    /**
     * Consumes the SSE log stream until it ends, handing each event to {@code sink}.
     *
     * @param since per-cell watermarks from a previous stream end, or {@code null} to start from the
     *              beginning
     * @return the terminating event, so the caller can decide whether to reconnect
     */
    LogEvent streamLogs(String buildId, Long since, Consumer<LogEvent> sink) throws IOException {
        String path = "/builds/" + buildId + "/logs" + (since == null ? "" : "?since=" + since);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofMinutes(16))
                .GET();
        signer.sign(builder, "GET", endpoint.resolve(path), null, region);

        HttpResponse<java.io.InputStream> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while opening the log stream", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("log stream failed with HTTP " + response.statusCode());
        }

        LogEvent last = null;
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // SSE: `data: <json>`, blank lines separate events, `:` lines are keepalive comments.
                if (!line.startsWith("data:")) {
                    continue;
                }
                String json = line.substring("data:".length()).trim();
                if (json.isEmpty()) {
                    continue;
                }
                LogEvent event = mapper.readValue(json, LogEvent.class);
                last = event;
                sink.accept(event);
            }
        }
        return last;
    }

    /** Uploads to a presigned URL. Unsigned on purpose — see the class javadoc. */
    void putPresigned(String url, Path file) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .PUT(HttpRequest.BodyPublishers.ofFile(file))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("upload of " + file.getFileName() + " failed with HTTP "
                        + response.statusCode() + ": " + truncate(response.body()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while uploading " + file, e);
        }
    }

    /** Downloads from a presigned URL, overwriting any existing file. */
    void getPresigned(String url, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        try {
            /*
             * ofFile, not ofFileDownload.
             *
             * ofFileDownload derives the filename from the Content-Disposition header and throws
             * "No Content-Disposition header in response" when it is absent. S3 does not send one on a
             * presigned GET, so it can never work here -- it failed on the first build that got far
             * enough to download an artifact, after both native images had already been built
             * successfully. ofFile writes to a path we choose, which is what we want anyway: the
             * destination name comes from the artifact descriptor, not from the server.
             *
             * TRUNCATE_EXISTING so a repeat build does not fail on, or append to, a previous artifact.
             * The direct path had that bug, fixed in b33f9e2.
             */
            HttpResponse<Path> response = http.send(request,
                    HttpResponse.BodyHandlers.ofFile(destination,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.WRITE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("download failed with HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading " + destination, e);
        }
    }

    private <T> T send(String method, String path, Object body, Class<T> responseType,
                       int expectedStatus) throws IOException {
        URI uri = endpoint.resolve(path);
        byte[] payload = body == null ? new byte[0] : mapper.writeValueAsBytes(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60));
        if (payload.length > 0) {
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        signer.sign(builder, method, uri, payload, region);

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted calling " + method + " " + path, e);
        }
        if (response.statusCode() != expectedStatus) {
            throw new IOException(describeFailure(method, path, response));
        }
        return mapper.readValue(response.body(), responseType);
    }

    /**
     * Turns a failure into something actionable. The service answers every non-2xx with a consistent
     * {@link ErrorResponse}, so the machine-readable code is surfaced rather than just a status number.
     */
    private String describeFailure(String method, String path, HttpResponse<String> response) {
        String detail = truncate(response.body());
        try {
            ErrorResponse error = mapper.readValue(response.body(), ErrorResponse.class);
            if (error.error() != null) {
                detail = error.error() + ": " + error.message()
                        + (error.missingItems().isEmpty() ? ""
                        : " (missing: " + String.join(", ", error.missingItems()) + ")");
            }
        } catch (Exception ignored) {
            // Not an ErrorResponse -- most likely an AWS-level rejection before reaching the service,
            // e.g. a 403 from the Function URL's IAM auth, where the raw body is the useful part.
        }
        String hint = response.statusCode() == 403
                ? " -- check the caller has lambda:InvokeFunctionUrl and lambda:InvokeFunction on the "
                + "control-plane function, and that credentials are for the right account"
                : "";
        return method + " " + path + " failed with HTTP " + response.statusCode() + ": " + detail + hint;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "<empty>";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "...";
    }
}
