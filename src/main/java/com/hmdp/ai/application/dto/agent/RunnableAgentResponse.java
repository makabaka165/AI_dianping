package com.hmdp.ai.application.dto.agent;

public final class RunnableAgentResponse {
    private final String id;
    private final String code;
    private final String name;
    private final String description;
    private final int publishedVersion;

    public RunnableAgentResponse(String id, String code, String name, String description, int publishedVersion) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.publishedVersion = publishedVersion;
    }

    public String getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getPublishedVersion() {
        return publishedVersion;
    }
}
