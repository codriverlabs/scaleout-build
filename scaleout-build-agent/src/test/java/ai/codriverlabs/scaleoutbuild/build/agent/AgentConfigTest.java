/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.build.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentConfigTest {

    private static final Architecture HOST = Architecture.host().orElse(Architecture.X86_64);

    private static final Map<String, String> MINIMAL_REQUIRED = Map.of(
            AgentConfig.ENV_BUILD_ID, "build-1",
            AgentConfig.ENV_BUILD_KIND, "native",
            AgentConfig.ENV_ARCH, HOST.name(),
            AgentConfig.ENV_STAGING_RELATIVE_PATH, "builds/build-1/" + HOST.stagingDirName());

    @Test
    void appliesDefaultsWhenOnlyRequiredVariablesAreSet() {
        AgentConfig config = AgentConfig.fromMap(MINIMAL_REQUIRED);

        assertThat(config.mountRoot()).isEqualTo(Path.of("/mnt/build"));
        assertThat(config.nativeImageCommand()).containsExactly("native-image");
        assertThat(config.tempDirectory()).isEqualTo(Path.of("/tmp"));
        assertThat(config.buildId()).isEqualTo("build-1");
        assertThat(config.buildKind()).isEqualTo(BuildKind.NATIVE);
        assertThat(config.architecture()).isEqualTo(HOST);
        assertThat(config.argFileName()).isEqualTo("native-image.args");
        assertThat(config.profileRelativePath()).isNull();
        assertThat(config.expectedArtifacts()).isEmpty();
        assertThat(config.extraNativeImageArgs()).isEmpty();
        assertThat(config.timeoutMinutes()).isZero();
    }

    @Test
    void readsEveryVariableWhenSet() {
        Map<String, String> environment = merged(Map.of(
                AgentConfig.ENV_MOUNT_ROOT, "/data/build",
                AgentConfig.ENV_NATIVE_IMAGE, "docker run --rm native-image",
                AgentConfig.ENV_TEMP_DIR, "/scratch",
                AgentConfig.ENV_ARG_FILE_NAME, "custom.args",
                AgentConfig.ENV_EXPECTED_ARTIFACTS, "app, app.debug",
                AgentConfig.ENV_EXTRA_NATIVE_IMAGE_ARGS, "-H:+ReportExceptionStackTraces --verbose",
                AgentConfig.ENV_TIMEOUT_MINUTES, "45"));

        AgentConfig config = AgentConfig.fromMap(environment);

        assertThat(config.mountRoot()).isEqualTo(Path.of("/data/build"));
        assertThat(config.nativeImageCommand()).containsExactly("docker", "run", "--rm", "native-image");
        assertThat(config.tempDirectory()).isEqualTo(Path.of("/scratch"));
        assertThat(config.argFileName()).isEqualTo("custom.args");
        assertThat(config.expectedArtifacts()).containsExactly("app", "app.debug");
        assertThat(config.extraNativeImageArgs())
                .containsExactly("-H:+ReportExceptionStackTraces", "--verbose");
        assertThat(config.timeoutMinutes()).isEqualTo(45);
    }

    @Test
    void readsTheProfilePathForPgoOptimize() {
        Map<String, String> environment = merged(Map.of(
                AgentConfig.ENV_BUILD_KIND, "native-pgo-optimize",
                AgentConfig.ENV_PROFILE_RELATIVE_PATH, "default.iprof"));

        AgentConfig config = AgentConfig.fromMap(environment);

        assertThat(config.buildKind()).isEqualTo(BuildKind.NATIVE_PGO_OPTIMIZE);
        assertThat(config.profileRelativePath()).isEqualTo("default.iprof");
    }

    @Test
    void requiresTheProfilePathForPgoOptimize() {
        Map<String, String> environment = merged(Map.of(
                AgentConfig.ENV_BUILD_KIND, "native-pgo-optimize"));

        assertThatThrownBy(() -> AgentConfig.fromMap(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AgentConfig.ENV_PROFILE_RELATIVE_PATH);
    }

    @Test
    void requiresTheBuildId() {
        assertThatThrownBy(() -> AgentConfig.fromMap(Map.of(
                AgentConfig.ENV_BUILD_KIND, "native",
                AgentConfig.ENV_ARCH, HOST.name(),
                AgentConfig.ENV_STAGING_RELATIVE_PATH, "builds/x/x86_64")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AgentConfig.ENV_BUILD_ID);
    }

    @Test
    void requiresTheStagingRelativePath() {
        assertThatThrownBy(() -> AgentConfig.fromMap(Map.of(
                AgentConfig.ENV_BUILD_ID, "build-1",
                AgentConfig.ENV_BUILD_KIND, "native",
                AgentConfig.ENV_ARCH, HOST.name())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AgentConfig.ENV_STAGING_RELATIVE_PATH);
    }

    @Test
    void rejectsJvmAsABuildKindSinceItNeverLaunchesARemoteTask() {
        assertThatThrownBy(() -> AgentConfig.fromMap(merged(Map.of(
                AgentConfig.ENV_BUILD_KIND, "jvm"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JVM");
    }

    @Test
    void rejectsAnArchitectureMismatchWithTheHost() {
        Architecture other = HOST == Architecture.X86_64 ? Architecture.ARM64 : Architecture.X86_64;
        assertThatThrownBy(() -> AgentConfig.fromMap(merged(Map.of(
                AgentConfig.ENV_ARCH, other.name()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot cross-compile");
    }

    @Test
    void rejectsAnUnrecognisedArchitecture() {
        assertThatThrownBy(() -> AgentConfig.fromMap(merged(Map.of(AgentConfig.ENV_ARCH, "risc-v"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("risc-v");
    }

    @Test
    void rejectsANonNumericTimeout() {
        assertThatThrownBy(() -> AgentConfig.fromMap(
                merged(Map.of(AgentConfig.ENV_TIMEOUT_MINUTES, "soon"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AgentConfig.ENV_TIMEOUT_MINUTES);
    }

    private static Map<String, String> merged(Map<String, String> extra) {
        var combined = new HashMap<>(MINIMAL_REQUIRED);
        combined.putAll(extra);
        return combined;
    }
}
