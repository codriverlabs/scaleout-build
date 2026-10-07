/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api.client;

import ai.codriverlabs.scaleoutbuild.controlplane.api.AdminApi;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ArtifactListResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildRequest;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ErrorResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.LogEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import software.amazon.awssdk.regions.Region;

/**
 * Typed HTTP client for the scaleout-build control plane, over {@link HttpClient}.
 *
 * <p>Shared by the Maven plugin, the CLI, and the MCP server — see
 * {@code docs/design/control-plane/control-plane-client-consolidation.md} for why there was
 * previously one independent copy per consumer and why that was a mistake worth undoing.
 *
 * <p>Deliberately not a JAX-RS REST client: that would put a JAX-RS implementation and its
 * transitive dependencies on the classpath of every consumer, to gain nothing — the whole surface
 * is a dozen calls with hand-held bodies.
 *
 * <p>Presigned S3 transfers go out on a <b>separate, unsigned</b> request path. A presigned URL
 * authenticates by query string and already carries a signature; adding an {@code Authorization}
 * header would invalidate it. This is the practical reason
 * {@code docs/design/control-plane/sigv4-client-signing.md} insists the signer is attached per API
 * call rather than globally to an HTTP client.
 */
public final class ControlPlaneClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    /*
     * JavaTimeModule is registered explicitly rather than via findAndRegisterModules().
     *
     * findAndRegisterModules() is a ServiceLoader scan: with jackson-datatype-jsr310 absent from a
     * consumer's runtime classpath it silently finds nothing and the mapper looks fine, then fails
     * the first time an Instant crosses the wire -- which is what happened on the first real build
     * through this client's predecessor, at createBuild, on UploadTarget.expiresAt. An explicit
     * registration cannot compile without the dependency present, which is also why this module
     * declares jackson-datatype-jsr310 at compile scope rather than leaving it to a transitive, and
     * possibly absent, dependency.
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final SigV4Signer signer;
    private final URI endpoint;
    private final Region region;

    public ControlPlaneClient(String endpoint) {
        this(endpoint, new SigV4Signer());
    }

    /**
     * Test-only seam: injects a pre-built signer instead of the default, so tests can supply
     * {@code StaticCredentialsProvider} rather than depend on {@code DefaultCredentialsProvider}
     * resolving real or ambient AWS credentials.
     */
    ControlPlaneClient(String endpoint, SigV4Signer signer) {
        String normalized = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        this.endpoint = URI.create(normalized);
        this.region = SigV4Signer.regionOf(this.endpoint);
        this.signer = signer;
    }

    /**
     * Test-only seam: bypasses {@link SigV4Signer#regionOf(URI)}'s Lambda-Function-URL hostname
     * requirement, so tests can point this client at a loopback {@code HttpServer} (whose host is
     * {@code localhost}, not a {@code <id>.lambda-url.<region>.on.aws} hostname) without that call
     * throwing before the test gets to exercise anything.
     */
    ControlPlaneClient(String endpoint, SigV4Signer signer, Region region) {
        String normalized = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        this.endpoint = URI.create(normalized);
        this.region = region;
        this.signer = signer;
    }

    Region region() {
        return region;
    }

    // ── Build lifecycle ──────────────────────────────────────────────────────────────────────────

    public CreateBuildResponse createBuild(CreateBuildRequest request)
            throws ControlPlaneClientException {
        return send("POST", "/builds", request, CreateBuildResponse.class, 201);
    }

    public BuildStatus startBuild(String buildId) throws ControlPlaneClientException {
        return send("POST", "/builds/" + buildId + "/start", null, BuildStatus.class, 202);
    }

    public BuildStatus status(String buildId) throws ControlPlaneClientException {
        return send("GET", "/builds/" + buildId, null, BuildStatus.class, 200);
    }

    public ArtifactListResponse artifacts(String buildId) throws ControlPlaneClientException {
        return send("GET", "/builds/" + buildId + "/artifacts", null, ArtifactListResponse.class,
                200);
    }

    public BuildStatus heartbeat(String buildId) throws ControlPlaneClientException {
        return send("POST", "/builds/" + buildId + "/heartbeat", null, BuildStatus.class, 200);
    }

    public BuildStatus cancel(String buildId) throws ControlPlaneClientException {
        return send("DELETE", "/builds/" + buildId, null, BuildStatus.class, 200);
    }

    /**
     * Lists the caller's most recent builds, newest first.
     *
     * <p>The wire body is a plain JSON array of {@link BuildStatus} — confirmed against
     * {@code BuildService.listMine()}'s own return type and {@code BuildResource.listBuilds()}
     * wrapping it directly, with no envelope — rather than assumed.
     */
    public List<BuildStatus> listBuilds(int limit) throws ControlPlaneClientException {
        return sendList("GET", "/builds?limit=" + limit, null,
                new TypeReference<List<BuildStatus>>() {}, 200);
    }

    // ── Log streaming ────────────────────────────────────────────────────────────────────────────

    /**
     * Consumes the SSE log stream until it ends, handing each event to {@code sink}.
     *
     * @param since per-cell watermarks from a previous stream end, or {@code null} to start from the
     *              beginning
     * @return the terminating event, so the caller can decide whether to reconnect
     */
    public LogEvent streamLogs(String buildId, Long since, Consumer<LogEvent> sink)
            throws ControlPlaneClientException {
        String path = "/builds/" + buildId + "/logs" + (since == null ? "" : "?since=" + since);
        URI uri = endpoint.resolve(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(16))
                .GET();
        signer.sign(builder, "GET", uri, null, region);

        HttpResponse<java.io.InputStream> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlPlaneClientException("interrupted while opening the log stream", 0, e);
        } catch (IOException e) {
            throw new ControlPlaneClientException("failed to open the log stream: " + e.getMessage(),
                    0, e);
        }
        if (response.statusCode() != 200) {
            throw new ControlPlaneClientException(
                    "log stream failed with HTTP " + response.statusCode(), response.statusCode());
        }

        LogEvent last = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
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
        } catch (IOException e) {
            throw new ControlPlaneClientException("failed while reading the log stream: "
                    + e.getMessage(), 0, e);
        }
        return last;
    }

    // ── Artifact transfer (presigned, deliberately unsigned — see class javadoc) ───────────────────

    /** Uploads to a presigned URL. Unsigned on purpose — see the class javadoc. */
    public void putPresigned(String url, Path file) throws ControlPlaneClientException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .PUT(bodyFromFile(file))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ControlPlaneClientException("upload of " + file.getFileName()
                        + " failed with HTTP " + response.statusCode() + ": "
                        + truncate(response.body()), response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlPlaneClientException("interrupted while uploading " + file, 0, e);
        } catch (IOException e) {
            throw new ControlPlaneClientException("upload of " + file + " failed: "
                    + e.getMessage(), 0, e);
        }
    }

    /** Downloads from a presigned URL, overwriting any existing file. */
    public void getPresigned(String url, Path destination) throws ControlPlaneClientException {
        try {
            Files.createDirectories(destination.getParent());
        } catch (IOException e) {
            throw new ControlPlaneClientException("failed to create parent directories for "
                    + destination, 0, e);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        try {
            /*
             * ofFile, not ofFileDownload.
             *
             * ofFileDownload derives the filename from the Content-Disposition header and throws
             * "No Content-Disposition header in response" when it is absent. S3 does not send one on
             * a presigned GET, so it can never work here. ofFile writes to a path we choose, which
             * is what we want anyway: the destination name comes from the artifact descriptor, not
             * from the server.
             *
             * TRUNCATE_EXISTING so a repeat build does not fail on, or append to, a previous
             * artifact.
             */
            HttpResponse<Path> response = http.send(request,
                    HttpResponse.BodyHandlers.ofFile(destination,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING));
            if (response.statusCode() / 100 != 2) {
                throw new ControlPlaneClientException(
                        "download failed with HTTP " + response.statusCode(),
                        response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlPlaneClientException("interrupted while downloading " + destination,
                    0, e);
        } catch (IOException e) {
            throw new ControlPlaneClientException("download to " + destination + " failed: "
                    + e.getMessage(), 0, e);
        }
    }

    // ── Admin / MicroVM ──────────────────────────────────────────────────────────────────────────

    public AdminApi.ImageBuildResponse triggerImageBuild(String artifactS3Uri)
            throws ControlPlaneClientException {
        return send("POST", "/admin/microvm-images",
                new AdminApi.TriggerImageBuildRequest(artifactS3Uri),
                AdminApi.ImageBuildResponse.class, 202);
    }

    public AdminApi.ImageBuildResponse getImageStatus(String imageIdentifier)
            throws ControlPlaneClientException {
        return send("GET", "/admin/microvm-images/" + imageIdentifier, null,
                AdminApi.ImageBuildResponse.class, 200);
    }

    // ── HTTP plumbing ────────────────────────────────────────────────────────────────────────────

    private static HttpRequest.BodyPublisher bodyFromFile(Path file) throws ControlPlaneClientException {
        try {
            return HttpRequest.BodyPublishers.ofFile(file);
        } catch (java.io.FileNotFoundException e) {
            throw new ControlPlaneClientException("file not found: " + file, 0, e);
        }
    }

    private <T> T send(String method, String path, Object body, Class<T> responseType,
                       int expectedStatus) throws ControlPlaneClientException {
        byte[] responseBody = sendRaw(method, path, body, expectedStatus);
        try {
            return mapper.readValue(responseBody, responseType);
        } catch (IOException e) {
            throw new ControlPlaneClientException("failed to parse response for " + method + " "
                    + path + ": " + e.getMessage(), 0, e);
        }
    }

    private <T> T sendList(String method, String path, Object body, TypeReference<T> responseType,
                           int expectedStatus) throws ControlPlaneClientException {
        byte[] responseBody = sendRaw(method, path, body, expectedStatus);
        try {
            return mapper.readValue(responseBody, responseType);
        } catch (IOException e) {
            throw new ControlPlaneClientException("failed to parse response for " + method + " "
                    + path + ": " + e.getMessage(), 0, e);
        }
    }

    private byte[] sendRaw(String method, String path, Object body, int expectedStatus)
            throws ControlPlaneClientException {
        URI uri = endpoint.resolve(path);
        byte[] payload;
        try {
            payload = body == null ? new byte[0] : mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new ControlPlaneClientException("failed to serialize request body for " + method
                    + " " + path + ": " + e.getMessage(), 0, e);
        }
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
            throw new ControlPlaneClientException("interrupted calling " + method + " " + path, 0, e);
        } catch (IOException e) {
            throw new ControlPlaneClientException("request failed calling " + method + " " + path
                    + ": " + e.getMessage(), 0, e);
        }
        if (response.statusCode() != expectedStatus) {
            throw new ControlPlaneClientException(describeFailure(method, path, response),
                    response.statusCode());
        }
        return response.body().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Turns a failure into something actionable. The service answers every non-2xx with a consistent
     * {@link ErrorResponse}, so the machine-readable code is surfaced rather than just a status
     * number.
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
        return method + " " + path + " failed with HTTP " + response.statusCode() + ": " + detail
                + hint;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "<empty>";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "...";
    }
}
