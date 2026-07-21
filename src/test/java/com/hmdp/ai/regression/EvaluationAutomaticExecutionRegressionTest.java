package com.hmdp.ai.regression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.evaluation.CreateEvaluationRunRequest;
import org.junit.jupiter.api.Test;

import javax.validation.Validation;
import javax.validation.Validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        assertFalse(new ObjectMapper().valueToTree(request).has("candidates"),
                "caller-supplied actual outputs must not be part of the evaluation run contract");
    }
}
