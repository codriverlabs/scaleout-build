/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;

/**
 * Signs outgoing JAX-RS client requests with AWS SigV4, for callers of {@code BuildApi}.
 *
 * <p>Defaults to signing name {@code lambda}, because the control plane is exposed through a Lambda
 * Function URL with {@code AuthType: AWS_IAM} rather than API Gateway. Use {@code execute-api} only
 * if the service is ever moved behind API Gateway.
 *
 * <p>Credentials come from the standard AWS SDK default chain (environment, shared config, SSO,
 * container and instance metadata), so an MCP server, a CLI, and a Maven plugin all authenticate the
 * same way the AWS CLI already does on the same machine, with no separate credential handling.
 *
 * <h2>Why the body is serialized here rather than left to the JAX-RS provider</h2>
 *
 * SigV4 signs a hash of the request payload, so whatever is signed must be byte-identical to
 * whatever is sent. A {@code ClientRequestFilter} runs <em>before</em> the entity is serialized, so
 * the obvious implementation — read {@code getEntity()}, sign only if it is already a
 * {@code String} or {@code byte[]}, otherwise sign an empty payload — silently produces a signature
 * over an empty body while a JSON body is actually transmitted. The request is then rejected with
 * {@code SignatureDoesNotMatch} for every POJO body, which is most request bodies.
 *
 * <p>{@code express-compute-control-plane}'s equivalent filter has exactly that shape. This
 * implementation instead serializes any non-{@code String}/{@code byte[]} entity to bytes itself,
 * signs those bytes, and replaces the entity with them, so signed and sent cannot diverge.
 *
 * <p>Register per JAX-RS client, e.g. {@code @RegisterProvider(SigV4RequestFilter.class)} on a
 * Quarkus REST Client interface, or {@code ClientBuilder.newClient().register(new
 * SigV4RequestFilter(region, "lambda"))} for a plain client.
 */
public final class SigV4RequestFilter implements ClientRequestFilter {

    /** Signing name for a Lambda Function URL. */
    public static final String SERVICE_LAMBDA = "lambda";

    /** Signing name for API Gateway, should the service ever move behind one. */
    public static final String SERVICE_EXECUTE_API = "execute-api";

    private static final String PROP_REGION = "scaleout.sigv4.region";
    private static final String PROP_SERVICE = "scaleout.sigv4.service";

    private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();
    private final AwsCredentialsProvider credentialsProvider;
    private final ObjectMapper objectMapper;
    private final Region region;
    private final String service;

    /**
     * Resolves the region from {@code scaleout.sigv4.region}, then {@code AWS_REGION}, then
     * {@code AWS_DEFAULT_REGION}; and the signing name from {@code scaleout.sigv4.service},
     * defaulting to {@link #SERVICE_LAMBDA}.
     *
     * @throws IllegalStateException if no region can be determined — failing here with a clear
     *         message beats emitting requests signed for an arbitrary default region and debugging
     *         the resulting signature mismatch
     */
    public SigV4RequestFilter() {
        this(resolveRegion(), System.getProperty(PROP_SERVICE, SERVICE_LAMBDA));
    }

    public SigV4RequestFilter(String region, String service) {
        this(region, service, DefaultCredentialsProvider.builder()
                .reuseLastProviderEnabled(true).build(), new ObjectMapper());
    }

    public SigV4RequestFilter(String region, String service,
                              AwsCredentialsProvider credentialsProvider,
                              ObjectMapper objectMapper) {
        this.region = Region.of(Objects.requireNonNull(region, "region"));
        this.service = Objects.requireNonNull(service, "service");
        this.credentialsProvider = Objects.requireNonNull(credentialsProvider, "credentialsProvider");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    private static String resolveRegion() {
        String configured = System.getProperty(PROP_REGION);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("AWS_REGION");
        }
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("AWS_DEFAULT_REGION");
        }
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Cannot determine the AWS region for SigV4 signing; set -D"
                    + PROP_REGION + ", AWS_REGION, or AWS_DEFAULT_REGION");
        }
        return configured;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        byte[] payload = materializeEntity(requestContext);
        URI uri = requestContext.getUri();

        SdkHttpRequest.Builder unsigned = SdkHttpRequest.builder()
                .method(SdkHttpMethod.fromValue(requestContext.getMethod()))
                .uri(uri);

        // Do NOT call appendRawQueryParameter here. The query string is already present in the
        // URI passed to uri() above, and AwsV4HttpSigner reads it from the URI automatically.
        // Calling appendRawQueryParameter in addition would double every query parameter in the
        // canonical query string, producing a signature that Lambda cannot verify.
        // See: SigV4Signer — same fix applied there first.

        requestContext.getStringHeaders().forEach((name, values) -> {
            if (!"Host".equalsIgnoreCase(name) && !"Authorization".equalsIgnoreCase(name)) {
                values.forEach(value -> unsigned.appendHeader(name, value));
            }
        });

        SignedRequest signed = signer.sign(b -> {
            // No cast needed: AwsCredentials extends AwsCredentialsIdentity.
            b.identity(credentialsProvider.resolveCredentials())
                    .request(unsigned.build())
                    .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, service)
                    .putProperty(AwsV4HttpSigner.REGION_NAME, region.id());
            // DOUBLE_URL_ENCODE, NORMALIZE_PATH (both default true), PAYLOAD_SIGNING_ENABLED
            // (default true) and AUTH_LOCATION (default HEADER) are deliberately left at their
            // defaults, which are correct for every service except S3. See
            // docs/design/control-plane/sigv4-client-signing.md.
            if (payload.length > 0) {
                b.payload(() -> new ByteArrayInputStream(payload));
            }
        });

        signed.request().headers().forEach((name, values) -> {
            if (!"Host".equalsIgnoreCase(name)) {
                requestContext.getHeaders().putSingle(name, String.join(",", values));
            }
        });
    }

    /**
     * Returns the exact bytes that will be transmitted, replacing the entity when it had to be
     * serialized here so that signed bytes and sent bytes are the same bytes.
     */
    private byte[] materializeEntity(ClientRequestContext requestContext) {
        if (!requestContext.hasEntity()) {
            return new byte[0];
        }
        Object entity = requestContext.getEntity();
        if (entity instanceof byte[] bytes) {
            return bytes;
        }
        if (entity instanceof String string) {
            return string.getBytes(StandardCharsets.UTF_8);
        }
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(entity);
            requestContext.setEntity(serialized);
            return serialized;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize the request entity of type "
                    + entity.getClass().getName() + " for SigV4 signing", e);
        }
    }
}
