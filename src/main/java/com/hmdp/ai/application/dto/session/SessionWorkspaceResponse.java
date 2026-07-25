package com.hmdp.ai.application.dto.session;

public final class SessionWorkspaceResponse {
    private final String id;
    private final String name;

    public SessionWorkspaceResponse(String id, String name) {
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
