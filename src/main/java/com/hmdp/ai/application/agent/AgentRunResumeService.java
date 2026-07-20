package com.hmdp.ai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.agent.AgentRunCreatedResponse;
import com.hmdp.ai.application.dto.agent.ResumeAgentRunRequest;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.application.agent.AgentRuntime;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.domain.workflow.WorkflowStateRepository;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunResumeService {
    private final RunRepository repository;
    private final AgentRuntime runtime;
    private final ContentHashService hashes;
    private final ObjectMapper objectMapper;
    private final WorkflowStateRepository workflowStates;

    public AgentRunResumeService(RunRepository repository, AgentRuntime runtime,
                                 ContentHashService hashes, ObjectMapper objectMapper,
                                 WorkflowStateRepository workflowStates) {
        this.repository = repository;
        this.runtime = runtime;
        this.hashes = hashes;
        this.objectMapper = objectMapper;
        this.workflowStates = workflowStates;
    }

    @Transactional
    public AgentRunCreatedResponse resume(AiSecurityContext context, AgentRunRecord run,
                                          ResumeAgentRunRequest request) {
        if (run.getStatus() != RunStatus.WAITING_FOR_USER && run.getStatus() != RunStatus.WAITING_FOR_APPROVAL) {
            throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT, "run is not waiting for a resume action");
        }
        try {
            String data = objectMapper.writeValueAsString(request.getVariables());
            String tokenHash = hashes.sha256(request.getResumeToken());
            java.util.Map<String, Object> variables = objectMapper.convertValue(request.getVariables(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
            boolean workflowResumed = workflowStates.resume(run.getTenantId(), run.getWorkspaceId(), run.getId(),
                    tokenHash, variables, context.getUserId());
            boolean resumed = workflowResumed && repository.resumeWaiting(run.getTenantId(), run.getWorkspaceId(),
                    run.getId(), tokenHash, data, context.getUserId());
            if (!resumed) {
                throw new AiPlatformException(ErrorCode.AI_VERSION_CONFLICT,
                        "resume token is invalid or expired");
            }
            runtime.enqueue(run.getTenantId(), run.getWorkspaceId(), run.getId());
            return new AgentRunCreatedResponse(run.getId(), RunStatus.QUEUED,
                    run.getAgentId(), agentCode(run), run.getAgentVersion());
        } catch (AiPlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("resume variables are invalid", e);
        }
    }

    private String agentCode(AgentRunRecord run) {
        try { return objectMapper.readTree(run.getVersionSnapshotJson()).path("agentCode").asText(run.getAgentId()); }
        catch (Exception ignored) { return run.getAgentId(); }
    }
}
