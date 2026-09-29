/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.reaper;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.ecs.EcsClient;

/**
 * Stops the ECS tasks of builds whose client has died, and marks them {@code EXPIRED}.
 *
 * <h2>Why this is a separate function rather than an endpoint on the service</h2>
 *
 * The control-plane service runs behind the AWS Lambda Web Adapter, which converts <em>every</em>
 * invocation into an HTTP request against the packaged app. An EventBridge scheduled event would
 * therefore arrive as an ordinary POST carrying no {@code x-amzn-request-context}, which the
 * service's fail-closed {@code CallerIdentityFilter} correctly rejects as unauthenticated. Exempting
 * that path would make it reachable through the public Function URL as well, turning "reap builds"
 * into an unauthenticated operation.
 *
 * <p>A separate function with no Function URL has no such ambiguity: the only principal that can
 * invoke it is the EventBridge rule, enforced by its resource policy.
 *
 * <h2>Why a reaper is needed at all</h2>
 *
 * In the direct-ECS model the client process <em>was</em> the supervisor, so a dead client and a dead
 * supervisor were the same event. Behind a control plane nothing stops a task unless the server does.
 *
 * <p>Note the exposure this bounds, and the exposure it does not: the agent enforces its own per-build
 * timeout, so an orphaned task self-terminates eventually. This reaper turns "pays until the agent's own
 * timeout" into "pays until the next scheduled run", which is a cost optimisation rather than a fix for
 * unbounded liability.
 *
 * <p><b>That was not true when it was written.</b> {@code BuildSpec.timeoutMinutes} defaults to 0 from the
 * client and documented "0 means server default", but no server default existed: the 0 travelled through to
 * {@code AgentEnvironment}, which only sets the timeout variable when positive, so the agent reached
 * {@code process.waitFor()} with no deadline. Until {@code scaleout.ecs.default-cell-timeout-minutes} was
 * added, this reaper was the <em>only</em> bound on a crashed client's Fargate bill. Kept as a note because
 * the defence-in-depth reading above is what makes it safe to reason about removing or rescheduling this
 * function, and it was false for as long as the claim existed. Streaming makes it matter more than it looks: AWS documents that
 * a streamed response is not interrupted when the client connection breaks, so a client hanging up
 * cannot be detected and cannot be used as a cancellation signal.
 */
public class BuildReaperHandler implements RequestHandler<Map<String, Object>, String> {

    private static final String ATTR_BUILD_ID = "buildId";
    private static final String ATTR_STATE = "state";
    private static final String ATTR_LAST_HEARTBEAT_AT = "lastHeartbeatAt";
    private static final String ATTR_EXPIRES_AT = "expiresAt";
    private static final String ATTR_TASK_ARNS = "taskArns";

    private static final List<String> TERMINAL_STATES =
            List.of("SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED");

    private final DynamoDbClient dynamo;
    private final EcsClient ecs;
    private final String tableName;
    private final String clusterArn;
    private final Duration heartbeatGrace;

    public BuildReaperHandler() {
        this(DynamoDbClient.create(), EcsClient.create(),
                env("SCALEOUT_BUILDS_TABLE", "scaleout-builds"),
                env("SCALEOUT_ECS_CLUSTER_ARN", ""),
                Duration.ofSeconds(Long.parseLong(env("SCALEOUT_HEARTBEAT_GRACE_SECONDS", "120"))));
    }

    BuildReaperHandler(DynamoDbClient dynamo, EcsClient ecs, String tableName, String clusterArn,
                       Duration heartbeatGrace) {
        this.dynamo = dynamo;
        this.ecs = ecs;
        this.tableName = tableName;
        this.clusterArn = clusterArn;
        this.heartbeatGrace = heartbeatGrace;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        int reaped = reap(Instant.now());
        return "reaped=" + reaped;
    }

    /** Visible for testing. */
    int reap(Instant now) {
        int reaped = 0;
        Map<String, AttributeValue> exclusiveStartKey = null;
        do {
            var requestBuilder = software.amazon.awssdk.services.dynamodb.model.ScanRequest.builder()
                    .tableName(tableName)
                    // Only non-terminal builds can be orphaned. Filtering server-side keeps the
                    // returned page small even once the table holds months of history.
                    .filterExpression("NOT (#s IN (:t1, :t2, :t3, :t4))")
                    .expressionAttributeNames(Map.of("#s", ATTR_STATE))
                    .expressionAttributeValues(Map.of(
                            ":t1", AttributeValue.fromS(TERMINAL_STATES.get(0)),
                            ":t2", AttributeValue.fromS(TERMINAL_STATES.get(1)),
                            ":t3", AttributeValue.fromS(TERMINAL_STATES.get(2)),
                            ":t4", AttributeValue.fromS(TERMINAL_STATES.get(3))));
            if (exclusiveStartKey != null) {
                requestBuilder.exclusiveStartKey(exclusiveStartKey);
            }
            var response = dynamo.scan(requestBuilder.build());
            for (Map<String, AttributeValue> item : response.items()) {
                if (isOrphaned(item, now)) {
                    reapOne(item);
                    reaped++;
                }
            }
            exclusiveStartKey = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                    ? response.lastEvaluatedKey() : null;
        } while (exclusiveStartKey != null);
        return reaped;
    }

    /**
     * A build is orphaned if its client stopped heartbeating, or if it passed its absolute deadline.
     * Both checks are needed: the heartbeat catches a crashed client quickly, while the deadline
     * catches a client that is somehow still heartbeating a build that should have finished.
     */
    boolean isOrphaned(Map<String, AttributeValue> item, Instant now) {
        Instant expiresAt = instant(item, ATTR_EXPIRES_AT);
        if (expiresAt != null && now.isAfter(expiresAt)) {
            return true;
        }
        Instant lastHeartbeat = instant(item, ATTR_LAST_HEARTBEAT_AT);
        return lastHeartbeat != null && now.isAfter(lastHeartbeat.plus(heartbeatGrace));
    }

    private void reapOne(Map<String, AttributeValue> item) {
        String buildId = item.containsKey(ATTR_BUILD_ID) ? item.get(ATTR_BUILD_ID).s() : null;
        List<String> taskArns = item.containsKey(ATTR_TASK_ARNS)
                ? item.get(ATTR_TASK_ARNS).ss() : List.of();
        List<String> failures = new ArrayList<>();
        for (String taskArn : taskArns) {
            try {
                ecs.stopTask(b -> b.cluster(clusterArn).task(taskArn)
                        .reason("scaleout control plane reaped an orphaned build"));
            } catch (RuntimeException e) {
                // Already stopped, or transient. Marking the build EXPIRED regardless is correct:
                // leaving it non-terminal would have the reaper retry it forever.
                failures.add(taskArn + ": " + e.getMessage());
            }
        }
        var key = new HashMap<String, AttributeValue>();
        key.put(ATTR_BUILD_ID, AttributeValue.fromS(buildId));
        dynamo.updateItem(b -> b.tableName(tableName).key(key)
                .updateExpression("SET #s = :expired REMOVE #t")
                .expressionAttributeNames(Map.of("#s", ATTR_STATE, "#t", ATTR_TASK_ARNS))
                .expressionAttributeValues(Map.of(":expired", AttributeValue.fromS("EXPIRED"))));
        System.out.printf("Reaped build %s, stopped %d task(s)%s%n", buildId, taskArns.size(),
                failures.isEmpty() ? "" : " (StopTask issues: " + failures + ")");
    }

    private static Instant instant(Map<String, AttributeValue> item, String attribute) {
        AttributeValue value = item.get(attribute);
        if (value == null || value.s() == null) {
            return null;
        }
        try {
            return Instant.parse(value.s());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
