/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.auth;

import ai.codriverlabs.scaleoutbuild.controlplane.api.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import ai.codriverlabs.scaleoutbuild.controlplane.config.ControlPlaneConfig;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Establishes the verified caller identity for every request, or rejects the request.
 *
 * <h2>Where the identity comes from</h2>
 *
 * This service runs as a plain HTTP server behind the AWS Lambda Web Adapter, so there is no Lambda
 * event object to read. LWA forwards what would have been {@code event.requestContext} as the
 * {@code x-amzn-request-context} request header. The verified caller is {@code userArn} inside that
 * JSON, placed there by the Lambda service after it validated the request's SigV4 signature against
 * the Function URL's {@code AWS_IAM} auth type.
 *
 * <h2>Why this fails closed</h2>
 *
 * That header is trustworthy <em>only</em> because LWA sets it. Nothing about an HTTP header is
 * inherently authentic: if this service were ever reachable by any path that does not go through the
 * Function URL — a misconfigured ALB, a port left open, a local run — a client could simply send its
 * own header, or omit it.
 *
 * Treating a missing header as "anonymous" and continuing is therefore a privilege-escalation path,
 * not a convenience. It would let an unauthenticated caller create builds owned by {@code null} and
 * then read them all back by asking for the same {@code null} owner. So a missing or unparseable
 * header is a {@code 401} and the request is aborted here, before any resource method runs.
 *
 * Local development, which has no LWA in front, supplies an identity through
 * {@code scaleout.auth.dev-principal-arn} and must set {@code scaleout.auth.allow-dev-principal}
 * explicitly. That switch defaults to false, so a production deployment that forgets to unset it
 * still refuses unsigned requests rather than trusting a configured principal.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class CallerIdentityFilter implements ContainerRequestFilter {

    /** Header the Lambda Web Adapter populates with the Lambda request context JSON. */
    public static final String REQUEST_CONTEXT_HEADER = "x-amzn-request-context";

    private static final Logger LOG = Logger.getLogger(CallerIdentityFilter.class);

    @Inject
    ObjectMapper objectMapper;

    /*
     * Instance<>, not a direct @Inject of the mapping.
     *
     * A JAX-RS filter is instantiated during RESTEasy Reactive's deployment
     * (RuntimeInterceptorDeployment.createInterceptorInstances), which runs before runtime config
     * mappings are registered. Injecting ControlPlaneConfig directly therefore resolves too early and
     * fails the whole REST deployment with "SRCFG00027: Could not find a mapping" -- an error that
     * names the config class and says nothing about ordering.
     *
     * Instance<> defers resolution to the first get(), which happens inside filter() on a real request,
     * long after startup. ApplicationBootTest covers this; it is what found it.
     */
    @Inject
    Instance<ControlPlaneConfig> configInstance;

    private ControlPlaneConfig.Auth auth() {
        return configInstance.get().auth();
    }


    /** Paths served without a caller identity: health checks, which the Lambda runtime itself polls. */
    private static boolean isUnauthenticatedPath(String path) {
        return path.startsWith("q/health") || path.startsWith("/q/health");
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (isUnauthenticatedPath(ctx.getUriInfo().getPath())) {
            return;
        }

        String header = ctx.getHeaderString(REQUEST_CONTEXT_HEADER);
        CallerIdentityResolver resolver = new CallerIdentityResolver(objectMapper);

        try {
            ctx.setProperty(CallerIdentity.PROPERTY, resolver.resolve(header));
            return;
        } catch (UnauthenticatedException e) {
            if (auth().allowDevPrincipal() && auth().devPrincipalArn().filter(a -> !a.isBlank()).isPresent()) {
                LOG.warnf("Using configured dev principal %s because %s (NEVER enable this in a "
                        + "deployed environment)", auth().devPrincipalArn().orElseThrow(), e.getMessage());
                ctx.setProperty(CallerIdentity.PROPERTY,
                        CallerIdentityResolver.of(auth().devPrincipalArn().orElseThrow(), null, null));
                return;
            }
            // Deliberately not echoing the reason to the client: whether the header was absent
            // versus malformed is useful to an attacker probing the boundary and useless to a
            // legitimate caller, whose SDK sets it automatically.
            LOG.debugf("Rejecting unauthenticated request to %s: %s", ctx.getUriInfo().getPath(),
                    e.getMessage());
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(ErrorResponse.of("Unauthenticated",
                            "Request carried no verified caller identity.", null))
                    .build());
        }
    }
}
