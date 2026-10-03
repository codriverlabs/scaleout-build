/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

/**
 * Unit tests for the CLI commands. Each test drives the full picocli pipeline, injecting a mock
 * {@link ControlPlaneHttpClient} so no real HTTP calls are made.
 *
 * <p>The tests focus on:
 * <ul>
 *   <li>Argument parsing (required vs optional, defaults)</li>
 *   <li>Correct delegation to the HTTP client</li>
 *   <li>Error formatting when the server returns an HTTP error</li>
 *   <li>Missing endpoint detection</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ScaleoutCliTest {

    @Mock
    ControlPlaneHttpClient http;

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
            public ControlPlaneHttpClient client() {
                return http;
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

    // ── builds list ───────────────────────────────────────────────────────────────────────────

    @Test
    void builds_list_uses_default_limit_20() throws CliException {
        when(http.listBuilds(20)).thenReturn("[]");
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "list");
        assertThat(exit).isZero();
        verify(http).listBuilds(20);
    }

    @Test
    void builds_list_respects_custom_limit() throws CliException {
        when(http.listBuilds(5)).thenReturn("[]");
        cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "list", "--limit", "5");
        verify(http).listBuilds(5);
    }

    @Test
    void builds_list_pretty_prints_json() throws CliException {
        when(http.listBuilds(20)).thenReturn("{\"builds\":[]}");
        cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "list");
        // Pretty-printed JSON has newlines; raw compact JSON does not
        assertThat(stdout()).contains("\n");
    }

    // ── builds status ─────────────────────────────────────────────────────────────────────────

    @Test
    void builds_status_passes_build_id() throws CliException {
        when(http.getBuild("abc-123")).thenReturn("{\"buildId\":\"abc-123\"}");
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "status", "abc-123");
        assertThat(exit).isZero();
        verify(http).getBuild("abc-123");
    }

    @Test
    void builds_status_exits_1_on_http_error() throws CliException {
        doThrow(new CliException("build not found", 404))
                .when(http).getBuild("missing");
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "status", "missing");
        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("build not found");
    }

    // ── builds cancel ─────────────────────────────────────────────────────────────────────────

    @Test
    void builds_cancel_calls_http_client() throws CliException {
        when(http.cancelBuild("build-1")).thenReturn("{\"state\":\"CANCELLED\"}");
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "cancel", "build-1");
        assertThat(exit).isZero();
        verify(http).cancelBuild("build-1");
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
    void images_build_passes_artifact_uri() throws CliException {
        when(http.triggerImageBuild("s3://bucket/agent.zip"))
                .thenReturn("{\"state\":\"CREATING\",\"imageVersion\":\"1.0\"}");
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "images", "build", "--artifact", "s3://bucket/agent.zip");
        assertThat(exit).isZero();
        verify(http).triggerImageBuild("s3://bucket/agent.zip");
    }

    // ── images status ────────────────────────────────────────────────────────────────────────

    @Test
    void images_status_defaults_to_agent_image_name() throws CliException {
        when(http.getImageStatus("scaleout-build-agent"))
                .thenReturn("{\"state\":\"CREATED\"}");
        int exit = cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "images", "status");
        assertThat(exit).isZero();
        verify(http).getImageStatus("scaleout-build-agent");
    }

    @Test
    void images_status_passes_custom_identifier() throws CliException {
        when(http.getImageStatus("arn:aws:lambda:eu-central-1:123:microvm-image:my-agent"))
                .thenReturn("{\"state\":\"CREATING\"}");
        cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "images", "status", "arn:aws:lambda:eu-central-1:123:microvm-image:my-agent");
        verify(http).getImageStatus("arn:aws:lambda:eu-central-1:123:microvm-image:my-agent");
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

    // ── pretty-printing ─────────────────────────────────────────────────────────────────────

    @Test
    void invalid_json_from_server_is_printed_raw() throws CliException {
        when(http.listBuilds(20)).thenReturn("not-json");
        cmd.execute("--endpoint", "https://ep/", "--region", "eu-central-1",
                "builds", "list");
        assertThat(stdout()).contains("not-json");
    }
}
