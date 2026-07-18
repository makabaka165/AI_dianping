package com.hmdp.ai.domain.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import com.hmdp.ai.shared.validation.ValidationIssue;
import com.hmdp.ai.shared.validation.ValidationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PromptPublishValidator {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_.-]{0,127})\\s*}}" );
    private final JsonSchemaValidationService schemas;
    private final ObjectMapper objectMapper;

    public PromptPublishValidator(JsonSchemaValidationService schemas, ObjectMapper objectMapper) {
        this.schemas = schemas;
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(PromptVersion version) {
        List<ValidationIssue> issues = new ArrayList<>();
        issues.addAll(schemas.validateSchema(version.getVariablesSchema(), "variablesSchema").getIssues());
        issues.addAll(schemas.validateSchema(version.getInputSchema(), "inputSchema").getIssues());
        issues.addAll(schemas.validateSchema(version.getOutputSchema(), "outputSchema").getIssues());
        Set<String> variables = variables(version.getVariablesSchema(), issues);
        validateExamples(version.getExamplesJson(), issues);
        validatePlaceholders("systemPrompt", version.getSystemPrompt(), variables, issues);
        validatePlaceholders("taskPrompt", version.getTaskPrompt(), variables, issues);
        validatePlaceholders("toolInstruction", version.getToolInstruction(), variables, issues);
        validatePlaceholders("retrievalInstruction", version.getRetrievalInstruction(), variables, issues);
        validatePlaceholders("outputInstruction", version.getOutputInstruction(), variables, issues);
        return new ValidationResult(issues);
    }

    private Set<String> variables(String variablesSchema, List<ValidationIssue> issues) {
        Set<String> result = new HashSet<>();
        try {
            JsonNode properties = objectMapper.readTree(variablesSchema).path("properties");
            if (!properties.isObject()) {
                issues.add(new ValidationIssue("PROMPT_VARIABLE_PROPERTIES_REQUIRED", "variablesSchema.properties",
                        "variablesSchema must declare a properties object"));
                return result;
            }
            properties.fieldNames().forEachRemaining(result::add);
        } catch (Exception ignored) {
            // Schema syntax is reported by JsonSchemaValidationService.
        }
        return result;
    }

    private void validateExamples(String examplesJson, List<ValidationIssue> issues) {
        try {
            if (!objectMapper.readTree(examplesJson).isArray()) {
                issues.add(new ValidationIssue("PROMPT_EXAMPLES_NOT_ARRAY", "examplesJson",
                        "examplesJson must be a JSON array"));
            }
        } catch (Exception e) {
            issues.add(new ValidationIssue("PROMPT_EXAMPLES_INVALID", "examplesJson",
                    "examplesJson must be valid JSON"));
        }
    }

    private void validatePlaceholders(String path, String text, Set<String> variables,
                                      List<ValidationIssue> issues) {
        if (text == null) return;
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String root = placeholder.contains(".") ? placeholder.substring(0, placeholder.indexOf('.')) : placeholder;
            if (!variables.contains(root)) {
                issues.add(new ValidationIssue("PROMPT_VARIABLE_UNBOUND", path,
                        "placeholder is not declared in variablesSchema: " + placeholder));
            }
        }
    }
}
