package com.hmdp.ai.application.session;

import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.ai.application.dto.session.SessionBootstrapResponse;
import com.hmdp.ai.application.dto.session.SessionMembershipResponse;
import com.hmdp.ai.application.dto.session.SessionScopeResponse;
import com.hmdp.ai.application.dto.session.SessionTenantResponse;
import com.hmdp.ai.application.dto.session.SessionUserResponse;
import com.hmdp.ai.application.dto.session.SessionWorkspaceResponse;
import com.hmdp.ai.application.security.AiAuthorizationService;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.common.ErrorCode;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SessionBootstrapApplicationService {
    private final JdbcTemplate jdbcTemplate;
    private final IUserService userService;
    private final AiAuthorizationService authorizationService;

    public SessionBootstrapApplicationService(JdbcTemplate jdbcTemplate, IUserService userService,
                                              AiAuthorizationService authorizationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.userService = userService;
        this.authorizationService = authorizationService;
    }

    public SessionBootstrapResponse bootstrap() {
        if (!StpUtil.isLogin()) {
            throw new AiPlatformException(ErrorCode.UNAUTHORIZED, "login required");
        }
        String userId = StpUtil.getLoginIdAsString();
        User user = userService.getById(Long.valueOf(userId));
        if (user == null) {
            throw new AiPlatformException(ErrorCode.UNAUTHORIZED, "login required");
        }
        SessionUserResponse userResponse = new SessionUserResponse(
                String.valueOf(user.getId()),
                user.getNickName() == null ? "" : user.getNickName(),
                user.getIcon() == null ? "" : user.getIcon());
        List<MembershipRow> rows = loadMemberships(userId);
        List<SessionMembershipResponse> memberships = new ArrayList<>();
        for (MembershipRow row : rows) {
            List<String> roles = Collections.singletonList(row.role);
            List<String> permissions;
            if ("ACTIVE".equals(row.status)) {
                AuthorizationContext authorization = authorizationService.authorize(
                        userId, row.tenantId, row.workspaceId);
                permissions = authorization.getPermissions().stream()
                        .map(AiPermission::name)
                        .sorted()
                        .collect(Collectors.toList());
            } else {
                permissions = Collections.emptyList();
            }
            memberships.add(new SessionMembershipResponse(
                    new SessionTenantResponse(row.tenantId, row.tenantName),
                    new SessionWorkspaceResponse(row.workspaceId, row.workspaceName),
                    roles,
                    permissions,
                    false,
                    row.status));
        }
        SessionScopeResponse defaultScope = resolveDefaultScope(memberships);
        if (defaultScope != null) {
            memberships = markDefault(memberships, defaultScope);
        }
        return new SessionBootstrapResponse(userResponse, memberships, defaultScope);
    }

    private List<MembershipRow> loadMemberships(String userId) {
        try {
            return jdbcTemplate.query(
                    "select wm.tenant_id, coalesce(t.name, wm.tenant_id) as tenant_name, "
                            + "wm.workspace_id, coalesce(w.name, wm.workspace_id) as workspace_name, "
                            + "wm.role, wm.status "
                            + "from ai_workspace_member wm "
                            + "left join ai_workspace w on w.id = wm.workspace_id and w.deleted = 0 "
                            + "left join ai_tenant t on t.id = wm.tenant_id and t.deleted = 0 "
                            + "where wm.user_id = ? and wm.deleted = 0 "
                            + "order by case when wm.status = 'ACTIVE' then 0 else 1 end, "
                            + "wm.tenant_id, wm.workspace_id",
                    (rs, rowNum) -> new MembershipRow(
                            rs.getString("tenant_id"),
                            rs.getString("tenant_name"),
                            rs.getString("workspace_id"),
                            rs.getString("workspace_name"),
                            rs.getString("role") == null ? "" : rs.getString("role"),
                            rs.getString("status") == null ? "" : rs.getString("status")),
                    userId);
        } catch (DataAccessException e) {
            return Collections.emptyList();
        }
    }

    private SessionScopeResponse resolveDefaultScope(List<SessionMembershipResponse> memberships) {
        return memberships.stream()
                .filter(item -> "ACTIVE".equalsIgnoreCase(item.getStatus()))
                .filter(item -> !item.getPermissions().isEmpty())
                .findFirst()
                .map(item -> new SessionScopeResponse(item.getTenant().getId(), item.getWorkspace().getId()))
                .orElse(null);
    }

    private List<SessionMembershipResponse> markDefault(List<SessionMembershipResponse> memberships,
                                                        SessionScopeResponse defaultScope) {
        List<SessionMembershipResponse> result = new ArrayList<>(memberships.size());
        for (SessionMembershipResponse membership : memberships) {
            boolean isDefault = membership.getTenant().getId().equals(defaultScope.getTenantId())
                    && membership.getWorkspace().getId().equals(defaultScope.getWorkspaceId());
            result.add(new SessionMembershipResponse(
                    membership.getTenant(),
                    membership.getWorkspace(),
                    membership.getRoles(),
                    membership.getPermissions(),
                    isDefault,
                    membership.getStatus()));
        }
        return result;
    }

    private static final class MembershipRow {
        private final String tenantId;
        private final String tenantName;
        private final String workspaceId;
        private final String workspaceName;
        private final String role;
        private final String status;

        private MembershipRow(String tenantId, String tenantName, String workspaceId, String workspaceName,
                              String role, String status) {
            this.tenantId = tenantId;
            this.tenantName = tenantName;
            this.workspaceId = workspaceId;
            this.workspaceName = workspaceName;
            this.role = role;
            this.status = status;
        }
    }
}
