package com.hmdp.ai.domain.security;

public final class AiSecurityContext {
    private final String userId;
    private final TenantContext tenant;
    private final WorkspaceContext workspace;
    private final AuthorizationContext authorization;
    private final boolean anonymous;

    public AiSecurityContext(String userId, TenantContext tenant, WorkspaceContext workspace,
                             AuthorizationContext authorization, boolean anonymous) {
        this.userId = userId;
        this.tenant = tenant;
        this.workspace = workspace;
        this.authorization = authorization;
        this.anonymous = anonymous;
    }
    public String getUserId() { return userId; }
    public TenantContext getTenant() { return tenant; }
    public WorkspaceContext getWorkspace() { return workspace; }
    public AuthorizationContext getAuthorization() { return authorization; }
    public boolean isAnonymous() { return anonymous; }
}
