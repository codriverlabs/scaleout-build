/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.stepfunctions;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * Builds the ASL (Amazon States Language) definition for the build-matrix state machine described
 * in {@code docs/DESIGN.md} §5.
 *
 * <p>Uses {@code QueryLanguage: "JSONata"} at the state machine level — AWS's current recommendation
 * for new state machines, confirmed against the Step Functions developer guide — rather than the
 * older JSONPath style, so the whole definition uses one consistent syntax.
 *
 * <p>One Inline {@code Map} state fans out over the computed matrix (the {@code cells} array in the
 * execution input), each iteration calling {@code arn:aws:states:::ecs:runTask.sync}. Both the
 * success and failure paths are normalized into the same shape — {@code {buildId, buildKind,
 * architecture, success, ...}} — via a {@code Pass} state on each branch, specifically so the
 * plugin can parse the Map's aggregated output as a uniform array regardless of which cells
 * succeeded. The failure path's {@code Catch.Output} references both {@code $states.input} (the
 * original cell, preserved) and {@code $states.errorOutput} (the {@code {Error, Cause}} object Step
 * Functions constructs) — confirmed against the Step Functions error-handling reference — so a
 * failed cell's identifiers are never lost. Inline Map has no {@code ToleratedFailurePercentage}
 * (that is a Distributed-Map-only feature, confirmed against the docs) and fails the whole Map on
 * the first uncaught iteration failure; catching each cell's failure here and recording it as data,
 * rather than letting it propagate, is what gives an Inline Map a GitHub Actions
 * {@code fail-fast: false}-equivalent — the plugin decides what "the matrix failed" means by
 * inspecting the per-cell {@code success} flags after the execution completes, not by relying on
 * the Map state's own pass/fail outcome.
 */
public final class BuildMatrixStateMachineDefinition {

    private static final String CONTAINER_NAME = "jobrunr-build-agent";

    private BuildMatrixStateMachineDefinition() {
    }

    /**
     * @param maxConcurrency upper bound on parallel Map iterations; must not exceed Inline Map's
     *                       40-iteration ceiling
     * @param maxAttempts    retry attempts for a {@code RunTask.sync} failure (e.g. a Spot
     *                       interruption) before that cell is recorded as failed
     */
    public static ObjectNode build(com.fasterxml.jackson.databind.ObjectMapper mapper,
                                   int maxConcurrency, int maxAttempts) {
        Objects.requireNonNull(mapper, "mapper");
        if (maxConcurrency < 1 || maxConcurrency > 40) {
            throw new IllegalArgumentException(
                    "maxConcurrency must be between 1 and 40 (Inline Map's ceiling), was "
                            + maxConcurrency);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("QueryLanguage", "JSONata");
        root.put("StartAt", "BuildMatrix");
        ObjectNode states = root.putObject("States");

        ObjectNode buildMatrix = states.putObject("BuildMatrix");
        buildMatrix.put("Type", "Map");
        buildMatrix.put("Items", "{% $states.input.cells %}");
        buildMatrix.put("MaxConcurrency", maxConcurrency);
        buildMatrix.put("End", true);

        ObjectNode itemProcessor = buildMatrix.putObject("ItemProcessor");
        itemProcessor.putObject("ProcessorConfig").put("Mode", "INLINE");
        itemProcessor.put("StartAt", "RunCell");
        ObjectNode cellStates = itemProcessor.putObject("States");

        addRunCellState(cellStates, maxAttempts);
        addRecordCellSuccessState(cellStates);
        addRecordCellFailureState(cellStates);

        return root;
    }

    private static void addRunCellState(ObjectNode cellStates, int maxAttempts) {
        ObjectNode runCell = cellStates.putObject("RunCell");
        runCell.put("Type", "Task");
        runCell.put("Resource", "arn:aws:states:::ecs:runTask.sync");

        ObjectNode arguments = runCell.putObject("Arguments");
        arguments.put("Cluster", "{% $states.input.clusterArn %}");
        arguments.put("TaskDefinition", "{% $states.input.taskDefinitionArn %}");

        ArrayNode capacityProviderStrategy = arguments.putArray("CapacityProviderStrategy");
        ObjectNode spot = capacityProviderStrategy.addObject();
        spot.put("CapacityProvider", "{% $states.input.preferOnDemand ? 'FARGATE' : 'FARGATE_SPOT' %}");
        spot.put("Weight", 4);
        spot.put("Base", 1);
        ObjectNode onDemand = capacityProviderStrategy.addObject();
        onDemand.put("CapacityProvider",
                "{% $states.input.preferOnDemand ? 'FARGATE_SPOT' : 'FARGATE' %}");
        onDemand.put("Weight", 1);
        onDemand.put("Base", 0);

        ObjectNode networkConfiguration = arguments.putObject("NetworkConfiguration");
        ObjectNode awsvpcConfiguration = networkConfiguration.putObject("AwsvpcConfiguration");
        awsvpcConfiguration.put("Subnets", "{% $states.input.subnetIds %}");
        awsvpcConfiguration.put("SecurityGroups", "{% $states.input.securityGroupIds %}");
        awsvpcConfiguration.put("AssignPublicIp",
                "{% $states.input.assignPublicIp ? 'ENABLED' : 'DISABLED' %}");

        ObjectNode overrides = arguments.putObject("Overrides");
        ArrayNode containerOverrides = overrides.putArray("ContainerOverrides");
        ObjectNode containerOverride = containerOverrides.addObject();
        containerOverride.put("Name", CONTAINER_NAME);
        containerOverride.put("Environment", "{% $states.input.environment %}");

        ArrayNode retry = runCell.putArray("Retry");
        ObjectNode retryRule = retry.addObject();
        retryRule.putArray("ErrorEquals").add("States.TaskFailed").add("States.Timeout");
        retryRule.put("MaxAttempts", maxAttempts);
        retryRule.put("BackoffRate", 2.0);
        retryRule.put("IntervalSeconds", 5);

        // Catch.Output can reference both $states.input (the original cell, preserved -- confirmed
        // via the Step Functions error-handling reference) and $states.errorOutput (the {Error,
        // Cause} object Step Functions constructs), so the failure path below carries the same
        // buildId/buildKind/architecture identifiers forward as the success path, with no ambiguity
        // about correlating a failure back to its matrix cell.
        ArrayNode catchNode = runCell.putArray("Catch");
        ObjectNode catchRule = catchNode.addObject();
        catchRule.putArray("ErrorEquals").add("States.ALL");
        catchRule.put("Next", "RecordCellFailure");
        ObjectNode catchOutput = catchRule.putObject("Output");
        catchOutput.put("buildId", "{% $states.input.buildId %}");
        catchOutput.put("buildKind", "{% $states.input.buildKind %}");
        catchOutput.put("architecture", "{% $states.input.architecture %}");
        catchOutput.put("success", false);
        catchOutput.put("error", "{% $states.errorOutput.Error %}");
        catchOutput.put("cause", "{% $states.errorOutput.Cause %}");

        runCell.put("Next", "RecordCellSuccess");
    }

    private static void addRecordCellSuccessState(ObjectNode cellStates) {
        ObjectNode recordSuccess = cellStates.putObject("RecordCellSuccess");
        recordSuccess.put("Type", "Pass");
        ObjectNode output = recordSuccess.putObject("Output");
        output.put("buildId", "{% $states.input.buildId %}");
        output.put("buildKind", "{% $states.input.buildKind %}");
        output.put("architecture", "{% $states.input.architecture %}");
        output.put("success", true);
        recordSuccess.put("End", true);
    }

    private static void addRecordCellFailureState(ObjectNode cellStates) {
        // RunCell's Catch.Output already produced the fully-shaped failure record (see above), so
        // this state exists purely to give the Catch's Next target a name -- it passes the input
        // through unchanged.
        ObjectNode recordFailure = cellStates.putObject("RecordCellFailure");
        recordFailure.put("Type", "Pass");
        recordFailure.put("Output", "{% $states.input %}");
        recordFailure.put("End", true);
    }
}
