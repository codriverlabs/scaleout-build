/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.resource;

import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildApi;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildRequest;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ErrorResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.auth.CallerIdentity;
import ai.codriverlabs.scaleoutbuild.controlplane.service.BuildService;
import ai.codriverlabs.scaleoutbuild.controlplane.service.InvalidRequestException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Providers;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Implements {@link BuildApi}.
 *
 * <p>Two conventions applied uniformly, both deliberate:
 *
 * <ul>
 *   <li><b>A build the caller does not own is {@code 404}, never {@code 403}.</b> A distinguishable
 *       "forbidden" would let one developer enumerate another's build ids by probing status codes.
 *       {@code BuildRepository#findOwned} returns empty for both "absent" and "not yours", so the two
 *       are indistinguishable by construction rather than by remembering to conflate them here.</li>
 *   <li><b>The caller identity is read from the request context, never from the request body.</b> It
 *       was established by {@code CallerIdentityFilter} from the signed Lambda request context; a
 *       caller-supplied owner field would be trivially forgeable.</li>
 * </ul>
 */
/*
 * @Path is declared here as well as on BuildApi, and it is load-bearing.
 *
 * RESTEasy Reactive discovers resources by scanning for @Path on the CLASS. Inheriting it from an
 * implemented interface is not sufficient: without this annotation the class is not registered as a
 * resource at all and every /builds request answers 404 -- no warning at build time, nothing in the
 * logs, just an API that does not exist.
 *
 * Found by ApplicationBootTest asserting 401 on /builds/{id} and getting 404. The deploy before it
 * looked fine, because a health check never touches a resource route.
 */
@Path("/builds")
public class BuildResource implements BuildApi {

    private static final Logger LOG = Logger.getLogger(BuildResource.class);

    @Inject
    BuildService buildService;

    @Context
    jakarta.ws.rs.container.ContainerRequestContext requestContext;

    @SuppressWarnings("unused")
    @Context
    Providers providers;

    private CallerIdentity caller() {
        return (CallerIdentity) requestContext.getProperty(CallerIdentity.PROPERTY);
    }

    @Override
    public Response createBuild(CreateBuildRequest request) {
        if (request == null) {
            return badRequest("InvalidRequest", "a request body is required");
        }
        try {
            return Response.status(Response.Status.CREATED)
                    .entity(buildService.create(caller(), request))
                    .build();
        } catch (InvalidRequestException e) {
            return badRequest("InvalidRequest", e.getMessage());
        }
    }

    @Override
    public Response startBuild(String buildId) {
        try {
            Optional<?> status = buildService.start(caller(), buildId);
            return status.<Response>map(s -> Response.accepted().entity(s).build())
                    .orElseGet(() -> notFound(buildId));
        } catch (InvalidRequestException e) {
            // 409, not 400: the request is well-formed, the build is simply not in a startable state.
            return Response.status(Response.Status.CONFLICT)
                    .entity(ErrorResponse.of("InvalidState", e.getMessage(), null))
                    .build();
        }
    }

    @Override
    public Response getBuild(String buildId) {
        return buildService.status(caller(), buildId)
                .<Response>map(s -> Response.ok(s).build())
                .orElseGet(() -> notFound(buildId));
    }

    @Override
    public Response streamLogs(String buildId, String cell, Long since) {
        // Implemented by LogStreamResource, which needs SSE-specific annotations this interface
        // method cannot carry. Declared here only to satisfy the contract.
        return Response.status(Response.Status.NOT_IMPLEMENTED)
                .entity(ErrorResponse.of("NotImplemented",
                        "Log streaming is served by GET /builds/{buildId}/logs on the streaming "
                                + "resource.", null))
                .build();
    }

    @Override
    public Response cancelBuild(String buildId) {
        return buildService.cancel(caller(), buildId)
                .<Response>map(s -> Response.ok(s).build())
                .orElseGet(() -> notFound(buildId));
    }

    @Override
    public Response heartbeat(String buildId) {
        return buildService.heartbeat(caller(), buildId)
                .<Response>map(s -> Response.ok(s).build())
                .orElseGet(() -> notFound(buildId));
    }

    @Override
    public Response listArtifacts(String buildId) {
        return buildService.artifacts(caller(), buildId)
                .<Response>map(a -> Response.ok(a).build())
                .orElseGet(() -> notFound(buildId));
    }

    private Response notFound(String buildId) {
        LOG.debugf("Build %s not found for caller %s", buildId,
                caller() == null ? "<none>" : caller().ownerKey());
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ErrorResponse.of("BuildNotFound", "No build with id " + buildId, null))
                .build();
    }

    private Response badRequest(String code, String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ErrorResponse.of(code, message, null))
                .build();
    }
}
