/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.ecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.plasticity.jobrunr.build.Architecture;
import cloud.plasticity.jobrunr.build.BuildJobRequest;
import cloud.plasticity.jobrunr.build.storage.StorageProviderFactory;
import cloud.plasticity.jobrunr.build.storage.StorageSettings;
import java.time.Duration;
import java.util.List;
import org.jobrunr.jobs.JobId;
import org.jobrunr.jobs.states.EnqueuedState;
import org.jobrunr.jobs.states.FailedState;
import org.jobrunr.jobs.states.ProcessingState;
import org.jobrunr.jobs.states.SucceededState;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.Task;

@ExtendWith(MockitoExtension.class)
// Every test here drives a bounded polling loop; a real bug (e.g. the earlier ClassCastException
// swallowed on a background thread) should fail fast with a clear timeout instead of hanging the
// whole build indefinitely.
@Timeout(10)
class BuildSupervisorTest {

    @Mock
    private EcsClient ecsClient;
    @Mock
    private CloudWatchLogsClient logsClient;

    private FargateTaskLauncher launcher;
    private CloudWatchLogTailer logTailer;
    private StorageProvider storageProvider;
    private EcsClusterSettings clusterSettings;
    private JobId jobId;
    private java.util.List<String> logLines;
    /**
     * Constructed but never started (never {@code .start()}-ed, so no polling threads run) --
     * exists purely so {@link org.jobrunr.jobs.Job#startProcessingOn} has a valid server identity to
     * record. {@code Job.updateProcessing()} does NOT perform the ENQUEUED -> PROCESSING
     * transition itself (it only refreshes an already-PROCESSING job's timestamp); using it where a
     * transition was needed previously caused a silent ClassCastException on this background
     * thread, which made the supervisor's polling loop spin until the test's own timeout with no
     * visible cause.
     */
    private org.jobrunr.server.BackgroundJobServer backgroundJobServer;
    private volatile Throwable backgroundThreadFailure;

    @BeforeEach
    void setUp() {
        launcher = new FargateTaskLauncher(ecsClient);
        logTailer = new CloudWatchLogTailer(logsClient);
        storageProvider = StorageProviderFactory.create(StorageSettings.inMemory(),
                StorageProviderFactory.jsonMapper());
        clusterSettings = new EcsClusterSettings(
                "arn:aws:ecs:us-east-1:123456789012:cluster/jobrunr-build",
                List.of("subnet-1"), List.of("sg-1"), false,
                "arn:aws:iam::123456789012:role/exec", "arn:aws:iam::123456789012:role/task",
                "arn:aws:s3files:us-east-1:123456789012:file-system/fs-abc123", null, null,
                "/jobrunr/build-agent", "us-east-1");
        logLines = new java.util.ArrayList<>();
        backgroundJobServer = new org.jobrunr.server.BackgroundJobServer(storageProvider,
                StorageProviderFactory.jsonMapper(), null,
                org.jobrunr.server.BackgroundJobServerConfiguration
                        .usingStandardBackgroundJobServerConfiguration());
        backgroundThreadFailure = null;

        JobRequestScheduler scheduler = new JobRequestScheduler(storageProvider);
        jobId = scheduler.enqueue(BuildJobRequest.builder()
                .buildId("build-1")
                .architecture(Architecture.host().orElse(Architecture.X86_64))
                .stagingRelativePath("builds/build-1/x86_64")
                .build());

        when(logsClient.filterLogEvents(any(java.util.function.Consumer.class)))
                .thenReturn(FilterLogEventsResponse.builder().events(List.of()).build());
    }

    @Test
    void succeedsAssoonAsTheJobReachesSucceeded() throws InterruptedException {
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").build())
                .build());
        // The job "completes" on a background thread shortly after supervision starts, simulating
        // the agent finishing its work while the supervisor is polling.
        markSucceededAfter(Duration.ofMillis(50));

        BuildSupervisor supervisor = new BuildSupervisor(launcher, logTailer, storageProvider, ecsClient);
        var result = supervisor.supervise(clusterSettings, "arn:...:task-definition/x:1", jobId,
                "jobrunr-build", logLines::add,
                BuildSupervisor.SupervisionOptions.defaults().pollInterval(Duration.ofMillis(20)));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.spotInterruptions()).isZero();
        assertThat(backgroundThreadFailure).isNull();
    }

    @Test
    void failsWithTheRecordedReasonWhenTheJobFails() throws InterruptedException {
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").build())
                .build());
        markFailedAfter(Duration.ofMillis(50), "native-image exited with 1");

        BuildSupervisor supervisor = new BuildSupervisor(launcher, logTailer, storageProvider, ecsClient);
        var result = supervisor.supervise(clusterSettings, "arn:...:task-definition/x:1", jobId,
                "jobrunr-build", logLines::add,
                BuildSupervisor.SupervisionOptions.defaults().pollInterval(Duration.ofMillis(20)));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failureReason()).contains("native-image exited with 1");
        assertThat(backgroundThreadFailure).isNull();
    }

    @Test
    void relaunchesOnSpotInterruptionThenSucceeds() throws InterruptedException {
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class)))
                .thenReturn(RunTaskResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/1").build()).build())
                .thenReturn(RunTaskResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/2").build()).build());
        // First task is reported STOPPED once we check it, simulating a Spot reclaim.
        when(ecsClient.describeTasks(any(java.util.function.Consumer.class)))
                .thenReturn(software.amazon.awssdk.services.ecs.model.DescribeTasksResponse.builder()
                        .tasks(Task.builder().taskArn("arn:...:task/1").lastStatus("STOPPED").build())
                        .build());

        // A single sequential driver thread rather than two independently-timed ones: driving the
        // same in-memory job from two threads on independent delays raced against each other and
        // threw ConcurrentJobModificationException on the loser's save() -- JobRunr's optimistic
        // locking correctly detected the conflict, but this test's simulation needs to model one
        // Spot interruption happening, being fully resolved (relaunch included), and only then the
        // replacement task's job completing -- never two writers touching the job at once.
        runInBackgroundAfter(Duration.ofMillis(30), () -> {
            simulateSpotInterruption();
            sleepUninterruptibly(Duration.ofMillis(80)); // let the supervisor observe & relaunch
            simulateSuccess();
        });

        BuildSupervisor supervisor = new BuildSupervisor(launcher, logTailer, storageProvider, ecsClient);
        var result = supervisor.supervise(clusterSettings, "arn:...:task-definition/x:1", jobId,
                "jobrunr-build", logLines::add,
                BuildSupervisor.SupervisionOptions.defaults().pollInterval(Duration.ofMillis(20)));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.spotInterruptions()).isEqualTo(1);
        verify(ecsClient, times(2))
                .runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class));
        assertThat(backgroundThreadFailure).isNull();
    }

    @Test
    void stopsTheTaskAndFailsOnOverallTimeout() throws InterruptedException {
        when(ecsClient.runTask(any(software.amazon.awssdk.services.ecs.model.RunTaskRequest.class))).thenReturn(RunTaskResponse.builder()
                .tasks(Task.builder().taskArn("arn:...:task/1").build())
                .build());
        // Job never reaches a terminal state -- forces the timeout path.

        BuildSupervisor supervisor = new BuildSupervisor(launcher, logTailer, storageProvider, ecsClient);
        var result = supervisor.supervise(clusterSettings, "arn:...:task-definition/x:1", jobId,
                "jobrunr-build", logLines::add,
                BuildSupervisor.SupervisionOptions.defaults()
                        .pollInterval(Duration.ofMillis(20))
                        .overallTimeout(Duration.ofMillis(60)));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failureReason()).contains("timeout");
        verify(ecsClient).stopTask(any(java.util.function.Consumer.class));
    }

    private void markSucceededAfter(Duration delay) {
        runInBackgroundAfter(delay, this::simulateSuccess);
    }

    private void markFailedAfter(Duration delay, String message) {
        runInBackgroundAfter(delay, () -> simulateFailure(message));
    }

    /** Moves the job PROCESSING -> (held briefly) -> SUCCEEDED, via a fresh startProcessingOn. */
    private void simulateSuccess() {
        var job = storageProvider.getJobById(jobId);
        job.startProcessingOn(backgroundJobServer);
        storageProvider.save(job);
        var processingJob = storageProvider.getJobById(jobId);
        processingJob.succeeded();
        storageProvider.save(processingJob);
    }

    /** Moves the job PROCESSING -> FAILED, via a fresh startProcessingOn. */
    private void simulateFailure(String message) {
        var job = storageProvider.getJobById(jobId);
        job.startProcessingOn(backgroundJobServer);
        storageProvider.save(job);
        var processingJob = storageProvider.getJobById(jobId);
        processingJob.failed(message, new RuntimeException(message));
        storageProvider.save(processingJob);
    }

    /**
     * Simulates a Spot interruption: the job moves to PROCESSING, is held there long enough for
     * the supervisor to observe it (poll interval is small; a real interruption leaves the job
     * ENQUEUED far longer than this), then re-queued via PROCESSING -&gt; FAILED -&gt; ENQUEUED.
     *
     * <p>JobRunr's state machine does not allow PROCESSING -&gt; ENQUEUED directly (verified via
     * {@code AllowedJobStateStateChanges} bytecode: {@code enqueue()} throws {@code
     * IllegalJobStateChangeException} from PROCESSING). The real path a Spot-interrupted job takes
     * is PROCESSING -&gt; FAILED -&gt; ENQUEUED, mirroring how JobRunr's own retry filter re-queues a
     * failed job -- FAILED is an allowed source state for ENQUEUED.
     */
    private void simulateSpotInterruption() {
        var job = storageProvider.getJobById(jobId);
        job.startProcessingOn(backgroundJobServer);
        storageProvider.save(job);
        sleepUninterruptibly(Duration.ofMillis(60));
        var processingJob = storageProvider.getJobById(jobId);
        processingJob.failed("Task was reclaimed by Spot", new RuntimeException("Spot interruption"));
        storageProvider.save(processingJob);
        var failedJob = storageProvider.getJobById(jobId);
        failedJob.enqueue();
        storageProvider.save(failedJob);
    }

    private void sleepUninterruptibly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runInBackgroundAfter(Duration delay, Runnable action) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delay.toMillis());
                action.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException | Error e) {
                // Surface driver-thread bugs immediately instead of letting the polling loop in
                // BuildSupervisor spin until the overall timeout with no visible cause -- this is
                // exactly the failure mode that made an earlier version of this test look "hung."
                backgroundThreadFailure = e;
            }
        }, "test-job-state-driver");
        thread.setDaemon(true);
        thread.start();
    }
}
