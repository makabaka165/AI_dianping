package com.hmdp.ai.task;

import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskEvent;
import com.hmdp.dto.ai.AiTaskStatus;
import com.hmdp.dto.ai.AiTaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AiTaskStreamServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private AiTaskRepository repository;

    @Mock
    private RTopic topic;

    private AiTaskStreamService service;

    @BeforeEach
    void setUp() {
        service = new AiTaskStreamService();
        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "streamTimeoutMinutes", 1L);
        lenient().when(redissonClient.getTopic("hmdp:ai:task:events:task-1")).thenReturn(topic);
    }

    @Test
    void streamShouldEmitSnapshotAndRemoveListenerOnTerminalSnapshot() {
        when(topic.addListener(eq(String.class), org.mockito.ArgumentMatchers.<MessageListener<String>>any()))
                .thenReturn(42);
        when(repository.find("task-1")).thenReturn(Optional.of(task(AiTaskStatus.SUCCESS, 2, 2)));

        AiTaskEvent event = service.stream("task-1").blockFirst();

        assertThat(event).isNotNull();
        assertThat(event.getTaskId()).isEqualTo("task-1");
        assertThat(event.getStatus()).isEqualTo(AiTaskStatus.SUCCESS);
        assertThat(event.getProgressCurrent()).isEqualTo(2);
        assertThat(event.getProgressTotal()).isEqualTo(2);
        verify(topic).removeListener(42);
    }

    @Test
    void streamShouldRegisterListenerBeforeReadingSnapshotAndCompleteOnTerminalTopicEvent() {
        ArgumentCaptor<MessageListener<String>> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);
        when(topic.addListener(eq(String.class), listenerCaptor.capture())).thenReturn(7);
        when(repository.find("task-1")).thenReturn(Optional.of(task(AiTaskStatus.RUNNING, 1, 2)));

        java.util.List<AiTaskEvent> events = new java.util.ArrayList<>();
        service.stream("task-1").subscribe(events::add);

        listenerCaptor.getValue().onMessage("hmdp:ai:task:events:task-1",
                "{\"taskId\":\"task-1\",\"status\":\"SUCCESS\",\"progressCurrent\":2,\"progressTotal\":2,\"timestampEpochMillis\":123}");

        assertThat(events).extracting(AiTaskEvent::getStatus)
                .containsExactly(AiTaskStatus.RUNNING, AiTaskStatus.SUCCESS);
        verify(topic).removeListener(7);
    }

    @Test
    void streamTimeoutShouldKeepRepositoryTaskStatus() {
        when(repository.find("task-1")).thenReturn(Optional.of(task(AiTaskStatus.RUNNING, 1, 2)));

        AiTaskEvent event = ReflectionTestUtils.invokeMethod(service, "timeoutEvent", "task-1");

        assertThat(event).isNotNull();
        assertThat(event.getStatus()).isEqualTo(AiTaskStatus.RUNNING);
        assertThat(event.getErrorMessage()).contains("status is unchanged");
    }

    @Test
    void streamTimeoutShouldStillEmitNeutralEventWhenRepositoryReadFails() {
        when(repository.find("task-1")).thenThrow(new IllegalStateException("redis down"));

        AiTaskEvent event = ReflectionTestUtils.invokeMethod(service, "timeoutEvent", "task-1");

        assertThat(event).isNotNull();
        assertThat(event.getTaskId()).isEqualTo("task-1");
        assertThat(event.getStatus()).isNull();
        assertThat(event.getErrorMessage()).contains("status is unchanged");
    }

    private AiTask task(AiTaskStatus status, Integer current, Integer total) {
        return AiTask.builder()
                .taskId("task-1")
                .type(AiTaskType.RAG_REBUILD_ALL)
                .status(status)
                .progressCurrent(current)
                .progressTotal(total)
                .createdAtEpochMillis(1L)
                .updatedAtEpochMillis(2L)
                .build();
    }
}
