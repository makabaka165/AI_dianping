package com.hmdp.ai.retrieval;

import com.hmdp.entity.DocumentMetadata;
import com.hmdp.entity.DocumentStatus;
import dev.langchain4j.data.document.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformPolicyVectorDocumentFactoryTest {

    @Test
    void importedDocumentIdShouldUseStableSourceKeyNotContent() {
        String sourceKey = "system-initial-import|text|classpath:/content/refund.md";

        String first = PlatformPolicyVectorDocumentFactory.importedDocumentId(sourceKey);
        String second = PlatformPolicyVectorDocumentFactory.importedDocumentId(sourceKey);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void vectorMetadataShouldChangeContentHashWhenContentChangesUnderSameDocumentId() {
        DocumentMetadata metadata = new DocumentMetadata();
        metadata.setId("doc-1");
        metadata.setStatus(DocumentStatus.PUBLISHED);

        Document oldDocument = PlatformPolicyVectorDocumentFactory.toDocument("doc-1", Document.from("old policy"), metadata);
        Document newDocument = PlatformPolicyVectorDocumentFactory.toDocument("doc-1", Document.from("new policy"), metadata);

        assertThat(oldDocument.metadata().getString(PlatformPolicyVectorDocumentFactory.META_DOCUMENT_ID)).isEqualTo("doc-1");
        assertThat(oldDocument.metadata().getString(PlatformPolicyVectorDocumentFactory.META_CONTENT_HASH))
                .isNotEqualTo(newDocument.metadata().getString(PlatformPolicyVectorDocumentFactory.META_CONTENT_HASH));
    }
}
