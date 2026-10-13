/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.lambdamicrovms.LambdaMicrovmsClient;
import software.amazon.awssdk.services.lambdamicrovms.model.GetMicrovmRequest;
import software.amazon.awssdk.services.lambdamicrovms.model.GetMicrovmResponse;
import software.amazon.awssdk.services.lambdamicrovms.model.MicrovmState;
import software.amazon.awssdk.services.lambdamicrovms.model.RunMicrovmRequest;
import software.amazon.awssdk.services.lambdamicrovms.model.RunMicrovmResponse;
import software.amazon.awssdk.services.lambdamicrovms.model.TerminateMicrovmRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Mocks {@link LambdaMicrovmsClient}/{@link S3Client} the same way
 * {@code MicroVmImageManagerTest} already mocks {@code LambdaMicrovmsClient} for the admin
 * image-build flow, per
 * {@code docs/design/control-plane/microvm-build-backend-phase1.md}'s implementation-order step 4.
 */
@ExtendWith(MockitoExtension.class)
class MicroVmCellLauncherTest {

    private static final String IMAGE_ARN =
            "arn:aws:lambda:eu-west-1:123456789012:microvm-image:scaleout-build-agent";
    private static final String MICROVM_ID = "microvm-abc123";
    private static final String ENDPOINT = "https://microvm-abc123.lambda-microvms.eu-west-1.amazonaws.com";
    private static final String STAGING_RELATIVE_PATH = "builds/01J8ZQ/native/arm64";

    @Mock
    private LambdaMicrovmsClient microvms;

    @Mock
    private S3Client s3Client;

    private MicroVmCellLauncher launcher;
    private MicroVmSettings settings;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        settings = new MicroVmSettings(IMAGE_ARN, "1.0",
                "arn:aws:iam::123456789012:role/MicrovmExecutionRole",
                "arn:aws:lambda:eu-west-1:123456789012:network-connector:egress-xyz",
                "/scaleout-build/build-agent");
        launcher = new MicroVmCellLauncher(microvms, s3Client, settings, "scaleout-build-staging");
    }

    private CellLaunchRequest sampleRequest() {
        return new CellLaunchRequest(
                fargateClusterSettingsStandIn(),
                new AgentContainerSettings("123456789012.dkr.ecr.eu-west-1.amazonaws.com/agent",
                        "4096", "8192", 0),
                "01J8ZQ",
                BuildKind.NATIVE,
                Architecture.ARM64,
                STAGING_RELATIVE_PATH,
                Map.of("SCALEOUT_BUILD_ID", "01J8ZQ", "SCALEOUT_BUILD_ARCH", "ARM64",
                        "SCALEOUT_BUILD_TIMEOUT_MINUTES", "20"));
    }

    /** MicroVmCellLauncher never reads clusterSettings -- only Fargate does -- but
     * CellLaunchRequest requires one, so this is a minimal placeholder. */
    private static EcsClusterSettings fargateClusterSettingsStandIn() {
        return new EcsClusterSettings(
                EcsLaunchType.FARGATE,
                "arn:aws:ecs:eu-west-1:123456789012:cluster/scaleout-build",
                java.util.List.of("subnet-1"),
                java.util.List.of("sg-1"),
                false,
                "arn:aws:iam::123456789012:role/exec",
                "arn:aws:iam::123456789012:role/task",
                null, null, null, null, null,
                "/scaleout-build/build-agent",
                "eu-west-1",
                true);
    }

    @Test
    void launch_rejectsX86_64() {
        CellLaunchRequest request = new CellLaunchRequest(
                fargateClusterSettingsStandIn(),
                new AgentContainerSettings("123456789012.dkr.ecr.eu-west-1.amazonaws.com/agent",
                        "4096", "8192", 0),
                "01J8ZQ", BuildKind.NATIVE, Architecture.X86_64, STAGING_RELATIVE_PATH, Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> launcher.launch(request))
                .isInstanceOf(CellLaunchException.class)
                .hasMessageContaining("ARM64");
    }

    @Test
    void launch_omitsEgressNetworkConnectors_whenNoneConfigured() throws Exception {
        // The actual proof for MicroVmSettings' own javadoc correction: a MicroVM reaches S3 via
        // its default public internet access when no connector is configured, so launch() must
        // not send an empty/blank connector ARN to the SDK -- it must omit the field entirely.
        MicroVmSettings noConnectorSettings = new MicroVmSettings(IMAGE_ARN, "1.0",
                "arn:aws:iam::123456789012:role/MicrovmExecutionRole", null,
                "/scaleout-build/build-agent");
        MicroVmCellLauncher launcherWithoutConnector = new MicroVmCellLauncher(microvms, s3Client,
                noConnectorSettings, "scaleout-build-staging");
        ArgumentCaptor<RunMicrovmRequest> captor = ArgumentCaptor.forClass(RunMicrovmRequest.class);
        when(microvms.runMicrovm(captor.capture())).thenReturn(RunMicrovmResponse.builder()
                .microvmId(MICROVM_ID)
                .endpoint(ENDPOINT)
                .build());

        launcherWithoutConnector.launch(sampleRequest());

        RunMicrovmRequest req = captor.getValue();
        assertThat(req.hasEgressNetworkConnectors()).isFalse();
    }

    @Test
    void launch_sendsCorrectRunMicrovmRequest() throws Exception {
        ArgumentCaptor<RunMicrovmRequest> captor = ArgumentCaptor.forClass(RunMicrovmRequest.class);
        when(microvms.runMicrovm(captor.capture())).thenReturn(RunMicrovmResponse.builder()
                .microvmId(MICROVM_ID)
                .endpoint(ENDPOINT)
                .build());

        CellHandle handle = launcher.launch(sampleRequest());

        RunMicrovmRequest req = captor.getValue();
        assertThat(req.imageIdentifier()).isEqualTo(IMAGE_ARN);
        assertThat(req.imageVersion()).isEqualTo("1.0");
        assertThat(req.executionRoleArn()).isEqualTo(settings.executionRoleArn());
        assertThat(req.egressNetworkConnectors()).containsExactly(settings.vpcEgressConnectorArn());
        assertThat(req.hasIngressNetworkConnectors()).isFalse();
        // maxIdleDurationSeconds must match maximumDurationInSeconds, not a short fixed value --
        // see MicroVmCellLauncher's own comment above launch() for why a short idle duration
        // (confirmed via a real RunMicrovm call) actively kills a healthy compile: idle time is
        // measured by inbound HTTP traffic to the MicroVM's endpoint, and the compile itself
        // generates none.
        assertThat(req.idlePolicy().maxIdleDurationSeconds()).isEqualTo(30 * 60);
        assertThat(req.idlePolicy().suspendedDurationSeconds()).isEqualTo(0);
        assertThat(req.idlePolicy().autoResumeEnabled()).isFalse();
        // 20 (timeout) + 10 (margin) minutes, in seconds.
        assertThat(req.maximumDurationInSeconds()).isEqualTo(30 * 60);
        // Explicit logging config: without this, runtime stdout/stderr is unobservable -- see
        // this class's own comment above the .logging(...) call in launch() for why, confirmed
        // against AWS's documentation and by a real build producing zero log output.
        assertThat(req.logging().cloudWatch().logGroup()).isEqualTo(settings.logGroupName());
        assertThat(req.logging().cloudWatch().logStream()).isEqualTo(sampleRequest().cellId());

        // runHookPayload is a plain String (confirmed via javap -- see this class's own javadoc),
        // and its content is the same agentEnvironment map CellLaunchRequest already carries.
        Map<String, String> payload = mapper.readValue(req.runHookPayload(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
                });
        assertThat(payload).containsEntry("SCALEOUT_BUILD_ID", "01J8ZQ")
                .containsEntry("SCALEOUT_BUILD_ARCH", "ARM64");

        assertThat(handle).isInstanceOf(CellHandle.MicroVmHandle.class);
        CellHandle.MicroVmHandle microVmHandle = (CellHandle.MicroVmHandle) handle;
        assertThat(microVmHandle.microvmId()).isEqualTo(MICROVM_ID);
        assertThat(microVmHandle.endpoint()).isEqualTo(ENDPOINT);
        assertThat(microVmHandle.stagingRelativePath()).isEqualTo(STAGING_RELATIVE_PATH);
    }

    @Test
    void launch_wrapsSdkExceptionAsCellLaunchException() {
        when(microvms.runMicrovm(any(RunMicrovmRequest.class)))
                .thenThrow(SdkClientException.create("insufficient capacity"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> launcher.launch(sampleRequest()))
                .isInstanceOf(CellLaunchException.class)
                .hasMessageContaining("RunMicrovm failed");
    }

    @Test
    void poll_readsStatusJsonWhenPresent_succeeded() throws Exception {
        CellHandle.MicroVmHandle handle =
                new CellHandle.MicroVmHandle(MICROVM_ID, ENDPOINT, STAGING_RELATIVE_PATH);
        String statusJson = """
                {"buildId":"01J8ZQ","cell":"NATIVE/ARM64","state":"SUCCEEDED","exitCode":0,
                 "artifacts":[{"path":"app","sha256":"abc","sizeBytes":123}],
                 "completedAt":"2026-10-10T00:00:00Z"}
                """;
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(),
                        statusJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        CellPollResult result = launcher.poll(handle);

        assertThat(result.state()).isEqualTo(CellLaunchState.SUCCEEDED);
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void poll_readsStatusJsonWhenPresent_failed() throws Exception {
        CellHandle.MicroVmHandle handle =
                new CellHandle.MicroVmHandle(MICROVM_ID, ENDPOINT, STAGING_RELATIVE_PATH);
        String statusJson = """
                {"buildId":"01J8ZQ","cell":"NATIVE/ARM64","state":"FAILED","exitCode":1,
                 "artifacts":[],"completedAt":"2026-10-10T00:00:00Z"}
                """;
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(),
                        statusJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        CellPollResult result = launcher.poll(handle);

        assertThat(result.state()).isEqualTo(CellLaunchState.FAILED);
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void poll_fallsBackToGetMicrovm_whenStatusJsonAbsentAndStillRunning() {
        CellHandle.MicroVmHandle handle =
                new CellHandle.MicroVmHandle(MICROVM_ID, ENDPOINT, STAGING_RELATIVE_PATH);
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());
        when(microvms.getMicrovm(any(GetMicrovmRequest.class)))
                .thenReturn(GetMicrovmResponse.builder().state(MicrovmState.RUNNING).build());

        CellPollResult result = launcher.poll(handle);

        assertThat(result.state()).isEqualTo(CellLaunchState.RUNNING);
    }

    @Test
    void poll_fallsBackToGetMicrovm_whenStatusJsonAbsentAndTerminated() {
        // The key proof the design doc calls for: MicrovmState has no FAILED value (confirmed via
        // javap), so a MicroVM that was killed by the platform before writing status.json must be
        // distinguished from "still running" via TERMINATED, not via a FAILED state that doesn't
        // exist.
        CellHandle.MicroVmHandle handle =
                new CellHandle.MicroVmHandle(MICROVM_ID, ENDPOINT, STAGING_RELATIVE_PATH);
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());
        when(microvms.getMicrovm(any(GetMicrovmRequest.class)))
                .thenReturn(GetMicrovmResponse.builder().state(MicrovmState.TERMINATED)
                        .stateReason("MaximumDurationExceeded").build());

        CellPollResult result = launcher.poll(handle);

        assertThat(result.state()).isEqualTo(CellLaunchState.FAILED);
        assertThat(result.failureReason()).contains("MaximumDurationExceeded");
    }

    @Test
    void stop_callsTerminateMicrovm() {
        CellHandle.MicroVmHandle handle =
                new CellHandle.MicroVmHandle(MICROVM_ID, ENDPOINT, STAGING_RELATIVE_PATH);

        launcher.stop(handle, "build cancelled");

        ArgumentCaptor<TerminateMicrovmRequest> captor =
                ArgumentCaptor.forClass(TerminateMicrovmRequest.class);
        verify(microvms).terminateMicrovm(captor.capture());
        assertThat(captor.getValue().microvmIdentifier()).isEqualTo(MICROVM_ID);
    }

    @Test
    void stop_toleratesAlreadyTerminated() {
        when(microvms.terminateMicrovm(any(TerminateMicrovmRequest.class)))
                .thenThrow(SdkClientException.create("already terminated"));
        CellHandle.MicroVmHandle handle =
                new CellHandle.MicroVmHandle(MICROVM_ID, ENDPOINT, STAGING_RELATIVE_PATH);

        // Must not throw.
        launcher.stop(handle, "build cancelled");
    }
}
