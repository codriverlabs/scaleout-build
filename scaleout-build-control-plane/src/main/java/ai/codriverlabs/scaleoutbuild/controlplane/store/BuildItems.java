/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.store;

import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps {@link BuildRecord} to and from DynamoDB attributes.
 *
 * <h2>Why a document attribute rather than a fully decomposed item</h2>
 *
 * Only a handful of fields are ever queried: the partition key, the owner (for "list my builds"), the
 * state and heartbeat timestamps (for the reaper). Those are stored as real top-level attributes. The
 * rest — build spec, input manifest, per-cell detail — is stored as one JSON {@code document}
 * attribute.
 *
 * <p>This is deliberate rather than lazy. Decomposing nested lists into DynamoDB {@code L}/{@code M}
 * attributes would add a large amount of mapping code whose only benefit is queries nobody makes, and
 * the enhanced client's annotation-driven mapping needs reflection registration to survive a GraalVM
 * native image. A JSON blob has neither problem, and the fields that must be queryable are still
 * first-class attributes rather than buried inside it.
 *
 * <p>The tradeoff to be aware of: a schema change to the document shape must stay
 * backward-compatible when read, because old items are not rewritten. Jackson is configured to ignore
 * unknown properties for exactly that reason.
 */
final class BuildItems {

    static final String ATTR_BUILD_ID = "buildId";
    static final String ATTR_OWNER_KEY = "ownerKey";
    static final String ATTR_OWNER_ARN = "ownerArn";
    static final String ATTR_OWNER_PER_HUMAN = "ownerPerHuman";
    static final String ATTR_STATE = "state";
    static final String ATTR_CREATED_AT = "createdAt";
    static final String ATTR_STARTED_AT = "startedAt";
    static final String ATTR_UPDATED_AT = "updatedAt";
    static final String ATTR_LAST_HEARTBEAT_AT = "lastHeartbeatAt";
    static final String ATTR_EXPIRES_AT = "expiresAt";
    static final String ATTR_TTL = "ttl";
    static final String ATTR_DOCUMENT = "document";
    /**
     * Running task ARNs, duplicated out of the JSON document as a top-level string set.
     *
     * <p>Redundant by design: the reaper is a separate Lambda that must stop a dead build's tasks, and
     * without this it would have to parse the document JSON — coupling an independently deployed
     * function to this service's storage shape. A flat attribute keeps the reaper's contract to
     * "state, timestamps, task ARNs" and nothing more.
     */
    static final String ATTR_TASK_ARNS = "taskArns";

    private BuildItems() {
    }

    /** The part of a record carried as JSON rather than as discrete attributes. */
    record Document(Object buildSpec, Object inputs, Object cells, String appliedCpu,
                    String appliedMemory, int appliedEphemeralStorageGiB, int overallTimeoutMinutes) {
    }

    static Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> toItem(
            BuildRecord record, ObjectMapper mapper) {
        var item = new HashMap<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>();
        item.put(ATTR_BUILD_ID, s(record.getBuildId()));
        item.put(ATTR_OWNER_KEY, s(record.getOwnerKey()));
        putIfPresent(item, ATTR_OWNER_ARN, record.getOwnerArn());
        item.put(ATTR_OWNER_PER_HUMAN, bool(record.isOwnerPerHuman()));
        item.put(ATTR_STATE, s(record.getState().name()));
        putInstant(item, ATTR_CREATED_AT, record.getCreatedAt());
        putInstant(item, ATTR_STARTED_AT, record.getStartedAt());
        putInstant(item, ATTR_UPDATED_AT, record.getUpdatedAt());
        putInstant(item, ATTR_LAST_HEARTBEAT_AT, record.getLastHeartbeatAt());
        putInstant(item, ATTR_EXPIRES_AT, record.getExpiresAt());
        if (record.getTtl() > 0) {
            item.put(ATTR_TTL, n(Long.toString(record.getTtl())));
        }
        var taskArns = record.getCells().stream()
                .filter(c -> c.getTaskArn() != null && !c.getState().isTerminal())
                .map(BuildRecord.CellRecord::getTaskArn)
                .toList();
        if (!taskArns.isEmpty()) {
            item.put(ATTR_TASK_ARNS,
                    software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromSs(taskArns));
        }
        try {
            item.put(ATTR_DOCUMENT, s(mapper.writeValueAsString(new Document(
                    record.getBuildSpec(), record.getInputs(), record.getCells(),
                    record.getAppliedCpu(), record.getAppliedMemory(),
                    record.getAppliedEphemeralStorageGiB(), record.getOverallTimeoutMinutes()))));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize build " + record.getBuildId(), e);
        }
        return item;
    }

    static BuildRecord fromItem(
            Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item,
            ObjectMapper mapper) {
        BuildRecord record = new BuildRecord();
        record.setBuildId(str(item, ATTR_BUILD_ID));
        record.setOwnerKey(str(item, ATTR_OWNER_KEY));
        record.setOwnerArn(str(item, ATTR_OWNER_ARN));
        record.setOwnerPerHuman(item.containsKey(ATTR_OWNER_PER_HUMAN)
                && Boolean.TRUE.equals(item.get(ATTR_OWNER_PER_HUMAN).bool()));
        String state = str(item, ATTR_STATE);
        if (state != null) {
            record.setState(BuildState.valueOf(state));
        }
        record.setCreatedAt(instant(item, ATTR_CREATED_AT));
        record.setStartedAt(instant(item, ATTR_STARTED_AT));
        record.setUpdatedAt(instant(item, ATTR_UPDATED_AT));
        record.setLastHeartbeatAt(instant(item, ATTR_LAST_HEARTBEAT_AT));
        record.setExpiresAt(instant(item, ATTR_EXPIRES_AT));
        if (item.containsKey(ATTR_TTL) && item.get(ATTR_TTL).n() != null) {
            record.setTtl(Long.parseLong(item.get(ATTR_TTL).n()));
        }
        String document = str(item, ATTR_DOCUMENT);
        if (document != null) {
            try {
                var node = mapper.readTree(document);
                record.setBuildSpec(mapper.treeToValue(node.path("buildSpec"),
                        ai.codriverlabs.scaleoutbuild.controlplane.api.BuildSpec.class));
                record.setInputs(mapper.readerForListOf(
                        ai.codriverlabs.scaleoutbuild.controlplane.api.InputDescriptor.class)
                        .readValue(node.path("inputs")));
                record.setCells(mapper.readerForListOf(BuildRecord.CellRecord.class)
                        .readValue(node.path("cells")));
                record.setAppliedCpu(node.path("appliedCpu").asText(null));
                record.setAppliedMemory(node.path("appliedMemory").asText(null));
                record.setAppliedEphemeralStorageGiB(node.path("appliedEphemeralStorageGiB").asInt());
                record.setOverallTimeoutMinutes(node.path("overallTimeoutMinutes").asInt());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to deserialize build " + record.getBuildId(), e);
            }
        }
        return record;
    }

    private static software.amazon.awssdk.services.dynamodb.model.AttributeValue s(String value) {
        return software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS(value);
    }

    private static software.amazon.awssdk.services.dynamodb.model.AttributeValue n(String value) {
        return software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromN(value);
    }

    private static software.amazon.awssdk.services.dynamodb.model.AttributeValue bool(boolean value) {
        return software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromBool(value);
    }

    private static void putIfPresent(
            Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item,
            String key, String value) {
        if (value != null && !value.isBlank()) {
            item.put(key, s(value));
        }
    }

    private static void putInstant(
            Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item,
            String key, Instant value) {
        if (value != null) {
            item.put(key, s(value.toString()));
        }
    }

    private static String str(
            Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item,
            String key) {
        var value = item.get(key);
        return value == null ? null : value.s();
    }

    private static Instant instant(
            Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item,
            String key) {
        String value = str(item, key);
        return value == null ? null : Instant.parse(value);
    }
}
