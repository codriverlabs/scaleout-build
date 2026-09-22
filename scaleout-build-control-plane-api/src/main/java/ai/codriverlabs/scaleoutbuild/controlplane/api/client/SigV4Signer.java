/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api.client;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
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
 * Signs {@link java.net.http.HttpRequest}s with AWS SigV4.
 *
 * <p>The counterpart to {@link SigV4RequestFilter} for callers that use the JDK HTTP client rather
 * than JAX-RS. Both exist because the two have genuinely different problems:
 *
 * <ul>
 *   <li>A JAX-RS {@code ClientRequestFilter} runs <em>before</em> the entity is serialized, so the
 *       filter has to serialize it itself to guarantee that signed bytes equal sent bytes.</li>
 *   <li>Here the caller already holds the body as bytes, so that ordering problem does not exist —
 *       the bytes are passed in, signed, and sent. This is the simpler and safer of the two, which is
 *       why a Maven plugin uses it rather than pulling in a JAX-RS implementation purely to sign.</li>
 * </ul>
 *
 * <p>Signing defaults to service {@code lambda}, because the control plane is exposed through a Lambda
 * Function URL. See {@code docs/design/control-plane/sigv4-client-signing.md}.
 *
 * <p>The region is not configured separately: a Function URL host is
 * {@code <id>.lambda-url.<region>.on.aws}, so {@link #regionOf(URI)} reads it straight out of the
 * endpoint. That is deliberate — it removes the one remaining piece of deployment configuration a
 * client would otherwise need, and removes the failure mode where a correct endpoint is signed for the
 * wrong region and returns an opaque 403.
 */
public final class SigV4Signer {

    private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();
    private final AwsCredentialsProvider credentialsProvider;
    private final String service;

    public SigV4Signer() {
        this(DefaultCredentialsProvider.builder().reuseLastProviderEnabled(true).build(),
                SigV4RequestFilter.SERVICE_LAMBDA);
    }

    public SigV4Signer(AwsCredentialsProvider credentialsProvider, String service) {
        this.credentialsProvider = Objects.requireNonNull(credentialsProvider, "credentialsProvider");
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * Extracts the signing region from a Lambda Function URL.
     *
     * @throws IllegalArgumentException if the host is not a Function URL, rather than guessing a
     *         default region and failing later with a signature error that names nothing useful
     */
    public static Region regionOf(URI endpoint) {
        String host = endpoint.getHost();
        if (host == null) {
            throw new IllegalArgumentException("endpoint has no host: " + endpoint);
        }
        // <id>.lambda-url.<region>.on.aws
        String[] parts = host.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("lambda-url".equals(parts[i])) {
                return Region.of(parts[i + 1]);
            }
        }
        throw new IllegalArgumentException("not a Lambda Function URL, cannot infer the signing region "
                + "from '" + host + "'; expected <id>.lambda-url.<region>.on.aws");
    }

    /**
     * Adds the SigV4 headers for a request over {@code body} to {@code builder}.
     *
     * <p>{@code body} must be the exact bytes that will be transmitted. Passing anything else — a
     * pretty-printed variant, a different mapper's output — produces a valid-looking signature the
     * service will reject.
     */
    public void sign(HttpRequest.Builder builder, String method, URI uri, byte[] body, Region region) {
        SdkHttpRequest.Builder unsigned = SdkHttpRequest.builder()
                .method(SdkHttpMethod.fromValue(method))
                .uri(uri)
                .putHeader("Content-Type", "application/json");

        // The raw, already-encoded query string is what goes on the wire, so it is what must be
        // hashed. Our own log endpoint carries cell=NATIVE%2FARM64, so getting this wrong would break
        // resuming a stream while leaving a fresh stream working.
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                String[] kv = pair.split("=", 2);
                unsigned.appendRawQueryParameter(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }

        byte[] payload = body == null ? new byte[0] : body;
        SignedRequest signed = signer.sign(b -> {
            b.identity(credentialsProvider.resolveCredentials())
                    .request(unsigned.build())
                    .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, service)
                    .putProperty(AwsV4HttpSigner.REGION_NAME, region.id());
            if (payload.length > 0) {
                b.payload(() -> new java.io.ByteArrayInputStream(payload));
            }
        });

        for (Map.Entry<String, List<String>> header : signed.request().headers().entrySet()) {
            // Host is set by the HTTP client from the URI; java.net.http also forbids setting it.
            if (!"Host".equalsIgnoreCase(header.getKey())
                    && !"Content-Length".equalsIgnoreCase(header.getKey())) {
                builder.header(header.getKey(), String.join(",", header.getValue()));
            }
        }
    }
}
