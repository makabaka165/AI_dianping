package com.hmdp.ai.shared.exception;

import com.hmdp.ai.shared.validation.ValidationIssue;
import com.hmdp.common.ErrorCode;
import com.hmdp.exception.BusinessException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AiPlatformException extends BusinessException {
    private final List<ValidationIssue> issues;

    public AiPlatformException(ErrorCode errorCode, String message) {
        this(errorCode, message, Collections.emptyList());
    }

    public AiPlatformException(ErrorCode errorCode, String message, List<ValidationIssue> issues) {
        super(errorCode, message);
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues == null
                ? Collections.emptyList() : issues));
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }
}
