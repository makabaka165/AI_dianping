package com.hmdp.ai.application.security;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

@Service
public class AiAuthorizationService {
    private final JdbcTemplate jdbcTemplate;
    private final StpInterface permissionProvider;

    public AiAuthorizationService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiAuthorizationService(JdbcTemplate jdbcTemplate, StpInterface permissionProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.permissionProvider = permissionProvider;
    }

    public AuthorizationContext authorize(String userId, String tenantId, String workspaceId) {
        if (!isMember(userId, tenantId, workspaceId)) return new AuthorizationContext(EnumSet.noneOf(AiPermission.class));
        EnumSet<AiPermission> permissions = EnumSet.noneOf(AiPermission.class);
        addGrantedPermissions(userId, permissions);
        String role = membershipRole(userId, tenantId, workspaceId);
        if ("OWNER".equals(role) || "ADMIN".equals(role)) permissions.add(AiPermission.ADMIN);
        if ("EDITOR".equals(role)) {
            permissions.add(AiPermission.AGENT_RUN);
            permissions.add(AiPermission.KNOWLEDGE_READ);
            permissions.add(AiPermission.KNOWLEDGE_WRITE);
        }
        if ("RUNNER".equals(role)) permissions.add(AiPermission.AGENT_RUN);
        if ("VIEWER".equals(role)) permissions.add(AiPermission.KNOWLEDGE_READ);
        return new AuthorizationContext(permissions);
    }

    public boolean hasPermission(AuthorizationContext context, AiPermission permission) {
        return context != null && context.has(permission);
    }

    private void addGrantedPermissions(String userId, EnumSet<AiPermission> permissions) {
        List<String> granted;
        if (permissionProvider != null) {
            granted = permissionProvider.getPermissionList(userId, "login");
        } else if (StpUtil.isLogin()) {
            granted = StpUtil.getPermissionList();
        } else {
            granted = Collections.emptyList();
        }
        if (granted.contains("*")) {
            permissions.addAll(EnumSet.allOf(AiPermission.class));
            return;
        }
        for (AiPermission permission : AiPermission.values()) {
            String lower = permission.name().toLowerCase(java.util.Locale.ROOT);
            if (granted.contains(permission.name()) || granted.contains("ai:" + lower)) {
                permissions.add(permission);
            }
        }
    }

    private boolean isMember(String userId, String tenantId, String workspaceId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(1) from ai_workspace_member wm join ai_workspace w on w.id=wm.workspace_id " +
                            "where wm.user_id=? and wm.workspace_id=? and w.tenant_id=? and wm.status='ACTIVE' " +
                            "and wm.deleted=0 and w.deleted=0", Integer.class, userId, workspaceId, tenantId);
            return count != null && count > 0;
        } catch (DataAccessException e) {
            return false;
        }
    }

    private String membershipRole(String userId, String tenantId, String workspaceId) {
        try {
            return jdbcTemplate.queryForObject(
                    "select wm.role from ai_workspace_member wm join ai_workspace w on w.id=wm.workspace_id " +
                            "where wm.user_id=? and wm.workspace_id=? and w.tenant_id=? and wm.deleted=0 and w.deleted=0",
                    String.class, userId, workspaceId, tenantId);
        } catch (DataAccessException e) {
            return "";
        }
    }
}
