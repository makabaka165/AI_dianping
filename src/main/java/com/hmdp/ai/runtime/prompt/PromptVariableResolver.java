package com.hmdp.ai.runtime.prompt;

import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;

@Component
public final class PromptVariableResolver {
    public Object resolve(String expression, PromptRenderContext context) {
        String path = expression == null ? "" : expression.trim();
        if (path.startsWith("$.")) path = path.substring(2);
        Object value = context.getVariables().get(path);
        if (value != null) return value;
        String[] parts = path.split("\\.");
        value = context.getVariables().get(parts[0]);
        for (int index = 1; index < parts.length && value != null; index++) value = child(value, parts[index]);
        if (value != null) return value;
        if ("currentTime".equals(path)) return context.getCurrentTime().toString();
        if ("locale".equals(path)) return context.getLocale();
        if ("timezone".equals(path)) return context.getTimezone();
        return null;
    }

    private Object child(Object value, String key) {
        if (value instanceof Map) return ((Map<?, ?>) value).get(key);
        if (value instanceof List) {
            try { return ((List<?>) value).get(Integer.parseInt(key)); } catch (Exception ignored) { return null; }
        }
        if (value.getClass().isArray()) {
            try { return Array.get(value, Integer.parseInt(key)); } catch (Exception ignored) { return null; }
        }
        return null;
    }
}
