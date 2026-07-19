package com.hmdp.ai.runtime.model;

import java.util.Objects;

public final class ModelInvocation {
    private final ModelInvocationContext context;
    private final String modelProfileVersionId;
    private final String systemPrompt;
    private final String userPrompt;
    private final String responseFormat;
    private final String outputSchema;
    private final Double temperature;
    private final Integer maxOutputTokens;
    private final boolean streaming;
    private final String requestSummary;

    public ModelInvocation(ModelInvocationContext context, String modelProfileVersionId, String systemPrompt,
                           String userPrompt, String responseFormat, String outputSchema, Double temperature,
                           Integer maxOutputTokens, boolean streaming, String requestSummary) {
        this.context = Objects.requireNonNull(context, "context");
        this.modelProfileVersionId = Objects.requireNonNull(modelProfileVersionId, "modelProfileVersionId");
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
        this.responseFormat = responseFormat == null ? "TEXT" : responseFormat;
        this.outputSchema = outputSchema;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.streaming = streaming;
        this.requestSummary = requestSummary;
    }

    public ModelInvocationContext getContext() { return context; }
    public String getModelProfileVersionId() { return modelProfileVersionId; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getUserPrompt() { return userPrompt; }
    public String getResponseFormat() { return responseFormat; }
    public String getOutputSchema() { return outputSchema; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public boolean isStreaming() { return streaming; }
    public String getRequestSummary() { return requestSummary; }
}
