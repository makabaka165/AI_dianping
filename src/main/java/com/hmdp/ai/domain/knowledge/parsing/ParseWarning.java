package com.hmdp.ai.domain.knowledge.parsing;

public final class ParseWarning {
    private final String code;
    private final String message;
    public ParseWarning(String code, String message) { this.code = code; this.message = message; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
}
