/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Outcome of a successful build.
 *
 * <p>Only reported locally. JobRunr's community edition discards job return values (Job Result is a
 * Pro feature), so a remote build communicates its outcome through the produced files on the shared
 * mount and through the job's terminal state — not through this object.
 *
 * @param exitCode  exit status of the {@code native-image} process, always 0 here since a non-zero
 *                  status is raised as an exception so JobRunr can apply its retry policy
 * @param duration  wall-clock duration of the build process
 * @param artifacts absolute paths of the binaries produced, in discovery order
 */
public record BuildResult(int exitCode, Duration duration, List<Path> artifacts) {

    public BuildResult(int exitCode, Duration duration, List<Path> artifacts) {
        this.exitCode = exitCode;
        this.duration = duration;
        this.artifacts = List.copyOf(artifacts);
    }
}
