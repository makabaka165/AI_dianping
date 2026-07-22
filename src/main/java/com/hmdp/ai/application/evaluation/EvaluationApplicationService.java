package com.hmdp.ai.application.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.evaluation.CreateEvaluationDatasetRequest;
import com.hmdp.ai.application.dto.evaluation.CreateEvaluationRunRequest;
import com.hmdp.ai.application.dto.evaluation.CreateEvaluationCaseRequest;
import com.hmdp.ai.application.dto.evaluation.EvaluationRunResponse;
import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.evaluation.EvaluationCase;
import com.hmdp.ai.domain.evaluation.EvaluationDataset;
import com.hmdp.ai.domain.evaluation.EvaluationRepository;
import com.hmdp.ai.domain.evaluation.EvaluationRun;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AiSecurityContext;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.shared.id.AiIdGenerator;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class EvaluationApplicationService {
    private final EvaluationRepository repository;
    private final EvaluationRunWorker worker;
    private final AiAccessGuard access;
    private final AiIdGenerator ids;
    private final ObjectMapper mapper;

    public EvaluationApplicationService(EvaluationRepository repository, EvaluationRunWorker worker,
                                        AiAccessGuard access, AiIdGenerator ids,
                                        ObjectMapper mapper) {
        this.repository = repository;
        this.worker = worker;
        this.access = access;
        this.ids = ids;
        this.mapper = mapper;
    }

    @Transactional
    public EvaluationDataset createDataset(CreateEvaluationDatasetRequest request) {
        AiSecurityContext context = access.require(AiPermission.EVALUATION_MANAGE);
        return repository.createDataset(new EvaluationDataset(ids.nextId(), context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), request.getCode(), request.getName(),
                request.getDescription(), request.getType(), "ACTIVE"), context.getUserId());
    }

    @Transactional
    public EvaluationCase createCase(String datasetId, CreateEvaluationCaseRequest request) {
        AiSecurityContext context = access.require(AiPermission.EVALUATION_MANAGE);
        requireDataset(context, datasetId);
        return repository.createCase(new EvaluationCase(ids.nextId(), context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), datasetId, request.getName(), json(request.getInput()),
                json(request.getExpected()), json(request.getAssertions()), "ACTIVE"), context.getUserId());
    }

    @Transactional
    public EvaluationRunResponse run(CreateEvaluationRunRequest request) {
        AiSecurityContext context = access.require(AiPermission.EVALUATION_RUN);
        if (!worker.supports(request.getTargetType())) {
            throw new IllegalArgumentException("EVALUATION_TARGET_UNSUPPORTED");
        }
        requireDataset(context, request.getDatasetId());
        List<EvaluationCase> cases = repository.findCases(context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), request.getDatasetId());
        EvaluationRun run = repository.createRun(new EvaluationRun(ids.nextId(), context.getTenant().getTenantId(),
                context.getWorkspace().getWorkspaceId(), request.getDatasetId(), request.getTargetType(),
                request.getTargetId(), request.getTargetVersion(), "RUNNING", "{}", Instant.now(), null),
                context.getUserId());
        worker.execute(run, cases, request, context);
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

}
