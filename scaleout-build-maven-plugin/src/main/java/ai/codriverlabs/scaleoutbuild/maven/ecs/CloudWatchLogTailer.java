/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.ecs;

import ai.codriverlabs.scaleoutbuild.build.BuildLog;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;

/**
 * Tails a running task's CloudWatch Logs stream into the Maven console, incrementally.
 *
 * <p>Polls with {@code FilterLogEvents}, whose filter is a stream-name <em>prefix</em>. Callers
 * supervising a specific task should pass that task's full, exact stream name (see
 * {@code EcsTaskSupervisor#logStreamNameFor}) rather than the task definition's shared
 * {@code awslogs-stream-prefix}: a full name used as a prefix matches exactly the one stream, while
 * the bare shared prefix matches every concurrent task's stream in the group. Passing the shared
 * prefix is not merely noisy — the watermark below is a single timestamp, so events interleaved from
 * another task advance it past the current task's own unread lines and those lines are then dropped
 * as "already forwarded". The stream name is derivable as soon as {@code RunTask} returns, so there
 * is no window in which only the prefix is known.
 *
 * <p>Uses a moving {@code startTime} watermark rather than {@code nextToken} pagination: for a
 * continuously appended stream being tailed live (not read once to completion), tracking "the
 * timestamp after the last event we've printed" is simpler to reason about than the token
 * semantics, and de-duplicates naturally across polls using each event's own timestamp.
 */
public final class CloudWatchLogTailer {

    private static final Logger LOG = LoggerFactory.getLogger(CloudWatchLogTailer.class);

    private final CloudWatchLogsClient logsClient;

    public CloudWatchLogTailer(CloudWatchLogsClient logsClient) {
        this.logsClient = Objects.requireNonNull(logsClient, "logsClient");
    }

    /**
     * Fetches and forwards any log events newer than {@code sinceExclusive} to {@code sink}.
     *
     * @return the timestamp of the newest event forwarded, or {@code sinceExclusive} unchanged if
     *         nothing new was found — pass this back in as {@code sinceExclusive} on the next poll
     */
    public Instant pollOnce(String logGroupName, String logStreamNamePrefix, Instant sinceExclusive,
                            BuildLog sink) {
        Objects.requireNonNull(logGroupName, "logGroupName");
        Objects.requireNonNull(logStreamNamePrefix, "logStreamNamePrefix");
        Objects.requireNonNull(sinceExclusive, "sinceExclusive");
        Objects.requireNonNull(sink, "sink");

        List<FilteredLogEvent> events;
        try {
            events = fetchAllPages(logGroupName, logStreamNamePrefix, sinceExclusive);
        } catch (ResourceNotFoundException e) {
            // The log stream does not exist yet -- normal in the window between RunTask returning
            // and the container actually starting and emitting its first line.
            LOG.debug("Log stream for prefix '{}' not found yet in group {}", logStreamNamePrefix,
                    logGroupName);
            return sinceExclusive;
        }

        Instant newestSeen = sinceExclusive;
        for (FilteredLogEvent event : events) {
            Instant eventTime = Instant.ofEpochMilli(event.timestamp());
            if (!eventTime.isAfter(sinceExclusive)) {
                continue; // already forwarded on a previous poll
            }
            sink.line(event.message());
            if (eventTime.isAfter(newestSeen)) {
                newestSeen = eventTime;
            }
        }
        return newestSeen;
    }

    private List<FilteredLogEvent> fetchAllPages(String logGroupName, String logStreamNamePrefix,
                                                 Instant sinceExclusive) {
        List<FilteredLogEvent> allEvents = new java.util.ArrayList<>();
        String nextToken = null;
        do {
            final String token = nextToken;
            var response = logsClient.filterLogEvents(b -> {
                b.logGroupName(logGroupName)
                        .logStreamNamePrefix(logStreamNamePrefix)
                        .startTime(sinceExclusive.toEpochMilli())
                        .interleaved(true);
                if (token != null) {
                    b.nextToken(token);
                }
            });
            allEvents.addAll(response.events());
            nextToken = response.nextToken();
        } while (nextToken != null);

        allEvents.sort(Comparator.comparing(FilteredLogEvent::timestamp));
        return allEvents;
    }
}
