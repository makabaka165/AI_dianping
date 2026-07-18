package com.hmdp.ai.domain.evaluation;
import com.fasterxml.jackson.databind.ObjectMapper;import com.hmdp.ai.shared.validation.JsonSchemaValidationService;import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class EvaluationMetricEngineTest {@Test void calculatesDeterministicAgentAndRagMetrics()throws Exception{ObjectMapper mapper=new ObjectMapper();
    EvaluationMetricEngine engine=new EvaluationMetricEngine(mapper,new JsonSchemaValidationService(mapper));
    EvaluationCase c=new EvaluationCase("case","t","w","dataset","case","{}","{\"answer\":\"ok\"}",
            "{\"keywords\":[\"ok\"],\"expectedIntent\":\"SHOP_QA\",\"expectedTool\":\"ask-shop\",\"expectedEvidenceIds\":[\"e1\",\"e2\"],\"thresholds\":{\"citationCoverage\":1,\"intentAccuracy\":1}}","ACTIVE");
    EvaluationCandidate candidate=new EvaluationCandidate(mapper.readTree("{\"answer\":\"ok\",\"intent\":\"SHOP_QA\",\"selectedTool\":\"ask-shop\",\"evidenceIds\":[\"e2\",\"e1\"],\"citationIds\":[\"e1\",\"e2\"],\"grounded\":true}"),120,10,20,1,1,.01,true);
    MetricEvaluation result=engine.evaluate(c,candidate);assertThat(result.isPassed()).isTrue();assertThat(result.getMetrics()).containsEntry("recallAtK",1d).containsEntry("mrr",1d).containsEntry("citationPrecision",1d).containsEntry("tokenUsage",30d);}}
