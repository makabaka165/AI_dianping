package com.hmdp.ai.shared.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class JsonSchemaValidationService {
    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    public JsonSchemaValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationResult validateSchema(String schemaText, String path) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (schemaText == null || schemaText.trim().isEmpty()) {
            issues.add(new ValidationIssue("SCHEMA_REQUIRED", path, "JSON Schema is required"));
            return new ValidationResult(issues);
        }
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaText);
            if (!schemaNode.isObject()) {
                issues.add(new ValidationIssue("SCHEMA_NOT_OBJECT", path, "JSON Schema must be an object"));
                return new ValidationResult(issues);
            }
            schemaFactory.getSchema(schemaNode);
        } catch (Exception e) {
            issues.add(new ValidationIssue("SCHEMA_INVALID", path, "JSON Schema is invalid"));
        }
        return new ValidationResult(issues);
    }

    public ValidationResult validateValue(String schemaText, JsonNode value, String path) {
        List<ValidationIssue> issues = new ArrayList<>(validateSchema(schemaText, path).getIssues());
        if (!issues.isEmpty()) return new ValidationResult(issues);
        try {
            JsonSchema schema = schemaFactory.getSchema(objectMapper.readTree(schemaText));
            Set<ValidationMessage> messages = schema.validate(value);
            messages.stream()
                    .sorted(Comparator.comparing(ValidationMessage::getPath)
                            .thenComparing(ValidationMessage::getMessage))
                    .forEach(message -> issues.add(new ValidationIssue(
                            "SCHEMA_VALIDATION_FAILED", path + message.getPath(), message.getMessage())));
        } catch (Exception e) {
            issues.add(new ValidationIssue("SCHEMA_VALIDATION_ERROR", path, "Schema validation failed"));
        }
        return new ValidationResult(issues);
    }
}
