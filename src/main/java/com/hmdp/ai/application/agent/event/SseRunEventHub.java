package com.hmdp.ai.application.agent.event;

import com.hmdp.ai.application.dto.agent.AgentRunEventResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Component
public class SseRunEventHub {
    private static final long HEARTBEAT_MILLIS = Duration.ofSeconds(15).toMillis();
    private static final int MAX_REPLAY_PASSES = 8;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SseEmitter, SubscriptionState> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SseEmitter, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ai-run-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final Supplier<SseEmitter> emitterFactory;
    private final long heartbeatMillis;

    public SseRunEventHub() {
        this(() -> new SseEmitter(Duration.ofMinutes(30).toMillis()), HEARTBEAT_MILLIS);
    }

    SseRunEventHub(Supplier<SseEmitter> emitterFactory) {
        this(emitterFactory, HEARTBEAT_MILLIS);
    }

    SseRunEventHub(Supplier<SseEmitter> emitterFactory, long heartbeatMillis) {
        this.emitterFactory = emitterFactory;
        this.heartbeatMillis = Math.max(10, heartbeatMillis);
    }

    public SseEmitter open(String tenantId, String workspaceId, String runId, long afterSequence,
                           List<AgentRunEventResponse> replay, boolean terminal) {
        long replayMaximum = replay.stream().mapToLong(AgentRunEventResponse::getSequence)
                .max().orElse(afterSequence);
        return open(tenantId, workspaceId, runId, afterSequence, () -> replayMaximum,
                ignored -> replay, terminal);
    }

    public SseEmitter open(String tenantId, String workspaceId, String runId, long afterSequence,
                           LongSupplier latestSequence,
                           LongFunction<List<AgentRunEventResponse>> replayLoader,
                           boolean terminal) {
        String key = key(tenantId, workspaceId, runId);
        SseEmitter emitter = emitterFactory.get();
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> remove(key, emitter));
        emitter.onError(error -> remove(key, emitter));

        subscriptions.put(emitter, new SubscriptionState(Math.max(0, afterSequence),
                latestSequence, replayLoader));
        emitters.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        try {
            boolean terminalPublishedDuringReplay = replayToStableBoundary(emitter, latestSequence, replayLoader);
            if (terminal || terminalPublishedDuringReplay) {
                emitter.complete();
                remove(key, emitter);
            } else {
                heartbeat.set(heartbeatExecutor.scheduleAtFixedRate(() -> heartbeat(key, emitter),
                        heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS));
                heartbeats.put(emitter, heartbeat.get());
            }
        } catch (IOException | RuntimeException e) {
            emitter.completeWithError(e);
            remove(key, emitter);
        }
        return emitter;
    }

    public void publish(String tenantId, String workspaceId, AgentRunEventResponse event, boolean terminal) {
        String key = key(tenantId, workspaceId, event.getRunId());
        CopyOnWriteArrayList<SseEmitter> clients = emitters.get(key);
        if (clients == null) return;
        for (SseEmitter emitter : clients) {
            try {
                boolean completeNow = sendLive(emitter, event, terminal);
                if (completeNow) {
                    emitter.complete();
                    remove(key, emitter);
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
                remove(key, emitter);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : emitters.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // Shutdown must still release the scheduler and remaining subscriptions.
                }
                remove(entry.getKey(), emitter);
            }
        }
        heartbeatExecutor.shutdownNow();
    }

    private boolean replayToStableBoundary(SseEmitter emitter, LongSupplier latestSequence,
                                           LongFunction<List<AgentRunEventResponse>> replayLoader)
            throws IOException {
        for (int pass = 0; pass < MAX_REPLAY_PASSES; pass++) {
            long boundary = latestSequence.getAsLong();
            long after = state(emitter).lastSequence;
            if (boundary <= after) return finishReplay(emitter);
            List<AgentRunEventResponse> events = replayLoader.apply(after);
            for (AgentRunEventResponse event : events) sendReplay(emitter, event);
            long replayed = state(emitter).lastSequence;
            if (replayed >= boundary) {
                long current = latestSequence.getAsLong();
                if (current <= replayed) return finishReplay(emitter);
            }
            if (events.isEmpty()) return finishReplay(emitter);
        }
        return finishReplay(emitter);
    }

    private void sendReplay(SseEmitter emitter, AgentRunEventResponse event) throws IOException {
        SubscriptionState state = state(emitter);
        synchronized (state) {
            if (event.getSequence() <= state.lastSequence) return;
            transmit(emitter, event);
            state.lastSequence = event.getSequence();
            state.terminalPending = state.terminalPending || terminalEvent(event.getType());
        }
    }

    private boolean sendLive(SseEmitter emitter, AgentRunEventResponse event, boolean terminal)
            throws IOException {
        SubscriptionState state = state(emitter);
        synchronized (state) {
            if (event.getSequence() <= state.lastSequence) {
                state.terminalPending = state.terminalPending || terminal;
                return terminal && !state.replaying;
            }
            if (state.replaying) {
                state.pending.put(event.getSequence(), event);
                state.terminalPending = state.terminalPending || terminal;
                return false;
            }
            transmit(emitter, event);
            state.lastSequence = event.getSequence();
            return terminal;
        }
    }

    private boolean finishReplay(SseEmitter emitter) throws IOException {
        SubscriptionState state = state(emitter);
        synchronized (state) {
            for (Map.Entry<Long, AgentRunEventResponse> pending : state.pending.entrySet()) {
                if (pending.getKey() <= state.lastSequence) continue;
                transmit(emitter, pending.getValue());
                state.lastSequence = pending.getKey();
            }
            state.pending.clear();
            state.replaying = false;
            return state.terminalPending;
        }
    }

    private void transmit(SseEmitter emitter, AgentRunEventResponse event) throws IOException {
        emitter.send(SseEmitter.event().id(String.valueOf(event.getSequence()))
                .name(event.getType()).data(event));
    }

    private void heartbeat(String key, SseEmitter emitter) {
        try {
            SubscriptionState state = state(emitter);
            synchronized (state) {
                state.replaying = true;
            }
            if (replayToStableBoundary(emitter, state.latestSequence, state.replayLoader)) {
                emitter.complete();
                remove(key, emitter);
                return;
            }
            synchronized (state(emitter)) {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            }
        } catch (IOException | RuntimeException e) {
            emitter.completeWithError(e);
            remove(key, emitter);
        }
    }

    private void remove(String key, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> clients = emitters.get(key);
        if (clients != null) {
            clients.remove(emitter);
            if (clients.isEmpty()) emitters.remove(key, clients);
        }
        subscriptions.remove(emitter);
        ScheduledFuture<?> heartbeat = heartbeats.remove(emitter);
        if (heartbeat != null) heartbeat.cancel(false);
    }

    private String key(String tenantId, String workspaceId, String runId) {
        return tenantId + '\u001f' + workspaceId + '\u001f' + runId;
    }

    private boolean terminalEvent(String type) {
        return "run.completed".equals(type) || "run.failed".equals(type)
                || "run.cancelled".equals(type);
    }

    private SubscriptionState state(SseEmitter emitter) {
        SubscriptionState state = subscriptions.get(emitter);
        if (state == null) throw new IllegalStateException("SSE_SUBSCRIPTION_CLOSED");
        return state;
    }

    private static final class SubscriptionState {
        private final TreeMap<Long, AgentRunEventResponse> pending = new TreeMap<>();
        private final LongSupplier latestSequence;
        private final LongFunction<List<AgentRunEventResponse>> replayLoader;
        private long lastSequence;
        private boolean replaying = true;
        private boolean terminalPending;

        private SubscriptionState(long lastSequence, LongSupplier latestSequence,
                                  LongFunction<List<AgentRunEventResponse>> replayLoader) {
            this.lastSequence = lastSequence;
            this.latestSequence = latestSequence;
            this.replayLoader = replayLoader;
        }
    }
}
