package com.hmdp.ai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.agent.AgentInputRequest;
import com.hmdp.ai.application.dto.agent.AgentResponseMode;
import com.hmdp.ai.application.dto.agent.AgentRunCreatedResponse;
import com.hmdp.ai.application.dto.agent.AgentRunRequest;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.agent.AgentDefinition;
import com.hmdp.ai.domain.agent.AgentVersion;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelType;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.run.VersionSnapshot;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.security.TenantContext;
import com.hmdp.ai.domain.security.WorkspaceContext;
import com.hmdp.ai.application.agent.AgentDefinitionLoader;
import com.hmdp.ai.application.agent.AgentRuntime;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.application.agent.event.SseRunEventHub;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunApplicationServiceTest {

    @Test
    void persistsTenantScopedAuthorizationAndCompleteVersionSnapshotBeforeEnqueue() {
        ObjectMapper mapper = new ObjectMapper();
        RunRepository repository = mock(RunRepository.class);
        AgentDefinitionLoader loader = mock(AgentDefinitionLoader.class);
        AgentRunRequestValidator validator = mock(AgentRunRequestValidator.class);
        AgentRunAccessPolicy accessPolicy = mock(AgentRunAccessPolicy.class);
        AgentRunCancellationService cancellation = mock(AgentRunCancellationService.class);
        AgentRunRetryService retry = mock(AgentRunRetryService.class);
        AgentRunResumeService resume = mock(AgentRunResumeService.class);
        AgentRuntime runtime = mock(AgentRuntime.class);
        AiAccessGuard accessGuard = mock(AiAccessGuard.class);
        AiIdGenerator ids = mock(AiIdGenerator.class);
        ContentHashService hashes = mock(ContentHashService.class);
        ExecutionBudgetFactory budgets = new ExecutionBudgetFactory(mapper);
        AiSecurityContext security = new AiSecurityContext("user-1", new TenantContext("tenant-1"),
                new WorkspaceContext("workspace-1"),
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN, AiPermission.KNOWLEDGE_READ)), false);
        PublishedAgentDefinition definition = definition();
        when(accessGuard.require(AiPermission.AGENT_RUN)).thenReturn(security);
        when(loader.load("tenant-1", "workspace-1", "shop-consultant", 1)).thenReturn(definition);
        when(ids.nextId()).thenReturn("run-1", "trace-1");
        when(hashes.sha256(any(String.class)))
                .thenReturn("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        when(repository.create(any(AgentRunRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AgentRunApplicationService service = new AgentRunApplicationService(repository, loader, validator,
                accessPolicy, cancellation, retry, resume, runtime, mock(SseRunEventHub.class), accessGuard,
                ids, hashes, budgets, mapper);

        AgentRunCreatedResponse response = service.create(request());

        ArgumentCaptor<AgentRunRecord> captor = ArgumentCaptor.forClass(AgentRunRecord.class);
        verify(repository).create(captor.capture());
        AgentRunRecord stored = captor.getValue();
        assertEquals("tenant-1", stored.getTenantId());
        assertEquals("workspace-1", stored.getWorkspaceId());
        assertTrue(stored.getAuthorizationJson().contains("AGENT_RUN"));
        assertTrue(stored.getVersionSnapshotJson().contains("prompt"));
        assertTrue(stored.getVersionSnapshotJson().contains("index-v1"));
        assertEquals(RunStatus.QUEUED, response.getStatus());
        verify(runtime).enqueue("tenant-1", "workspace-1", "run-1");
    }

    private AgentRunRequest request() {
        AgentInputRequest input = new AgentInputRequest();
        input.setText("summarize shop 1");
        AgentRunRequest request = new AgentRunRequest();
        request.setAgentId("shop-consultant");
        request.setAgentVersion(1);
        request.setSessionId("session-1");
        request.setInput(input);
        request.setResponseMode(AgentResponseMode.STREAM);
        return request;
    }

    private PublishedAgentDefinition definition() {
        AgentDefinition agent = new AgentDefinition("agent", "tenant-1", "workspace-1", "shop-consultant",
                "Shop", "description", 1, "ACTIVE", null, null);
        AgentVersion version = new AgentVersion("agent-v1", "tenant-1", "workspace-1", "agent", 1,
                "Shop", "description", "model", "prompt-v1", "workflow-v1", "{}",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                "{\"maxWorkflowNodes\":64,\"maxLoopIterations\":5,\"maxParallelism\":4," +
                        "\"maxModelCalls\":8,\"maxToolCalls\":16,\"maxRunDurationSeconds\":120}",
                "{}", VersionStatus.PUBLISHED, "hash", "change", null, null, null);
        ModelProfile model = new ModelProfile("model", "tenant-1", "workspace-1", "model", "Model", "provider",
                "name", "https://example.com/v1", "env:AI_CHAT_API_KEY", ModelType.CHAT,
                "{\"streaming\":true,\"toolCalling\":true,\"jsonSchema\":true,\"vision\":false,\"longContext\":true}",
                "{}", 32000, 1000, 30000, "{}", null, BigDecimal.ZERO, BigDecimal.ZERO,
                true, 1, "ACTIVE", null, null);
        PromptVersion prompt = new PromptVersion("prompt-v1", "tenant-1", "workspace-1", "prompt", 1,
                "system", "task", null, null, null, "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", "[]",
                VersionStatus.PUBLISHED, "hash", "change", null, null, null);
        VersionSnapshot snapshot = new VersionSnapshot("agent", 1, "prompt", 1, "workflow", 1,
                "model", 1, Collections.emptyMap(), Collections.singletonMap("kb", 1),
                Collections.singletonMap("kb", "index-v1"));
        return new PublishedAgentDefinition(agent, version, model, prompt, "workflow", 1,
                "PUBLISHED", Collections.emptyList(), Collections.emptyList(), snapshot);
    }
}
