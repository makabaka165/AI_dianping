package com.hmdp.ai.domain.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.domain.model.ModelProfile;
import com.hmdp.ai.domain.model.ModelProfileRepository;
import com.hmdp.ai.domain.model.ModelProfileVersion;
import com.hmdp.ai.domain.model.ModelProfileVersionRepository;
import com.hmdp.ai.domain.prompt.PromptRepository;
import com.hmdp.ai.domain.prompt.PromptVersion;
import com.hmdp.ai.domain.prompt.VersionStatus;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import com.hmdp.ai.shared.validation.ValidationIssue;
import com.hmdp.ai.shared.validation.ValidationResult;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class AgentPublishValidator {
    private final ModelProfileRepository modelProfiles;
    private final PromptRepository prompts;
    private final AgentDependencyInspector dependencies;
    private final JsonSchemaValidationService schemas;
    private final ObjectMapper objectMapper;
    private final ModelProfileVersionRepository modelVersions;

    public AgentPublishValidator(ModelProfileRepository modelProfiles, PromptRepository prompts,
                                 AgentDependencyInspector dependencies, JsonSchemaValidationService schemas,
                                 ObjectMapper objectMapper) {
        this(modelProfiles, null, prompts, dependencies, schemas, objectMapper);
    }

    @Autowired
    public AgentPublishValidator(ModelProfileRepository modelProfiles, ModelProfileVersionRepository modelVersions,
                                 PromptRepository prompts, AgentDependencyInspector dependencies,
                                 JsonSchemaValidationService schemas, ObjectMapper objectMapper) {
        this.modelProfiles = modelProfiles;
        this.modelVersions = modelVersions;
        this.prompts = prompts;
        this.dependencies = dependencies;
        this.schemas = schemas;
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(AgentVersion version) {
        List<ValidationIssue> issues = new ArrayList<>();
        issues.addAll(schemas.validateSchema(version.getInputSchema(), "inputSchema").getIssues());
        issues.addAll(schemas.validateSchema(version.getOutputSchema(), "outputSchema").getIssues());
        validateModel(version, issues);
        validatePrompt(version, issues);
        validateWorkflow(version, issues);
        validateTools(version, issues);
        validateKnowledge(version, issues);
        validateJsonObject(version.getMemoryPolicyJson(), "memoryPolicyJson", issues);
        validateJsonObject(version.getResponseRenderPolicyJson(), "responseRenderPolicyJson", issues);
        validateExecutionPolicy(version.getExecutionPolicyJson(), issues);
        return new ValidationResult(issues);
    }

    private void validateModel(AgentVersion version, List<ValidationIssue> issues) {
        if (modelVersions != null) {
            ModelProfileVersion snapshot = modelVersions.findById(version.getTenantId(), version.getWorkspaceId(),
                    version.getModelProfileVersionId()).orElse(null);
            if (snapshot == null) {
                issues.add(issue("AGENT_MODEL_VERSION_NOT_FOUND", "modelProfileVersionId",
                        "model profile version does not exist"));
                return;
            }
            if (!"PUBLISHED".equals(snapshot.getStatus()) && !"ARCHIVED".equals(snapshot.getStatus())) {
                issues.add(issue("AGENT_MODEL_VERSION_NOT_PUBLISHED", "modelProfileVersionId",
                        "model profile version is not published"));
            }
            if (!snapshot.getSecretRef().matches("env:[A-Z][A-Z0-9_]{1,127}")) {
                issues.add(issue("AGENT_MODEL_SECRET_INVALID", "modelProfileVersionId",
                        "model profile version must use an environment secret reference"));
            }
        }
        ModelProfile model = modelProfiles.findById(version.getTenantId(), version.getWorkspaceId(),
                        version.getModelProfileId()).orElse(null);
        if (model == null) {
            issues.add(issue("AGENT_MODEL_NOT_FOUND", "modelProfileId", "model profile does not exist"));
            return;
        }
        if (!model.isEnabled() || !"ACTIVE".equals(model.getStatus())) {
            issues.add(issue("AGENT_MODEL_DISABLED", "modelProfileId", "model profile is not active"));
        }
        if (!model.getSecretRef().matches("env:[A-Z][A-Z0-9_]{1,127}")) {
            issues.add(issue("AGENT_MODEL_SECRET_INVALID", "modelProfileId",
                    "model profile must use an environment secret reference"));
        }
    }

    private void validatePrompt(AgentVersion version, List<ValidationIssue> issues) {
        PromptVersion prompt = prompts.findVersionById(version.getTenantId(), version.getWorkspaceId(),
                        version.getPromptVersionId()).orElse(null);
        if (prompt == null) {
            issues.add(issue("AGENT_PROMPT_NOT_FOUND", "promptVersionId", "prompt version does not exist"));
        } else if (prompt.getStatus() != VersionStatus.PUBLISHED) {
            issues.add(issue("AGENT_PROMPT_NOT_PUBLISHED", "promptVersionId", "prompt version is not published"));
        }
    }

    private void validateWorkflow(AgentVersion version, List<ValidationIssue> issues) {
        DependencyStatus workflow = dependencies.workflow(version.getTenantId(), version.getWorkspaceId(),
                version.getWorkflowVersionId()).orElse(null);
        if (workflow == null) {
            issues.add(issue("AGENT_WORKFLOW_NOT_FOUND", "workflowVersionId", "workflow version does not exist"));
        } else if (!"PUBLISHED".equals(workflow.getStatus())) {
            issues.add(issue("AGENT_WORKFLOW_NOT_PUBLISHED", "workflowVersionId",
                    "workflow version is not published"));
        }
    }

    private void validateTools(AgentVersion version, List<ValidationIssue> issues) {
        List<DependencyStatus> tools = dependencies.tools(version.getTenantId(), version.getWorkspaceId(),
                version.getId());
        Set<String> seen = new HashSet<>();
        for (DependencyStatus tool : tools) {
            if (!seen.add(tool.getId())) {
                issues.add(issue("AGENT_TOOL_DUPLICATE", "toolBindings", "duplicate tool version: " + tool.getId()));
            }
            if (!tool.isExists()) {
                issues.add(issue("AGENT_TOOL_NOT_FOUND", "toolBindings", "tool version does not exist: " + tool.getId()));
                continue;
            }
            if (!tool.isEnabled() || !"PUBLISHED".equals(tool.getStatus())) {
                issues.add(issue("AGENT_TOOL_NOT_AVAILABLE", "toolBindings",
                        "tool version is not published and enabled: " + tool.getId()));
            }
            validatePermissions(tool.getMetadataJson(), tool.getId(), issues);
        }
    }

    private void validateKnowledge(AgentVersion version, List<ValidationIssue> issues) {
        for (DependencyStatus knowledge : dependencies.knowledgeBases(version.getTenantId(),
                version.getWorkspaceId(), version.getId())) {
            if (!knowledge.isExists()) {
                issues.add(issue("AGENT_KNOWLEDGE_NOT_FOUND", "knowledgeBindings",
                        "knowledge base version does not exist: " + knowledge.getId()));
                continue;
            }
            if (!"PUBLISHED".equals(knowledge.getStatus())) {
                issues.add(issue("AGENT_KNOWLEDGE_NOT_PUBLISHED", "knowledgeBindings",
                        "knowledge base version is not published: " + knowledge.getId()));
            }
            if (!"READY".equals(knowledge.getSecondaryStatus())) {
                issues.add(issue("AGENT_KNOWLEDGE_INDEX_NOT_READY", "knowledgeBindings",
                        "knowledge index is not ready: " + knowledge.getId()));
            }
        }
    }

    private void validatePermissions(String json, String toolId, List<ValidationIssue> issues) {
        try {
            JsonNode values = objectMapper.readTree(json == null ? "[]" : json);
            if (!values.isArray()) throw new IllegalArgumentException();
            for (JsonNode value : values) {
                if (!value.isTextual()) throw new IllegalArgumentException();
                AiPermission.valueOf(value.asText());
            }
        } catch (Exception e) {
            issues.add(issue("AGENT_TOOL_PERMISSION_INVALID", "toolBindings",
                    "tool permissions are invalid: " + toolId));
        }
    }

    private void validateExecutionPolicy(String json, List<ValidationIssue> issues) {
        JsonNode policy = validateJsonObject(json, "executionPolicyJson", issues);
        if (policy == null) return;
        for (String field : new String[]{"maxWorkflowNodes", "maxLoopIterations", "maxParallelism",
                "maxModelCalls", "maxToolCalls", "maxRunDurationSeconds"}) {
            if (!policy.has(field) || !policy.get(field).canConvertToInt() || policy.get(field).asInt() <= 0) {
                issues.add(issue("AGENT_BUDGET_INVALID", "executionPolicyJson." + field,
                        field + " must be a positive integer"));
            }
        }
    }

    private JsonNode validateJsonObject(String json, String path, List<ValidationIssue> issues) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) throw new IllegalArgumentException();
            return node;
        } catch (Exception e) {
            issues.add(issue("AGENT_JSON_INVALID", path, path + " must be a JSON object"));
            return null;
        }
    }

    private ValidationIssue issue(String code, String path, String message) {
        return new ValidationIssue(code, path, message);
    }
}
