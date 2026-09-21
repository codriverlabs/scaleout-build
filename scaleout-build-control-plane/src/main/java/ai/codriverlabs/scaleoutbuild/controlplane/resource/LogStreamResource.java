/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.resource;

import ai.codriverlabs.scaleoutbuild.controlplane.api.CellState;
import ai.codriverlabs.scaleoutbuild.controlplane.api.LogEvent;
import ai.codriverlabs.scaleoutbuild.controlplane.api.StreamEndReason;
import ai.codriverlabs.scaleoutbuild.controlplane.auth.CallerIdentity;
import ai.codriverlabs.scaleoutbuild.controlplane.config.ControlPlaneConfig;
import ai.codriverlabs.scaleoutbuild.controlplane.service.BuildService;
import ai.codriverlabs.scaleoutbuild.controlplane.store.BuildRecord;
import ai.codriverlabs.scaleoutbuild.controlplane.store.BuildRepository;
import ai.codriverlabs.scaleoutbuild.ecs.CloudWatchLogTailer;
import ai.codriverlabs.scaleoutbuild.ecs.EcsTaskSupervisor;
import ai.codriverlabs.scaleoutbuild.ecs.TaskDefinitionRegistrar;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * Streams build output as Server-Sent Events.
 *
 * <p>Separate from {@link BuildResource} because SSE needs {@code @Produces(SERVER_SENT_EVENTS)},
 * {@code @RestStreamElementType} and a {@code Multi<>} return type, none of which the shared
 * {@code BuildApi} interface can express while remaining usable by a plain HTTP client.
 *
 * <h2>The watermark is per cell, and that is the whole point</h2>
 *
 * Each cell's task writes to its own CloudWatch log stream
 * ({@code <prefix>/<container>/<taskId>}), and cells run concurrently at different speeds. A single
 * shared watermark advances to whichever stream emitted most recently, which then suppresses the
 * slower cell's unread lines as "already seen". That is not hypothetical: commit {@code 7782771}
 * fixed exactly this in the Maven plugin, where the ARM64-labelled output carried 8 x86_64 mentions
 * against 6 arm64 ones — the faster task was eating the slower one's output.
 *
 * <p>So watermarks are tracked in a map keyed by cell, and {@code nextSince} is emitted as a map too,
 * so a client resuming after the Lambda timeout cannot collapse it back into one value.
 *
 * <h2>Why the stream ends before it is finished</h2>
 *
 * A Lambda invocation is capped at 900s and a {@code native-image} build can outlive that. Rather
 * than dying mid-stream, the stream closes deliberately at {@link #STREAM_BUDGET} with
 * {@link StreamEndReason#LAMBDA_TIMEOUT} and the per-cell watermarks needed to resume. Only
 * {@link StreamEndReason#BUILD_TERMINAL} means "do not reconnect".
 */
@Path("/builds")
public class LogStreamResource {

    /**
     * How long one streaming invocation runs before handing over to a reconnect. Comfortably inside
     * the 900s function timeout: the margin has to cover a final poll, the terminating event, and
     * the client's reconnect, all of which happen after this elapses.
     */
    static final Duration STREAM_BUDGET = Duration.ofSeconds(780);

    /** Poll cadence against CloudWatch Logs. Also the effective keepalive interval. */
    static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

    @Inject
    BuildRepository repository;

    @Inject
    BuildService buildService;

    @Inject
    ControlPlaneConfig config;

    @Inject
    CloudWatchLogTailer logTailer;

    @Context
    jakarta.ws.rs.container.ContainerRequestContext requestContext;

    @GET
    @Path("/{buildId}/logs")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Blocking
    public Multi<LogEvent> streamLogs(@PathParam("buildId") String buildId,
                                      @QueryParam("cell") String cellFilter,
                                      @QueryParam("since") Long since) {
        CallerIdentity caller = (CallerIdentity) requestContext.getProperty(CallerIdentity.PROPERTY);
        BuildRecord record = repository.findOwned(buildId, caller.ownerKey())
                // 404 rather than 403 for another owner's build, consistently with BuildResource.
                .orElseThrow(() -> new NotFoundException("No build with id " + buildId));

        Map<String, Instant> watermarks = new HashMap<>();
        Instant start = since == null ? Instant.EPOCH : Instant.ofEpochMilli(since);
        for (BuildRecord.CellRecord cell : record.getCells()) {
            if (cellFilter == null || cellFilter.equals(cell.getCell())) {
                watermarks.put(cell.getCell(), start);
            }
        }
        if (watermarks.isEmpty()) {
            throw new NotFoundException("No such cell in build " + buildId + ": " + cellFilter);
        }

        Map<String, CellState> lastReported = new HashMap<>();
        Instant deadline = Instant.now().plus(STREAM_BUDGET);
        AtomicBoolean finished = new AtomicBoolean(false);

        return Multi.createFrom().ticks().every(POLL_INTERVAL)
                .onOverflow().drop()
                .flatMap(tick -> {
                    if (finished.get()) {
                        return Multi.createFrom().empty();
                    }
                    return Multi.createFrom().iterable(
                            poll(buildId, caller, watermarks, lastReported, deadline, finished));
                })
                .select().first(event -> !finished.get() || event.type() == LogEvent.Type.STREAM_END);
    }

    /** One poll cycle: fresh state, new log lines, and possibly a terminating event. */
    private List<LogEvent> poll(String buildId, CallerIdentity caller, Map<String, Instant> watermarks,
                                Map<String, CellState> lastReported, Instant deadline,
                                AtomicBoolean finished) {
        List<LogEvent> events = new ArrayList<>();
        BuildRecord record = repository.findOwned(buildId, caller.ownerKey()).orElse(null);
        if (record == null) {
            finished.set(true);
            events.add(LogEvent.streamEnd(StreamEndReason.BUILD_TERMINAL, snapshot(watermarks)));
            return events;
        }
        // Reconcile against ECS on each poll: this is also what advances the build to a terminal
        // state, since nothing else is watching the tasks.
        record = buildService.refreshFromEcs(record);

        for (BuildRecord.CellRecord cell : record.getCells()) {
            if (!watermarks.containsKey(cell.getCell())) {
                continue;
            }
            if (cell.getTaskArn() != null) {
                events.addAll(drain(cell, watermarks));
            }
            CellState previous = lastReported.put(cell.getCell(), cell.getState());
            if (previous != cell.getState()) {
                events.add(LogEvent.cellState(cell.getCell(), cell.getState()));
            }
        }

        boolean allDone = watermarks.keySet().stream()
                .map(record::cell)
                .allMatch(c -> c != null && c.getState().isTerminal());
        if (allDone) {
            finished.set(true);
            events.add(LogEvent.streamEnd(StreamEndReason.BUILD_TERMINAL, Map.of()));
        } else if (Instant.now().isAfter(deadline)) {
            finished.set(true);
            events.add(LogEvent.streamEnd(StreamEndReason.LAMBDA_TIMEOUT, snapshot(watermarks)));
        }
        return events;
    }

    /**
     * Reads one cell's own log stream, advancing only that cell's watermark.
     *
     * <p>The tailer is given the full task-scoped stream name rather than the shared
     * {@code awslogs-stream-prefix}: the prefix matches every concurrent cell's stream, which is the
     * bug fixed in {@code 7782771}.
     */
    private List<LogEvent> drain(BuildRecord.CellRecord cell, Map<String, Instant> watermarks) {
        List<LogEvent> events = new ArrayList<>();
        String streamName = EcsTaskSupervisor.logStreamNameFor(
                TaskDefinitionRegistrar.LOG_STREAM_PREFIX, cell.getTaskArn());
        Instant before = watermarks.get(cell.getCell());
        try {
            Instant after = logTailer.pollOnce(config.ecs().logGroupName(), streamName, before,
                    line -> events.add(LogEvent.log(cell.getCell(), System.currentTimeMillis(), line)));
            watermarks.put(cell.getCell(), after);
        } catch (RuntimeException e) {
            // Log tailing is a convenience, not load-bearing: a transient CloudWatch error must not
            // end the stream. The next poll's wider window catches up.
            return List.of();
        }
        return events;
    }

    private static Map<String, Long> snapshot(Map<String, Instant> watermarks) {
        Map<String, Long> snapshot = new HashMap<>();
        watermarks.forEach((cell, instant) -> snapshot.put(cell, instant.toEpochMilli()));
        return snapshot;
    }
}
