package com.hmdp.ai.guard;

import java.util.Collections;
import java.util.List;

public final class GuardrailDecision {
    private final boolean allowed;
    private final List<String> issues;
    private final String sanitizedText;
    public GuardrailDecision(boolean allowed, List<String> issues, String sanitizedText) {
        this.allowed=allowed; this.issues=Collections.unmodifiableList(issues); this.sanitizedText=sanitizedText;
    }
    public boolean isAllowed(){return allowed;} public List<String> getIssues(){return issues;} public String getSanitizedText(){return sanitizedText;}
}
