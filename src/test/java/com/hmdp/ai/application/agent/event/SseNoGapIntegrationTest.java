package com.hmdp.ai.application.agent.event;

import com.hmdp.ai.application.dto.agent.AgentRunEventResponse;
import com.hmdp.ai.domain.run.RunEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class SseNoGapIntegrationTest {
    @Test
    void lastEventIdReplayAndConcurrentPublicationDeliverEachSequenceExactlyOnce() throws Exception {
        CapturingEmitter emitter = new CapturingEmitter();
        SseRunEventHub hub = new SseRunEventHub(() -> emitter, 1000);
        List<AgentRunEventResponse> persisted = new CopyOnWriteArrayList<>(
                Arrays.asList(event(1, "run.started"), event(2, "node.completed")));
        AtomicLong latest = new AtomicLong(2);
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);

        Thread opener = new Thread(() -> hub.open("tenant", "workspace", "run", 1,
                latest::get, after -> {
                    loaderEntered.countDown();
                    try {
                        releaseLoader.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    return after(persisted, after);
                }, false));
        opener.start();
        assertThat(loaderEntered.await(2, TimeUnit.SECONDS)).isTrue();
        AgentRunEventResponse concurrent = event(3, "model.completed");
        persisted.add(concurrent);
        latest.set(3);
        hub.publish("tenant", "workspace", concurrent, false);
        releaseLoader.countDown();
        opener.join(2000);

        assertThat(emitter.sequences).containsExactly(2L, 3L);
    }

    @Test
    void heartbeatCatchesUpTerminalEventWrittenByAnotherInstance() throws Exception {
        CapturingEmitter emitter = new CapturingEmitter();
        SseRunEventHub firstInstance = new SseRunEventHub(() -> emitter, 25);
        List<AgentRunEventResponse> persisted = new CopyOnWriteArrayList<>(
                Arrays.asList(event(1, "run.started"), event(2, "node.completed"),
                        event(3, "model.completed")));
        AtomicLong latest = new AtomicLong(3);
        firstInstance.open("tenant", "workspace", "run", 3, latest::get,
                after -> after(persisted, after), false);

        persisted.add(event(4, "run.completed"));
        latest.set(4);

        assertThat(emitter.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter.sequences).containsExactly(4L);
    }

    private static List<AgentRunEventResponse> after(List<AgentRunEventResponse> events, long sequence) {
        return events.stream().filter(value -> value.getSequence() > sequence)
                .collect(Collectors.toList());
    }

    private static AgentRunEventResponse event(long sequence, String type) {
        return new AgentRunEventResponse(new RunEvent(sequence, "run", type, "{}", Instant.now()), null);
    }

    private static final class CapturingEmitter extends SseEmitter {
        private final List<Long> sequences = new ArrayList<>();
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public synchronized void send(SseEventBuilder builder) {
            for (ResponseBodyEmitter.DataWithMediaType value : builder.build()) {
                if (value.getData() instanceof AgentRunEventResponse) {
                    sequences.add(((AgentRunEventResponse) value.getData()).getSequence());
                }
            }
        }

        @Override
        public synchronized void complete() {
            completed.countDown();
        }

        @Override
        public synchronized void completeWithError(Throwable error) {
            completed.countDown();
        }
    }
}
