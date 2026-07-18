package com.hmdp.ai.domain.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import com.hmdp.ai.shared.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptPublishValidatorTest {

    @Test
    void acceptsDeclaredPlaceholdersAndJsonSchemas() {
        PromptPublishValidator validator = validator();
        PromptVersion version = version("Use {{shopId}}", "{\"type\":\"object\",\"properties\":{\"shopId\":{\"type\":\"integer\"}}}");

        assertTrue(validator.validate(version).isValid());
    }

    @Test
    void rejectsUndeclaredPlaceholderAndNonArrayExamples() {
        PromptPublishValidator validator = validator();
        PromptVersion source = version("Use {{unknown}}", "{\"type\":\"object\",\"properties\":{}}");
        PromptVersion invalid = new PromptVersion(source.getId(), source.getTenantId(), source.getWorkspaceId(),
                source.getPromptId(), source.getVersion(), source.getSystemPrompt(), source.getTaskPrompt(),
                source.getToolInstruction(), source.getRetrievalInstruction(), source.getOutputInstruction(),
                source.getVariablesSchema(), source.getInputSchema(), source.getOutputSchema(), "{}",
                source.getStatus(), source.getContentHash(), source.getChangeNote(), null, null, null);

        ValidationResult result = validator.validate(invalid);

        assertFalse(result.isValid());
        assertTrue(result.getIssues().stream().anyMatch(issue -> "PROMPT_VARIABLE_UNBOUND".equals(issue.getCode())));
        assertTrue(result.getIssues().stream().anyMatch(issue -> "PROMPT_EXAMPLES_NOT_ARRAY".equals(issue.getCode())));
    }

    private PromptPublishValidator validator() {
        ObjectMapper mapper = new ObjectMapper();
        return new PromptPublishValidator(new JsonSchemaValidationService(mapper), mapper);
    }

    private PromptVersion version(String task, String variablesSchema) {
        return new PromptVersion("v", "tenant", "workspace", "prompt", 1, "system", task,
                null, null, null, variablesSchema, "{\"type\":\"object\"}",
                "{\"type\":\"object\"}", "[]", VersionStatus.DRAFT, "hash", "change",
                null, null, null);
    }
}
