package com.hmdp.ai.infrastructure.parser;

public final class ParseWarning {
    private final String code;
    private final String message;
    public ParseWarning(String code, String message) { this.code = code; this.message = message; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
}
