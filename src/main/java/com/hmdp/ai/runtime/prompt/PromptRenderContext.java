package com.hmdp.ai.runtime.prompt;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PromptRenderContext {
    private final Map<String, Object> variables;
    private final Instant currentTime;
    private final String locale;
    private final String timezone;

    public PromptRenderContext(Map<String, Object> variables, Instant currentTime,
                               String locale, String timezone) {
        this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables == null
                ? Collections.emptyMap() : variables));
        this.currentTime = currentTime == null ? Instant.now() : currentTime;
        this.locale = locale == null ? "zh-CN" : locale;
        this.timezone = timezone == null ? "Asia/Shanghai" : timezone;
    }

    public Map<String, Object> getVariables() { return variables; }
    public Instant getCurrentTime() { return currentTime; }
    public String getLocale() { return locale; }
    public String getTimezone() { return timezone; }
}
