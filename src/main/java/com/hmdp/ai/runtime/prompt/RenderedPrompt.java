package com.hmdp.ai.runtime.prompt;

public final class RenderedPrompt {
    private final String systemPrompt;
    private final String userPrompt;
    private final String summary;

    public RenderedPrompt(String systemPrompt, String userPrompt, String summary) {
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.summary = summary;
    }

    public String getSystemPrompt() { return systemPrompt; }
    public String getUserPrompt() { return userPrompt; }
    public String getSummary() { return summary; }
}
