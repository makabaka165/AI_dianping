package com.hmdp.ai.application.agent;

import com.hmdp.ai.application.dto.agent.AgentRunCreatedResponse;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.application.agent.event.RunEventPublisher;
import com.hmdp.ai.domain.run.RunLifecycleEventPayload;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class AgentRunCancellationService {
    private final RunRepository repository;
    private final RunEventPublisher events;

    public AgentRunCancellationService(RunRepository repository, RunEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    public AgentRunCreatedResponse cancel(AiSecurityContext context, AgentRunRecord run) {
        if (!repository.cancel(run.getTenantId(), run.getWorkspaceId(), run.getId(), context.getUserId())) {
            throw new AiPlatformException(ErrorCode.AI_RUN_NOT_CANCELLABLE,
                    "run is already terminal or cannot be cancelled");
        }
        events.publish(run.getTenantId(), run.getWorkspaceId(), run.getId(), "run.failed",
                new RunLifecycleEventPayload(run.getId(), RunStatus.CANCELLED, null,
                        "RUN_CANCELLED", "run cancelled"), true);
        return new AgentRunCreatedResponse(run.getId(), RunStatus.CANCELLED,
                run.getAgentId(), run.getAgentVersion());
    }
}
