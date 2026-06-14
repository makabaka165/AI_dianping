package com.hmdp.ai.task;

import com.hmdp.dto.ai.AiTask;

import java.util.Map;

public final class AiTaskParams {

    private AiTaskParams() {
    }

    public static Integer integerParam(AiTask task, String key) {
        return integerParam(task == null ? null : task.getParams(), key);
    }

    public static Integer integerParam(AiTask task, String key, int defaultValue) {
        Integer value = integerParam(task, key);
        return value == null ? defaultValue : value;
    }

    public static Long longParam(AiTask task, String key) {
        return longParam(task == null ? null : task.getParams(), key);
    }

    public static Integer integerParam(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Long longParam(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
