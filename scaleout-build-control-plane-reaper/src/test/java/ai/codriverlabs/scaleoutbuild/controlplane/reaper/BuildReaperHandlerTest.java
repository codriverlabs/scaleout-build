/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.reaper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.lambdamicrovms.LambdaMicrovmsClient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BuildReaperHandlerTest {

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    @Mock
    private DynamoDbClient dynamo;
    @Mock
    private EcsClient ecs;
    @Mock
    private LambdaMicrovmsClient microvms;

    private BuildReaperHandler handler() {
        return new BuildReaperHandler(dynamo, ecs, microvms, "scaleout-builds",
                "arn:aws:ecs:eu-west-1:1:cluster/scaleout-build", Duration.ofSeconds(120));
    }

    private static Map<String, AttributeValue> build(String id, String heartbeat, String expires,
                                                     List<String> taskArns) {
        return build(id, heartbeat, expires, taskArns, List.of());
    }

    private static Map<String, AttributeValue> build(String id, String heartbeat, String expires,
                                                     List<String> taskArns, List<String> microvmIds) {
        var item = new java.util.HashMap<String, AttributeValue>();
        item.put("buildId", AttributeValue.fromS(id));
        item.put("state", AttributeValue.fromS("RUNNING"));
        if (heartbeat != null) {
            item.put("lastHeartbeatAt", AttributeValue.fromS(heartbeat));
        }
        if (expires != null) {
            item.put("expiresAt", AttributeValue.fromS(expires));
        }
        if (!taskArns.isEmpty()) {
            item.put("taskArns", AttributeValue.fromSs(taskArns));
        }
        if (!microvmIds.isEmpty()) {
            item.put("microvmIds", AttributeValue.fromSs(microvmIds));
        }
        return item;
    }

    private void givenScanReturns(Map<String, AttributeValue>... items) {
        when(dynamo.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder().items(List.of(items)).build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reapsABuildWhoseClientStoppedHeartbeating() {
        givenScanReturns(build("b1", "2026-09-21T11:55:00Z", "2026-09-21T13:00:00Z",
                List.of("arn:task/1", "arn:task/2")));

        assertThat(handler().reap(NOW)).isEqualTo(1);

        // Both cells' tasks must be stopped, not just the first.
        verify(ecs, org.mockito.Mockito.times(2)).stopTask(any(Consumer.class));
        verify(dynamo).updateItem(any(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesALiveBuildAlone() {
        givenScanReturns(build("b1", NOW.minusSeconds(5).toString(),
                NOW.plusSeconds(3600).toString(), List.of("arn:task/1")));

        assertThat(handler().reap(NOW)).isZero();

        verify(ecs, never()).stopTask(any(Consumer.class));
        verify(dynamo, never()).updateItem(any(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.class));
    }

    @Test
    void reapsAPastDeadlineBuildEvenWhileItIsStillHeartbeating() {
        // Both checks are needed independently: a client that keeps heartbeating a build which should
        // have finished must still be stopped.
        var item = build("b1", NOW.toString(), NOW.minusSeconds(1).toString(), List.of("arn:task/1"));

        assertThat(handler().isOrphaned(item, NOW)).isTrue();
    }

    @Test
    void toleratesMissingTimestampsRatherThanReapingIndiscriminately() {
        assertThat(handler().isOrphaned(build("b1", null, null, List.of()), NOW)).isFalse();
        assertThat(handler().isOrphaned(build("b1", "not-a-timestamp", null, List.of()), NOW))
                .isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void marksTheBuildExpiredEvenWhenStopTaskFails() {
        // Otherwise the build stays non-terminal and the reaper retries it on every run, forever.
        givenScanReturns(build("b1", "2026-09-21T11:00:00Z", null, List.of("arn:task/1")));
        when(ecs.stopTask(any(Consumer.class))).thenThrow(new RuntimeException("already stopped"));

        assertThat(handler().reap(NOW)).isEqualTo(1);

        verify(dynamo).updateItem(any(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.class));
    }

    @Test
    void scanFiltersOutTerminalBuildsServerSide() {
        givenScanReturns();
        handler().reap(NOW);

        var captor = org.mockito.ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamo).scan(captor.capture());
        // Keeps the returned page small once the table holds months of history.
        assertThat(captor.getValue().filterExpression()).contains("NOT (#s IN");
        assertThat(captor.getValue().expressionAttributeValues().values())
                .extracting(AttributeValue::s)
                .containsExactlyInAnyOrder("SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED");
    }

    // ── MicroVM-backed cells ────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void reapsAMicrovmBackedOrphanedBuild() {
        givenScanReturns(build("mv1", "2026-09-21T11:55:00Z", "2026-09-21T13:00:00Z",
                List.of(), List.of("microvm-abc123", "microvm-def456")));

        assertThat(handler().reap(NOW)).isEqualTo(1);

        verify(microvms, org.mockito.Mockito.times(2))
                .terminateMicrovm(any(Consumer.class));
        verify(dynamo).updateItem(any(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.class));
        verify(ecs, never()).stopTask(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reapsAMixedBackendBuild() {
        // One build can have both Fargate and MicroVM cells (e.g. x86_64 on Fargate, arm64 on a
        // MicroVM) -- the reaper must stop/terminate both, not just one.
        givenScanReturns(build("mv2", "2026-09-21T11:55:00Z", "2026-09-21T13:00:00Z",
                List.of("arn:task/1"), List.of("microvm-abc123")));

        assertThat(handler().reap(NOW)).isEqualTo(1);

        verify(ecs, org.mockito.Mockito.times(1)).stopTask(any(Consumer.class));
        verify(microvms, org.mockito.Mockito.times(1)).terminateMicrovm(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void marksTheBuildExpiredEvenWhenTerminateMicrovmFails() {
        givenScanReturns(build("mv3", "2026-09-21T11:00:00Z", null, List.of(),
                List.of("microvm-abc123")));
        when(microvms.terminateMicrovm(any(Consumer.class)))
                .thenThrow(new RuntimeException("already terminated"));

        assertThat(handler().reap(NOW)).isEqualTo(1);

        verify(dynamo).updateItem(any(software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateExpressionRemovesBothFlatAttributes() {
        givenScanReturns(build("mv4", "2026-09-21T11:55:00Z", null, List.of("arn:task/1"),
                List.of("microvm-abc123")));

        handler().reap(NOW);

        var captor = org.mockito.ArgumentCaptor.forClass(
                software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.class);
        verify(dynamo).updateItem(captor.capture());
        assertThat(captor.getValue().updateExpression()).contains("REMOVE #t, #m");
        assertThat(captor.getValue().expressionAttributeNames())
                .containsEntry("#t", "taskArns")
                .containsEntry("#m", "microvmIds");
    }
}
