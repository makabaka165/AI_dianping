package com.hmdp.ai.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.run.AgentRunOutput;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import com.hmdp.ai.shared.validation.ValidationResult;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class AgentOutputValidator {
    private final ObjectMapper objectMapper;
    private final JsonSchemaValidationService schemas;

    public AgentOutputValidator(ObjectMapper objectMapper, JsonSchemaValidationService schemas) {
        this.objectMapper = objectMapper;
        this.schemas = schemas;
    }

    public void validate(String outputSchema, AgentRunOutput output) {
        JsonNode value = objectMapper.valueToTree(output);
        ValidationResult validation = schemas.validateValue(outputSchema, value, "output");
        if (!validation.isValid()) {
            throw new AiPlatformException(ErrorCode.AI_OUTPUT_SCHEMA_INVALID,
                    "agent output does not match the published schema", validation.getIssues());
        }
    }
}
