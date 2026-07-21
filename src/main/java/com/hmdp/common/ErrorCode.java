package com.hmdp.common;

public enum ErrorCode {

    SUCCESS(0, "OK"),
    PARAM_ERROR(40000, "Bad request"),
    CAPTCHA_ERROR(40010, "Captcha error"),
    CAPTCHA_EXPIRED(40011, "Captcha expired"),
    UNAUTHORIZED(40100, "Not logged in"),
    FORBIDDEN(40300, "Permission denied"),
    ACCOUNT_DISABLED(40310, "Account disabled"),
    LOGIN_BLOCKED(42300, "Login temporarily blocked"),
    RATE_LIMITED(42900, "Too many requests"),
    NOT_FOUND(40400, "Resource not found"),
    SHOP_NOT_FOUND(40410, "Shop not found"),
    SHOP_TYPE_NOT_FOUND(40420, "Shop type not found"),
    SHOP_UPDATE_CONFLICT(40910, "Shop update conflict"),
    SHOP_TYPE_UPDATE_FAILED(50020, "Shop type update failed"),
    SHOP_GEO_REBUILD_FAILED(50030, "Shop GEO rebuild failed"),
    AI_RESOURCE_NOT_FOUND(40450, "AI resource not found"),
    AI_VERSION_NOT_FOUND(40451, "AI resource version not found"),
    AI_VERSION_CONFLICT(40950, "AI resource version conflict"),
    AI_RUN_NOT_CANCELLABLE(40951, "AI run cannot be cancelled"),
    AI_RUN_NOT_RETRYABLE(40952, "AI run cannot be retried"),
    AI_PUBLISH_VALIDATION_FAILED(42250, "AI publish validation failed"),
    AI_INPUT_SCHEMA_INVALID(42251, "Agent input does not match its schema"),
    PROMPT_VARIABLE_MISSING(42253, "Required prompt variable is missing"),
    AI_OUTPUT_SCHEMA_INVALID(50050, "Agent output does not match its schema"),
    AI_MODEL_CAPABILITY_MISMATCH(42252, "Model capability does not match the input"),
    AI_PROVIDER_NOT_CONFIGURED(50350, "AI provider is not configured"),
    AI_EXECUTION_FAILED(50051, "Agent execution failed"),
    SERVICE_UNAVAILABLE(50300, "Service unavailable"),
    SYSTEM_BUSY(50310, "System busy"),
    BUSINESS_ERROR(50000, "Business error"),
    SYSTEM_ERROR(50001, "Internal server error");

    private final Integer code;
    private final String message;

    ErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
