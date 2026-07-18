package com.hmdp.ai.application.evaluation;
import com.fasterxml.jackson.databind.ObjectMapper;import com.hmdp.ai.application.dto.evaluation.*;import com.hmdp.ai.application.security.AiAccessGuard;
import com.hmdp.ai.domain.evaluation.*;import com.hmdp.ai.domain.security.*;import com.hmdp.ai.shared.exception.AiPlatformException;import com.hmdp.ai.shared.id.AiIdGenerator;import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import java.time.Instant;import java.util.*;import java.util.stream.Collectors;
@Service public class EvaluationApplicationService {private final EvaluationRepository repository;private final EvaluationMetricEngine metrics;
    private final AiAccessGuard access;private final AiIdGenerator ids;private final ObjectMapper mapper;
    public EvaluationApplicationService(EvaluationRepository repository,EvaluationMetricEngine metrics,AiAccessGuard access,AiIdGenerator ids,ObjectMapper mapper){
        this.repository=repository;this.metrics=metrics;this.access=access;this.ids=ids;this.mapper=mapper;}
    @Transactional public EvaluationDataset createDataset(CreateEvaluationDatasetRequest request){AiSecurityContext c=access.require(AiPermission.EVALUATION_RUN);
        return repository.createDataset(new EvaluationDataset(ids.nextId(),c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),
                request.getCode(),request.getName(),request.getDescription(),request.getType(),"ACTIVE"),c.getUserId());}
    @Transactional public EvaluationCase createCase(String datasetId,CreateEvaluationCaseRequest request){AiSecurityContext c=access.require(AiPermission.EVALUATION_RUN);
        requireDataset(c,datasetId);return repository.createCase(new EvaluationCase(ids.nextId(),c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),
                datasetId,request.getName(),json(request.getInput()),json(request.getExpected()),json(request.getAssertions()),"ACTIVE"),c.getUserId());}
    @Transactional public EvaluationRunResponse run(CreateEvaluationRunRequest request){AiSecurityContext c=access.require(AiPermission.EVALUATION_RUN);requireDataset(c,request.getDatasetId());
        List<EvaluationCase>cases=repository.findCases(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),request.getDatasetId());
        Map<String,EvaluationCandidateRequest>candidates=request.getCandidates().stream().collect(Collectors.toMap(EvaluationCandidateRequest::getCaseId,v->v,(a,b)->{throw new IllegalArgumentException("duplicate evaluation case output");}));
        if(cases.size()!=candidates.size())throw new IllegalArgumentException("candidate outputs must match every active dataset case");
        EvaluationRun run=repository.createRun(new EvaluationRun(ids.nextId(),c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),request.getDatasetId(),
                request.getTargetType(),request.getTargetId(),request.getTargetVersion(),"RUNNING","{}",Instant.now(),null),c.getUserId());
        List<EvaluationResult>results=new ArrayList<>();int passed=0;for(EvaluationCase evalCase:cases){EvaluationCandidateRequest candidate=candidates.get(evalCase.getId());
            if(candidate==null)throw new IllegalArgumentException("missing candidate output for case "+evalCase.getId());EvaluationCandidate domainCandidate=new EvaluationCandidate(candidate.getActual(),candidate.getLatencyMs(),candidate.getInputTokens(),candidate.getOutputTokens(),candidate.getModelCalls(),candidate.getToolCalls(),candidate.getCost(),candidate.isSuccess());MetricEvaluation outcome=metrics.evaluate(evalCase,domainCandidate);if(outcome.isPassed())passed++;
            results.add(new EvaluationResult(ids.nextId(),run.getTenantId(),run.getWorkspaceId(),run.getId(),evalCase.getId(),json(candidate.getActual()),json(outcome.getMetrics()),outcome.isPassed(),null,null,"COMPLETED"));}
        String summary=json(new Summary(results.size(),passed));repository.saveResults(run.getId(),results,summary,c.getUserId());
        return getInternal(c,run.getId());}
    public EvaluationRunResponse get(String runId){return getInternal(access.require(AiPermission.EVALUATION_RUN),runId);}
    private EvaluationRunResponse getInternal(AiSecurityContext c,String id){EvaluationRun run=repository.findRun(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),id)
            .orElseThrow(()->new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,"evaluation run not found"));return new EvaluationRunResponse(run,repository.findResults(run.getTenantId(),run.getWorkspaceId(),run.getId()));}
    private EvaluationDataset requireDataset(AiSecurityContext c,String id){return repository.findDataset(c.getTenant().getTenantId(),c.getWorkspace().getWorkspaceId(),id)
            .orElseThrow(()->new AiPlatformException(ErrorCode.AI_RESOURCE_NOT_FOUND,"evaluation dataset not found"));}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("evaluation payload is invalid",e);}}
    private static final class Summary{private final int total,passed,failed;private Summary(int total,int passed){this.total=total;this.passed=passed;this.failed=total-passed;}
        public int getTotal(){return total;}public int getPassed(){return passed;}public int getFailed(){return failed;}}}
