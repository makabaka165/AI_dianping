package com.hmdp.ai.api.evaluation;
import com.hmdp.ai.api.security.RequireAiPermission;import com.hmdp.ai.application.dto.evaluation.*;import com.hmdp.ai.application.evaluation.EvaluationApplicationService;
import com.hmdp.ai.domain.evaluation.*;import com.hmdp.ai.domain.security.AiPermission;import org.springframework.web.bind.annotation.*;import javax.validation.Valid;import javax.validation.constraints.Size;
@RestController @RequestMapping("/api/v1") public class EvaluationController {private final EvaluationApplicationService evaluation;
    public EvaluationController(EvaluationApplicationService evaluation){this.evaluation=evaluation;}
    @PostMapping("/evaluation-datasets")@RequireAiPermission(AiPermission.EVALUATION_RUN)public EvaluationDataset dataset(@Valid@RequestBody CreateEvaluationDatasetRequest request){return evaluation.createDataset(request);}
    @PostMapping("/evaluation-datasets/{id}/cases")@RequireAiPermission(AiPermission.EVALUATION_RUN)public EvaluationCase evalCase(@PathVariable@Size(max=64)String id,@Valid@RequestBody CreateEvaluationCaseRequest request){return evaluation.createCase(id,request);}
    @PostMapping("/evaluation-runs")@RequireAiPermission(AiPermission.EVALUATION_RUN)public EvaluationRunResponse run(@Valid@RequestBody CreateEvaluationRunRequest request){return evaluation.run(request);}
    @GetMapping("/evaluation-runs/{id}")@RequireAiPermission(AiPermission.EVALUATION_RUN)public EvaluationRunResponse get(@PathVariable@Size(max=64)String id){return evaluation.get(id);}}
