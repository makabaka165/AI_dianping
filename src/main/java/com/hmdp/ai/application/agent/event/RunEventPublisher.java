package com.hmdp.ai.application.agent.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.agent.AgentRunEventResponse;
import com.hmdp.ai.domain.run.RunEvent;
import com.hmdp.ai.domain.run.RunRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class RunEventPublisher {
    private final RunRepository repository;
    private final ObjectMapper objectMapper;
    private final SseRunEventHub eventHub;

    public RunEventPublisher(RunRepository repository, ObjectMapper objectMapper, SseRunEventHub eventHub) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.eventHub = eventHub;
    }

    public AgentRunEventResponse publish(String tenantId, String workspaceId, String runId,
                                         String type, Object payload, boolean terminal) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            long sequence = repository.appendEvent(tenantId, workspaceId, runId, type, payloadJson);
            JsonNode payloadNode = objectMapper.readTree(payloadJson);
            AgentRunEventResponse response = new AgentRunEventResponse(
                    new RunEvent(sequence, runId, type, payloadJson, Instant.now()), payloadNode);
            eventHub.publish(tenantId, workspaceId, response, terminal);
            return response;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("run event cannot be serialized", e);
        }
    }
}
