package com.hmdp.ai.application.dto.agent;

import com.hmdp.ai.shared.validation.ValidationIssue;
import com.hmdp.ai.shared.validation.ValidationResult;

import java.util.List;

public final class PublishValidationResponse {
    private final boolean valid;
    private final List<ValidationIssue> issues;

    public PublishValidationResponse(ValidationResult result) {
        this.valid = result.isValid();
        this.issues = result.getIssues();
    }

    public boolean isValid() { return valid; }
    public List<ValidationIssue> getIssues() { return issues; }
}
