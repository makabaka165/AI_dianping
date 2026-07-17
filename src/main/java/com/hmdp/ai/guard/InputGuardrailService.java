package com.hmdp.ai.guard;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class InputGuardrailService {
    private final PromptInjectionDetectionService injection; private final ContentPolicyService policy; private final PiiRedactionService redaction;
    public InputGuardrailService(PromptInjectionDetectionService injection,ContentPolicyService policy,PiiRedactionService redaction){this.injection=injection;this.policy=policy;this.redaction=redaction;}
    public GuardrailDecision inspect(String text,int maxLength,boolean redactPii){List<String> issues=new ArrayList<>();if(text==null||text.trim().isEmpty())issues.add("INPUT_EMPTY");if(text!=null&&text.length()>maxLength)issues.add("INPUT_TOO_LONG");if(injection.detected(text))issues.add("PROMPT_INJECTION_DETECTED");issues.addAll(policy.violations(text));String sanitized=redactPii?redaction.redact(text):text;return new GuardrailDecision(issues.isEmpty(),issues,sanitized);}
}
