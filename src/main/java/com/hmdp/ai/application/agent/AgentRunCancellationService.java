package com.hmdp.ai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.agent.AgentRunCreatedResponse;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.application.agent.event.RunEventPublisher;
import com.hmdp.ai.domain.run.RunLifecycleEventPayload;
import com.hmdp.ai.domain.run.RunCancellationPort;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class AgentRunCancellationService {
    private final RunRepository repository;
    private final RunEventPublisher events;
    private final ObjectMapper mapper;
    private final RunCancellationPort cancellations;

    public AgentRunCancellationService(RunRepository repository, RunEventPublisher events) {
        this(repository, events, new ObjectMapper(), null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunCancellationService(RunRepository repository, RunEventPublisher events, ObjectMapper mapper,
                                       RunCancellationPort cancellations) {
        this.repository = repository;
        this.events = events;
        this.mapper = mapper;
        this.cancellations = cancellations;
    }

    public AgentRunCreatedResponse cancel(AiSecurityContext context, AgentRunRecord run) {
        if (!repository.cancel(run.getTenantId(), run.getWorkspaceId(), run.getId(), context.getUserId())) {
            throw new AiPlatformException(ErrorCode.AI_RUN_NOT_CANCELLABLE,
                    "run is already terminal or cannot be cancelled");
        }
        if (cancellations != null) cancellations.cancel(run.getId());
        events.publish(run.getTenantId(), run.getWorkspaceId(), run.getId(), "run.cancelled",
                new RunLifecycleEventPayload(run.getId(), RunStatus.CANCELLED, null,
                        "RUN_CANCELLED", "run cancelled"), true);
        return new AgentRunCreatedResponse(run.getId(), RunStatus.CANCELLED,
                run.getAgentId(), agentCode(run), run.getAgentVersion());
    }

    private String agentCode(AgentRunRecord run) {
        try { JsonNode snapshot = mapper.readTree(run.getVersionSnapshotJson()); return snapshot.path("agentCode").asText(run.getAgentId()); }
        catch (Exception ignored) { return run.getAgentId(); }
    }
}
