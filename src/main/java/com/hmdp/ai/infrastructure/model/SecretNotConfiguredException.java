package com.hmdp.ai.infrastructure.model;

public class SecretNotConfiguredException extends RuntimeException {
    private final String variableName;

    public SecretNotConfiguredException(String variableName) {
        super("required secret is not configured");
        this.variableName = variableName;
    }

    public String getVariableName() {
        return variableName;
    }
}
