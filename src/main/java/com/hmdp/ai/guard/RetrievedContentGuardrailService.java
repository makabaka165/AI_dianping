package com.hmdp.ai.guard;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class RetrievedContentGuardrailService {
    private final PromptInjectionDetectionService injection; private final PiiRedactionService redaction;
    public RetrievedContentGuardrailService(PromptInjectionDetectionService injection,PiiRedactionService redaction){this.injection=injection;this.redaction=redaction;}
    public GuardrailDecision inspect(String text){List<String> issues=new ArrayList<>();if(injection.detected(text))issues.add("UNTRUSTED_RETRIEVED_INSTRUCTION");return new GuardrailDecision(true,issues,redaction.redact(text));}
}
