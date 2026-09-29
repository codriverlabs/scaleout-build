/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.ecs;

import static org.assertj.core.api.Assertions.assertThat;

import ai.codriverlabs.scaleoutbuild.build.Architecture;
import ai.codriverlabs.scaleoutbuild.build.BuildKind;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;

/**
 * Pins the agent's build timeout across the boundary that made it disappear.
 *
 * <p>{@code AgentEnvironment} only emits {@code SCALEOUT_BUILD_TIMEOUT_MINUTES} when the value is positive,
 * and {@code NativeImageBuildExecutor} falls back to an unbounded {@code process.waitFor()} when the variable
 * is absent. So a 0 travelling from the client all the way through meant "no deadline at all", while
 * {@code BuildSpec} documented it as "server default". These tests fix the contract in place: 0 must never
 * reach the environment, because the service is now responsible for substituting its default first.
 */
class AgentEnvironmentTimeoutTest {

    private static final String VAR = "SCALEOUT_BUILD_TIMEOUT_MINUTES";

    @Test
    void positiveTimeoutReachesTheAgent() {
        assertThat(environment(30)).containsEntry(VAR, "30");
    }

    /**
     * The gap this whole change exists to close. Left as a test rather than a comment because the omission
     * is silent: nothing fails, the agent simply never gets a deadline, and the cost only shows up on a
     * crashed client's bill.
     */
    @Test
    void zeroEmitsNothing_soTheAgentWouldWaitForever() {
        assertThat(environment(0)).doesNotContainKey(VAR);
    }

    @Test
    void negativeIsTreatedAsAbsentRatherThanPassedThrough() {
        assertThat(environment(-1)).doesNotContainKey(VAR);
    }

    private Map<String, String> environment(int timeoutMinutes) {
        List<KeyValuePair> pairs = AgentEnvironment.builder("b1", BuildKind.NATIVE, Architecture.ARM64,
                        "workspace/owner/builds/b1/native/arm64", "native-image.args")
                .timeoutMinutes(timeoutMinutes)
                .build();
        return pairs.stream().collect(Collectors.toMap(KeyValuePair::name, KeyValuePair::value));
    }
}
