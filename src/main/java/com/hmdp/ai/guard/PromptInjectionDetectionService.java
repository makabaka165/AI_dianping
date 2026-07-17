package com.hmdp.ai.guard;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class PromptInjectionDetectionService {
    private final List<Pattern> patterns=Arrays.asList(
            Pattern.compile("(?i)ignore\\s+(all|any|the)?\\s*(previous|prior|system)\\s+instructions"),
            Pattern.compile("(?i)(reveal|print|return).{0,30}(system prompt|api key|secret)"),
            Pattern.compile("(?i)忽略.{0,12}(之前|系统).{0,12}(指令|提示词)"));
    public boolean detected(String text){if(text==null)return false;return patterns.stream().anyMatch(p->p.matcher(text).find());}
}
