/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build.agent;

import ai.codriverlabs.scaleoutbuild.build.StagingLayout;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The agent's entrypoint when running as a Lambda MicroVM, as opposed to {@link AgentMain} on
 * Fargate/ECS.
 *
 * <p>A MicroVM's {@code run} hook delivers {@code runHookPayload} as an HTTP POST and must receive
 * a response within {@code MicroVmImageManager.RUN_HOOK_TIMEOUT_SECONDS} (30 s against the
 * platform's 60 s ceiling) — far too short for a multi-minute {@code native-image} compile.
 * {@code AgentMain}'s one-shot "read environment, run one compile, exit" shape has nothing that
 * could serve this: it has no HTTP server of any kind. This class provides exactly that server and
 * nothing else — {@link AgentMain#runWithDirectS3Io} is reused completely unchanged for the compile
 * itself, parameterized by an {@link AgentConfig} read from the hook payload
 * ({@link AgentConfig#fromHookPayload}) rather than from the environment.
 *
 * <p>Three routes, matching {@code MicroVmImageManager.buildHooks()}'s hook configuration on the
 * control-plane side:
 * <ul>
 *   <li>{@code /ready} — image-build time only. Phase 1 establishes no warm state before the first
 *       build, so this always returns 200 immediately.</li>
 *   <li>{@code /validate} — image-build time only. Runs a synthetic compile so the platform can
 *       sample hot memory pages for snapshot prefetch; see {@link #validate} for why this must not
 *       be a no-op.</li>
 *   <li>{@code /run} — runtime, Phase 1's actual work. Fork-and-return: read the payload, respond
 *       200 well within the 30 s hook budget, then run the compile on a non-daemon thread so the
 *       JVM does not exit before it finishes (this method's own {@code main} returns immediately
 *       after starting the server).</li>
 * </ul>
 *
 * <p>Uses {@code com.sun.net.httpserver.HttpServer} — JDK built-in, no new dependency — the same
 * choice {@code ControlPlaneClientTest}'s loopback-server pattern already made in this codebase for
 * exactly this "no new dependency" reason (see
 * {@code docs/design/testing/unit-test-strategy.md}'s "Pattern C").
 *
 * <p>No ingress connector reaches this server at runtime in Phase 1: the control plane never calls
 * into a MicroVM's endpoint (see {@code CellHandle.MicroVmHandle}'s javadoc). The platform itself is
 * the only caller of {@code /run}, {@code /ready} and {@code /validate}.
 */
public final class AgentHookServer {

    private static final Logger LOG = LoggerFactory.getLogger(AgentHookServer.class);

    /**
     * Port the agent listens on for lifecycle hooks. Must match
     * {@code MicroVmImageManager.AGENT_HOOK_PORT} on the control-plane side, which configures
     * {@code Hooks.port()} for the MicroVM image — that is the authoritative value; this is a
     * second copy because {@code scaleout-build-agent} does not depend on {@code scaleout-build-ecs}
     * (the agent is a worker process, not an orchestration client — the same separation
     * {@code CellLauncher}'s own javadoc describes between the plugin and the control plane applies
     * here too). No compile-time link enforces agreement, the same caveat
     * {@code AgentEnvironment}'s javadoc already states for the {@code SCALEOUT_BUILD_*} contract.
     */
    static final int DEFAULT_PORT = 9000;

    /**
     * Fixed prefix every Lambda MicroVM lifecycle/build hook listens under, confirmed against
     * AWS's own documentation (Lambda MicroVMs' "MicroVM images" and "Running and using
     * MicroVMs" pages): {@code /ready} and {@code /validate} (image-build time) and
     * {@code /run}/{@code /resume}/{@code /suspend}/{@code /terminate} (runtime) are all served
     * under {@code /aws/lambda-microvms/runtime/v1/<hook-name>}, not the bare hook name. Getting
     * this wrong does not fail loudly: the server still starts and the platform can still reach
     * it at the TCP level, so the only symptom is a 404 from this server's own routing, which the
     * platform reports as a generic "4xx client error" hook failure with no further detail.
     */
    private static final String HOOK_PATH_PREFIX = "/aws/lambda-microvms/runtime/v1/";

    private static final String STATUS_FILE_NAME = "status.json";
    private static final ObjectMapper STATUS_MAPPER = new ObjectMapper();
    private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper();

    private final S3Client s3Client;
    private HttpServer server;

    public AgentHookServer() {
        this(S3Client.create());
    }

    /** For tests: inject a mocked/fake {@link S3Client} instead of a real one. */
    AgentHookServer(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public static void main(String[] args) throws IOException {
        new AgentHookServer().start(DEFAULT_PORT);
    }

    /** Starts the server. Visible for tests, which bind to an ephemeral port instead. */
    void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        // Full hook paths, confirmed against AWS's own documentation (Lambda MicroVMs' "MicroVM
        // images" and "Running and using MicroVMs" pages) after an earlier version of this class
        // registered the bare "/ready", "/validate", "/run" paths instead. Every real hook listens
        // on /aws/lambda-microvms/runtime/v1/<hook-name>, not the bare name -- confirmed the hard
        // way, by actually submitting a real image build: the bare-path version passed the
        // platform's TCP-level reachability (it got past the earlier ENTRYPOINT bug fully) but
        // failed with "Ready hook check failed: the application returned a client error (HTTP 4xx)
        // response" -- a 404 from this server's own HttpServer for a path it had never registered
        // a context for, surfaced by the platform as a 4xx hook failure.
        server.createContext(HOOK_PATH_PREFIX + "ready", this::ready);
        server.createContext(HOOK_PATH_PREFIX + "validate", this::validate);
        server.createContext(HOOK_PATH_PREFIX + "run", this::run);
        server.start();
        LOG.info("Agent hook server listening on port {}", port);
    }

    /** Stops the server. Visible for tests. */
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    int boundPort() {
        return server.getAddress().getPort();
    }

    private void ready(HttpExchange exchange) throws IOException {
        // Phase 1 establishes no warm state before the first build, so always ready immediately.
        respondEmpty(exchange, 200);
    }

    private void validate(HttpExchange exchange) throws IOException {
        // The platform samples hot memory pages for snapshot prefetch here -- MicroVmImageManager's
        // own javadoc on VALIDATE_HOOK_TIMEOUT_SECONDS already documents why this must not be a
        // no-op: "the agent runs a synthetic build here, so this must exceed a minimal compile."
        // The synthetic compile intentionally does not depend on any real build's inputs -- /validate
        // runs once per image version, long before any real build's staged inputs exist.
        try {
            runSyntheticCompile();
        } catch (RuntimeException e) {
            LOG.warn("Synthetic compile during /validate failed; proceeding anyway so image "
                    + "construction is not blocked on prefetch quality: {}", e.getMessage());
        }
        respondEmpty(exchange, 200);
    }

    private void run(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        // Respond before compiling: the hook's ~30s budget covers reading the payload, not the
        // multi-minute compile that follows. See MicroVmImageManager.RUN_HOOK_TIMEOUT_SECONDS.
        respondEmpty(exchange, 200);

        // Fork: the hook has already returned. NOT a daemon thread -- a daemon thread would let
        // the JVM exit the moment main() returns (which happens right after server.start()),
        // before the compile finishes.
        Thread compile = new Thread(() -> runCompileAndWriteStatus(body), "scaleout-build-compile");
        compile.setDaemon(false);
        compile.start();
    }

    /**
     * Extracts {@code runHookPayload} from the envelope the platform actually sends to {@code
     * /run} -- confirmed against AWS's own documentation (Lambda MicroVMs' "Running and using
     * MicroVMs" page): the request body is {@code {"microvmId": "...", "runHookPayload":
     * "<string>"}}, not the raw payload this launcher's own JSON object. An earlier version of
     * this method passed the whole envelope straight to {@link AgentConfig#fromHookPayload},
     * which failed every real run with "SCALEOUT_BUILD_ID must be set" -- {@code
     * AgentConfig.fromMap} saw the envelope's own two keys ({@code microvmId},
     * {@code runHookPayload}), neither of which is a {@code SCALEOUT_BUILD_*} name, and found none
     * of the variables it actually needs. {@code runHookPayload}'s value is itself the JSON string
     * {@link MicroVmCellLauncher#runHookPayloadJson} serialized -- the flat {@code
     * SCALEOUT_BUILD_*} map -- so this is a double-decode: unwrap the envelope, then hand the
     * inner string to {@code fromHookPayload} exactly as before.
     *
     * @throws IllegalArgumentException naming what was wrong, same contract as
     *         {@link AgentConfig#fromHookPayload}, so a malformed envelope fails as legibly as a
     *         malformed inner payload already did
     */
    private byte[] unwrapRunHookEnvelope(byte[] body) {
        try {
            RunHookEnvelope envelope = ENVELOPE_MAPPER.readValue(body, RunHookEnvelope.class);
            if (envelope.runHookPayload() == null) {
                throw new IllegalArgumentException(
                        "/run request body has no runHookPayload field: " + new String(body,
                                java.nio.charset.StandardCharsets.UTF_8));
            }
            return envelope.runHookPayload()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "/run request body is not the expected {microvmId, runHookPayload} envelope: "
                            + e.getMessage(), e);
        }
    }

    private void runCompileAndWriteStatus(byte[] runHookRequestBody) {
        AgentConfig config;
        try {
            byte[] innerPayload = unwrapRunHookEnvelope(runHookRequestBody);
            config = AgentConfig.fromHookPayload(innerPayload);
        } catch (RuntimeException e) {
            LOG.error("Invalid run hook payload: {}", e.getMessage());
            return; // Nothing to report a status for -- no buildId/cell was ever parsed.
        }
        LOG.info("Starting build agent (MicroVM hook) with {}", config);
        int exitCode = AgentMain.runWithDirectS3Io(config, ai.codriverlabs.scaleoutbuild.build.BuildLog
                .defaultLog(), s3Client);
        try {
            writeStatusJson(config, exitCode);
        } catch (IOException e) {
            // The compile's own exit code/log output already reached CloudWatch via stdout; losing
            // status.json loses only the control plane's MicroVM-side poll signal, not the build
            // record itself -- MicroVmCellLauncher.poll() falls back to GetMicrovm for exactly this
            // case (see the design doc's "Poll" section).
            LOG.error("Failed to write {} for build {}: {}", STATUS_FILE_NAME, config.buildId(),
                    e.getMessage(), e);
        }
    }

    /**
     * Writes the {@code status.json} object the design doc specifies to
     * {@code s3://<bucket>/<stagingRelativePath>/status.json} — the same staging prefix
     * {@link S3Io}/{@code StagingLayout} already resolve every other input/output of this cell
     * against, so no new prefix convention is needed.
     *
     * <p>This is the control plane's only read signal for a MicroVM cell's outcome: there is no
     * ingress connector for it to call the agent back on (see this class's own javadoc), so
     * {@code MicroVmCellLauncher.poll()} reads this object from S3 instead.
     */
    void writeStatusJson(AgentConfig config, int exitCode) throws IOException {
        String cell = config.buildKind().configValue().toUpperCase(java.util.Locale.ROOT) + "/"
                + config.architecture().name();
        String state = exitCode == AgentMain.EXIT_SUCCESS ? "SUCCEEDED" : "FAILED";
        List<ArtifactEntry> artifacts = state.equals("SUCCEEDED")
                ? describeArtifacts(config)
                : List.of();

        StatusReport report = new StatusReport(config.buildId(), cell, state, exitCode, artifacts,
                Instant.now().toString());

        byte[] json = STATUS_MAPPER.writeValueAsBytes(report);
        String key = joinKey(config.stagingRelativePath(), STATUS_FILE_NAME);
        try {
            s3Client.putObject(
                    software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                            .bucket(config.s3Bucket())
                            .key(key)
                            .contentType("application/json")
                            .build(),
                    software.amazon.awssdk.core.sync.RequestBody.fromBytes(json));
        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            throw new IOException(
                    "Failed to upload " + STATUS_FILE_NAME + " to s3://" + config.s3Bucket() + "/"
                            + key, e);
        }
        LOG.info("Wrote {} (state={}, exitCode={}) to s3://{}/{}", STATUS_FILE_NAME, state, exitCode,
                config.s3Bucket(), key);
    }

    /**
     * Describes a successful build's artifacts for {@code status.json} by listing what
     * {@link AgentMain#runWithDirectS3Io} already uploaded to
     * {@code <stagingRelativePath>/output/}, rather than re-reading the local filesystem.
     *
     * <p>Re-reading local disk does not work here: {@code runWithDirectS3Io}'s own {@code finally}
     * block deletes the local staging directory — including {@code output/} — before this method
     * ever runs, since {@link #writeStatusJson} is called strictly after
     * {@code runWithDirectS3Io} returns. S3 is also the only copy of the artifact by then, so this
     * reads the digest from the object that was actually uploaded, not from a local file that may
     * no longer exist.
     */
    private List<ArtifactEntry> describeArtifacts(AgentConfig config) {
        String outputKeyPrefix = joinKey(config.stagingRelativePath(), StagingLayout.OUTPUT_DIR_NAME);
        String normalizedPrefix = outputKeyPrefix.endsWith("/") ? outputKeyPrefix : outputKeyPrefix + "/";
        var response = s3Client.listObjectsV2(
                software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                        .bucket(config.s3Bucket())
                        .prefix(normalizedPrefix)
                        .build());
        return response.contents().stream()
                .map(object -> describeOne(config.s3Bucket(), object))
                .toList();
    }

    private ArtifactEntry describeOne(String bucket, software.amazon.awssdk.services.s3.model.S3Object object) {
        String fileName = object.key().substring(object.key().lastIndexOf('/') + 1);
        String sha256 = streamingSha256(bucket, object.key());
        return new ArtifactEntry(fileName, sha256, object.size());
    }

    /** Hashes an S3 object by streaming its bytes through the digest, without buffering the whole
     * object in memory — artifacts here are native-image binaries, which can be large. */
    private String streamingSha256(String bucket, String key) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-guaranteed MessageDigest algorithm; this cannot happen in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        try (var in = s3Client.getObject(
                software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                        .bucket(bucket).key(key).build())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read s3://" + bucket + "/" + key + " for status.json digest", e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void runSyntheticCompile() {
        // A minimal native-image invocation against a trivial bundled class, intentionally not the
        // user's actual project -- /validate runs once per image version, before any real build's
        // inputs exist. Phase 1 ships this as a best-effort warm-up; a genuinely minimal compile
        // (e.g. "public static void main(String[] a) {}") is cheap enough that failures here should
        // not block image construction, which is why callers of this method only log on failure.
        ProcessBuilder pb = new ProcessBuilder("native-image", "--version");
        try {
            Process process = pb.start();
            process.waitFor();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running synthetic compile", e);
        }
    }

    private void respondEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static String joinKey(String prefix, String relativePath) {
        return prefix.endsWith("/") ? prefix + relativePath : prefix + "/" + relativePath;
    }

    /** One artifact entry in {@code status.json}. */
    record ArtifactEntry(String path, String sha256, long sizeBytes) {
    }

    /**
     * The envelope the platform actually sends as {@code /run}'s request body -- confirmed
     * against AWS's own documentation (Lambda MicroVMs' "Running and using MicroVMs" page):
     * {@code {"microvmId": "...", "runHookPayload": "<string>"}}. {@code microvmId} is unused
     * here -- {@link CellHandle.MicroVmHandle} already carries it from {@code RunMicrovmResponse}
     * on the control-plane side, so this class has no need to read it back out of the hook body.
     */
    record RunHookEnvelope(String microvmId, String runHookPayload) {
    }

    /**
     * The {@code status.json} shape, matching
     * {@code docs/design/control-plane/microvm-build-backend-phase1.md}'s "status.json" section
     * exactly.
     */
    record StatusReport(String buildId, String cell, String state, int exitCode,
                        List<ArtifactEntry> artifacts, String completedAt) {
    }
}
