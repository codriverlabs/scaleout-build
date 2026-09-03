/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.stepfunctions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

// Every test here drives a bounded polling loop; per the lesson learned wiring BuildSupervisorTest
// in an earlier session, a real bug in the polling logic should fail fast with a clear timeout
// instead of hanging the whole build indefinitely.
@ExtendWith(MockitoExtension.class)
@Timeout(10)
class StepFunctionsExecutionSupervisorTest {

    @Mock
    private SfnClient sfnClient;

    private StepFunctionsExecutionSupervisor supervisor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        supervisor = new StepFunctionsExecutionSupervisor(sfnClient, objectMapper);
        when(sfnClient.startExecution(any(Consumer.class))).thenReturn(StartExecutionResponse.builder()
                .executionArn("arn:aws:states:us-east-1:123456789012:execution:build:exec-1")
                .build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsAllSucceededWhenEveryCellSucceeds() throws InterruptedException {
        String output = """
                [
                  {"buildId":"b1","buildKind":"native","architecture":"X86_64","success":true},
                  {"buildId":"b1","buildKind":"native","architecture":"ARM64","success":true}
                ]""";
        when(sfnClient.describeExecution(any(Consumer.class)))
                .thenReturn(DescribeExecutionResponse.builder().status(ExecutionStatus.SUCCEEDED)
                        .output(output).build());

        var result = supervisor.supervise("arn:...:stateMachine:build", "exec-1", "{\"cells\":[]}",
                Duration.ofMillis(10), Duration.ofSeconds(5));

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.cellOutcomes()).hasSize(2);
        assertThat(result.failedCells()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsFailedCellsWithTheirErrorAndCause() throws InterruptedException {
        String output = """
                [
                  {"buildId":"b1","buildKind":"native","architecture":"X86_64","success":true},
                  {"buildId":"b1","buildKind":"native","architecture":"ARM64","success":false,
                   "error":"States.TaskFailed","cause":"Spot capacity unavailable"}
                ]""";
        when(sfnClient.describeExecution(any(Consumer.class)))
                .thenReturn(DescribeExecutionResponse.builder().status(ExecutionStatus.SUCCEEDED)
                        .output(output).build());

        var result = supervisor.supervise("arn:...:stateMachine:build", "exec-1", "{\"cells\":[]}",
                Duration.ofMillis(10), Duration.ofSeconds(5));

        assertThat(result.allSucceeded()).isFalse();
        assertThat(result.failedCells()).hasSize(1);
        assertThat(result.failedCells().get(0).error()).isEqualTo("States.TaskFailed");
        assertThat(result.failedCells().get(0).cause()).isEqualTo("Spot capacity unavailable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollsUntilTheExecutionReachesATerminalState() throws InterruptedException {
        when(sfnClient.describeExecution(any(Consumer.class)))
                .thenReturn(DescribeExecutionResponse.builder().status(ExecutionStatus.RUNNING).build())
                .thenReturn(DescribeExecutionResponse.builder().status(ExecutionStatus.RUNNING).build())
                .thenReturn(DescribeExecutionResponse.builder().status(ExecutionStatus.SUCCEEDED)
                        .output("[]").build());

        var result = supervisor.supervise("arn:...:stateMachine:build", "exec-1", "{\"cells\":[]}",
                Duration.ofMillis(10), Duration.ofSeconds(5));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.cellOutcomes()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsTimeoutWhenTheExecutionNeverReachesATerminalStateInTime() throws InterruptedException {
        when(sfnClient.describeExecution(any(Consumer.class)))
                .thenReturn(DescribeExecutionResponse.builder().status(ExecutionStatus.RUNNING).build());

        var result = supervisor.supervise("arn:...:stateMachine:build", "exec-1", "{\"cells\":[]}",
                Duration.ofMillis(10), Duration.ofMillis(50));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.allSucceeded()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsNoOutcomesWhenTheExecutionItselfFails() throws InterruptedException {
        when(sfnClient.describeExecution(any(Consumer.class)))
                .thenReturn(DescribeExecutionResponse.builder().status(ExecutionStatus.FAILED)
                        .error("States.Runtime").cause("Invalid state machine definition").build());

        var result = supervisor.supervise("arn:...:stateMachine:build", "exec-1", "{\"cells\":[]}",
                Duration.ofMillis(10), Duration.ofSeconds(5));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.cellOutcomes()).isEmpty();
        assertThat(result.allSucceeded()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEmptyMatrixIsNotVacuouslySuccessfulWhenTheExecutionItselfFailed() throws InterruptedException {
        // Regression test: allSucceeded() must not treat an empty cellOutcomes list as trivially
        // "all matched" (Stream.allMatch on an empty stream returns true by definition) when that
        // emptiness is actually because the whole execution failed before producing any per-cell
        // output, rather than because zero cells were requested.
        when(sfnClient.describeExecution(any(Consumer.class)))
                .thenReturn(DescribeExecutionResponse.builder().status(ExecutionStatus.FAILED)
                        .error("States.Runtime").cause("bad definition").build());

        var result = supervisor.supervise("arn:...:stateMachine:build", "exec-1", "{\"cells\":[]}",
                Duration.ofMillis(10), Duration.ofSeconds(5));

        assertThat(result.cellOutcomes()).isEmpty();
        assertThat(result.allSucceeded())
                .as("an execution-level failure must not read as success just because there were "
                        + "no per-cell outcomes to check")
                .isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void passesTheMatrixInputThrough() throws InterruptedException {
        when(sfnClient.describeExecution(any(Consumer.class)))
                .thenReturn(DescribeExecutionResponse.builder().status(ExecutionStatus.SUCCEEDED)
                        .output("[]").build());

        supervisor.supervise("arn:...:stateMachine:build", "exec-1",
                "{\"cells\":[{\"buildId\":\"b1\"}]}", Duration.ofMillis(10), Duration.ofSeconds(5));

        var captor = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        org.mockito.Mockito.verify(sfnClient).startExecution(captor.capture());
        StartExecutionRequest.Builder builder = StartExecutionRequest.builder();
        captor.getValue().accept(builder);
        StartExecutionRequest request = builder.build();

        assertThat(request.stateMachineArn()).isEqualTo("arn:...:stateMachine:build");
        assertThat(request.name()).isEqualTo("exec-1");
        assertThat(request.input()).isEqualTo("{\"cells\":[{\"buildId\":\"b1\"}]}");
    }
}
