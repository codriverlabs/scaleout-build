/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.backend;

import ai.codriverlabs.scaleoutbuild.controlplane.api.ArtifactListResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildSpec;
import ai.codriverlabs.scaleoutbuild.controlplane.api.BuildStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CellState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.ArtifactDownload;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CellStatus;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildRequest;
import ai.codriverlabs.scaleoutbuild.controlplane.api.CreateBuildResponse;
import ai.codriverlabs.scaleoutbuild.controlplane.api.InputDescriptor;
import ai.codriverlabs.scaleoutbuild.controlplane.api.LogEvent;
import ai.codriverlabs.scaleoutbuild.controlplane.api.RequestedResources;
import ai.codriverlabs.scaleoutbuild.controlplane.api.StreamEndReason;
import ai.codriverlabs.scaleoutbuild.controlplane.api.UploadTarget;
import ai.codriverlabs.scaleoutbuild.maven.MatrixCell;
import ai.codriverlabs.scaleoutbuild.planner.NativeImageInputPlan;
import ai.codriverlabs.scaleoutbuild.planner.StagedFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;

/**
 * Runs remote cells by calling the builder control plane. The only backend.
 *
 * <p>The client holds no AWS permissions on ECS, S3, CloudWatch or ECR. It needs
 * {@code lambda:InvokeFunctionUrl} and {@code lambda:InvokeFunction} on one function, and knows one
 * deployment value: the endpoint.
 *
 * <h2>Cancellation</h2>
 *
 * In the direct-ECS model the Maven process <em>was</em> the supervisor, so killing the build killed
 * the thing that would have stopped the tasks. Here nothing stops them unless the server is told, so
 * this class does both halves of that:
 *
 * <ul>
 *   <li>A shutdown hook issues {@code DELETE /builds/{id}} on {@code Ctrl+C}, giving the same
 *       observable behaviour as interrupting a local build.</li>
 *   <li>A heartbeat thread posts liveness while waiting, so a client killed in a way that skips
 *       shutdown hooks ({@code kill -9}, a closed terminal, a crash) is reaped server-side instead of
 *       leaving Fargate tasks billing.</li>
 * </ul>
 *
 * <p>Client disconnect is deliberately <em>not</em> relied on: AWS documents that a streamed response
 * is not interrupted when the connection breaks, and bills for the full duration, so hanging up is
 * invisible to the service.
 */
public final class ServiceBuildBackend implements BuildBackend {

    private final ControlPlaneClient client;
    private final ServiceBuildOptions options;
    private final Log log;

    public ServiceBuildBackend(ServiceBuildOptions options, Log log) {
        this.options = options;
        this.log = log;
        this.client = new ControlPlaneClient(options.endpoint());
    }

    @Override
    public List<String> runCells(List<MatrixCell> cells, NativeImageInputPlan plan, String buildId,
                                 ArtifactSink artifactSink)
            throws IOException, MojoExecutionException, MojoFailureException, InterruptedException {
        BuildSpec spec = toBuildSpec(cells);
        List<StagedFile> staged = stagedFiles(plan);
        List<InputDescriptor> inputs = describeInputs(staged);

        log.info("Submitting " + cells.size() + " cell(s) to " + options.endpoint()
                + " (signing region " + client.region() + ")");
        CreateBuildResponse created = client.createBuild(new CreateBuildRequest(spec, inputs,
                options.requestedResources(), options.clientVersion()));
        log.info("Build " + created.buildId() + ": " + created.uploads().size() + " input(s) to upload, "
                + created.alreadyStaged().size() + " already staged");
        if (!created.appliedResources().equals(options.requestedResources())
                && options.requestedResources() != null) {
            // Surfaced rather than silently applied, so a clamped request is visible in the log.
            log.info("Server applied resources: cpu=" + created.appliedResources().cpu()
                    + " memory=" + created.appliedResources().memory()
                    + " ephemeralStorageGiB=" + created.appliedResources().ephemeralStorageGiB());
        }

        upload(created, inputs, staged);

        ScheduledExecutorService heartbeat = startHeartbeat(created);
        Thread shutdownHook = installCancelHook(created.buildId());
        try {
            client.startBuild(created.buildId());
            BuildStatus terminal = streamUntilTerminal(created.buildId());
            return collect(terminal, artifactSink);
        } finally {
            heartbeat.shutdownNow();
            // Removing the hook is what makes a normal completion not issue a cancel on JVM exit.
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // Nothing to do: the hook is running or has run.
            }
        }
    }

    // --- Submission ---------------------------------------------------------------------------

    private BuildSpec toBuildSpec(List<MatrixCell> cells) {
        List<String> kinds = cells.stream().map(c -> c.buildKind().configValue()).distinct().toList();
        List<String> architectures = cells.stream()
                .map(c -> c.architecture().name().toLowerCase(Locale.ROOT)).distinct().toList();
        return new BuildSpec(kinds, architectures, options.mainClass(), options.imageName(),
                options.nativeImageCommand(), options.extraNativeImageArgs(), options.extraBuildArgs(),
                options.timeoutMinutes(), options.overallTimeoutMinutes());
    }

    /**
     * Describes every staged input by content digest.
     *
     * <p>Digests are what make the upload negotiation work: the server replies with only the ones it
     * lacks. In practice that is the project's own jar, because Maven embeds timestamps and so its jar
     * is not byte-reproducible, while dependency jars are identical across builds.
     */
    /**
     * Everything the cell needs in its staging prefix, including the argfile.
     *
     * <p>In {@code DERIVED} mode the argfile is generated text rather than a file on disk, so it is
     * written out here and then treated as an ordinary content-addressed input. The alternative would be
     * to add an {@code argsContent} field to the wire contract and have the service write the object,
     * which buys nothing: as a normal input it dedups like any other blob, and the service keeps a single
     * code path that materializes declared inputs and nothing else.
     *
     * <p>Omitting it is what the first real end-to-end build failed on. Both cells launched, downloaded
     * their two jars, and died with "Argument file not found" -- the agent requires the argfile named by
     * {@code SCALEOUT_BUILD_ARG_FILE_NAME} to be present in the prefix, and the direct-ECS backend used
     * to write it as a side effect of staging.
     */
    private List<StagedFile> stagedFiles(NativeImageInputPlan plan) throws IOException {
        List<StagedFile> files = new ArrayList<>(plan.files());
        if (plan.generatedArgsContent().isPresent()) {
            Path argsFile = options.workDirectory().resolve(plan.argsFileName());
            Files.createDirectories(argsFile.getParent());
            Files.writeString(argsFile, plan.generatedArgsContent().orElseThrow());
            files.add(new StagedFile(plan.argsFileName(), argsFile));
        }
        return files;
    }

    private List<InputDescriptor> describeInputs(List<StagedFile> stagedFiles) throws IOException {
        List<InputDescriptor> inputs = new ArrayList<>();
        for (StagedFile file : stagedFiles) {
            Path source = file.source();
            inputs.add(new InputDescriptor(file.relativePath(), sha256Hex(source),
                    Files.size(source)));
        }
        return inputs;
    }

    private void upload(CreateBuildResponse created, List<InputDescriptor> inputs,
                        List<StagedFile> stagedFiles) throws IOException {
        if (created.uploads().isEmpty()) {
            return;
        }
        Map<String, Path> byDigest = new HashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            byDigest.putIfAbsent(inputs.get(i).sha256(), stagedFiles.get(i).source());
        }
        long bytes = 0;
        for (UploadTarget target : created.uploads()) {
            Path source = byDigest.get(target.sha256());
            if (source == null) {
                throw new IOException("the service asked for digest " + target.sha256()
                        + ", which is not in the manifest this client sent");
            }
            client.putPresigned(target.url(), source);
            bytes += Files.size(source);
        }
        log.info(String.format(Locale.ROOT, "Uploaded %d input(s), %.1f MB",
                created.uploads().size(), bytes / 1048576.0));
    }

    // --- Liveness and cancellation -------------------------------------------------------------

    private ScheduledExecutorService startHeartbeat(CreateBuildResponse created) {
        int interval = Math.max(5, created.heartbeatIntervalSeconds());
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "scaleout-build-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                client.heartbeat(created.buildId());
            } catch (IOException e) {
                // Not fatal: missing one heartbeat does not reap a build, and failing the build over a
                // transient error while the remote build is healthy would be worse than a warning.
                log.debug("Heartbeat failed: " + e.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);
        return executor;
    }

    private Thread installCancelHook(String buildId) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread hook = new Thread(() -> {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            try {
                // Deliberately synchronous: returning before StopTask has been issued would leave
                // paid-for tasks running, which is the failure this hook exists to prevent.
                client.cancel(buildId);
                log.info("Cancelled remote build " + buildId);
            } catch (IOException e) {
                log.warn("Could not cancel remote build " + buildId + ": " + e.getMessage()
                        + " -- the server will reap it once heartbeats stop");
            }
        }, "scaleout-build-cancel");
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    // --- Observation --------------------------------------------------------------------------

    /**
     * Streams logs, reconnecting across the service's streaming budget, until every cell is terminal.
     *
     * <p>Only {@link StreamEndReason#BUILD_TERMINAL} means stop. The other reasons are expected for any
     * build longer than the streaming Lambda's budget, and resume from the per-cell watermarks: a client
     * that treated every stream end as completion would silently truncate long builds.
     */
    private BuildStatus streamUntilTerminal(String buildId)
            throws IOException, InterruptedException, MojoExecutionException {
        Long since = null;
        for (int reconnects = 0; reconnects < 64; reconnects++) {
            LogEvent last = client.streamLogs(buildId, since, this::render);
            if (last == null) {
                // The stream ended without a terminating event, e.g. a dropped connection. Fall back
                // to polling so a network blip does not fail an otherwise healthy build.
                BuildStatus status = client.status(buildId);
                if (status.state().isTerminal()) {
                    return status;
                }
                Thread.sleep(2000);
                continue;
            }
            if (last.type() == LogEvent.Type.STREAM_END && last.reason() != null) {
                if (!last.reason().shouldReconnect()) {
                    return client.status(buildId);
                }
                since = last.nextSince().values().stream().min(Long::compareTo).orElse(since);
                log.debug("Log stream ended with " + last.reason() + "; resuming from " + since);
            }
        }
        throw new MojoExecutionException("gave up after 64 log-stream reconnects for build " + buildId);
    }

    private void render(LogEvent event) {
        switch (event.type()) {
            case LOG -> log.info("[" + event.cell() + "] " + event.message());
            case CELL_STATE -> log.info("[" + event.cell() + "] -> " + event.cellState());
            case STREAM_END -> {
                // Reported by the caller, which knows whether it is reconnecting.
            }
            default -> {
            }
        }
    }

    // --- Artifacts ----------------------------------------------------------------------------

    private List<String> collect(BuildStatus terminal, ArtifactSink artifactSink) throws IOException {
        List<String> failures = new ArrayList<>();
        for (CellStatus cell : terminal.cells()) {
            if (cell.state() != CellState.SUCCEEDED) {
                failures.add(cell.cell() + " failed remotely: "
                        + (cell.failureReason() == null ? cell.state().toString()
                        : cell.failureReason()));
            }
        }
        if (!failures.isEmpty()) {
            return failures;
        }

        ArtifactListResponse artifacts = client.artifacts(terminal.buildId());
        for (ArtifactListResponse.CellArtifacts cellArtifacts : artifacts.cells()) {
            if (cellArtifacts.downloads().isEmpty()) {
                failures.add(cellArtifacts.cell()
                        + " succeeded remotely but the service reported no artifact");
                continue;
            }
            MatrixCell cell = parseCell(cellArtifacts.cell());
            Path destinationDir = options.workDirectory().resolve("remote-artifacts")
                    .resolve(cellArtifacts.cell().replace('/', '-'));
            List<Path> downloaded = new ArrayList<>();
            for (ArtifactDownload download : cellArtifacts.downloads()) {
                // download.path(), not options.imageName(): several artifacts per cell must not collapse
                // into one file, and imageName is absent for a framework-generated argfile.
                Path destination = destinationDir.resolve(download.path());
                client.getPresigned(download.url(), destination);
                downloaded.add(destination);
            }
            artifactSink.accept(cell, downloaded);
        }
        return failures;
    }

    private static MatrixCell parseCell(String cell) {
        String[] parts = cell.split("/");
        return new MatrixCell(
                ai.codriverlabs.scaleoutbuild.build.BuildKind.parse(parts[0]),
                ai.codriverlabs.scaleoutbuild.build.Architecture.parse(parts[1]));
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    /** Per-invocation inputs the backend needs from the Mojo. */
    public record ServiceBuildOptions(String endpoint, Path workDirectory, String mainClass,
                                      String imageName, String nativeImageCommand,
                                      List<String> extraNativeImageArgs, List<String> extraBuildArgs,
                                      int timeoutMinutes, int overallTimeoutMinutes,
                                      RequestedResources requestedResources, String clientVersion) {

        public ServiceBuildOptions {
            extraNativeImageArgs = extraNativeImageArgs == null ? List.of()
                    : List.copyOf(extraNativeImageArgs);
            extraBuildArgs = extraBuildArgs == null ? List.of() : List.copyOf(extraBuildArgs);
        }
    }
}
