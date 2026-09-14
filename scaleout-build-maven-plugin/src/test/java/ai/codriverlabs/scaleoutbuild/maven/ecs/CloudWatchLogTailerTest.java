/*
 * Copyright © 2026 Plasticity.Cloud Limited & CoDriverLabs Limited. All rights reserved.
 */
package ai.codriverlabs.scaleoutbuild.maven.ecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
class CloudWatchLogTailerTest {

    @Mock
    private CloudWatchLogsClient logsClient;

    private CloudWatchLogTailer tailer;
    private List<String> forwardedLines;

    @BeforeEach
    void setUp() {
        tailer = new CloudWatchLogTailer(logsClient);
        forwardedLines = new ArrayList<>();
    }

    @Test
    void forwardsNewEventsAndReturnsTheNewestTimestamp() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant second = Instant.parse("2026-01-01T00:00:01Z");
        when(logsClient.filterLogEvents(any(java.util.function.Consumer.class)))
                .thenReturn(FilterLogEventsResponse.builder()
                        .events(
                                FilteredLogEvent.builder().timestamp(first.toEpochMilli())
                                        .message("line 1").build(),
                                FilteredLogEvent.builder().timestamp(second.toEpochMilli())
                                        .message("line 2").build())
                        .build());

        Instant result = tailer.pollOnce("/scaleout-build/build-agent", "scaleout-build", Instant.EPOCH,
                forwardedLines::add);

        assertThat(forwardedLines).containsExactly("line 1", "line 2");
        assertThat(result).isEqualTo(second);
    }

    @Test
    void doesNotReforwardEventsAtOrBeforeTheWatermark() {
        Instant watermark = Instant.parse("2026-01-01T00:00:01Z");
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-01-01T00:00:02Z");
        when(logsClient.filterLogEvents(any(java.util.function.Consumer.class)))
                .thenReturn(FilterLogEventsResponse.builder()
                        .events(
                                FilteredLogEvent.builder().timestamp(older.toEpochMilli())
                                        .message("stale").build(),
                                FilteredLogEvent.builder().timestamp(watermark.toEpochMilli())
                                        .message("at watermark, already seen").build(),
                                FilteredLogEvent.builder().timestamp(newer.toEpochMilli())
                                        .message("genuinely new").build())
                        .build());

        Instant result = tailer.pollOnce("/scaleout-build/build-agent", "scaleout-build", watermark,
                forwardedLines::add);

        assertThat(forwardedLines).containsExactly("genuinely new");
        assertThat(result).isEqualTo(newer);
    }

    @Test
    void returnsTheWatermarkUnchangedWhenTheStreamDoesNotExistYet() {
        when(logsClient.filterLogEvents(any(java.util.function.Consumer.class)))
                .thenThrow(ResourceNotFoundException.builder().message("not found").build());

        Instant watermark = Instant.parse("2026-01-01T00:00:00Z");
        Instant result = tailer.pollOnce("/scaleout-build/build-agent", "scaleout-build", watermark,
                forwardedLines::add);

        assertThat(result).isEqualTo(watermark);
        assertThat(forwardedLines).isEmpty();
    }

    @Test
    void followsPaginationAcrossMultiplePages() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T00:00:01Z");
        when(logsClient.filterLogEvents(any(java.util.function.Consumer.class)))
                .thenReturn(FilterLogEventsResponse.builder()
                        .events(FilteredLogEvent.builder().timestamp(t1.toEpochMilli())
                                .message("page 1").build())
                        .nextToken("token-2")
                        .build())
                .thenReturn(FilterLogEventsResponse.builder()
                        .events(FilteredLogEvent.builder().timestamp(t2.toEpochMilli())
                                .message("page 2").build())
                        .build());

        Instant result = tailer.pollOnce("/scaleout-build/build-agent", "scaleout-build", Instant.EPOCH,
                forwardedLines::add);

        assertThat(forwardedLines).containsExactly("page 1", "page 2");
        assertThat(result).isEqualTo(t2);
    }
}
