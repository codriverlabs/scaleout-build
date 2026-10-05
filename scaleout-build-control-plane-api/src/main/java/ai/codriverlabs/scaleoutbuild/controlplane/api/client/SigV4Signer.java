/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api.client;

import java.io.ByteArrayInputStream;
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
 * <p>Defaults to signing name {@code lambda}, because the control plane is exposed through a Lambda
 * Function URL with {@code AuthType: AWS_IAM} rather than API Gateway. Use {@code execute-api} only
 * if the service is ever moved behind API Gateway.
 *
 * <p>Credentials come from the standard AWS SDK default chain (environment, shared config, SSO,
 * container and instance metadata), so an MCP server, a CLI, and a Maven plugin all authenticate
 * the same way.
 *
 * <p>Uses {@link AwsV4HttpSigner} from {@code software.amazon.awssdk:http-auth-aws} — the current
 * AWS SDK v2 signer. Lambda Function URLs with {@code AuthType: AWS_IAM} accept the
 * {@code x-amz-content-sha256} header that this signer includes in {@code SignedHeaders} for all
 * requests, including bodyless GET/DELETE. This is correct per the SigV4 spec: any {@code x-amz-*}
 * header included in the request must be signed.
 */
public final class SigV4Signer {

    /** Signing service name for Lambda Function URLs. */
    public static final String SERVICE_LAMBDA = "lambda";

    private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();
    private final AwsCredentialsProvider credentialsProvider;
    private final String service;

    public SigV4Signer() {
        this(DefaultCredentialsProvider.builder().reuseLastProviderEnabled(true).build(),
                SERVICE_LAMBDA);
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
    public void sign(HttpRequest.Builder builder, String method, URI uri, byte[] body,
                     Region region) {
        byte[] payload = body == null ? new byte[0] : body;
        boolean hasBody = payload.length > 0;

        SdkHttpRequest.Builder unsigned = SdkHttpRequest.builder()
                .method(SdkHttpMethod.fromValue(method))
                .uri(uri);

        // Only include Content-Type in signed headers when there is a body.
        // For bodyless requests (GET, DELETE), adding Content-Type to the canonical
        // headers but not to the actual HTTP request causes a signature mismatch,
        // because Lambda's SigV4 validation reads what was actually sent on the wire.
        if (hasBody) {
            unsigned.putHeader("Content-Type", "application/json");
        }

        // The raw query string is already in the URI; do NOT also call appendRawQueryParameter.
        // The AWS SDK AwsV4HttpSigner reads query parameters from the URI automatically.
        // Calling appendRawQueryParameter in addition would double the query string in the
        // canonical request, producing a signature that Lambda cannot verify.

        SignedRequest signed = signer.sign(b -> {
            b.identity(credentialsProvider.resolveCredentials())
                    .request(unsigned.build())
                    .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, service)
                    .putProperty(AwsV4HttpSigner.REGION_NAME, region.id());
            if (payload.length > 0) {
                b.payload(() -> new ByteArrayInputStream(payload));
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
