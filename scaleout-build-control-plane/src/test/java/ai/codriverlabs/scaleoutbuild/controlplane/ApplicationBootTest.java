/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Boots the application and serves one request.
 *
 * <p>This exists because of a production failure it would have prevented. 122 unit tests passed while
 * every cold start died during init with:
 *
 * <pre>
 *   SRCFG00050: scaleout.auth.allow-dev-principal ... does not map to any root
 * </pre>
 *
 * <p>{@code @ConfigMapping(prefix = "scaleout")} makes SmallRye the owner of the whole {@code
 * scaleout.*} namespace, so a property read through a loose {@code @ConfigProperty} instead of being a
 * member of the mapping fails validation. That check runs at startup and nowhere else, and not one of
 * the existing tests started the application: they were plain unit tests over the filter and resolver
 * with mocked collaborators. The gap was not thin coverage of the config, it was that nothing
 * exercised the wiring at all.
 *
 * <p>Worse, the symptom hid itself. A Function URL with {@code InvokeMode.RESPONSE_STREAM} answers
 * {@code HTTP 200} and puts the runtime error in the response body, so a health check asserting only
 * on status code reported success while the function was crash-looping. The body assertion below is
 * the part that matters.
 */
@QuarkusTest
class ApplicationBootTest {

    /**
     * Readiness is what the Lambda Web Adapter itself polls, via {@code READINESS_CHECK_PATH}, before
     * it will forward any traffic. If this does not return UP the function never serves a request.
     */
    @Test
    void startsAndReportsReady() {
        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", is("UP"));
    }

    /**
     * The whole config mapping resolves, not just the parts a given request happens to touch. Any
     * {@code scaleout.*} property that is not a member of {@link
     * ai.codriverlabs.scaleoutbuild.controlplane.config.ControlPlaneConfig} fails the application's
     * startup validation, so reaching this assertion at all is the real check.
     */
    @Test
    void liveness() {
        given()
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", is("UP"));
    }

    /**
     * Fails closed. An unauthenticated request carries no {@code x-amzn-request-context} header, and the
     * filter must reject it rather than defaulting to some principal -- the anti-pattern noted in
     * {@code docs/design/control-plane/sigv4-client-signing.md}, where a comparable filter returns early
     * on a missing context and lets the request through unauthenticated.
     *
     * <p>Exercised here through the real JAX-RS pipeline rather than by calling the filter directly, so
     * it also proves the filter is actually registered.
     */
    @Test
    void rejectsRequestsWithNoCallerIdentity() {
        given()
                .when().get("/builds/some-build-id")
                .then()
                .statusCode(401);
    }
}
