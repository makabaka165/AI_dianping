package com.hmdp.ai.api.evaluation;

import com.hmdp.ai.api.security.RequireAiPermission;
import com.hmdp.ai.application.dto.evaluation.CreateEvaluationCaseRequest;
import com.hmdp.ai.application.dto.evaluation.CreateEvaluationDatasetRequest;
import com.hmdp.ai.application.dto.evaluation.CreateEvaluationRunRequest;
import com.hmdp.ai.application.dto.evaluation.EvaluationRunResponse;
import com.hmdp.ai.application.evaluation.EvaluationApplicationService;
import com.hmdp.ai.domain.evaluation.EvaluationCase;
import com.hmdp.ai.domain.evaluation.EvaluationDataset;
import com.hmdp.ai.domain.security.AiPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1")
public class EvaluationController {
    private final EvaluationApplicationService evaluation;

    public EvaluationController(EvaluationApplicationService evaluation) {
        this.evaluation = evaluation;
    }

    @PostMapping("/evaluation-datasets")
    @RequireAiPermission(AiPermission.EVALUATION_MANAGE)
    public EvaluationDataset dataset(@Valid @RequestBody CreateEvaluationDatasetRequest request) {
        return evaluation.createDataset(request);
    }

    @PostMapping("/evaluation-datasets/{id}/cases")
    @RequireAiPermission(AiPermission.EVALUATION_MANAGE)
    public EvaluationCase evaluationCase(@PathVariable @Size(max = 64) String id,
                                         @Valid @RequestBody CreateEvaluationCaseRequest request) {
        return evaluation.createCase(id, request);
    }

    @PostMapping("/evaluation-runs")
    @RequireAiPermission(AiPermission.EVALUATION_RUN)
    public EvaluationRunResponse run(@Valid @RequestBody CreateEvaluationRunRequest request) {
        return evaluation.run(request);
    }

    @GetMapping("/evaluation-runs/{id}")
    @RequireAiPermission(AiPermission.EVALUATION_RUN)
    public EvaluationRunResponse get(@PathVariable @Size(max = 64) String id) {
        return evaluation.get(id);
    }
}
