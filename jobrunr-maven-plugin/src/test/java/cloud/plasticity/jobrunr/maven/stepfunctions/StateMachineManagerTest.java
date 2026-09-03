/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.maven.stepfunctions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.CreateStateMachineRequest;
import software.amazon.awssdk.services.sfn.model.CreateStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.ListStateMachinesRequest;
import software.amazon.awssdk.services.sfn.model.ListStateMachinesResponse;
import software.amazon.awssdk.services.sfn.model.ListTagsForResourceResponse;
import software.amazon.awssdk.services.sfn.model.StateMachineListItem;
import software.amazon.awssdk.services.sfn.model.Tag;

@ExtendWith(MockitoExtension.class)
class StateMachineManagerTest {

    @Mock
    private SfnClient sfnClient;

    private StateMachineManager manager;
    private ObjectMapper objectMapper;
    private ObjectNode definition;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        manager = new StateMachineManager(sfnClient, objectMapper);
        definition = BuildMatrixStateMachineDefinition.build(objectMapper, 5, 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsANewStateMachineWhenNoneWithThatNameExists() {
        when(sfnClient.listStateMachines(any(Consumer.class)))
                .thenReturn(ListStateMachinesResponse.builder().stateMachines(java.util.List.of())
                        .build());
        when(sfnClient.createStateMachine(any(Consumer.class)))
                .thenReturn(CreateStateMachineResponse.builder()
                        .stateMachineArn("arn:aws:states:us-east-1:123456789012:stateMachine:build")
                        .build());

        String arn = manager.deployIfChanged("build", "arn:aws:iam::123456789012:role/sfn-exec",
                definition);

        assertThat(arn).isEqualTo("arn:aws:states:us-east-1:123456789012:stateMachine:build");
        verify(sfnClient, times(1)).createStateMachine(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reusesTheExistingStateMachineWhenTheConfigHashMatches() {
        String arn = "arn:aws:states:us-east-1:123456789012:stateMachine:build";
        String roleArn = "arn:aws:iam::123456789012:role/sfn-exec";

        // Capture the hash a real deployment would compute...
        String[] capturedHash = new String[1];
        when(sfnClient.listStateMachines(any(Consumer.class)))
                .thenReturn(ListStateMachinesResponse.builder().stateMachines(java.util.List.of())
                        .build());
        when(sfnClient.createStateMachine(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<CreateStateMachineRequest.Builder> consumer = invocation.getArgument(0);
            CreateStateMachineRequest.Builder builder = CreateStateMachineRequest.builder();
            consumer.accept(builder);
            capturedHash[0] = builder.build().tags().get(0).value();
            return CreateStateMachineResponse.builder().stateMachineArn(arn).build();
        });
        manager.deployIfChanged("build", roleArn, definition);
        assertThat(capturedHash[0]).isNotBlank();

        // ...then simulate a second invocation where the state machine already exists with that hash.
        SfnClient freshMock = org.mockito.Mockito.mock(SfnClient.class);
        when(freshMock.listStateMachines(any(Consumer.class)))
                .thenReturn(ListStateMachinesResponse.builder()
                        .stateMachines(StateMachineListItem.builder().name("build")
                                .stateMachineArn(arn).build())
                        .build());
        when(freshMock.listTagsForResource(any(Consumer.class)))
                .thenReturn(ListTagsForResourceResponse.builder()
                        .tags(Tag.builder().key(StateMachineManager.CONFIG_HASH_TAG_KEY)
                                .value(capturedHash[0]).build())
                        .build());
        StateMachineManager secondManager = new StateMachineManager(freshMock, objectMapper);

        String reusedArn = secondManager.deployIfChanged("build", roleArn, definition);

        assertThat(reusedArn).isEqualTo(arn);
        verify(freshMock, never()).createStateMachine(any(Consumer.class));
        verify(freshMock, never()).updateStateMachine(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatesAndRetagsWhenTheConfigHashDiffers() {
        String arn = "arn:aws:states:us-east-1:123456789012:stateMachine:build";
        when(sfnClient.listStateMachines(any(Consumer.class)))
                .thenReturn(ListStateMachinesResponse.builder()
                        .stateMachines(StateMachineListItem.builder().name("build")
                                .stateMachineArn(arn).build())
                        .build());
        when(sfnClient.listTagsForResource(any(Consumer.class)))
                .thenReturn(ListTagsForResourceResponse.builder()
                        .tags(Tag.builder().key(StateMachineManager.CONFIG_HASH_TAG_KEY)
                                .value("stale-hash").build())
                        .build());

        String reusedArn = manager.deployIfChanged("build",
                "arn:aws:iam::123456789012:role/sfn-exec", definition);

        assertThat(reusedArn).isEqualTo(arn);
        verify(sfnClient, times(1)).updateStateMachine(any(Consumer.class));
        verify(sfnClient, times(1)).tagResource(any(Consumer.class));
        verify(sfnClient, never()).createStateMachine(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findsAnExistingStateMachineAcrossMultiplePagesOfListStateMachines() {
        String arn = "arn:aws:states:us-east-1:123456789012:stateMachine:build";
        when(sfnClient.listStateMachines(any(Consumer.class)))
                .thenAnswer(invocation -> {
                    Consumer<ListStateMachinesRequest.Builder> consumer = invocation.getArgument(0);
                    ListStateMachinesRequest.Builder builder = ListStateMachinesRequest.builder();
                    consumer.accept(builder);
                    ListStateMachinesRequest request = builder.build();
                    if (request.nextToken() == null) {
                        return ListStateMachinesResponse.builder()
                                .stateMachines(StateMachineListItem.builder().name("other")
                                        .stateMachineArn("arn:...:other").build())
                                .nextToken("page-2")
                                .build();
                    }
                    return ListStateMachinesResponse.builder()
                            .stateMachines(StateMachineListItem.builder().name("build")
                                    .stateMachineArn(arn).build())
                            .build();
                });
        when(sfnClient.listTagsForResource(any(Consumer.class)))
                .thenReturn(ListTagsForResourceResponse.builder().tags(java.util.List.of()).build());

        String reusedArn = manager.deployIfChanged("build",
                "arn:aws:iam::123456789012:role/sfn-exec", definition);

        assertThat(reusedArn).isEqualTo(arn);
        verify(sfnClient, times(1)).updateStateMachine(any(Consumer.class));
    }
}
