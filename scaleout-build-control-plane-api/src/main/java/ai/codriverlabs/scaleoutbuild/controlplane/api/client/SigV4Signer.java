/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api.client;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

/**
 * Signs {@link java.net.http.HttpRequest}s with AWS SigV4, for callers of the control-plane
 * Function URL.
 *
 * <h2>Why {@link Aws4Signer} rather than {@link software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner}</h2>
 *
 * <p>{@code AwsV4HttpSigner} (the newer signer from {@code http-auth-aws}) always includes
 * {@code x-amz-content-sha256} in {@code SignedHeaders}, even for bodyless GET/DELETE requests —
 * both with payload signing enabled (hash {@code e3b0c4...}) and disabled ({@code UNSIGNED-PAYLOAD}).
 *
 * <p>Lambda Function URLs with {@code AuthType: AWS_IAM} validate incoming SigV4 signatures
 * expecting {@code SignedHeaders=host;x-amz-date} for bodyless requests. They reject signatures
 * computed with additional headers in {@code SignedHeaders}, returning HTTP 403 "The request
 * signature we calculated does not match". This is a Lambda Function URL service behavior — it is
 * not configurable from the client side.
 *
 * <p>{@code Aws4Signer} (the v2 SDK signer from {@code auth}) does not include
 * {@code x-amz-content-sha256} for bodyless requests, producing {@code SignedHeaders=host;x-amz-date}
 * — exactly what Lambda Function URLs accept. This is the same signer used by
 * {@code express-compute-control-plane}'s CLI for the same reason.
 *
 * <p>{@code Aws4Signer} is marked {@code @Deprecated} in the SDK. The deprecation is a signal
 * that AWS prefers callers to use {@code AwsV4HttpSigner}, but there is no migration path for
 * Lambda Function URL callers until the service is updated to accept {@code x-amz-content-sha256}
 * in {@code SignedHeaders} for bodyless requests. This usage will be revisited if the Lambda
 * service behavior changes or the SDK removes {@code Aws4Signer}.
 *
 * @see <a href="https://docs.aws.amazon.com/lambda/latest/dg/urls-auth.html">Lambda Function URL auth</a>
 */
public final class SigV4Signer {

    /** Signing service name for Lambda Function URLs. */
    public static final String SERVICE_LAMBDA = "lambda";

    private final Aws4Signer signer = Aws4Signer.create();
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
     * Adds SigV4 headers to {@code builder} for a request over {@code body}.
     *
     * <p>{@code body} must be the exact bytes that will be transmitted. Passing anything else —
     * a pretty-printed variant, a different mapper's output — produces a valid-looking signature
     * the service will reject.
     */
    public void sign(HttpRequest.Builder builder, String method, URI uri, byte[] body,
                     Region region) {
        byte[] payload = body == null ? new byte[0] : body;

        var sdkBuilder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(method))
                .uri(uri);

        if (payload.length > 0) {
            sdkBuilder.putHeader("Content-Type", "application/json");
            sdkBuilder.contentStreamProvider(() -> new ByteArrayInputStream(payload));
        }

        // The raw, already-encoded query string is what goes on the wire, so it is what must be
        // hashed. Our own log endpoint carries cell=NATIVE%2FARM64, so getting this wrong would
        // break resuming a stream while leaving a fresh stream working.
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                String[] kv = pair.split("=", 2);
                sdkBuilder.putRawQueryParameter(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }

        var signed = signer.sign(sdkBuilder.build(),
                Aws4SignerParams.builder()
                        .awsCredentials(credentialsProvider.resolveCredentials())
                        .signingRegion(region)
                        .signingName(service)
                        .build());

        signed.headers().forEach((name, values) -> {
            // Host is set by the HTTP client from the URI; java.net.http also forbids setting it.
            if (!"Host".equalsIgnoreCase(name)) {
                values.forEach(value -> builder.header(name, value));
            }
        });
    }
}
