/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.resource;

import ai.codriverlabs.scaleoutbuild.controlplane.api.AdminApi;
import ai.codriverlabs.scaleoutbuild.controlplane.api.AdminApi.ImageBuildResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.AdminApi.TriggerImageBuildRequest;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ErrorResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.auth.CallerIdentity;
import ai.codriverlabs.scaleoutbuild.controlplane.config.ControlPlaneConfig;
import ai.codriverlabs.scaleoutbuild.microvm.MicroVmImageManager;
import ai.codriverlabs.scaleoutbuild.microvm.MicroVmImageManager.ImageStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Admin operations: triggering MicroVM image builds.
 *
 * <h2>Authorization</h2>
 *
 * All endpoints under {@code /admin} require the caller's normalized role ARN to appear in
 * {@link ControlPlaneConfig.MicroVm#adminRoleArns()}. The check uses exact ARN matching, not prefix
 * or wildcard matching — partial matching against a caller-supplied field is a bypass surface. See
 * the config interface for the full security rationale.
 *
 * <p>These roles are typically IAM Identity Center permission set roles, e.g.
 * {@code arn:aws:iam::123456789012:role/AWSReservedSSO_ScaleoutBuildAdmins_abc123}. The
 * {@link ai.codriverlabs.scaleoutbuild.controlplane.auth.CallerIdentityResolver} normalizes
 * assumed-role ARNs by stripping the session name, so the same human calling via Identity Center
 * always resolves to the same role ARN regardless of session.
 *
 * <h2>Endpoints</h2>
 *
 * {@code POST /admin/microvm-images} — triggers a new MicroVM image build for the given version.
 * Returns 202 Accepted immediately; the image build runs asynchronously (~10–15 minutes).
 *
 * {@code GET /admin/microvm-images/{version}} — returns the current build status for a version.
 *
 * <h2>Non-goals</h2>
 *
 * This resource does not upload the code artifact ZIP to S3. That is a CI step performed before
 * calling this endpoint. The request body carries only the S3 URI and the version string.
 */
@Path("/admin")
public class AdminResource {

    private static final Logger LOG = Logger.getLogger(AdminResource.class);

    @Inject
    ControlPlaneConfig config;

    @Inject
    MicroVmImageManager imageManager;

    @Context
    jakarta.ws.rs.container.ContainerRequestContext requestContext;

    // ── POST /admin/microvm-images ────────────────────────────────────────────────────────────

    @POST
    @Path("/microvm-images")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response triggerImageBuild(TriggerImageBuildRequest body) {
        CallerIdentity caller = (CallerIdentity) requestContext.getProperty(CallerIdentity.PROPERTY);

        Response guardResponse = guardAdmin(caller);
        if (guardResponse != null) {
            return guardResponse;
        }

        if (body == null || isBlank(body.artifactS3Uri())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("BadRequest",
                            "artifactS3Uri is required", null))
                    .build();
        }

        ControlPlaneConfig.MicroVm microVmConfig = config.microVm();
        if (microVmConfig.buildRoleArn().isEmpty()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(ErrorResponse.of("NotConfigured",
                            "MicroVM build role is not configured on this deployment", null))
                    .build();
        }

        String region = regionFromEnv();
        LOG.infof("Admin %s triggering MicroVM image build from %s",
                caller.roleArn(), body.artifactS3Uri());

        try {
            ImageStatus status = imageManager.triggerBuild(
                    region,
                    microVmConfig.buildRoleArn().get(),
                    body.artifactS3Uri());
            return Response.accepted(toResponse(status)).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to trigger MicroVM image build");
            return Response.serverError()
                    .entity(ErrorResponse.of("BuildTriggerFailed", e.getMessage(), null))
                    .build();
        }
    }

    // ── GET /admin/microvm-images/{version} ───────────────────────────────────────────────────

    @GET
    @Path("/microvm-images/{version}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getImageStatus(@PathParam("version") String version) {
        CallerIdentity caller = (CallerIdentity) requestContext.getProperty(CallerIdentity.PROPERTY);

        Response guardResponse = guardAdmin(caller);
        if (guardResponse != null) {
            return guardResponse;
        }

        ImageStatus status = imageManager.getImageStatus(version);
        if (status == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("NotFound",
                            "No MicroVM image '" + version + "' found", null))
                    .build();
        }
        return Response.ok(toResponse(status)).build();
    }

    // ── Authorization ─────────────────────────────────────────────────────────────────────────

    /**
     * Checks that the caller's normalized role ARN is in the configured admin role list.
     *
     * <p>Returns a non-null {@link Response} to abort with if the check fails, or {@code null} if
     * the caller is authorized to proceed.
     *
     * <p><b>Why exact ARN matching, not prefix or wildcard:</b> the caller's {@code roleArn} is
     * normalized by {@link ai.codriverlabs.scaleoutbuild.controlplane.auth.CallerIdentityResolver}
     * — assumed-role session names are stripped, so {@code roleArn} is always a stable
     * {@code arn:aws:iam::ACCOUNT:role/ROLE_NAME}. Exact matching against that stable value means
     * the authorization list contains exactly the roles that are permitted, with no substring that
     * a crafted role name could match.
     */
    private Response guardAdmin(CallerIdentity caller) {
        List<String> adminRoles = config.microVm().adminRoleArns();
        if (adminRoles == null || adminRoles.isEmpty() || (adminRoles.size() == 1 && adminRoles.get(0).isBlank())) {
            LOG.warn("Admin endpoint called but no admin role ARNs are configured");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(ErrorResponse.of("NotConfigured",
                            "Admin operations are not enabled on this deployment", null))
                    .build();
        }
        if (!adminRoles.contains(caller.roleArn())) {
            LOG.warnf("Rejecting admin call from %s (not in admin role list)", caller.roleArn());
            // 404, not 403: returning 403 reveals that the endpoint exists and the caller is not
            // authorized, which is unnecessary information. 404 is consistent with what any
            // non-existent path returns and gives an attacker no useful signal.
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("NotFound", "Not found", null))
                    .build();
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private static ImageBuildResponse toResponse(ImageStatus status) {
        return new ImageBuildResponse(
                status.imageName(), status.imageVersion(), status.state(), status.imageArn());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String regionFromEnv() {
        String region = System.getenv("AWS_REGION");
        return (region != null && !region.isBlank()) ? region : "us-east-1";
    }
}
