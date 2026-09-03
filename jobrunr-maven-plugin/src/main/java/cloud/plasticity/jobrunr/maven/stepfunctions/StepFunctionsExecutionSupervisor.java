/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;

/**
 * Starts one execution of the build-matrix state machine and waits for it to finish.
 *
 * <p>Per {@code docs/DESIGN.md} §5, each Map iteration's output is normalized to
 * {@code {buildId, buildKind, architecture, success, ...}} regardless of whether that cell
 * succeeded or was caught as a failure, so this class's job is simply: start, poll
 * {@code DescribeExecution} until terminal, parse the Map's output array, and report which cells
 * failed. Retries and Spot-interruption handling are declarative (the state machine's own
 * {@code Retry}/{@code Catch}), not something this class implements.
 */
public final class StepFunctionsExecutionSupervisor {

    private static final Logger LOG = LoggerFactory.getLogger(StepFunctionsExecutionSupervisor.class);

    private final SfnClient sfnClient;
    private final ObjectMapper objectMapper;

    public StepFunctionsExecutionSupervisor(SfnClient sfnClient, ObjectMapper objectMapper) {
        this.sfnClient = Objects.requireNonNull(sfnClient, "sfnClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Outcome of one matrix cell, parsed from the Map state's per-iteration output. */
    public record CellOutcome(String buildId, String buildKind, String architecture,
                              boolean success, String error, String cause) {
    }

    /** Outcome of supervising one execution of the whole matrix to completion or timeout. */
    public record MatrixResult(boolean timedOut, boolean executionFailed,
                               List<CellOutcome> cellOutcomes) {

        /**
         * True when every cell that ran reported success, the execution did not time out, and the
         * execution itself did not fail outright (e.g. a bad definition or permissions error,
         * which produces no per-cell output at all -- an empty {@code cellOutcomes} must not be
         * read as vacuous success in that case, hence checking {@code executionFailed} explicitly
         * rather than inferring failure from an empty list, which is also the legitimate shape of
         * a matrix with zero cells).
         */
        public boolean allSucceeded() {
            return !timedOut && !executionFailed
                    && cellOutcomes.stream().allMatch(CellOutcome::success);
        }

        public List<CellOutcome> failedCells() {
            return cellOutcomes.stream().filter(cell -> !cell.success()).toList();
        }
    }

    /**
     * Starts an execution with {@code matrixInputJson} as input, polls until it reaches a terminal
     * state, and parses the per-cell results.
     *
     * @param matrixInputJson the {@code {"cells": [...]}} JSON the state machine's Map state
     *                        iterates over
     * @param pollInterval    how often to call {@code DescribeExecution} while waiting
     * @param overallTimeout  how long to wait before giving up and reporting a timeout
     */
    public MatrixResult supervise(String stateMachineArn, String executionName,
                                  String matrixInputJson, Duration pollInterval,
                                  Duration overallTimeout) throws InterruptedException {
        Objects.requireNonNull(stateMachineArn, "stateMachineArn");
        Objects.requireNonNull(executionName, "executionName");
        Objects.requireNonNull(matrixInputJson, "matrixInputJson");
        Objects.requireNonNull(pollInterval, "pollInterval");
        Objects.requireNonNull(overallTimeout, "overallTimeout");

        String executionArn = sfnClient.startExecution(b -> b
                        .stateMachineArn(stateMachineArn)
                        .name(executionName)
                        .input(matrixInputJson))
                .executionArn();
        LOG.info("Started execution {}", executionArn);

        Instant deadline = Instant.now().plus(overallTimeout);
        while (true) {
            DescribeExecutionResponse response = sfnClient.describeExecution(b ->
                    b.executionArn(executionArn));
            ExecutionStatus status = response.status();

            if (status == ExecutionStatus.SUCCEEDED) {
                LOG.info("Execution {} succeeded", executionArn);
                return new MatrixResult(false, false, parseCellOutcomes(response.output()));
            }
            if (status == ExecutionStatus.FAILED || status == ExecutionStatus.TIMED_OUT
                    || status == ExecutionStatus.ABORTED) {
                LOG.error("Execution {} ended in {}: {} ({})", executionArn, status,
                        response.error(), response.cause());
                // The execution itself failed (e.g. a definition/permissions error) rather than an
                // individual cell -- there is no per-cell output to parse in this case.
                return new MatrixResult(false, true, List.of());
            }

            if (Instant.now().isAfter(deadline)) {
                LOG.warn("Execution {} exceeded the overall timeout of {} while in status {}",
                        executionArn, overallTimeout, status);
                return new MatrixResult(true, false, List.of());
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }

    private List<CellOutcome> parseCellOutcomes(String outputJson) {
        List<CellOutcome> outcomes = new ArrayList<>();
        if (outputJson == null || outputJson.isBlank()) {
            return outcomes;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(outputJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.error("Could not parse execution output as JSON: {}", e.getMessage());
            return outcomes;
        }
        if (!root.isArray()) {
            LOG.error("Expected the execution output to be a JSON array of per-cell results, got: {}",
                    outputJson);
            return outcomes;
        }
        for (JsonNode cell : root) {
            outcomes.add(new CellOutcome(
                    text(cell, "buildId"),
                    text(cell, "buildKind"),
                    text(cell, "architecture"),
                    cell.path("success").asBoolean(false),
                    text(cell, "error"),
                    text(cell, "cause")));
        }
        return outcomes;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
