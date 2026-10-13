/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Unit tests for {@link BuildItems} — a plain class with no CDI dependencies, so Pattern A (plain
 * JUnit 5, construct directly) per {@code docs/design/testing/unit-test-strategy.md}, not
 * {@code @QuarkusTest}.
 *
 * <p>The behavior under test is the backward-compatible widening described in
 * {@code docs/design/control-plane/microvm-build-backend-phase1.md}'s "widening {@code taskArn}
 * without a flag day" section: adding {@code backend}/{@code microvmId}/{@code microvmEndpoint} to
 * {@code CellRecord}, and a {@code microvmIds} sibling to the flat {@code taskArns} attribute, must
 * not change how a record written before this change deserializes.
 */
class BuildItemsTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * A {@code document} attribute shaped exactly like one written before {@code backend} /
     * {@code microvmId} / {@code microvmEndpoint} existed on {@code CellRecord} — no mention of any
     * of the three fields anywhere in the JSON.
     */
    private static final String PRE_MICROVM_DOCUMENT = """
            {
              "buildSpec": {
                "buildKinds": ["NATIVE"],
                "architectures": ["X86_64"],
                "mainClass": "com.example.Main"
              },
              "inputs": [],
              "cells": [
                {
                  "cell": "NATIVE-X86_64",
                  "state": "RUNNING",
                  "taskArn": "arn:aws:ecs:eu-west-1:000000000000:task/cluster/abc123",
                  "taskDefinitionArn": "arn:aws:ecs:eu-west-1:000000000000:task-definition/fam:1",
                  "artifactPaths": []
                }
              ],
              "appliedCpu": "4096",
              "appliedMemory": "8192",
              "appliedEphemeralStorageGiB": 0,
              "overallTimeoutMinutes": 20
            }
            """;

    @Test
    void fromItem_deserializesPreExistingRecord_withBackendDefaultingToFargate() {
        Map<String, AttributeValue> item = Map.of(
                BuildItems.ATTR_BUILD_ID, AttributeValue.fromS("01HXYZ"),
                BuildItems.ATTR_OWNER_KEY, AttributeValue.fromS("owner-1"),
                BuildItems.ATTR_STATE, AttributeValue.fromS("RUNNING"),
                BuildItems.ATTR_DOCUMENT, AttributeValue.fromS(PRE_MICROVM_DOCUMENT));

        BuildRecord record = BuildItems.fromItem(item, mapper);

        assertThat(record.getCells()).hasSize(1);
        BuildRecord.CellRecord cell = record.getCells().get(0);
        // The actual proof: a pre-existing record, with no "backend" field anywhere in its JSON,
        // comes back as FARGATE rather than null — old data is simply data where the new fields
        // happen to be absent, exactly as the design doc specifies.
        assertThat(cell.getBackend()).isEqualTo("FARGATE");
        assertThat(cell.getMicrovmId()).isNull();
        assertThat(cell.getMicrovmEndpoint()).isNull();
        // Everything that was already there deserializes unaffected.
        assertThat(cell.getTaskArn()).isEqualTo("arn:aws:ecs:eu-west-1:000000000000:task/cluster/abc123");
    }

    @Test
    void toItem_writesTaskArnsOnly_whenAllCellsAreFargate() {
        BuildRecord record = minimalRecord();
        BuildRecord.CellRecord cell = new BuildRecord.CellRecord();
        cell.setCell("NATIVE-X86_64");
        cell.setState(ai.codriverlabs.scaleoutbuild.controlplane.api.CellState.RUNNING);
        cell.setTaskArn("arn:aws:ecs:eu-west-1:000000000000:task/cluster/abc123");
        record.setCells(List.of(cell));

        Map<String, AttributeValue> item = BuildItems.toItem(record, mapper);

        assertThat(item.get(BuildItems.ATTR_TASK_ARNS).ss())
                .containsExactly("arn:aws:ecs:eu-west-1:000000000000:task/cluster/abc123");
        assertThat(item).doesNotContainKey(BuildItems.ATTR_MICROVM_IDS);
    }

    @Test
    void toItem_writesMicrovmIds_forMicrovmBackedCells() {
        BuildRecord record = minimalRecord();
        BuildRecord.CellRecord fargateCell = new BuildRecord.CellRecord();
        fargateCell.setCell("NATIVE-X86_64");
        fargateCell.setState(ai.codriverlabs.scaleoutbuild.controlplane.api.CellState.RUNNING);
        fargateCell.setTaskArn("arn:aws:ecs:eu-west-1:000000000000:task/cluster/abc123");

        BuildRecord.CellRecord microvmCell = new BuildRecord.CellRecord();
        microvmCell.setCell("NATIVE-ARM64");
        microvmCell.setState(ai.codriverlabs.scaleoutbuild.controlplane.api.CellState.RUNNING);
        microvmCell.setBackend("MICROVM");
        microvmCell.setMicrovmId("microvm-abc123");
        microvmCell.setMicrovmEndpoint("https://microvm-abc123.lambda-microvms.eu-west-1.amazonaws.com");

        record.setCells(List.of(fargateCell, microvmCell));

        Map<String, AttributeValue> item = BuildItems.toItem(record, mapper);

        assertThat(item.get(BuildItems.ATTR_TASK_ARNS).ss())
                .containsExactly("arn:aws:ecs:eu-west-1:000000000000:task/cluster/abc123");
        assertThat(item.get(BuildItems.ATTR_MICROVM_IDS).ss())
                .containsExactly("microvm-abc123");

        // And the round trip through the document blob preserves the MicroVM fields too, not just
        // the flat reaper-facing attribute.
        BuildRecord roundTripped = BuildItems.fromItem(item, mapper);
        BuildRecord.CellRecord roundTrippedMicrovmCell = roundTripped.cell("NATIVE-ARM64");
        assertThat(roundTrippedMicrovmCell.getBackend()).isEqualTo("MICROVM");
        assertThat(roundTrippedMicrovmCell.getMicrovmId()).isEqualTo("microvm-abc123");
        assertThat(roundTrippedMicrovmCell.getMicrovmEndpoint())
                .isEqualTo("https://microvm-abc123.lambda-microvms.eu-west-1.amazonaws.com");
    }

    @Test
    void toItem_omitsMicrovmIds_whenMicrovmCellIsTerminal() {
        // Mirrors the existing taskArns behavior: ATTR_TASK_ARNS/ATTR_MICROVM_IDS only ever name
        // cells the reaper might still need to stop, not finished ones.
        BuildRecord record = minimalRecord();
        BuildRecord.CellRecord microvmCell = new BuildRecord.CellRecord();
        microvmCell.setCell("NATIVE-ARM64");
        microvmCell.setState(ai.codriverlabs.scaleoutbuild.controlplane.api.CellState.SUCCEEDED);
        microvmCell.setBackend("MICROVM");
        microvmCell.setMicrovmId("microvm-abc123");
        record.setCells(List.of(microvmCell));

        Map<String, AttributeValue> item = BuildItems.toItem(record, mapper);

        assertThat(item).doesNotContainKey(BuildItems.ATTR_MICROVM_IDS);
    }

    private static BuildRecord minimalRecord() {
        BuildRecord record = new BuildRecord();
        record.setBuildId("01HXYZ");
        record.setOwnerKey("owner-1");
        record.setState(ai.codriverlabs.scaleoutbuild.controlplane.api.BuildState.RUNNING);
        return record;
    }
}
