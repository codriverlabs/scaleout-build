/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Unit tests for {@link SigV4Signer}.
 *
 * <p>These tests use fake credentials (static, known key material) so no AWS account is required.
 * They assert on the structural properties of the signed request rather than on a specific signature
 * value, because the exact signature changes with the timestamp.
 */
class SigV4SignerTest {

    private static final Region REGION = Region.of("eu-west-1");
    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("AKIAEXAMPLE", "secretExampleKeyMaterial"));

    private final SigV4Signer signer = new SigV4Signer(CREDENTIALS, SigV4Signer.SERVICE_LAMBDA);

    // ── GET (bodyless) ────────────────────────────────────────────────────────────────────────

    @Test
    void get_adds_authorization_and_date_headers() {
        URI uri = URI.create("https://abc.lambda-url.eu-west-1.on.aws/builds?limit=20");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10));
        signer.sign(builder, "GET", uri, null, REGION);
        HttpRequest req = builder.GET().build();

        assertThat(req.headers().firstValue("Authorization")).isPresent()
                .hasValueSatisfying(auth -> {
                    assertThat(auth).startsWith("AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/");
                    assertThat(auth).contains("/eu-west-1/lambda/aws4_request");
                    assertThat(auth).contains("Signature=");
                });
        assertThat(req.headers().firstValue("X-Amz-Date")).isPresent();
    }

    @Test
    void get_does_not_set_host_header() {
        // java.net.http.HttpClient forbids setting Host, and it must not appear in the
        // signed headers we copy back because a stale value would invalidate the signature.
        URI uri = URI.create("https://abc.lambda-url.eu-west-1.on.aws/builds");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        signer.sign(builder, "GET", uri, null, REGION);
        HttpRequest req = builder.GET().build();

        assertThat(req.headers().map()).doesNotContainKey("Host");
        assertThat(req.headers().map()).doesNotContainKey("host");
    }

    // ── Query string — regression for the doubled-query-string bug ────────────────────────────

    /**
     * Regression test: query parameters must appear exactly once in the canonical request.
     *
     * <p>The bug: {@code SigV4Signer} previously called {@code appendRawQueryParameter} on the
     * {@code SdkHttpRequest.Builder} <em>in addition to</em> passing the query string in the URI.
     * {@code AwsV4HttpSigner} read both sources and doubled every parameter in the canonical
     * query string. Lambda computes the canonical request from the URI only, so the signatures
     * diverged and every GET with query params produced HTTP 403.
     */
    @Test
    void query_parameters_are_not_doubled_in_canonical_request() {
        URI withQuery = URI.create("https://abc.lambda-url.eu-west-1.on.aws/builds?limit=20");
        URI noQuery = URI.create("https://abc.lambda-url.eu-west-1.on.aws/builds");

        HttpRequest.Builder b1 = HttpRequest.newBuilder(withQuery).timeout(Duration.ofSeconds(10));
        signer.sign(b1, "GET", withQuery, null, REGION);
        String sigWith = extractSignature(b1.GET().build());

        HttpRequest.Builder b2 = HttpRequest.newBuilder(noQuery).timeout(Duration.ofSeconds(10));
        signer.sign(b2, "GET", noQuery, null, REGION);
        String sigWithout = extractSignature(b2.GET().build());

        // If the query string is signed, adding it must change the signature.
        // If it were doubled, that would also change it — but identically to if you had
        // "limit=20&limit=20" in the canonical request, which the server would never produce.
        assertThat(sigWith)
                .as("a request with ?limit=20 must produce a different signature than one without")
                .isNotEqualTo(sigWithout);
    }

    @Test
    void different_query_values_produce_different_signatures() {
        URI limit20 = URI.create("https://abc.lambda-url.eu-west-1.on.aws/builds?limit=20");
        URI limit5 = URI.create("https://abc.lambda-url.eu-west-1.on.aws/builds?limit=5");

        HttpRequest.Builder b1 = HttpRequest.newBuilder(limit20).timeout(Duration.ofSeconds(10));
        signer.sign(b1, "GET", limit20, null, REGION);

        HttpRequest.Builder b2 = HttpRequest.newBuilder(limit5).timeout(Duration.ofSeconds(10));
        signer.sign(b2, "GET", limit5, null, REGION);

        // Must produce different signatures — proves the query value is in the canonical request.
        // If this fails, the query string is not being signed at all.
        assertThat(extractSignature(b1.GET().build()))
                .as("limit=20 and limit=5 must produce different signatures")
                .isNotEqualTo(extractSignature(b2.GET().build()));
    }

    @Test
    void url_encoded_query_values_are_preserved() {
        // cell=NATIVE%2FARM64 must be signed as-is, not decoded then re-encoded
        URI withEncoded = URI.create(
                "https://abc.lambda-url.eu-west-1.on.aws/builds/id/logs?cell=NATIVE%2FARM64&since=123");
        URI withDifferentCell = URI.create(
                "https://abc.lambda-url.eu-west-1.on.aws/builds/id/logs?cell=NATIVE%2FX86_64&since=123");

        HttpRequest.Builder b1 = HttpRequest.newBuilder(withEncoded).timeout(Duration.ofSeconds(10));
        signer.sign(b1, "GET", withEncoded, null, REGION);

        HttpRequest.Builder b2 = HttpRequest.newBuilder(withDifferentCell).timeout(Duration.ofSeconds(10));
        signer.sign(b2, "GET", withDifferentCell, null, REGION);

        assertThat(extractSignature(b1.GET().build()))
                .as("ARM64 and X86_64 cell values must produce different signatures")
                .isNotEqualTo(extractSignature(b2.GET().build()));
    }

    // ── POST (with body) ──────────────────────────────────────────────────────────────────────

    @Test
    void post_with_body_produces_signature_sensitive_to_body_content() {
        URI uri = URI.create("https://abc.lambda-url.eu-west-1.on.aws/builds");
        byte[] body1 = "{\"buildKinds\":[\"native\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body2 = "{\"buildKinds\":[\"native\"],\"architectures\":[\"arm64\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        HttpRequest.Builder b1 = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        signer.sign(b1, "POST", uri, body1, REGION);

        HttpRequest.Builder b2 = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        signer.sign(b2, "POST", uri, body2, REGION);

        assertThat(extractSignature(b1.POST(HttpRequest.BodyPublishers.ofByteArray(body1)).build()))
                .as("different POST bodies must produce different signatures")
                .isNotEqualTo(extractSignature(b2.POST(HttpRequest.BodyPublishers.ofByteArray(body2)).build()));
    }

    @Test
    void post_with_body_differs_from_post_without_body() {
        URI uri = URI.create("https://abc.lambda-url.eu-west-1.on.aws/builds/id/start");
        byte[] body = "{\"x\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        HttpRequest.Builder bWithBody = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        signer.sign(bWithBody, "POST", uri, body, REGION);

        HttpRequest.Builder bNoBody = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        signer.sign(bNoBody, "POST", uri, null, REGION);

        assertThat(extractSignature(bWithBody.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()))
                .as("POST with body must differ from POST without body")
                .isNotEqualTo(extractSignature(bNoBody.POST(HttpRequest.BodyPublishers.noBody()).build()));
    }

    // ── regionOf ─────────────────────────────────────────────────────────────────────────────

    @Test
    void regionOf_extracts_region_from_function_url() {
        assertThat(SigV4Signer.regionOf(
                URI.create("https://abc123.lambda-url.eu-central-1.on.aws/")))
                .isEqualTo(Region.of("eu-central-1"));
    }

    @Test
    void regionOf_extracts_us_east_1() {
        assertThat(SigV4Signer.regionOf(
                URI.create("https://h6qanq.lambda-url.us-east-1.on.aws/")))
                .isEqualTo(Region.of("us-east-1"));
    }

    @Test
    void regionOf_throws_for_non_function_url() {
        assertThatThrownBy(() -> SigV4Signer.regionOf(
                URI.create("https://execute-api.eu-west-1.amazonaws.com/prod/")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a Lambda Function URL");
    }

    // ── helper ────────────────────────────────────────────────────────────────────────────────

    private static String extractSignature(HttpRequest req) {
        return req.headers().firstValue("Authorization")
                .map(auth -> auth.replaceAll(".*Signature=([0-9a-f]+).*", "$1"))
                .orElseThrow(() -> new AssertionError("No Authorization header"));
    }
}
