/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildSpec;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildRequest;
import ai.codriverlabs.scaleoutbuild.controlplane.api.InputDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

@ExtendWith(MockitoExtension.class)
class SigV4RequestFilterTest {

    private static final StaticCredentialsProvider CREDENTIALS = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("AKIAEXAMPLE", "secretExampleKeyMaterial"));

    @Mock
    private ClientRequestContext requestContext;

    private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
    private final AtomicReference<Object> replacedEntity = new AtomicReference<>();

    private SigV4RequestFilter newFilter() {
        return new SigV4RequestFilter("eu-west-1", SigV4RequestFilter.SERVICE_LAMBDA, CREDENTIALS,
                new ObjectMapper());
    }

    private void stubContext(Object entity) {
        when(requestContext.getUri()).thenReturn(URI.create("https://abc123.lambda-url.eu-west-1.on.aws/builds"));
        when(requestContext.getMethod()).thenReturn(entity == null ? "GET" : "POST");
        when(requestContext.getStringHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(requestContext.getHeaders()).thenReturn(headers);
        when(requestContext.hasEntity()).thenReturn(entity != null);
        if (entity != null) {
            when(requestContext.getEntity()).thenReturn(entity);
            // lenient(): for String/byte[] entities setEntity is deliberately never called -- that
            // pass-through is what passesStringAndByteArrayEntitiesThroughUnchanged asserts, so an
            // unused stub here is correct behaviour rather than dead test code.
            org.mockito.Mockito.lenient().doAnswer(invocation -> {
                replacedEntity.set(invocation.getArgument(0));
                return null;
            }).when(requestContext).setEntity(any());
        }
    }

    private static CreateBuildRequest sampleRequest() {
        return new CreateBuildRequest(
                new BuildSpec(List.of("native"), List.of("x86_64", "arm64"),
                        "ai.codriverlabs.example.HelloNative", "hello-native", null, List.of(),
                        List.of(), 0, 120),
                List.of(new InputDescriptor("app.jar", "a1b2c3", 5060)),
                null, "test/1.0");
    }

    @Test
    void signsTheRequestAndSetsAnAuthorizationHeader() {
        stubContext(sampleRequest());

        newFilter().filter(requestContext);

        assertThat(headers.getFirst("Authorization")).asString()
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/")
                .contains("/eu-west-1/lambda/aws4_request")
                .contains("Signature=");
        // Host must not be copied back: the HTTP client sets it, and a stale value would break the
        // very signature it is part of.
        assertThat(headers.keySet()).doesNotContain("Host");
    }

    /**
     * A POJO entity must be serialized here, signed, and written back — otherwise the signature
     * covers an empty payload while a JSON body is transmitted, and the service rejects every
     * request with a body. See this filter's class javadoc.
     */
    @Test
    void serializesAPojoEntityAndSignsThoseExactBytes() throws Exception {
        CreateBuildRequest request = sampleRequest();
        stubContext(request);

        newFilter().filter(requestContext);

        byte[] expected = new ObjectMapper().writeValueAsBytes(request);
        assertThat(replacedEntity.get())
                .as("the entity must be replaced with the bytes that were signed")
                .isInstanceOf(byte[].class);
        assertThat((byte[]) replacedEntity.get()).isEqualTo(expected);
    }

    /**
     * Regression guard for the payload-signing bug this filter exists to avoid: if the body were
     * ignored and an empty payload signed instead, a request with a body and one without would
     * produce the same signature.
     */
    @Test
    void producesADifferentSignatureForABodyThanForNoBody() {
        stubContext(sampleRequest());
        newFilter().filter(requestContext);
        String withBody = String.valueOf(headers.getFirst("Authorization"));

        headers.clear();
        replacedEntity.set(null);
        org.mockito.Mockito.reset(requestContext);
        stubContext(null);
        newFilter().filter(requestContext);
        String withoutBody = String.valueOf(headers.getFirst("Authorization"));

        assertThat(withBody).isNotEqualTo(withoutBody);
    }

    @Test
    void passesStringAndByteArrayEntitiesThroughUnchanged() {
        stubContext("{\"already\":\"serialized\"}");

        newFilter().filter(requestContext);

        assertThat(replacedEntity.get())
                .as("an already-serialized entity must not be rewritten")
                .isNull();
        assertThat(headers.getFirst("Authorization")).asString().contains("Signature=");
    }

    @Test
    void signsQueryParametersSoResumingAStreamIsNotRejected() {
        when(requestContext.getUri()).thenReturn(URI.create(
                "https://abc123.lambda-url.eu-west-1.on.aws/builds/01J8/logs?cell=NATIVE%2FARM64&since=1789867601234"));
        when(requestContext.getMethod()).thenReturn("GET");
        when(requestContext.getStringHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(requestContext.getHeaders()).thenReturn(headers);
        when(requestContext.hasEntity()).thenReturn(false);

        newFilter().filter(requestContext);

        String authorization = String.valueOf(headers.getFirst("Authorization"));
        assertThat(authorization).contains("Signature=");
        // The signature must be sensitive to the query string, or a resumed stream would be
        // indistinguishable from a fresh one to the signer.
        headers.clear();
        when(requestContext.getUri()).thenReturn(URI.create(
                "https://abc123.lambda-url.eu-west-1.on.aws/builds/01J8/logs?cell=NATIVE%2FX86_64&since=1789867601234"));
        newFilter().filter(requestContext);
        assertThat(String.valueOf(headers.getFirst("Authorization"))).isNotEqualTo(authorization);
    }

    @Test
    void rejectsAMissingRegionWithAnActionableMessage() {
        String savedRegion = System.getProperty("scaleout.sigv4.region");
        System.clearProperty("scaleout.sigv4.region");
        try {
            if (System.getenv("AWS_REGION") == null && System.getenv("AWS_DEFAULT_REGION") == null) {
                assertThat(org.junit.jupiter.api.Assertions
                        .assertThrows(IllegalStateException.class, SigV4RequestFilter::new))
                        .hasMessageContaining("scaleout.sigv4.region");
            }
        } finally {
            if (savedRegion != null) {
                System.setProperty("scaleout.sigv4.region", savedRegion);
            }
        }
    }

    @Test
    void utf8BodiesAreSignedByTheirByteLengthNotCharacterCount() throws Exception {
        String body = "{\"imageName\":\"héllo-nativé\"}";
        stubContext(body);

        newFilter().filter(requestContext);

        assertThat(body.getBytes(StandardCharsets.UTF_8).length)
                .as("precondition: the body is longer in bytes than in characters")
                .isGreaterThan(body.length());
        assertThat(headers.getFirst("Authorization")).asString().contains("Signature=");
    }
}
