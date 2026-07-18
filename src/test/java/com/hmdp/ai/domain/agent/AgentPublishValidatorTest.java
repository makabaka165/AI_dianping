package com.hmdp.ai.domain.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelProfileRepository;
import com.hmdp.ai.domain.model.ModelType;
import com.hmdp.ai.domain.prompt.PromptRepository;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import com.hmdp.ai.shared.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPublishValidatorTest {
    private ModelProfileRepository models;
    private PromptRepository prompts;
    private AgentDependencyInspector dependencies;
    private AgentPublishValidator validator;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        models = mock(ModelProfileRepository.class);
        prompts = mock(PromptRepository.class);
        dependencies = mock(AgentDependencyInspector.class);
        validator = new AgentPublishValidator(models, prompts, dependencies,
                new JsonSchemaValidationService(mapper), mapper);
        when(models.findById(anyString(), anyString(), anyString())).thenReturn(Optional.of(model(true)));
        when(prompts.findVersionById(anyString(), anyString(), anyString())).thenReturn(Optional.of(prompt()));
        when(dependencies.workflow(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new DependencyStatus("workflow-v1", true, "PUBLISHED", true, null, null)));
        when(dependencies.tools(anyString(), anyString(), anyString())).thenReturn(Collections.emptyList());
        when(dependencies.knowledgeBases(anyString(), anyString(), anyString())).thenReturn(Collections.emptyList());
    }

    @Test
    void acceptsCompletePublishedDependenciesAndPositiveBudget() {
        ValidationResult result = validator.validate(version(validPolicy()));

        assertTrue(result.isValid());
    }

    @Test
    void reportsDisabledModelUnpublishedWorkflowAndInvalidBudgetTogether() {
        when(models.findById(anyString(), anyString(), anyString())).thenReturn(Optional.of(model(false)));
        when(dependencies.workflow(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new DependencyStatus("workflow-v1", true, "DRAFT", true, null, null)));

        ValidationResult result = validator.validate(version("{\"maxWorkflowNodes\":0}"));

        assertFalse(result.isValid());
        assertTrue(result.getIssues().stream().anyMatch(issue -> "AGENT_MODEL_DISABLED".equals(issue.getCode())));
        assertTrue(result.getIssues().stream().anyMatch(issue -> "AGENT_WORKFLOW_NOT_PUBLISHED".equals(issue.getCode())));
        assertTrue(result.getIssues().stream().anyMatch(issue -> "AGENT_BUDGET_INVALID".equals(issue.getCode())));
    }

    private AgentVersion version(String executionPolicy) {
        return new AgentVersion("agent-v1", "tenant", "workspace", "agent", 1, "Agent", "description",
                "model", "prompt-v1", "workflow-v1", "{}",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", executionPolicy, "{}",
                VersionStatus.DRAFT, "hash", "change", null, null, null);
    }

    private String validPolicy() {
        return "{\"maxWorkflowNodes\":64,\"maxLoopIterations\":5,\"maxParallelism\":4," +
                "\"maxModelCalls\":8,\"maxToolCalls\":16,\"maxRunDurationSeconds\":120}";
    }

    private ModelProfile model(boolean enabled) {
        return new ModelProfile("model", "tenant", "workspace", "model", "Model", "provider", "name",
                "https://provider.example/v1", "env:AI_CHAT_API_KEY", ModelType.CHAT,
                "{\"streaming\":true,\"toolCalling\":true,\"jsonSchema\":true,\"vision\":false,\"longContext\":true}",
                "{}", 32000, 1000, 30000, "{}", null, BigDecimal.ZERO, BigDecimal.ZERO,
                enabled, 1, "ACTIVE", null, null);
    }

    private PromptVersion prompt() {
        return new PromptVersion("prompt-v1", "tenant", "workspace", "prompt", 1,
                "system", "task", null, null, null, "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", "[]",
                VersionStatus.PUBLISHED, "hash", "change", null, "user", null);
    }
}
