package com.hmdp.ai.guard;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class PiiDetectionService {
    private static final Map<String,Pattern> PATTERNS;
    static { Map<String,Pattern> p=new LinkedHashMap<>(); p.put("EMAIL",Pattern.compile("(?i)\\b[\\w.%+-]+@[\\w.-]+\\.[A-Z]{2,}\\b")); p.put("PHONE",Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)")); p.put("CN_ID",Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)")); PATTERNS=Collections.unmodifiableMap(p); }
    public Set<String> detect(String text){ Set<String> found=new LinkedHashSet<>(); if(text==null)return found; PATTERNS.forEach((k,v)->{if(v.matcher(text).find())found.add(k);}); return found; }
    Map<String,Pattern> patterns(){return PATTERNS;}
}
