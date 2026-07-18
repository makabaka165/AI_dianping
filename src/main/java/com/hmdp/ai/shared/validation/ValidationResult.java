package com.hmdp.ai.shared.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationResult {
    private final List<ValidationIssue> issues;

    public ValidationResult(List<ValidationIssue> issues) {
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues == null
                ? Collections.emptyList() : issues));
    }

    public static ValidationResult valid() {
        return new ValidationResult(Collections.emptyList());
    }

    public boolean isValid() { return issues.isEmpty(); }
    public List<ValidationIssue> getIssues() { return issues; }
}
