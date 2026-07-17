package com.hmdp.ai.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ContentPolicyService {
    private final List<Pattern> blocked;
    public ContentPolicyService(@Value("${hmdp.ai.content-policy.blocked-patterns:}") String configured){ List<Pattern> values=new ArrayList<>(); if(configured!=null) for(String item:configured.split("\\|")) if(!item.trim().isEmpty()) values.add(Pattern.compile(item.trim(),Pattern.CASE_INSENSITIVE)); blocked=Collections.unmodifiableList(values); }
    public List<String> violations(String text){List<String> issues=new ArrayList<>();if(text!=null)for(Pattern p:blocked)if(p.matcher(text).find())issues.add("CONTENT_POLICY_BLOCKED");return issues;}
}
