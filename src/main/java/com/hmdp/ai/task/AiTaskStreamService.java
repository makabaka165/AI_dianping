package com.hmdp.ai.task;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ai.AiTask;
import com.hmdp.dto.ai.AiTaskEvent;
import com.hmdp.dto.ai.AiTaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Bridges per-task Redisson topics to local SSE streams. The stream registers the topic listener before reading the
 * task snapshot, so late subscribers still receive current state. A terminal snapshot and a terminal topic event can
 * race and duplicate status semantically; SSE clients should treat status events as idempotent, and completed sinks
 * drop extra emits harmlessly.
 */
@Service
@Slf4j
public class AiTaskStreamService {

    @Resource(name = "memoryRedissonClient")
    private RedissonClient redissonClient;

    @Resource
    private AiTaskRepository repository;

    @Value("${hmdp.ai.task.stream-timeout-minutes:10}")
    private long streamTimeoutMinutes;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public Flux<AiTaskEvent> stream(String taskId) {
        return Flux.defer(() -> {
            Sinks.Many<AiTaskEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
            RTopic topic = redissonClient.getTopic(AiTaskEventPublisher.topicName(taskId));
            int listenerId = topic.addListener(String.class, (channel, message) -> {
                AiTaskEvent event = parse(message);
                if (event == null) {
                    return;
                }
                sink.tryEmitNext(event);
                if (terminal(event.getStatus())) {
                    sink.tryEmitComplete();
                }
            });

            return sink.asFlux()
                    .doOnSubscribe(subscription -> emitSnapshot(taskId, sink))
                    .timeout(timeout())
                    .onErrorResume(TimeoutException.class, throwable -> Flux.just(timeoutEvent(taskId)))
                    .doFinally(signalType -> topic.removeListener(listenerId));
        });
    }

    private void emitSnapshot(String taskId, Sinks.Many<AiTaskEvent> sink) {
        repository.find(taskId)
                .map(this::fromTask)
                .ifPresent(event -> {
                    sink.tryEmitNext(event);
                    if (terminal(event.getStatus())) {
                        sink.tryEmitComplete();
                    }
                });
    }

    private AiTaskEvent parse(String message) {
        try {
            return objectMapper.readValue(message, AiTaskEvent.class);
        } catch (Exception e) {
            log.debug("Parse AI task event failed", e);
            return null;
        }
    }

    private AiTaskEvent fromTask(AiTask task) {
        return AiTaskEvent.builder()
                .taskId(task.getTaskId())
                .status(task.getStatus())
                .progressCurrent(task.getProgressCurrent())
                .progressTotal(task.getProgressTotal())
                .errorMessage(task.getErrorMessage())
                .timestampEpochMillis(System.currentTimeMillis())
                .build();
    }

    private AiTaskEvent timeoutEvent(String taskId) {
        AiTaskEvent event;
        try {
            event = repository.find(taskId)
                    .map(this::fromTask)
                    .orElseGet(() -> emptyEvent(taskId));
        } catch (RuntimeException e) {
            log.warn("Read AI task snapshot after SSE timeout failed, taskId={}", taskId, e);
            event = emptyEvent(taskId);
        }
        event.setErrorMessage("AI task stream timed out; task status is unchanged");
        return event;
    }

    private AiTaskEvent emptyEvent(String taskId) {
        return AiTaskEvent.builder()
                .taskId(taskId)
                .timestampEpochMillis(System.currentTimeMillis())
                .build();
    }

    private Duration timeout() {
        return Duration.ofMinutes(Math.max(1, streamTimeoutMinutes));
    }

    private boolean terminal(AiTaskStatus status) {
        return status == AiTaskStatus.SUCCESS
                || status == AiTaskStatus.FAILED
                || status == AiTaskStatus.CANCELLED;
    }
}
