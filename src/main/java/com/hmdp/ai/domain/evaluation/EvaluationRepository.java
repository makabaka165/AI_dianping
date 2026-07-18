package com.hmdp.ai.domain.evaluation;

import java.util.List;
import java.util.Optional;

public interface EvaluationRepository {
    EvaluationDataset createDataset(EvaluationDataset dataset,String actorId);
    Optional<EvaluationDataset> findDataset(String tenantId,String workspaceId,String datasetId);
    EvaluationCase createCase(EvaluationCase value,String actorId);
    List<EvaluationCase> findCases(String tenantId,String workspaceId,String datasetId);
    EvaluationRun createRun(EvaluationRun run,String actorId);
    void saveResults(String runId,List<EvaluationResult> results,String summaryJson,String actorId);
    Optional<EvaluationRun> findRun(String tenantId,String workspaceId,String runId);
    List<EvaluationResult> findResults(String tenantId,String workspaceId,String runId);
}
