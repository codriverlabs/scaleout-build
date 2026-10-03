/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.mcp;

import ai.codriverlabs.scaleoutbuild.cli.CliException;
import ai.codriverlabs.scaleoutbuild.cli.ControlPlaneHttpClient;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * MCP tools for the scaleout-build control plane.
 *
 * <p>Each {@link Tool}-annotated method becomes an MCP tool that AI assistants can call.
 * The Quarkus MCP Server extension handles transport (stdio), JSON-RPC negotiation, and
 * argument deserialization. The tools themselves just call the control plane HTTP API.
 *
 * <h2>Configuration</h2>
 *
 * <ul>
 *   <li>{@code SCALEOUT_ENDPOINT} — the Lambda Function URL (required)</li>
 *   <li>{@code AWS_REGION} — the signing region (default: {@code eu-central-1})</li>
 * </ul>
 *
 * <h2>MCP client config (Kiro / Claude Desktop)</h2>
 *
 * <pre>
 * {
 *   "mcpServers": {
 *     "scaleout-build": {
 *       "command": "java",
 *       "args": ["-jar", "/path/to/scaleout-build-mcp-1.0.1-runner.jar"],
 *       "env": {
 *         "SCALEOUT_ENDPOINT": "https://REPLACE.lambda-url.eu-central-1.on.aws/",
 *         "AWS_REGION": "eu-central-1"
 *       }
 *     }
 *   }
 * }
 * </pre>
 */
@ApplicationScoped
public class ScaleoutTools {

    @Inject
    ControlPlaneHttpClient http;

    // ── Build lifecycle ──────────────────────────────────────────────────────────────────────

    @Tool(description = """
            Lists the most recent native-image builds for the authenticated caller.
            Returns build IDs, states (PENDING, STAGED, RUNNING, SUCCEEDED, FAILED, CANCELLED),
            and per-cell architecture status (NATIVE/X86_64, NATIVE/ARM64).
            Use get_build_status with a specific build_id to see full detail.""")
    ToolResponse list_builds(
            @ToolArg(description = "Maximum number of builds to return", required = false)
            Integer limit) {
        int n = limit != null ? limit : 20;
        return invoke(() -> http.listBuilds(n));
    }

    @Tool(description = """
            Gets the full status of a single native-image build including per-cell architecture
            state, task ARNs, exit codes, failure reasons, and artifact paths.
            Use this to check whether a build has completed and whether artifacts are ready
            for download via list_build_artifacts.""")
    ToolResponse get_build_status(
            @ToolArg(description = "Build ID returned by the Maven plugin or list_builds")
            String build_id) {
        return invoke(() -> http.getBuild(build_id));
    }

    @Tool(description = """
            Cancels a running native-image build and stops all its Fargate tasks.
            Idempotent: cancelling an already-terminal build returns its final state.
            Blocks until StopTask has been issued for every running cell.""")
    ToolResponse cancel_build(
            @ToolArg(description = "Build ID to cancel")
            String build_id) {
        return invoke(() -> http.cancelBuild(build_id));
    }

    @Tool(description = """
            Lists presigned download URLs for the artifacts produced by a succeeded build.
            Returns one URL per artifact per architecture cell (x86_64, arm64).
            URLs are short-lived; download promptly after calling this.""")
    ToolResponse list_build_artifacts(
            @ToolArg(description = "Build ID to list artifacts for")
            String build_id) {
        return invoke(() -> http.listArtifacts(build_id));
    }

    // ── MicroVM image management (admin) ─────────────────────────────────────────────────────

    @Tool(description = """
            Triggers a new Lambda MicroVM agent image build. Requires admin role
            (SCALEOUT_MICROVM_ADMIN_ROLE_ARNS must include the caller's IAM role ARN).
            The code artifact ZIP must already be in S3 before calling this endpoint.
            The build takes approximately 10-15 minutes.
            After triggering, use get_image_status to poll until state is CREATED.""")
    ToolResponse trigger_image_build(
            @ToolArg(description = "S3 URI of the code artifact ZIP, e.g. s3://bucket/agent.zip")
            String artifact_s3_uri) {
        return invoke(() -> http.triggerImageBuild(artifact_s3_uri));
    }

    @Tool(description = """
            Gets the current status of the Lambda MicroVM agent image.
            States: CREATING (build in progress), CREATED (ready for RunMicrovm),
            CREATE_FAILED (build failed), UPDATING, UPDATED, UPDATE_FAILED.
            Poll until state is CREATED before attempting to use the image.""")
    ToolResponse get_image_status(
            @ToolArg(description = "Image name or ARN (default: scaleout-build-agent)",
                     required = false)
            String image_identifier) {
        String id = (image_identifier == null || image_identifier.isBlank())
                ? "scaleout-build-agent" : image_identifier;
        return invoke(() -> http.getImageStatus(id));
    }

    // ── Dispatch helper ──────────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Action {
        String execute() throws CliException;
    }

    private static ToolResponse invoke(Action action) {
        try {
            String json = action.execute();
            // Pretty-print for readability in the AI assistant's context window
            try {
                var node = ControlPlaneHttpClient.MAPPER.readTree(json);
                json = ControlPlaneHttpClient.MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(node);
            } catch (Exception ignored) {}
            return ToolResponse.success(json);
        } catch (CliException e) {
            return ToolResponse.error("Error (HTTP " + e.statusCode() + "): " + e.getMessage());
        }
    }
}
