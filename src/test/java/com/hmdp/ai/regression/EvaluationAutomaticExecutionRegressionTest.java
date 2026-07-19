package com.hmdp.ai.regression;

import com.hmdp.ai.application.dto.evaluation.CreateEvaluationRunRequest;
import org.junit.jupiter.api.Test;

import javax.validation.Validation;
import javax.validation.Validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationAutomaticExecutionRegressionTest {
    @Test
    void evaluationRunRequestMustNotRequireCallerSubmittedActuals() {
        CreateEvaluationRunRequest request = new CreateEvaluationRunRequest();
        request.setDatasetId("dataset");
        request.setTargetType("AGENT");
        request.setTargetId("agent");
        request.setTargetVersion(1);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertEquals(0, validator.validate(request).size(),
                "the evaluator must execute cases instead of requiring candidate actual outputs");
        assertTrue(request.getCandidates().isEmpty());
    }
}
