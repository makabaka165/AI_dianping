package com.hmdp.ai.application.dto.evaluation;
import com.hmdp.ai.domain.evaluation.*;import java.util.List;
public final class EvaluationRunResponse {private final EvaluationRun run;private final List<EvaluationResult>results;
    public EvaluationRunResponse(EvaluationRun run,List<EvaluationResult>results){this.run=run;this.results=results;}
    public EvaluationRun getRun(){return run;}public List<EvaluationResult>getResults(){return results;}}
