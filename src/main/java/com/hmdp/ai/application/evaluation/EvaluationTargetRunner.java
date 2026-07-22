package com.hmdp.ai.application.evaluation;

public interface EvaluationTargetRunner {
    String targetType();

    EvaluationExecutionResult execute(EvaluationTargetRequest request);
}
