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
    SHOP_UPDATE_FAILED(50010, "Shop update failed"),
    SHOP_TYPE_UPDATE_FAILED(50020, "Shop type update failed"),
    SHOP_GEO_REBUILD_FAILED(50030, "Shop GEO rebuild failed"),
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
