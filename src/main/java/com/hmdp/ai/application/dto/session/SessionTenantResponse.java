package com.hmdp.ai.application.dto.session;

public final class SessionTenantResponse {
    private final String id;
    private final String name;

    public SessionTenantResponse(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
