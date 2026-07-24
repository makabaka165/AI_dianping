package com.hmdp.ai.application.agent.event;

import com.hmdp.ai.application.dto.agent.AgentRunEventResponse;
import com.hmdp.ai.domain.run.RunEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseRunEventHubTest {
    @Test
    void eventPublishedDuringReplayIsDeliveredExactlyOnce() throws Exception {
        CapturingEmitter emitter = new CapturingEmitter();
        SseRunEventHub hub = new SseRunEventHub(() -> emitter);
        AtomicLong latest = new AtomicLong(1);
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);

        Thread opener = new Thread(() -> hub.open("tenant", "workspace", "run", 0,
                latest::get, after -> {
                    loaderEntered.countDown();
                    try {
                        releaseLoader.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return after == 0 ? Arrays.asList(event(1), event(2)) : Arrays.asList(event(2));
                }, false));
        opener.start();
        assertTrue(loaderEntered.await(2, TimeUnit.SECONDS));
        latest.set(2);
        hub.publish("tenant", "workspace", event(2), false);
        releaseLoader.countDown();
        opener.join(2000);

        assertEquals(Arrays.asList(1L, 2L), emitter.sequences);
    }

    @Test
    void duplicateTerminalPublicationStillClosesSubscription() {
        CapturingEmitter emitter = new CapturingEmitter();
        SseRunEventHub hub = new SseRunEventHub(() -> emitter);
        hub.open("tenant", "workspace", "run", 0, () -> 1,
                ignored -> Arrays.asList(event(1)), false);

        hub.publish("tenant", "workspace", event(1), true);

        assertTrue(emitter.completed);
        assertEquals(Arrays.asList(1L), emitter.sequences);
    }

    @Test
    void shutdownShouldCompleteOpenEmittersAndStopHeartbeatResources() {
        CapturingEmitter emitter = new CapturingEmitter();
        SseRunEventHub hub = new SseRunEventHub(() -> emitter, 10_000);
        hub.open("tenant", "workspace", "run", 0, () -> 0,
                ignored -> List.of(), false);

        hub.shutdown();

        assertTrue(emitter.completed);
    }

    private AgentRunEventResponse event(long sequence) {
        return new AgentRunEventResponse(new RunEvent(sequence, "run", "node.completed", "{}",
                Instant.now()), null);
    }

    private static final class CapturingEmitter extends SseEmitter {
        private final List<Long> sequences = new ArrayList<>();
        private boolean completed;

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
            completed = true;
        }
    }
}
