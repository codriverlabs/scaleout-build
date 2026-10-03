/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.microvm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.codriverlabs.scaleoutbuild.microvm.MicroVmImageManager.ImageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.lambdamicrovms.LambdaMicrovmsClient;
import software.amazon.awssdk.services.lambdamicrovms.model.CreateMicrovmImageRequest;
import software.amazon.awssdk.services.lambdamicrovms.model.CreateMicrovmImageResponse;
import software.amazon.awssdk.services.lambdamicrovms.model.GetMicrovmImageRequest;
import software.amazon.awssdk.services.lambdamicrovms.model.GetMicrovmImageResponse;
import software.amazon.awssdk.services.lambdamicrovms.model.HookState;
import software.amazon.awssdk.services.lambdamicrovms.model.MicrovmImageState;
import software.amazon.awssdk.services.lambdamicrovms.model.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
class MicroVmImageManagerTest {

    @Mock
    LambdaMicrovmsClient client;

    MicroVmImageManager manager;

    @BeforeEach
    void setUp() {
        manager = new MicroVmImageManager(client);
    }

    // ── triggerBuild ─────────────────────────────────────────────────────────────────────────

    @Test
    void triggerBuild_sends_correct_request() {
        when(client.createMicrovmImage(any(CreateMicrovmImageRequest.class)))
                .thenReturn(CreateMicrovmImageResponse.builder()
                        .imageArn("arn:aws:lambda:eu-central-1:123:microvm-image:scaleout-build-agent")
                        .imageVersion("1.0")
                        .state(MicrovmImageState.CREATING)
                        .build());

        ImageStatus status = manager.triggerBuild(
                "eu-central-1",
                "arn:aws:iam::123:role/MicrovmBuildRole",
                "s3://bucket/agent.zip");

        ArgumentCaptor<CreateMicrovmImageRequest> captor =
                ArgumentCaptor.forClass(CreateMicrovmImageRequest.class);
        verify(client).createMicrovmImage(captor.capture());
        CreateMicrovmImageRequest req = captor.getValue();

        assertThat(req.name()).isEqualTo(MicroVmImageManager.IMAGE_NAME);
        assertThat(req.buildRoleArn()).isEqualTo("arn:aws:iam::123:role/MicrovmBuildRole");
        assertThat(req.codeArtifact().uri()).isEqualTo("s3://bucket/agent.zip");
        assertThat(req.baseImageArn()).contains("eu-central-1");
        assertThat(req.baseImageArn()).endsWith(MicroVmImageManager.AL2023_BASE_IMAGE_ARN_SUFFIX);

        assertThat(status.imageName()).isEqualTo(MicroVmImageManager.IMAGE_NAME);
        assertThat(status.imageVersion()).isEqualTo("1.0");
        assertThat(status.state()).isEqualTo("CREATING");
        assertThat(status.isReady()).isFalse();
    }

    @Test
    void triggerBuild_hooks_are_enabled_with_correct_port_and_timeouts() {
        when(client.createMicrovmImage(any(CreateMicrovmImageRequest.class)))
                .thenReturn(CreateMicrovmImageResponse.builder()
                        .state(MicrovmImageState.CREATING)
                        .build());

        manager.triggerBuild("eu-central-1",
                "arn:aws:iam::123:role/MicrovmBuildRole", "s3://bucket/agent.zip");

        ArgumentCaptor<CreateMicrovmImageRequest> captor =
                ArgumentCaptor.forClass(CreateMicrovmImageRequest.class);
        verify(client).createMicrovmImage(captor.capture());
        CreateMicrovmImageRequest req = captor.getValue();

        // Port applies to all hooks
        assertThat(req.hooks().port()).isEqualTo(MicroVmImageManager.AGENT_HOOK_PORT);

        // Image build hooks
        assertThat(req.hooks().microvmImageHooks().ready()).isEqualTo(HookState.ENABLED);
        assertThat(req.hooks().microvmImageHooks().readyTimeoutInSeconds())
                .isEqualTo(MicroVmImageManager.READY_HOOK_TIMEOUT_SECONDS);
        assertThat(req.hooks().microvmImageHooks().validate()).isEqualTo(HookState.ENABLED);
        assertThat(req.hooks().microvmImageHooks().validateTimeoutInSeconds())
                .isEqualTo(MicroVmImageManager.VALIDATE_HOOK_TIMEOUT_SECONDS);

        // Runtime hook — must respect the 60s platform ceiling
        assertThat(req.hooks().microvmHooks().run()).isEqualTo(HookState.ENABLED);
        assertThat(req.hooks().microvmHooks().runTimeoutInSeconds())
                .isEqualTo(MicroVmImageManager.RUN_HOOK_TIMEOUT_SECONDS);
        assertThat(req.hooks().microvmHooks().runTimeoutInSeconds())
                .isLessThanOrEqualTo(60); // platform ceiling
    }

    // ── getImageStatus ────────────────────────────────────────────────────────────────────────

    @Test
    void getImageStatus_returns_ready_image() {
        when(client.getMicrovmImage(any(GetMicrovmImageRequest.class)))
                .thenReturn(GetMicrovmImageResponse.builder()
                        .imageArn("arn:aws:lambda:eu-central-1:123:microvm-image:scaleout-build-agent")
                        .state(MicrovmImageState.CREATED)
                        .latestActiveImageVersion("1.0")
                        .build());

        ImageStatus status = manager.getImageStatus(MicroVmImageManager.IMAGE_NAME);

        assertThat(status).isNotNull();
        assertThat(status.isReady()).isTrue(); // CREATED means the image is ready to serve launches
        assertThat(status.isFailed()).isFalse();
        assertThat(status.imageArn()).isNotBlank();
        assertThat(status.state()).isEqualTo("CREATED");

        ArgumentCaptor<GetMicrovmImageRequest> captor =
                ArgumentCaptor.forClass(GetMicrovmImageRequest.class);
        verify(client).getMicrovmImage(captor.capture());
        assertThat(captor.getValue().imageIdentifier()).isEqualTo(MicroVmImageManager.IMAGE_NAME);
    }

    @Test
    void getImageStatus_returns_null_when_not_found() {
        when(client.getMicrovmImage(any(GetMicrovmImageRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("not found").build());

        ImageStatus status = manager.getImageStatus("nonexistent-image");

        assertThat(status).isNull();
    }

    @Test
    void getImageStatus_failed_image_is_detected() {
        when(client.getMicrovmImage(any(GetMicrovmImageRequest.class)))
                .thenReturn(GetMicrovmImageResponse.builder()
                        .state(MicrovmImageState.CREATE_FAILED)
                        .build());

        ImageStatus status = manager.getImageStatus(MicroVmImageManager.IMAGE_NAME);

        // CREATE_FAILED does not match "FAILED" in isFailed(), but does not match "READY" in isReady()
        assertThat(status.isReady()).isFalse();
        assertThat(status.state()).isEqualTo("CREATE_FAILED");
    }
}
