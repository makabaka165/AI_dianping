package com.hmdp.ai.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.application.dto.agent.AttachmentRequest;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.AttachmentReference;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentContextAssembler {
    private final ObjectMapper objectMapper;
    private final ExecutionBudgetFactory budgets;

    public AgentContextAssembler(ObjectMapper objectMapper, ExecutionBudgetFactory budgets) {
        this.objectMapper = objectMapper;
        this.budgets = budgets;
    }

    public ExecutionContext assemble(AgentRunRecord run, PublishedAgentDefinition definition,
                                     AgentInputRequest input) {
        try {
            ExecutionBudget budget = budgets.fromStoredJson(run.getBudgetJson());
            AuthorizationContext authorization = authorization(run.getAuthorizationJson());
            JsonNode metadata = objectMapper.readTree(run.getMetadataJson());
            List<AttachmentReference> attachments = new ArrayList<>();
            for (AttachmentRequest attachment : input.getAttachments()) {
                attachments.add(new AttachmentReference(attachment.getAttachmentId(), attachment.getName(),
                        attachment.getContentType(), attachment.getSizeBytes(), attachment.getUri()));
            }
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("text", input.getText());
            variables.put("input", objectMapper.convertValue(input, Map.class));
            variables.put("metadata", objectMapper.convertValue(metadata, Map.class));
            String locale = metadata.path("locale").asText("zh-CN");
            String timezone = metadata.path("timezone").asText("Asia/Shanghai");
            return new ExecutionContext(run.getTenantId(), run.getWorkspaceId(), run.getUserId(),
                    run.getSessionId(), run.getConversationId(), run.getId(), definition.getAgent().getId(),
                    definition.getVersion().getVersion(), locale, timezone, attachments, input.getReferenceUris(),
                    authorization, budget, run.getDeadlineAt(), variables, run.getTraceId());
        } catch (Exception e) {
            throw new IllegalStateException("stored execution context is invalid", e);
        }
    }

    private AuthorizationContext authorization(String json) throws Exception {
        EnumSet<AiPermission> permissions = EnumSet.noneOf(AiPermission.class);
        for (JsonNode value : objectMapper.readTree(json).path("permissions")) {
            permissions.add(AiPermission.valueOf(value.asText()));
        }
        return new AuthorizationContext(permissions);
    }
}
