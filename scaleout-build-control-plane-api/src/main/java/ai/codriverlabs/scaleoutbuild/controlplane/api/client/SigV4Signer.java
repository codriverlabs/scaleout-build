/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api.client;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * SigV4 signer for Lambda Function URL requests using JDK crypto directly.
 *
 * <p>The AWS SDK's {@code AwsV4HttpSigner} always includes {@code x-amz-content-sha256} in
 * {@code SignedHeaders}, even for bodyless requests. Lambda Function URLs with
 * {@code AuthType: AWS_IAM} reject signatures that include that header for GET/DELETE requests —
 * they only accept {@code SignedHeaders=host;x-amz-date}. This implementation produces exactly
 * that, matching what {@code curl --aws-sigv4} produces.
 *
 * <p>For requests with a body (POST, PUT), the body hash is included in the canonical request
 * and {@code x-amz-content-sha256} is also included in signed headers, matching AWS's requirements
 * for Lambda Function URL with IAM auth.
 *
 * <h2>References</h2>
 * <ul>
 *   <li>AWS SigV4 spec: https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html
 *   <li>Lambda Function URL auth: https://docs.aws.amazon.com/lambda/latest/dg/urls-auth.html
 * </ul>
 */
public final class SigV4Signer {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE_LAMBDA = "lambda";
    private static final String EMPTY_HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final AwsCredentialsProvider credentialsProvider;
    private final String service;

    public SigV4Signer() {
        this(DefaultCredentialsProvider.builder().reuseLastProviderEnabled(true).build(),
                SERVICE_LAMBDA);
    }

    public SigV4Signer(AwsCredentialsProvider credentialsProvider, String service) {
        this.credentialsProvider = credentialsProvider;
        this.service = service;
    }

    /**
     * Extracts the signing region from a Lambda Function URL.
     * Host format: {@code <id>.lambda-url.<region>.on.aws}
     */
    public static Region regionOf(URI endpoint) {
        String host = endpoint.getHost();
        if (host == null) {
            throw new IllegalArgumentException("endpoint has no host: " + endpoint);
        }
        String[] parts = host.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("lambda-url".equals(parts[i])) {
                return Region.of(parts[i + 1]);
            }
        }
        throw new IllegalArgumentException("not a Lambda Function URL, cannot infer the signing "
                + "region from '" + host + "'; expected <id>.lambda-url.<region>.on.aws");
    }

    /**
     * Signs the request, adding {@code Authorization}, {@code X-Amz-Date}, and (if a session
     * credential) {@code X-Amz-Security-Token} headers to {@code builder}.
     *
     * <p>For bodyless requests (body == null or empty): {@code SignedHeaders=host;x-amz-date}.
     * For requests with a body: {@code SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date}.
     */
    public void sign(HttpRequest.Builder builder, String method, URI uri, byte[] body,
                     Region region) {
        AwsCredentials creds = credentialsProvider.resolveCredentials();
        Instant now = Instant.now();
        String dateTime = DATE_TIME_FMT.format(now);
        String date = DATE_FMT.format(now);

        boolean hasBody = body != null && body.length > 0;
        String bodyHash = hasBody ? sha256hex(body) : EMPTY_HASH;

        // ── Canonical request ─────────────────────────────────────────────────────────────────
        // Signed headers: always host + x-amz-date; add content-type + x-amz-content-sha256
        // only when there is a body.
        String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String canonicalHeaders;
        String signedHeaders;
        if (hasBody) {
            canonicalHeaders = "content-type:application/json\n"
                    + "host:" + host + "\n"
                    + "x-amz-content-sha256:" + bodyHash + "\n"
                    + "x-amz-date:" + dateTime + "\n";
            signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date";
        } else {
            canonicalHeaders = "host:" + host + "\n"
                    + "x-amz-date:" + dateTime + "\n";
            signedHeaders = "host;x-amz-date";
        }

        String canonicalQueryString = canonicalQueryString(uri.getRawQuery());
        String canonicalRequest = method.toUpperCase(Locale.ROOT) + "\n"
                + canonicalPath(uri) + "\n"
                + canonicalQueryString + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + bodyHash;

        // ── String to sign ────────────────────────────────────────────────────────────────────
        String credentialScope = date + "/" + region.id() + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
                + dateTime + "\n"
                + credentialScope + "\n"
                + sha256hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        // ── Signing key ───────────────────────────────────────────────────────────────────────
        byte[] signingKey = hmacSha256(
                hmacSha256(
                        hmacSha256(
                                hmacSha256(
                                        ("AWS4" + creds.secretAccessKey()).getBytes(StandardCharsets.UTF_8),
                                        date),
                                region.id()),
                        service),
                "aws4_request");
        String signature = HexFormat.of().formatHex(hmacSha256(signingKey,
                stringToSign.getBytes(StandardCharsets.UTF_8)));

        // ── Authorization header ──────────────────────────────────────────────────────────────
        String authorization = ALGORITHM
                + " Credential=" + creds.accessKeyId() + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        builder.header("X-Amz-Date", dateTime);
        builder.header("Authorization", authorization);
        if (hasBody) {
            builder.header("Content-Type", "application/json");
            builder.header("x-amz-content-sha256", bodyHash);
        }
        // Session token (STS temporary credentials or IAM Identity Center)
        if (creds instanceof AwsSessionCredentials session) {
            builder.header("X-Amz-Security-Token", session.sessionToken());
        }
    }

    // ── Crypto helpers ────────────────────────────────────────────────────────────────────────

    private static String sha256hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        return hmacSha256(key, data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    private static String canonicalPath(URI uri) {
        String path = uri.getRawPath();
        return (path == null || path.isEmpty()) ? "/" : path;
    }

    private static String canonicalQueryString(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        // Sort by parameter name, then by value.
        // Our queries are simple (limit=N) with no special characters that need re-encoding.
        return java.util.Arrays.stream(rawQuery.split("&"))
                .sorted()
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "&" + b);
    }
}
