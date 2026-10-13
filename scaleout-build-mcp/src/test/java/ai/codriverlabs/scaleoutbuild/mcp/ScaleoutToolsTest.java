/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.controlplane.api.AdminApi;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ArtifactListResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.client.ControlPlaneClient;
import ai.codriverlabs.scaleoutbuild.controlplane.api.client.ControlPlaneClientException;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the MCP tool methods in {@link ScaleoutTools}.
 *
 * <p>{@link ControlPlaneClient} is mocked via {@link InjectMock}; no real HTTP calls are made.
 * Each test verifies:
 * <ul>
 *   <li>The correct client method is called with the right arguments</li>
 *   <li>Successful responses produce a non-error {@link ToolResponse} with JSON content</li>
 *   <li>HTTP errors produce an error {@link ToolResponse} carrying the status code and message</li>
 *   <li>Default argument values are applied correctly</li>
 * </ul>
 */
@QuarkusTest
class ScaleoutToolsTest {

    @InjectMock
    ControlPlaneClient client;

    @Inject
    ScaleoutTools tools;

    private static BuildStatus statusWith(String buildId, BuildState state) {
        return new BuildStatus(buildId, state, "arn:aws:iam::123456789012:user/test", List.of(),
                null, null, null, null);
    }

    // ── list_builds ──────────────────────────────────────────────────────────────────────────

    @Test
    void list_builds_default_limit_is_20() throws ControlPlaneClientException {
        when(client.listBuilds(20)).thenReturn(List.of());
        ToolResponse resp = tools.list_builds(null);
        assertThat(resp.isError()).isFalse();
        verify(client).listBuilds(20);
    }

    @Test
    void list_builds_respects_explicit_limit() throws ControlPlaneClientException {
        when(client.listBuilds(5)).thenReturn(List.of());
        ToolResponse resp = tools.list_builds(5);
        assertThat(resp.isError()).isFalse();
        verify(client).listBuilds(5);
    }

    @Test
    void list_builds_returns_pretty_json() throws ControlPlaneClientException {
        when(client.listBuilds(anyInt())).thenReturn(
                List.of(statusWith("b1", BuildState.RUNNING)));
        ToolResponse resp = tools.list_builds(null);
        String content = extractText(resp);
        // Pretty-printed JSON has newlines
        assertThat(content).contains("\n").contains("b1");
    }

    @Test
    void list_builds_http_error_produces_error_response() throws ControlPlaneClientException {
        when(client.listBuilds(anyInt()))
                .thenThrow(new ControlPlaneClientException("unauthorized", 401));
        ToolResponse resp = tools.list_builds(null);
        assertThat(resp.isError()).isTrue();
        assertThat(extractText(resp)).contains("401").contains("unauthorized");
    }

    // ── get_build_status ──────────────────────────────────────────────────────────────────────

    @Test
    void get_build_status_passes_build_id() throws ControlPlaneClientException {
        when(client.status("build-abc")).thenReturn(statusWith("build-abc", BuildState.RUNNING));
        ToolResponse resp = tools.get_build_status("build-abc");
        assertThat(resp.isError()).isFalse();
        assertThat(extractText(resp)).contains("build-abc");
        verify(client).status("build-abc");
    }

    @Test
    void get_build_status_404_is_error() throws ControlPlaneClientException {
        when(client.status("missing")).thenThrow(new ControlPlaneClientException("not found", 404));
        ToolResponse resp = tools.get_build_status("missing");
        assertThat(resp.isError()).isTrue();
        assertThat(extractText(resp)).contains("404");
    }

    // ── cancel_build ──────────────────────────────────────────────────────────────────────────

    @Test
    void cancel_build_calls_client_and_returns_final_state() throws ControlPlaneClientException {
        when(client.cancel("build-1")).thenReturn(statusWith("build-1", BuildState.CANCELLED));
        ToolResponse resp = tools.cancel_build("build-1");
        assertThat(resp.isError()).isFalse();
        assertThat(extractText(resp)).contains("CANCELLED");
        verify(client).cancel("build-1");
    }

    // ── list_build_artifacts ─────────────────────────────────────────────────────────────────

    @Test
    void list_build_artifacts_passes_build_id() throws ControlPlaneClientException {
        when(client.artifacts("build-1"))
                .thenReturn(new ArtifactListResponse("build-1", List.of()));
        ToolResponse resp = tools.list_build_artifacts("build-1");
        assertThat(resp.isError()).isFalse();
        verify(client).artifacts("build-1");
    }

    // ── trigger_image_build ──────────────────────────────────────────────────────────────────

    @Test
    void trigger_image_build_passes_artifact_uri() throws ControlPlaneClientException {
        when(client.triggerImageBuild("s3://bucket/agent.zip"))
                .thenReturn(new AdminApi.ImageBuildResponse(
                        "scaleout-build-agent", "1.0", "CREATING", null));
        ToolResponse resp = tools.trigger_image_build("s3://bucket/agent.zip");
        assertThat(resp.isError()).isFalse();
        assertThat(extractText(resp)).contains("CREATING");
        verify(client).triggerImageBuild("s3://bucket/agent.zip");
    }

    @Test
    void trigger_image_build_403_is_error() throws ControlPlaneClientException {
        when(client.triggerImageBuild(anyString()))
                .thenThrow(new ControlPlaneClientException("not an admin role", 404));
        ToolResponse resp = tools.trigger_image_build("s3://bucket/agent.zip");
        assertThat(resp.isError()).isTrue();
        assertThat(extractText(resp)).contains("404");
    }

    // ── get_image_status ─────────────────────────────────────────────────────────────────────

    @Test
    void get_image_status_defaults_to_agent_image_name() throws ControlPlaneClientException {
        when(client.getImageStatus("scaleout-build-agent"))
                .thenReturn(new AdminApi.ImageBuildResponse(
                        "scaleout-build-agent", null, "CREATED", "arn:..."));
        ToolResponse resp = tools.get_image_status(null);
        assertThat(resp.isError()).isFalse();
        verify(client).getImageStatus("scaleout-build-agent");
    }

    @Test
    void get_image_status_blank_defaults_to_agent_image_name() throws ControlPlaneClientException {
        when(client.getImageStatus("scaleout-build-agent"))
                .thenReturn(new AdminApi.ImageBuildResponse(
                        "scaleout-build-agent", null, "CREATED", "arn:..."));
        ToolResponse resp = tools.get_image_status("   ");
        assertThat(resp.isError()).isFalse();
        verify(client).getImageStatus("scaleout-build-agent");
    }

    @Test
    void get_image_status_passes_custom_identifier() throws ControlPlaneClientException {
        String arn = "arn:aws:lambda:eu-central-1:123:microvm-image:scaleout-build-agent";
        when(client.getImageStatus(arn)).thenReturn(
                new AdminApi.ImageBuildResponse("scaleout-build-agent", null, "CREATING", null));
        ToolResponse resp = tools.get_image_status(arn);
        assertThat(resp.isError()).isFalse();
        verify(client).getImageStatus(arn);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static String extractText(ToolResponse resp) {
        return resp.content().stream()
                .filter(c -> c instanceof io.quarkiverse.mcp.server.TextContent)
                .map(c -> ((io.quarkiverse.mcp.server.TextContent) c).text())
                .findFirst()
                .orElse("");
    }
}
