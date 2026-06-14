package com.hmdp.ai.quota;

public class AiQuotaExceededException extends RuntimeException {

    private final boolean infraError;

    public AiQuotaExceededException(String message) {
        this(message, false, null);
    }

    public AiQuotaExceededException(String message, Throwable cause) {
        this(message, false, cause);
    }

    private AiQuotaExceededException(String message, boolean infraError, Throwable cause) {
        super(message, cause);
        this.infraError = infraError;
    }

    public static AiQuotaExceededException infra(String message, Throwable cause) {
        return new AiQuotaExceededException(message, true, cause);
    }

    public boolean isInfraError() {
        return infraError;
    }
}
