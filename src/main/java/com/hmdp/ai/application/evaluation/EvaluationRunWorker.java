package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.evaluation.CreateEvaluationRunRequest;
import com.hmdp.ai.domain.evaluation.EvaluationCandidate;
import com.hmdp.ai.domain.evaluation.EvaluationCase;
import com.hmdp.ai.domain.evaluation.EvaluationMetricEngine;
import com.hmdp.ai.domain.evaluation.EvaluationRepository;
import com.hmdp.ai.domain.evaluation.EvaluationResult;
import com.hmdp.ai.domain.evaluation.EvaluationRun;
import com.hmdp.ai.domain.evaluation.MetricEvaluation;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.shared.id.AiIdGenerator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class EvaluationRunWorker {
    private final EvaluationRepository repository;
    private final EvaluationMetricEngine metrics;
    private final EvaluationExecutor executor;
    private final AiIdGenerator ids;
    private final ObjectMapper mapper;

    public EvaluationRunWorker(EvaluationRepository repository, EvaluationMetricEngine metrics,
                               EvaluationExecutor executor, AiIdGenerator ids, ObjectMapper mapper) {
        this.repository = repository;
        this.metrics = metrics;
        this.executor = executor;
        this.ids = ids;
        this.mapper = mapper;
    }

    public void execute(EvaluationRun run, List<EvaluationCase> cases,
                        CreateEvaluationRunRequest request, AiSecurityContext context) {
        List<EvaluationResult> results = new ArrayList<>();
        int passed = 0;
        for (EvaluationCase evaluationCase : cases) {
            EvaluationExecutionResult execution = executor.execute(evaluationCase, request.getTargetType(),
                    request.getTargetId(), request.getTargetVersion(), request.getExecutionOptions(),
                    context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(),
                    context.getUserId(), context.getAuthorization());
            EvaluationCandidate candidate = new EvaluationCandidate(execution.getActual(),
                    execution.getLatencyMs(), execution.getInputTokens(), execution.getOutputTokens(),
                    execution.getModelCalls(), execution.getToolCalls(), execution.getCost(),
                    execution.isSuccess());
            MetricEvaluation outcome = metrics.evaluate(evaluationCase, candidate);
            if (outcome.isPassed()) passed++;
            results.add(new EvaluationResult(ids.nextId(), run.getTenantId(), run.getWorkspaceId(),
                    run.getId(), evaluationCase.getId(), execution.getRunId(), json(candidate.getActual()),
                    json(outcome.getMetrics()), outcome.isPassed(), execution.getErrorCode(),
                    execution.getErrorMessage(), execution.isSuccess() ? "COMPLETED" : "FAILED"));
        }
        repository.saveResults(run.getId(), results, json(new Summary(results.size(), passed)),
                context.getUserId());
    }

    public boolean supports(String targetType) {
        return executor.supports(targetType);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("evaluation payload is invalid", error);
        }
    }

    private static final class Summary {
        private final int total;
        private final int passed;
        private final int failed;

        private Summary(int total, int passed) {
            this.total = total;
            this.passed = passed;
            this.failed = total - passed;
        }

        public int getTotal() { return total; }
        public int getPassed() { return passed; }
        public int getFailed() { return failed; }
    }
}
