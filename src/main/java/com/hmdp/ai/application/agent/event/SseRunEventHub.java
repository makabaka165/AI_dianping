package com.hmdp.ai.application.agent.event;

import com.hmdp.ai.application.dto.agent.AgentRunEventResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SseRunEventHub {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter open(String tenantId, String workspaceId, String runId,
                           List<AgentRunEventResponse> replay, boolean terminal) {
        String key = key(tenantId, workspaceId, runId);
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> remove(key, emitter));
        emitter.onError(error -> remove(key, emitter));
        try {
            for (AgentRunEventResponse event : replay) send(emitter, event);
            if (terminal) {
                emitter.complete();
            } else {
                emitters.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    public void publish(String tenantId, String workspaceId, AgentRunEventResponse event, boolean terminal) {
        String key = key(tenantId, workspaceId, event.getRunId());
        CopyOnWriteArrayList<SseEmitter> clients = emitters.get(key);
        if (clients == null) return;
        for (SseEmitter emitter : clients) {
            try {
                send(emitter, event);
                if (terminal) emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
                remove(key, emitter);
            }
        }
        if (terminal) emitters.remove(key);
    }

    private void send(SseEmitter emitter, AgentRunEventResponse event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(String.valueOf(event.getSequence()))
                .name(event.getType())
                .data(event));
    }

    private void remove(String key, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> clients = emitters.get(key);
        if (clients == null) return;
        clients.remove(emitter);
        if (clients.isEmpty()) emitters.remove(key, clients);
    }

    private String key(String tenantId, String workspaceId, String runId) {
        return tenantId + '\u001f' + workspaceId + '\u001f' + runId;
    }
}
