package com.hmdp.ai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.agent.AgentRunSummaryResponse;
import com.hmdp.ai.application.agent.event.SseRunEventHub;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.run.AgentRunRecord;
import com.hmdp.ai.domain.run.ExecutionBudgetFactory;
import com.hmdp.ai.domain.run.RunRepository;
import com.hmdp.ai.domain.run.RunStatus;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.security.TenantContext;
import com.hmdp.ai.domain.security.WorkspaceContext;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunListHistoryTest {

    @Test
    void ordinaryRunnerOnlySeesOwnRuns() {
        RunRepository repository = mock(RunRepository.class);
        AiAccessGuard accessGuard = mock(AiAccessGuard.class);
        AiSecurityContext security = new AiSecurityContext("user-1", new TenantContext("tenant-1"),
                new WorkspaceContext("workspace-1"),
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)), false);
        when(accessGuard.require(AiPermission.AGENT_RUN)).thenReturn(security);
        AgentRunRecord run = sampleRun("user-1");
        when(repository.findPage(eq("tenant-1"), eq("workspace-1"), eq("user-1"), isNull(), isNull(),
                isNull(), isNull(), eq(0), eq(20))).thenReturn(Collections.singletonList(run));
        when(repository.countPage(eq("tenant-1"), eq("workspace-1"), eq("user-1"), isNull(), isNull(),
                isNull(), isNull())).thenReturn(1L);

        AgentRunApplicationService service = newService(repository, accessGuard);
        PageResponse<AgentRunSummaryResponse> page = service.list(1, 20, null, null, null, null);

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getItems().get(0).getRunId()).isEqualTo("run-1");
    }

    @Test
    void inspectorCanListAllRunsInScope() {
        RunRepository repository = mock(RunRepository.class);
        AiAccessGuard accessGuard = mock(AiAccessGuard.class);
        AiSecurityContext security = new AiSecurityContext("inspector", new TenantContext("tenant-1"),
                new WorkspaceContext("workspace-1"),
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN, AiPermission.RUN_INSPECT)), false);
        when(accessGuard.require(AiPermission.AGENT_RUN)).thenReturn(security);
        when(repository.findPage(eq("tenant-1"), eq("workspace-1"), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(0), eq(20))).thenReturn(Collections.singletonList(sampleRun("user-9")));
        when(repository.countPage(eq("tenant-1"), eq("workspace-1"), isNull(), isNull(), isNull(),
                isNull(), isNull())).thenReturn(1L);

        AgentRunApplicationService service = newService(repository, accessGuard);
        PageResponse<AgentRunSummaryResponse> page = service.list(1, 20, null, null, null, null);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getAgentId()).isEqualTo("agent-1");
    }

    private AgentRunApplicationService newService(RunRepository repository, AiAccessGuard accessGuard) {
        ObjectMapper mapper = new ObjectMapper();
        return new AgentRunApplicationService(repository, mock(AgentDefinitionLoader.class),
                mock(AgentRunRequestValidator.class), mock(AgentRunAccessPolicy.class),
                mock(AgentRunCancellationService.class), mock(AgentRunRetryService.class),
                mock(AgentRunResumeService.class), mock(AgentRuntime.class), mock(SseRunEventHub.class),
                accessGuard, mock(AiIdGenerator.class), mock(ContentHashService.class),
                new ExecutionBudgetFactory(mapper), mapper);
    }

    private AgentRunRecord sampleRun(String userId) {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        return new AgentRunRecord("run-1", "tenant-1", "workspace-1", userId, "session-1", "conv-1",
                "agent-1", 1, RunStatus.COMPLETED, "STREAM", "{}", "{}", "{}", "{}", "{}", "{}",
                "trace-1", null, 1, null, null, now, now, now, now.plusSeconds(60), now);
    }
}
