/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;

/**
 * Builds the {@code SCALEOUT_BUILD_*} task-override environment the agent container reads.
 *
 * <p><b>This is the single source for that contract, and it must stay that way.</b> The names here
 * are matched literally by {@code AgentConfig} inside the container, so writer and reader have no
 * compile-time link — nothing fails at build time if they diverge. That has already gone wrong once:
 * commit {@code b33f9e2} had to fix 14 environment variables still named {@code JOBRUNR_BUILD_*} on
 * both sides, missed by earlier bulk renames precisely because a plain SCREAMING_SNAKE_CASE string
 * matches no code pattern.
 *
 * <p>Two independent callers now assemble this environment — the Maven plugin's direct-ECS backend
 * and the control-plane service — so a second copy would reintroduce exactly that failure mode with
 * twice the surface.
 */
public final class AgentEnvironment {

    private AgentEnvironment() {
    }

    /** Mutable builder, since which variables apply depends on the staging mode and build kind. */
    public static final class Builder {
        private final List<KeyValuePair> environment = new ArrayList<>();

        private Builder(String buildId, BuildKind buildKind, Architecture architecture,
                        String stagingRelativePath, String argFileName) {
            add("SCALEOUT_BUILD_MOUNT_ROOT", TaskDefinitionRegistrar.MOUNT_CONTAINER_PATH);
            add("SCALEOUT_BUILD_ID", buildId);
            add("SCALEOUT_BUILD_KIND", buildKind.configValue());
            add("SCALEOUT_BUILD_ARCH", architecture.name());
            add("SCALEOUT_BUILD_STAGING_RELATIVE_PATH", stagingRelativePath);
            add("SCALEOUT_BUILD_ARG_FILE_NAME", argFileName);
        }

        /** Set only in the direct-S3 staging mode, where the agent does its own S3 I/O. */
        public Builder s3Bucket(String bucket) {
            if (bucket != null && !bucket.isBlank()) {
                add("SCALEOUT_BUILD_S3_BUCKET", bucket);
            }
            return this;
        }

        public Builder profileRelativePath(String profileRelativePath) {
            if (profileRelativePath != null && !profileRelativePath.isBlank()) {
                add("SCALEOUT_BUILD_PROFILE_RELATIVE_PATH", profileRelativePath);
            }
            return this;
        }

        public Builder expectedArtifacts(List<String> expectedArtifacts) {
            if (expectedArtifacts != null && !expectedArtifacts.isEmpty()) {
                add("SCALEOUT_BUILD_EXPECTED_ARTIFACTS", String.join(",", expectedArtifacts));
            }
            return this;
        }

        public Builder extraNativeImageArgs(List<String> extraNativeImageArgs) {
            if (extraNativeImageArgs != null && !extraNativeImageArgs.isEmpty()) {
                add("SCALEOUT_BUILD_EXTRA_NATIVE_IMAGE_ARGS", String.join(" ", extraNativeImageArgs));
            }
            return this;
        }

        public Builder timeoutMinutes(int timeoutMinutes) {
            if (timeoutMinutes > 0) {
                add("SCALEOUT_BUILD_TIMEOUT_MINUTES", String.valueOf(timeoutMinutes));
            }
            return this;
        }

        public List<KeyValuePair> build() {
            return List.copyOf(environment);
        }

        private void add(String name, String value) {
            environment.add(KeyValuePair.builder().name(name).value(value).build());
        }
    }

    /**
     * @param stagingRelativePath the cell's staging prefix, as produced by
     *        {@code StagingLayout#stagingPath}
     * @param argFileName         the {@code native-image} argfile name within that prefix
     */
    public static Builder builder(String buildId, BuildKind buildKind, Architecture architecture,
                                  String stagingRelativePath, String argFileName) {
        return new Builder(buildId, buildKind, architecture, stagingRelativePath, argFileName);
    }
}
