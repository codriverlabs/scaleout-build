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

    private static final Map<String, String> MINIMAL_REQUIRED = Map.of(
            AgentConfig.ENV_DSQL_ENDPOINT, "abcd1234.dsql.us-east-1.on.aws",
            AgentConfig.ENV_DSQL_REGION, "us-east-1");

    @Test
    void appliesDefaultsWhenOnlyRequiredVariablesAreSet() {
        AgentConfig config = AgentConfig.fromMap(MINIMAL_REQUIRED);

        assertThat(config.mountRoot()).isEqualTo(Path.of("/mnt/build"));
        assertThat(config.nativeImageCommand()).containsExactly("native-image");
        assertThat(config.tempDirectory()).isEqualTo(Path.of("/tmp"));
        assertThat(config.idleTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.maxDuration()).isEqualTo(Duration.ofHours(2));
        assertThat(config.jobsToProcess()).isEqualTo(1);
        assertThat(config.architecture()).isEqualTo(
                Architecture.host().orElseThrow(() -> new AssertionError("host architecture unknown")));
        assertThat(config.dsqlEndpoint()).isEqualTo("abcd1234.dsql.us-east-1.on.aws");
        assertThat(config.dsqlRegion()).isEqualTo("us-east-1");
        assertThat(config.dsqlUser()).isEqualTo("admin");
        assertThat(config.schemaPrefix()).isEqualTo("jobrunr_");
    }

    @Test
    void readsEveryVariableWhenSet() {
        Map<String, String> environment = merged(Map.of(
                AgentConfig.ENV_MOUNT_ROOT, "/data/build",
                AgentConfig.ENV_ARCH, "arm64",
                AgentConfig.ENV_NATIVE_IMAGE, "docker run --rm native-image",
                AgentConfig.ENV_TEMP_DIR, "/scratch",
                AgentConfig.ENV_IDLE_TIMEOUT_SECONDS, "42",
                AgentConfig.ENV_MAX_DURATION_MINUTES, "7",
                AgentConfig.ENV_JOBS, "3",
                AgentConfig.ENV_DSQL_USER, "scoped-role",
                AgentConfig.ENV_SCHEMA_PREFIX, "custom_"));

        AgentConfig config = AgentConfig.fromMap(environment);

        assertThat(config.mountRoot()).isEqualTo(Path.of("/data/build"));
        assertThat(config.architecture()).isEqualTo(Architecture.ARM64);
        assertThat(config.nativeImageCommand()).containsExactly("docker", "run", "--rm", "native-image");
        assertThat(config.tempDirectory()).isEqualTo(Path.of("/scratch"));
        assertThat(config.idleTimeout()).isEqualTo(Duration.ofSeconds(42));
        assertThat(config.maxDuration()).isEqualTo(Duration.ofMinutes(7));
        assertThat(config.jobsToProcess()).isEqualTo(3);
        assertThat(config.dsqlUser()).isEqualTo("scoped-role");
        assertThat(config.schemaPrefix()).isEqualTo("custom_");
    }

    @Test
    void requiresTheDsqlEndpoint() {
        assertThatThrownBy(() -> AgentConfig.fromMap(Map.of(AgentConfig.ENV_DSQL_REGION, "us-east-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AgentConfig.ENV_DSQL_ENDPOINT);
    }

    @Test
    void requiresTheDsqlRegion() {
        assertThatThrownBy(() -> AgentConfig.fromMap(
                Map.of(AgentConfig.ENV_DSQL_ENDPOINT, "abcd1234.dsql.us-east-1.on.aws")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AgentConfig.ENV_DSQL_REGION);
    }

    @Test
    void rejectsAnUnrecognisedArchitecture() {
        assertThatThrownBy(() -> AgentConfig.fromMap(merged(Map.of(AgentConfig.ENV_ARCH, "risc-v"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("risc-v");
    }

    @Test
    void rejectsANonPositiveJobsValue() {
        assertThatThrownBy(() -> AgentConfig.fromMap(merged(Map.of(AgentConfig.ENV_JOBS, "0"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AgentConfig.ENV_JOBS);
    }

    @Test
    void rejectsANonNumericDuration() {
        assertThatThrownBy(() -> AgentConfig.fromMap(
                merged(Map.of(AgentConfig.ENV_IDLE_TIMEOUT_SECONDS, "soon"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AgentConfig.ENV_IDLE_TIMEOUT_SECONDS);
    }

    private static Map<String, String> merged(Map<String, String> extra) {
        var combined = new java.util.HashMap<>(MINIMAL_REQUIRED);
        combined.putAll(extra);
        return combined;
    }
}
