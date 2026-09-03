/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.stepfunctions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class BuildMatrixStateMachineDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void usesJsonataAndFansOutOverTheCellsInput() {
        ObjectNode definition = BuildMatrixStateMachineDefinition.build(objectMapper, 7, 2);

        assertThat(definition.path("QueryLanguage").asText()).isEqualTo("JSONata");
        assertThat(definition.path("StartAt").asText()).isEqualTo("BuildMatrix");

        ObjectNode buildMatrix = (ObjectNode) definition.path("States").path("BuildMatrix");
        assertThat(buildMatrix.path("Type").asText()).isEqualTo("Map");
        assertThat(buildMatrix.path("Items").asText()).isEqualTo("{% $states.input.cells %}");
        assertThat(buildMatrix.path("MaxConcurrency").asInt()).isEqualTo(7);
        assertThat(buildMatrix.path("End").asBoolean()).isTrue();
    }

    @Test
    void runCellCallsEcsRunTaskSyncAndOmitsLaunchType() {
        ObjectNode definition = BuildMatrixStateMachineDefinition.build(objectMapper, 5, 3);
        ObjectNode runCell = cellState(definition, "RunCell");

        assertThat(runCell.path("Type").asText()).isEqualTo("Task");
        assertThat(runCell.path("Resource").asText()).isEqualTo("arn:aws:states:::ecs:runTask.sync");
        assertThat(runCell.has("LaunchType")).isFalse();
        assertThat(runCell.path("Arguments").has("CapacityProviderStrategy")).isTrue();
    }

    @Test
    void runCellRetriesTaskFailuresWithTheConfiguredAttemptCount() {
        ObjectNode definition = BuildMatrixStateMachineDefinition.build(objectMapper, 5, 4);
        ObjectNode runCell = cellState(definition, "RunCell");

        ObjectNode retryRule = (ObjectNode) runCell.path("Retry").get(0);
        assertThat(retryRule.path("MaxAttempts").asInt()).isEqualTo(4);
        assertThat(retryRule.path("ErrorEquals")).extracting(n -> n.asText())
                .contains("States.TaskFailed", "States.Timeout");
    }

    @Test
    void runCellCatchesEveryErrorAndPreservesTheOriginalCellIdentifiers() {
        ObjectNode definition = BuildMatrixStateMachineDefinition.build(objectMapper, 5, 2);
        ObjectNode runCell = cellState(definition, "RunCell");

        ObjectNode catchRule = (ObjectNode) runCell.path("Catch").get(0);
        assertThat(catchRule.path("ErrorEquals").get(0).asText()).isEqualTo("States.ALL");
        assertThat(catchRule.path("Next").asText()).isEqualTo("RecordCellFailure");

        ObjectNode catchOutput = (ObjectNode) catchRule.path("Output");
        // Must reference $states.input (the original cell, preserved by Catch.Output under
        // JSONata) rather than $states.input.Error -- confirmed against the Step Functions
        // error-handling reference; an earlier draft of this class got this wrong.
        assertThat(catchOutput.path("buildId").asText()).isEqualTo("{% $states.input.buildId %}");
        assertThat(catchOutput.path("success").asBoolean()).isFalse();
        assertThat(catchOutput.path("error").asText()).isEqualTo("{% $states.errorOutput.Error %}");
        assertThat(catchOutput.path("cause").asText()).isEqualTo("{% $states.errorOutput.Cause %}");
    }

    @Test
    void recordCellSuccessNormalizesTheSameShapeAsFailure() {
        ObjectNode definition = BuildMatrixStateMachineDefinition.build(objectMapper, 5, 2);
        ObjectNode recordSuccess = cellState(definition, "RecordCellSuccess");

        ObjectNode output = (ObjectNode) recordSuccess.path("Output");
        assertThat(output.path("buildId").asText()).isEqualTo("{% $states.input.buildId %}");
        assertThat(output.path("buildKind").asText()).isEqualTo("{% $states.input.buildKind %}");
        assertThat(output.path("architecture").asText())
                .isEqualTo("{% $states.input.architecture %}");
        assertThat(output.path("success").asBoolean()).isTrue();
        assertThat(recordSuccess.path("End").asBoolean()).isTrue();
    }

    @Test
    void rejectsMaxConcurrencyOutsideInlineMapsCeiling() {
        assertThatThrownBy(() -> BuildMatrixStateMachineDefinition.build(objectMapper, 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BuildMatrixStateMachineDefinition.build(objectMapper, 41, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40");
    }

    @Test
    void rejectsNonPositiveMaxAttempts() {
        assertThatThrownBy(() -> BuildMatrixStateMachineDefinition.build(objectMapper, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectNode cellState(ObjectNode definition, String stateName) {
        return (ObjectNode) definition.path("States").path("BuildMatrix").path("ItemProcessor")
                .path("States").path(stateName);
    }
}
