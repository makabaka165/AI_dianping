package com.hmdp.ai.application.agent;

import com.hmdp.ai.application.dto.PageResponse;
import com.hmdp.ai.application.dto.agent.RunnableAgentResponse;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.agent.AgentDefinition;
import com.hmdp.ai.domain.agent.AgentDependencyInspector;
import com.hmdp.ai.domain.agent.AgentPublishValidator;
import com.hmdp.ai.domain.agent.AgentRepository;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.security.TenantContext;
import com.hmdp.ai.domain.security.WorkspaceContext;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.ai.shared.json.ContentHashService;
import com.hmdp.ai.shared.json.VersionDiffService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentDefinitionListRunnableTest {

    @Test
    void listsPublishedAgentsForRunPermission() {
        AgentRepository repository = mock(AgentRepository.class);
        AiAccessGuard accessGuard = mock(AiAccessGuard.class);
        AiSecurityContext security = new AiSecurityContext("user-1", new TenantContext("tenant-1"),
                new WorkspaceContext("workspace-1"),
                new AuthorizationContext(EnumSet.of(AiPermission.AGENT_RUN)), false);
        when(accessGuard.require(AiPermission.AGENT_RUN)).thenReturn(security);
        AgentDefinition agent = new AgentDefinition("agent-1", "tenant-1", "workspace-1", "shop-consultant",
                "Shop Consultant", "desc", 2, "ACTIVE", Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z"));
        when(repository.findRunnablePage("tenant-1", "workspace-1", 0, 20))
                .thenReturn(Collections.singletonList(agent));
        when(repository.findPublishedVersion("tenant-1", "workspace-1", "agent-1"))
                .thenReturn(Optional.of(2));
        when(repository.countRunnable("tenant-1", "workspace-1")).thenReturn(1L);

        AgentDefinitionApplicationService service = new AgentDefinitionApplicationService(
                repository, mock(AgentDependencyInspector.class), mock(AgentPublishValidator.class),
                accessGuard, mock(AiIdGenerator.class), mock(ContentHashService.class),
                mock(VersionDiffService.class));

        PageResponse<RunnableAgentResponse> page = service.listRunnable(1, 20);

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getCode()).isEqualTo("shop-consultant");
        assertThat(page.getItems().get(0).getPublishedVersion()).isEqualTo(2);
    }
}
