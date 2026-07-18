package com.hmdp.ai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.application.dto.agent.AgentRunRequest;
import com.hmdp.ai.application.dto.agent.AttachmentRequest;
import com.hmdp.ai.application.dto.agent.InputPartRequest;
import com.hmdp.ai.application.dto.agent.InputPartType;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.ai.domain.agent.PublishedAgentDefinition;
import com.hmdp.ai.shared.validation.JsonSchemaValidationService;
import com.hmdp.ai.shared.validation.ValidationIssue;
import com.hmdp.ai.shared.validation.ValidationResult;
import com.hmdp.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class AgentRunRequestValidator {
    private final ObjectMapper objectMapper;
    private final JsonSchemaValidationService schemas;

    public AgentRunRequestValidator(ObjectMapper objectMapper, JsonSchemaValidationService schemas) {
        this.objectMapper = objectMapper;
        this.schemas = schemas;
    }

    public void validate(PublishedAgentDefinition definition, AgentRunRequest request) {
        JsonNode input = objectMapper.valueToTree(request.getInput());
        ValidationResult schemaValidation = schemas.validateValue(definition.getVersion().getInputSchema(),
                input, "input");
        if (!schemaValidation.isValid()) {
            throw new AiPlatformException(ErrorCode.AI_INPUT_SCHEMA_INVALID,
                    "agent input does not match the published schema", schemaValidation.getIssues());
        }
        List<ValidationIssue> issues = new ArrayList<>();
        validateMetadata(request, issues);
        validateReferences(request, issues);
        validateAttachments(request, issues);
        validateParts(request, issues);
        validateCapabilities(definition, request, issues);
        if (!issues.isEmpty()) {
            throw new AiPlatformException(ErrorCode.PARAM_ERROR, "agent run request is invalid", issues);
        }
    }

    private void validateMetadata(AgentRunRequest request, List<ValidationIssue> issues) {
        try {
            byte[] value = objectMapper.writeValueAsBytes(request.getMetadata());
            if (value.length > 16 * 1024) {
                issues.add(issue("RUN_METADATA_TOO_LARGE", "metadata", "metadata cannot exceed 16 KiB"));
            }
        } catch (Exception e) {
            issues.add(issue("RUN_METADATA_INVALID", "metadata", "metadata cannot be serialized"));
        }
    }

    private void validateReferences(AgentRunRequest request, List<ValidationIssue> issues) {
        int index = 0;
        for (String value : request.getInput().getReferenceUris()) {
            validateReferenceUri(value, "input.referenceUris[" + index++ + "]", issues);
        }
    }

    private void validateAttachments(AgentRunRequest request, List<ValidationIssue> issues) {
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (AttachmentRequest attachment : request.getInput().getAttachments()) {
            String path = "input.attachments[" + index++ + "]";
            if (!ids.add(attachment.getAttachmentId())) {
                issues.add(issue("ATTACHMENT_DUPLICATE", path + ".attachmentId",
                        "attachmentId must be unique"));
            }
            if (attachment.getName().contains("..") || attachment.getName().contains("/")
                    || attachment.getName().contains("\\")) {
                issues.add(issue("ATTACHMENT_NAME_UNSAFE", path + ".name",
                        "attachment name cannot contain path segments"));
            }
            validateReferenceUri(attachment.getUri(), path + ".uri", issues);
        }
    }

    private void validateParts(AgentRunRequest request, List<ValidationIssue> issues) {
        int index = 0;
        for (InputPartRequest part : request.getInput().getParts()) {
            String path = "input.parts[" + index++ + "]";
            if (part.getType() == InputPartType.REFERENCE_URI || part.getType() == InputPartType.FILE
                    || part.getType() == InputPartType.IMAGE) {
                if (part.getUri() == null || part.getUri().trim().isEmpty()) {
                    issues.add(issue("INPUT_PART_URI_REQUIRED", path + ".uri",
                            "this input part type requires a URI"));
                } else {
                    validateReferenceUri(part.getUri(), path + ".uri", issues);
                }
            }
            if (part.getType() == InputPartType.TEXT && (part.getText() == null || part.getText().trim().isEmpty())) {
                issues.add(issue("INPUT_PART_TEXT_REQUIRED", path + ".text", "text input part cannot be empty"));
            }
        }
    }

    private void validateCapabilities(PublishedAgentDefinition definition, AgentRunRequest request,
                                      List<ValidationIssue> issues) {
        boolean hasImage = request.getInput().getParts().stream().anyMatch(part -> part.getType() == InputPartType.IMAGE)
                || request.getInput().getAttachments().stream()
                .anyMatch(attachment -> attachment.getContentType().toLowerCase().startsWith("image/"));
        if (!hasImage) return;
        try {
            boolean vision = objectMapper.readTree(definition.getModelProfile().getCapabilitiesJson())
                    .path("vision").asBoolean(false);
            if (!vision) {
                issues.add(issue("MODEL_CAPABILITY_MISMATCH", "input",
                        "the published model profile does not support image input"));
            }
        } catch (Exception e) {
            issues.add(issue("MODEL_CAPABILITY_INVALID", "modelProfileId",
                    "model capabilities are invalid"));
        }
    }

    private void validateReferenceUri(String value, String path, List<ValidationIssue> issues) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "kb".equalsIgnoreCase(scheme)
                    || "s3".equalsIgnoreCase(scheme))) {
                issues.add(issue("REFERENCE_URI_SCHEME_DENIED", path,
                        "reference URI scheme must be https, kb or s3"));
            }
            if ("https".equalsIgnoreCase(scheme) && uri.getHost() == null) {
                issues.add(issue("REFERENCE_URI_HOST_REQUIRED", path, "HTTPS reference URI requires a host"));
            }
            if (uri.getUserInfo() != null) {
                issues.add(issue("REFERENCE_URI_USERINFO_DENIED", path,
                        "reference URI cannot contain user information"));
            }
        } catch (Exception e) {
            issues.add(issue("REFERENCE_URI_INVALID", path, "reference URI is invalid"));
        }
    }

    private ValidationIssue issue(String code, String path, String message) {
        return new ValidationIssue(code, path, message);
    }
}
