package com.hmdp.ai.guard;

import org.springframework.stereotype.Service;

@Service
public class PiiRedactionService {
    private final PiiDetectionService detectionService;
    public PiiRedactionService(PiiDetectionService detectionService){this.detectionService=detectionService;}
    public String redact(String text){ if(text==null)return null; String value=text; for(java.util.Map.Entry<String,java.util.regex.Pattern> e:detectionService.patterns().entrySet()) value=e.getValue().matcher(value).replaceAll("[REDACTED_"+e.getKey()+"]"); return value; }
}
