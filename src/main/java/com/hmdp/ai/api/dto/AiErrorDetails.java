package com.hmdp.ai.api.dto;

import com.hmdp.ai.shared.validation.ValidationIssue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AiErrorDetails {
    private final List<ValidationIssue> issues;

    public AiErrorDetails(List<ValidationIssue> issues) {
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues == null
                ? Collections.emptyList() : issues));
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }
}
