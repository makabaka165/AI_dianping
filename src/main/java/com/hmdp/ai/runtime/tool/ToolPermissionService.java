package com.hmdp.ai.runtime.tool;
import com.hmdp.ai.domain.run.ExecutionContext;import com.hmdp.ai.domain.tool.ToolDefinition;import com.hmdp.ai.domain.security.AiPermission;import org.springframework.stereotype.Component;
@Component public class ToolPermissionService {public boolean allowed(ExecutionContext c,ToolDefinition d){if(!d.isEnabled())return false;for(AiPermission p:d.getRequiredPermissions())if(!c.getAuthorizationContext().has(p))return false;return d.getRiskLevel()!=com.hmdp.ai.domain.tool.ToolRiskLevel.CRITICAL||c.getAuthorizationContext().has(AiPermission.ADMIN);}}
