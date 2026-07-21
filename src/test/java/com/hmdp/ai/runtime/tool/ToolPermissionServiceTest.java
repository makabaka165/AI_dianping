package com.hmdp.ai.runtime.tool;

import com.hmdp.ai.application.security.AiAuthorizationService;
import com.hmdp.ai.domain.run.ExecutionBudget;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolProtocol;
import com.hmdp.ai.domain.tool.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolPermissionServiceTest {
    @Test
    void enforcesRequiredPermissionAndCriticalAdmin() {
        ToolPermissionService service = new ToolPermissionService();
        ExecutionContext run = context(EnumSet.of(AiPermission.AGENT_RUN));
        assertTrue(service.allowed(run, tool(ToolRiskLevel.LOW)));
        assertFalse(service.allowed(run, tool(ToolRiskLevel.CRITICAL)));
        assertTrue(service.allowed(context(EnumSet.of(AiPermission.ADMIN)), tool(ToolRiskLevel.CRITICAL)));
    }

    @Test
    void rechecksCurrentAuthorizationForEveryToolInvocation() {
        AiAuthorizationService authorization = mock(AiAuthorizationService.class);
        when(authorization.authorize("u", "t", "w"))
                .thenReturn(new AuthorizationContext(Collections.emptySet()));
        ToolPermissionService service = new ToolPermissionService(authorization);

        assertFalse(service.allowed(context(EnumSet.of(AiPermission.AGENT_RUN)), tool(ToolRiskLevel.LOW)));
        verify(authorization).authorize(eq("u"), eq("t"), eq("w"));
    }

    private ToolDefinition tool(ToolRiskLevel risk) {
        return new ToolDefinition("t", "v", "code", 1, "name", ToolProtocol.LOCAL_SKILL,
                "{}", "{}", risk, false, true, 1000,
                Collections.singletonList(AiPermission.AGENT_RUN), "{}", true);
    }

    private ExecutionContext context(java.util.Set<AiPermission> permissions) {
        return new ExecutionContext("t", "w", "u", "s", null, "r", "a", 1, "zh-CN", "UTC",
                Collections.emptyList(), Collections.emptyList(), new AuthorizationContext(permissions),
                ExecutionBudget.defaults(), Instant.now().plusSeconds(30), Collections.emptyMap(), "trace");
    }
}
