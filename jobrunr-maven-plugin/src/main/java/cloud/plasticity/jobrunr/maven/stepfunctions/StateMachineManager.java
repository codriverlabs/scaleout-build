/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.CreateStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.StateMachineListItem;
import software.amazon.awssdk.services.sfn.model.StateMachineType;
import software.amazon.awssdk.services.sfn.model.Tag;

/**
 * Deploys the build-matrix state machine idempotently, the same content-hash-tag pattern
 * {@link cloud.plasticity.jobrunr.maven.ecs.TaskDefinitionRegistrar} uses for task definitions: a
 * repeated plugin invocation reuses the existing state machine unless its definition or execution
 * role actually changed.
 *
 * <p>Unlike ECS task definitions, Step Functions has no "describe by name" call that also returns
 * tags in one round trip — finding the current state machine by name requires paginating
 * {@code ListStateMachines}, and its tags require a separate {@code ListTagsForResource} call.
 */
public final class StateMachineManager {

    private static final Logger LOG = LoggerFactory.getLogger(StateMachineManager.class);

    /** Tag key holding the content hash used to detect an unchanged configuration. */
    public static final String CONFIG_HASH_TAG_KEY = "jobrunr:configHash";

    private final SfnClient sfnClient;
    private final ObjectMapper objectMapper;

    public StateMachineManager(SfnClient sfnClient, ObjectMapper objectMapper) {
        this.sfnClient = Objects.requireNonNull(sfnClient, "sfnClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Deploys (or reuses) the named state machine.
     *
     * @return the ARN to call {@code StartExecution} against
     */
    public String deployIfChanged(String name, String executionRoleArn, ObjectNode definition) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(executionRoleArn, "executionRoleArn");
        Objects.requireNonNull(definition, "definition");

        String definitionJson = definition.toString();
        String configHash = computeConfigHash(executionRoleArn, definitionJson);

        Optional<String> existingArn = findByName(name);
        if (existingArn.isEmpty()) {
            CreateStateMachineResponse response = sfnClient.createStateMachine(b -> b
                    .name(name)
                    .definition(definitionJson)
                    .roleArn(executionRoleArn)
                    .type(StateMachineType.STANDARD)
                    .tags(Tag.builder().key(CONFIG_HASH_TAG_KEY).value(configHash).build()));
            LOG.info("Created state machine {}: {}", name, response.stateMachineArn());
            return response.stateMachineArn();
        }

        String arn = existingArn.get();
        if (hasMatchingConfigHash(arn, configHash)) {
            LOG.info("State machine {} is unchanged; reusing {}", name, arn);
            return arn;
        }

        sfnClient.updateStateMachine(b -> b
                .stateMachineArn(arn)
                .definition(definitionJson)
                .roleArn(executionRoleArn));
        // UpdateStateMachine has no tags parameter (confirmed against the SDK) -- tags are set
        // separately, and TagResource overwrites rather than requiring the tag to not already exist.
        sfnClient.tagResource(b -> b
                .resourceArn(arn)
                .tags(Tag.builder().key(CONFIG_HASH_TAG_KEY).value(configHash).build()));
        LOG.info("Updated state machine {}: {}", name, arn);
        return arn;
    }

    private Optional<String> findByName(String name) {
        String nextToken = null;
        do {
            final String token = nextToken;
            var response = sfnClient.listStateMachines(b -> {
                if (token != null) {
                    b.nextToken(token);
                }
            });
            for (StateMachineListItem item : response.stateMachines()) {
                if (name.equals(item.name())) {
                    return Optional.of(item.stateMachineArn());
                }
            }
            nextToken = response.nextToken();
        } while (nextToken != null);
        return Optional.empty();
    }

    private boolean hasMatchingConfigHash(String stateMachineArn, String desiredConfigHash) {
        var response = sfnClient.listTagsForResource(b -> b.resourceArn(stateMachineArn));
        return response.tags().stream()
                .filter(tag -> CONFIG_HASH_TAG_KEY.equals(tag.key()))
                .map(Tag::value)
                .anyMatch(desiredConfigHash::equals);
    }

    private String computeConfigHash(String executionRoleArn, String definitionJson) {
        String canonical = "roleArn=" + executionRoleArn + "\ndefinition=" + definitionJson;
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
