package com.hmdp.ai.application.dto.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SessionMembershipResponse {
    private final SessionTenantResponse tenant;
    private final SessionWorkspaceResponse workspace;
    private final List<String> roles;
    private final List<String> permissions;
    private final boolean isDefault;
    private final String status;

    public SessionMembershipResponse(SessionTenantResponse tenant, SessionWorkspaceResponse workspace,
                                     List<String> roles, List<String> permissions, boolean isDefault,
                                     String status) {
        this.tenant = tenant;
        this.workspace = workspace;
        this.roles = Collections.unmodifiableList(new ArrayList<>(roles));
        this.permissions = Collections.unmodifiableList(new ArrayList<>(permissions));
        this.isDefault = isDefault;
        this.status = status;
    }

    public SessionTenantResponse getTenant() {
        return tenant;
    }

    public SessionWorkspaceResponse getWorkspace() {
        return workspace;
    }

    public List<String> getRoles() {
        return roles;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public String getStatus() {
        return status;
    }
}
