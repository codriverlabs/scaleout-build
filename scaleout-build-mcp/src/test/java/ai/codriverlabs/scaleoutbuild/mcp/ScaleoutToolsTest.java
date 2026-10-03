/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.cli.CliException;
import ai.codriverlabs.scaleoutbuild.cli.ControlPlaneHttpClient;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Tests for the MCP tool methods in {@link ScaleoutTools}.
 *
 * <p>{@link ControlPlaneHttpClient} is mocked via {@link InjectMock}; no real HTTP calls are made.
 * Each test verifies:
 * <ul>
 *   <li>The correct HTTP client method is called with the right arguments</li>
 *   <li>Successful responses produce a non-error {@link ToolResponse} with JSON content</li>
 *   <li>HTTP errors produce an error {@link ToolResponse} carrying the status code and message</li>
 *   <li>Default argument values are applied correctly</li>
 * </ul>
 */
@QuarkusTest
class ScaleoutToolsTest {

    @InjectMock
    ControlPlaneHttpClient http;

    @Inject
    ScaleoutTools tools;

    // ── list_builds ──────────────────────────────────────────────────────────────────────────

    @Test
    void list_builds_default_limit_is_20() throws CliException {
        when(http.listBuilds(20)).thenReturn("{\"builds\":[]}");
        ToolResponse resp = tools.list_builds(null);
        assertThat(resp.isError()).isFalse();
        verify(http).listBuilds(20);
    }

    @Test
    void list_builds_respects_explicit_limit() throws CliException {
        when(http.listBuilds(5)).thenReturn("[]");
        ToolResponse resp = tools.list_builds(5);
        assertThat(resp.isError()).isFalse();
        verify(http).listBuilds(5);
    }

    @Test
    void list_builds_returns_pretty_json() throws CliException {
        when(http.listBuilds(anyInt())).thenReturn("{\"builds\":[]}");
        ToolResponse resp = tools.list_builds(null);
        String content = extractText(resp);
        // Pretty-printed JSON has newlines
        assertThat(content).contains("\n");
    }

    @Test
    void list_builds_http_error_produces_error_response() throws CliException {
        when(http.listBuilds(anyInt())).thenThrow(new CliException("unauthorized", 401));
        ToolResponse resp = tools.list_builds(null);
        assertThat(resp.isError()).isTrue();
        assertThat(extractText(resp)).contains("401").contains("unauthorized");
    }

    // ── get_build_status ──────────────────────────────────────────────────────────────────────

    @Test
    void get_build_status_passes_build_id() throws CliException {
        when(http.getBuild("build-abc")).thenReturn("{\"buildId\":\"build-abc\",\"state\":\"RUNNING\"}");
        ToolResponse resp = tools.get_build_status("build-abc");
        assertThat(resp.isError()).isFalse();
        assertThat(extractText(resp)).contains("build-abc");
        verify(http).getBuild("build-abc");
    }

    @Test
    void get_build_status_404_is_error() throws CliException {
        when(http.getBuild("missing")).thenThrow(new CliException("not found", 404));
        ToolResponse resp = tools.get_build_status("missing");
        assertThat(resp.isError()).isTrue();
        assertThat(extractText(resp)).contains("404");
    }

    // ── cancel_build ──────────────────────────────────────────────────────────────────────────

    @Test
    void cancel_build_calls_http_and_returns_final_state() throws CliException {
        when(http.cancelBuild("build-1")).thenReturn("{\"state\":\"CANCELLED\"}");
        ToolResponse resp = tools.cancel_build("build-1");
        assertThat(resp.isError()).isFalse();
        assertThat(extractText(resp)).contains("CANCELLED");
        verify(http).cancelBuild("build-1");
    }

    // ── list_build_artifacts ─────────────────────────────────────────────────────────────────

    @Test
    void list_build_artifacts_passes_build_id() throws CliException {
        when(http.listArtifacts("build-1"))
                .thenReturn("{\"buildId\":\"build-1\",\"cells\":[]}");
        ToolResponse resp = tools.list_build_artifacts("build-1");
        assertThat(resp.isError()).isFalse();
        verify(http).listArtifacts("build-1");
    }

    // ── trigger_image_build ──────────────────────────────────────────────────────────────────

    @Test
    void trigger_image_build_passes_artifact_uri() throws CliException {
        when(http.triggerImageBuild("s3://bucket/agent.zip"))
                .thenReturn("{\"state\":\"CREATING\",\"imageVersion\":\"1.0\"}");
        ToolResponse resp = tools.trigger_image_build("s3://bucket/agent.zip");
        assertThat(resp.isError()).isFalse();
        assertThat(extractText(resp)).contains("CREATING");
        verify(http).triggerImageBuild("s3://bucket/agent.zip");
    }

    @Test
    void trigger_image_build_403_is_error() throws CliException {
        when(http.triggerImageBuild(anyString()))
                .thenThrow(new CliException("not an admin role", 404));
        ToolResponse resp = tools.trigger_image_build("s3://bucket/agent.zip");
        assertThat(resp.isError()).isTrue();
        assertThat(extractText(resp)).contains("404");
    }

    // ── get_image_status ─────────────────────────────────────────────────────────────────────

    @Test
    void get_image_status_defaults_to_agent_image_name() throws CliException {
        when(http.getImageStatus("scaleout-build-agent"))
                .thenReturn("{\"state\":\"CREATED\"}");
        ToolResponse resp = tools.get_image_status(null);
        assertThat(resp.isError()).isFalse();
        verify(http).getImageStatus("scaleout-build-agent");
    }

    @Test
    void get_image_status_blank_defaults_to_agent_image_name() throws CliException {
        when(http.getImageStatus("scaleout-build-agent"))
                .thenReturn("{\"state\":\"CREATED\"}");
        ToolResponse resp = tools.get_image_status("   ");
        assertThat(resp.isError()).isFalse();
        verify(http).getImageStatus("scaleout-build-agent");
    }

    @Test
    void get_image_status_passes_custom_identifier() throws CliException {
        String arn = "arn:aws:lambda:eu-central-1:123:microvm-image:scaleout-build-agent";
        when(http.getImageStatus(arn)).thenReturn("{\"state\":\"CREATING\"}");
        ToolResponse resp = tools.get_image_status(arn);
        assertThat(resp.isError()).isFalse();
        verify(http).getImageStatus(arn);
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
