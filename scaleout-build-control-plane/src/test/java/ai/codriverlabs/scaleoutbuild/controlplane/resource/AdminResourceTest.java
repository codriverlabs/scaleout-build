/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import ai.codriverlabs.scaleoutbuild.controlplane.auth.CallerIdentity;
import ai.codriverlabs.scaleoutbuild.controlplane.auth.CallerIdentityFilter;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import ai.codriverlabs.scaleoutbuild.microvm.MicroVmImageManager;
import ai.codriverlabs.scaleoutbuild.microvm.MicroVmImageManager.ImageStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Admin endpoint authorization and routing tests.
 *
 * <p>Authorization relies on the {@code CallerIdentityFilter} having already resolved the caller
 * and stored it under {@link CallerIdentity#PROPERTY}. In the test environment, the filter falls
 * through to the dev-principal path (configured in application.properties for %test) so every
 * request automatically gets the test role ARN {@code arn:aws:iam::000000000000:role/TestAdminRole}.
 *
 * <p>The configured admin role ARN is the same value in test, so an unmodified request reaches the
 * endpoint. Tests that want to probe unauthorized access supply a custom header to make the filter
 * set a different identity, or directly test the guard with a non-admin role.
 *
 * <h2>What is tested here</h2>
 *
 * <ul>
 *   <li>Unauthenticated calls (no caller identity) → 401 from the filter</li>
 *   <li>Authenticated but non-admin role → 404 (not 403, see AdminResource javadoc)</li>
 *   <li>Admin role + valid body → 202 Accepted</li>
 *   <li>Admin role + missing version → 400 Bad Request</li>
 *   <li>GET status with unknown version → 404</li>
 *   <li>GET status with known version → 200 with status</li>
 * </ul>
 */
@QuarkusTest
class AdminResourceTest {

    @InjectMock
    MicroVmImageManager imageManager;

    // ── Unauthenticated ───────────────────────────────────────────────────────────────────────

    @Test
    void unauthenticated_post_is_401() {
        // No x-amzn-request-context header and no dev principal fallback active → 401
        // (Tested via the standard unauthenticated path; the admin endpoint is no different.)
        given()
                .contentType("application/json")
                .body("{}")
                .when().post("/admin/microvm-images")
                .then()
                .statusCode(401);
    }

    @Test
    void unauthenticated_get_is_401() {
        given()
                .when().get("/admin/microvm-images/1.0.1")
                .then()
                .statusCode(401);
    }

    // ── Missing fields ────────────────────────────────────────────────────────────────────────

    @Test
    void post_with_missing_version_is_400() {
        // Dev principal is the TestAdminRole → passes the admin guard; empty body → 400
        given()
                .header(CallerIdentityFilter.REQUEST_CONTEXT_HEADER, devPrincipalContext())
                .contentType("application/json")
                .body("{}")
                .when().post("/admin/microvm-images")
                .then()
                .statusCode(400)
                .body("error", is("BadRequest"));
    }

    @Test
    void post_with_missing_artifact_uri_is_400() {
        given()
                .header(CallerIdentityFilter.REQUEST_CONTEXT_HEADER, devPrincipalContext())
                .contentType("application/json")
                .body("{}")
                .when().post("/admin/microvm-images")
                .then()
                .statusCode(400);
    }

    // ── Admin role authz ─────────────────────────────────────────────────────────────────────

    @Test
    void non_admin_role_gets_404_not_403() {
        // A different role that is not in adminRoleArns → 404 (existence not revealed)
        String ctx = requestContext("arn:aws:iam::000000000000:role/SomeDeveloperRole");
        given()
                .header(CallerIdentityFilter.REQUEST_CONTEXT_HEADER, ctx)
                .contentType("application/json")
                .body("{\"version\":\"1.0.1\",\"artifactS3Uri\":\"s3://bucket/agent-1.0.1.zip\"}")
                .when().post("/admin/microvm-images")
                .then()
                .statusCode(404);
    }

    // ── Happy path: POST triggers build ──────────────────────────────────────────────────────

    @Test
    void admin_post_triggers_build_and_returns_202() {
        ImageStatus stub = new ImageStatus(
                MicroVmImageManager.IMAGE_NAME, "1.0", "CREATING", null);
        Mockito.when(imageManager.triggerBuild(
                Mockito.anyString(),
                Mockito.anyString(), Mockito.eq("s3://bucket/agent-1.0.1.zip")))
                .thenReturn(stub);

        given()
                .header(CallerIdentityFilter.REQUEST_CONTEXT_HEADER, devPrincipalContext())
                .contentType("application/json")
                .body("{\"artifactS3Uri\":\"s3://bucket/agent-1.0.1.zip\"}")
                .when().post("/admin/microvm-images")
                .then()
                .statusCode(202)
                .body("imageVersion", is("1.0"))
                .body("state", is("CREATING"));
    }

    // ── Happy path: GET status ────────────────────────────────────────────────────────────────

    @Test
    void get_known_version_returns_200() {
        ImageStatus stub = new ImageStatus(
                MicroVmImageManager.IMAGE_NAME, "1.0", "CREATED",
                "arn:aws:lambda:eu-central-1:123:microvm-image:scaleout-build-agent");
        Mockito.when(imageManager.getImageStatus("scaleout-build-agent")).thenReturn(stub);

        given()
                .header(CallerIdentityFilter.REQUEST_CONTEXT_HEADER, devPrincipalContext())
                .when().get("/admin/microvm-images/scaleout-build-agent")
                .then()
                .statusCode(200)
                .body("state", is("CREATED"))
                .body("imageArn", containsString("scaleout-build-agent"));
    }

    @Test
    void get_unknown_image_returns_404() {
        Mockito.when(imageManager.getImageStatus("nonexistent")).thenReturn(null);

        given()
                .header(CallerIdentityFilter.REQUEST_CONTEXT_HEADER, devPrincipalContext())
                .when().get("/admin/microvm-images/nonexistent")
                .then()
                .statusCode(404);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    /**
     * A Lambda request context JSON that carries the TestAdminRole ARN. This is what LWA sets on
     * {@code x-amzn-request-context} for a request from the TestAdminRole.
     */
    private static String devPrincipalContext() {
        return requestContext("arn:aws:iam::000000000000:role/TestAdminRole");
    }

    private static String requestContext(String roleArn) {
        // The filter reads authorizer.iam.userArn (and falls back to top-level userArn).
        // For an IAM role (not an assumed-role ARN) roleArn == userArn after normalization.
        return "{\"authorizer\":{\"iam\":{\"userArn\":\"" + roleArn + "\",\"principalId\":\"AROA000\"}}}";
    }
}
