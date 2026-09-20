/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.controlplane.api;

import java.util.Map;

/**
 * One Server-Sent Event on a {@link BuildApi#streamLogs} stream.
 *
 * <p>Three shapes, discriminated by {@link #type()}:
 * <ul>
 *   <li>{@link Type#LOG} — {@code cell}, {@code timestamp}, {@code message} set</li>
 *   <li>{@link Type#CELL_STATE} — {@code cell}, {@code cellState} set</li>
 *   <li>{@link Type#STREAM_END} — {@code reason} and, unless the reason is
 *       {@link StreamEndReason#BUILD_TERMINAL}, {@code nextSince} set</li>
 * </ul>
 *
 * @param type      which shape this event is
 * @param cell      cell identifier for {@code LOG} and {@code CELL_STATE}
 * @param timestamp event time in epoch millis, for {@code LOG}
 * @param message   the log line, for {@code LOG}
 * @param cellState new state, for {@code CELL_STATE}
 * @param reason    why the stream ended, for {@code STREAM_END}
 * @param nextSince <b>per-cell</b> resume watermarks, for {@code STREAM_END}. Keyed by cell
 *                  identifier. It is a map and not a single timestamp on purpose: one watermark
 *                  shared across concurrently running cells lets the faster cell's timestamps
 *                  suppress the slower cell's unread lines, which this project shipped and fixed
 *                  once already (commit {@code 7782771}). A client that collapses this to a scalar
 *                  reintroduces that bug on every reconnect
 */
public record LogEvent(Type type, String cell, Long timestamp, String message, CellState cellState,
                       StreamEndReason reason, Map<String, Long> nextSince) {

    /** Event discriminator. */
    public enum Type {
        LOG, CELL_STATE, STREAM_END
    }

    public LogEvent {
        nextSince = nextSince == null ? Map.of() : Map.copyOf(nextSince);
    }

    /** A build output line from one cell. */
    public static LogEvent log(String cell, long timestamp, String message) {
        return new LogEvent(Type.LOG, cell, timestamp, message, null, null, null);
    }

    /** A cell state transition, so clients need not poll {@link BuildApi#getBuild} to notice. */
    public static LogEvent cellState(String cell, CellState state) {
        return new LogEvent(Type.CELL_STATE, cell, null, null, state, null, null);
    }

    /** Terminates the stream, carrying resume watermarks when reconnection is expected. */
    public static LogEvent streamEnd(StreamEndReason reason, Map<String, Long> nextSince) {
        return new LogEvent(Type.STREAM_END, null, null, null, null, reason, nextSince);
    }
}
