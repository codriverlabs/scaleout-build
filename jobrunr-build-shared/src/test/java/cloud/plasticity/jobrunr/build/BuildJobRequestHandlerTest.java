/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Duration;
import org.jobrunr.jobs.annotations.Job;
import org.junit.jupiter.api.Test;

/**
 * Covers the handler's architecture guard, its {@code @Job(retries = 0)} contract, and that a build
 * failure propagates rather than being swallowed.
 */
class BuildJobRequestHandlerTest {

    @Test
    void retriesAreDisabledOnRun() throws NoSuchMethodException {
        Method run = BuildJobRequestHandler.class.getMethod("run", BuildJobRequest.class);
        Job annotation = run.getAnnotation(Job.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.retries()).isZero();
    }

    @Test
    void rejectsAJobTargetingTheWrongArchitecture() {
        Architecture wrongArchitecture =
                Architecture.host().map(this::otherArchitecture).orElse(Architecture.X86_64);
        BuildJobRequestHandler handler = new BuildJobRequestHandler(
                (request, log) -> {
                    throw new AssertionError("executor must not run for a mismatched architecture");
                },
                new JobCompletionTracker(), BuildLog.discarding());

        BuildJobRequest request = BuildJobRequest.builder()
                .buildId("build-1")
                .architecture(wrongArchitecture)
                .stagingRelativePath("builds/build-1/" + wrongArchitecture.stagingDirName())
                .build();

        assertThatThrownBy(() -> handler.run(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(wrongArchitecture.toString());
    }

    @Test
    void propagatesABuildFailureRatherThanSwallowingIt() {
        Architecture hostArchitecture = Architecture.host().orElse(Architecture.X86_64);
        JobCompletionTracker tracker = new JobCompletionTracker();
        BuildExecutor failingExecutor = (request, log) -> {
            throw new BuildFailedException("native-image exited with 1", 1);
        };
        BuildJobRequestHandler handler =
                new BuildJobRequestHandler(failingExecutor, tracker, BuildLog.discarding());

        BuildJobRequest request = BuildJobRequest.builder()
                .buildId("build-2")
                .architecture(hostArchitecture)
                .stagingRelativePath("builds/build-2/" + hostArchitecture.stagingDirName())
                .build();

        assertThatThrownBy(() -> handler.run(request)).isInstanceOf(BuildFailedException.class);
        // The finally block must still record completion so a waiting caller is not left hanging.
        assertThat(tracker.finishedCount()).isEqualTo(1);
    }

    @Test
    void runsTheExecutorAndTracksCompletionOnSuccess() throws Exception {
        Architecture hostArchitecture = Architecture.host().orElse(Architecture.X86_64);
        JobCompletionTracker tracker = new JobCompletionTracker();
        BuildResult result = new BuildResult(0, Duration.ofSeconds(1), java.util.List.of());
        BuildJobRequestHandler handler =
                new BuildJobRequestHandler((request, log) -> result, tracker, BuildLog.discarding());

        BuildJobRequest request = BuildJobRequest.builder()
                .buildId("build-3")
                .architecture(hostArchitecture)
                .stagingRelativePath("builds/build-3/" + hostArchitecture.stagingDirName())
                .build();

        handler.run(request);

        assertThat(tracker.startedCount()).isEqualTo(1);
        assertThat(tracker.finishedCount()).isEqualTo(1);
    }

    private Architecture otherArchitecture(Architecture architecture) {
        return architecture == Architecture.X86_64 ? Architecture.ARM64 : Architecture.X86_64;
    }
}
