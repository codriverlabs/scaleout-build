/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Contract for the scaleout builder control plane.
 *
 * <p>Authentication is AWS SigV4 with signing name {@code lambda}, because the service is exposed
 * through a Lambda Function URL rather than API Gateway — see
 * {@code docs/design/control-plane/scaleout-builder-control-plane.md} for why. Callers need
 * {@code lambda:InvokeFunctionUrl} and {@code lambda:InvokeFunction} on the one function; every
 * other permission (ECS, S3, CloudWatch Logs, ECR) stays with the service. Clients can use
 * {@link ai.codriverlabs.scaleoutbuild.controlplane.api.client.SigV4RequestFilter} to sign.
 *
 * <p>Ownership is enforced server-side from the verified caller identity, so none of these methods
 * takes an owner parameter. A build belonging to another principal returns {@code 404}, not
 * {@code 403}, so other developers' builds are not enumerable.
 *
 * <p><b>Why this interface exists as its own module.</b> Modelled on
 * {@code express-compute-control-plane}'s {@code ecp-api}, whose own POM describes itself as
 * "shared by server implementations, CLI clients, and MCP server". The same three consumers apply
 * here: the service implements it, the Maven plugin's service backend calls it, and an MCP server
 * can expose each method as a tool without re-deriving paths, payload shapes, or status codes from
 * prose documentation.
 *
 * <h2>Normal call sequence</h2>
 * <ol>
 *   <li>{@link #createBuild} — declare the matrix and the input manifest; receive presigned upload
 *       targets for the inputs the server does not already hold.</li>
 *   <li>{@code PUT} each {@link UploadTarget#url()} directly to S3 (not through this API).</li>
 *   <li>{@link #startBuild} — launch one task per matrix cell.</li>
 *   <li>{@link #streamLogs} for output, and/or {@link #getBuild} to poll state.
 *       {@link #heartbeat} while waiting, so the server can tell a live client from a dead one.</li>
 *   <li>{@link #listArtifacts} — presigned download URLs per produced artifact.</li>
 * </ol>
 */
@Path("/builds")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface BuildApi {

    /**
     * Registers a build and negotiates which inputs still need uploading.
     *
     * <p>Does not launch anything: the returned build is {@link BuildState#PENDING} until
     * {@link #startBuild}. The manifest is digest-based so that unchanged inputs — in practice every
     * dependency jar — are never re-uploaded.
     *
     * @return {@code 201} with a {@link CreateBuildResponse}, or {@code 400} with an
     *         {@link ErrorResponse} if the spec requests a build kind, architecture, or resource
     *         size the server does not allow
     */
    @POST
    Response createBuild(CreateBuildRequest request);

    /**
     * Launches one task per matrix cell. Returns {@code 202} immediately — provisioning is not
     * synchronous with the response.
     *
     * @return {@code 202}, {@code 409} with {@link ErrorResponse} if any manifest digest is still
     *         missing or the build is not {@link BuildState#PENDING}, or {@code 404}
     */
    @POST
    @jakarta.ws.rs.Path("/{buildId}/start")
    Response startBuild(@PathParam("buildId") String buildId);

    /** @return {@code 200} with a {@link BuildStatus}, or {@code 404} */
    @GET
    @jakarta.ws.rs.Path("/{buildId}")
    Response getBuild(@PathParam("buildId") String buildId);

    /**
     * Streams build output as Server-Sent Events, each event a {@link LogEvent}.
     *
     * <p>The stream ends with a {@link LogEvent} of type {@link LogEvent.Type#STREAM_END} carrying a
     * reason. Only {@link StreamEndReason#BUILD_TERMINAL} means "do not reconnect"; the other
     * reasons require reconnecting with {@code since} set from
     * {@link LogEvent#nextSince()} — which is a <b>per-cell map</b>, not a single timestamp. A
     * scalar watermark shared across concurrently running cells silently drops the slower cell's
     * lines, which is a bug this project has already had and fixed once (commit {@code 7782771}).
     *
     * @param cell  optional cell filter (e.g. {@code NATIVE/ARM64}); omit to interleave all cells
     * @param since optional exclusive watermark, epoch millis, for resuming
     *
     * <p><b>Documented here but deliberately not declared as a method.</b> The route is served by the
     * service's own LogStreamResource, which returns {@code Multi<LogEvent>}. When this interface
     * declared it too, the implementing BuildResource inherited the JAX-RS annotations and its stub
     * shadowed the real resource: the endpoint answered 501 in a deployed environment while every test
     * passed. The wire contract is unchanged -- {@code GET /builds/{buildId}/logs},
     * {@code text/event-stream}.
     */

    /**
     * Cancels every non-terminal cell.
     *
     * <p>Idempotent: cancelling an already-terminal build returns {@code 200} with its existing
     * state rather than an error. Returns only once {@code StopTask} has been issued for every
     * running cell — a fire-and-forget cancel would leave paid-for tasks running, which is the
     * failure {@code express-compute-control-plane}'s CLI hid by treating {@code DELETE} timeouts as
     * success.
     */
    @DELETE
    @jakarta.ws.rs.Path("/{buildId}")
    Response cancelBuild(@PathParam("buildId") String buildId);

    /**
     * Records client liveness.
     *
     * <p>Clients call this every {@link CreateBuildResponse#heartbeatIntervalSeconds()} while
     * waiting. This is not optional bookkeeping: because the client no longer supervises the tasks,
     * a client that dies without cancelling would otherwise leave Fargate tasks billing until their
     * absolute timeout. Missed heartbeats are what let the server reap them.
     */
    @POST
    @jakarta.ws.rs.Path("/{buildId}/heartbeat")
    Response heartbeat(@PathParam("buildId") String buildId);

    /** @return {@code 200} with presigned download targets per produced artifact, or {@code 404} */
    @GET
    @jakarta.ws.rs.Path("/{buildId}/artifacts")
    Response listArtifacts(@PathParam("buildId") String buildId);
}
