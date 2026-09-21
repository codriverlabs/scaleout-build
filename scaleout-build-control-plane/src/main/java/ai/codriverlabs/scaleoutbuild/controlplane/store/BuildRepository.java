/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.store;

import ai.codriverlabs.scaleoutbuild.controlplane.config.ControlPlaneConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** Persistence for {@link BuildRecord}. */
@ApplicationScoped
public class BuildRepository {

    /** GSI enabling "list my builds" without a table scan. Created by CDK. */
    public static final String OWNER_INDEX = "owner-index";

    private final DynamoDbClient dynamo;
    private final ControlPlaneConfig config;
    private final ObjectMapper mapper;

    @Inject
    public BuildRepository(DynamoDbClient dynamo, ControlPlaneConfig config, ObjectMapper mapper) {
        this.dynamo = dynamo;
        this.config = config;
        // Unknown properties must be tolerated: items written by an older version of the service are
        // never rewritten, so a field removed later would otherwise make old builds unreadable.
        this.mapper = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public void put(BuildRecord record) {
        record.setUpdatedAt(Instant.now());
        dynamo.putItem(b -> b.tableName(config.buildsTable())
                .item(BuildItems.toItem(record, mapper)));
    }

    /**
     * Writes only if the build is still in {@code expectedState}.
     *
     * @return false if another caller changed the state first — two concurrent {@code start} calls
     *         must not both launch the matrix
     */
    public boolean putIfStateIs(BuildRecord record, ai.codriverlabs.scaleoutbuild.controlplane.api.BuildState expectedState) {
        record.setUpdatedAt(Instant.now());
        try {
            dynamo.putItem(b -> b.tableName(config.buildsTable())
                    .item(BuildItems.toItem(record, mapper))
                    .conditionExpression("#s = :expected")
                    .expressionAttributeNames(Map.of("#s", BuildItems.ATTR_STATE))
                    .expressionAttributeValues(
                            Map.of(":expected", AttributeValue.fromS(expectedState.name()))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    public Optional<BuildRecord> find(String buildId) {
        var response = dynamo.getItem(b -> b.tableName(config.buildsTable())
                .key(Map.of(BuildItems.ATTR_BUILD_ID, AttributeValue.fromS(buildId)))
                .consistentRead(true));
        return response.hasItem() && !response.item().isEmpty()
                ? Optional.of(BuildItems.fromItem(response.item(), mapper))
                : Optional.empty();
    }

    /**
     * Finds a build only if it belongs to {@code ownerKey}.
     *
     * <p>Returning empty rather than throwing a distinguishable "forbidden" is intentional: the
     * resource layer maps this to {@code 404}, so another developer's build ids are not enumerable by
     * probing for a different status code.
     */
    public Optional<BuildRecord> findOwned(String buildId, String ownerKey) {
        return find(buildId).filter(r -> ownerKey.equals(r.getOwnerKey()));
    }

    /** Lists one owner's builds, newest first. Build ids are ULIDs, so key order is time order. */
    public List<BuildRecord> listByOwner(String ownerKey, int limit) {
        var response = dynamo.query(b -> b.tableName(config.buildsTable())
                .indexName(OWNER_INDEX)
                .keyConditionExpression("#o = :owner")
                .expressionAttributeNames(Map.of("#o", BuildItems.ATTR_OWNER_KEY))
                .expressionAttributeValues(Map.of(":owner", AttributeValue.fromS(ownerKey)))
                .scanIndexForward(false)
                .limit(limit));
        List<BuildRecord> records = new ArrayList<>();
        response.items().forEach(item -> records.add(BuildItems.fromItem(item, mapper)));
        return records;
    }

    /** Counts an owner's non-terminal builds, for the per-owner concurrency cap. */
    public long countActiveByOwner(String ownerKey) {
        return listByOwner(ownerKey, 100).stream()
                .filter(r -> !r.getState().isTerminal())
                .count();
    }
}
