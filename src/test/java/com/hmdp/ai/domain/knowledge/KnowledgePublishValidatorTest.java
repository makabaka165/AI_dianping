package com.hmdp.ai.domain.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.shared.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgePublishValidatorTest {
    private final KnowledgePublishValidator validator = new KnowledgePublishValidator(new ObjectMapper());

    @Test
    void rejectsUnavailableModelAndIncompleteIngestion() {
        KnowledgeBaseVersion version = version("DRAFT", 1024,
                "{\"strategy\":\"RECURSIVE\",\"maxChars\":800,\"minChars\":100,\"overlapChars\":80}",
                "{\"vectorTopN\":20,\"lexicalTopN\":20,\"finalTopK\":8}");

        ValidationResult result = validator.validate(version, false, 2);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getIssues()).extracting("code").contains(
                "KNOWLEDGE_EMBEDDING_MODEL_UNAVAILABLE", "KNOWLEDGE_INGESTION_INCOMPLETE");
    }

    @Test
    void acceptsReadyDraftConfiguration() {
        KnowledgeBaseVersion version = version("DRAFT", 1024,
                "{\"strategy\":\"HEADING_AWARE\",\"maxChars\":800,\"minChars\":100,\"overlapChars\":80}",
                "{\"vectorTopN\":20,\"lexicalTopN\":20,\"finalTopK\":8}");

        assertThat(validator.validate(version, true, 0).isValid()).isTrue();
    }

    private KnowledgeBaseVersion version(String status, int dimension, String chunking, String retrieval) {
        return new KnowledgeBaseVersion("id", "tenant", "workspace", "kb", 1, "embedding", dimension,
                chunking, retrieval, "index", "READY", status);
    }
}
