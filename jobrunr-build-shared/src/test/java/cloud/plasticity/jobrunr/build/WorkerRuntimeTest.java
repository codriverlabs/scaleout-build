/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import static org.assertj.core.api.Assertions.assertThat;

import cloud.plasticity.jobrunr.build.storage.StorageProviderFactory;
import cloud.plasticity.jobrunr.build.storage.StorageSettings;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.JobId;
import org.jobrunr.jobs.states.FailedState;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.utils.mapper.JsonMapper;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test of the same enqueue/worker/completion path the Maven plugin's local mode uses:
 * this is the "vertical slice" the design doc's Task 1 demo describes, minus Maven itself.
 */
class WorkerRuntimeTest {

    @Test
    void aSuccessfulJobIsRecordedAsSucceeded() throws InterruptedException {
        JsonMapper jsonMapper = StorageProviderFactory.jsonMapper();
        StorageProvider storageProvider =
                StorageProviderFactory.create(StorageSettings.inMemory(), jsonMapper);
        BuildResult fakeResult = new BuildResult(0, Duration.ofMillis(1), List.of());
        BuildExecutor succeedingExecutor = (request, log) -> fakeResult;

        WorkerRuntime.WorkerOptions options = WorkerRuntime.WorkerOptions.defaults()
                .overallTimeout(Duration.ofSeconds(30))
                .idleTimeout(Duration.ofSeconds(10));

        JobId jobId;
        try (WorkerRuntime worker =
                WorkerRuntime.start(storageProvider, jsonMapper, succeedingExecutor, options)) {
            JobRequestScheduler scheduler = new JobRequestScheduler(storageProvider);
            BuildJobRequest request = BuildJobRequest.builder()
                    .buildId("build-1")
                    .architecture(Architecture.host().orElse(Architecture.X86_64))
                    .stagingRelativePath("builds/build-1/x86_64")
                    .build();
            jobId = scheduler.enqueue(request);

            boolean completed = worker.awaitCompletion(1);
            assertThat(completed).isTrue();
        }

        Job job = storageProvider.getJobById(jobId);
        assertThat(job.hasState(StateName.SUCCEEDED)).isTrue();
    }

    @Test
    void aFailedJobIsRecordedAsFailedWithoutBeingRetried() throws InterruptedException {
        JsonMapper jsonMapper = StorageProviderFactory.jsonMapper();
        StorageProvider storageProvider =
                StorageProviderFactory.create(StorageSettings.inMemory(), jsonMapper);
        java.util.concurrent.atomic.AtomicInteger invocationCount =
                new java.util.concurrent.atomic.AtomicInteger();
        BuildExecutor failingExecutor = (request, log) -> {
            invocationCount.incrementAndGet();
            throw new BuildFailedException("native-image is not on PATH");
        };

        WorkerRuntime.WorkerOptions options = WorkerRuntime.WorkerOptions.defaults()
                .overallTimeout(Duration.ofSeconds(30))
                .idleTimeout(Duration.ofSeconds(10));

        JobId jobId;
        try (WorkerRuntime worker =
                WorkerRuntime.start(storageProvider, jsonMapper, failingExecutor, options)) {
            JobRequestScheduler scheduler = new JobRequestScheduler(storageProvider);
            BuildJobRequest request = BuildJobRequest.builder()
                    .buildId("build-2")
                    .architecture(Architecture.host().orElse(Architecture.X86_64))
                    .stagingRelativePath("builds/build-2/x86_64")
                    .build();
            jobId = scheduler.enqueue(request);

            boolean completed = worker.awaitCompletion(1);
            assertThat(completed).isTrue();
        }

        Job job = storageProvider.getJobById(jobId);
        Optional<FailedState> failure =
                job.getJobStatesOfType(FailedState.class).reduce((first, second) -> second);
        assertThat(failure).isPresent();
        assertThat(failure.get().getExceptionMessage()).contains("native-image is not on PATH");
        // @Job(retries = 0) on the handler must prevent JobRunr's default retry-with-backoff.
        assertThat(invocationCount.get()).isEqualTo(1);
    }
}
