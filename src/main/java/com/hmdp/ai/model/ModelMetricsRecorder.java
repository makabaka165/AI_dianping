package com.hmdp.ai.model;

import com.hmdp.ai.infra.AiMetricsService;
import com.hmdp.ai.infra.AiTokenEstimator;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class ModelMetricsRecorder {

    @Resource
    private AiMetricsService aiMetricsService;

    @Resource
    private AiTokenEstimator aiTokenEstimator;

    public void record(String operation,
                       String modelName,
                       String input,
                       String output,
                       long durationMillis,
                       boolean success) {
        if (aiMetricsService == null) {
            return;
        }
        int inputTokens = aiTokenEstimator == null ? 0 : aiTokenEstimator.estimate(input);
        int outputTokens = aiTokenEstimator == null ? 0 : aiTokenEstimator.estimate(output);
        aiMetricsService.recordModelCall(analysisType(operation), operation, modelName,
                durationMillis, success, inputTokens, outputTokens);
    }

    public String analysisType(String operation) {
        if (operation == null || operation.trim().isEmpty()) {
            return "unknown";
        }
        int colon = operation.indexOf(':');
        if (colon > 0) {
            return operation.substring(0, colon);
        }
        if (operation.contains("Intent")) {
            return "intent";
        }
        if (operation.contains("freeChat")) {
            return "chat";
        }
        if (operation.contains("StructuredAnalysis")) {
            return "summary";
        }
        return operation;
    }
}
