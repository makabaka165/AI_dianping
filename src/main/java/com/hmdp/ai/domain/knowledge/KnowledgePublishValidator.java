package com.hmdp.ai.domain.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.shared.validation.ValidationIssue;
import com.hmdp.ai.shared.validation.ValidationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class KnowledgePublishValidator {
    private final ObjectMapper mapper;

    public KnowledgePublishValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ValidationResult validate(KnowledgeBaseVersion version, boolean embeddingModelUsable,
                                     long incompleteJobs) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (version == null) {
            issues.add(issue("KNOWLEDGE_VERSION_REQUIRED", "version", "knowledge version is required"));
            return new ValidationResult(issues);
        }
        if (!"DRAFT".equals(version.getStatus())) {
            issues.add(issue("KNOWLEDGE_VERSION_NOT_DRAFT", "status", "only a draft version can be published"));
        }
        if (!embeddingModelUsable) {
            issues.add(issue("KNOWLEDGE_EMBEDDING_MODEL_UNAVAILABLE", "embeddingModelProfileId",
                    "embedding model must exist, be enabled and have EMBEDDING type"));
        }
        if (version.getEmbeddingDimension() <= 0) {
            issues.add(issue("KNOWLEDGE_EMBEDDING_DIMENSION_INVALID", "embeddingDimension",
                    "embedding dimension must be positive"));
        }
        validateChunking(version.getChunkingPolicyJson(), issues);
        validateRetrieval(version.getRetrievalPolicyJson(), issues);
        if (incompleteJobs > 0) {
            issues.add(issue("KNOWLEDGE_INGESTION_INCOMPLETE", "ingestionJobs",
                    "all ingestion jobs for this version must complete before publishing"));
        }
        return new ValidationResult(issues);
    }

    private void validateChunking(String json, List<ValidationIssue> issues) {
        try {
            ChunkingPolicy.parse(json, mapper);
        } catch (Exception e) {
            issues.add(issue("KNOWLEDGE_CHUNKING_POLICY_INVALID", "chunkingPolicyJson", e.getMessage()));
        }
    }

    private void validateRetrieval(String json, List<ValidationIssue> issues) {
        try {
            JsonNode value = mapper.readTree(json);
            if (value == null || !value.isObject()) {
                issues.add(issue("KNOWLEDGE_RETRIEVAL_POLICY_INVALID", "retrievalPolicyJson",
                        "retrieval policy must be a JSON object"));
                return;
            }
            for (String field : new String[]{"vectorTopN", "lexicalTopN", "finalTopK"}) {
                if (value.has(field) && value.path(field).asInt(0) <= 0) {
                    issues.add(issue("KNOWLEDGE_RETRIEVAL_LIMIT_INVALID", "retrievalPolicyJson." + field,
                            field + " must be positive"));
                }
            }
        } catch (Exception e) {
            issues.add(issue("KNOWLEDGE_RETRIEVAL_POLICY_INVALID", "retrievalPolicyJson",
                    "retrieval policy is invalid JSON"));
        }
    }

    private ValidationIssue issue(String code, String path, String message) {
        return new ValidationIssue(code, path, message == null ? code : message);
    }
}
