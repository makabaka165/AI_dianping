package com.hmdp.ai.shared.validation;

import java.util.Objects;

public final class ValidationIssue {
    private final String code;
    private final String path;
    private final String message;

    public ValidationIssue(String code, String path, String message) {
        this.code = Objects.requireNonNull(code, "code");
        this.path = path == null ? "" : path;
        this.message = Objects.requireNonNull(message, "message");
    }

    public String getCode() { return code; }
    public String getPath() { return path; }
    public String getMessage() { return message; }
}
