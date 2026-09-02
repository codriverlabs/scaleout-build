/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cloud.plasticity.jobrunr.build.Architecture;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentConfigTest {

    @Test
    void appliesDefaultsWhenNoVariablesAreSet() {
        AgentConfig config = AgentConfig.fromMap(Map.of());

        assertThat(config.mountRoot()).isEqualTo(Path.of("/mnt/build"));
        assertThat(config.nativeImageCommand()).containsExactly("native-image");
        assertThat(config.tempDirectory()).isEqualTo(Path.of("/tmp"));
        assertThat(config.idleTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.maxDuration()).isEqualTo(Duration.ofHours(2));
        assertThat(config.jobsToProcess()).isEqualTo(1);
        assertThat(config.architecture()).isEqualTo(
                Architecture.host().orElseThrow(() -> new AssertionError("host architecture unknown")));
    }

    @Test
    void readsEveryVariableWhenSet() {
        Map<String, String> environment = Map.of(
                AgentConfig.ENV_MOUNT_ROOT, "/data/build",
                AgentConfig.ENV_ARCH, "arm64",
                AgentConfig.ENV_NATIVE_IMAGE, "docker run --rm native-image",
                AgentConfig.ENV_TEMP_DIR, "/scratch",
                AgentConfig.ENV_IDLE_TIMEOUT_SECONDS, "42",
                AgentConfig.ENV_MAX_DURATION_MINUTES, "7",
                AgentConfig.ENV_JOBS, "3");

        AgentConfig config = AgentConfig.fromMap(environment);

        assertThat(config.mountRoot()).isEqualTo(Path.of("/data/build"));
        assertThat(config.architecture()).isEqualTo(Architecture.ARM64);
        assertThat(config.nativeImageCommand()).containsExactly("docker", "run", "--rm", "native-image");
        assertThat(config.tempDirectory()).isEqualTo(Path.of("/scratch"));
        assertThat(config.idleTimeout()).isEqualTo(Duration.ofSeconds(42));
        assertThat(config.maxDuration()).isEqualTo(Duration.ofMinutes(7));
        assertThat(config.jobsToProcess()).isEqualTo(3);
    }

    @Test
    void rejectsAnUnrecognisedArchitecture() {
        assertThatThrownBy(() -> AgentConfig.fromMap(Map.of(AgentConfig.ENV_ARCH, "risc-v")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("risc-v");
    }

    @Test
    void rejectsANonPositiveJobsValue() {
        assertThatThrownBy(() -> AgentConfig.fromMap(Map.of(AgentConfig.ENV_JOBS, "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AgentConfig.ENV_JOBS);
    }

    @Test
    void rejectsANonNumericDuration() {
        assertThatThrownBy(
                () -> AgentConfig.fromMap(Map.of(AgentConfig.ENV_IDLE_TIMEOUT_SECONDS, "soon")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AgentConfig.ENV_IDLE_TIMEOUT_SECONDS);
    }
}
