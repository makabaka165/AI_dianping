package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.evaluation.CreateEvaluationDatasetRequest;
import com.hmdp.ai.application.dto.evaluation.CreateEvaluationRunRequest;
import com.hmdp.ai.application.dto.evaluation.CreateEvaluationCaseRequest;
import com.hmdp.ai.application.dto.evaluation.EvaluationCandidateRequest;
import com.hmdp.ai.application.dto.evaluation.EvaluationRunResponse;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.evaluation.EvaluationCandidate;
import com.hmdp.ai.domain.evaluation.EvaluationCase;
import com.hmdp.ai.domain.evaluation.EvaluationDataset;
import com.hmdp.ai.domain.evaluation.EvaluationMetricEngine;
import com.hmdp.ai.domain.evaluation.EvaluationRepository;
import com.hmdp.ai.domain.evaluation.EvaluationResult;
import com.hmdp.ai.domain.evaluation.EvaluationRun;
import com.hmdp.ai.domain.evaluation.MetricEvaluation;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class EvaluationApplicationService {
    private final EvaluationRepository repository;
    private final EvaluationMetricEngine metrics;
    private final EvaluationExecutor executor;
    private final AiAccessGuard access;
    private final AiIdGenerator ids;
    private final ObjectMapper mapper;

    public EvaluationApplicationService(EvaluationRepository repository, EvaluationMetricEngine metrics,
                                        EvaluationExecutor executor, AiAccessGuard access, AiIdGenerator ids,
                                        ObjectMapper mapper) {
        this.repository = repository;
        this.metrics = metrics;
        this.executor = executor;
        this.access = access;
        this.ids = ids;
        this.mapper = mapper;
    }

    @Transactional
    public EvaluationDataset createDataset(CreateEvaluationDatasetRequest request) {
        AiSecurityContext context = access.require(AiPermission.EVALUATION_RUN);
        return repository.createDataset(new EvaluationDataset(ids.nextId(), context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), request.getCode(), request.getName(),
                request.getDescription(), request.getType(), "ACTIVE"), context.getUserId());
    }

    @Transactional
    public EvaluationCase createCase(String datasetId, CreateEvaluationCaseRequest request) {
        AiSecurityContext context = access.require(AiPermission.EVALUATION_RUN);
        requireDataset(context, datasetId);
        return repository.createCase(new EvaluationCase(ids.nextId(), context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), datasetId, request.getName(), json(request.getInput()),
                json(request.getExpected()), json(request.getAssertions()), "ACTIVE"), context.getUserId());
    }

    @Transactional
    public EvaluationRunResponse run(CreateEvaluationRunRequest request) {
        AiSecurityContext context = access.require(AiPermission.EVALUATION_RUN);
        requireDataset(context, request.getDatasetId());
        List<EvaluationCase> cases = repository.findCases(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), request.getDatasetId());
        Map<String, EvaluationCandidateRequest> supplied = request.getCandidates().stream()
                .collect(Collectors.toMap(EvaluationCandidateRequest::getCaseId, value -> value,
                        (left, right) -> { throw new IllegalArgumentException("duplicate evaluation case output"); }));
        if (!supplied.isEmpty() && cases.size() != supplied.size()) {
            throw new IllegalArgumentException("candidate outputs must match every active dataset case");
        }
        EvaluationRun run = repository.createRun(new EvaluationRun(ids.nextId(), context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), request.getDatasetId(), request.getTargetType(),
                request.getTargetId(), request.getTargetVersion(), "RUNNING", "{}", Instant.now(), null),
                context.getUserId());
        List<EvaluationResult> results = new ArrayList<>();
        int passed = 0;
        for (EvaluationCase evaluationCase : cases) {
            EvaluationCandidate candidate;
            if (supplied.isEmpty()) {
                EvaluationExecutionResult actual = executor.execute(evaluationCase, request.getTargetType(),
                        request.getTargetId(), request.getTargetVersion(), request.getExecutionOptions(),
                        context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(),
                        context.getUserId());
                candidate = new EvaluationCandidate(actual.getActual(), actual.getLatencyMs(), actual.getInputTokens(),
                        actual.getOutputTokens(), actual.getModelCalls(), actual.getToolCalls(), actual.getCost(),
                        actual.isSuccess());
            } else {
                EvaluationCandidateRequest value = supplied.get(evaluationCase.getId());
                candidate = new EvaluationCandidate(value.getActual(), value.getLatencyMs(), value.getInputTokens(),
                        value.getOutputTokens(), value.getModelCalls(), value.getToolCalls(), value.getCost(),
                        value.isSuccess());
            }
            MetricEvaluation outcome = metrics.evaluate(evaluationCase, candidate);
            if (outcome.isPassed()) passed++;
            results.add(new EvaluationResult(ids.nextId(), run.getTenantId(), run.getWorkspaceId(), run.getId(),
                    evaluationCase.getId(), json(candidate.getActual()), json(outcome.getMetrics()), outcome.isPassed(),
                    null, null, "COMPLETED"));
        }
        repository.saveResults(run.getId(), results, json(new Summary(results.size(), passed)), context.getUserId());
        return getInternal(context, run.getId());
    }

    public EvaluationRunResponse get(String runId) {
        return getInternal(access.require(AiPermission.EVALUATION_RUN), runId);
    }

    private EvaluationRunResponse getInternal(AiSecurityContext context, String id) {
        EvaluationRun run = repository.findRun(context.getTenant().getTenantId(),
                        context.getWorkspace().getWorkspaceId(), id)
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,
                        "evaluation run not found"));
        return new EvaluationRunResponse(run, repository.findResults(run.getTenantId(), run.getWorkspaceId(), run.getId()));
    }

    private EvaluationDataset requireDataset(AiSecurityContext context, String id) {
        return repository.findDataset(context.getTenant().getTenantId(), context.getWorkspace().getWorkspaceId(), id)
                .orElseThrow(() -> new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,
                        "evaluation dataset not found"));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("evaluation payload is invalid", e); }
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
