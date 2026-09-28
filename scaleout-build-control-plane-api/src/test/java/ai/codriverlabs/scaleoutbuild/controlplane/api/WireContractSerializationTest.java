/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The contract is only useful if it round-trips over the wire, and Java records plus
 * {@code java.time} types are the two things most likely to break that silently. These tests are the
 * cheapest possible guard against a DTO that compiles but cannot be deserialized by a client.
 */
class WireContractSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void createBuildRequestRoundTrips() throws Exception {
        CreateBuildRequest original = new CreateBuildRequest(
                new BuildSpec(List.of("native"), List.of("x86_64", "arm64"),
                        "ai.codriverlabs.example.HelloNative", "hello-native", "native-image",
                        List.of("-O2"), List.of(), 0, 120),
                List.of(new InputDescriptor("scaleout-build-example-app.jar", "a1b2", 5060),
                        new InputDescriptor("lib/commons-lang3-3.19.0.jar", "c3d4", 709075)),
                new RequestedResources("4096", "16384", 40),
                "scaleout-build-maven-plugin/1.1.0");

        assertThat(mapper.readValue(mapper.writeValueAsString(original), CreateBuildRequest.class))
                .isEqualTo(original);
    }

    @Test
    void createBuildResponseRoundTripsIncludingInstants() throws Exception {
        CreateBuildResponse original = new CreateBuildResponse("01J8ZQ", BuildState.PENDING,
                List.of(new CellStatus("NATIVE/ARM64", CellState.PENDING, null, null, null, 0,
                        List.of())),
                List.of(new UploadTarget("c3d4", "PUT", "https://example.invalid/put",
                        Instant.parse("2026-09-20T19:00:00Z"))),
                List.of("a1b2"), new RequestedResources("4096", "16384", 40), 30,
                Instant.parse("2026-09-20T21:00:00Z"));

        assertThat(mapper.readValue(mapper.writeValueAsString(original), CreateBuildResponse.class))
                .isEqualTo(original);
    }

    @Test
    void buildStatusRoundTripsWithArtifacts() throws Exception {
        BuildStatus original = new BuildStatus("01J8ZQ", BuildState.SUCCEEDED,
                "arn:aws:iam::123456789012:role/Developers",
                List.of(new CellStatus("NATIVE/X86_64", CellState.SUCCEEDED, "arn:aws:ecs:task/abc",
                        0, null, 1,
                        List.of(new ArtifactDescriptor("hello-native", "6f0de787", 13372680)))),
                Instant.parse("2026-09-20T19:00:00Z"), Instant.parse("2026-09-20T19:00:05Z"),
                Instant.parse("2026-09-20T19:02:00Z"), Instant.parse("2026-09-20T21:00:00Z"));

        assertThat(mapper.readValue(mapper.writeValueAsString(original), BuildStatus.class))
                .isEqualTo(original);
    }

    @Test
    void logEventRoundTripsForEachShape() throws Exception {
        for (LogEvent original : List.of(
                LogEvent.log("NATIVE/ARM64", 1789867513606L, "Finished generating 'hello-native'"),
                LogEvent.cellState("NATIVE/ARM64", CellState.SUCCEEDED),
                LogEvent.streamEnd(StreamEndReason.LAMBDA_TIMEOUT,
                        Map.of("NATIVE/X86_64", 1789867601234L, "NATIVE/ARM64", 1789867598001L)))) {
            assertThat(mapper.readValue(mapper.writeValueAsString(original), LogEvent.class))
                    .isEqualTo(original);
        }
    }

    /**
     * The resume watermark must survive the wire as a per-cell map. If it ever collapses to a
     * scalar, reconnecting a multi-cell stream silently drops the slower cell's output — the bug
     * fixed in commit {@code 7782771}, reintroduced at a different layer.
     */
    @Test
    void streamEndCarriesOneWatermarkPerCell() throws Exception {
        LogEvent original = LogEvent.streamEnd(StreamEndReason.LAMBDA_TIMEOUT,
                Map.of("NATIVE/X86_64", 1789867601234L, "NATIVE/ARM64", 1789867598001L));

        LogEvent parsed = mapper.readValue(mapper.writeValueAsString(original), LogEvent.class);

        assertThat(parsed.nextSince())
                .hasSize(2)
                .containsEntry("NATIVE/X86_64", 1789867601234L)
                .containsEntry("NATIVE/ARM64", 1789867598001L);
        assertThat(parsed.reason().shouldReconnect()).isTrue();
        assertThat(StreamEndReason.BUILD_TERMINAL.shouldReconnect()).isFalse();
    }

    @Test
    void errorResponseRoundTripsWithMissingItems() throws Exception {
        ErrorResponse original = new ErrorResponse("InputsMissing",
                "2 manifest digests are not staged", "req-123", List.of("a1b2", "c3d4"));

        assertThat(mapper.readValue(mapper.writeValueAsString(original), ErrorResponse.class))
                .isEqualTo(original);
    }

    @Test
    void nullCollectionsNormalizeToEmptyRatherThanNull() {
        assertThat(new BuildSpec(null, null, null, null, null, null, null, 0, 0).buildKinds())
                .isEmpty();
        assertThat(new CreateBuildRequest(null, null, null, null).inputs()).isEmpty();
        assertThat(LogEvent.log("c", 1L, "m").nextSince()).isEmpty();
        assertThat(ErrorResponse.of("E", "m", "r").missingItems()).isEmpty();
    }

    @Test
    void terminalStatesAreClassifiedConsistently() {
        assertThat(List.of(BuildState.SUCCEEDED, BuildState.FAILED, BuildState.CANCELLED,
                BuildState.EXPIRED)).allMatch(BuildState::isTerminal);
        assertThat(List.of(BuildState.PENDING, BuildState.STAGED, BuildState.RUNNING))
                .noneMatch(BuildState::isTerminal);
        assertThat(List.of(CellState.SUCCEEDED, CellState.FAILED, CellState.CANCELLED))
                .allMatch(CellState::isTerminal);
        assertThat(List.of(CellState.PENDING, CellState.PROVISIONING, CellState.RUNNING))
                .noneMatch(CellState::isTerminal);
    }
}
