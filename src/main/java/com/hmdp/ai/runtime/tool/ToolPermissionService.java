package com.hmdp.ai.runtime.tool;

import com.hmdp.ai.application.security.AiAuthorizationService;
import com.hmdp.ai.domain.run.ExecutionContext;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.domain.tool.ToolDefinition;
import com.hmdp.ai.domain.tool.ToolRiskLevel;
import org.springframework.stereotype.Component;

@Component
public class ToolPermissionService {
    private final AiAuthorizationService authorization;

    @org.springframework.beans.factory.annotation.Autowired
    public ToolPermissionService(AiAuthorizationService authorization) {
        this.authorization = authorization;
    }

    public ToolPermissionService() {
        this.authorization = null;
    }

    public boolean allowed(ExecutionContext context, ToolDefinition definition) {
        if (!definition.isEnabled()) {
            return false;
        }
        AuthorizationContext current = authorization == null
                ? context.getAuthorizationContext()
                : authorization.authorize(context.getUserId(), context.getTenantId(), context.getWorkspaceId());
        for (AiPermission permission : definition.getRequiredPermissions()) {
            if (!current.has(permission)) {
                return false;
            }
        }
        return definition.getRiskLevel() != ToolRiskLevel.CRITICAL || current.has(AiPermission.ADMIN);
    }
}
