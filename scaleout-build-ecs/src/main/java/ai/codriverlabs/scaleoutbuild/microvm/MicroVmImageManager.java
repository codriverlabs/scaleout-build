/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.microvm;

import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.services.lambdamicrovms.LambdaMicrovmsClient;
import software.amazon.awssdk.services.lambdamicrovms.model.CodeArtifact;
import software.amazon.awssdk.services.lambdamicrovms.model.CreateMicrovmImageRequest;
import software.amazon.awssdk.services.lambdamicrovms.model.CreateMicrovmImageResponse;
import software.amazon.awssdk.services.lambdamicrovms.model.GetMicrovmImageBuildRequest;
import software.amazon.awssdk.services.lambdamicrovms.model.GetMicrovmImageBuildResponse;
import software.amazon.awssdk.services.lambdamicrovms.model.GetMicrovmImageRequest;
import software.amazon.awssdk.services.lambdamicrovms.model.GetMicrovmImageResponse;
import software.amazon.awssdk.services.lambdamicrovms.model.Hooks;
import software.amazon.awssdk.services.lambdamicrovms.model.HookState;
import software.amazon.awssdk.services.lambdamicrovms.model.MicrovmImageHooks;
import software.amazon.awssdk.services.lambdamicrovms.model.MicrovmHooks;
import software.amazon.awssdk.services.lambdamicrovms.model.ResourceNotFoundException;

/**
 * Creates and queries Lambda MicroVM images for the scaleout-build agent.
 *
 * <p>A MicroVM image is built from a ZIP containing a {@code Dockerfile} (the code artifact) and a
 * managed base image ARN. Building it is a platform operation triggered via {@code CreateMicrovmImage},
 * then polled via {@code GetMicrovmImage} until the image is in a terminal state.
 *
 * <p>The image name is fixed ({@code scaleout-build-agent}). Each call to {@link #triggerBuild}
 * creates a new version; the platform auto-assigns the version number. The caller should check
 * {@link #getImageStatus} first to avoid submitting a second build when one is already running.
 *
 * <h2>SDK model notes</h2>
 *
 * The {@code lambdamicrovms} SDK model differs from the API documentation examples in several ways
 * confirmed by inspection of the 2.55.11 JAR:
 *
 * <ul>
 *   <li>{@code Hooks} has a single {@code port(int)} that applies to all hooks — not per-hook paths.
 *   <li>{@code MicrovmImageHooks} and {@code MicrovmHooks} use {@link HookState} enum (ENABLED/DISABLED)
 *       and separate {@code *TimeoutInSeconds(int)} fields — not a nested {@code Hook} object.</li>
 *   <li>{@code CreateMicrovmImageResponse} returns fields directly ({@code imageArn()},
 *       {@code state()}, {@code imageVersion()}) — not via a nested {@code image()} accessor.</li>
 *   <li>{@code GetMicrovmImageRequest} uses {@code imageIdentifier()} not {@code name()}/{@code version()}.
 *       Version-specific queries use {@code GetMicrovmImageVersionRequest} with both
 *       {@code imageIdentifier} and {@code imageVersion}.</li>
 * </ul>
 */
public final class MicroVmImageManager {

    /**
     * The fixed image name. All builds for all versions share this name; versions are
     * auto-assigned by the platform and returned in the {@link ImageStatus}.
     */
    public static final String IMAGE_NAME = "scaleout-build-agent";

    /**
     * The managed base image ARN suffix for Amazon Linux 2023 MicroVMs. ARM64 only: Lambda MicroVMs
     * run on ARM64, and GraalVM does not cross-compile.
     *
     * <p>The full ARN is {@code arn:aws:lambda:<region>:aws:microvm-image:al2023-1}.
     */
    public static final String AL2023_BASE_IMAGE_ARN_SUFFIX = "microvm-image:al2023-1";

    /**
     * Default port the agent listens on for lifecycle hooks. {@code Hooks.port()} applies to all
     * hooks in the MicroVM lifecycle.
     */
    static final int AGENT_HOOK_PORT = 9000;

    /**
     * Timeout for the {@code /ready} hook: the agent must signal boot-complete within this many
     * seconds or the image build fails. Generous to accommodate JVM warm-up.
     */
    static final int READY_HOOK_TIMEOUT_SECONDS = 120;

    /**
     * Timeout for the {@code /validate} hook: the platform samples hot memory pages for prefetching.
     * The agent runs a synthetic build here, so this must exceed a minimal compile.
     */
    static final int VALIDATE_HOOK_TIMEOUT_SECONDS = 180;

    /**
     * Timeout for the {@code /run} hook at runtime. The hook must return within this many seconds.
     * The platform ceiling is 60 s; the agent forks the build and returns immediately, so 30 s
     * is sufficient headroom without consuming the full allowance.
     */
    static final int RUN_HOOK_TIMEOUT_SECONDS = 30;

    private final LambdaMicrovmsClient client;

    public MicroVmImageManager(LambdaMicrovmsClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * The result of a {@link #triggerBuild} or {@link #getImageStatus} call.
     *
     * @param imageName    the image name ({@link #IMAGE_NAME})
     * @param imageVersion the auto-assigned version string (e.g. {@code "1.0"}), or {@code null}
     *                     if not yet assigned
     * @param state        the {@code MicrovmImageState} enum value as a string
     *                     (e.g. {@code "BUILDING"}, {@code "READY"}, {@code "FAILED"})
     * @param imageArn     the full image ARN, available once the image reaches {@code READY}
     */
    public record ImageStatus(String imageName, String imageVersion, String state, String imageArn) {

        /** @return whether this image is in a created/active state ready to serve MicroVM launches */
        public boolean isReady() {
            return "CREATED".equalsIgnoreCase(state) || "UPDATED".equalsIgnoreCase(state);
        }

        /** @return whether this image has permanently failed */
        public boolean isFailed() {
            return state != null && state.toUpperCase(java.util.Locale.ROOT).endsWith("_FAILED");
        }
    }

    /**
     * Submits a request to build a new version of the agent MicroVM image.
     *
     * <p>Returns as soon as the platform accepts the request (typically within seconds). The image
     * build takes 10–15 minutes; poll {@link #getImageStatus} until {@link ImageStatus#isReady()}.
     *
     * @param region         the AWS region to build in (e.g. {@code "eu-central-1"})
     * @param buildRoleArn   ARN of the role the platform assumes during image build
     * @param artifactS3Uri  S3 URI of the code artifact ZIP (e.g. {@code "s3://bucket/agent.zip"})
     * @return the initial image status — will be {@code BUILDING} or similar, not yet {@code READY}
     */
    public ImageStatus triggerBuild(String region, String buildRoleArn, String artifactS3Uri) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(buildRoleArn, "buildRoleArn");
        Objects.requireNonNull(artifactS3Uri, "artifactS3Uri");

        String baseImageArn = String.format("arn:aws:lambda:%s:aws:%s", region, AL2023_BASE_IMAGE_ARN_SUFFIX);

        CreateMicrovmImageResponse response = client.createMicrovmImage(
                CreateMicrovmImageRequest.builder()
                        .name(IMAGE_NAME)
                        .baseImageArn(baseImageArn)
                        .buildRoleArn(buildRoleArn)
                        .codeArtifact(CodeArtifact.builder().uri(artifactS3Uri).build())
                        .hooks(buildHooks())
                        .build());

        return new ImageStatus(
                IMAGE_NAME,
                response.imageVersion(),
                response.state() != null ? response.state().toString() : null,
                response.imageArn());
    }

    /**
     * Returns the current status of the image (latest version).
     *
     * @param imageIdentifier the full image ARN or image name
     * @return the image status, or {@code null} if no such image exists
     */
    public ImageStatus getImageStatus(String imageIdentifier) {
        Objects.requireNonNull(imageIdentifier, "imageIdentifier");
        try {
            GetMicrovmImageResponse response = client.getMicrovmImage(
                    GetMicrovmImageRequest.builder()
                            .imageIdentifier(imageIdentifier)
                            .build());
            return new ImageStatus(
                    IMAGE_NAME,
                    response.latestActiveImageVersion(),
                    response.state() != null ? response.state().toString() : null,
                    response.imageArn());
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    /**
     * Returns the build status for a specific image version (e.g. the version returned by
     * {@link #triggerBuild}).
     *
     * @param imageIdentifier the full image ARN or image name
     * @param imageVersion    the specific version string
     * @return the image status, or {@code null} if not found
     */
    public ImageStatus getImageVersionStatus(String imageIdentifier, String imageVersion) {
        Objects.requireNonNull(imageIdentifier, "imageIdentifier");
        Objects.requireNonNull(imageVersion, "imageVersion");
        try {
            GetMicrovmImageBuildResponse response = client.getMicrovmImageBuild(
                    GetMicrovmImageBuildRequest.builder()
                            .imageIdentifier(imageIdentifier)
                            .imageVersion(imageVersion)
                            .build());
            return new ImageStatus(
                    IMAGE_NAME,
                    imageVersion,
                    response.buildState() != null ? response.buildState().toString() : null,
                    response.imageArn());
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    // ── Hook configuration ────────────────────────────────────────────────────────────────────

    /**
     * Builds the hook configuration for the agent image.
     *
     * <p>The {@code Hooks.port()} applies to all hooks — it is not per-hook. The agent listens on
     * {@link #AGENT_HOOK_PORT} for all lifecycle hook calls from the platform.
     *
     * <p>{@code MicrovmImageHooks} — called during image construction:
     * <ul>
     *   <li>{@code ready}: agent returns 200 when fully booted; platform snapshots then.</li>
     *   <li>{@code validate}: agent runs a synthetic build; platform samples hot pages for
     *       prefetching to reduce cold-start latency on subsequent {@code RunMicrovm}.</li>
     * </ul>
     *
     * <p>{@code MicrovmHooks} — called at runtime after {@code RunMicrovm}:
     * <ul>
     *   <li>{@code run}: agent forks the build asynchronously and returns 200 immediately.
     *       The platform ceiling is 60 s; {@link #RUN_HOOK_TIMEOUT_SECONDS} is 30 s.</li>
     * </ul>
     */
    private static Hooks buildHooks() {
        return Hooks.builder()
                .port(AGENT_HOOK_PORT)
                .microvmImageHooks(MicrovmImageHooks.builder()
                        .ready(HookState.ENABLED)
                        .readyTimeoutInSeconds(READY_HOOK_TIMEOUT_SECONDS)
                        .validate(HookState.ENABLED)
                        .validateTimeoutInSeconds(VALIDATE_HOOK_TIMEOUT_SECONDS)
                        .build())
                .microvmHooks(MicrovmHooks.builder()
                        .run(HookState.ENABLED)
                        .runTimeoutInSeconds(RUN_HOOK_TIMEOUT_SECONDS)
                        .build())
                .build();
    }
}
