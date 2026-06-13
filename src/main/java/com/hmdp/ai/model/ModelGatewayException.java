package com.hmdp.ai.model;

public class ModelGatewayException extends RuntimeException {
    public ModelGatewayException(String operation, Throwable cause) {
        super("AI model operation failed: " + operation, cause);
    }
}
