package com.hmdp.ai.domain.security;

public final class TenantContext {
    private final String tenantId;
    public TenantContext(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) throw new IllegalArgumentException("tenantId required");
        this.tenantId = tenantId.trim();
    }
    public String getTenantId() { return tenantId; }
}
