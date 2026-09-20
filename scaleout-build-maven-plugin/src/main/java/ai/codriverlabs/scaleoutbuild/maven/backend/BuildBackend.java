/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.backend;

import ai.codriverlabs.scaleoutbuild.maven.MatrixCell;
import ai.codriverlabs.scaleoutbuild.maven.planner.NativeImageInputPlan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/**
 * Executes the matrix cells that do not build on the local host.
 *
 * <p>This is the seam described as Phase 0 in
 * {@code docs/design/control-plane/migration-from-direct-ecs-access.md}. It exists so that
 * {@code BuildMojo} does not know <em>how</em> a remote cell gets built:
 * {@link DirectEcsBuildBackend} talks to ECS, S3, and CloudWatch Logs with the developer's own AWS
 * credentials (today's behaviour), and a forthcoming control-plane client will implement the same
 * interface by calling one HTTPS endpoint instead.
 *
 * <p><b>The seam is deliberately coarse — one "run these cells" call — and this differs from the
 * design document's initial sketch</b> of an interface carrying {@code submit}/{@code status}/
 * {@code logs}/{@code cancel}/{@code artifacts}. Those five operations are the control plane's
 * <em>wire protocol</em>, not the plugin's seam: the direct backend has no notion of a build id to
 * poll or a stream to resume, because the client process <em>is</em> the supervisor. Forcing today's
 * blocking supervision loop into a submit-then-poll shape would be a redesign rather than the
 * behaviour-preserving refactor Phase 0 calls for. A service backend will express submit/poll/stream
 * internally, behind this same single method.
 *
 * <p>Artifact attachment stays with the caller, via {@link ArtifactSink}, because attaching to the
 * reactor needs {@code MavenProjectHelper}. Passing a callback rather than returning the paths also
 * preserves today's log interleaving exactly — each cell's artifact is attached as that cell
 * finishes, not after every cell has finished.
 */
public interface BuildBackend {

    /**
     * Runs every supplied cell, blocking until all have reached a terminal state.
     *
     * @param cells        the cells to build remotely; never empty
     * @param plan         the shared input plan (classpath, argfile, expected artifacts)
     * @param buildId      correlates staging paths and log output across cells
     * @param artifactSink invoked once per successful cell with the artifacts it produced
     * @return one human-readable failure message per failed cell; empty if every cell succeeded
     */
    List<String> runCells(List<MatrixCell> cells, NativeImageInputPlan plan, String buildId,
                          ArtifactSink artifactSink)
            throws IOException, MojoExecutionException, MojoFailureException, InterruptedException;

    /** Receives the artifacts a cell produced, for attaching to the Maven reactor. */
    @FunctionalInterface
    interface ArtifactSink {
        void accept(MatrixCell cell, List<Path> artifacts);
    }
}
