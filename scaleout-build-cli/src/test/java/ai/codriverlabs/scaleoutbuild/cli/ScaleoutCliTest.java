/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.controlplane.api.AdminApi;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.client.ControlPlaneClient;
import ai.codriverlabs.scaleoutbuild.controlplane.api.client.ControlPlaneClientException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

/**
 * Unit tests for the CLI commands. Each test drives the full picocli pipeline, injecting a mock
 * {@link ControlPlaneClient} so no real HTTP calls are made.
 *
 * <p>The tests focus on:
 * <ul>
 *   <li>Argument parsing (required vs optional, defaults)</li>
 *   <li>Correct delegation to the client</li>
 *   <li>Error formatting when the server returns an HTTP error</li>
 *   <li>Missing endpoint detection</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ScaleoutCliTest {

    @Mock
    ControlPlaneClient client;

    ScaleoutCli cli;
    CommandLine cmd;
    StringWriter err;

    // Capture System.out (commands print directly to System.out)
    ByteArrayOutputStream capturedOut;
    PrintStream originalOut;

    @BeforeEach
    void setUp() {
        cli = new ScaleoutCli() {
            @Override
            public ControlPlaneClient client() {
                return client;
            }
        };
        err = new StringWriter();
        cmd = new CommandLine(cli);
        cmd.setErr(new PrintWriter(err));
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            err.write("error: " + ex.getMessage());
            return 1;
        });
        // Redirect System.out so we can assert on printed output
        capturedOut = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(capturedOut));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    private String stdout() {
        return capturedOut.toString();
    }

    private static BuildStatus statusWith(String buildId, BuildState state) {
        return new BuildStatus(buildId, state, "arn:aws:iam::123456789012:user/test", List.of(),
                null, null, null, null);
    }

    // ── builds list ───────────────────────────────────────────────────────────────────────────

    @Test
    void builds_list_uses_default_limit_20() throws ControlPlaneClientException {
        when(client.listBuilds(20)).thenReturn(List.of());
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "list");
        assertThat(exit).isZero();
        verify(client).listBuilds(20);
    }

    @Test
    void builds_list_respects_custom_limit() throws ControlPlaneClientException {
        when(client.listBuilds(5)).thenReturn(List.of());
        cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "list", "--limit", "5");
        verify(client).listBuilds(5);
    }

    @Test
    void builds_list_pretty_prints_json() throws ControlPlaneClientException {
        when(client.listBuilds(20)).thenReturn(
                List.of(statusWith("b1", BuildState.RUNNING)));
        cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "list");
        // Pretty-printed JSON has newlines; raw compact JSON does not
        assertThat(stdout()).contains("\n").contains("b1");
    }

    // ── builds status ─────────────────────────────────────────────────────────────────────────

    @Test
    void builds_status_passes_build_id() throws ControlPlaneClientException {
        when(client.status("abc-123")).thenReturn(statusWith("abc-123", BuildState.RUNNING));
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "status", "abc-123");
        assertThat(exit).isZero();
        verify(client).status("abc-123");
    }

    @Test
    void builds_status_exits_1_on_http_error() throws ControlPlaneClientException {
        doThrow(new ControlPlaneClientException("build not found", 404))
                .when(client).status("missing");
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "status", "missing");
        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("build not found");
    }

    // ── builds cancel ─────────────────────────────────────────────────────────────────────────

    @Test
    void builds_cancel_calls_client() throws ControlPlaneClientException {
        when(client.cancel("build-1")).thenReturn(statusWith("build-1", BuildState.CANCELLED));
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "cancel", "build-1");
        assertThat(exit).isZero();
        verify(client).cancel("build-1");
    }

    // ── images build ─────────────────────────────────────────────────────────────────────────

    @Test
    void images_build_requires_artifact_uri() {
        // Missing --artifact should fail argument parsing
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "images", "build");
        assertThat(exit).isNotZero();
    }

    @Test
    void images_build_passes_artifact_uri() throws ControlPlaneClientException {
        when(client.triggerImageBuild("s3://bucket/agent.zip"))
                .thenReturn(new AdminApi.ImageBuildResponse(
                        "scaleout-build-agent", "1.0", "CREATING", null));
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "images", "build", "--artifact", "s3://bucket/agent.zip");
        assertThat(exit).isZero();
        verify(client).triggerImageBuild("s3://bucket/agent.zip");
    }

    // ── images status ────────────────────────────────────────────────────────────────────────

    @Test
    void images_status_defaults_to_agent_image_name() throws ControlPlaneClientException {
        when(client.getImageStatus("scaleout-build-agent"))
                .thenReturn(new AdminApi.ImageBuildResponse(
                        "scaleout-build-agent", "1.0", "CREATED", "arn:..."));
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "images", "status");
        assertThat(exit).isZero();
        verify(client).getImageStatus("scaleout-build-agent");
    }

    @Test
    void images_status_passes_custom_identifier() throws ControlPlaneClientException {
        when(client.getImageStatus("arn:aws:lambda:eu-central-1:123:microvm-image:my-agent"))
                .thenReturn(new AdminApi.ImageBuildResponse("my-agent", null, "CREATING", null));
        cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "images", "status", "arn:aws:lambda:eu-central-1:123:microvm-image:my-agent");
        verify(client).getImageStatus("arn:aws:lambda:eu-central-1:123:microvm-image:my-agent");
    }

    // ── endpoint validation ──────────────────────────────────────────────────────────────────

    @Test
    void missing_endpoint_is_reported_as_error() {
        // No --endpoint and no SCALEOUT_ENDPOINT env var
        ScaleoutCli bare = new ScaleoutCli();
        CommandLine bareCmd = new CommandLine(bare);
        StringWriter bareErr = new StringWriter();
        bareCmd.setErr(new PrintWriter(bareErr));
        bareCmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            bareErr.write("error: " + ex.getMessage());
            return 1;
        });
        // The endpoint default resolves to blank; client() should throw ParameterException
        int exit = bareCmd.execute("builds", "list");
        assertThat(exit).isNotZero();
    }
}
