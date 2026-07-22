package com.hmdp.ai.application.evaluation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class EvaluationTargetResolver {
    private final Map<String, EvaluationTargetRunner> runners;

    public EvaluationTargetResolver(List<EvaluationTargetRunner> runners) {
        Map<String, EvaluationTargetRunner> values = new LinkedHashMap<>();
        for (EvaluationTargetRunner runner : runners) {
            String type = normalize(runner.targetType());
            if (values.putIfAbsent(type, runner) != null) {
                throw new IllegalStateException("duplicate evaluation target runner: " + type);
            }
        }
        this.runners = java.util.Collections.unmodifiableMap(values);
    }

    public EvaluationTargetRunner resolve(String targetType) {
        EvaluationTargetRunner runner = runners.get(normalize(targetType));
        if (runner == null) throw new IllegalArgumentException("EVALUATION_TARGET_UNSUPPORTED");
        return runner;
    }

    private String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("EVALUATION_TARGET_TYPE_REQUIRED");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
